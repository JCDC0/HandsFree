package com.example.handsfree.pipeline

import org.json.JSONObject

/**
 * Fuzzy intent classifier using Levenshtein Distance.
 *
 * Accepts a normalized English string, computes Levenshtein Distance against
 * each entry in the intent vocabulary JSON (assets/intent-vocabulary.json),
 * and returns the closest matching intent label if the distance is within
 * a configurable threshold.
 */
class LevenshteinMatcher(private val vocabulary: JSONObject) {

    /**
     * Classify a normalized English phrase into an intent category.
     *
     * @param input Normalized English text from TranslationLayer
     * @return Pair of (intentCategory, matchedPhrase) or null if no match within threshold
     */
    fun classify(input: String): Pair<String, String>? {
        // TODO: For each intent category and its phrases:
        // TODO:   Compute Levenshtein distance between input and each phrase
        // TODO:   Track the best (lowest distance) match
        // TODO: If best distance <= threshold, return (category, phrase)
        // TODO: Otherwise return null (unrecognized)
        return null
    }

    companion object {
        /**
         * Load the intent vocabulary from assets/intent-vocabulary.json.
         */
        fun fromAssets(jsonString: String): LevenshteinMatcher {
            val intents = JSONObject(jsonString).getJSONObject("intents")
            return LevenshteinMatcher(intents)
        }

        /**
         * Compute Levenshtein Distance between two strings.
         * Implementation: standard dynamic programming O(n*m).
         */
        fun levenshteinDistance(a: String, b: String): Int {
            // TODO: Implement DP table
            return 0
        }
    }
}
