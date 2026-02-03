package com.omymaxz.download

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.omymaxz.download.databinding.ItemTabBinding

class TabAdapter(
    private var tabs: List<Tab>,
    private val currentTabIndex: Int, // Pass in the current tab index
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

        holder.binding.tabTitle.text = tab.title

        if (isSelectionMode) {
            holder.binding.closeTabButton.visibility = View.GONE
            holder.itemView.setOnClickListener { toggleSelection(position) }
            holder.binding.closeTabButton.setOnClickListener(null)
        } else {
            holder.binding.closeTabButton.visibility = View.VISIBLE
            holder.itemView.setOnClickListener { onTabClick(position) }
            holder.binding.closeTabButton.setOnClickListener { onCloseClick(position) }
        }

        // --- Highlight Logic ---
        val context = holder.itemView.context
        if (isSelectionMode && selectedPositions.contains(position)) {
            holder.itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.selected_tab_bg))
        } else if (position == currentTabIndex) {
            holder.itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.active_tab_bg))
        } else {
            // Reset to the default background with ripple effect for other tabs
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            holder.itemView.setBackgroundResource(outValue.resourceId)
        }
    }

    override fun getItemCount() = tabs.size
}
