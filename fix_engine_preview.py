import sys

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "r") as f:
    content = f.read()

old_ad_keywords = """        val adKeywords = listOf(
            "vast", "preroll", "midroll", "postroll", "doubleclick", "googlesyndication",
            "adnxs", "adservice", "promo", "banner", "tracker", "analytics", "beacon",
            "/ads/", "/ad/", "commercial", "sponsor", "pubmatic", "rubicon", "smartadserver",
            "scorecardresearch", "criteo", "outbrain", "taboola", "moatads", "advertising",
            "tiktokcdn", "ad-site"
        )"""

new_ad_keywords = """        val adKeywords = listOf(
            "vast", "preroll", "midroll", "postroll", "doubleclick", "googlesyndication",
            "adnxs", "adservice", "promo", "banner", "tracker", "analytics", "beacon",
            "/ads/", "/ad/", "commercial", "sponsor", "pubmatic", "rubicon", "smartadserver",
            "scorecardresearch", "criteo", "outbrain", "taboola", "moatads", "advertising",
            "tiktokcdn", "ad-site", "/heat-preview/", "heatmap", "preview_v", "/trailer/",
            "/teaser/", "short_preview", "/preview/"
        )"""

content = content.replace(old_ad_keywords, new_ad_keywords)

# Also block them in final scoring so they can't sneak past the adUrl test
old_final_score = """            if (isManifest || mediaKind == MediaKind.HLS_MANIFEST || mediaKind == MediaKind.DASH_MANIFEST) score += 20
            if (mediaKind == MediaKind.PROGRESSIVE) score += 15"""

new_final_score = """            if (isManifest || mediaKind == MediaKind.HLS_MANIFEST || mediaKind == MediaKind.DASH_MANIFEST) score += 20
            if (mediaKind == MediaKind.PROGRESSIVE) score += 15

            val lowerUrl = url.lowercase()
            if (lowerUrl.contains("preview") || lowerUrl.contains("trailer") || lowerUrl.contains("teaser") || lowerUrl.contains("heatmap")) {
                score -= 50
            }"""

content = content.replace(old_final_score, new_final_score)

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "w") as f:
    f.write(content)

print("Done")
