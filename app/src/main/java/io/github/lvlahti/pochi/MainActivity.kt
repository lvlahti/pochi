package io.github.lvlahti.pochi

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var balancedRadio: RadioButton
    private var mode = CaptureService.MODE_H264

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
        balancedRadio = findViewById(R.id.balancedRadio)
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
        startButton.setOnClickListener {
            mode = if (balancedRadio.isChecked) CaptureService.MODE_H264 else CaptureService.MODE_MJPEG
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE_REQUEST)
        }
        stopButton.setOnClickListener {
            startService(Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_STOP
            })
        }
        renderStatus(CaptureService.status, CaptureService.url, CaptureService.running)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(CaptureService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
        renderStatus(CaptureService.status, CaptureService.url, CaptureService.running)
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    @Deprecated("Platform callback used to avoid an AndroidX dependency")
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
            putExtra(CaptureService.EXTRA_MODE, mode)
        }
        startForegroundService(service)
        renderStatus("Starting capture…", null, true)
    }

    private fun renderStatus(status: String, url: String?, active: Boolean) {
        statusText.text = status
        urlText.text = url ?: ""
        startButton.isEnabled = !active
        stopButton.isEnabled = active
    }

    companion object {
        private const val CAPTURE_REQUEST = 2401
    }
}