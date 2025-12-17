package com.example.iwiapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// 🔴 DATA CLASS FOR MESSAGES
data class Message(
    val text: String,
    val senderName: String,
    val timestamp: String,
    val isMine: Boolean
)

class MainActivity : AppCompatActivity() {

    // --- UI Variables ---
    lateinit var mainHeader: LinearLayout
    lateinit var bottomNav: LinearLayout
    lateinit var btnSettings: ImageButton

    // Tabs
    lateinit var tabChatLayout: LinearLayout
    lateinit var globalChatRecyclerView: RecyclerView // 🔴 Changed
    lateinit var inputMessage: EditText
    lateinit var btnSend: Button
    lateinit var tabPeopleLayout: LinearLayout
    lateinit var peersListView: ListView
    lateinit var navChat: Button
    lateinit var navPeople: Button

    // Private Chat
    lateinit var layoutPrivateChat: LinearLayout
    lateinit var txtPrivateChatName: TextView
    lateinit var privateChatRecyclerView: RecyclerView // 🔴 Changed
    lateinit var inputPrivateMessage: EditText
    lateinit var btnPrivateSend: Button
    lateinit var btnBackFromPrivate: Button
    lateinit var btnPrivatePhoto: Button

    // --- Logic Variables ---
    val STRATEGY = Strategy.P2P_CLUSTER
    val SERVICE_ID = "com.example.localnet"
    var myNickname = "Unknown"

    val connectedDevices = mutableMapOf<String, String>()
    val seenMessages = HashSet<String>()

    lateinit var peersAdapter: ArrayAdapter<String>
    val peersList = mutableListOf<String>()
    var currentChatPartnerName: String? = null

    // File Handling
    val incomingFilePayloads = mutableMapOf<Long, File>()

    // 🔴 RECYCLERVIEW ADAPTERS
    lateinit var globalChatAdapter: MessageAdapter
    val globalMessagesList = mutableListOf<Message>()
    lateinit var privateChatAdapter: MessageAdapter
    val privateMessagesList = mutableListOf<Message>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupRecyclerViews() // 🔴 Initialize Adapters

        peersAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, peersList)
        peersListView.adapter = peersAdapter

        // 1. CHECK LOGIN STATUS
        val prefs = getSharedPreferences("LocalNetPrefs", Context.MODE_PRIVATE)
        val savedName = prefs.getString("username", null)

        if (savedName != null) {
            myNickname = savedName
            checkPermissionsAndStart()
        } else {
            showNameInputDialog(false)
        }

        // 2. BUTTON LISTENERS
        btnSettings.setOnClickListener { view -> showSettingsMenu(view) }
        navChat.setOnClickListener { switchToTab("GLOBAL") }
        navPeople.setOnClickListener { switchToTab("PEOPLE") }

        peersListView.setOnItemClickListener { _, _, position, _ ->
            val itemText = peersList[position]
            if (itemText.startsWith("👤 ")) {
                openPrivateChat(itemText.substring(3))
            }
        }

        btnBackFromPrivate.setOnClickListener {
            layoutPrivateChat.visibility = View.GONE
            mainHeader.visibility = View.VISIBLE
            bottomNav.visibility = View.VISIBLE
            currentChatPartnerName = null
        }

        btnSend.setOnClickListener {
            val msg = inputMessage.text.toString()
            if (msg.isNotEmpty()) {
                processAndSendMessage(msg, "ALL")
                inputMessage.text.clear()
            }
        }

        btnPrivateSend.setOnClickListener {
            val msg = inputPrivateMessage.text.toString()
            if (msg.isNotEmpty() && currentChatPartnerName != null) {
                processAndSendMessage(msg, currentChatPartnerName!!)
                inputPrivateMessage.text.clear()
            }
        }

        btnPrivatePhoto.setOnClickListener {
            pickMedia.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    // 🔴 SETUP RECYCLERVIEWS
    private fun setupRecyclerViews() {
        globalChatAdapter = MessageAdapter(globalMessagesList)
        globalChatRecyclerView.layoutManager = LinearLayoutManager(this)
        globalChatRecyclerView.adapter = globalChatAdapter

        privateChatAdapter = MessageAdapter(privateMessagesList)
        privateChatRecyclerView.layoutManager = LinearLayoutManager(this)
        privateChatRecyclerView.adapter = privateChatAdapter
    }

    // 🔴 HELPER FOR TIME
    private fun getCurrentTime(): String {
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
    }

    private fun showSettingsMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.top_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_user_details -> {
                    AlertDialog.Builder(this)
                        .setTitle("Profile")
                        .setMessage("Name: $myNickname\nStatus: ${if (connectedDevices.isEmpty()) "Searching..." else "Online"}")
                        .setPositiveButton("OK", null)
                        .show()
                    true
                }
                R.id.action_change_name -> { showNameInputDialog(true); true }
                R.id.action_logout -> { logout(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun showNameInputDialog(isChanging: Boolean) {
        val input = EditText(this)
        input.hint = "Enter your display name"
        AlertDialog.Builder(this)
            .setTitle(if (isChanging) "Change Name" else "Welcome")
            .setMessage("Enter name to join mesh:")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString()
                if (name.isNotEmpty()) {
                    getSharedPreferences("LocalNetPrefs", Context.MODE_PRIVATE).edit().putString("username", name).apply()
                    myNickname = name
                    if (isChanging) { logout(); checkPermissionsAndStart() } else { checkPermissionsAndStart() }
                } else {
                    Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                    showNameInputDialog(isChanging)
                }
            }
            .show()
    }

    private fun logout() {
        Nearby.getConnectionsClient(this).stopAdvertising()
        Nearby.getConnectionsClient(this).stopDiscovery()
        Nearby.getConnectionsClient(this).stopAllEndpoints()
        connectedDevices.clear()
        updatePeersListUI()
        Toast.makeText(this, "Logged Out", Toast.LENGTH_SHORT).show()
    }

    private fun bindViews() {
        mainHeader = findViewById(R.id.mainHeader)
        btnSettings = findViewById(R.id.btnSettings)
        bottomNav = findViewById(R.id.bottomNav)
        tabChatLayout = findViewById(R.id.tabChatLayout)
        globalChatRecyclerView = findViewById(R.id.globalChatRecyclerView)
        inputMessage = findViewById(R.id.inputMessage)
        btnSend = findViewById(R.id.btnSend)
        tabPeopleLayout = findViewById(R.id.tabPeopleLayout)
        peersListView = findViewById(R.id.peersListView)
        navChat = findViewById(R.id.navChat)
        navPeople = findViewById(R.id.navPeople)
        layoutPrivateChat = findViewById(R.id.layoutPrivateChat)
        txtPrivateChatName = findViewById(R.id.txtPrivateChatName)
        privateChatRecyclerView = findViewById(R.id.privateChatRecyclerView)
        inputPrivateMessage = findViewById(R.id.inputPrivateMessage)
        btnPrivateSend = findViewById(R.id.btnPrivateSend)
        btnBackFromPrivate = findViewById(R.id.btnBackFromPrivate)
        btnPrivatePhoto = findViewById(R.id.btnPrivatePhoto)
    }

    private fun switchToTab(tab: String) {
        if (tab == "GLOBAL") {
            tabChatLayout.visibility = View.VISIBLE
            tabPeopleLayout.visibility = View.GONE
            navChat.setTextColor(Color.parseColor("#075E54"))
            navPeople.setTextColor(Color.parseColor("#666666"))
        } else {
            tabChatLayout.visibility = View.GONE
            tabPeopleLayout.visibility = View.VISIBLE
            navPeople.setTextColor(Color.parseColor("#075E54"))
            navChat.setTextColor(Color.parseColor("#666666"))
            updatePeersListUI()
        }
    }

    private fun openPrivateChat(name: String) {
        currentChatPartnerName = name
        layoutPrivateChat.visibility = View.VISIBLE
        mainHeader.visibility = View.GONE
        bottomNav.visibility = View.GONE
        txtPrivateChatName.text = name
        // Clear private list when opening new chat
        privateMessagesList.clear()
        privateChatAdapter.notifyDataSetChanged()
    }

    private fun updatePeersListUI() {
        peersList.clear()
        if (connectedDevices.isEmpty()) {
            peersList.add("No neighbors found.")
        } else {
            for ((_, name) in connectedDevices) {
                peersList.add("👤 $name")
            }
        }
        peersAdapter.notifyDataSetChanged()
    }

    private fun processAndSendMessage(msgText: String, target: String) {
        val messageId = UUID.randomUUID().toString()
        val fullPayload = "$messageId:$myNickname:$target:$msgText"
        seenMessages.add(messageId)

        // 🔴 ADD TO MY OWN SCREEN AS "SENT"
        val newMessage = Message(msgText, "Me", getCurrentTime(), true)
        if (target == "ALL") appendGlobalMessage(newMessage)
        else if (currentChatPartnerName == target) appendPrivateMessage(newMessage)

        sendPayloadBytes(fullPayload)
    }

    private fun sendPayloadBytes(data: String) {
        val bytesPayload = Payload.fromBytes(data.toByteArray(StandardCharsets.UTF_8))
        if (connectedDevices.isNotEmpty()) {
            Nearby.getConnectionsClient(this).sendPayload(connectedDevices.keys.toList(), bytesPayload)
        }
    }

    // 🔴 UPDATED APPEND FUNCTIONS TO USE ADAPTER
    private fun appendGlobalMessage(message: Message) {
        runOnUiThread {
            globalMessagesList.add(message)
            globalChatAdapter.notifyItemInserted(globalMessagesList.size - 1)
            globalChatRecyclerView.scrollToPosition(globalMessagesList.size - 1)
        }
    }

    private fun appendPrivateMessage(message: Message) {
        runOnUiThread {
            privateMessagesList.add(message)
            privateChatAdapter.notifyItemInserted(privateMessagesList.size - 1)
            privateChatRecyclerView.scrollToPosition(privateMessagesList.size - 1)
        }
    }

    val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) sendImage(uri)
    }

    private fun sendImage(uri: Uri) {
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val filePayload = Payload.fromFile(pfd)
                val fileName = "IMG_${System.currentTimeMillis()}.jpg"
                processAndSendMessage("FILE_NAME:$fileName", currentChatPartnerName ?: "ALL")
                if (connectedDevices.isNotEmpty()) {
                    Nearby.getConnectionsClient(this).sendPayload(connectedDevices.keys.toList(), filePayload)
                    val msg = Message("📤 Sending Photo...", "Me", getCurrentTime(), true)
                    if (currentChatPartnerName != null) appendPrivateMessage(msg) else appendGlobalMessage(msg)
                }
            }
        } catch (e: Exception) { Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val raw = String(payload.asBytes()!!, StandardCharsets.UTF_8)
                handleTextMessage(endpointId, raw)
            } else if (payload.type == Payload.Type.FILE) {
                val file = payload.asFile()?.asJavaFile()
                if (file != null) incomingFilePayloads[payload.id] = file
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                val file = incomingFilePayloads[update.payloadId]
                if (file != null) {
                    processReceivedFile(file)
                    incomingFilePayloads.remove(update.payloadId)
                }
            }
        }
    }

    private fun handleTextMessage(senderEndpointId: String, rawMessage: String) {
        val parts = rawMessage.split(":", limit = 4)
        if (parts.size == 4) {
            val messageId = parts[0]
            val senderName = parts[1]
            val targetName = parts[2]
            val actualMessage = parts[3]

            if (seenMessages.contains(messageId)) return
            seenMessages.add(messageId)

            val reForwardPayload = Payload.fromBytes(rawMessage.toByteArray(StandardCharsets.UTF_8))
            connectedDevices.keys.forEach { neighborId ->
                if (neighborId != senderEndpointId) Nearby.getConnectionsClient(this).sendPayload(neighborId, reForwardPayload)
            }

            if (actualMessage.startsWith("FILE_NAME:")) return

            // 🔴 CREATE RECEIVED MESSAGE OBJECT
            val newMessage = Message(actualMessage, senderName, getCurrentTime(), false)

            if (targetName == "ALL") appendGlobalMessage(newMessage)
            else if (targetName == myNickname) {
                if (currentChatPartnerName == senderName) appendPrivateMessage(newMessage)
                else {
                    // Show notification for private message not in current chat
                    runOnUiThread { Toast.makeText(this, "Private from $senderName", Toast.LENGTH_SHORT).show() }
                    // Optionally add to global as well with a marker
                    appendGlobalMessage(Message("🔒 Private from $senderName", senderName, getCurrentTime(), false))
                }
            }
        }
    }

    private fun processReceivedFile(tempFile: File) {
        val newFileName = "Received_${System.currentTimeMillis()}.jpg"
        val destFile = File(getExternalFilesDir(null), newFileName)
        try {
            tempFile.renameTo(destFile)
            val msg = Message("📷 Photo received. Tap settings to view.", "System", getCurrentTime(), false)
            if (currentChatPartnerName != null) appendPrivateMessage(msg) else appendGlobalMessage(msg)
        } catch (e: Exception) { }
    }

    private fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "image/*")
            intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            startActivity(intent)
        } catch (e: Exception) { Toast.makeText(this, "Cannot open: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE); permissions.add(Manifest.permission.BLUETOOTH_SCAN); permissions.add(Manifest.permission.BLUETOOTH_CONNECT); permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        if (!hasPermissions(permissions)) ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)
        else startNearby()
    }
    private fun hasPermissions(perms: List<String>) = perms.all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startNearby()
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Nearby.getConnectionsClient(this@MainActivity).acceptConnection(endpointId, payloadCallback)
            connectedDevices[endpointId] = info.endpointName
            updatePeersListUI()
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) updatePeersListUI()
            else { connectedDevices.remove(endpointId); updatePeersListUI() }
        }
        override fun onDisconnected(endpointId: String) {
            connectedDevices.remove(endpointId)
            updatePeersListUI()
        }
    }

    private fun startNearby() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        Nearby.getConnectionsClient(this).startAdvertising(myNickname, SERVICE_ID, connectionLifecycleCallback, options)
        Nearby.getConnectionsClient(this).startDiscovery(SERVICE_ID, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                Nearby.getConnectionsClient(this@MainActivity).requestConnection(myNickname, endpointId, connectionLifecycleCallback)
            }
            override fun onEndpointLost(endpointId: String) {}
        }, DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
    }
}

// 🔴 MESSAGE ADAPTER CLASS (ADD THIS AT THE BOTTOM OF THE FILE, OUTSIDE MainActivity)
class MessageAdapter(private val messages: List<Message>) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val layoutReceived: LinearLayout = view.findViewById(R.id.layoutReceived)
        val txtReceivedBody: TextView = view.findViewById(R.id.txtReceivedBody)
        val txtReceivedTime: TextView = view.findViewById(R.id.txtReceivedTime)
        val layoutSent: LinearLayout = view.findViewById(R.id.layoutSent)
        val txtSentBody: TextView = view.findViewById(R.id.txtSentBody)
        val txtSentTime: TextView = view.findViewById(R.id.txtSentTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        if (message.isMine) {
            // Show SENT layout
            holder.layoutSent.visibility = View.VISIBLE
            holder.layoutReceived.visibility = View.GONE
            holder.txtSentBody.text = message.text
            holder.txtSentTime.text = message.timestamp
        } else {
            // Show RECEIVED layout
            holder.layoutReceived.visibility = View.VISIBLE
            holder.layoutSent.visibility = View.GONE
            // Add sender name to received message
            holder.txtReceivedBody.text = "${message.senderName}:\n${message.text}"
            holder.txtReceivedTime.text = message.timestamp
        }
    }

    override fun getItemCount() = messages.size
}