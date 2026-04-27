package com.ghareeb.smstowhatsapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Drives WhatsApp's UI to deliver a forwarded SMS to a named group automatically.
 * WhatsApp exposes no API to target a group by name, so we walk the accessibility
 * tree to find the row, click it, then click Send. Activation is gated by a
 * short-lived "pending" flag set by WhatsAppIntentHelper right after a matching SMS.
 */
class WhatsAppAutoSendService : AccessibilityService() {

    companion object {
        private const val TAG = "WhatsAppAutoSend"

        const val KEY_PENDING_TARGET = "pending_target_name"
        const val KEY_PENDING_EXPIRES = "pending_expires_at"
        const val KEY_PENDING_MODE = "pending_mode"
        const val MODE_GROUP = "group"
        const val MODE_NUMBER = "number"
        const val PENDING_WINDOW_MS = 60_000L

        private val RETRY_DELAYS_MS = longArrayOf(300, 700, 1200, 2000, 3000, 4500, 6500, 9000)
        private const val GIVE_UP_DELAY_MS = 10_500L

        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

        @Volatile
        private var instance: WhatsAppAutoSendService? = null

        fun isRunning(): Boolean = instance != null

        fun launchIntent(intent: Intent): Boolean {
            val svc = instance ?: return false
            return try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                svc.startActivity(intent)
                svc.scheduleRetries()
                true
            } catch (e: Exception) {
                Log.e(TAG, "startActivity from accessibility service failed", e)
                false
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable { attemptForward("retry") }
    private val giveUpRunnable = Runnable { handleGiveUp() }

    private var sessionTarget: String? = null
    private var lastClickedTarget: String? = null
    private var sentToastShown = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        cancelPendingHandlers()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        cancelPendingHandlers()
        super.onDestroy()
    }

    private fun cancelPendingHandlers() {
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.removeCallbacks(giveUpRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in WHATSAPP_PACKAGES) return
        attemptForward("event:${event.eventType}")
    }

    override fun onInterrupt() {}

    /** Schedules retries after we launch WhatsApp, so we keep trying as the UI populates. */
    private fun scheduleRetries() {
        cancelPendingHandlers()
        for (delay in RETRY_DELAYS_MS) {
            mainHandler.postDelayed(retryRunnable, delay)
        }
        mainHandler.postDelayed(giveUpRunnable, GIVE_UP_DELAY_MS)
    }

    private fun handleGiveUp() {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_PENDING_MODE)) return
        val target = prefs.getString(KEY_PENDING_TARGET, null)
        Log.w(TAG, "Gave up — '$target' not delivered after retries")
        if (target != null) {
            mainHandler.post {
                Toast.makeText(
                    applicationContext,
                    "Could not find '$target' in WhatsApp — open the chat once and try again",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        clearSession(prefs)
    }

    private fun attemptForward(trigger: String) {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val expires = prefs.getLong(KEY_PENDING_EXPIRES, 0L)
        if (System.currentTimeMillis() > expires) {
            if (prefs.contains(KEY_PENDING_MODE)) clearPending(prefs)
            return
        }

        val mode = prefs.getString(KEY_PENDING_MODE, null) ?: return
        val root = findWhatsAppRoot() ?: run {
            Log.d(TAG, "[$trigger] WhatsApp window not yet visible")
            return
        }

        when (mode) {
            MODE_GROUP -> {
                val target = prefs.getString(KEY_PENDING_TARGET, null) ?: return
                resetSessionIfTargetChanged(target)

                if (lastClickedTarget != target) {
                    if (clickTargetChat(root, target)) {
                        Log.d(TAG, "[$trigger] Clicked target chat row: '$target'")
                        lastClickedTarget = target
                        return
                    }
                    // Direct row click didn't work — fall back to WhatsApp's
                    // built-in search. We click the magnifier icon, then type
                    // the target name into the search EditText. The next retry
                    // will see the filtered list (single result) and clickTargetChat
                    // will succeed.
                    if (driveSearchFlow(root, target)) return
                    return
                }

                // Already clicked target row. Before tapping Send, REQUIRE that the
                // target name appears as a text node on this screen (i.e. the chat
                // header shows "Pharmacy"). If it doesn't, we're on the wrong chat
                // and must NOT send — better to fail silently than message a stranger.
                if (!screenShowsTarget(root, target)) {
                    Log.d(TAG, "[$trigger] Waiting for chat screen titled '$target'")
                    return
                }

                if (clickSendButton(root)) {
                    Log.d(TAG, "[$trigger] Send tapped for group: '$target'")
                    showSentToast(target)
                    clearSession(prefs)
                }
            }
            MODE_NUMBER -> {
                if (clickSendButton(root)) {
                    Log.d(TAG, "[$trigger] Send tapped for direct chat")
                    showSentToast(null)
                    clearSession(prefs)
                }
            }
        }
    }

    /**
     * Returns true if any node in the active window has visible text exactly
     * matching [target] (case- and whitespace-normalized). Used to confirm the
     * chat header shows the intended group name before tapping Send.
     */
    private fun screenShowsTarget(root: AccessibilityNodeInfo, target: String): Boolean {
        val normalized = normalize(target)
        if (normalized.isEmpty()) return true
        var found = false
        walkTree(root) { n ->
            if (found) return@walkTree
            if (normalize(n.text?.toString()) == normalized) {
                found = true
            }
        }
        return found
    }

    /**
     * Returns the root of WhatsApp's window, even when a heads-up notification or
     * other transient window has focus on top of it.
     */
    private fun findWhatsAppRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active != null && active.packageName?.toString() in WHATSAPP_PACKAGES) {
            return active
        }
        return try {
            windows?.asSequence()
                ?.mapNotNull { it.root }
                ?.firstOrNull { it.packageName?.toString() in WHATSAPP_PACKAGES }
        } catch (e: Exception) {
            Log.w(TAG, "windows API failed: ${e.message}")
            null
        }
    }

    /**
     * Clicks the contact-picker row matching [target]. Uses a layered set of
     * matchers (strict → emoji-prefix-tolerant → first-word-anchored contains)
     * and a layered set of click strategies (clickable-ancestor performAction
     * → clickable-ancestor gesture → row-sized ancestor gesture).
     *
     * The contains fallback is only consulted as a last resort and remains
     * safe because it requires the matched node's text to actually contain
     * the FULL target string — substrings of other rows' text won't match.
     */
    private fun clickTargetChat(root: AccessibilityNodeInfo, target: String): Boolean {
        val normalized = normalize(target)
        if (normalized.isEmpty()) return false

        val matches = mutableListOf<AccessibilityNodeInfo>()

        // Layer 1: exact match (with optional emoji/decoration prefix).
        walkTree(root) { n ->
            // Don't match the search EditText itself — once we've typed the
            // target into search, its text equals our target and we'd try to
            // "click" it, opening a useless cursor placement.
            if (n.className?.toString() == "android.widget.EditText") return@walkTree
            val nText = normalize(n.text?.toString())
            if (nText.isEmpty()) return@walkTree
            if (nText == normalized) {
                matches.add(n)
                return@walkTree
            }
            val stripped = nText.trimStart { !it.isLetterOrDigit() }.trim()
            if (stripped == normalized) {
                matches.add(n)
            }
        }

        // Layer 2: contains-fallback anchored on the first word of the target,
        // narrowed via findAccessibilityNodeInfosByText so we don't sweep the
        // whole tree. Only kicks in if Layer 1 finds nothing.
        if (matches.isEmpty()) {
            val firstWord = target.trim().split(Regex("\\s+")).firstOrNull()
            if (firstWord != null && firstWord.length >= 3) {
                val candidates = root.findAccessibilityNodeInfosByText(firstWord) ?: emptyList()
                for (n in candidates) {
                    if (n.className?.toString() == "android.widget.EditText") continue
                    val nText = normalize(n.text?.toString())
                    if (nText.isNotEmpty() && nText.contains(normalized)) {
                        matches.add(n)
                    }
                }
            }
        }

        if (matches.isEmpty()) {
            Log.d(TAG, "No title text matches '$target' in current tree")
            return false
        }
        Log.d(TAG, "Found ${matches.size} match(es) for '$target'")

        // Click strategy 1: walk up to a clickable ancestor and performAction.
        for (match in matches) {
            val clickable = findClickableAncestor(match) ?: continue
            if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "Clicked clickable ancestor")
                return true
            }
        }

        // Click strategy 2: gesture tap on the clickable ancestor.
        for (match in matches) {
            val clickable = findClickableAncestor(match) ?: continue
            if (gestureTap(clickable)) {
                Log.d(TAG, "Gesture-tapped clickable ancestor")
                return true
            }
        }

        // Click strategy 3: no clickable ancestor exists — gesture tap the
        // smallest ancestor that's at least 60% of the screen width. That's
        // the row container, even if WhatsApp didn't mark it isClickable.
        for (match in matches) {
            val rowAncestor = findRowAncestor(match) ?: continue
            if (gestureTap(rowAncestor)) {
                Log.d(TAG, "Gesture-tapped row-sized ancestor")
                return true
            }
        }

        Log.w(TAG, "Match found but no clickable/row ancestor for '$target'")
        return false
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 15) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * Walks up from [node] looking for the first ancestor whose on-screen width
     * is at least 60% of the display — that's the chat row in a typical list
     * layout, even when the row isn't marked isClickable.
     */
    private fun findRowAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val screenWidth = resources.displayMetrics.widthPixels
        val minRowWidth = (screenWidth * 0.6).toInt()
        val bounds = Rect()

        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 15) {
            current.getBoundsInScreen(bounds)
            if (bounds.width() >= minRowWidth && bounds.height() in 30..400) {
                return current
            }
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * Drives WhatsApp's built-in search to filter the contact picker down to
     * just the target group. Stateless — every retry re-evaluates the screen
     * and either clicks the search icon or types into the search field.
     *
     * Returns true if it took an action this tick.
     */
    private fun driveSearchFlow(root: AccessibilityNodeInfo, target: String): Boolean {
        val editText = findSearchEditText(root)
        if (editText != null) {
            val current = editText.text?.toString() ?: ""
            if (normalize(current) == normalize(target)) {
                // Search already filtered; the next retry's clickTargetChat
                // will see and click the result.
                return false
            }
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    target
                )
            }
            if (editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                Log.d(TAG, "Typed '$target' into WhatsApp search field")
                return true
            }
            return false
        }

        val searchIcon = findSearchIcon(root) ?: return false
        if (tryClickWithAncestors(searchIcon)) {
            Log.d(TAG, "Clicked WhatsApp search icon")
            return true
        }
        if (gestureTap(searchIcon)) {
            Log.d(TAG, "Gesture-tapped WhatsApp search icon")
            return true
        }
        return false
    }

    private fun findSearchIcon(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ids = listOf(
            "com.whatsapp:id/menuitem_search",
            "com.whatsapp:id/action_search",
            "com.whatsapp:id/search",
            "com.whatsapp.w4b:id/menuitem_search",
            "com.whatsapp.w4b:id/action_search",
            "com.whatsapp.w4b:id/search"
        )
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: continue
            for (n in nodes) {
                if (n.isVisibleToUser) return n
            }
        }
        return findNode(root) { n ->
            val desc = n.contentDescription?.toString() ?: return@findNode false
            (desc.equals("Search", ignoreCase = true) ||
                desc.equals("Search chats", ignoreCase = true)) &&
                n.isVisibleToUser
        }
    }

    private fun findSearchEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findNode(root) { n ->
            n.className?.toString() == "android.widget.EditText" && n.isVisibleToUser
        }
    }

    /**
     * Finds the Send FAB on the share-confirm or chat screen and clicks it.
     * Strict matching only: WhatsApp's resource id, or contentDescription
     * exactly equal to "Send" (no startsWith — that was matching label text
     * like "Send to..." and clicking the wrong thing).
     */
    private fun clickSendButton(root: AccessibilityNodeInfo): Boolean {
        val byId = (root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
            ?: emptyList()) +
            (root.findAccessibilityNodeInfosByViewId("com.whatsapp.w4b:id/send")
                ?: emptyList())
        for (node in byId) {
            if (tryClickWithAncestors(node)) return true
            if (gestureTap(node)) return true
        }

        val byDesc = findNode(root) { n ->
            val desc = n.contentDescription?.toString() ?: return@findNode false
            desc.equals("Send", ignoreCase = true)
        }
        if (byDesc != null) {
            if (tryClickWithAncestors(byDesc)) return true
            if (gestureTap(byDesc)) return true
        }

        return false
    }

    private fun tryClickWithAncestors(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 15) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            depth++
        }
        return false
    }

    private fun gestureTap(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false

        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val path = Path().apply { moveTo(cx, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun walkTree(node: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
        action(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walkTree(child, action)
        }
    }

    private fun findNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNode(child, predicate)
            if (found != null) return found
        }
        return null
    }

    private fun normalize(s: String?): String {
        if (s.isNullOrBlank()) return ""
        // Treat non-breaking / figure / narrow no-break spaces as regular spaces;
        // WhatsApp occasionally uses these in group names rendered with custom fonts.
        return s.trim()
            .replace(Regex("[\\s\\u00A0\\u2007\\u202F]+"), " ")
            .lowercase()
    }

    private fun resetSessionIfTargetChanged(target: String) {
        if (sessionTarget != target) {
            sessionTarget = target
            lastClickedTarget = null
            sentToastShown = false
        }
    }

    private fun clearSession(prefs: SharedPreferences) {
        clearPending(prefs)
        cancelPendingHandlers()
        sessionTarget = null
        lastClickedTarget = null
    }

    private fun clearPending(prefs: SharedPreferences) {
        prefs.edit()
            .remove(KEY_PENDING_TARGET)
            .remove(KEY_PENDING_EXPIRES)
            .remove(KEY_PENDING_MODE)
            .apply()
    }

    private fun showSentToast(target: String?) {
        if (sentToastShown) return
        sentToastShown = true
        mainHandler.post {
            val text = if (target != null) "Sent to $target" else "Sent"
            Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }
}
