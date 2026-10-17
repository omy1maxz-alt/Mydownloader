package com.omymaxz.download

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.omymaxz.download.databinding.ActivityUserScriptManagerBinding
import kotlinx.coroutines.launch

class UserScriptManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserScriptManagerBinding
    private lateinit var db: AppDatabase
    private lateinit var adapter: UserScriptAdapter

    private val addEditScriptLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            loadScripts()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserScriptManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        db = AppDatabase.getDatabase(this)
        
        setupRecyclerView()
        loadScripts()
        
        binding.fabAddScript.setOnClickListener {
            val intent = Intent(this, AddEditScriptActivity::class.java)
            addEditScriptLauncher.launch(intent)
        }
    }

    private fun setupRecyclerView() {
        adapter = UserScriptAdapter(
            scripts = mutableListOf(),
            onToggle = { script, isEnabled ->
                lifecycleScope.launch {
                    db.userScriptDao().update(script.copy(isEnabled = isEnabled))
                }
            },
            onDelete = { script, position ->
                showDeleteConfirmationDialog(script, position)
            },
            onEdit = { script ->
                val intent = Intent(this, AddEditScriptActivity::class.java)
                intent.putExtra("SCRIPT_ID", script.id)
                addEditScriptLauncher.launch(intent)
            }
        )
        binding.scriptsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.scriptsRecyclerView.adapter = adapter
    }

    private fun updateEmptyState() {
        val isEmpty = adapter.itemCount == 0
        binding.scriptsEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.scriptsRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun loadScripts() {
        lifecycleScope.launch {
            val scripts = db.userScriptDao().getAllScripts()
            adapter.updateScripts(scripts)
            updateEmptyState()
        }
    }

    private fun showDeleteConfirmationDialog(script: UserScript, position: Int) {
        AlertDialog.Builder(this, R.style.CustomAlertDialogTheme)
            .setTitle("Delete Userscript?")
            .setMessage("Are you sure you want to delete '${script.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    db.userScriptDao().deleteById(script.id)
                    runOnUiThread {
                        adapter.removeScript(position)
                        updateEmptyState()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}