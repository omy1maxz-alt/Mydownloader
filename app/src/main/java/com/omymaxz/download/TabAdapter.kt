package com.omymaxz.download

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.omymaxz.download.databinding.ItemTabBinding

class TabAdapter(
    private var tabs: List<Tab>,
    private val currentTabIndex: Int, // Pass in the current tab index
    private val onTabClick: (Int) -> Unit,
    private val onCloseClick: (Int) -> Unit
) : RecyclerView.Adapter<TabAdapter.TabViewHolder>() {

    class TabViewHolder(private val binding: ItemTabBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            tab: Tab,
            position: Int,
            onTabClick: (Int) -> Unit,
            onCloseClick: (Int) -> Unit
        ) {
            binding.tabTitle.text = tab.title
            binding.root.setOnClickListener { onTabClick(position) }
            binding.closeTabButton.setOnClickListener { onCloseClick(position) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val binding = ItemTabBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TabViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        val tab = tabs[position]
        holder.bind(tab, position, onTabClick, onCloseClick)

        // --- NEW: Highlight the active tab ---
        val context = holder.itemView.context
        if (position == currentTabIndex) {
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