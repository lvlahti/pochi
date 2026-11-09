package io.github.lvlahti.pochi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Android's one-use MediaProjection authorization is consumed only after the FGS starts. */
class CaptureService : Service() {
    private var server: StreamServer? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var codecSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var drainThread: Thread? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (running) return START_NOT_STICKY
                running = true
                stopping = false
                val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_H264
                try {
                    startForegroundNotification()
                    val usb = UsbNetwork.findAddress()
                        ?: error("No USB tether network found. Enable USB tethering first.")
                    server = StreamServer(this, usb, mode).also { it.start() }
                    val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                    @Suppress("DEPRECATION")
                    val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                        ?: error("Missing screen-capture consent")
                    val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    projection = manager.getMediaProjection(code, data)
                        ?: error("Android denied screen capture")
                    projection!!.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            if (!stopping) Handler(Looper.getMainLooper()).post { stopSelf() }
                        }
                    }, Handler(Looper.getMainLooper()))

                    val (width, height, dpi) = captureSize(if (mode == MODE_H264) 1280 else 960)
                    if (mode == MODE_MJPEG) startMjpeg(width, height, dpi)
                    else startH264(width, height, dpi)
                    notifyStatus("Sharing (${if (mode == MODE_H264) "H.264" else "MJPEG"}) — open this URL on Windows", server?.url, true)
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot start capture", e)
                    notifyStatus("Error: ${e.message ?: "capture could not start"}", null, false)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY // never restart with a stale MediaProjection token
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Pochi sharing",
            NotificationManager.IMPORTANCE_LOW))
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, CaptureService::class.java).apply { action = ACTION_STOP }
        val stopAction = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pochi is sharing your screen")
            .setContentText("Tap to open Pochi and stop sharing")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, "Stop", stopAction).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun captureSize(limit: Int): Triple<Int, Int, Int> {
        val metrics = resources.displayMetrics
        val scale = (limit.toFloat() / max(metrics.widthPixels, metrics.heightPixels))
            .coerceAtMost(1f)
        // Surface-input video encoders on older phones often prefer 16-aligned dimensions.
        val width = (((metrics.widthPixels * scale).roundToInt() + 15) / 16) * 16
        val height = (((metrics.heightPixels * scale).roundToInt() + 15) / 16) * 16
        return Triple(width.coerceAtLeast(16), height.coerceAtLeast(16),
            (metrics.densityDpi * scale).roundToInt().coerceAtLeast(1))
    }

    private fun startH264(width: Int, height: Int, dpi: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 24)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec = encoder
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        codecSurface = surface
        encoder.start()
        display = projection!!.createVirtualDisplay("Pochi H.264", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        drainThread = Thread({ drainEncoder(encoder, width, height) }, "Pochi-H264").also { it.start() }
    }

    private fun drainEncoder(encoder: MediaCodec, width: Int, height: Int) {
        val info = MediaCodec.BufferInfo()
        var muxer: H264Mp4Muxer? = null
        try {
            while (!stopping) {
                when (val slot = encoder.dequeueOutputBuffer(info, 20_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = encoder.outputFormat
                        fun getNal(name: String, type: Int): ByteArray? {
                            val buffer = output.getByteBuffer(name) ?: return null
                            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                            return AvcNal.split(bytes).firstOrNull { it.isNotEmpty() && (it[0].toInt() and 31) == type }
                        }
                        val sps = getNal("csd-0", 7) ?: error("Encoder did not provide SPS")
                        val pps = getNal("csd-1", 8) ?: error("Encoder did not provide PPS")
                        muxer = H264Mp4Muxer(width, height, sps, pps)
                        server?.setH264Init(
                            "video/mp4; codecs=\"${muxer.codecString}\"", muxer.initSegment())
                    }
                    in 0..Int.MAX_VALUE -> {
                        val buffer = encoder.getOutputBuffer(slot)
                        if (buffer != null && info.size > 0 &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            muxer?.offerFrame(bytes, info.presentationTimeUs,
                                info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                                ?.forEach { server?.publishFragment(it) }
                        }
                        encoder.releaseOutputBuffer(slot, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (e: Exception) {
            if (!stopping) {
                Log.e(TAG, "H.264 encoding failed", e)
                Handler(Looper.getMainLooper()).post {
                    notifyStatus("Error: H.264 encoder failed. Try MJPEG mode.", null, false)
                    stopSelf()
                }
            }
        } finally {
            muxer?.finish()?.let { server?.publishFragment(it) }
        }
    }

    private fun startMjpeg(width: Int, height: Int, dpi: Int) {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        val thread = HandlerThread("Pochi-MJPEG").also { it.start() }
        imageThread = thread
        var lastFrameAt = 0L
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrameAt < 100) return@setOnImageAvailableListener
                lastFrameAt = now
                val plane = image.planes.firstOrNull() ?: return@setOnImageAvailableListener
                val buffer = plane.buffer
                val paddedWidth = width + (plane.rowStride - width * plane.pixelStride) / plane.pixelStride
                val rawBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                rawBitmap.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, width, height)
                val out = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, 70, out)
                server?.publishJpeg(out.toByteArray())
                cropped.recycle()
                rawBitmap.recycle()
            } catch (e: Exception) {
                Log.w(TAG, "Dropped MJPEG frame", e)
            } finally {
                image.close()
            }
        }, Handler(thread.looper))
        display = projection!!.createVirtualDisplay("Pochi MJPEG", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)
    }

    private fun notifyStatus(message: String, link: String?, active: Boolean) {
        status = message
        url = link
        running = active
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).apply {
            putExtra(EXTRA_STATUS, message)
            putExtra(EXTRA_URL, link)
            putExtra(EXTRA_RUNNING, active)
        })
    }

    override fun onDestroy() {
        stopping = true
        runCatching { display?.release() }
        runCatching { imageReader?.close() }
        runCatching { imageThread?.quitSafely() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { codecSurface?.release() }
        drainThread?.interrupt()
        runCatching { projection?.stop() }
        server?.close()
        if (!status.startsWith("Error:")) notifyStatus("Not sharing", null, false)
        else running = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PochiCapture"
        private const val CHANNEL_ID = "pochi_media_projection"
        private const val NOTIFICATION_ID = 41
        const val ACTION_START = "io.github.lvlahti.pochi.START"
        const val ACTION_STOP = "io.github.lvlahti.pochi.STOP"
        const val ACTION_STATUS = "io.github.lvlahti.pochi.STATUS"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_STATUS = "status"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_URL = "url"
        const val MODE_H264 = "h264"
        const val MODE_MJPEG = "mjpeg"
        @Volatile var status: String = "Not sharing"
            private set
        @Volatile var url: String? = null
            private set
        @Volatile var running: Boolean = false
            private set
    }
}