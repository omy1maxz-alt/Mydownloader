with open("./app/src/main/java/com/omymaxz/download/MediaModels.kt", "r") as f:
    content = f.read()

target = """data class MediaFile(
    val url: String,
    var title: String,
    val mimeType: String,
    val quality: String,
    val category: MediaCategory,
    var fileSize: String,
    val language: String?,
    var isMainContent: Boolean,
    var referer: String? = null,
    var audioUrl: String? = null
)"""

replacement = """data class MediaFile(
    val url: String,
    var title: String,
    val mimeType: String,
    val quality: String,
    val category: MediaCategory,
    var fileSize: String,
    val language: String?,
    var isMainContent: Boolean,
    var referer: String? = null,
    var audioUrl: String? = null,
    var subtitleUrls: List<String>? = null
)"""

if target in content:
    content = content.replace(target, replacement)
    with open("./app/src/main/java/com/omymaxz/download/MediaModels.kt", "w") as f:
        f.write(content)
    print("Fixed MediaFile")
else:
    print("Failed")
