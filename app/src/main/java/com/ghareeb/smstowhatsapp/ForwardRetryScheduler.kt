package com.ghareeb.smstowhatsapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Schedules a retry of MessageQueue.drain via AlarmManager. Used when the
 * Baileys bridge is unreachable — e.g. the user closed Termux, or the phone
 * lost connectivity. We back off in fixed steps (60s / 5min / 30min); each
 * attempt that fails reschedules the next.
 */
object ForwardRetryScheduler {

    private const val TAG = "ForwardRetry"
    private const val ACTION_RETRY = "com.ghareeb.smstowhatsapp.ACTION_FORWARD_RETRY"
    private const val REQUEST_CODE = 4242

    private const val DELAY_SHORT_MS = 60_000L
    private const val DELAY_MEDIUM_MS = 5 * 60_000L
    private const val DELAY_LONG_MS = 30 * 60_000L

    fun scheduleSoon(context: Context) = schedule(context, DELAY_SHORT_MS)

    fun scheduleAfterFailure(context: Context, attemptCount: Int) {
        val delay = when {
            attemptCount < 3 -> DELAY_SHORT_MS
            attemptCount < 8 -> DELAY_MEDIUM_MS
            else -> DELAY_LONG_MS
        }
        schedule(context, delay)
    }

    private fun schedule(context: Context, delayMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, RetryReceiver::class.java).apply { action = ACTION_RETRY }
        val pi = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pi
            )
            Log.d(TAG, "Retry scheduled in ${delayMs / 1000}s")
        } catch (e: SecurityException) {
            // Some Android 12+ devices restrict setExact without USE_EXACT_ALARM.
            am.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pi
            )
            Log.d(TAG, "Retry scheduled (inexact) in ${delayMs / 1000}s")
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, RetryReceiver::class.java).apply { action = ACTION_RETRY }
        val pi = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }

    class RetryReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_RETRY) return
            Log.d(TAG, "Retry alarm fired; draining queue")
            val before = MessageQueue.size(context)
            if (before == 0) return
            val pending = goAsync()
            Thread({
                try {
                    val ok = MessageQueue.drain(context)
                    val after = MessageQueue.size(context)
                    if (after > 0 && !ok) {
                        scheduleAfterFailure(context, before)
                    }
                } finally {
                    pending.finish()
                }
            }, "ForwardRetry").start()
        }
    }
}
