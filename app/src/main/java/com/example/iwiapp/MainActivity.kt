// PASTE YOUR PACKAGE NAME HERE (e.g., package com.example.localnet)

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.nio.charset.StandardCharsets

class MainActivity : AppCompatActivity() {

    // UI Elements
    lateinit var statusText: TextView
    lateinit var receivedMessages: TextView
    lateinit var inputMessage: EditText
    lateinit var usernameInput: EditText
    lateinit var btnSend: Button
    lateinit var btnConnect: Button
    lateinit var messageScrollView: ScrollView

    // Connection Variables
    val STRATEGY = Strategy.P2P_CLUSTER
    val SERVICE_ID = "com.example.localnet"
    var myNickname = "Unknown"

    val connectedEndpoints = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Link UI to Code
        statusText = findViewById(R.id.statusText)
        receivedMessages = findViewById(R.id.receivedMessages)
        inputMessage = findViewById(R.id.inputMessage)
        usernameInput = findViewById(R.id.usernameInput)
        btnSend = findViewById(R.id.btnSend)
        btnConnect = findViewById(R.id.btnConnect)
        messageScrollView = findViewById(R.id.messageScrollView)

        // 2. "Go Online" Button Listener
        btnConnect.setOnClickListener {
            val name = usernameInput.text.toString()
            if (name.isNotEmpty()) {
                myNickname = name
                // Hide keyboard and disable input
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(usernameInput.windowToken, 0)
                usernameInput.isEnabled = false
                btnConnect.isEnabled = false

                checkPermissionsAndStart()
            } else {
                Toast.makeText(this, "Please enter a name first", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. Send Button Listener
        btnSend.setOnClickListener {
            val msg = inputMessage.text.toString()
            if (msg.isNotEmpty()) {
                // Send the format: "Name: Message"
                val fullMessage = "$myNickname: $msg"
                sendPayload(fullMessage)
                inputMessage.text.clear()
            }
        }
    }

    // --- HELPER TO APPEND TEXT AND SCROLL ---
    private fun appendMessage(text: String) {
        // Run on UI Thread to be safe
        runOnUiThread {
            receivedMessages.append("\n$text")

            // This little trick forces the view to scroll to the bottom
            messageScrollView.post {
                messageScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    // --- PERMISSION LOGIC ---
    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        if (!hasPermissions(permissions)) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)
        } else {
            startNearby()
        }
    }

    private fun hasPermissions(perms: List<String>): Boolean {
        return perms.all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startNearby()
    }

    // --- NEARBY CONNECTIONS LOGIC ---

    private fun startNearby() {
        startAdvertising()
        startDiscovery()
        statusText.text = "Status: Searching for neighbors..."
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        // We use myNickname here so others see my real name!
        Nearby.getConnectionsClient(this)
            .startAdvertising(myNickname, SERVICE_ID, connectionLifecycleCallback, options)
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        Nearby.getConnectionsClient(this)
            .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Nearby.getConnectionsClient(this@MainActivity)
                .requestConnection(myNickname, endpointId, connectionLifecycleCallback)
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Nearby.getConnectionsClient(this@MainActivity).acceptConnection(endpointId, payloadCallback)
            // Optional: You could show a toast saying "Connecting to ${info.endpointName}"
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                statusText.text = "Status: Connected to ${connectedEndpoints.size} devices"
                Toast.makeText(this@MainActivity, "Connected!", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            statusText.text = "Status: Connected to ${connectedEndpoints.size} devices"
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes()
            if (bytes != null) {
                val message = String(bytes, StandardCharsets.UTF_8)
                appendMessage(message) // Use our new helper function

                // MESH FORWARDING
                connectedEndpoints.forEach { otherEndpoint ->
                    if (otherEndpoint != endpointId) {
                        Nearby.getConnectionsClient(this@MainActivity).sendPayload(otherEndpoint, payload)
                    }
                }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun sendPayload(message: String) {
        val bytesPayload = Payload.fromBytes(message.toByteArray(StandardCharsets.UTF_8))
        if (connectedEndpoints.isNotEmpty()) {
            Nearby.getConnectionsClient(this).sendPayload(connectedEndpoints, bytesPayload)
            appendMessage(message) // Show my own message on my screen
        } else {
            Toast.makeText(this, "No neighbors found yet!", Toast.LENGTH_SHORT).show()
        }
    }
}