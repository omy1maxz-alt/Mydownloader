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
                    // The name in the EditText should be the one the user can edit, not the metadata one.
                    binding.editScriptName.setText(it.name) 
                    binding.editScriptUrl.setText(it.targetUrl)

                    val scriptByteSize = it.script.toByteArray(Charsets.UTF_8).size
                    if (scriptByteSize <= UserScript.MAX_SCRIPT_SIZE_BYTES) {
                        binding.editScriptCode.setText(it.script)
                    } else {
                        binding.editScriptCode.setText("// Script is too large (${scriptByteSize / 1000}KB) to display/edit safely. Max allowed is ${UserScript.MAX_SCRIPT_SIZE_BYTES / 1000}KB.")
                        Toast.makeText(this@AddEditScriptActivity, "Warning: Existing script is very large (${scriptByteSize / 1000}KB).", Toast.LENGTH_LONG).show()
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
        val code = binding.editScriptCode.text.toString()

        val codeByteSize = code.toByteArray(Charsets.UTF_8).size
        if (codeByteSize > UserScript.MAX_SCRIPT_SIZE_BYTES) {
            Toast.makeText(this, "Script file size is too large (max ${UserScript.MAX_SCRIPT_SIZE_BYTES / 1000}KB). Current size: ${codeByteSize / 1000}KB", Toast.LENGTH_LONG).show()
            return
        }

        if (binding.applyToAllCheckbox.isChecked) {
            urlPattern = "*"
        }

        if (name.isEmpty() || urlPattern.isEmpty() || code.isEmpty()) {
            Toast.makeText(this, "All fields are required.", Toast.LENGTH_SHORT).show()
            return
        }

        val initialScript = existingScript?.copy(
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

        // **FIX IMPLEMENTED HERE:** Parse metadata from the script's code before saving.
        // This will update the name, runAt, grants, etc., based on the script's header.
        val scriptToSave = initialScript.parseMetadata()

        if (!scriptToSave.isValid()) {
            Toast.makeText(this, "Script is invalid or too large (max ${UserScript.MAX_SCRIPT_SIZE_BYTES / 1000}KB)", Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                db.userScriptDao().insert(scriptToSave)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AddEditScriptActivity, "Script saved successfully", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
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
