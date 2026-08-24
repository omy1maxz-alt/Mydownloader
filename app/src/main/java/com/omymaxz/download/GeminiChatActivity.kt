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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

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

        chatAdapter = ChatAdapter(messages)
        chatRecyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        chatRecyclerView.adapter = chatAdapter

        val sharedPrefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val apiKey = sharedPrefs.getString("gemini_api_key", "") ?: ""

        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Please configure Gemini API Key in Settings", Toast.LENGTH_LONG).show()
            chatInputEditText.isEnabled = false
            sendButton.isEnabled = false
        } else {
            generativeModel = GenerativeModel(
                modelName = "gemini-3.7-flash",
                apiKey = apiKey
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

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Here we are simply using generateContent. For a true chat we would use startChat()
                val response = generativeModel?.generateContent(query)
                val responseText = response?.text ?: "No response from Gemini."
                withContext(Dispatchers.Main) {
                    messages.add(ChatMessage(responseText, isUser = false))
                    chatAdapter.notifyItemInserted(messages.size - 1)
                    chatRecyclerView.scrollToPosition(messages.size - 1)
                }
            } catch (e: SerializationException) {
                withContext(Dispatchers.Main) {
                    messages.add(ChatMessage("Error: The AI response was not formatted as expected or the model is unsupported.", isUser = false))
                    chatAdapter.notifyItemInserted(messages.size - 1)
                    chatRecyclerView.scrollToPosition(messages.size - 1)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    messages.add(ChatMessage("Error: ${e.message}", isUser = false))
                    chatAdapter.notifyItemInserted(messages.size - 1)
                    chatRecyclerView.scrollToPosition(messages.size - 1)
                }
            }
        }
    }
}

data class ChatMessage(val text: String, val isUser: Boolean)

class ChatAdapter(private val messages: List<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

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
    }

    override fun getItemCount() = messages.size
}
