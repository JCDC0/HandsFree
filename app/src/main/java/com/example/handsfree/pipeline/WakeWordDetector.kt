package com.example.handsfree.pipeline

/**
 * Decides whether a transcript was addressed to HandsFree, and returns whatever
 * was said after the call sign.
 *
 * The app has no separate hotword engine. Always-on mode simply keeps running
 * the normal listen-transcribe cycle and discards every utterance that does not
 * open with the call sign — so the wake word is matched against the same 63% WER
 * Whisper output as everything else, and has to tolerate the same garble.
 * "hey handsfree" arrives as "hey hands free", "ay hensfri", "hi handfree".
 *
 * Two things follow from that:
 *
 * 1. **Match across token boundaries.** Whisper's spacing is unreliable, so
 *    candidates are formed by joining up to [MAX_WINDOW] consecutive tokens and
 *    comparing without spaces: "hands free" and "handsfree" are the same string
 *    by the time they are compared.
 * 2. **Keep a variant list, like the lexicon.** Tolerance alone cannot absorb
 *    this much noise without firing on ordinary speech, so known mishearings are
 *    listed explicitly. Adding one is a one-line change — the same maintenance
 *    loop as `translation-lexicon.json`, and the right first response when a
 *    tester reports that the call sign is not waking the app.
 *
 * Deterministic: same transcript in, same decision out. No model, no threshold
 * on a probability, nothing sampled.
 */
class WakeWordDetector(private val variants: List<String> = DEFAULT_VARIANTS) {

    /**
     * @property command what was said after the call sign, empty when the call
     *           sign was the whole utterance
     * @property matched the variant that fired, for the log
     */
    data class Hit(val command: String, val matched: String)

    /** @return null when the utterance was not addressed to HandsFree. */
    fun detect(transcript: String): Hit? {
        if (transcript.isBlank()) return null
        val tokens = transcript.trim().lowercase().split(WHITESPACE)
        if (tokens.isEmpty()) return null

        for (start in tokens.indices) {
            for (size in 1..MAX_WINDOW) {
                val end = start + size
                if (end > tokens.size) break

                val candidate = tokens.subList(start, end).joinToString("") { squash(it) }
                if (candidate.isEmpty()) continue

                val hit = variants.firstOrNull { variant ->
                    LevenshteinMatcher.levenshteinDistance(candidate, variant) <= tolerance(variant)
                } ?: continue

                // Everything before the call sign is discarded — it is the
                // "hey" in "hey handsfree", or noise the room contributed.
                return Hit(tokens.drop(end).joinToString(" ").trim(), hit)
            }
        }
        return null
    }

    private fun squash(token: String) = token.filter { it.isLetterOrDigit() }

    /**
     * Edit budget for a variant, scaled to its length.
     *
     * Same reasoning as the four-character rule for trigger phrases in
     * `DEV_GUIDE.md` §4.3: a fixed budget against a short string accepts
     * everything. Nine characters of "handsfree" buys two edits; the empty
     * budget below five characters is why no variant shorter than that is
     * listed.
     */
    private fun tolerance(variant: String) = when {
        variant.length >= 11 -> 3
        variant.length >= 8 -> 2
        variant.length >= 5 -> 1
        else -> 0
    }

    companion object {
        private val WHITESPACE = "\\s+".toRegex()

        /** Tokens joined per candidate — covers "hands free" and "hey hands free". */
        private const val MAX_WINDOW = 3

        /**
         * Known spellings of the call sign, squashed and lowercased.
         *
         * Add mishearings here as testers report them. Keep every entry at five
         * characters or more, and keep them distinctive — a variant close to an
         * ordinary Tagalog or English word will wake the app mid-conversation.
         */
        val DEFAULT_VARIANTS = listOf(
            "heyhandsfree", "hayhandsfree", "hihandsfree",
            "handsfree", "handfree", "hansfree", "hanfree",
            "handsfri", "hansfri", "hanfri", "hensfri", "hensfree",
            "handsprey", "handspree", "handsfrie", "andsfree"
        )
    }
}
