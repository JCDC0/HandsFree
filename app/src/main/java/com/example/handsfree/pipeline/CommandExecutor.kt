package com.example.handsfree.pipeline

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.provider.AlarmClock
import android.telecom.TelecomManager
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.handsfree.service.HandsFreeAccessibilityService
import com.example.handsfree.util.Connectivity

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

    /**
     * Outcome of an execution attempt, surfaced to the UI.
     *
     * [warning] is a separate channel from [message] on purpose: it describes a
     * condition outside the app's control that will spoil the result (today,
     * only "no internet" on a web search). It is not a failure — the command
     * matched and ran — so folding it into [message] would make the transcript
     * log read as a recognition problem when the pipeline did its job.
     * Defaulted, so no existing construction site changes.
     */
    data class Result(
        val success: Boolean,
        val message: String,
        val warning: String? = null
    )

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
        // CALL_ACCEPT is exempt: with ANSWER_PHONE_CALLS granted, TelecomManager
        // answers without the accessibility service being involved at all. The
        // service is only needed for the node-click fallback.
        if (a11y == null && intentCategory !in NO_A11Y_REQUIRED) {
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

        // An explicit tap verb wins over whatever the object happens to collide
        // with. "pindutin ang search google" normalises to "press search
        // google", which carries NAVIGATION "press" and SMART_SEARCH "google"
        // at distance 0 — the user is naming a thing to tap, not asking for a
        // web search. No lexicon entry can separate these: any object
        // containing "search" or "google" pulls the search intent in with it.
        if (intentCategory != "NAVIGATION") {
            val verb = TAP_VERBS.firstOrNull { utterance.startsWith(it, ignoreCase = true) }
            if (verb != null) {
                return a11y?.let { navigate(utterance, verb, it) }
                    ?: Result(false, "Accessibility Service is off — enable HandsFree in Settings")
            }
        }

        return when (intentCategory) {
            "APP_LAUNCH" -> launchApp(argumentAfter(utterance, matchedPhrase))
            "CALL_ACCEPT" -> handleCall(utterance)
            "NAVIGATION" -> navigate(utterance, matchedPhrase, a11y!!)
            "SMART_SEARCH" -> search(argumentAfter(utterance, matchedPhrase))
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
        val verb = if (declining) "declined" else "answered"

        // Only trust Telecom if the permission is actually held. Without
        // ANSWER_PHONE_CALLS these calls do not throw on every OEM — some
        // silently do nothing, and the old code returned "Call answered" while
        // the phone kept ringing. A false success is worse than a failure: it
        // stops us falling back, and it lies in the transcript log.
        val telecom = context.getSystemService(TelecomManager::class.java)
        if (telecom != null && hasAnswerCallPermission()) {
            try {
                if (declining) telecom.endCall() else telecom.acceptRingingCall()
                return Result(true, "Call $verb")
            } catch (se: SecurityException) {
                Log.w(TAG, "Telecom denied despite permission, falling back", se)
            } catch (t: Throwable) {
                Log.w(TAG, "Telecom failed, falling back to node click", t)
            }
        } else if (telecom != null) {
            Log.w(TAG, "ANSWER_PHONE_CALLS not granted — using the node tree instead")
        }

        val a11y = HandsFreeAccessibilityService.instance ?: return Result(
            false,
            "Grant Phone permission in the app, or turn on the Accessibility Service"
        )
        val labels = if (declining) DECLINE_LABELS else ANSWER_LABELS
        return if (clickCallControl(a11y, labels)) {
            Result(true, "Call $verb")
        } else {
            Result(false, "No incoming call control found")
        }
    }

    private fun hasAnswerCallPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Find and click an answer/decline control in **any** visible window.
     *
     * Two reasons this does not go through [DfsNodeLocator.findNode]:
     *
     * 1. A ringing call is usually a heads-up notification, which lives in its
     *    own window. `rootInActiveWindow` returns whatever the user was looking
     *    at — often our own overlay — so searching only the active window found
     *    nothing while the call banner was plainly on screen.
     * 2. `ACTION_CLICK` on the node beats tapping its centre coordinate. Call
     *    banners are frequently laid out so the centre of the matched node is
     *    not a touch target.
     *
     * Falls back to a coordinate tap when the click action is refused.
     */
    private fun clickCallControl(
        a11y: HandsFreeAccessibilityService,
        labels: List<String>
    ): Boolean {
        a11y.getAllRoots().forEach { root ->
            labels.forEach { label ->
                val hit = findLabelled(root, label, 0)
                if (hit != null) {
                    val target = nodeLocator.clickableAncestor(hit) ?: hit
                    Log.i(TAG, "call control matched \"$label\"")
                    if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    val bounds = Rect().also { target.getBoundsInScreen(it) }
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        a11y.tapRect(bounds)
                        return true
                    }
                }
            }
        }
        return false
    }

    /** First visible node whose text, description or id contains [label]. */
    private fun findLabelled(
        node: AccessibilityNodeInfo?,
        label: String,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_TREE_DEPTH) return null
        if (node.isVisibleToUser) {
            val haystack = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString(),
                node.viewIdResourceName?.substringAfterLast('/')
            ).joinToString(" ").lowercase()
            if (haystack.contains(label.lowercase())) return node
        }
        for (i in 0 until node.childCount) {
            findLabelled(node.getChild(i), label, depth + 1)?.let { return it }
        }
        return null
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
                val match = bestNodeFor(a11y, target)
                if (match != null) {
                    a11y.tapRect(match.first); Result(true, "Tapped \"${match.second}\"")
                } else {
                    Result(false, "Nothing on screen matches \"$target\"")
                }
            }
        }
    }

    /**
     * Best on-screen node for [target], falling back to its individual words.
     *
     * `DfsNodeLocator` needs the needle to be a substring of the label, so a
     * two-word spoken target rarely matches a real control: "search google"
     * finds nothing on a browser whose field reads "Search or type URL", while
     * "search" finds it outright. Words are tried only after the whole phrase
     * misses, so nothing that resolves today can change — this can only convert
     * a current failure into a tap.
     *
     * Words shorter than four characters are skipped for the same reason
     * trigger phrases are (DEV_GUIDE §4.3): they match too much.
     *
     * @return bounds and the text that matched, or null if nothing scored.
     */
    private fun bestNodeFor(
        a11y: HandsFreeAccessibilityService,
        target: String
    ): Pair<Rect, String>? {
        val root = a11y.getActiveRoot()
        nodeLocator.findNode(root, target)?.let { return it to target }

        val words = target.split(' ').filter { it.length >= 4 }
        if (words.size < 2) return null

        var best: Pair<Rect, String>? = null
        var bestScore = 0
        words.forEach { word ->
            val match = nodeLocator.findBest(a11y.getActiveRoot(), word)
            if (match != null && match.score > bestScore) {
                bestScore = match.score
                best = match.bounds to word
            }
        }
        return best
    }

    /**
     * Whole-word (plus optional plural) test.
     *
     * Substring matching sent "uksan treloh" to scroll-up because it contains
     * "up", and would send anything mentioning "backup" to the back gesture.
     */
    private fun hasWord(text: String, word: String): Boolean =
        Regex("\\b${Regex.escape(word)}s?\\b").containsMatchIn(text)

    /**
     * Run a web search for [query] and land on the results.
     *
     * [Intent.ACTION_WEB_SEARCH] only *prefills* the query on most handlers —
     * the browser opens with the words sitting in the search box waiting for a
     * keypress, which is precisely the thing a hands-free app cannot do. Loading
     * the results URL directly executes the search instead.
     *
     * The trade-off is that the engine is fixed rather than following the user's
     * default. `ACTION_WEB_SEARCH` is kept as the fallback, so a device with no
     * browser registered for http still behaves as it did before.
     *
     * This does not weaken the offline claim in §1: the app makes no network
     * call, it hands a URL to the browser. Transcription, translation and intent
     * matching remain entirely on-device — a web search is a network action by
     * definition and always was.
     *
     * SMART_SEARCH is the one intent that needs a connection, so it is the one
     * intent that checks. The search is still handed to the browser when the
     * device is offline — the check can be wrong, and refusing to act on it
     * would be a worse failure than a browser error page — but the tester is
     * told why the page is blank, and the transcript log records it so a
     * reviewer does not read the entry as a recognition failure.
     */
    private fun search(query: String): Result {
        if (query.isBlank()) return Result(false, "No search query heard")

        val warning =
            if (Connectivity.hasInternet(context)) null else OFFLINE_WARNING
        if (warning != null) Log.w(TAG, "SMART_SEARCH with no internet: \"$query\"")

        val results = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("$SEARCH_URL${Uri.encode(query)}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            context.startActivity(results)
            return Result(true, "Searching for \"$query\"", warning)
        } catch (t: Throwable) {
            Log.w(TAG, "no browser for a results URL, falling back to ACTION_WEB_SEARCH", t)
        }

        val webSearch = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(android.app.SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(webSearch)
            Result(true, "Searching for \"$query\"", warning)
        } catch (t: Throwable) {
            Result(false, "No search app available", warning)
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

        /**
         * Explicit "tap this thing" verbs. Longest first — "press on" must be
         * tested before "press" or the argument keeps a leading "on".
         */
        private val TAP_VERBS = listOf("press on", "tap on", "press", "click", "select")

        /** Guard against pathological trees, matching `DfsNodeLocator`. */
        private const val MAX_TREE_DEPTH = 60

        /** Results-page prefix. The query is appended URL-encoded. */
        private const val SEARCH_URL = "https://www.google.com/search?q="

        /**
         * Shown to the tester and written to the transcript log when a search
         * runs with no connection. Bilingual because the testers are, and
         * because whoever reads the exported log may not be.
         */
        const val OFFLINE_WARNING =
            "Walang internet — hindi mag-lo-load ang hinanap. " +
                "(No internet connection: the search results will not load.)"

        /** Intents that can still do something useful with the service off. */
        private val NO_A11Y_REQUIRED = setOf("APP_LAUNCH", "SCHEDULING", "CALL_ACCEPT")

        private val DECLINE_WORDS = listOf("decline", "reject", "tanggihan", "ayaw")
        private val ANSWER_LABELS = listOf("answer", "accept", "sagutin", "pick up")
        private val DECLINE_LABELS = listOf("decline", "reject", "hang up", "end call")
    }
}
