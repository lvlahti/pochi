package io.github.lvlahti.pochi

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
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
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Surface
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** One MediaProjection consent creates ONE VirtualDisplay, resized on rotation without reusing the token. */
class CaptureService : Service() {
    private var server: StreamServer? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var codecSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var drainThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private lateinit var displayManager: DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var settings: StreamSettings
    private var currentGeometry: Geometry? = null
    @Volatile private var videoGeneration = 0
    @Volatile private var stopping = false
    @Volatile private var audioRunning = false
    @Volatile private var jpegQuality = 70
    @Volatile private var frameIntervalMs = 42L
    private var lastPacketDrops = 0L
    private var qualityController: AutoQuality? = null
    private val qualityTicker = object : Runnable {
        override fun run() {
            if (stopping || !::settings.isInitialized) return
            val current = server?.stats()
            if (settings.autoQuality && current != null && current.viewers > 0) {
                val congested = current.queueDepth >= 5 ||
                    current.dropped > lastPacketDrops || current.viewerLagMs >= 600
                lastPacketDrops = current.dropped
                if (settings.mode == StreamSettings.MODE_H264) {
                    val bitrate = qualityController?.update(congested)
                    if (bitrate != null) runCatching {
                        codec?.setParameters(Bundle().apply {
                            putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate * 1000)
                        })
                    }.onFailure { Log.w(TAG, "Dynamic bitrate not supported by encoder", it) }
                } else {
                    jpegQuality = (jpegQuality + if (congested) -7 else 2).coerceIn(40, settings.jpegQuality)
                    frameIntervalMs = if (congested) (frameIntervalMs + 18L).coerceAtMost(150L)
                        else (frameIntervalMs - 7L).coerceAtLeast(1000L / settings.fps)
                }
            }
            mainHandler.postDelayed(this, 3000L)
        }
    }

    private data class Geometry(val width: Int, val height: Int, val dpi: Int)

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) requestResize()
        }
    }
    private val resizeRunnable = Runnable {
        if (stopping || projection == null || virtualDisplay == null) return@Runnable
        val next = captureSize(settings.maxEdge)
        val previous = currentGeometry
        if (previous != null && (next.width != previous.width || next.height != previous.height)) {
            try {
                Log.i(TAG, "Screen rotation: ${previous.width}x${previous.height} -> ${next.width}x${next.height}")
                rebuildVideo(next)
            } catch (error: Exception) {
                Log.e(TAG, "Failed to resize capture", error)
                notifyStatus("Error: rotation resize failed: ${error.message}", null, false)
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestResize()
    }

    private fun requestResize() {
        mainHandler.removeCallbacks(resizeRunnable)
        mainHandler.removeCallbacks(qualityTicker)
        mainHandler.postDelayed(resizeRunnable, 350)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                if (running) return START_NOT_STICKY
                running = true
                stopping = false
                settings = StreamSettings.from(intent)
                try {
                    startForegroundNotification()
                    val ip = LocalNetwork.findAddress(this, settings.transport)
                        ?: error(if (settings.transport == StreamSettings.TRANSPORT_WIFI)
                            "Wi-Fi IPv4 not found. Connect both devices to one Wi-Fi network."
                            else "USB tethering not found. Connect a data cable and enable USB tethering.")
                    server = StreamServer(this, ip, settings, onViewerPending = { viewer ->
                        notifyViewerPending(viewer)
                    }).also { it.start() }
                    activeServer = server
                    qualityController = AutoQuality(settings.bitrateKbps)
                    jpegQuality = settings.jpegQuality
                    frameIntervalMs = 1000L / settings.fps
                    lastPacketDrops = 0L
                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                    @Suppress("DEPRECATION")
                    val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                        ?: error("Missing screen-capture permission")
                    val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val mediaProjection = manager.getMediaProjection(resultCode, resultData)
                        ?: error("Android denied screen capture")
                    projection = mediaProjection
                    mediaProjection.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            if (!stopping) mainHandler.post { stopSelf() }
                        }
                    }, mainHandler)
                    displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
                    buildVideo(captureSize(settings.maxEdge))
                    displayManager.registerDisplayListener(displayListener, mainHandler)
                    if (settings.audio) startInternalAudio()
                    mainHandler.postDelayed(qualityTicker, 3000L)
                    notifyStatus("Sharing ${settings.transport.uppercase()} (${settings.mode.uppercase()}, ${settings.fps} FPS${if (audioRunning) ", audio on" else ""})", server?.url, true)
                } catch (error: Exception) {
                    Log.e(TAG, "Capture could not start", error)
                    notifyStatus("Error: ${error.message ?: "could not start"}", null, false)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Pochi sharing", NotificationManager.IMPORTANCE_LOW))
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, CaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pochi is sharing your screen")
            .setContentText("Tap to open Pochi and stop sharing")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true).setContentIntent(launch)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else { @Suppress("DEPRECATION") startForeground(NOTIFICATION_ID, notification) }
    }

    private fun notifyViewerPending(viewer: ViewerGate.Request) {
        sendBroadcast(Intent(ACTION_VIEWER_PENDING).setPackage(packageName).apply {
            putExtra(EXTRA_VIEWER_ID, viewer.id)
            putExtra(EXTRA_VIEWER_IP, viewer.ip)
        })
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(VIEWER_CHANNEL, "Viewer approval",
            NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(this, 8, Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(VIEWER_NOTIFICATION_ID, Notification.Builder(this, VIEWER_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Pochi: viewer requests access")
            .setContentText("Device ${viewer.ip} is waiting. Tap to Allow or Deny.")
            .setContentIntent(open).setAutoCancel(true).build())
    }

    private fun captureSize(limit: Int): Geometry {
        val screen = Point()
        val actual = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        actual?.getRealSize(screen)
        val w = screen.x.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = screen.y.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val scale = (limit.toFloat() / max(w, h)).coerceAtMost(1f)
        fun aligned(value: Int) = (((value * scale).roundToInt() + 15) / 16 * 16).coerceAtLeast(16)
        return Geometry(aligned(w), aligned(h), resources.displayMetrics.densityDpi.coerceAtLeast(1))
    }

    private fun buildVideo(geometry: Geometry) {
        currentGeometry = geometry
        if (settings.mode == StreamSettings.MODE_MJPEG) startMjpeg(geometry)
        else startH264(geometry)
    }

    /** MediaProjection#createVirtualDisplay is invoked only on first start, never on rotation. */
    private fun attachSurface(geometry: Geometry, surface: Surface) {
        val projection = projection ?: error("MediaProjection stopped")
        if (virtualDisplay == null) {
            virtualDisplay = projection.createVirtualDisplay("Pochi", geometry.width, geometry.height,
                geometry.dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        } else {
            virtualDisplay!!.resize(geometry.width, geometry.height, geometry.dpi)
            virtualDisplay!!.setSurface(surface)
        }
    }

    private fun rebuildVideo(geometry: Geometry) {
        if (settings.mode == StreamSettings.MODE_H264) server?.resetVideo()
        virtualDisplay?.setSurface(null)
        stopVideoPipeline()
        buildVideo(geometry)
    }

    private fun stopVideoPipeline() {
        videoGeneration++
        drainThread?.join(500)
        drainThread = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { codecSurface?.release() }
        codecSurface = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { imageThread?.quitSafely() }
        imageThread = null
    }

    private fun startH264(geometry: Geometry) {
        val (width, height) = geometry
        val rate = settings.fps
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, rate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec = encoder
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        codecSurface = surface
        encoder.start()
        attachSurface(geometry, surface)
        val generation = ++videoGeneration
        drainThread = Thread({ drainEncoder(encoder, width, height, rate, generation) }, "Pochi-H264")
            .also { it.start() }
    }

    private fun drainEncoder(encoder: MediaCodec, width: Int, height: Int, fps: Int, generation: Int) {
        val info = MediaCodec.BufferInfo()
        var muxer: H264Mp4Muxer? = null
        try {
            while (!stopping && generation == videoGeneration) {
                when (val slot = encoder.dequeueOutputBuffer(info, 20_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = encoder.outputFormat
                        fun nal(key: String, type: Int): ByteArray? {
                            val data = format.getByteBuffer(key) ?: return null
                            val bytes = ByteArray(data.remaining()).also { data.get(it) }
                            return AvcNal.split(bytes).firstOrNull { it.isNotEmpty() && (it[0].toInt() and 31) == type }
                        }
                        val sps = nal("csd-0", 7) ?: error("H.264 encoder omitted SPS")
                        val pps = nal("csd-1", 8) ?: error("H.264 encoder omitted PPS")
                        muxer = H264Mp4Muxer(width, height, sps, pps, fps)
                        server?.setH264Init("video/mp4; codecs=\"${muxer.codecString}\"", muxer.initSegment())
                    }
                    in 0..Int.MAX_VALUE -> {
                        val buffer = encoder.getOutputBuffer(slot)
                        if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val frame = ByteArray(info.size)
                            buffer.get(frame)
                            server?.recordVideoFrame(frame.size)
                            muxer?.offerFrame(frame, info.presentationTimeUs,
                                info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                                ?.forEach { server?.publishFragment(it) }
                        }
                        encoder.releaseOutputBuffer(slot, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (error: Exception) {
            if (!stopping && generation == videoGeneration) {
                Log.e(TAG, "H.264 encoder failed", error)
                mainHandler.post {
                    notifyStatus("Error: H.264 encoding failed. Try MJPEG or lower settings.", null, false)
                    stopSelf()
                }
            }
        } finally {
            if (generation == videoGeneration) muxer?.finish()?.let { server?.publishFragment(it) }
        }
    }

    private fun startMjpeg(geometry: Geometry) {
        val (width, height) = geometry
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        val thread = HandlerThread("Pochi-MJPEG").also { it.start() }
        imageThread = thread
        val generation = ++videoGeneration
        var lastFrameAt = 0L
        reader.setOnImageAvailableListener({ source ->
            val frame = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (stopping || generation != videoGeneration || now - lastFrameAt < frameIntervalMs)
                    return@setOnImageAvailableListener
                lastFrameAt = now
                val plane = frame.planes.firstOrNull() ?: return@setOnImageAvailableListener
                val buffer = plane.buffer
                val paddedWidth = width + (plane.rowStride - width * plane.pixelStride) / plane.pixelStride
                val raw = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                try {
                    raw.copyPixelsFromBuffer(buffer)
                    val cropped = Bitmap.createBitmap(raw, 0, 0, width, height)
                    try {
                        val encoded = ByteArrayOutputStream()
                        cropped.compress(Bitmap.CompressFormat.JPEG, jpegQuality, encoded)
                        val bytes = encoded.toByteArray()
                        server?.recordVideoFrame(bytes.size)
                        server?.publishJpeg(bytes)
                    } finally { cropped.recycle() }
                } finally { raw.recycle() }
            } catch (error: Exception) {
                if (!stopping && generation == videoGeneration) Log.w(TAG, "Dropped MJPEG frame", error)
            } finally { frame.close() }
        }, Handler(thread.looper))
        attachSurface(geometry, reader.surface)
    }

    private fun startInternalAudio() {
        if (Build.VERSION.SDK_INT < 29 ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Audio capture requires Android 10 and RECORD_AUDIO permission")
            return
        }
        val projection = projection ?: return
        // Only user-approved playback (USAGE_MEDIA/GAME) may be captured; microphone is never used.
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            val minimum = AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            require(minimum > 0) { "Audio input unsupported" }
            val recorder = AudioRecord.Builder().setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format).setBufferSizeInBytes(max(minimum, 8192)).build()
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed" }
            audioRecord = recorder
            recorder.startRecording()
            audioRunning = true
            server?.setAudioAvailable(true)
            audioThread = Thread({
                val chunk = ByteArray(3840) // 40 ms at 48kHz, mono, PCM16LE
                try {
                    while (audioRunning && !stopping) {
                        val n = recorder.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                        if (n > 0) server?.publishAudio(chunk.copyOf(n), SystemClock.elapsedRealtime())
                        else if (n < 0) break
                    }
                } catch (error: Exception) {
                    if (!stopping) Log.w(TAG, "Internal audio capture stopped", error)
                }
            }, "Pochi-Internal-Audio").also { it.start() }
        } catch (error: Exception) {
            server?.setAudioAvailable(false)
            Log.w(TAG, "Audio unavailable; continuing video-only", error)
            runCatching { audioRecord?.release() }
            audioRecord = null
        }
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
        audioRunning = false
        server?.setAudioAvailable(false)
        mainHandler.removeCallbacks(resizeRunnable)
        if (::displayManager.isInitialized) runCatching { displayManager.unregisterDisplayListener(displayListener) }
        runCatching { audioRecord?.stop() }
        runCatching { audioThread?.join(300) }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { virtualDisplay?.setSurface(null) }
        stopVideoPipeline()
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { projection?.stop() }
        projection = null
        server?.close()
        activeServer = null
        server = null
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(VIEWER_NOTIFICATION_ID)
        if (!status.startsWith("Error:")) notifyStatus("Not sharing", null, false)
        else running = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PochiCapture"
        private const val CHANNEL_ID = "pochi_media_projection"
        private const val NOTIFICATION_ID = 41
        private const val VIEWER_NOTIFICATION_ID = 42
        private const val VIEWER_CHANNEL = "pochi_viewer_approval"
        const val ACTION_VIEWER_PENDING = "io.github.lvlahti.pochi.VIEWER_PENDING"
        const val EXTRA_VIEWER_ID = "viewer_id"
        const val EXTRA_VIEWER_IP = "viewer_ip"
        @Volatile private var activeServer: StreamServer? = null

        fun pendingViewers(): List<ViewerGate.Request> = activeServer?.pendingViewers().orEmpty()
        fun decideViewer(id: String, allow: Boolean): Boolean =
            activeServer?.decideViewer(id, allow) ?: false
        const val ACTION_START = "io.github.lvlahti.pochi.START"
        const val ACTION_STOP = "io.github.lvlahti.pochi.STOP"
        const val ACTION_STATUS = "io.github.lvlahti.pochi.STATUS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_STATUS = "status"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_URL = "url"
        @Volatile var status: String = "Not sharing"
            private set
        @Volatile var url: String? = null
            private set
        @Volatile var running: Boolean = false
            private set
    }
}