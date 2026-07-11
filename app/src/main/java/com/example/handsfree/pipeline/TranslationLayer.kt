package com.example.handsfree.pipeline

import org.json.JSONObject

/**
 * Accepts a transcribed string from [VoskSpeechEngine], tokenizes it,
 * looks up each token against a bilingual Tagalog-to-English JSON lexicon
 * loaded from assets, replaces matched tokens with their English equivalents,
 * and returns a normalized English string.
 *
 * Tokens not found in the lexicon pass through unchanged (assumed already
 * English). The lexicon lives in `assets/translation-lexicon.json`.
 *
 * -----
 * PHASE 1 STATUS: **STUB — passthrough.** [normalize] returns its input
 * unchanged. Harold will implement the real token-replacement logic later;
 * the lexicon is parsed into a [Map] here so that lookup is ready to drop in.
 */
class TranslationLayer private constructor(private val lexicon: Map<String, String>) {

    /**
     * Normalize a transcribed Tagalog/Taglish string to English.
     *
     * Phase 1: passthrough — input is returned unchanged.
     *
     * @param transcribed Raw text from VOSK (may contain Tagalog, English, or Taglish)
     * @return Normalized English string ready for intent classification
     */
    fun normalize(transcribed: String): String {
        if (transcribed.isBlank()) return transcribed
        return transcribed.trim().split(Regex("\\s+")).joinToString(" ") { token ->
            lexicon[token.lowercase()] ?: token
        }
    }

    /**
     * Look up a single lowercased token in the lexicon. For Harold's future
     * replacement implementation; not used by the stub.
     *
     * @return the English equivalent, or `null` if the token is not found.
     */
    fun lookup(token: String): String? = lexicon[token.lowercase()]

    /** Number of entries loaded from the lexicon JSON. */
    val size: Int get() = lexicon.size

    companion object {
        /**
         * Load the translation lexicon from `assets/translation-lexicon.json`.
         *
         * Expected JSON shape (already present in the repo):
         * ```
         * { "entries": { "buksan": "open", "tumawag": "call", ... } }
         * ```
         */
        fun fromAssets(jsonString: String): TranslationLayer {
            val entries = JSONObject(jsonString).getJSONObject("entries")
            val map = LinkedHashMap<String, String>()
            val keys = entries.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key.lowercase()] = entries.getString(key)
            }
            return TranslationLayer(map)
        }
    }
}