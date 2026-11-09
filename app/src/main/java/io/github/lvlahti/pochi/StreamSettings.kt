package io.github.lvlahti.pochi

import android.content.Intent

/** Explicit, bounded user-selected stream parameters. */
data class StreamSettings(
    val transport: String = TRANSPORT_USB,
    val mode: String = MODE_H264,
    val maxEdge: Int = 1280,
    val fps: Int = 24,
    val bitrateKbps: Int = 2500,
    val jpegQuality: Int = 70,
    val audio: Boolean = false
) {
    fun writeTo(intent: Intent): Intent = intent.apply {
        putExtra(KEY_TRANSPORT, transport)
        putExtra(KEY_MODE, mode)
        putExtra(KEY_EDGE, maxEdge)
        putExtra(KEY_FPS, fps)
        putExtra(KEY_BITRATE, bitrateKbps)
        putExtra(KEY_JPEG_QUALITY, jpegQuality)
        putExtra(KEY_AUDIO, audio)
    }

    companion object {
        const val TRANSPORT_USB = "usb"
        const val TRANSPORT_WIFI = "wifi"
        const val MODE_H264 = "h264"
        const val MODE_MJPEG = "mjpeg"
        private const val KEY_TRANSPORT = "transport"
        private const val KEY_MODE = "mode"
        private const val KEY_EDGE = "max_edge"
        private const val KEY_FPS = "fps"
        private const val KEY_BITRATE = "bitrate_kbps"
        private const val KEY_JPEG_QUALITY = "jpeg_quality"
        private const val KEY_AUDIO = "audio"

        fun from(intent: Intent): StreamSettings = StreamSettings(
            transport = if (intent.getStringExtra(KEY_TRANSPORT) == TRANSPORT_WIFI) TRANSPORT_WIFI else TRANSPORT_USB,
            mode = if (intent.getStringExtra(KEY_MODE) == MODE_MJPEG) MODE_MJPEG else MODE_H264,
            maxEdge = intent.getIntExtra(KEY_EDGE, 1280).coerceIn(320, 1920),
            fps = intent.getIntExtra(KEY_FPS, 24).coerceIn(5, 60),
            bitrateKbps = intent.getIntExtra(KEY_BITRATE, 2500).coerceIn(300, 16000),
            jpegQuality = intent.getIntExtra(KEY_JPEG_QUALITY, 70).coerceIn(20, 95),
            audio = intent.getBooleanExtra(KEY_AUDIO, false)
        )
    }
}