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

        return when (intentCategory) {
            "APP_LAUNCH" -> launchApp(argumentAfter(utterance, matchedPhrase))
            "CALL_ACCEPT" -> handleCall(utterance)
            "NAVIGATION" -> navigate(utterance, a11y!!)
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

    private fun navigate(utterance: String, a11y: HandsFreeAccessibilityService): Result {
        val u = utterance.lowercase()
        return when {
            u.contains("back") -> Result(a11y.goBack(), "Back")
            u.contains("home") -> Result(a11y.goHome(), "Home")
            u.contains("recent") -> Result(a11y.openRecents(), "Recents")
            u.contains("notification") -> Result(a11y.openNotifications(), "Notifications")
            u.contains("down") || u.contains("next") -> Result(a11y.scroll(true), "Scrolled down")
            u.contains("up") || u.contains("previous") -> Result(a11y.scroll(false), "Scrolled up")
            else -> {
                // Fall back to tapping whatever the user named.
                val bounds = nodeLocator.findNode(a11y.getActiveRoot(), utterance)
                if (bounds != null) {
                    a11y.tapRect(bounds); Result(true, "Tapped \"$utterance\"")
                } else {
                    Result(false, "Nothing on screen matches \"$utterance\"")
                }
            }
        }
    }

    // ---- SMART_SEARCH (entry point ready, not in demo scope) ----

    private fun search(query: String, a11y: HandsFreeAccessibilityService): Result {
        if (query.isBlank()) return Result(false, "No search query heard")
        return if (a11y.dispatchTextInput(query)) {
            Result(true, "Searching for \"$query\"")
        } else {
            Result(false, "No text field on screen")
        }
    }

    // ---- SCHEDULING (entry point ready, not in demo scope) ----

    private fun setAlarm(utterance: String): Result {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, utterance)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent); Result(true, "Opening alarm")
        } catch (t: Throwable) {
            Result(false, "No alarm app available")
        }
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
        private val DECLINE_WORDS = listOf("decline", "reject", "tanggihan", "ayaw")
        private val ANSWER_LABELS = listOf("answer", "accept", "sagutin", "pick up")
        private val DECLINE_LABELS = listOf("decline", "reject", "hang up", "end call")
    }
}
