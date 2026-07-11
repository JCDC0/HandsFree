package com.example.handsfree

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.handsfree.service.OverlayService

/**
 * Main launcher activity for HandsFree.
 *
 * Phase 1 (current): Simple debug screen showing transcribed and translated text
 * produced by the pipeline hosted in [OverlayService]. The activity registers
 * itself as the [OverlayService.PipelineListener] while it is resumed.
 *
 * Phase 2: Onboarding flow for Accessibility Service and overlay permissions
 * (Module G).
 */
class MainActivity : AppCompatActivity(), OverlayService.PipelineListener {

    private lateinit var statusText: TextView
    private lateinit var transcribedText: TextView
    private lateinit var translatedText: TextView
    private lateinit var startButton: Button
    private lateinit var permButton: Button

    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startOverlayService()
            } else {
                Toast.makeText(this, "RECORD_AUDIO permission required", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        transcribedText = findViewById(R.id.transcribed_text)
        translatedText = findViewById(R.id.translated_text)
        startButton = findViewById(R.id.start_button)
        permButton = findViewById(R.id.perm_button)

        startButton.setOnClickListener {
            if (hasRecordAudioPermission()) {
                startOverlayService()
            } else {
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        permButton.setOnClickListener {
            // Open Accessibility Settings so user can enable the HandsFree service.
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        OverlayService.pipelineListener = this
        updatePermissionStatus()
    }

    override fun onPause() {
        super.onPause()
        if (OverlayService.pipelineListener === this) {
            OverlayService.pipelineListener = null
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Reflect missing permissions / overlay grant in the status TextView so the
     * user knows what to fix before tapping "Start Mic & VOSK".
     */
    private fun updatePermissionStatus() {
        val missing = mutableListOf<String>()
        if (!hasRecordAudioPermission()) missing += "RECORD_AUDIO"
        if (!Settings.canDrawOverlays(this)) missing += "OVERLAY"
        if (missing.isNotEmpty()) {
            statusText.text = "Missing: ${missing.joinToString()} — grant before starting"
        } else if (statusText.text.toString().startsWith("Missing", ignoreCase = true)) {
            statusText.text = "Ready — tap Start Mic & VOSK"
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Pipeline starting...", Toast.LENGTH_SHORT).show()
        statusText.text = "PREPARING"
    }

    // ---- OverlayService.PipelineListener ----

    override fun onTranscriptionResult(rawTranscribed: String, normalized: String) {
        runOnUiThread {
            transcribedText.text = rawTranscribed
            translatedText.text = normalized
        }
    }

    override fun onStatusChanged(status: String) {
        runOnUiThread {
            statusText.text = status
        }
    }
}