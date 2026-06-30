package com.example.handsfree.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service that hosts the HandsFree processing pipeline and renders
 * the transparent grid overlay using TYPE_ACCESSIBILITY_OVERLAY (API 34+).
 *
 * The pipeline runs in five sequential stages:
 *   1. AudioPreprocessor   — mic capture + VAD (16000 Hz, mono, 16-bit PCM)
 *   2. VoskSpeechEngine    — offline ASR via VOSK (English + Filipino models)
 *   3. TranslationLayer    — Tagalog/Taglish → English via bilingual lexicon
 *   4. AppStateMachine     — 6-state FSM enforcing sequential command processing
 *   5. DfsNodeLocator      — AccessibilityNodeInfo traversal for coordinate mapping
 */
class OverlayService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // TODO: Initialize AudioPreprocessor, VoskSpeechEngine, TranslationLayer,
        //       AppStateMachine, DfsNodeLocator
        // TODO: Start foreground notification for the persistent overlay
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO: Handle start commands (START_LISTENING, STOP_LISTENING, etc.)
        return START_STICKY
    }

    override fun onDestroy() {
        // TODO: Clean up VOSK recognizer, AudioRecord, WindowManager overlay
        super.onDestroy()
    }
}
