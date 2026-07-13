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
 */
class TranslationLayer private constructor(private val lexicon: Map<String, String>) {

    /**
     * Normalize a transcribed Tagalog/Taglish string to English.
     *
     * Tokenizes on whitespace, lowercases each token, looks it up in the
     * bilingual lexicon, replaces matched tokens with their English equivalents,
     * and rejoins. Unmatched tokens pass through unchanged.
     *
     * Multi-word lexicon entries (e.g. "mga setting" → "settings") are matched
     * greedily left-to-right with a 2-token lookahead before falling back to
     * single-token lookup.
     *
     * @param transcribed Raw text from VOSK (may contain Tagalog, English, or Taglish)
     * @return Normalized English string ready for intent classification
     */
    fun normalize(transcribed: String): String {
        if (transcribed.isBlank()) return transcribed

        val tokens = transcribed.trim().lowercase().split("\\s+".toRegex())
        val result = mutableListOf<String>()
        var i = 0

        while (i < tokens.size) {
            // Greedy 2-token lookahead for multi-word entries (e.g. "mga setting")
            if (i + 1 < tokens.size) {
                val bigram = "${tokens[i]} ${tokens[i + 1]}"
                val bigramTranslation = lexicon[bigram]
                if (bigramTranslation != null) {
                    result.add(bigramTranslation)
                    i += 2
                    continue
                }
            }
            // Single-token lookup
            val translation = lexicon[tokens[i]]
            result.add(translation ?: tokens[i])
            i++
        }

        return result.joinToString(" ")
    }

    /**
     * Look up a single lowercased token in the lexicon.
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
         * Expected JSON shape:
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
