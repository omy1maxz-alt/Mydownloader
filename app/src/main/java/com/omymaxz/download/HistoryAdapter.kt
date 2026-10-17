package com.omymaxz.download

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
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
            binding.pageTitle.text = item.title.ifBlank { item.hostname.ifBlank { item.url } }
            val host = item.hostname.removePrefix("www.").ifBlank { item.url }
            val timeStr = if (item.timestamp > 0L) {
                DateUtils.getRelativeTimeSpanString(
                    item.timestamp,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                ).toString()
            } else ""
            binding.pageUrl.text = if (timeStr.isNotEmpty()) "$host • $timeStr" else host

            try {
                if (item.hostname.isNotBlank()) {
                    val faviconUrl = "https://www.google.com/s2/favicons?domain=${item.hostname}&sz=64"
                    Glide.with(binding.root.context)
                        .load(faviconUrl)
                        .placeholder(R.drawable.ic_public)
                        .error(R.drawable.ic_public)
                        .into(binding.favicon)
                } else {
                    binding.favicon.setImageResource(R.drawable.ic_public)
                }
            } catch (e: Exception) {
                binding.favicon.setImageResource(R.drawable.ic_public)
            }

            binding.root.setOnClickListener { onItemClick(item) }
            binding.deleteButton.setOnClickListener { onDeleteClick(item, bindingAdapterPosition) }
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