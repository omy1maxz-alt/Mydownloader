package com.omymaxz.download

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.omymaxz.download.databinding.ItemHistoryBinding

class HistoryAdapter(
    private var historyItems: MutableList<HistoryItem>,
    private val onItemClick: (HistoryItem) -> Unit,
    private val onDeleteClick: (HistoryItem, Int) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    class HistoryViewHolder(private val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            item: HistoryItem,
            onItemClick: (HistoryItem) -> Unit,
            onDeleteClick: (HistoryItem, Int) -> Unit
        ) {
            binding.pageTitle.text = item.title
            binding.pageUrl.text = item.hostname
            binding.root.setOnClickListener { onItemClick(item) }
            binding.deleteButton.setOnClickListener { onDeleteClick(item, adapterPosition) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(historyItems[position], onItemClick, onDeleteClick)
    }

    override fun getItemCount() = historyItems.size

    fun removeItem(position: Int) {
        if (position in historyItems.indices) {
            historyItems.removeAt(position)
            notifyItemRemoved(position)
        }
    }
    
    fun updateData(newItems: List<HistoryItem>) {
        historyItems = newItems.toMutableList()
        notifyDataSetChanged()
    }
}