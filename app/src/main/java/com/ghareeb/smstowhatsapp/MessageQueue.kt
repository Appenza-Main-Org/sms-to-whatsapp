package com.ghareeb.smstowhatsapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists pending forwards in SharedPreferences when the device is locked,
 * then drains them one-by-one once the user unlocks. Avoids losing IPN
 * notifications that arrive while the phone is asleep & PIN-locked.
 */
object MessageQueue {

    private const val TAG = "MessageQueue"
    private const val KEY_QUEUE = "pending_forwards"
    private const val FIELD_RECIPIENT = "recipient"
    private const val FIELD_MESSAGE = "message"
    private const val FIELD_TIMESTAMP = "ts"

    private const val INTER_SEND_GAP_MS = 25_000L
    private const val MAX_QUEUE_AGE_MS = 6 * 60 * 60 * 1000L

    fun enqueue(context: Context, recipient: String, message: String) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val arr = readQueue(prefs)
        arr.put(JSONObject().apply {
            put(FIELD_RECIPIENT, recipient)
            put(FIELD_MESSAGE, message)
            put(FIELD_TIMESTAMP, System.currentTimeMillis())
        })
        writeQueue(prefs, arr)
        Log.d(TAG, "Queued forward to '$recipient' (queue size=${arr.length()})")
    }

    fun size(context: Context): Int {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        return readQueue(prefs).length()
    }

    fun drain(context: Context) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        var arr = pruneStale(readQueue(prefs))
        writeQueue(prefs, arr)
        if (arr.length() == 0) return

        val entry = arr.getJSONObject(0)
        val recipient = entry.getString(FIELD_RECIPIENT)
        val message = entry.getString(FIELD_MESSAGE)

        arr = removeFirst(arr)
        writeQueue(prefs, arr)

        Log.d(TAG, "Draining 1 message to '$recipient' (${arr.length()} remaining)")
        WhatsAppIntentHelper.sendMessage(context, recipient, message)

        if (arr.length() > 0) {
            Handler(Looper.getMainLooper()).postDelayed(
                { drain(context) },
                INTER_SEND_GAP_MS
            )
        }
    }

    private fun pruneStale(arr: JSONArray): JSONArray {
        val now = System.currentTimeMillis()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val ts = e.optLong(FIELD_TIMESTAMP, now)
            if (now - ts <= MAX_QUEUE_AGE_MS) out.put(e)
        }
        return out
    }

    private fun removeFirst(arr: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 1 until arr.length()) {
            arr.optJSONObject(i)?.let { out.put(it) }
        }
        return out
    }

    private fun readQueue(prefs: android.content.SharedPreferences): JSONArray {
        val raw = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Queue corrupt, resetting: ${e.message}")
            JSONArray()
        }
    }

    private fun writeQueue(prefs: android.content.SharedPreferences, arr: JSONArray) {
        prefs.edit().putString(KEY_QUEUE, arr.toString()).apply()
    }
}
