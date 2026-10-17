package com.omymaxz.download

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.omymaxz.download.databinding.ItemTabBinding

class TabAdapter(
    private var tabs: List<Tab>,
    private val currentTabIndex: Int,
    private val onTabClick: (Int) -> Unit,
    private val onCloseClick: (Int) -> Unit,
    private val onSelectionChanged: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<TabAdapter.TabViewHolder>() {

    var isSelectionMode = false
        private set
    private val selectedPositions = mutableSetOf<Int>()

    fun setSelectionMode(enabled: Boolean) {
        isSelectionMode = enabled
        selectedPositions.clear()
        notifyDataSetChanged()
    }

    fun toggleSelection(position: Int) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position)
        } else {
            selectedPositions.add(position)
        }
        notifyItemChanged(position)
        onSelectionChanged?.invoke(selectedPositions.size)
    }

    fun getSelectedPositions(): Set<Int> {
        return selectedPositions.toSet()
    }

    class TabViewHolder(val binding: ItemTabBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val binding = ItemTabBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TabViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        val tab = tabs[position]
        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density

        holder.binding.tabTitle.text = tab.title.ifBlank { "New Tab" }
        val urlSubtitle = tab.url?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching {
                val uri = Uri.parse(raw)
                val host = uri.host?.removePrefix("www.")
                if (!host.isNullOrBlank()) "$host${uri.path?.takeIf { p -> p != "/" }.orEmpty()}" else raw
            }.getOrDefault(raw)
        } ?: "Start page • Bookmarks"
        holder.binding.tabUrl.text = urlSubtitle

        val isActive = position == currentTabIndex
        val isSelected = isSelectionMode && selectedPositions.contains(position)

        holder.binding.tabActiveBadge.visibility = if (isActive && !isSelectionMode) View.VISIBLE else View.GONE

        if (isSelectionMode) {
            holder.binding.closeTabButton.visibility = View.GONE
            holder.itemView.setOnClickListener { toggleSelection(position) }
            holder.binding.closeTabButton.setOnClickListener(null)
        } else {
            holder.binding.closeTabButton.visibility = View.VISIBLE
            holder.itemView.setOnClickListener { onTabClick(position) }
            holder.binding.closeTabButton.setOnClickListener { onCloseClick(position) }
        }

        // Semantic Material 3 card styling for active, selected, and default states
        val card = holder.binding.tabCard
        when {
            isSelected -> {
                card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.selected_tab_bg))
                card.strokeColor = ContextCompat.getColor(context, R.color.selected_tab_stroke)
                card.strokeWidth = (1.5f * density).toInt()
            }
            isActive -> {
                card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.active_tab_bg))
                card.strokeColor = ContextCompat.getColor(context, R.color.active_tab_stroke)
                card.strokeWidth = (1.5f * density).toInt()
            }
            else -> {
                card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.md_surface_container))
                card.strokeColor = ContextCompat.getColor(context, R.color.md_outline_variant)
                card.strokeWidth = (1f * density).toInt()
            }
        }
    }

    override fun getItemCount() = tabs.size
}
