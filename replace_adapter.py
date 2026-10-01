with open('app/src/main/java/com/omymaxz/download/MediaListAdapter.kt', 'r') as f:
    content = f.read()

replacement = """        fun bind(mediaFile: MediaFile) {
            val btnInfo = itemView.findViewById<android.widget.ImageButton>(R.id.btn_media_info)
            btnInfo.setOnClickListener {
                (itemView.context as? MainActivity)?.showMediaInfoDialog(mediaFile)
            }

            val btnPlay = itemView.findViewById<android.widget.ImageButton>(R.id.btn_play_media)
            if (mediaFile.category == MediaCategory.VIDEO || mediaFile.category == MediaCategory.AUDIO) {
                btnPlay.visibility = android.view.View.VISIBLE
                btnPlay.setOnClickListener {
                    (itemView.context as? MainActivity)?.launchCustomPlayer(mediaFile)
                }
            } else {
                btnPlay.visibility = android.view.View.GONE
            }
"""

content = content.replace("""        fun bind(mediaFile: MediaFile) {
            val btnInfo = itemView.findViewById<android.widget.ImageButton>(R.id.btn_media_info)
            btnInfo.setOnClickListener {
                (itemView.context as? MainActivity)?.showMediaInfoDialog(mediaFile)
            }""", replacement)

with open('app/src/main/java/com/omymaxz/download/MediaListAdapter.kt', 'w') as f:
    f.write(content)
