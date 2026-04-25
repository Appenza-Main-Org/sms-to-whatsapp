package com.ghareeb.smstowhatsapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Telephony
import android.util.Log

class SMSReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SMSReceiver"

        // Simple in-memory duplicate guard across rapid re-delivery
        private var lastProcessedKey: String? = null
        private var lastProcessedTime: Long = 0L
        private const val DUPLICATE_WINDOW_MS = 5000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val senderFilter = prefs.getString(MainActivity.KEY_SENDER_FILTER, "") ?: ""
        val recipient = prefs.getString(MainActivity.KEY_RECIPIENT, "") ?: ""
        val isRunning = prefs.getBoolean(MainActivity.KEY_IS_RUNNING, false)

        if (!isRunning) {
            Log.d(TAG, "Listener not active. Ignoring.")
            return
        }

        if (senderFilter.isEmpty() || recipient.isEmpty()) {
            Log.w(TAG, "Sender filter or recipient not set. Skipping.")
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

        // Combine multipart SMS bodies from the same sender
        val combinedBody = StringBuilder()
        var sender: String? = null

        for (message in messages) {
            if (sender == null) sender = message.originatingAddress
            combinedBody.append(message.messageBody)
        }

        val fullBody = combinedBody.toString()
        val senderStr = sender ?: return

        Log.d(TAG, "Received SMS from: $senderStr | Body: $fullBody")

        // Match on sender OR body content (IPN transfers always contain "IPN transfer" phrase)
        if (!matchesFilter(senderStr, fullBody, senderFilter)) {
            Log.d(TAG, "SMS from '$senderStr' does not match filter '$senderFilter'. Ignored.")
            return
        }

        // Duplicate guard: prevent the same message from being re-processed within 5 seconds
        val key = "$senderStr|$fullBody"
        val now = System.currentTimeMillis()
        if (key == lastProcessedKey && (now - lastProcessedTime) < DUPLICATE_WINDOW_MS) {
            Log.d(TAG, "Duplicate SMS within window. Skipping.")
            return
        }
        lastProcessedKey = key
        lastProcessedTime = now

        Log.d(TAG, "Sender matches filter. Forwarding to WhatsApp.")
        val formatted = formatMessage(senderStr, fullBody)

        // Wake the screen before launching WhatsApp. Without a powered display,
        // WhatsApp's contact-picker rows do not lay out, so the accessibility
        // service has nothing to click and the user gets stuck on "Send To".
        wakeScreen(context)

        WhatsAppIntentHelper.sendMessage(context, recipient, formatted)
    }

    private fun wakeScreen(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "SMSToWhatsApp::ForwardWakeLock"
            )
            wakeLock.acquire(15_000L)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock: ${e.message}")
        }
    }

    /**
     * Matches against sender OR body content using comma-separated filter list.
     * Each filter entry is matched as a substring (case-insensitive) in either
     * the sender address or the message body. This catches IPN transfers
     * whether they come from "InstaPay", "IPN", or any bank sender ID.
     */
    private fun matchesFilter(sender: String, body: String, filter: String): Boolean {
        val filters = filter.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return filters.any {
            sender.contains(it, ignoreCase = true) || body.contains(it, ignoreCase = true)
        }
    }

    /**
     * Formats the outgoing WhatsApp message — parses IPN transfer details
     * when possible, otherwise falls back to raw body.
     */
    private fun formatMessage(sender: String, body: String): String {
        // Try to parse IPN transfer details
        val amount = Regex("EGP\\s*([0-9,.]+)", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
        val account = Regex("on\\s+(\\d{4,})\\s+on", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
        val date = Regex("on\\s+(\\d{2}/\\d{2})\\s+at", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
        val time = Regex("at\\s+(\\d{1,2}:\\d{2}\\s*[APMapm]{2})", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
        val ref = Regex("Ref#\\s*([a-zA-Z0-9]+)", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)

        return if (amount != null && ref != null) {
            buildString {
                append("💰 *IPN Transfer Received*\n\n")
                append("*Amount:* EGP $amount\n")
                if (account != null) append("*Account:* ****$account\n")
                if (date != null) append("*Date:* $date\n")
                if (time != null) append("*Time:* $time\n")
                append("*Ref#:* $ref\n\n")
                append("_Full message:_\n$body")
            }
        } else {
            "📩 SMS from $sender\n\n$body"
        }
    }
}
