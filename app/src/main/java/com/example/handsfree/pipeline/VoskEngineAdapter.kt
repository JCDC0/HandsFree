package com.example.handsfree.pipeline

import android.content.Context

/**
 * Adapts the existing [VoskSpeechEngine] to the [SpeechEngine] contract.
 *
 * Written as a wrapper rather than by modifying [VoskSpeechEngine] directly so
 * the VOSK path — the only one that currently runs end to end — stays exactly
 * as it was while the Whisper export is outstanding. When Whisper is live this
 * class and VOSK can be deleted together.
 */
class VoskEngineAdapter(context: Context) : SpeechEngine {

    private val delegate = VoskSpeechEngine(context)

    @Volatile
    override var isReady: Boolean = false
        private set

    override var onReady: (() -> Unit)? = null
        set(value) {
            field = value
            delegate.onReady = {
                isReady = true
                value?.invoke()
            }
        }

    override var onError: ((Throwable) -> Unit)? = null
        set(value) {
            field = value
            delegate.onError = { t ->
                isReady = false
                value?.invoke(t)
            }
        }

    override val activeLanguage: String
        get() = delegate.activeLanguage

    override val displayName: String
        get() = "VOSK (${delegate.activeLanguage})"

    override fun prepare(language: String) {
        delegate.unpackModelsIfNeeded(language)
    }

    override fun switchModel(language: String) {
        isReady = false
        delegate.switchModel(language)
    }

    override fun acceptWaveform(buffer: ShortArray): String? = delegate.acceptWaveform(buffer)

    override fun getPartialResult(): String = delegate.getPartialResult()

    override fun release() {
        isReady = false
        delegate.release()
    }
}
