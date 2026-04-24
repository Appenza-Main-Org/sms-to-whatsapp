package com.ghareeb.smstowhatsapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.net.URLEncoder

/**
 * Helper to forward messages to WhatsApp.
 *
 * Two modes are supported:
 *   1. Individual recipient via wa.me deeplink (direct open of chat with phone number)
 *   2. Group/contact picker via ACTION_SEND share intent (user taps the group once)
 *
 * WhatsApp platform limitations:
 *   - There is NO public URL scheme to open a specific group directly.
 *   - ACTION_SEND opens WhatsApp's share sheet with the message pre-filled;
 *     the user picks the group from the list and taps send.
 *   - For fully automated sending to a group, you must use the WhatsApp
 *     Business Cloud API with a registered business number.
 */
object WhatsAppIntentHelper {

    private const val TAG = "WhatsAppIntentHelper"

    /**
     * Sends the message via WhatsApp.
     *
     * @param recipient Either a phone number with country code (e.g. "201234567890")
     *                  OR the literal string "GROUP" to trigger the share picker.
     */
    fun sendMessage(context: Context, recipient: String, message: String) {
        val trimmed = recipient.trim()
        if (trimmed.equals("GROUP", ignoreCase = true) || trimmed.isEmpty()) {
            sendViaSharePicker(context, message)
        } else {
            sendToNumber(context, trimmed, message)
        }
    }

    /**
     * Opens WhatsApp's share sheet with the message pre-filled.
     * The user selects a contact OR group from the list and taps send.
     * This is the ONLY way to route a message to a group without the Business API.
     */
    private fun sendViaSharePicker(context: Context, message: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
                setPackage("com.whatsapp")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            val pm = context.packageManager
            if (pm.resolveActivity(intent, 0) == null) {
                // Fallback to WhatsApp Business
                intent.setPackage("com.whatsapp.w4b")
                if (pm.resolveActivity(intent, 0) == null) {
                    Log.e(TAG, "WhatsApp / WhatsApp Business not installed")
                    return
                }
            }

            context.startActivity(intent)
            Log.d(TAG, "WhatsApp share picker opened - user to select group")
        } catch (e: Exception) {
            Log.e(TAG, "Error launching WhatsApp share picker", e)
        }
    }

    /**
     * Opens WhatsApp direct chat with a specific phone number.
     */
    private fun sendToNumber(context: Context, phoneNumber: String, message: String) {
        try {
            val cleanedNumber = phoneNumber.replace(Regex("[^0-9]"), "")

            if (cleanedNumber.isEmpty()) {
                Log.e(TAG, "Invalid phone number after cleaning: $phoneNumber")
                return
            }

            val encodedMessage = URLEncoder.encode(message, "UTF-8")
            val url = "https://api.whatsapp.com/send?phone=$cleanedNumber&text=$encodedMessage"

            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
                setPackage("com.whatsapp")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                addCategory(Intent.CATEGORY_BROWSABLE)
            }

            val pm = context.packageManager
            if (pm.resolveActivity(intent, 0) == null) {
                intent.setPackage("com.whatsapp.w4b")
                if (pm.resolveActivity(intent, 0) == null) {
                    Log.e(TAG, "WhatsApp not installed")
                    return
                }
            }

            context.startActivity(intent)
            Log.d(TAG, "WhatsApp direct chat opened for $cleanedNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Error launching WhatsApp direct chat", e)
        }
    }
}
