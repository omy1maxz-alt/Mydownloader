package com.omymaxz.download

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.omymaxz.download.databinding.ItemBookmarkBinding

class BookmarkAdapter(
    private var bookmarks: MutableList<Bookmark>,
    private val onItemClick: (Bookmark) -> Unit,
    private val onItemLongClick: (Bookmark) -> Unit // For delete
) : RecyclerView.Adapter<BookmarkAdapter.BookmarkViewHolder>() {

    fun updateData(newBookmarks: List<Bookmark>) {
        bookmarks.clear()
        bookmarks.addAll(newBookmarks)
        notifyDataSetChanged()
    }

    class BookmarkViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val binding = ItemBookmarkBinding.bind(view)
        val title: TextView = binding.tvBookmarkTitle
        val hostView: TextView = binding.tvBookmarkHost
        val icon: ImageView = binding.ivBookmarkIcon

        fun bind(
            bookmark: Bookmark,
            onItemClick: (Bookmark) -> Unit,
            onItemLongClick: (Bookmark) -> Unit
        ) {
            title.text = bookmark.title.ifBlank { bookmark.host ?: "Bookmark" }
            val cleanHost = (bookmark.host ?: runCatching { Uri.parse(bookmark.url).host }.getOrNull() ?: bookmark.url)
                .removePrefix("www.")
            hostView.text = cleanHost

            itemView.setOnClickListener { onItemClick(bookmark) }
            itemView.setOnLongClickListener {
                onItemLongClick(bookmark)
                true // Consume the long click
            }

            try {
                if (bookmark.host != null) {
                    val faviconUrl = "https://www.google.com/s2/favicons?domain=${bookmark.host}&sz=128"
                    Glide.with(itemView.context)
                        .load(faviconUrl)
                        .placeholder(R.drawable.ic_public)
                        .error(R.drawable.ic_public)
                        .into(icon)
                } else {
                    icon.setImageResource(R.drawable.ic_public)
                }
            } catch (e: Exception) {
                icon.setImageResource(R.drawable.ic_public)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bookmark, parent, false)
        return BookmarkViewHolder(view)
    }

    override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
        holder.bind(bookmarks[position], onItemClick, onItemLongClick)
    }

    override fun getItemCount() = bookmarks.size
}
