package com.example.handsfree.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Operating-system bridge for HandsFree.
 *
 * Two jobs:
 *  1. Read the active window's view hierarchy so [com.example.handsfree.pipeline.DfsNodeLocator]
 *     can find targets.
 *  2. Dispatch gestures, global actions and text input on the user's behalf.
 *
 * It also hosts the hardware activator: holding VOLUME DOWN wakes the
 * listener, which is the closest sanctioned equivalent to Gemini's
 * power-button hold. Android reserves the power button for the system
 * assistant and gives no public API for it, so volume-key capture inside an
 * accessibility service is the supported route.
 *
 * Must be enabled manually at Settings > Accessibility > HandsFree. There is
 * no programmatic way to grant this, by design.
 */
class HandsFreeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "HandsFreeA11y"

        /** Hold duration before the single-key activator fires. */
        const val ACTIVATION_HOLD_MS = 1200L

        /** Tap contact time. Below ~50 ms some launchers ignore the gesture. */
        private const val TAP_DURATION_MS = 60L
        private const val SWIPE_DURATION_MS = 250L

        @Volatile
        var instance: HandsFreeAccessibilityService? = null
            private set

        /** True when the service is enabled and connected. */
        val isConnected: Boolean get() = instance != null
    }

    private var volumeDownPressedAt = 0L
    private var activationFired = false

    // --- Lifecycle ---

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "connected — gesture dispatch and node access available")
        
        runCatching {
            accessibilityButtonController.registerAccessibilityButtonCallback(
                object : AccessibilityButtonController.AccessibilityButtonCallback() {
                    override fun onClicked(controller: AccessibilityButtonController) {
                        Log.i(TAG, "activator: accessibility button clicked -> waking listener")
                        sendActivation()
                    }
                }
            )
        }.onFailure { Log.e(TAG, "failed to register accessibility button callback", it) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Node lookups are performed on demand via rootInActiveWindow, so there
        // is nothing to cache here. Kept for future window-change tracking.
    }

    override fun onInterrupt() {
        Log.d(TAG, "interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // --- Hardware activator ---

    /**
     * Activation by volume keys: hold VOLUME_DOWN alone for
     * [ACTIVATION_HOLD_MS] (about 1.2 s). Short presses always pass through so
     * the user keeps volume control.
     *
     * There is no both-keys activator any more (removed in 1.5.4). Holding both
     * volume keys is Android's own accessibility shortcut. On the TECNO LJ9 the
     * shortcut was assigned to HandsFree, so our gesture also switched the
     * whole service off. VOLUME_UP is not handled at all, so it always reaches
     * the system.
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return handleVolumeDown(event)
        return false
    }

    private fun handleVolumeDown(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (volumeDownPressedAt == 0L) {
                    volumeDownPressedAt = SystemClock.elapsedRealtime()
                    activationFired = false
                }
                if (!activationFired &&
                    SystemClock.elapsedRealtime() - volumeDownPressedAt >= ACTIVATION_HOLD_MS
                ) {
                    activationFired = true
                    Log.i(TAG, "activator: volume-down held -> waking listener")
                    sendActivation()
                    return true
                }
                return activationFired
            }

            KeyEvent.ACTION_UP -> {
                val held = SystemClock.elapsedRealtime() - volumeDownPressedAt
                volumeDownPressedAt = 0L
                val consumed = activationFired
                activationFired = false
                return consumed || held >= ACTIVATION_HOLD_MS
            }
        }
        return false
    }

    private fun sendActivation() {
        runCatching {
            startService(
                Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_ACTIVATE
                }
            )
        }.onFailure { Log.e(TAG, "activation dispatch failed", it) }
    }

    // --- Node tree access ---

    fun getActiveRoot(): AccessibilityNodeInfo? = rootInActiveWindow

    /**
     * Roots of every window this service can see, active window first.
     *
     * An incoming call is normally a heads-up notification living in its own
     * window, so [rootInActiveWindow] returns whatever the user was looking at
     * — frequently our own overlay — and the call banner is invisible to a
     * search that only looks there. `flagRetrieveInteractiveWindows` in
     * `accessibility_service_config.xml` is what makes `windows` non-empty.
     *
     * Additive on purpose: [getActiveRoot] is unchanged, so every existing
     * caller behaves exactly as before.
     */
    fun getAllRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        runCatching {
            windows.forEach { window ->
                window.root?.let { if (it != rootInActiveWindow) roots.add(it) }
            }
        }.onFailure { Log.w(TAG, "window enumeration failed", it) }
        return roots
    }

    // --- Gesture dispatch ---

    /** Single-finger tap at absolute screen coordinates. */
    fun dispatchTap(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        dispatch(GestureDescription.Builder().addStroke(stroke).build(), callback, "tap($x,$y)")
    }

    /** Tap the centre of a bounding rectangle — what [DfsNodeLocator] returns. */
    fun tapRect(bounds: Rect, callback: ((Boolean) -> Unit)? = null) =
        dispatchTap(bounds.exactCenterX(), bounds.exactCenterY(), callback)

    /** Linear swipe. Used for scrolling and revealing off-screen controls. */
    fun dispatchSwipe(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long = SWIPE_DURATION_MS,
        callback: ((Boolean) -> Unit)? = null
    ) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        dispatch(
            GestureDescription.Builder().addStroke(stroke).build(),
            callback,
            "swipe($x1,$y1 -> $x2,$y2)"
        )
    }

    private fun dispatch(
        gesture: GestureDescription,
        callback: ((Boolean) -> Unit)?,
        label: String
    ) {
        val cb = object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) {
                Log.d(TAG, "$label completed"); callback?.invoke(true)
            }

            override fun onCancelled(d: GestureDescription?) {
                Log.w(TAG, "$label cancelled"); callback?.invoke(false)
            }
        }
        if (!dispatchGesture(gesture, cb, null)) {
            Log.e(TAG, "$label rejected by the system")
            callback?.invoke(false)
        }
    }

    // --- Text input ---

    /**
     * Set [text] on the focused editable field.
     *
     * Falls back to searching the tree for any editable node when nothing holds
     * input focus, which is common immediately after an app launches.
     */
    fun dispatchTextInput(text: String): Boolean {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "no active window for text input")
            return false
        }
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditable(root)
            ?: run {
                Log.w(TAG, "no editable field found")
                return false
            }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(TAG, "setText(\"$text\") -> $ok")
        return ok
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            findFirstEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    // --- Global actions (NAVIGATION intent) ---

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    /**
     * Scroll the first scrollable container found, falling back to a swipe
     * across the middle of the screen when no scrollable node is exposed.
     */
    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow
        val scrollable = findScrollable(root)
        if (scrollable != null) {
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (scrollable.performAction(action)) return true
        }
        val metrics = resources.displayMetrics
        val midX = metrics.widthPixels / 2f
        val from = if (forward) metrics.heightPixels * 0.7f else metrics.heightPixels * 0.3f
        val to = if (forward) metrics.heightPixels * 0.3f else metrics.heightPixels * 0.7f
        dispatchSwipe(midX, from, midX, to)
        return true
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            findScrollable(node.getChild(i))?.let { return it }
        }
        return null
    }
}
