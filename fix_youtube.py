with open("./app/src/main/java/com/omymaxz/download/YoutubeExtractorHelper.kt", "r") as f:
    content = f.read()

import re

# Add extraction of Subtitles from NewPipe YoutubeExtractorHelper to attach to MediaFile
# We need to find `suspend fun extractMedia`

target = """            if (!dashManifestUrl.isNullOrEmpty()) {"""

replacement = """            val subtitleList = mutableListOf<String>()
            try {
                if (extractor.subtitlesDefault.isNotEmpty()) {
                    extractor.subtitlesDefault.forEach { sub ->
                        if (!sub.content.isNullOrEmpty()) {
                            subtitleList.add(sub.content)
                        }
                    }
                }
            } catch(e: Exception) {}

            if (!dashManifestUrl.isNullOrEmpty()) {"""

if target in content:
    content = content.replace(target, replacement)

    # We must also ensure subtitleList is passed into MediaFile creations
    content = content.replace(
        "isMainContent = true\n                )",
        "isMainContent = true,\n                    subtitleUrls = if (subtitleList.isNotEmpty()) subtitleList else null\n                )"
    )
    content = content.replace(
        "audioUrl = bestAudio.content\n                    )",
        "audioUrl = bestAudio.content,\n                        subtitleUrls = if (subtitleList.isNotEmpty()) subtitleList else null\n                    )"
    )

    with open("./app/src/main/java/com/omymaxz/download/YoutubeExtractorHelper.kt", "w") as f:
        f.write(content)
    print("Added Subtitles to NewPipe Extractor")
else:
    print("Could not find extraction point")

# Now update MediaFile model
with open("./app/src/main/java/com/omymaxz/download/MediaModels.kt", "r") as f:
    models = f.read()

target_model = """    val title: String,
    val mimeType: String?,
    val quality: String,
    val category: MediaCategory,
    val fileSize: String,
    val language: String?,
    var isMainContent: Boolean = false,
    val durationSec: Int? = null,
    val referer: String? = null,
    val audioUrl: String? = null
) : java.io.Serializable"""

replacement_model = """    val title: String,
    val mimeType: String?,
    val quality: String,
    val category: MediaCategory,
    val fileSize: String,
    val language: String?,
    var isMainContent: Boolean = false,
    val durationSec: Int? = null,
    val referer: String? = null,
    val audioUrl: String? = null,
    val subtitleUrls: List<String>? = null
) : java.io.Serializable"""

if target_model in models:
    models = models.replace(target_model, replacement_model)
    with open("./app/src/main/java/com/omymaxz/download/MediaModels.kt", "w") as f:
        f.write(models)
    print("Updated MediaFile model")
else:
    print("Could not find MediaFile model")

# Now update MainActivity intent building to pass subtitleUrls
with open("./app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    main_activity = f.read()

target_intent = """            if (!mediaFile.audioUrl.isNullOrEmpty()) {
                putExtra(YouTubeDownloadService.EXTRA_AUDIO_URL, mediaFile.audioUrl)
                putExtra("EXTRA_AUDIO_MIME_TYPE", "audio/mp4")
            }
        }"""

replacement_intent = """            if (!mediaFile.audioUrl.isNullOrEmpty()) {
                putExtra(YouTubeDownloadService.EXTRA_AUDIO_URL, mediaFile.audioUrl)
                putExtra("EXTRA_AUDIO_MIME_TYPE", "audio/mp4")
            }
            if (!mediaFile.subtitleUrls.isNullOrEmpty()) {
                putStringArrayListExtra(CustomPlayerActivity.EXTRA_SUBTITLE_URLS, java.util.ArrayList(mediaFile.subtitleUrls))
            }
        }"""

if target_intent in main_activity:
    main_activity = main_activity.replace(target_intent, replacement_intent)
    with open("./app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
        f.write(main_activity)
    print("Updated MainActivity intent")
else:
    print("Could not find MainActivity intent logic")
