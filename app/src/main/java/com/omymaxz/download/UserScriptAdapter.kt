package com.omymaxz.download

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.omymaxz.download.databinding.ItemUserScriptBinding

class UserScriptAdapter(
    private var scripts: MutableList<UserScript>,
    private val onToggle: (UserScript, Boolean) -> Unit,
    private val onDelete: (UserScript, Int) -> Unit,
    private val onEdit: (UserScript) -> Unit
) : RecyclerView.Adapter<UserScriptAdapter.ScriptViewHolder>() {

    class ScriptViewHolder(private val binding: ItemUserScriptBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            script: UserScript,
            onToggle: (UserScript, Boolean) -> Unit,
            onDelete: (UserScript, Int) -> Unit,
            onEdit: (UserScript) -> Unit
        ) {
            binding.scriptName.text = script.name

            val patterns = script.matches.ifEmpty { listOf(script.targetUrl) }
            binding.scriptUrl.text = when {
                patterns.contains("*") || script.targetUrl == "*" -> "Runs on: All websites (*)"
                patterns.size == 1 -> "Runs on: ${patterns.first()}"
                else -> "Runs on: ${patterns.first()} (+${patterns.size - 1} more)"
            }

            binding.scriptRunAtBadge.text = when (script.runAt) {
                UserScript.RunAt.DOCUMENT_START -> "doc-start"
                UserScript.RunAt.DOCUMENT_END -> "doc-end"
                UserScript.RunAt.DOCUMENT_IDLE -> "doc-idle"
            }

            binding.scriptEnabledSwitch.setOnCheckedChangeListener(null)
            binding.scriptEnabledSwitch.isChecked = script.isEnabled
            binding.scriptEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
                onToggle(script, isChecked)
            }

            binding.deleteScriptButton.setOnClickListener {
                onDelete(script, bindingAdapterPosition)
            }

            binding.root.setOnClickListener {
                onEdit(script)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScriptViewHolder {
        val binding = ItemUserScriptBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ScriptViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ScriptViewHolder, position: Int) {
        holder.bind(scripts[position], onToggle, onDelete, onEdit)
    }

    override fun getItemCount() = scripts.size

    fun updateScripts(newScripts: List<UserScript>) {
        scripts.clear()
        scripts.addAll(newScripts)
        notifyDataSetChanged()
    }

    fun removeScript(position: Int) {
        if (position in scripts.indices) {
            scripts.removeAt(position)
            notifyItemRemoved(position)
        }
    }
}