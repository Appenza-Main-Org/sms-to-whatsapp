package com.ghareeb.smstowhatsapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Fires once the user unlocks the device. Drains any forwards that were
 * queued while the screen was locked. Registered dynamically by
 * SMSListenerService since ACTION_USER_PRESENT can't be received via a
 * static manifest declaration on Android 8+.
 */
class UserPresentReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "UserPresentReceiver"
        private const val POST_UNLOCK_DELAY_MS = 1_500L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_PRESENT) return
        val pending = MessageQueue.size(context)
        if (pending == 0) return
        Log.d(TAG, "User unlocked; draining $pending queued forward(s)")
        // Tiny delay so the home screen has settled before we launch WhatsApp.
        Handler(Looper.getMainLooper()).postDelayed(
            { MessageQueue.drain(context) },
            POST_UNLOCK_DELAY_MS
        )
    }
}
