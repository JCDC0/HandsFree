package com.example.handsfree.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.handsfree.util.CrashLog

/**
 * Spoken feedback (W7), through the phone's own text-to-speech engine.
 *
 * Offline only. A voice is used only when it reports that it needs no network
 * connection and is installed, so no text leaves the phone, which keeps the
 * zero-transmission claim. If no offline voice exists, the announcer stays
 * silent and every callback still runs.
 *
 * Voices: one offline Filipino voice (fil or tl) and one offline English
 * voice, when installed. Each line is spoken in the voice of its own
 * language. [speaksFilipino] tells the caller whether Tagalog lines are
 * possible at all: Tagalog text read by an English voice is hard to follow.
 *
 * [speak] calls [onDone] on the main thread when the line has finished, or
 * at once when nothing can be spoken. OverlayService opens the microphone
 * only after that, so Whisper never transcribes the app's own voice.
 */
class Announcer(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashMap<String, () -> Unit>()
    private var counter = 0

    @Volatile var isReady = false
        private set

    @Volatile var speaksFilipino = false
        private set

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                CrashLog.info(appContext, "text-to-speech unavailable (status $status), announcements off")
                return@TextToSpeech
            }
            chooseVoice()
        }
    }

    private fun offline(v: Voice): Boolean =
        !v.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in (v.features ?: emptySet())

    private fun chooseVoice() {
        val engine = tts ?: return
        val voices = runCatching { engine.voices?.toList().orEmpty() }.getOrDefault(emptyList())
            .filter { offline(it) }
        val filipino = voices.firstOrNull { it.locale.language == "fil" || it.locale.language == "tl" }
        val english = voices.firstOrNull { it.locale.language == "en" && it.locale.country == "US" }
            ?: voices.firstOrNull { it.locale.language == "en" }
        val chosen = english ?: filipino
        if (chosen == null) {
            CrashLog.info(appContext, "no offline text-to-speech voice installed, announcements off")
            return
        }
        filipinoVoice = filipino
        englishVoice = english
        engine.voice = chosen
        engine.setSpeechRate(1.0f)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
        })
        speaksFilipino = filipino != null
        isReady = true
        Log.i(TAG, "voices: filipino=${filipino?.name}, english=${english?.name}, offline total ${voices.size}")
        CrashLog.info(appContext, "text-to-speech voices: filipino=${filipino?.name}, english=${english?.name}")
    }

    private var filipinoVoice: Voice? = null
    private var englishVoice: Voice? = null

    private fun finish(id: String?) {
        val done = synchronized(pending) { pending.remove(id ?: return) } ?: return
        main.post(done)
    }

    /**
     * Speak [text], replacing anything still being spoken. [tagalog] picks
     * the Filipino voice when one is installed; English text always gets the
     * English voice, so it is not read with Filipino pronunciation. [onDone]
     * runs on the main thread when it ends, or at once if nothing is spoken.
     */
    fun speak(text: String, tagalog: Boolean = false, onDone: (() -> Unit)? = null) {
        val engine = tts
        if (!isReady || engine == null || text.isBlank()) {
            onDone?.let { main.post(it) }
            return
        }
        val voice = if (tagalog) filipinoVoice ?: englishVoice else englishVoice ?: filipinoVoice
        if (voice != null && engine.voice != voice) runCatching { engine.voice = voice }
        val id = "hf-${++counter}"
        val superseded = synchronized(pending) {
            val old = pending.values.toList()
            pending.clear()
            if (onDone != null) pending[id] = onDone
            old
        }
        superseded.forEach { main.post(it) }
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) finish(id)
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun release() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        isReady = false
        val left = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        left.forEach { main.post(it) }
    }

    companion object {
        private const val TAG = "Announcer"
    }
}
