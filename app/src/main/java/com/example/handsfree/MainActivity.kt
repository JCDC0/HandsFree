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
import com.example.handsfree.pipeline.LevenshteinMatcher
import com.example.handsfree.service.OverlayService

/**
 * Main launcher activity for HandsFree.
 *
 * Debug screen showing the full pipeline result:
 *   - Raw transcription from VOSK
 *   - Tagalog→English translation from TranslationLayer
 *   - Detected intent from LevenshteinMatcher
 *
 * The activity registers itself as the [OverlayService.PipelineListener]
 * while resumed and loads the intent vocabulary for local display.
 */
class MainActivity : AppCompatActivity(), OverlayService.PipelineListener {

    private lateinit var statusText: TextView
    private lateinit var transcribedText: TextView
    private lateinit var translatedText: TextView
    private lateinit var intentText: TextView
    private lateinit var startButton: Button
    private lateinit var permButton: Button

    private var levenshteinMatcher: LevenshteinMatcher? = null

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

        statusText      = findViewById(R.id.status_text)
        transcribedText = findViewById(R.id.transcribed_text)
        translatedText  = findViewById(R.id.translated_text)
        intentText      = findViewById(R.id.intent_text)
        startButton     = findViewById(R.id.start_button)
        permButton      = findViewById(R.id.perm_button)

        // Load LevenshteinMatcher for intent display in the debug UI
        levenshteinMatcher = runCatching {
            val json = assets.open("intent-vocabulary.json").bufferedReader().use { it.readText() }
            LevenshteinMatcher.fromAssets(json)
        }.getOrNull()

        startButton.setOnClickListener {
            if (hasRecordAudioPermission()) {
                startOverlayService()
            } else {
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        permButton.setOnClickListener {
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

    private fun updatePermissionStatus() {
        val missing = mutableListOf<String>()
        if (!hasRecordAudioPermission()) missing += "RECORD_AUDIO"
        if (!Settings.canDrawOverlays(this)) missing += "OVERLAY"
        if (missing.isNotEmpty()) {
            statusText.text = "⚠ Missing: ${missing.joinToString()} — grant before starting"
        } else if (statusText.text.toString().startsWith("⚠", ignoreCase = false)) {
            statusText.text = "✓ Ready — tap Start"
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
        statusText.text = "PREPARING…"
    }

    // ---- OverlayService.PipelineListener ----

    override fun onTranscriptionResult(rawTranscribed: String, normalized: String) {
        runOnUiThread {
            transcribedText.text = rawTranscribed.ifBlank { "—" }
            translatedText.text  = normalized.ifBlank { "—" }

            // Run intent classification and show result in debug UI
            val match = levenshteinMatcher?.classify(normalized)
            intentText.text = if (match != null) {
                "${match.first}  (matched: \"${match.second}\")"
            } else {
                "No match"
            }
        }
    }

    override fun onStatusChanged(status: String) {
        runOnUiThread {
            statusText.text = status
        }
    }
}
