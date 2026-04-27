package com.ghareeb.smstowhatsapp

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Persists pending forwards in SharedPreferences when the bridge is unreachable
 * (Termux not running, network blip, etc.) and retries them on a schedule.
 */
object MessageQueue {

    private const val TAG = "MessageQueue"
    private const val KEY_QUEUE = "pending_forwards"
    private const val FIELD_RECIPIENT = "recipient"
    private const val FIELD_MESSAGE = "message"
    private const val FIELD_TIMESTAMP = "ts"

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

    /**
     * Walks the queue, sending each entry via [ForwardClient]. Stops at the
     * first network failure so we don't blast the server while it's still
     * unreachable. Caller should reschedule itself if any entries remain.
     */
    fun drain(context: Context): Boolean {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        var arr = pruneStale(readQueue(prefs))
        writeQueue(prefs, arr)
        if (arr.length() == 0) return true

        val baseUrl = prefs.getString(MainActivity.KEY_BRIDGE_URL, MainActivity.DEFAULT_BRIDGE_URL)
        var allSent = true

        while (arr.length() > 0) {
            val entry = arr.getJSONObject(0)
            val recipient = entry.getString(FIELD_RECIPIENT)
            val message = entry.getString(FIELD_MESSAGE)

            val result = ForwardClient.forward(baseUrl, recipient, message)
            when (result) {
                is ForwardClient.Result.Ok -> {
                    Log.d(TAG, "Drained 1 entry to '$recipient'")
                    arr = removeFirst(arr)
                    writeQueue(prefs, arr)
                }
                is ForwardClient.Result.Failed -> {
                    // 4xx that's not a network problem — drop the entry so we
                    // don't keep retrying a permanently bad request (e.g. the
                    // group name doesn't exist). Server logs explain.
                    Log.w(TAG, "Server rejected entry (${result.code}): ${result.body}; dropping")
                    arr = removeFirst(arr)
                    writeQueue(prefs, arr)
                    allSent = false
                }
                is ForwardClient.Result.NetworkError -> {
                    Log.d(TAG, "Bridge unreachable; will retry later")
                    allSent = false
                    break
                }
            }
        }
        return allSent
    }

    fun drainAsync(context: Context) {
        thread(name = "MessageQueue.drain", isDaemon = true) {
            try {
                drain(context)
            } catch (t: Throwable) {
                Log.w(TAG, "Drain crashed: ${t.message}")
            }
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
