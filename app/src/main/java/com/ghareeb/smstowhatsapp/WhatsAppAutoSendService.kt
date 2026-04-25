package com.ghareeb.smstowhatsapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

        private const val MAX_SCROLLS_PER_SESSION = 6
        private const val SCROLL_THROTTLE_MS = 800L

        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

        @Volatile
        private var instance: WhatsAppAutoSendService? = null

        fun isRunning(): Boolean = instance != null

        /**
         * Launches [intent] from the accessibility service context. The service is
         * system-bound and exempt from Android 10+ background activity-start
         * restrictions, so this works even when triggered from a BroadcastReceiver
         * with no visible UI.
         */
        fun launchIntent(intent: Intent): Boolean {
            val svc = instance ?: return false
            return try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                svc.startActivity(intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "startActivity from accessibility service failed", e)
                false
            }
        }
    }

    private var scrollCount = 0
    private var lastScrollAt = 0L
    private var sessionTarget: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in WHATSAPP_PACKAGES) return

        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val expires = prefs.getLong(KEY_PENDING_EXPIRES, 0L)
        if (System.currentTimeMillis() > expires) {
            if (prefs.contains(KEY_PENDING_MODE)) clearPending(prefs)
            return
        }

        val mode = prefs.getString(KEY_PENDING_MODE, null) ?: return
        val root = rootInActiveWindow ?: return

        when (mode) {
            MODE_GROUP -> {
                val target = prefs.getString(KEY_PENDING_TARGET, null) ?: return
                resetSessionIfTargetChanged(target)

                if (clickTargetChat(root, target)) {
                    Log.d(TAG, "Clicked target chat row: '$target'")
                    return
                }
                if (clickSendButton(root)) {
                    Log.d(TAG, "Send tapped for group: '$target'")
                    clearPending(prefs)
                    sessionTarget = null
                    return
                }
                // Target not on screen yet — try scrolling so more rows load.
                tryScroll(root)
            }
            MODE_NUMBER -> {
                if (clickSendButton(root)) {
                    Log.d(TAG, "Send tapped for direct chat")
                    clearPending(prefs)
                }
            }
        }
    }

    override fun onInterrupt() {}

    private fun resetSessionIfTargetChanged(target: String) {
        if (sessionTarget != target) {
            sessionTarget = target
            scrollCount = 0
            lastScrollAt = 0L
        }
    }

    /**
     * Finds a clickable node whose subtree contains a text or content-description
     * matching [target] (case-insensitive, whitespace-normalized) and clicks it.
     * Falls back to a gesture tap on the node's center if the click action fails.
     */
    private fun clickTargetChat(root: AccessibilityNodeInfo, target: String): Boolean {
        val normalized = normalize(target)
        if (normalized.isEmpty()) return false

        val clickables = mutableListOf<AccessibilityNodeInfo>()
        walkTree(root) { if (it.isClickable) clickables.add(it) }

        for (clickable in clickables) {
            if (!subtreeMatchesText(clickable, normalized)) continue
            if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            if (gestureTap(clickable)) return true
        }
        return false
    }

    /**
     * Finds and clicks the Send FAB on the share-confirmation screen.
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
            val desc = n.contentDescription?.toString()?.lowercase() ?: return@findNode false
            desc == "send" || desc == "send message" || desc.startsWith("send ")
        }
        if (byDesc != null) {
            if (tryClickWithAncestors(byDesc)) return true
            if (gestureTap(byDesc)) return true
        }

        return false
    }

    private fun tryScroll(root: AccessibilityNodeInfo): Boolean {
        if (scrollCount >= MAX_SCROLLS_PER_SESSION) return false
        val now = System.currentTimeMillis()
        if (now - lastScrollAt < SCROLL_THROTTLE_MS) return false

        val scrollable = findNode(root) { it.isScrollable } ?: return false
        val ok = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        if (ok) {
            scrollCount++
            lastScrollAt = now
            Log.d(TAG, "Scrolled forward (attempt $scrollCount/$MAX_SCROLLS_PER_SESSION)")
        }
        return ok
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

    private fun subtreeMatchesText(node: AccessibilityNodeInfo, target: String): Boolean {
        var matched = false
        walkTree(node) { n ->
            if (matched) return@walkTree
            if (normalize(n.text?.toString()) == target ||
                normalize(n.contentDescription?.toString()) == target) {
                matched = true
            }
        }
        return matched
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
        return s.trim().replace(Regex("\\s+"), " ").lowercase()
    }

    private fun clearPending(prefs: SharedPreferences) {
        prefs.edit()
            .remove(KEY_PENDING_TARGET)
            .remove(KEY_PENDING_EXPIRES)
            .remove(KEY_PENDING_MODE)
            .apply()
    }
}
