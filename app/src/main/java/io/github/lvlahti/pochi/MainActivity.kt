package io.github.lvlahti.pochi

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Toast
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var settingsButton: Button
    private lateinit var copyLinkButton: Button
    private lateinit var shareLinkButton: Button
    private lateinit var shareCard: View
    private var currentShareUrl: String? = null
    private lateinit var transportSpinner: Spinner
    private lateinit var modeSpinner: Spinner
    private lateinit var resolutionSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var bitrateSpinner: Spinner
    private lateinit var qualitySpinner: Spinner
    private lateinit var qualityLabel: TextView
    private lateinit var bitrateLabel: TextView
    private lateinit var audioCheckbox: CheckBox
    private var pendingSettings = StreamSettings()

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            renderStatus(
                intent.getStringExtra(CaptureService.EXTRA_STATUS) ?: "Not sharing",
                intent.getStringExtra(CaptureService.EXTRA_URL),
                intent.getBooleanExtra(CaptureService.EXTRA_RUNNING, false)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        urlText = findViewById(R.id.urlText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        settingsButton = findViewById(R.id.settingsButton)
        copyLinkButton = findViewById(R.id.copyLinkButton)
        shareLinkButton = findViewById(R.id.shareLinkButton)
        shareCard = findViewById(R.id.shareCard)
        transportSpinner = findViewById(R.id.transportSpinner)
        modeSpinner = findViewById(R.id.modeSpinner)
        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        fpsSpinner = findViewById(R.id.fpsSpinner)
        bitrateSpinner = findViewById(R.id.bitrateSpinner)
        qualitySpinner = findViewById(R.id.qualitySpinner)
        qualityLabel = findViewById(R.id.qualityLabel)
        bitrateLabel = findViewById(R.id.bitrateLabel)
        audioCheckbox = findViewById(R.id.audioCheckbox)
        setup(transportSpinner, arrayOf("USB cable (tethering)", "Wi-Fi (same network)"))
        setup(modeSpinner, arrayOf("H.264 · hardware accelerated", "MJPEG · compatibility"))
        setup(resolutionSpinner, EDGES.map { "${it}p maximum edge" }.toTypedArray())
        setup(fpsSpinner, FRAME_RATES.map { "$it FPS" }.toTypedArray())
        setup(bitrateSpinner, BITRATES.map { "$it kbps" }.toTypedArray())
        setup(qualitySpinner, QUALITIES.map { "$it% JPEG quality" }.toTypedArray())
        val prefs = getSharedPreferences("pochi_settings", MODE_PRIVATE)
        transportSpinner.setSelection(prefs.getInt("transport", 0).coerceIn(0, 1))
        modeSpinner.setSelection(prefs.getInt("mode", 0).coerceIn(0, 1))
        resolutionSpinner.setSelection(prefs.getInt("resolution", 2).coerceIn(0, EDGES.lastIndex))
        fpsSpinner.setSelection(prefs.getInt("fps", 1).coerceIn(0, FRAME_RATES.lastIndex))
        bitrateSpinner.setSelection(prefs.getInt("bitrate", 2).coerceIn(0, BITRATES.lastIndex))
        qualitySpinner.setSelection(prefs.getInt("quality", 2).coerceIn(0, QUALITIES.lastIndex))
        audioCheckbox.isChecked = prefs.getBoolean("audio", false) && Build.VERSION.SDK_INT >= 29
        if (Build.VERSION.SDK_INT < 29) {
            audioCheckbox.isEnabled = false
            audioCheckbox.text = "Internal audio requires Android 10+"
        }
        transportSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                settingsButton.text = if (position == 0) "USB tethering settings   ↗" else "Wi-Fi settings   ↗"
            }
        }
        modeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mjpeg = position == 1
                qualitySpinner.visibility = if (mjpeg) View.VISIBLE else View.GONE
                qualityLabel.visibility = if (mjpeg) View.VISIBLE else View.GONE
                bitrateSpinner.visibility = if (mjpeg) View.GONE else View.VISIBLE
                bitrateLabel.visibility = if (mjpeg) View.GONE else View.VISIBLE
            }
        }
        settingsButton.setOnClickListener { openNetworkSettings() }
        copyLinkButton.setOnClickListener {
            val link = currentShareUrl ?: return@setOnClickListener
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Pochi viewer link", link))
            Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
        }
        shareLinkButton.setOnClickListener {
            val link = currentShareUrl ?: return@setOnClickListener
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Pochi screen mirroring")
                putExtra(Intent.EXTRA_TEXT, link)
            }
            startActivity(Intent.createChooser(share, "Share Pochi link"))
        }
        startButton.setOnClickListener {
            pendingSettings = StreamSettings(
                transport = if (transportSpinner.selectedItemPosition == 0) StreamSettings.TRANSPORT_USB else StreamSettings.TRANSPORT_WIFI,
                mode = if (modeSpinner.selectedItemPosition == 0) StreamSettings.MODE_H264 else StreamSettings.MODE_MJPEG,
                maxEdge = EDGES[resolutionSpinner.selectedItemPosition],
                fps = FRAME_RATES[fpsSpinner.selectedItemPosition],
                bitrateKbps = BITRATES[bitrateSpinner.selectedItemPosition],
                jpegQuality = QUALITIES[qualitySpinner.selectedItemPosition],
                audio = audioCheckbox.isChecked && Build.VERSION.SDK_INT >= 29
            )
            prefs.edit()
                .putInt("transport", transportSpinner.selectedItemPosition)
                .putInt("mode", modeSpinner.selectedItemPosition)
                .putInt("resolution", resolutionSpinner.selectedItemPosition)
                .putInt("fps", fpsSpinner.selectedItemPosition)
                .putInt("bitrate", bitrateSpinner.selectedItemPosition)
                .putInt("quality", qualitySpinner.selectedItemPosition)
                .putBoolean("audio", pendingSettings.audio)
                .apply()
            if (pendingSettings.audio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_REQUEST)
            } else requestProjection()
        }
        stopButton.setOnClickListener {
            startService(Intent(this, CaptureService::class.java).apply { action = CaptureService.ACTION_STOP })
        }
        renderStatus(CaptureService.status, CaptureService.url, CaptureService.running)
        animatePress(startButton)
        animatePress(stopButton)
        animatePress(settingsButton)
        animatePress(copyLinkButton)
        animatePress(shareLinkButton)
        animateEntrance()
    }

    /** Gentle card reveal; only on app launch, so the stream is never interrupted. */
    private fun animateEntrance() {
        val ids = intArrayOf(R.id.heroCard, R.id.connectionCard, R.id.videoCard, R.id.audioCard, R.id.controlCard)
        val offset = 16f * resources.displayMetrics.density
        ids.forEachIndexed { index, id ->
            val panel = findViewById<View>(id)
            panel.alpha = 0f
            panel.translationY = offset
            panel.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(45L * index)
                .setDuration(420L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /** Native responsive press effect; returns false so normal clicks still fire. */
    private fun animatePress(button: View) {
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.975f).scaleY(0.975f).setDuration(90L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(180L)
                        .setInterpolator(DecelerateInterpolator()).start()
            }
            false
        }
    }

    /**
     * Android does not expose a guaranteed public deep link directly to the USB tether toggle.
     * Prefer the tethering activity, and fall back safely on Xiaomi/HyperOS variants.
     */
    private fun openNetworkSettings() {
        val usb = transportSpinner.selectedItemPosition == 0
        val destinations = if (usb) listOf(
            Intent("android.settings.TETHER_SETTINGS"),
            Intent(Intent.ACTION_MAIN).setClassName(
                "com.android.settings",
                "com.android.settings.Settings" + '
        val adapter = ArrayAdapter(this, R.layout.spinner_item, choices)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinner.adapter = adapter
    }

    private fun requestProjection() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestProjection()
            else renderStatus("Microphone permission is needed for internal audio. Uncheck audio to share video only.", null, false)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(CaptureService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") registerReceiver(statusReceiver, filter) }
        renderStatus(CaptureService.status, CaptureService.url, CaptureService.running)
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    @Deprecated("Platform callback retained to avoid adding AndroidX runtime dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CAPTURE_REQUEST) return
        if (resultCode != RESULT_OK || data == null) {
            renderStatus("Screen capture permission declined", null, false)
            return
        }
        val service = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            pendingSettings.writeTo(this)
        }
        startForegroundService(service)
        renderStatus("Starting capture…", null, true)
    }

    private fun renderStatus(message: String, link: String?, active: Boolean) {
        val isError = message.startsWith("Error:", ignoreCase = true) ||
            message.contains("declined", ignoreCase = true) ||
            message.contains("permission is needed", ignoreCase = true)
        // Only show YOU ARE LIVE after the service confirms it has a working URL.
        val live = active && !link.isNullOrBlank() && !isError
        val starting = active && !live && !isError

        statusText.text = message
        statusText.setTextColor(Color.parseColor(if (isError) "#FF6666" else "#DEDEDE"))
        findViewById<TextView>(R.id.statusIndicator).setTextColor(
            Color.parseColor(if (isError) "#FF6666" else if (live) "#49DE91" else "#A0A0A0")
        )

        startButton.text = when {
            live -> "●  You are live"
            starting -> "Starting live…"
            else -> "Start live    →"
        }
        startButton.setBackgroundResource(
            if (live) R.drawable.pochi_live_button else R.drawable.pochi_primary_button
        )
        startButton.setTextColor(Color.parseColor("#101010"))
        startButton.isEnabled = !active
        startButton.alpha = if (starting) 0.7f else 1f
        stopButton.visibility = if (live) View.VISIBLE else View.GONE
        stopButton.isEnabled = live

        currentShareUrl = if (live) link else null
        val wasVisible = shareCard.visibility == View.VISIBLE
        shareCard.visibility = if (live) View.VISIBLE else View.GONE
        urlText.text = currentShareUrl.orEmpty()
        copyLinkButton.isEnabled = live
        shareLinkButton.isEnabled = live

        if (live && !wasVisible) {
            shareCard.alpha = 0f
            shareCard.translationY = 12f * resources.displayMetrics.density
            shareCard.animate().alpha(1f).translationY(0f)
                .setDuration(260L).setInterpolator(DecelerateInterpolator()).start()
        } else if (!live) {
            shareCard.animate().cancel()
            shareCard.alpha = 1f
            shareCard.translationY = 0f
        }
    }

    companion object {
        private const val CAPTURE_REQUEST = 2401
        private const val AUDIO_PERMISSION_REQUEST = 2402
        private val EDGES = listOf(640, 960, 1280, 1600, 1920)
        private val FRAME_RATES = listOf(15, 24, 30, 60)
        private val BITRATES = listOf(750, 1500, 2500, 4000, 6500, 10000)
        private val QUALITIES = listOf(40, 55, 70, 85, 95)
    }
} + "TetherSettingsActivity"
            ),
            Intent(Settings.ACTION_SETTINGS)
        ) else listOf(
            Intent(Settings.ACTION_WIFI_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for ((index, destination) in destinations.withIndex()) {
            try {
                startActivity(destination)
                if (usb && index == destinations.lastIndex) {
                    Toast.makeText(this, "Open Portable hotspot → USB tethering", Toast.LENGTH_LONG).show()
                }
                return
            } catch (_: ActivityNotFoundException) {
                // Different Android vendors expose different Settings screens.
            } catch (_: SecurityException) {
                // An OEM may block a non-system app from opening its internal activity.
            }
        }
        Toast.makeText(this, "Open Settings → Portable hotspot → USB tethering", Toast.LENGTH_LONG).show()
    }

    private fun setup(spinner: Spinner, choices: Array<String>) {
        val adapter = ArrayAdapter(this, R.layout.spinner_item, choices)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinner.adapter = adapter
    }

    private fun requestProjection() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestProjection()
            else renderStatus("Microphone permission is needed for internal audio. Uncheck audio to share video only.", null, false)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(CaptureService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") registerReceiver(statusReceiver, filter) }
        renderStatus(CaptureService.status, CaptureService.url, CaptureService.running)
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    @Deprecated("Platform callback retained to avoid adding AndroidX runtime dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CAPTURE_REQUEST) return
        if (resultCode != RESULT_OK || data == null) {
            renderStatus("Screen capture permission declined", null, false)
            return
        }
        val service = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            pendingSettings.writeTo(this)
        }
        startForegroundService(service)
        renderStatus("Starting capture…", null, true)
    }

    private fun renderStatus(message: String, link: String?, active: Boolean) {
        statusText.text = message
        val isError = message.startsWith("Error:", ignoreCase = true) ||
            message.contains("declined", ignoreCase = true) ||
            message.contains("permission is needed", ignoreCase = true)
        statusText.setTextColor(Color.parseColor(if (isError) "#FF5555" else "#DEDEDE"))
        findViewById<TextView>(R.id.statusIndicator).setTextColor(Color.parseColor(
            if (isError) "#FF5555" else if (active) "#FFFFFF" else "#A0A0A0"
        ))
        urlText.text = link.orEmpty()
        startButton.isEnabled = !active
        stopButton.isEnabled = active
    }

    companion object {
        private const val CAPTURE_REQUEST = 2401
        private const val AUDIO_PERMISSION_REQUEST = 2402
        private val EDGES = listOf(640, 960, 1280, 1600, 1920)
        private val FRAME_RATES = listOf(15, 24, 30, 60)
        private val BITRATES = listOf(750, 1500, 2500, 4000, 6500, 10000)
        private val QUALITIES = listOf(40, 55, 70, 85, 95)
    }
}