package com.omymaxz.download

import android.app.Activity
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.omymaxz.download.databinding.ActivityAddEditScriptBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class AddEditScriptActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddEditScriptBinding
    private lateinit var db: AppDatabase
    private var existingScript: UserScript? = null
    private var scriptId: String? = null

    companion object {
        // Define a maximum length for the script to prevent OutOfMemoryError
        private const val MAX_SCRIPT_LENGTH = 600_000 // 600KB
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddEditScriptBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        scriptId = intent.getStringExtra("SCRIPT_ID")
        if (scriptId != null) {
            loadExistingScript(scriptId!!)
        } else {
            title = "Add Script"
        }

        // Removed InputFilter - validation will be done in UserScript.isValid()

        binding.applyToAllCheckbox.setOnCheckedChangeListener { _, isChecked ->
            binding.editScriptUrlLayout.isEnabled = !isChecked
            if (isChecked) {
                binding.editScriptUrl.setText("*")
            } else {
                binding.editScriptUrl.setText("")
            }
        }

        binding.saveScriptButton.setOnClickListener {
            saveScript()
        }
    }

    private fun loadExistingScript(id: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val script = db.userScriptDao().getScriptById(id)
            withContext(Dispatchers.Main) {
                script?.let {
                    existingScript = it
                    binding.editScriptName.setText(it.name)
                    binding.editScriptUrl.setText(it.targetUrl)

                    // Load script code - check size before setting text
                    val scriptByteSize = it.script.toByteArray(Charsets.UTF_8).size
                    if (scriptByteSize <= MAX_SCRIPT_LENGTH) {
                        binding.editScriptCode.setText(it.script)
                    } else {
                        // Handle case where existing script is too large for our new limit
                        binding.editScriptCode.setText("// Script is too large (${scriptByteSize / 1000}KB) to display/edit safely. Max allowed is ${MAX_SCRIPT_LENGTH / 1000}KB.")
                        Toast.makeText(this@AddEditScriptActivity, "Warning: Existing script is very large (${scriptByteSize / 1000}KB). Display truncated.", Toast.LENGTH_LONG).show()
                    }
                    title = "Edit Script"

                    if (it.targetUrl == "*") {
                        binding.applyToAllCheckbox.isChecked = true
                    }
                }
            }
        }
    }

    private fun saveScript() {
        val name = binding.editScriptName.text.toString().trim()
        var urlPattern = binding.editScriptUrl.text.toString().trim()
        val code = binding.editScriptCode.text.toString() // Don't trim extremely large strings

        // Check byte size before creating UserScript object
        val codeByteSize = code.toByteArray(Charsets.UTF_8).size
        if (codeByteSize > MAX_SCRIPT_LENGTH) {
             Toast.makeText(this, "Script file size is too large (max ${MAX_SCRIPT_LENGTH / 1000}KB). Please reduce its size. Current size: ${codeByteSize / 1000}KB", Toast.LENGTH_LONG).show()
             return
        }

        if (binding.applyToAllCheckbox.isChecked) {
            urlPattern = "*"
        }

        if (name.isEmpty() || urlPattern.isEmpty() || code.isEmpty()) {
            Toast.makeText(this, "All fields are required.", Toast.LENGTH_SHORT).show()
            return
        }

        val scriptToSave = existingScript?.copy(
            name = name,
            targetUrl = urlPattern,
            script = code
        ) ?: UserScript(
            id = UUID.randomUUID().toString(),
            name = name,
            targetUrl = urlPattern,
            script = code,
            isEnabled = true
        )

        // The isValid check in UserScript.kt now uses the byte size
        if (!scriptToSave.isValid()) {
            // This check might be redundant now, but good as a final validation
            Toast.makeText(this, "Script is invalid or too large (max ${MAX_SCRIPT_LENGTH / 1000}KB)", Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Saving a large string to Room DB might take time, do it off the main thread
                db.userScriptDao().insert(scriptToSave)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AddEditScriptActivity, "Script saved successfully", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK) // Only set RESULT_OK, no data
                    finish()
                }
            } catch (e: OutOfMemoryError) {
                 withContext(Dispatchers.Main) {
                     Toast.makeText(this@AddEditScriptActivity, "Error: Not enough memory to save such a large script. Please reduce its size.", Toast.LENGTH_LONG).show()
                 }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AddEditScriptActivity, "Error saving script: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}