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

class MainActivity : AppCompatActivity() {

    private lateinit var senderFilterEditText: EditText
    private lateinit var recipientEditText: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView
    private lateinit var batteryButton: Button
    private lateinit var prefs: SharedPreferences

    companion object {
        const val PREFS_NAME = "sms_to_whatsapp_prefs"
        const val KEY_SENDER_FILTER = "sender_filter"
        const val KEY_RECIPIENT = "recipient"
        const val KEY_IS_RUNNING = "is_running"
        const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        senderFilterEditText = findViewById(R.id.senderFilter)
        recipientEditText = findViewById(R.id.recipient)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statusText = findViewById(R.id.statusText)
        batteryButton = findViewById(R.id.batteryButton)

        // Load saved values — defaults tuned for InstaPay/IPN
        senderFilterEditText.setText(prefs.getString(KEY_SENDER_FILTER, "InstaPay,IPN"))
        recipientEditText.setText(prefs.getString(KEY_RECIPIENT, "GROUP"))
        updateStatus()

        startButton.setOnClickListener { requestPermissions() }

        stopButton.setOnClickListener {
            stopService(Intent(this, SMSListenerService::class.java))
            prefs.edit().putBoolean(KEY_IS_RUNNING, false).apply()
            updateStatus()
            Toast.makeText(this, "SMS listener stopped", Toast.LENGTH_SHORT).show()
        }

        batteryButton.setOnClickListener { requestBatteryOptimizationExemption() }
    }

    private fun updateStatus() {
        val running = prefs.getBoolean(KEY_IS_RUNNING, false)
        statusText.text = if (running) "Status: RUNNING" else "Status: STOPPED"
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

        if (senderFilter.isEmpty() || recipient.isEmpty()) {
            Toast.makeText(this, "Please fill sender and recipient", Toast.LENGTH_SHORT).show()
            return
        }

        // Save to prefs so receiver can read them
        prefs.edit()
            .putString(KEY_SENDER_FILTER, senderFilter)
            .putString(KEY_RECIPIENT, recipient)
            .putBoolean(KEY_IS_RUNNING, true)
            .apply()

        val intent = Intent(this, SMSListenerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        updateStatus()
        Toast.makeText(this, "SMS listener started", Toast.LENGTH_SHORT).show()
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
