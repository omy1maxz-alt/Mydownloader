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
import androidx.core.content.ContextCompat
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
import io.noties.markwon.Markwon

class GeminiChatActivity : AppCompatActivity() {

    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var chatEmptyState: View
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
        supportActionBar?.title = "Gemini AI Assistant"
        val ctxTitle = WebpageContextHolder.title
        if (!ctxTitle.isNullOrBlank()) {
            supportActionBar?.subtitle = ctxTitle
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        chatToolbar.setNavigationOnClickListener { finish() }

        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        chatEmptyState = findViewById(R.id.chatEmptyState)
        chatInputEditText = findViewById(R.id.chatInputEditText)
        sendButton = findViewById(R.id.sendButton)

        chatAdapter = ChatAdapter(messages,
            onMessageLongClick = { position -> handleMessageLongClick(position) },
            onMessageEdit = { position -> editMessage(position) },
            onMessageDelete = { position -> deleteMessage(position) }
        )
        chatRecyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        chatRecyclerView.adapter = chatAdapter

        val sharedPrefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val apiKey = sharedPrefs.getString("gemini_api_key", "") ?: ""
        val savedModel = sharedPrefs.getString("gemini_model", null)
        val preferredModel = when (savedModel) {
            null, "", "gemini-1.5-flash", "gemini-flash-latest", "gemini-1.5-flash-latest" -> "gemini-2.5-flash"
            else -> savedModel
        }

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
        updateEmptyState()

        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Please configure Gemini API Key in Settings", Toast.LENGTH_LONG).show()
            chatInputEditText.isEnabled = false
            sendButton.isEnabled = false
        } else {
            var systemPrompt = "You are a helpful browser assistant. The user is browsing the web inside a custom Android browser app."

            val ctxUrl = WebpageContextHolder.url
            val ctxText = WebpageContextHolder.textContent

            if (!ctxUrl.isNullOrEmpty() || !ctxText.isNullOrEmpty()) {
                systemPrompt += "\n\nHere is the context of the webpage the user is currently viewing:"
                if (!ctxTitle.isNullOrEmpty()) systemPrompt += "\nTitle: $ctxTitle"
                if (!ctxUrl.isNullOrEmpty()) systemPrompt += "\nURL: $ctxUrl"
                if (!ctxText.isNullOrEmpty()) systemPrompt += "\nPage Text (truncated): $ctxText"
            }

            generativeModel = GenerativeModel(
                modelName = preferredModel,
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

    private fun updateEmptyState() {
        val empty = messages.isEmpty()
        chatEmptyState.visibility = if (empty) View.VISIBLE else View.GONE
        chatRecyclerView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun sendMessage(query: String) {
        messages.add(ChatMessage(query, isUser = true))
        updateEmptyState()
        chatAdapter.notifyItemInserted(messages.size - 1)
        chatRecyclerView.scrollToPosition(messages.size - 1)
        chatInputEditText.text.clear()
        saveChatHistory()

        CoroutineScope(Dispatchers.IO).launch {
            var actualQuery = query
            val lowerQuery = query.lowercase()

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
                updateEmptyState()
                chatAdapter.notifyItemInserted(messages.size - 1)
                chatRecyclerView.scrollToPosition(messages.size - 1)
                saveChatHistory()
            }
        }
    }

    private fun editMessage(position: Int) {
        val message = messages[position]
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val input = EditText(this).apply {
            setText(message.text)
            selectAll()
            setBackgroundResource(R.drawable.edit_text_custom)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (16 * density).toInt(), (20 * density).toInt(), (8 * density).toInt())
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.CustomAlertDialogTheme)
            .setTitle("Edit & Restart From Here")
            .setView(layout)
            .setPositiveButton("Send") { _, _ ->
                val newText = input.text.toString()
                if (newText.isNotBlank()) {
                    val removeCount = messages.size - position
                    while (messages.size > position) {
                        messages.removeAt(messages.size - 1)
                    }
                    chatAdapter.notifyItemRangeRemoved(position, removeCount)
                    updateEmptyState()
                    saveChatHistory()
                    sendMessage(newText)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteMessage(position: Int) {
        val removeCount = messages.size - position
        while (messages.size > position) {
            messages.removeAt(messages.size - 1)
        }
        chatAdapter.notifyItemRangeRemoved(position, removeCount)
        updateEmptyState()
        saveChatHistory()
        Toast.makeText(this, "Messages deleted.", Toast.LENGTH_SHORT).show()
    }

    private fun handleMessageLongClick(position: Int) {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.CustomAlertDialogTheme)
            .setTitle("Chat Options")
            .setItems(arrayOf("Edit & Restart From Here", "Delete From Here")) { _, which ->
                when (which) {
                    0 -> editMessage(position)
                    1 -> deleteMessage(position)
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
    private val onMessageLongClick: (Int) -> Unit,
    private val onMessageEdit: (Int) -> Unit,
    private val onMessageDelete: (Int) -> Unit
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    private var markwon: Markwon? = null

    class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageContainer: LinearLayout = view.findViewById(R.id.messageContainer)
        val messageInnerContainer: LinearLayout = view.findViewById(R.id.messageInnerContainer)
        val messageTextView: TextView = view.findViewById(R.id.messageTextView)
        val actionButtonsContainer: LinearLayout = view.findViewById(R.id.actionButtonsContainer)
        val btnEditMessage: android.widget.ImageButton = view.findViewById(R.id.btnEditMessage)
        val btnDeleteMessage: android.widget.ImageButton = view.findViewById(R.id.btnDeleteMessage)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        markwon = Markwon.create(recyclerView.context)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val message = messages[position]
        markwon?.setMarkdown(holder.messageTextView, message.text)

        val context = holder.itemView.context
        if (message.isUser) {
            holder.messageContainer.gravity = android.view.Gravity.END
            holder.messageInnerContainer.gravity = android.view.Gravity.END
            holder.messageTextView.background = ContextCompat.getDrawable(context, R.drawable.bg_chat_bubble_user)
            holder.messageTextView.setTextColor(ContextCompat.getColor(context, R.color.chat_user_bubble_fg))
        } else {
            holder.messageContainer.gravity = android.view.Gravity.START
            holder.messageInnerContainer.gravity = android.view.Gravity.START
            holder.messageTextView.background = ContextCompat.getDrawable(context, R.drawable.bg_chat_bubble_model)
            holder.messageTextView.setTextColor(ContextCompat.getColor(context, R.color.chat_model_bubble_fg))
        }

        holder.actionButtonsContainer.visibility = View.VISIBLE

        holder.btnEditMessage.setOnClickListener {
            onMessageEdit(holder.bindingAdapterPosition)
        }

        holder.btnDeleteMessage.setOnClickListener {
            onMessageDelete(holder.bindingAdapterPosition)
        }

        holder.messageContainer.setOnLongClickListener {
            onMessageLongClick(holder.bindingAdapterPosition)
            true
        }
    }

    override fun getItemCount() = messages.size
}
