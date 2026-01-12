package com.omymaxz.download

import java.util.regex.Pattern

object SubtitleUtils {
    private val TIMESTAMP_PATTERN = Pattern.compile("\\d{2}:\\d{2}:\\d{2}[.,]\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2}[.,]\\d{3}")
    private val TAG_PATTERN = Pattern.compile("<[^>]*>")
    private val LANGUAGE_PATTERN = Pattern.compile("^Language:\\s*([a-zA-Z-]+)", Pattern.CASE_INSENSITIVE)

    data class SubtitleResult(val snippet: String?, val language: String?)

    fun extractSnippet(content: String): SubtitleResult {
        val lines = content.lines()
        var language: String? = null

        for (line in lines) {
            // Remove BOM and whitespace
            var trimmed = line.replace("\uFEFF", "").trim()

            if (trimmed.isEmpty()) continue

            // Skip Header
            if (trimmed.contains("WEBVTT", ignoreCase = true)) continue

            // Extract Language
            val langMatch = LANGUAGE_PATTERN.matcher(trimmed)
            if (langMatch.find()) {
                language = langMatch.group(1)
                continue
            }

            // Skip Metadata
            if (trimmed.startsWith("Kind:", ignoreCase = true) ||
                trimmed.startsWith("Style:", ignoreCase = true) ||
                trimmed.startsWith("Region:", ignoreCase = true) ||
                trimmed.startsWith("NOTE", ignoreCase = true)) {
                continue
            }

            // Skip Index numbers (usually just digits)
            if (trimmed.all { it.isDigit() }) continue

            // Skip Timestamps
            if (TIMESTAMP_PATTERN.matcher(trimmed).find()) continue

            // This is likely a text line
            // Remove music notes and other common subtitle markers
            var cleanText = trimmed.replace("♪", "").replace("♫", "").trim()

            // Remove HTML-like tags (e.g., <i>, <b>, <c.color>)
            cleanText = TAG_PATTERN.matcher(cleanText).replaceAll("")

            // If text is still empty (e.g. was just music notes), continue
            if (cleanText.isEmpty()) continue

            // Truncate to ~50 chars
            val snippet = if (cleanText.length > 50) {
                // Try to cut at a space if possible
                val cutIndex = cleanText.lastIndexOf(' ', 50)
                if (cutIndex != -1) {
                    cleanText.substring(0, cutIndex) + "..."
                } else {
                    cleanText.substring(0, 50) + "..."
                }
            } else {
                cleanText
            }
            return SubtitleResult(snippet, language)
        }
        return SubtitleResult(null, language)
    }
}
