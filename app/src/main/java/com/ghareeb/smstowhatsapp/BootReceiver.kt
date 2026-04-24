package com.ghareeb.smstowhatsapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Restarts the foreground service after device reboot
 * so SMS monitoring continues automatically.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {

            Log.d(TAG, "Boot completed - checking if service should restart")

            val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val wasRunning = prefs.getBoolean(MainActivity.KEY_IS_RUNNING, false)

            if (wasRunning) {
                Log.d(TAG, "Restarting SMS listener service")
                val serviceIntent = Intent(context, SMSListenerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
