package com.omymaxz.download

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.omymaxz.download.databinding.ActivityHistoryBinding

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var historyAdapter: HistoryAdapter
    private var historyList = mutableListOf<HistoryItem>()
    private var currentQuery = ""
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        loadHistory()
        setupRecyclerView()
        setupSearch()
        applyFilter()
    }

    private fun setupRecyclerView() {
        historyAdapter = HistoryAdapter(
            historyItems = historyList.toMutableList(),
            onItemClick = { item ->
                val resultIntent = Intent()
                resultIntent.putExtra("URL_TO_LOAD", item.url)
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            },
            onDeleteClick = { item, _ ->
                removeHistoryItem(item)
                applyFilter()
            }
        )
        binding.historyRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.historyRecyclerView.adapter = historyAdapter
    }

    private fun setupSearch() {
        binding.historySearchInput.doAfterTextChanged { editable ->
            currentQuery = editable?.toString()?.trim().orEmpty()
            applyFilter()
        }
    }

    private fun applyFilter() {
        val filtered = if (currentQuery.isBlank()) {
            historyList
        } else {
            historyList.filter { item ->
                item.title.contains(currentQuery, ignoreCase = true) ||
                    item.url.contains(currentQuery, ignoreCase = true) ||
                    item.hostname.contains(currentQuery, ignoreCase = true)
            }
        }
        historyAdapter.updateData(filtered)

        if (filtered.isEmpty()) {
            binding.historyEmptyState.visibility = View.VISIBLE
            binding.historyRecyclerView.visibility = View.GONE
            if (historyList.isEmpty()) {
                binding.historyEmptyTitle.setText(R.string.history_empty_title)
                binding.historyEmptySubtitle.setText(R.string.history_empty_subtitle)
            } else {
                binding.historyEmptyTitle.text = "No matching history"
                binding.historyEmptySubtitle.text = "Try a different keyword or URL filter."
            }
        } else {
            binding.historyEmptyState.visibility = View.GONE
            binding.historyRecyclerView.visibility = View.VISIBLE
        }
    }

    private fun loadHistory() {
        val sharedPrefs = getSharedPreferences("AppData", Context.MODE_PRIVATE)
        val historyJson = sharedPrefs.getString("HISTORY_V2", "[]")
        val type = object : TypeToken<MutableList<HistoryItem>>() {}.type
        historyList = gson.fromJson(historyJson, type) ?: mutableListOf()
    }
    
    private fun saveHistory() {
        val sharedPrefs = getSharedPreferences("AppData", Context.MODE_PRIVATE)
        val historyJson = gson.toJson(historyList)
        sharedPrefs.edit().putString("HISTORY_V2", historyJson).apply()
    }

    private fun removeHistoryItem(itemToRemove: HistoryItem) {
        historyList.remove(itemToRemove)
        saveHistory()
    }

    private fun clearHistory() {
        AlertDialog.Builder(this, R.style.CustomAlertDialogTheme)
            .setTitle("Clear Browsing History?")
            .setMessage("This will permanently remove all saved browsing history entries.")
            .setPositiveButton("Clear All") { _, _ ->
                historyList.clear()
                saveHistory()
                applyFilter()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.history_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.menu_clear_history -> {
                clearHistory()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}