package com.example.iwiapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
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

data class Message(
    val id: String,
    var text: String,
    val senderName: String,
    val timestamp: String,
    val isMine: Boolean,
    val type: String = "TEXT"
)

class MainActivity : AppCompatActivity() {

    // --- UI Variables ---
    lateinit var mainHeader: LinearLayout
    lateinit var bottomNav: LinearLayout
    lateinit var btnSettings: ImageButton

    // Global Chat UI
    lateinit var tabChatLayout: LinearLayout
    lateinit var globalChatRecyclerView: RecyclerView
    lateinit var inputMessage: EditText
    lateinit var btnSend: Button

    // People UI
    lateinit var tabPeopleLayout: LinearLayout
    lateinit var peersListView: ListView
    lateinit var navChat: Button
    lateinit var navPeople: Button

    // Private Chat UI
    lateinit var layoutPrivateChat: LinearLayout
    lateinit var txtPrivateChatName: TextView
    lateinit var privateChatRecyclerView: RecyclerView
    lateinit var inputPrivateMessage: EditText
    lateinit var btnPrivateSend: Button
    lateinit var btnBackFromPrivate: Button
    lateinit var btnPrivatePhoto: Button

    // --- Logic Variables ---
    val STRATEGY = Strategy.P2P_CLUSTER
    val SERVICE_ID = "com.example.localnet"
    var myNickname = "Unknown"

    val connectedDevices = mutableMapOf<String, String>() // ID -> Name
    val seenMessages = HashSet<String>()

    // 🔴 NEW: STORE HISTORY & UNREAD COUNTS
    // Stores all private messages: Map<UserName, ListOfMessages>
    val privateChatHistory = mutableMapOf<String, MutableList<Message>>()
    // Stores unread counts: Map<UserName, Count>
    val unreadCounts = mutableMapOf<String, Int>()

    lateinit var peersAdapter: ArrayAdapter<String>
    val peersList = mutableListOf<String>()

    // Who are we currently talking to? (Null if nobody)
    var currentChatPartnerName: String? = null

    val incomingFilePayloads = mutableMapOf<Long, File>()

    // Adapters
    lateinit var globalChatAdapter: MessageAdapter
    val globalMessagesList = mutableListOf<Message>()

    lateinit var privateChatAdapter: MessageAdapter
    val currentPrivateMessagesList = mutableListOf<Message>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupRecyclerViews()

        // Custom Adapter for People List (To handle Bold Text for Unread)
        peersAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, peersList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                val text = getItem(position)

                // If this person has unread messages, make text RED and BOLD
                if (text != null && text.contains("(")) {
                    view.setTypeface(null, Typeface.BOLD)
                    view.setTextColor(Color.RED)
                } else {
                    view.setTypeface(null, Typeface.NORMAL)
                    view.setTextColor(Color.BLACK)
                }
                return view
            }
        }
        peersListView.adapter = peersAdapter

        val prefs = getSharedPreferences("LocalNetPrefs", Context.MODE_PRIVATE)
        val savedName = prefs.getString("username", null)

        if (savedName != null) {
            myNickname = savedName
            checkPermissionsAndStart()
        } else {
            showNameInputDialog(false)
        }

        // --- BUTTON LISTENERS ---
        btnSettings.setOnClickListener { view -> showSettingsMenu(view) }
        navChat.setOnClickListener { switchToTab("GLOBAL") }
        navPeople.setOnClickListener { switchToTab("PEOPLE") }

        peersListView.setOnItemClickListener { _, _, position, _ ->
            val itemText = peersList[position]
            // Extract the real name (remove emojis and unread counts)
            val realName = getCleanNameFromList(itemText)
            openPrivateChat(realName)
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

    // --- RECYCLERVIEW SETUP ---
    private fun setupRecyclerViews() {
        // Global Chat
        globalChatAdapter = MessageAdapter(globalMessagesList) { message, position ->
            // REMOVED "if (message.isMine)" -> Now works for ALL messages
            showMessageOptions(message, position, isGlobal = true)
        }
        globalChatRecyclerView.layoutManager = LinearLayoutManager(this)
        globalChatRecyclerView.adapter = globalChatAdapter

        // Private Chat
        privateChatAdapter = MessageAdapter(currentPrivateMessagesList) { message, position ->
            // REMOVED "if (message.isMine)" -> Now works for ALL messages
            showMessageOptions(message, position, isGlobal = false)
        }
        // Stack from end logic
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        privateChatRecyclerView.layoutManager = layoutManager
        privateChatRecyclerView.adapter = privateChatAdapter
    }

    // --- EDIT / DELETE LOGIC ---
    private fun showMessageOptions(message: Message, position: Int, isGlobal: Boolean) {
        val options: Array<String>

        if (message.isMine) {
            // My Message: I can Edit and Delete
            options = arrayOf("Edit", "Delete")
        } else {
            // Received Message: I can only Delete (Locally)
            options = arrayOf("Delete from my view")
        }

        AlertDialog.Builder(this)
            .setTitle("Message Options")
            .setItems(options) { _, which ->
                if (message.isMine) {
                    when (which) {
                        0 -> showEditDialog(message, position, isGlobal)
                        1 -> deleteMessageLocal(message, position, isGlobal) // Changed to deleteMessageLocal
                    }
                } else {
                    // Only one option: Delete
                    deleteMessageLocal(message, position, isGlobal)
                }
            }
            .show()
    }

    private fun deleteMessageLocal(message: Message, position: Int, isGlobal: Boolean) {
        if (isGlobal) {
            globalMessagesList.removeAt(position)
            globalChatAdapter.notifyItemRemoved(position)
        } else {
            // Remove from the current view list
            currentPrivateMessagesList.removeAt(position)
            privateChatAdapter.notifyItemRemoved(position)

            // Remove from the saved history
            if (currentChatPartnerName != null) {
                privateChatHistory[currentChatPartnerName]?.remove(message)
            }
        }

        // If it was MY message, tell others to delete it too
        if (message.isMine) {
            val cmdId = UUID.randomUUID().toString()
            sendPayloadBytes("CMD__DELETE:$cmdId:${message.id}")
        }

        Toast.makeText(this, "Message deleted", Toast.LENGTH_SHORT).show()
    }

    private fun showEditDialog(message: Message, position: Int, isGlobal: Boolean) {
        val input = EditText(this)
        input.setText(message.text)
        AlertDialog.Builder(this)
            .setTitle("Edit Message")
            .setView(input)
            .setPositiveButton("Update") { _, _ ->
                val newText = input.text.toString()

                // 1. Update Local Screen Immediately
                message.text = newText
                if (isGlobal) globalChatAdapter.notifyItemChanged(position)
                else privateChatAdapter.notifyItemChanged(position)

                // 2. Send Command with UNIQUE ID (Fixes flickering)
                val cmdId = UUID.randomUUID().toString()
                val editCmd = "CMD__EDIT:$cmdId:${message.id}:$newText"

                // Mark this command as "seen" so we don't process our own echo
                seenMessages.add(cmdId)

                sendPayloadBytes(editCmd)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- CHAT LOGIC ---

    private fun processAndSendMessage(msgText: String, target: String) {
        val messageId = UUID.randomUUID().toString()
        val fullPayload = "$messageId:$myNickname:$target:$msgText"
        seenMessages.add(messageId)

        val newMessage = Message(messageId, msgText, "Me", getCurrentTime(), true)

        if (target == "ALL") {
            appendGlobalMessage(newMessage)
        } else {
            // Private Message
            if (currentChatPartnerName == target) {
                appendPrivateMessage(newMessage)
            }
            // 🔴 SAVE TO HISTORY IMMEDIATELY
            addToPrivateHistory(target, newMessage)
        }

        sendPayloadBytes(fullPayload)
    }

    private fun handleTextMessage(senderEndpointId: String, rawMessage: String) {
        // 🔴 FIX: INTERCEPT COMMANDS FIRST
        if (rawMessage.startsWith("CMD__")) {
            handleCommandMessage(rawMessage)
            return // Stop here, don't show as a new message
        }

        // ... (Rest of your existing code for normal messages) ...
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

            val newMessage = Message(messageId, actualMessage, senderName, getCurrentTime(), false)

            if (targetName == "ALL") {
                appendGlobalMessage(newMessage)
            } else if (targetName == myNickname) {
                // 🔴 IT IS A PRIVATE MESSAGE FOR ME

                // 1. Is the user looking at this chat RIGHT NOW?
                if (currentChatPartnerName == senderName) {
                    appendPrivateMessage(newMessage)
                    addToPrivateHistory(senderName, newMessage)
                } else {
                    // 2. User is elsewhere. STORE IT & MARK UNREAD.
                    addToPrivateHistory(senderName, newMessage)
                    incrementUnreadCount(senderName)

                    runOnUiThread {
                        Toast.makeText(this, "New msg from $senderName", Toast.LENGTH_SHORT).show()
                        updatePeersListUI() // Refresh list to show Red Bold Text
                    }
                }
            }
        }
    }
    private fun handleCommandMessage(cmd: String) {
        // 🔴 LOOP PROTECTION:
        // Command Format: CMD__TYPE : CMD_ID : DATA...
        // We look at the 2nd part (CMD_ID) to see if we processed this already.

        val parts = cmd.split(":", limit = 4)
        if (parts.size < 2) return

        val cmdId = parts[1] // The Unique ID of this command

        if (seenMessages.contains(cmdId)) return // STOP! We already did this.
        seenMessages.add(cmdId)

        // Forward to others
        val bytes = Payload.fromBytes(cmd.toByteArray(StandardCharsets.UTF_8))
        connectedDevices.keys.forEach { Nearby.getConnectionsClient(this).sendPayload(it, bytes) }

        if (cmd.startsWith("CMD__EDIT")) {
            // CMD__EDIT : CmdID : MsgID : NewText
            if (parts.size == 4) {
                val idToEdit = parts[2]
                val newText = parts[3]

                updateMessageText(idToEdit, newText)
            }
        }
        else if (cmd.startsWith("CMD__DELETE")) {
            // CMD__DELETE : CmdID : MsgID
            if (parts.size >= 3) {
                val idToDelete = parts[2]
                performDelete(idToDelete)
            }
        }
    }

    // Helper to keep code clean
    private fun updateMessageText(id: String, newText: String) {
        // Update Global
        val globalMsg = globalMessagesList.find { it.id == id }
        if (globalMsg != null) {
            runOnUiThread {
                globalMsg.text = newText
                globalChatAdapter.notifyDataSetChanged()
            }
        }
        // Update Private
        val privateMsg = currentPrivateMessagesList.find { it.id == id }
        if (privateMsg != null) {
            runOnUiThread {
                privateMsg.text = newText
                privateChatAdapter.notifyDataSetChanged()
            }
        }
        // Update History
        for ((_, history) in privateChatHistory) {
            history.find { it.id == id }?.text = newText
        }
    }

    // Helper to keep code clean
    private fun performDelete(id: String) {
        // Remove from Global
        val gIndex = globalMessagesList.indexOfFirst { it.id == id }
        if (gIndex != -1) {
            runOnUiThread {
                globalMessagesList.removeAt(gIndex)
                globalChatAdapter.notifyItemRemoved(gIndex)
            }
        }
        // Remove from Private
        val pIndex = currentPrivateMessagesList.indexOfFirst { it.id == id }
        if (pIndex != -1) {
            runOnUiThread {
                currentPrivateMessagesList.removeAt(pIndex)
                privateChatAdapter.notifyItemRemoved(pIndex)
            }
        }
        // Remove from History
        for ((_, history) in privateChatHistory) {
            history.removeIf { it.id == id }
        }
    }

    // --- HISTORY MANAGEMENT ---

    private fun addToPrivateHistory(person: String, msg: Message) {
        if (!privateChatHistory.containsKey(person)) {
            privateChatHistory[person] = mutableListOf()
        }
        privateChatHistory[person]?.add(msg)
    }

    private fun incrementUnreadCount(person: String) {
        val current = unreadCounts[person] ?: 0
        unreadCounts[person] = current + 1
    }

    private fun openPrivateChat(name: String) {
        currentChatPartnerName = name

        // 1. Clear Unread Count
        unreadCounts.remove(name)
        updatePeersListUI() // Remove bold/red style

        // 2. Switch UI
        layoutPrivateChat.visibility = View.VISIBLE
        mainHeader.visibility = View.GONE
        bottomNav.visibility = View.GONE
        txtPrivateChatName.text = name

        // 3. 🔴 LOAD HISTORY FROM DATABASE (Map)
        currentPrivateMessagesList.clear()
        val history = privateChatHistory[name]
        if (history != null) {
            currentPrivateMessagesList.addAll(history)
        }

        privateChatAdapter.notifyDataSetChanged()

        // Scroll to bottom
        if (currentPrivateMessagesList.isNotEmpty()) {
            privateChatRecyclerView.scrollToPosition(currentPrivateMessagesList.size - 1)
        }
    }

    private fun updatePeersListUI() {
        peersList.clear()
        if (connectedDevices.isEmpty()) {
            peersList.add("No neighbors found.")
        } else {
            for ((_, name) in connectedDevices) {
                // 🔴 CHECK FOR UNREAD MESSAGES
                val count = unreadCounts[name] ?: 0
                if (count > 0) {
                    peersList.add("👤 $name ($count)") // Add count to name
                } else {
                    peersList.add("👤 $name")
                }
            }
        }
        peersAdapter.notifyDataSetChanged()
    }

    // Helper to strip the "👤 " and " (1)" from names when clicking
    private fun getCleanNameFromList(rawText: String): String {
        var name = rawText
        if (name.startsWith("👤 ")) name = name.substring(3)
        if (name.contains(" (")) {
            name = name.substring(0, name.lastIndexOf(" ("))
        }
        return name
    }

    // --- STANDARD UI FUNCTIONS ---

    private fun appendGlobalMessage(message: Message) {
        runOnUiThread {
            globalMessagesList.add(message)
            globalChatAdapter.notifyItemInserted(globalMessagesList.size - 1)
            globalChatRecyclerView.scrollToPosition(globalMessagesList.size - 1)
        }
    }

    private fun appendPrivateMessage(message: Message) {
        runOnUiThread {
            currentPrivateMessagesList.add(message)
            privateChatAdapter.notifyItemInserted(currentPrivateMessagesList.size - 1)
            privateChatRecyclerView.scrollToPosition(currentPrivateMessagesList.size - 1)
        }
    }

    // --- BOILERPLATE (Binding, Permissions, Network) ---
    // (This part remains largely unchanged, just ensuring it fits the file)

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

    private fun getCurrentTime(): String {
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
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

    // --- SETTINGS ---
    private fun showSettingsMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.top_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_user_details -> {
                    AlertDialog.Builder(this).setTitle("Profile").setMessage("Name: $myNickname").setPositiveButton("OK", null).show()
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
        input.hint = "Enter display name"
        AlertDialog.Builder(this)
            .setTitle(if (isChanging) "Change Name" else "Welcome")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString()
                if (name.isNotEmpty()) {
                    getSharedPreferences("LocalNetPrefs", Context.MODE_PRIVATE).edit().putString("username", name).apply()
                    myNickname = name
                    if (isChanging) { logout(); checkPermissionsAndStart() } else { checkPermissionsAndStart() }
                } else { showNameInputDialog(isChanging) }
            }
            .show()
    }

    private fun logout() {
        Nearby.getConnectionsClient(this).stopAdvertising()
        Nearby.getConnectionsClient(this).stopDiscovery()
        Nearby.getConnectionsClient(this).stopAllEndpoints()
        connectedDevices.clear()
        updatePeersListUI()
    }

    // --- NEARBY CONNECTION LOGIC ---
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
                if (file != null) { processReceivedFile(file); incomingFilePayloads.remove(update.payloadId) }
            }
        }
    }

    private fun processReceivedFile(tempFile: File) {
        val newFileName = "Received_${System.currentTimeMillis()}.jpg"
        val destFile = File(getExternalFilesDir(null), newFileName)
        try {
            tempFile.renameTo(destFile)
            val msg = Message(UUID.randomUUID().toString(), "📷 Photo received. Check Gallery.", "System", getCurrentTime(), false)
            // Save to history based on current context (Simple approximation for file logic)
            if (currentChatPartnerName != null) {
                appendPrivateMessage(msg)
                addToPrivateHistory(currentChatPartnerName!!, msg)
            } else {
                appendGlobalMessage(msg)
            }

            AlertDialog.Builder(this).setTitle("Photo Received").setMessage("View now?")
                .setPositiveButton("View") { _, _ -> openFile(destFile) }
                .setNegativeButton("Close", null).show()
        } catch (e: Exception) { }
    }

    private fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "image/*")
            intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            startActivity(intent)
        } catch (e: Exception) { Toast.makeText(this, "Cannot open", Toast.LENGTH_SHORT).show() }
    }

    private fun sendPayloadBytes(data: String) {
        val bytesPayload = Payload.fromBytes(data.toByteArray(StandardCharsets.UTF_8))
        if (connectedDevices.isNotEmpty()) {
            Nearby.getConnectionsClient(this).sendPayload(connectedDevices.keys.toList(), bytesPayload)
        }
    }

    val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) sendImage(uri) }
    private fun sendImage(uri: Uri) {
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val filePayload = Payload.fromFile(pfd)
                val fileName = "IMG_${System.currentTimeMillis()}.jpg"
                processAndSendMessage("FILE_NAME:$fileName", currentChatPartnerName ?: "ALL")
                if (connectedDevices.isNotEmpty()) {
                    Nearby.getConnectionsClient(this).sendPayload(connectedDevices.keys.toList(), filePayload)
                    val msg = Message(UUID.randomUUID().toString(), "📤 Sending Photo...", "Me", getCurrentTime(), true)
                    if (currentChatPartnerName != null) {
                        appendPrivateMessage(msg)
                        addToPrivateHistory(currentChatPartnerName!!, msg)
                    } else {
                        appendGlobalMessage(msg)
                    }
                }
            }
        } catch (e: Exception) { Toast.makeText(this, "Error", Toast.LENGTH_SHORT).show() }
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

// 🔴 ADAPTER WITH LONG CLICK SUPPORT
class MessageAdapter(
    private val messages: List<Message>,
    private val onLongClick: (Message, Int) -> Unit
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val layoutReceived: LinearLayout = view.findViewById(R.id.layoutReceived)
        val txtReceivedSender: TextView = view.findViewById(R.id.txtReceivedSender)
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

        holder.itemView.setOnLongClickListener {
            onLongClick(message, position)
            true
        }

        if (message.isMine) {
            holder.layoutSent.visibility = View.VISIBLE
            holder.layoutReceived.visibility = View.GONE
            holder.txtSentBody.text = message.text
            holder.txtSentTime.text = message.timestamp
        } else {
            holder.layoutReceived.visibility = View.VISIBLE
            holder.layoutSent.visibility = View.GONE
            holder.txtReceivedSender.text = message.senderName
            holder.txtReceivedBody.text = message.text
            holder.txtReceivedTime.text = message.timestamp
        }
    }

    override fun getItemCount() = messages.size
}
