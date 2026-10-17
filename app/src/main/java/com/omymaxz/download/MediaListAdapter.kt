package com.omymaxz.download

import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class MediaListAdapter(
    var mediaFiles: MutableList<MediaFile>,
    private val onItemClicked: (MediaFile) -> Unit,
    private val onItemLongClicked: (MediaFile) -> Unit,
    private val onAnalyzeClicked: ((MediaFile, Int) -> Unit)? = null,
    private val onPlayClicked: ((MediaFile) -> Unit)? = null
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
    }

    override fun getItemCount(): Int = mediaFiles.size

    inner class MediaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.media_title)
        private val typeTextView: TextView = itemView.findViewById(R.id.media_type)
        private val thumbnailView: ImageView = itemView.findViewById(R.id.media_thumbnail)
        private val badgeView: TextView? = itemView.findViewById(R.id.media_badge)
        private val secondaryBadgeView: TextView? = itemView.findViewById(R.id.media_secondary_badge)
        private val btnAnalyzeMedia: TextView? = itemView.findViewById(R.id.btn_analyze_media)

        fun bind(mediaFile: MediaFile) {
            val context = itemView.context
            val density = itemView.resources.displayMetrics.density

            val btnInfo = itemView.findViewById<ImageButton>(R.id.btn_media_info)
            btnInfo.setOnClickListener {
                (itemView.context as? MainActivity)?.showMediaInfoDialog(mediaFile)
            }

            val btnPlay = itemView.findViewById<ImageButton>(R.id.btn_play_media)
            if (mediaFile.category == MediaCategory.VIDEO || mediaFile.category == MediaCategory.AUDIO) {
                btnPlay.visibility = View.VISIBLE
                btnPlay.setOnClickListener {
                    if (onPlayClicked != null) {
                        onPlayClicked.invoke(mediaFile)
                    } else {
                        (itemView.context as? MainActivity)?.launchCustomPlayer(mediaFile)
                    }
                }
            } else {
                btnPlay.visibility = View.GONE
            }

            // Semantic container + foreground pairing for both light and dark modes
            val (bgColorRes, fgColorRes) = when (mediaFile.category) {
                MediaCategory.VIDEO -> when {
                    mediaFile.hasMultipleQualities -> R.color.media_video_multi_quality_bg to R.color.media_video_multi_quality_fg
                    mediaFile.isMainContent -> R.color.media_video_main_bg to R.color.media_video_main_fg
                    else -> R.color.media_video_clip_bg to R.color.media_video_clip_fg
                }
                MediaCategory.AUDIO -> R.color.media_audio_bg to R.color.media_audio_fg
                MediaCategory.SUBTITLE -> R.color.media_subtitle_bg to R.color.media_subtitle_fg
                MediaCategory.AD -> R.color.media_ad_bg to R.color.media_ad_fg
                else -> R.color.media_other_bg to R.color.media_other_fg
            }

            val bgColor = ContextCompat.getColor(context, bgColorRes)
            val fgColor = ContextCompat.getColor(context, fgColorRes)

            thumbnailView.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(bgColor)
            }

            val iconRes = when (mediaFile.category) {
                MediaCategory.VIDEO -> R.drawable.ic_video_file
                MediaCategory.AUDIO -> R.drawable.ic_audio_file
                MediaCategory.SUBTITLE -> R.drawable.ic_subtitle_file
                MediaCategory.THUMBNAIL -> R.drawable.ic_public
                MediaCategory.AD -> R.drawable.ic_close
                else -> R.drawable.ic_info_outline
            }
            thumbnailView.setImageResource(iconRes)
            thumbnailView.setColorFilter(fgColor)

            // Primary category badge pill
            val badgeLabel = when (mediaFile.category) {
                MediaCategory.VIDEO -> if (mediaFile.isMainContent) "MAIN VIDEO" else "VIDEO CLIP"
                MediaCategory.AUDIO -> "AUDIO"
                MediaCategory.SUBTITLE -> mediaFile.language?.takeIf { it.isNotBlank() }?.let { "SUBTITLE • ${it.uppercase(Locale.ROOT)}" } ?: "SUBTITLE"
                MediaCategory.THUMBNAIL -> "THUMBNAIL"
                MediaCategory.AD -> "AD / TRACKER"
                else -> mediaFile.category.displayName.uppercase(Locale.ROOT)
            }
            badgeView?.apply {
                text = badgeLabel
                setTextColor(fgColor)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 6f * density
                    setColor(bgColor)
                }
            }

            // Secondary badge pill for Multi-Quality or Recommended streams
            when {
                mediaFile.hasMultipleQualities -> {
                    secondaryBadgeView?.visibility = View.VISIBLE
                    secondaryBadgeView?.text = "MULTI-QUALITY"
                    val mqBg = ContextCompat.getColor(context, R.color.media_video_multi_quality_bg)
                    val mqFg = ContextCompat.getColor(context, R.color.media_video_multi_quality_fg)
                    secondaryBadgeView?.setTextColor(mqFg)
                    secondaryBadgeView?.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 6f * density
                        setColor(mqBg)
                    }
                }
                mediaFile.isMainContent && mediaFile.category == MediaCategory.VIDEO -> {
                    secondaryBadgeView?.visibility = View.VISIBLE
                    secondaryBadgeView?.text = "RECOMMENDED"
                    val recBg = ContextCompat.getColor(context, R.color.md_primary_container)
                    val recFg = ContextCompat.getColor(context, R.color.md_on_primary_container)
                    secondaryBadgeView?.setTextColor(recFg)
                    secondaryBadgeView?.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 6f * density
                        setColor(recBg)
                    }
                }
                else -> {
                    secondaryBadgeView?.visibility = View.GONE
                }
            }

            // Clean title without emoji clutter, maintaining consistent typography
            titleTextView.text = mediaFile.title
            titleTextView.textSize = 14.5f

            val sourceInfo = detectSourceAndFormat(mediaFile.url)
            val sizeInfo = if (mediaFile.fileSize != "Unknown") " • ${mediaFile.fileSize}" else ""
            val typeText = "${sourceInfo.source} • ${mediaFile.quality} • ${sourceInfo.format}$sizeInfo"
            typeTextView.text = typeText

            if (mediaFile.category == MediaCategory.VIDEO) {
                val needsAnalysis = mediaFile.fileSize == "Unknown" || !mediaFile.title.contains("Duration:")
                if (needsAnalysis && onAnalyzeClicked != null) {
                    btnAnalyzeMedia?.visibility = View.VISIBLE
                    btnAnalyzeMedia?.text = "Analyze Media"
                    btnAnalyzeMedia?.isEnabled = true
                    btnAnalyzeMedia?.setOnClickListener {
                        btnAnalyzeMedia.text = "Analyzing..."
                        btnAnalyzeMedia.isEnabled = false
                        onAnalyzeClicked.invoke(mediaFile, absoluteAdapterPosition)
                    }
                } else {
                    btnAnalyzeMedia?.visibility = View.GONE
                }
            } else {
                btnAnalyzeMedia?.visibility = View.GONE
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
                lowerUrl.contains(".mpd") -> "DASH Stream"
                lowerUrl.contains(".m4a") -> "M4A Audio"
                lowerUrl.contains(".mp3") -> "MP3 Audio"
                lowerUrl.contains(".vtt") -> "WebVTT"
                lowerUrl.contains(".srt") -> "SRT"
                lowerUrl.contains(".m4v") -> "M4V"
                lowerUrl.contains("videoplayback") -> "MP4"
                else -> "Stream"
            }

            return SourceInfo(source, format)
        }
    }

    data class SourceInfo(val source: String, val format: String)
}
