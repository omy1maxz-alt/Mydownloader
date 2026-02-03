package com.omymaxz.download

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*
import android.net.Uri

class MediaListAdapter(
    private val mediaFiles: List<MediaFile>,
    private val onItemClicked: (MediaFile) -> Unit,
    private val onItemLongClicked: (MediaFile) -> Unit
) : RecyclerView.Adapter<MediaListAdapter.MediaViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_media, parent, false)
        return MediaViewHolder(view)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        val mediaFile = mediaFiles[position]
        holder.bind(mediaFile)
        holder.itemView.setOnClickListener {
            onItemClicked(mediaFile)
        }
        holder.itemView.setOnLongClickListener {
            onItemLongClicked(mediaFile)
            true
        }

        // --- FIXED: Visual styling using our custom colors ---
        val context = holder.itemView.context
        when (mediaFile.category) {
            MediaCategory.VIDEO -> {
                holder.itemView.alpha = if (mediaFile.isMainContent) 1.0f else 0.8f
                val colorRes = if (mediaFile.isMainContent) R.color.media_video_main_bg else R.color.media_video_clip_bg
                holder.itemView.setBackgroundColor(ContextCompat.getColor(context, colorRes))
            }
            MediaCategory.SUBTITLE -> {
                holder.itemView.alpha = 0.7f
                holder.itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.media_subtitle_bg))
            }
            MediaCategory.AD -> {
                holder.itemView.alpha = 0.5f
                holder.itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.media_ad_bg))
            }
            else -> { // Handles AUDIO, THUMBNAIL, UNKNOWN
                holder.itemView.alpha = 0.9f
                holder.itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.media_other_bg))
            }
        }
    }

    override fun getItemCount(): Int = mediaFiles.size

    class MediaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.media_title)
        private val typeTextView: TextView = itemView.findViewById(R.id.media_type)
        private val thumbnailView: ImageView = itemView.findViewById(R.id.media_thumbnail)

        fun bind(mediaFile: MediaFile) {
            val categoryIndicator = when (mediaFile.category) {
                MediaCategory.VIDEO -> if (mediaFile.isMainContent) "🎥 MAIN VIDEO" else "🎬 Video Clip"
                MediaCategory.AUDIO -> "🔊 Audio"
                MediaCategory.SUBTITLE -> "📝 Subtitle ${mediaFile.language ?: ""}"
                MediaCategory.THUMBNAIL -> "🖼️ Thumbnail"
                MediaCategory.AD -> "🚫 Advertisement"
                else -> "❓ ${mediaFile.category.displayName}"
            }

            titleTextView.text = "$categoryIndicator\n${mediaFile.title}"

            val sourceInfo = detectSourceAndFormat(mediaFile.url)
            val sizeInfo = if (mediaFile.fileSize != "Unknown") " • ${mediaFile.fileSize}" else ""
            val mainContentIndicator = if (mediaFile.isMainContent && mediaFile.category == MediaCategory.VIDEO) " • ⭐ RECOMMENDED" else ""

            val typeText = "${sourceInfo.source} • ${mediaFile.quality} • ${sourceInfo.format}${sizeInfo}${mainContentIndicator}"
            typeTextView.text = typeText

            val iconRes = when(mediaFile.category) {
                MediaCategory.VIDEO -> android.R.drawable.ic_media_play
                MediaCategory.AUDIO -> android.R.drawable.ic_media_ff
                MediaCategory.SUBTITLE -> android.R.drawable.ic_menu_sort_by_size
                MediaCategory.THUMBNAIL -> android.R.drawable.ic_menu_gallery
                MediaCategory.AD -> android.R.drawable.ic_menu_close_clear_cancel
                else -> android.R.drawable.ic_menu_help
            }
            thumbnailView.setImageResource(iconRes)

            titleTextView.textSize = when {
                mediaFile.isMainContent && mediaFile.category == MediaCategory.VIDEO -> 16f
                mediaFile.category == MediaCategory.VIDEO -> 14f
                else -> 12f
            }
        }

        private fun detectSourceAndFormat(url: String): SourceInfo {
            val lowerUrl = url.lowercase()
            val uri = Uri.parse(url)

            val source = when {
                lowerUrl.contains("youtube.com") || lowerUrl.contains("youtu.be") -> "YouTube"
                lowerUrl.contains("tiktok.com") -> "TikTok"
                lowerUrl.contains("instagram.com") -> "Instagram"
                lowerUrl.contains("facebook.com") || lowerUrl.contains("fb.com") -> "Facebook"
                lowerUrl.contains("twitter.com") || lowerUrl.contains("x.com") -> "Twitter"
                lowerUrl.contains("vimeo.com") -> "Vimeo"
                lowerUrl.contains("dailymotion.com") -> "Dailymotion"
                lowerUrl.contains("twitch.tv") -> "Twitch"
                uri.host != null -> uri.host!!.replace("www.", "").replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                }
                else -> "Unknown"
            }

            val format = when {
                lowerUrl.contains(".mp4") -> "MP4"
                lowerUrl.contains(".mkv") -> "MKV"
                lowerUrl.contains(".webm") -> "WebM"
                lowerUrl.contains(".avi") -> "AVI"
                lowerUrl.contains(".mov") -> "MOV"
                lowerUrl.contains(".flv") -> "FLV"
                lowerUrl.contains(".m3u8") -> "HLS Stream"
                lowerUrl.contains(".m4v") -> "M4V"
                lowerUrl.contains("videoplayback") -> "MP4"
                else -> "Video"
            }

            return SourceInfo(source, format)
        }
    }

    data class SourceInfo(val source: String, val format: String)
}
