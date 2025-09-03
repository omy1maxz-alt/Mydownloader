package com.omymaxz.download

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.omymaxz.download.databinding.ActivityHistoryBinding

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var historyAdapter: HistoryAdapter
    private var historyList = mutableListOf<HistoryItem>()
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        loadHistory()
        setupRecyclerView()
    }

    private fun setupRecyclerView() {
        historyAdapter = HistoryAdapter(
            historyItems = historyList,
            onItemClick = { item ->
                val resultIntent = Intent()
                resultIntent.putExtra("URL_TO_LOAD", item.url)
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            },
            onDeleteClick = { item, position ->
                removeHistoryItem(item)
                historyAdapter.removeItem(position)
            }
        )
        binding.historyRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.historyRecyclerView.adapter = historyAdapter
    }

    private fun loadHistory() {
        val sharedPrefs = getSharedPreferences("AppData", Context.MODE_PRIVATE)
        val historyJson = sharedPrefs.getString("HISTORY_V2", "[]")
        val type = object : TypeToken<MutableList<HistoryItem>>() {}.type
        historyList = gson.fromJson(historyJson, type)
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
        AlertDialog.Builder(this)
            .setTitle("Clear History?")
            .setMessage("Are you sure you want to delete all browsing history?")
            .setPositiveButton("Clear") { _, _ ->
                historyList.clear()
                saveHistory()
                historyAdapter.updateData(historyList)
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