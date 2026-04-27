package com.ghareeb.smstowhatsapp

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client for the local Baileys bridge.
 *
 * Posts {target, message} to the bridge's /forward endpoint. The bridge runs
 * locally (Termux on the same phone) at http://127.0.0.1:3000 by default.
 *
 * Synchronous on purpose: callers run this from a coroutine / IntentService /
 * background thread. It blocks until the HTTP exchange finishes so we know
 * whether to enqueue for retry.
 */
object ForwardClient {

    private const val TAG = "ForwardClient"
    private const val DEFAULT_BASE_URL = "http://127.0.0.1:3000"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 30_000

    sealed class Result {
        object Ok : Result()
        data class Failed(val code: Int, val body: String) : Result()
        data class NetworkError(val cause: Throwable) : Result()
    }

    fun forward(baseUrl: String?, target: String, message: String): Result {
        val url = URL((baseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL) + "/forward")
        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }

            val payload = JSONObject().apply {
                put("target", target)
                put("message", message)
            }.toString().toByteArray(Charsets.UTF_8)

            conn.outputStream.use { it.write(payload) }

            val code = conn.responseCode
            if (code in 200..299) {
                Log.d(TAG, "Forwarded '$target' OK")
                Result.Ok
            } else {
                val body = (conn.errorStream ?: conn.inputStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: ""
                Log.w(TAG, "Forward failed ($code): $body")
                Result.Failed(code, body)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Forward network error: ${t.message}")
            Result.NetworkError(t)
        } finally {
            conn?.disconnect()
        }
    }
}
