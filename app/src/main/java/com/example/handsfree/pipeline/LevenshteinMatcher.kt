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
     * Maximum Levenshtein distance allowed for a match.
     *
     * A threshold of 2 handles minor transcription errors (one substitution +
     * one deletion) without too many false positives. Raise to 3 if speech
     * recognition is noisier than expected during testing.
     */
    private val threshold: Int = 2

    /**
     * Classify a normalized English phrase into an intent category.
     *
     * Algorithm:
     *  1. Lowercase + trim the input.
     *  2. For each intent category and each of its vocabulary phrases, compute
     *     the Levenshtein distance between [input] and the phrase.
     *  3. Track the (category, phrase, distance) triple with the lowest distance.
     *  4. If the best distance is within [threshold], return the pair; else null.
     *
     * @param input Normalized English text from TranslationLayer
     * @return Pair of (intentCategory, matchedPhrase) or null if no match within threshold
     */
    fun classify(input: String): Pair<String, String>? {
        if (input.isBlank()) return null

        val normalizedInput = input.trim().lowercase()

        var bestCategory: String? = null
        var bestPhrase: String? = null
        var bestDistance = Int.MAX_VALUE

        val categoryKeys = vocabulary.keys()
        while (categoryKeys.hasNext()) {
            val category = categoryKeys.next()
            val categoryObj = vocabulary.optJSONObject(category) ?: continue
            val phrasesArray = categoryObj.optJSONArray("phrases") ?: continue

            for (j in 0 until phrasesArray.length()) {
                val phrase = phrasesArray.optString(j).lowercase()
                val distance = levenshteinDistance(normalizedInput, phrase)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestCategory = category
                    bestPhrase = phrase
                }
            }
        }

        return if (bestDistance <= threshold && bestCategory != null && bestPhrase != null) {
            Pair(bestCategory, bestPhrase)
        } else {
            null
        }
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
         *
         * Standard dynamic programming O(n × m) implementation.
         * Returns the minimum number of single-character edits (insertions,
         * deletions, substitutions) required to change [a] into [b].
         */
        fun levenshteinDistance(a: String, b: String): Int {
            val la = a.length
            val lb = b.length

            // Optimization: empty string edge cases
            if (la == 0) return lb
            if (lb == 0) return la

            // dp[i][j] = edit distance between a[0..i-1] and b[0..j-1]
            val dp = Array(la + 1) { IntArray(lb + 1) }

            // Base cases: transforming empty string → prefix costs i insertions
            for (i in 0..la) dp[i][0] = i
            for (j in 0..lb) dp[0][j] = j

            for (i in 1..la) {
                for (j in 1..lb) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    dp[i][j] = minOf(
                        dp[i - 1][j] + 1,       // deletion
                        dp[i][j - 1] + 1,       // insertion
                        dp[i - 1][j - 1] + cost // substitution
                    )
                }
            }

            return dp[la][lb]
        }
    }
}
