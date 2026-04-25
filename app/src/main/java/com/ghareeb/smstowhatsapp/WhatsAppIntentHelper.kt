package com.ghareeb.smstowhatsapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.URLEncoder

object WhatsAppIntentHelper {

    private const val TAG = "WhatsAppIntentHelper"
    private const val FORWARD_CHANNEL_ID = "sms_forward_channel"
    private const val FORWARD_CHANNEL_NAME = "SMS Forward Alerts"
    private const val NOTIFICATION_ID_BASE = 2000

    fun sendMessage(context: Context, recipient: String, message: String) {
        val intent = buildWhatsAppIntent(context, recipient, message)
        if (intent == null) {
            Log.e(TAG, "WhatsApp / WhatsApp Business not installed — cannot forward")
            postForwardNotification(context, message, null, whatsAppMissing = true)
            return
        }

        // Always post a tappable notification first — this is the reliable path
        // when the receiver fires while the app is in the background (Android 10+
        // blocks startActivity from background BroadcastReceivers).
        postForwardNotification(context, message, intent, whatsAppMissing = false)

        // Best-effort direct launch — works if the screen is on / app recently foregrounded.
        try {
            context.startActivity(intent)
            Log.d(TAG, "WhatsApp launched directly")
        } catch (e: Exception) {
            Log.w(TAG, "Direct launch failed, user must tap notification: ${e.message}")
        }
    }

    private fun buildWhatsAppIntent(context: Context, recipient: String, message: String): Intent? {
        val trimmed = recipient.trim()
        val digitsOnly = trimmed.replace(Regex("[^0-9]"), "")

        // Treat as direct number ONLY if the input looks like a real phone number
        // (literal "GROUP", free-form text like "Test Group", or too-few digits → share picker).
        val useSharePicker = trimmed.equals("GROUP", ignoreCase = true) ||
            digitsOnly.length < 7 ||
            trimmed.any { it.isLetter() }

        val intent = if (useSharePicker) buildShareIntent(message) else buildDeeplinkIntent(digitsOnly, message)

        val pm = context.packageManager
        if (pm.resolveActivity(intent, 0) != null) return intent

        intent.setPackage("com.whatsapp.w4b")
        if (pm.resolveActivity(intent, 0) != null) return intent

        return null
    }

    private fun buildShareIntent(message: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, message)
        setPackage("com.whatsapp")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }

    private fun buildDeeplinkIntent(digitsOnly: String, message: String): Intent {
        val encoded = URLEncoder.encode(message, "UTF-8")
        return Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://api.whatsapp.com/send?phone=$digitsOnly&text=$encoded")
            setPackage("com.whatsapp")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }

    private fun postForwardNotification(
        context: Context,
        message: String,
        whatsAppIntent: Intent?,
        whatsAppMissing: Boolean
    ) {
        ensureChannel(context)

        val builder = NotificationCompat.Builder(context, FORWARD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle(if (whatsAppMissing) "WhatsApp not installed" else "Tap to forward SMS to WhatsApp")
            .setContentText(message.lineSequence().first().take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)

        if (whatsAppIntent != null) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pending = PendingIntent.getActivity(
                context,
                System.currentTimeMillis().toInt(),
                whatsAppIntent,
                flags
            )
            builder.setContentIntent(pending)
            builder.setFullScreenIntent(pending, true)
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_BASE + (System.currentTimeMillis() % 1000).toInt(), builder.build())
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(FORWARD_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            FORWARD_CHANNEL_ID,
            FORWARD_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Tap to forward a matched SMS to WhatsApp"
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }
}
