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
    private var runAtTouchedByUser = false

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
            binding.editScriptUrl.isEnabled = !isChecked
            if (isChecked) {
                binding.editScriptUrl.setText("*")
            } else if (binding.editScriptUrl.text.toString().trim() == "*") {
                binding.editScriptUrl.setText("")
            }
        }

        binding.editScriptRunAt.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) runAtTouchedByUser = true
            false
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
                    binding.editScriptUrl.setText(it.matches.takeIf { values -> values.isNotEmpty() }?.joinToString("\n") ?: it.targetUrl)
                    binding.editScriptExcludes.setText(it.excludes.joinToString("\n"))
                    binding.editScriptRequires.setText(it.requires.joinToString("\n"))
                    binding.editScriptGrants.setText(it.grants.joinToString("\n"))
                    binding.editScriptRunAt.setSelection(
                        when (it.runAt) {
                            UserScript.RunAt.DOCUMENT_START -> 0
                            UserScript.RunAt.DOCUMENT_END -> 1
                            UserScript.RunAt.DOCUMENT_IDLE -> 2
                        }
                    )

                    val scriptByteSize = it.script.toByteArray(Charsets.UTF_8).size
                    if (scriptByteSize <= UserScript.MAX_SCRIPT_SIZE_BYTES) {
                        binding.editScriptCode.setText(it.script)
                    } else {
                        binding.editScriptCode.setText("// Script is too large (${scriptByteSize / 1000}KB) to display/edit safely. Max allowed is ${UserScript.MAX_SCRIPT_SIZE_BYTES / 1000}KB.")
                        Toast.makeText(this@AddEditScriptActivity, "Warning: Existing script is very large (${scriptByteSize / 1000}KB).", Toast.LENGTH_LONG).show()
                    }
                    title = "Edit Script"
                    
                    if (it.matches.contains("*") || it.targetUrl == "*") {
                        binding.applyToAllCheckbox.isChecked = true
                    }
                }
            }
        }
    }

    private fun saveScript() {
        val enteredName = binding.editScriptName.text.toString().trim()
        var urlPattern = binding.editScriptUrl.text.toString().trim()
        val enteredMatches = urlPattern.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val enteredExcludes = binding.editScriptExcludes.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val enteredRequires = binding.editScriptRequires.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val enteredGrants = binding.editScriptGrants.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val code = binding.editScriptCode.text.toString()

        val codeByteSize = code.toByteArray(Charsets.UTF_8).size
        if (codeByteSize > UserScript.MAX_SCRIPT_SIZE_BYTES) {
            Toast.makeText(this, "Script file size is too large (max ${UserScript.MAX_SCRIPT_SIZE_BYTES / 1000}KB). Current size: ${codeByteSize / 1000}KB", Toast.LENGTH_LONG).show()
            return
        }

        val runAtFromEditor = when (binding.editScriptRunAt.selectedItemPosition) {
            0 -> UserScript.RunAt.DOCUMENT_START
            2 -> UserScript.RunAt.DOCUMENT_IDLE
            else -> UserScript.RunAt.DOCUMENT_END
        }
        val metadataBase = existingScript?.copy(
            name = enteredName.ifBlank { existingScript?.name.orEmpty() },
            targetUrl = enteredMatches.firstOrNull() ?: existingScript?.targetUrl ?: "*",
            matches = enteredMatches,
            excludes = enteredExcludes,
            requires = enteredRequires,
            grants = enteredGrants,
            runAt = runAtFromEditor,
            script = code
        ) ?: UserScript(
            id = UUID.randomUUID().toString(),
            name = enteredName.ifBlank { "Userscript" },
            targetUrl = enteredMatches.firstOrNull() ?: "*",
            matches = enteredMatches,
            excludes = enteredExcludes,
            requires = enteredRequires,
            grants = enteredGrants,
            runAt = runAtFromEditor,
            script = code,
            isEnabled = true
        )
        val parsedMetadata = metadataBase.parseMetadata()

        val finalMatches = when {
            binding.applyToAllCheckbox.isChecked -> listOf("*")
            enteredMatches.isNotEmpty() -> enteredMatches
            parsedMetadata.matches.isNotEmpty() -> parsedMetadata.matches
            existingScript != null -> existingScript!!.matches.ifEmpty { listOf(existingScript!!.targetUrl) }
            else -> emptyList()
        }.distinct()
        val finalExcludes = enteredExcludes.ifEmpty { parsedMetadata.excludes }.distinct()
        val finalRequires = enteredRequires.ifEmpty { parsedMetadata.requires }.distinct()
        val finalGrants = enteredGrants.ifEmpty { parsedMetadata.grants }.distinct()
        val finalName = enteredName.ifBlank { parsedMetadata.name }.trim()
        val finalRunAt = if (runAtTouchedByUser) runAtFromEditor else parsedMetadata.runAt

        if (finalName.isEmpty() || finalMatches.isEmpty() || code.isEmpty()) {
            Toast.makeText(this, "Add a script name and at least one match pattern, or include them in the userscript metadata header.", Toast.LENGTH_LONG).show()
            return
        }

        val scriptToSave = parsedMetadata.copy(
            name = finalName,
            targetUrl = finalMatches.first(),
            matches = finalMatches,
            excludes = finalExcludes,
            requires = finalRequires,
            grants = finalGrants,
            runAt = finalRunAt
        )

        if (!scriptToSave.isValid()) {
            Toast.makeText(this, "Script is invalid. Check its URL patterns, required library URLs, or size limit.", Toast.LENGTH_LONG).show()
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
