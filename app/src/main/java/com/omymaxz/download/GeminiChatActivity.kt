package com.omymaxz.download

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class GeminiChatActivity : AppCompatActivity() {

    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var chatInputEditText: EditText
    private lateinit var sendButton: Button
    private lateinit var chatToolbar: Toolbar

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter

    private var generativeModel: GenerativeModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gemini_chat)

        chatToolbar = findViewById(R.id.chatToolbar)
        setSupportActionBar(chatToolbar)
        supportActionBar?.title = "Gemini AI"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        chatToolbar.setNavigationOnClickListener { finish() }

        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        chatInputEditText = findViewById(R.id.chatInputEditText)
        sendButton = findViewById(R.id.sendButton)

        chatAdapter = ChatAdapter(messages) { position ->
            handleMessageLongClick(position)
        }
        chatRecyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        chatRecyclerView.adapter = chatAdapter

        val sharedPrefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val apiKey = sharedPrefs.getString("gemini_api_key", "") ?: ""

        val savedChat = sharedPrefs.getString("gemini_chat_history", null)
        if (savedChat != null) {
            try {
                val type = object : TypeToken<List<ChatMessage>>() {}.type
                val savedMessages: List<ChatMessage> = Gson().fromJson(savedChat, type)
                messages.addAll(savedMessages)
                chatAdapter.notifyDataSetChanged()
                if (messages.isNotEmpty()) {
                    chatRecyclerView.scrollToPosition(messages.size - 1)
                }
            } catch (e: Exception) {
                // Ignore parsing errors
            }
        }

        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Please configure Gemini API Key in Settings", Toast.LENGTH_LONG).show()
            chatInputEditText.isEnabled = false
            sendButton.isEnabled = false
        } else {
            var systemPrompt = "You are a helpful browser assistant. The user is browsing the web inside a custom Android browser app."

            val ctxUrl = WebpageContextHolder.url
            val ctxTitle = WebpageContextHolder.title
            val ctxText = WebpageContextHolder.textContent

            if (!ctxUrl.isNullOrEmpty() || !ctxText.isNullOrEmpty()) {
                systemPrompt += "\n\nHere is the context of the webpage the user is currently viewing:"
                if (!ctxTitle.isNullOrEmpty()) systemPrompt += "\nTitle: $ctxTitle"
                if (!ctxUrl.isNullOrEmpty()) systemPrompt += "\nURL: $ctxUrl"
                if (!ctxText.isNullOrEmpty()) systemPrompt += "\nPage Text (truncated): $ctxText"
            }

            generativeModel = GenerativeModel(
                modelName = "gemini-flash-latest", // We use gemini-flash-latest for valid models
                apiKey = apiKey,
                systemInstruction = content { text(systemPrompt) }
            )
        }

        sendButton.setOnClickListener {
            val query = chatInputEditText.text.toString().trim()
            if (query.isNotEmpty()) {
                sendMessage(query)
            }
        }
    }

    private fun sendMessage(query: String) {
        messages.add(ChatMessage(query, isUser = true))
        chatAdapter.notifyItemInserted(messages.size - 1)
        chatRecyclerView.scrollToPosition(messages.size - 1)
        chatInputEditText.text.clear()
        saveChatHistory()

        CoroutineScope(Dispatchers.IO).launch {
            var actualQuery = query
            val lowerQuery = query.lowercase()

            // Check if user is asking for logs
            if (lowerQuery.contains("log") || lowerQuery.contains("crash") || lowerQuery.contains("error")) {
                try {
                    val process = Runtime.getRuntime().exec("logcat -d -t 300")
                    val logOutput = process.inputStream.bufferedReader().use { it.readText() }
                    if (logOutput.isNotEmpty()) {
                        actualQuery += "\n\n[SYSTEM LOGS ATTACHED FOR CONTEXT]\n$logOutput"
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            var attempt = 0
            val maxAttempts = 3
            var responseText = "Error: The model is overloaded or unavailable after multiple attempts."
            var success = false

            // Build the conversational history from all preceding messages.
            // Gemini strictly requires alternating user/model roles. If edits caused consecutive
            // messages from the same role, we must merge their text.
            val rawHistory = messages.dropLast(1)
            val mergedHistory = mutableListOf<com.google.ai.client.generativeai.type.Content>()
            var currentRole = ""
            var currentText = StringBuilder()

            for (msg in rawHistory) {
                val role = if (msg.isUser) "user" else "model"
                if (role == currentRole) {
                    currentText.append("\n\n").append(msg.text)
                } else {
                    if (currentRole.isNotEmpty()) {
                        mergedHistory.add(content(currentRole) { text(currentText.toString()) })
                    }
                    currentRole = role
                    currentText = StringBuilder(msg.text)
                }
            }
            if (currentRole.isNotEmpty()) {
                mergedHistory.add(content(currentRole) { text(currentText.toString()) })
            }

            while (attempt < maxAttempts && !success) {
                try {
                    val chat = generativeModel?.startChat(mergedHistory)
                    val response = chat?.sendMessage(actualQuery)
                    responseText = response?.text ?: "No response from Gemini."
                    success = true
                } catch (e: SerializationException) {
                    attempt++
                    if (attempt < maxAttempts) {
                        delay(1500)
                    }
                } catch (e: Exception) {
                    if (e.message?.contains("503") == true) {
                        attempt++
                        if (attempt < maxAttempts) {
                            delay(1500)
                        }
                    } else {
                        responseText = "Error: ${e.message}"
                        break
                    }
                }
            }

            withContext(Dispatchers.Main) {
                messages.add(ChatMessage(responseText, isUser = false))
                chatAdapter.notifyItemInserted(messages.size - 1)
                chatRecyclerView.scrollToPosition(messages.size - 1)
                saveChatHistory()
            }
        }

    }
    private fun handleMessageLongClick(position: Int) {
        val message = messages[position]
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Chat Options")
            .setItems(arrayOf("Edit & Restart From Here", "Delete From Here")) { _, which ->
                when (which) {
                    0 -> {
                        // Edit & Restart
                        val input = EditText(this).apply {
                            setText(message.text)
                            selectAll()
                        }
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Edit Message")
                            .setView(input)
                            .setPositiveButton("Send") { _, _ ->
                                val newText = input.text.toString()
                                if (newText.isNotBlank()) {
                                    // Remove this message and everything after it safely
                                    val removeCount = messages.size - position
                                    while (messages.size > position) {
                                        messages.removeAt(messages.size - 1)
                                    }
                                    chatAdapter.notifyItemRangeRemoved(position, removeCount)
                                    saveChatHistory()
                                    sendMessage(newText)
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    1 -> {
                        // Delete From Here
                        val removeCount = messages.size - position
                        while (messages.size > position) {
                            messages.removeAt(messages.size - 1)
                        }
                        chatAdapter.notifyItemRangeRemoved(position, removeCount)
                        saveChatHistory()
                        Toast.makeText(this, "Messages deleted.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveChatHistory() {
        val sharedPrefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val messagesToSave = if (messages.size > 100) messages.takeLast(100) else messages
        val json = Gson().toJson(messagesToSave)
        sharedPrefs.edit().putString("gemini_chat_history", json).apply()
    }
}

data class ChatMessage(val text: String, val isUser: Boolean)

class ChatAdapter(
    private val messages: List<ChatMessage>,
    private val onMessageLongClick: (Int) -> Unit
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageContainer: LinearLayout = view.findViewById(R.id.messageContainer)
        val messageTextView: TextView = view.findViewById(R.id.messageTextView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val message = messages[position]
        holder.messageTextView.text = message.text

        val layoutParams = holder.messageTextView.layoutParams as LinearLayout.LayoutParams
        if (message.isUser) {
            holder.messageContainer.gravity = android.view.Gravity.END
            holder.messageTextView.setBackgroundColor(0xFFDCF8C6.toInt()) // Light green
            layoutParams.gravity = android.view.Gravity.END
        } else {
            holder.messageContainer.gravity = android.view.Gravity.START
            holder.messageTextView.setBackgroundColor(0xFFE0E0E0.toInt()) // Light gray
            layoutParams.gravity = android.view.Gravity.START
        }
        holder.messageTextView.layoutParams = layoutParams

        holder.messageContainer.setOnLongClickListener {
            onMessageLongClick(holder.adapterPosition)
            true
        }
    }

    override fun getItemCount() = messages.size
}
