package com.example.handsfree.pipeline

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.telecom.TelecomManager
import android.util.Log
import com.example.handsfree.service.HandsFreeAccessibilityService

/**
 * Turns a classified intent into a real action on the device.
 *
 * Sits between [LevenshteinMatcher] (which says *what* the user meant) and
 * [HandsFreeAccessibilityService] (which can actually do it).
 *
 * Prototype scope is the three features chosen for the Aug 13 demo:
 *   APP_LAUNCH  — resolve a spoken app name and start it
 *   CALL_ACCEPT — answer or reject a ringing call
 *   NAVIGATION  — back, home, recents, scroll
 *
 * SMART_SEARCH, MESSAGING and SCHEDULING are stubbed with their entry points
 * in place so they can be finished without restructuring anything.
 */
class CommandExecutor(
    private val context: Context,
    private val nodeLocator: DfsNodeLocator = DfsNodeLocator()
) {

    /** Outcome of an execution attempt, surfaced to the UI. */
    data class Result(val success: Boolean, val message: String)

    /**
     * Execute [intentCategory] with the full normalised utterance as context.
     *
     * @param intentCategory key from intent-vocabulary.json, e.g. "APP_LAUNCH"
     * @param utterance      full normalised text, used to extract arguments
     *                       such as the app name or search query
     */
    fun execute(intentCategory: String, utterance: String, matchedPhrase: String): Result {
        Log.i(TAG, "execute($intentCategory) utterance=\"$utterance\"")

        val a11y = HandsFreeAccessibilityService.instance
        if (a11y == null && intentCategory != "APP_LAUNCH" && intentCategory != "SCHEDULING") {
            return Result(false, "Accessibility Service is off — enable HandsFree in Settings")
        }

        // "pumunta sa home screen" normalises to "go home", which contains an
        // APP_LAUNCH trigger ("go") and a NAVIGATION trigger ("home") at equal
        // distance. org.json does not guarantee key order, so the winner is
        // arbitrary. Route by destination instead of trusting the tie, or the
        // command works on one build and opens a random launcher on the next.
        if (intentCategory == "APP_LAUNCH") {
            val destination = argumentAfter(utterance, matchedPhrase).trim()
            if (NAVIGATION_TARGETS.any { destination.startsWith(it) }) {
                return a11y?.let { navigate(utterance, matchedPhrase, it) }
                    ?: Result(false, "Accessibility Service is off — enable HandsFree in Settings")
            }
        }

        return when (intentCategory) {
            "APP_LAUNCH" -> launchApp(argumentAfter(utterance, matchedPhrase))
            "CALL_ACCEPT" -> handleCall(utterance)
            "NAVIGATION" -> navigate(utterance, matchedPhrase, a11y!!)
            "SMART_SEARCH" -> search(argumentAfter(utterance, matchedPhrase), a11y!!)
            "MESSAGING" -> Result(false, "Messaging not in prototype scope")
            "SCHEDULING" -> setAlarm(utterance)
            else -> Result(false, "Unknown intent: $intentCategory")
        }
    }

    // ---- APP_LAUNCH ----

    /**
     * Resolve a spoken app name to an installed package and launch it.
     *
     * Matching is fuzzy on purpose: at 63% WER the transcript often mangles
     * the name, so exact comparison would fail constantly. Levenshtein against
     * every installed app's label recovers most of it.
     */
    private fun launchApp(spokenName: String): Result {
        if (spokenName.isBlank()) return Result(false, "No app name heard")

        val pm = context.packageManager
        val target = spokenName.trim().lowercase()

        val launchables = pm.getInstalledApplications(0).mapNotNull { info ->
            val label = pm.getApplicationLabel(info).toString()
            val launch = pm.getLaunchIntentForPackage(info.packageName) ?: return@mapNotNull null
            Triple(label, info.packageName, launch)
        }
        if (launchables.isEmpty()) return Result(false, "No launchable apps visible")

        var best: Triple<String, String, Intent>? = null
        var bestDistance = Int.MAX_VALUE

        launchables.forEach { entry ->
            val label = entry.first.lowercase()
            val d = when {
                label == target -> 0
                label.startsWith(target) || target.startsWith(label) -> 1
                label.contains(target) -> 2
                else -> LevenshteinMatcher.levenshteinDistance(target, label)
            }
            if (d < bestDistance) { bestDistance = d; best = entry }
        }

        val chosen = best
        val tolerance = maxOf(2, target.length / 3)
        if (chosen == null || bestDistance > tolerance) {
            return Result(false, "No app matching \"$spokenName\"")
        }

        return try {
            context.startActivity(
                chosen.third.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Result(true, "Opening ${chosen.first}")
        } catch (t: Throwable) {
            Log.e(TAG, "launch failed", t)
            Result(false, "Could not open ${chosen.first}")
        }
    }

    // ---- CALL_ACCEPT ----

    /**
     * Answer or reject a ringing call.
     *
     * [TelecomManager.acceptRingingCall] needs ANSWER_PHONE_CALLS, which is a
     * runtime permission. When it is unavailable the accessibility tree is used
     * instead — the answer control is a normal node on most dialers.
     */
    private fun handleCall(utterance: String): Result {
        val declining = DECLINE_WORDS.any { utterance.contains(it, ignoreCase = true) }

        val telecom = context.getSystemService(TelecomManager::class.java)
        if (telecom != null) {
            try {
                @Suppress("MissingPermission")
                if (declining) {
                    telecom.endCall()
                    return Result(true, "Call declined")
                } else {
                    telecom.acceptRingingCall()
                    return Result(true, "Call answered")
                }
            } catch (se: SecurityException) {
                Log.w(TAG, "Telecom denied, falling back to node tap", se)
            } catch (t: Throwable) {
                Log.w(TAG, "Telecom failed, falling back to node tap", t)
            }
        }

        val a11y = HandsFreeAccessibilityService.instance
            ?: return Result(false, "Accessibility Service is off")
        val labels = if (declining) DECLINE_LABELS else ANSWER_LABELS
        labels.forEach { label ->
            nodeLocator.findNode(a11y.getActiveRoot(), label)?.let { bounds ->
                a11y.tapRect(bounds)
                return Result(true, if (declining) "Call declined" else "Call answered")
            }
        }
        return Result(false, "No incoming call control found")
    }

    // ---- NAVIGATION ----

    private fun navigate(
        utterance: String,
        matchedPhrase: String,
        a11y: HandsFreeAccessibilityService
    ): Result {
        val u = utterance.lowercase()
        return when {
            hasWord(u, "back") -> Result(a11y.goBack(), "Back")
            hasWord(u, "home") -> Result(a11y.goHome(), "Home")
            hasWord(u, "recent") -> Result(a11y.openRecents(), "Recents")
            hasWord(u, "notification") -> Result(a11y.openNotifications(), "Notifications")
            hasWord(u, "down") || hasWord(u, "next") -> Result(a11y.scroll(true), "Scrolled down")
            hasWord(u, "up") || hasWord(u, "previous") -> Result(a11y.scroll(false), "Scrolled up")
            hasWord(u, "scroll") -> Result(a11y.scroll(true), "Scrolled down")
            else -> {
                // Tap-by-name. Search for what the user *named*, not the whole
                // utterance: "press games" must look for "games", because no node
                // is ever labelled "press games".
                val target = argumentAfter(utterance, matchedPhrase).ifBlank { utterance }
                val bounds = nodeLocator.findNode(a11y.getActiveRoot(), target)
                if (bounds != null) {
                    a11y.tapRect(bounds); Result(true, "Tapped \"$target\"")
                } else {
                    Result(false, "Nothing on screen matches \"$target\"")
                }
            }
        }
    }

    /**
     * Whole-word (plus optional plural) test.
     *
     * Substring matching sent "uksan treloh" to scroll-up because it contains
     * "up", and would send anything mentioning "backup" to the back gesture.
     */
    private fun hasWord(text: String, word: String): Boolean =
        Regex("\\b${Regex.escape(word)}s?\\b").containsMatchIn(text)

    private fun search(query: String, a11y: HandsFreeAccessibilityService): Result {
        if (query.isBlank()) return Result(false, "No search query heard")
        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(android.app.SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Result(true, "Searching for \"$query\"")
        } catch (t: Throwable) {
            Result(false, "No search app available")
        }
    }

    // ---- SCHEDULING (entry point ready, not in demo scope) ----

    private fun setAlarm(utterance: String): Result {
        val u = utterance.lowercase()

        // "timer 45 minutes" is a countdown, not a wall-clock alarm. Different
        // AlarmClock action, and the stock Clock app handles both.
        val timerSeconds = if (hasWord(u, "timer") || hasWord(u, "countdown")) {
            parseDurationSeconds(u)
        } else null

        val (intent, label) = if (timerSeconds != null) {
            Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, timerSeconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, "HandsFree timer")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            } to "Timer for ${timerSeconds / 60} min"
        } else {
            val time = parseTimeOfDay(u)
            Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_MESSAGE, "HandsFree alarm")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                if (time != null) {
                    putExtra(AlarmClock.EXTRA_HOUR, time.first)
                    putExtra(AlarmClock.EXTRA_MINUTES, time.second)
                }
            } to if (time != null) {
                "Alarm at %02d:%02d".format(time.first, time.second)
            } else "Opening alarm"
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            Result(true, label)
        } catch (se: SecurityException) {
            // Missing SET_ALARM in the manifest looks exactly like "no clock app"
            // from here. It cost a whole test round on 2026-08-12; name it.
            Log.e(TAG, "AlarmClock denied — SET_ALARM permission missing", se)
            Result(false, "Alarm blocked — SET_ALARM permission missing")
        } catch (t: Throwable) {
            Log.e(TAG, "AlarmClock intent failed", t)
            Result(false, "No clock app handled the alarm")
        }
    }

    /**
     * Countdown length in seconds from phrases like "timer 45 minutes",
     * "timer for 5 mins", "timer 1 hour". Returns null when no number is heard.
     */
    private fun parseDurationSeconds(u: String): Int? {
        val withUnit = Regex("(\\d{1,3})\\s*(hour|hr|minute|min|second|sec)").find(u)
        if (withUnit != null) {
            val value = withUnit.groupValues[1].toIntOrNull() ?: return null
            return when (withUnit.groupValues[2]) {
                "hour", "hr" -> value * 3600
                "second", "sec" -> value
                else -> value * 60
            }
        }
        // Bare number, e.g. "timer 45" — minutes is the safe reading.
        val bare = Regex("\\b(\\d{1,3})\\b").find(u)?.groupValues?.get(1)?.toIntOrNull()
        return bare?.times(60)
    }

    /**
     * 24-hour (hour, minute) from "7 p.m.", "seven thirty am", "19:30".
     * Returns null when no time is present, which leaves the Clock app on its
     * new-alarm screen rather than guessing.
     */
    private fun parseTimeOfDay(u: String): Pair<Int, Int>? {
        val m = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*([ap])\\s*\\.?\\s*m\\.?").find(u)
            ?: Regex("(\\d{1,2}):(\\d{2})").find(u)
            ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
        val meridiem = m.groupValues.getOrNull(3).orEmpty()
        if (meridiem == "p" && hour < 12) hour += 12
        if (meridiem == "a" && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    // ---- helpers ----

    /**
     * Everything after the matched trigger word is treated as the argument.
     * "open facebook" with trigger "open" yields "facebook".
     */
    private fun argumentAfter(utterance: String, matchedPhrase: String): String {
        val idx = utterance.lowercase().indexOf(matchedPhrase.lowercase())
        if (idx < 0) {
            // Trigger was fuzzy-matched and is not literally present; drop the
            // first token and treat the rest as the argument.
            return utterance.substringAfter(' ', "").trim()
        }
        return utterance.substring(idx + matchedPhrase.length).trim()
    }

    companion object {
        private const val TAG = "CommandExecutor"
        /**
         * Destinations that are OS navigation, never an installed app. An
         * APP_LAUNCH whose argument starts with one of these is really a
         * NAVIGATION command that lost a tie. "settings" is deliberately absent
         * — it is a real launchable app.
         */
        private val NAVIGATION_TARGETS = listOf(
            "home", "back", "recents", "recent apps", "notifications", "notification"
        )

        private val DECLINE_WORDS = listOf("decline", "reject", "tanggihan", "ayaw")
        private val ANSWER_LABELS = listOf("answer", "accept", "sagutin", "pick up")
        private val DECLINE_LABELS = listOf("decline", "reject", "hang up", "end call")
    }
}
