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
        val icon: ImageView = binding.ivBookmarkIcon

        fun bind(
            bookmark: Bookmark,
            onItemClick: (Bookmark) -> Unit,
            onItemLongClick: (Bookmark) -> Unit
        ) {
            title.text = bookmark.title
            itemView.setOnClickListener { onItemClick(bookmark) }
            itemView.setOnLongClickListener {
                onItemLongClick(bookmark)
                true // Consume the long click
            }

            try {
                val domain = Uri.parse(bookmark.url).host
                val faviconUrl = "https://www.google.com/s2/favicons?domain=$domain&sz=128"

                Glide.with(itemView.context)
                    .load(faviconUrl)
                    // Use android.R to access system resources
                    .placeholder(android.R.drawable.ic_menu_compass) 
                    .error(android.R.drawable.ic_menu_compass) 
                    .into(icon)
            } catch (e: Exception) {
                // Use android.R here as well
                icon.setImageResource(android.R.drawable.ic_menu_compass)
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
