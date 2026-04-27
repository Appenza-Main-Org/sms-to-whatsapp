package com.ghareeb.smstowhatsapp

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var senderFilterEditText: EditText
    private lateinit var recipientEditText: EditText
    private lateinit var bridgeUrlEditText: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var batteryButton: Button
    private lateinit var testConnectionButton: Button
    private lateinit var statusText: TextView
    private lateinit var bridgeStatusText: TextView
    private lateinit var prefs: SharedPreferences

    companion object {
        const val PREFS_NAME = "sms_to_whatsapp_prefs"
        const val KEY_SENDER_FILTER = "sender_filter"
        const val KEY_RECIPIENT = "recipient"
        const val KEY_IS_RUNNING = "is_running"
        const val KEY_BRIDGE_URL = "bridge_url"
        const val PERMISSION_REQUEST_CODE = 100

        const val DEFAULT_RECIPIENT = "MAHFOUZ IPN instapay revise"
        const val DEFAULT_SENDER_FILTER = "InstaPay,IPN"
        const val DEFAULT_BRIDGE_URL = "http://127.0.0.1:3000"

        private val LEGACY_RECIPIENTS = setOf("GROUP", "Pharmacy", "Test Group")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        migrateLegacyRecipient()

        senderFilterEditText = findViewById(R.id.senderFilter)
        recipientEditText = findViewById(R.id.recipient)
        bridgeUrlEditText = findViewById(R.id.bridgeUrl)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        batteryButton = findViewById(R.id.batteryButton)
        testConnectionButton = findViewById(R.id.testConnectionButton)
        statusText = findViewById(R.id.statusText)
        bridgeStatusText = findViewById(R.id.bridgeStatus)

        senderFilterEditText.setText(prefs.getString(KEY_SENDER_FILTER, DEFAULT_SENDER_FILTER))
        recipientEditText.setText(prefs.getString(KEY_RECIPIENT, DEFAULT_RECIPIENT))
        bridgeUrlEditText.setText(prefs.getString(KEY_BRIDGE_URL, DEFAULT_BRIDGE_URL))
        updateStatus()

        startButton.setOnClickListener { requestPermissions() }
        stopButton.setOnClickListener { stopListener() }
        batteryButton.setOnClickListener { requestBatteryOptimizationExemption() }
        testConnectionButton.setOnClickListener { testBridgeConnection() }
    }

    private fun migrateLegacyRecipient() {
        val saved = prefs.getString(KEY_RECIPIENT, null)
        if (saved == null || saved.trim() in LEGACY_RECIPIENTS) {
            prefs.edit().putString(KEY_RECIPIENT, DEFAULT_RECIPIENT).apply()
        }
    }

    private fun updateStatus() {
        val running = prefs.getBoolean(KEY_IS_RUNNING, false)
        statusText.text = if (running) "Status: RUNNING" else "Status: STOPPED"
        val pending = MessageQueue.size(this)
        bridgeStatusText.text = if (pending > 0) "Queue: $pending pending" else "Queue: empty"
    }

    private fun stopListener() {
        stopService(Intent(this, SMSListenerService::class.java))
        prefs.edit().putBoolean(KEY_IS_RUNNING, false).apply()
        ForwardRetryScheduler.cancel(this)
        updateStatus()
        Toast.makeText(this, "SMS listener stopped", Toast.LENGTH_SHORT).show()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.INTERNET
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            startListener()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startListener()
            } else {
                Toast.makeText(this, "All permissions required", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startListener() {
        val senderFilter = senderFilterEditText.text.toString().trim()
        val recipient = recipientEditText.text.toString().trim()
        val bridgeUrl = bridgeUrlEditText.text.toString().trim().trimEnd('/')

        if (senderFilter.isEmpty() || recipient.isEmpty()) {
            Toast.makeText(this, "Please fill sender and recipient", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.edit()
            .putString(KEY_SENDER_FILTER, senderFilter)
            .putString(KEY_RECIPIENT, recipient)
            .putString(KEY_BRIDGE_URL, bridgeUrl.ifEmpty { DEFAULT_BRIDGE_URL })
            .putBoolean(KEY_IS_RUNNING, true)
            .apply()

        val intent = Intent(this, SMSListenerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        updateStatus()
        Toast.makeText(this, "Listener started — bridge: $bridgeUrl", Toast.LENGTH_LONG).show()
    }

    /**
     * Performs a GET on the bridge's /health endpoint and reports the result.
     * Lets the user verify Termux/Baileys is up before relying on it.
     */
    private fun testBridgeConnection() {
        val baseUrl = bridgeUrlEditText.text.toString().trim().trimEnd('/').ifEmpty { DEFAULT_BRIDGE_URL }
        bridgeStatusText.text = "Testing $baseUrl ..."

        thread(name = "BridgeHealthCheck", isDaemon = true) {
            val (msg, ok) = try {
                val url = URL("$baseUrl/health")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                val code = conn.responseCode
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                if (code == 200) {
                    val json = JSONObject(body)
                    val ready = json.optBoolean("ready", false)
                    val groups = json.optInt("groups", 0)
                    if (ready) "✅ Bridge ready — $groups groups linked" to true
                    else "⚠️ Bridge running but WhatsApp not paired yet" to false
                } else {
                    "❌ Bridge returned HTTP $code" to false
                }
            } catch (t: Throwable) {
                "❌ Bridge unreachable: ${t.message ?: t.javaClass.simpleName}" to false
            }
            runOnUiThread {
                bridgeStatusText.text = msg
                Toast.makeText(this, msg, if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } else {
                Toast.makeText(this, "Already exempted from battery optimization", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }
}
