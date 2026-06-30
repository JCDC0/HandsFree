package com.example.handsfree

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Main launcher activity for HandsFree.
 *
 * Phase 1 (current): Simple debug screen showing transcribed and translated text.
 * Phase 2: Onboarding flow for Accessibility Service and overlay permissions.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var transcribedText: TextView
    private lateinit var translatedText: TextView
    private lateinit var startButton: Button
    private lateinit var permButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        transcribedText = findViewById(R.id.transcribed_text)
        translatedText = findViewById(R.id.translated_text)
        startButton = findViewById(R.id.start_button)
        permButton = findViewById(R.id.perm_button)

        startButton.setOnClickListener {
            // TODO: Start OverlayService (triggers mic capture + VOSK pipeline)
            Toast.makeText(this, "Overlay service starting...", Toast.LENGTH_SHORT).show()
        }

        permButton.setOnClickListener {
            // Open Accessibility Settings so user can enable HandsFree
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        // TODO: Check if Accessibility Service is enabled, update statusText
        // TODO: Check SYSTEM_ALERT_WINDOW permission (Settings.canDrawOverlays)
    }
}
