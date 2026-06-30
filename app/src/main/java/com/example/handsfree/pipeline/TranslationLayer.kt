package com.example.handsfree.pipeline

import org.json.JSONObject

/**
 * Accepts a transcribed string from [VoskSpeechEngine], tokenizes it,
 * looks up each token against a bilingual Tagalog-to-English JSON lexicon
 * loaded from assets, replaces matched tokens with their English equivalents,
 * and returns a normalized English string.
 *
 * Tokens not found in the lexicon pass through unchanged (assumed already English).
 *
 * The lexicon is loaded from assets/translation-lexicon.json.
 */
class TranslationLayer(private val lexicon: JSONObject) {

    /**
     * Normalize a transcribed Tagalog/Taglish string to English.
     *
     * @param transcribed Raw text from VOSK (may contain Tagalog, English, or Taglish)
     * @return Normalized English string ready for intent classification
     */
    fun normalize(transcribed: String): String {
        // TODO: Tokenize by whitespace
        // TODO: For each token, lowercase and look up in lexicon JSON
        // TODO: Replace matched tokens, pass unmatched through unchanged
        // TODO: Rejoin tokens and return
        return transcribed
    }

    companion object {
        /**
         * Load the translation lexicon from assets/translation-lexicon.json.
         */
        fun fromAssets(jsonString: String): TranslationLayer {
            val entries = JSONObject(jsonString).getJSONObject("entries")
            return TranslationLayer(entries)
        }
    }
}
