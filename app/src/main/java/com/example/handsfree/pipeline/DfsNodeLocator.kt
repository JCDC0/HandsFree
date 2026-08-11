package com.example.handsfree.pipeline

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Depth-first search over the active window's accessibility node tree.
 *
 * Given a target phrase from the intent vocabulary, walks the tree and returns
 * the screen-space bounds of the best matching node so
 * [com.example.handsfree.service.HandsFreeAccessibilityService] can tap it.
 *
 * Matching is deliberately tiered rather than exact. Labels vary wildly across
 * apps and Android versions: the same button may expose text on one device, a
 * contentDescription on another, and only a view id on a third. Each tier is
 * scored, and the highest-scoring visible node wins.
 */
class DfsNodeLocator {

    /** A matched node with the score that produced it. */
    data class Match(val bounds: Rect, val score: Int, val label: String)

    /**
     * Find the best node matching [target].
     *
     * @return bounds of the best match, or null when nothing scores above
     *         [MIN_SCORE].
     */
    fun findNode(root: AccessibilityNodeInfo?, target: String): Rect? =
        findBest(root, target)?.bounds

    /** As [findNode], but keeps the score and matched label for logging. */
    fun findBest(root: AccessibilityNodeInfo?, target: String): Match? {
        if (root == null || target.isBlank()) return null
        val needle = target.trim().lowercase()
        var best: Match? = null

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return

            if (node.isVisibleToUser) {
                val score = scoreNode(node, needle)
                if (score >= MIN_SCORE && (best == null || score > best!!.score)) {
                    val r = Rect().also { node.getBoundsInScreen(it) }
                    if (r.width() > 0 && r.height() > 0) {
                        best = Match(r, score, describe(node))
                    }
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }

        walk(root, 0)
        best?.let { Log.d(TAG, "match \"$target\" -> ${it.label} score=${it.score} ${it.bounds}") }
            ?: Log.d(TAG, "no match for \"$target\"")
        return best
    }

    /**
     * Score a node against the needle. Higher is better.
     *
     * Clickable nodes are boosted because the goal is something tappable, not
     * merely something that contains the word.
     */
    private fun scoreNode(node: AccessibilityNodeInfo, needle: String): Int {
        val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        val viewId = node.viewIdResourceName?.substringAfterLast('/')?.lowercase().orEmpty()

        var score = when {
            text == needle || desc == needle -> 100
            viewId == needle -> 90
            text.startsWith(needle) || desc.startsWith(needle) -> 70
            text.contains(needle) || desc.contains(needle) -> 50
            viewId.contains(needle) -> 40
            else -> 0
        }
        if (score == 0) return 0

        if (node.isClickable) score += 25
        if (node.isEnabled) score += 5
        if (node.className?.contains("Button", ignoreCase = true) == true) score += 10

        return score
    }

    /**
     * Nearest clickable ancestor of [node], or the node itself.
     *
     * Labels frequently sit on a non-clickable TextView nested inside the
     * button that actually handles the tap, so tapping the label's own bounds
     * can miss the touch target.
     */
    fun clickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var hops = 0
        while (current != null && hops < CLICKABLE_ANCESTOR_HOPS) {
            if (current.isClickable) return current
            current = current.parent
            hops++
        }
        return node
    }

    private fun describe(node: AccessibilityNodeInfo): String {
        val t = node.text?.toString().orEmpty()
        val d = node.contentDescription?.toString().orEmpty()
        val id = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
        return "[text=$t desc=$d id=$id clickable=${node.isClickable}]"
    }

    companion object {
        private const val TAG = "DfsNodeLocator"

        /** Guard against pathological trees. Real hierarchies rarely exceed ~30. */
        private const val MAX_DEPTH = 60

        /** Below this, a match is too weak to act on. */
        private const val MIN_SCORE = 40

        private const val CLICKABLE_ANCESTOR_HOPS = 5
    }
}
