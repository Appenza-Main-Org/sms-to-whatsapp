package com.ghareeb.smstowhatsapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.URLEncoder

object WhatsAppIntentHelper {

    private const val TAG = "WhatsAppIntentHelper"
    private const val FORWARD_CHANNEL_ID = "sms_forward_channel"
    private const val FORWARD_CHANNEL_NAME = "SMS Forward Alerts"
    private const val NOTIFICATION_ID_BASE = 2000

    fun sendMessage(context: Context, recipient: String, message: String) {
        val trimmed = recipient.trim()
        val digitsOnly = trimmed.replace(Regex("[^0-9]"), "")
        val looksLikePhoneNumber = digitsOnly.length >= 7 && trimmed.none { it.isLetter() }

        // Arm the accessibility service to auto-pick the group and tap Send.
        // The service only acts within PENDING_WINDOW_MS and on WhatsApp windows.
        armAutoSend(context, trimmed, looksLikePhoneNumber)

        val intent = if (looksLikePhoneNumber) {
            buildDeeplinkIntent(digitsOnly, message)
        } else {
            buildShareIntent(message)
        }

        val resolved = resolveWhatsAppPackage(context, intent)
        if (resolved == null) {
            Log.e(TAG, "WhatsApp / WhatsApp Business not installed — cannot forward")
            postForwardNotification(context, message, null, whatsAppMissing = true)
            return
        }

        val canLaunchFromBackground = canDrawOverlays(context)

        try {
            context.startActivity(resolved)
            Log.d(TAG, "WhatsApp launched directly (overlay granted: $canLaunchFromBackground)")
            // Only fall back to a tappable notification when background launch is NOT guaranteed.
            // With overlay permission, the launch always succeeds and the accessibility service
            // completes the send, so a notification would just clutter the UI.
            if (!canLaunchFromBackground) {
                postForwardNotification(context, message, resolved, whatsAppMissing = false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct launch failed, posting fallback notification: ${e.message}")
            postForwardNotification(context, message, resolved, whatsAppMissing = false)
        }
    }

    private fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    private fun armAutoSend(context: Context, recipient: String, isPhoneNumber: Boolean) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putLong(
                WhatsAppAutoSendService.KEY_PENDING_EXPIRES,
                System.currentTimeMillis() + WhatsAppAutoSendService.PENDING_WINDOW_MS
            )
        if (isPhoneNumber) {
            editor.putString(WhatsAppAutoSendService.KEY_PENDING_MODE, WhatsAppAutoSendService.MODE_NUMBER)
                .remove(WhatsAppAutoSendService.KEY_PENDING_TARGET)
        } else {
            editor.putString(WhatsAppAutoSendService.KEY_PENDING_MODE, WhatsAppAutoSendService.MODE_GROUP)
                .putString(WhatsAppAutoSendService.KEY_PENDING_TARGET, recipient)
        }
        editor.apply()
    }

    private fun resolveWhatsAppPackage(context: Context, intent: Intent): Intent? {
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
