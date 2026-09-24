import sys

with open("app/src/main/java/com/omymaxz/download/IframeSniffer.kt", "r") as f:
    content = f.read()

# IframeSniffer must also reject URLs that strongly match ad signatures instead of sending them up blindly
old_sniffer = """                        if (isMediaUrl(reqUrl)) {
                            if (isAdUrl(reqUrl)) {
                                Log.d("IframeSniffer", "Rejected AD URL: $reqUrl")
                            } else {
                                Log.d("IframeSniffer", "Candidate found: $reqUrl")
                                addCandidate(reqUrl)
                            }
                        }"""

new_sniffer = """                        if (isMediaUrl(reqUrl)) {
                            if (isAdUrl(reqUrl)) {
                                Log.d("IframeSniffer", "Rejected AD URL: $reqUrl")
                            } else {
                                Log.d("IframeSniffer", "Candidate found: $reqUrl")
                                addCandidate(reqUrl)
                            }
                        }"""

# Actually, IframeSniffer doesn't have duration. It just sniffs network requests inside the hidden WebView.
# But it does have `isAdUrl`. Let's just make sure the scores reflect proper weighting.
old_score = """    private fun calculateScore(url: String): Int {
        val lowerUrl = url.lowercase()
        var score = 10

        if (lowerUrl.contains(".m3u8")) score += 20
        else if (lowerUrl.contains(".mpd")) score += 15
        else if (lowerUrl.contains(".mp4") || lowerUrl.contains(".webm")) score += 5

        if (lowerUrl.contains("master") || lowerUrl.contains("index")) score += 10

        // Give a huge boost to the FIRST candidate we see to strongly prefer it, but only if it's a good type
        if (candidates.isEmpty() && (lowerUrl.contains(".m3u8") || lowerUrl.contains(".mpd"))) {
             score += 15
        }

        return score
    }"""

new_score = """    private fun calculateScore(url: String): Int {
        val lowerUrl = url.lowercase()
        var score = 10

        if (lowerUrl.contains(".m3u8") || lowerUrl.contains("/master.txt") || lowerUrl.contains("/hls/")) score += 20
        else if (lowerUrl.contains(".mpd")) score += 15
        else if (lowerUrl.contains(".mp4") || lowerUrl.contains(".webm")) score += 5

        if (lowerUrl.contains("master") || lowerUrl.contains("index")) score += 10

        // Give a huge boost to the FIRST candidate we see to strongly prefer it, but only if it's a good type
        if (candidates.isEmpty() && (lowerUrl.contains(".m3u8") || lowerUrl.contains(".mpd") || lowerUrl.contains("/master.txt"))) {
             score += 15
        }

        // Penalize likely short trailers
        if (lowerUrl.contains("preview") || lowerUrl.contains("trailer") || lowerUrl.contains("teaser")) {
             score -= 50
        }

        return score
    }"""

content = content.replace(old_score, new_score)

with open("app/src/main/java/com/omymaxz/download/IframeSniffer.kt", "w") as f:
    f.write(content)

print("Done")
