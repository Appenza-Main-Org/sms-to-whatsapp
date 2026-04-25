package com.ghareeb.smstowhatsapp

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Automates the final "pick group + tap send" steps inside WhatsApp after
 * our app fires a share intent. WhatsApp exposes no API to target a group
 * by name, so we drive its UI via the accessibility tree.
 *
 * Activation is gated by a short-lived "pending" flag set in SharedPreferences
 * by WhatsAppIntentHelper — the service only acts on WhatsApp windows
 * within PENDING_WINDOW_MS of our forward, then clears the flag.
 */
class WhatsAppAutoSendService : AccessibilityService() {

    companion object {
        private const val TAG = "WhatsAppAutoSend"

        const val KEY_PENDING_TARGET = "pending_target_name"
        const val KEY_PENDING_EXPIRES = "pending_expires_at"
        const val KEY_PENDING_MODE = "pending_mode"
        const val MODE_GROUP = "group"
        const val MODE_NUMBER = "number"
        const val PENDING_WINDOW_MS = 45_000L

        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

        @Volatile
        private var instance: WhatsAppAutoSendService? = null

        fun isRunning(): Boolean = instance != null

        /**
         * Launches [intent] from the accessibility service context. AccessibilityService
         * is a system-bound service and is exempt from Android 10+ background
         * activity-start restrictions, so this works even when the app was triggered
         * from a BroadcastReceiver with no visible UI.
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
            if (prefs.getString(KEY_PENDING_TARGET, null) != null || prefs.getString(KEY_PENDING_MODE, null) != null) {
                clearPending(prefs)
            }
            return
        }

        val mode = prefs.getString(KEY_PENDING_MODE, null) ?: return
        val root = rootInActiveWindow ?: return

        when (mode) {
            MODE_GROUP -> {
                val target = prefs.getString(KEY_PENDING_TARGET, null) ?: return
                // Step 1: if we're still on the share sheet, pick the target row.
                if (clickTargetChat(root, target)) {
                    Log.d(TAG, "Clicked target chat row: $target")
                    return
                }
                // Step 2: if we're now on the preview screen, tap Send.
                if (clickSendButton(root)) {
                    Log.d(TAG, "Send tapped for group: $target")
                    clearPending(prefs)
                }
            }
            MODE_NUMBER -> {
                // Deeplink opens the chat with text pre-filled; just tap Send.
                if (clickSendButton(root)) {
                    Log.d(TAG, "Send tapped for direct chat")
                    clearPending(prefs)
                }
            }
        }
    }

    override fun onInterrupt() {}

    /**
     * Locates a contact/group row in the share sheet whose name exactly matches
     * [target] (case-insensitive, trimmed) and clicks its clickable ancestor.
     */
    private fun clickTargetChat(root: AccessibilityNodeInfo, target: String): Boolean {
        val normalized = target.trim()
        val matches = root.findAccessibilityNodeInfosByText(normalized) ?: return false
        for (node in matches) {
            val text = node.text?.toString()?.trim() ?: continue
            if (!text.equals(normalized, ignoreCase = true)) continue
            val clickable = findClickableAncestor(node) ?: continue
            if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    /**
     * Finds and clicks the Send FAB. Tries WhatsApp's resource id first,
     * then falls back to content-description and text matches.
     */
    private fun clickSendButton(root: AccessibilityNodeInfo): Boolean {
        val byId = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
            ?: root.findAccessibilityNodeInfosByViewId("com.whatsapp.w4b:id/send")
            ?: emptyList()
        for (node in byId) {
            if (tryClick(node)) return true
        }

        val byDesc = findNode(root) { n ->
            val desc = n.contentDescription?.toString() ?: return@findNode false
            desc.equals("Send", ignoreCase = true) || desc.equals("Send message", ignoreCase = true)
        }
        if (byDesc != null && tryClick(byDesc)) return true

        return false
    }

    private fun tryClick(node: AccessibilityNodeInfo): Boolean {
        val target = if (node.isClickable) node else findClickableAncestor(node) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 10) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
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

    private fun clearPending(prefs: SharedPreferences) {
        prefs.edit()
            .remove(KEY_PENDING_TARGET)
            .remove(KEY_PENDING_EXPIRES)
            .remove(KEY_PENDING_MODE)
            .apply()
    }
}
