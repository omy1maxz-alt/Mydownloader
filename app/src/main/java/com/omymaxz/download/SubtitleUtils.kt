package com.omymaxz.download

import java.util.regex.Pattern

object SubtitleUtils {
    private val TIMESTAMP_PATTERN = Pattern.compile("\\d{2}:\\d{2}:\\d{2}[.,]\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2}[.,]\\d{3}")
    private val TAG_PATTERN = Pattern.compile("<[^>]*>")

    fun extractSnippet(content: String): String? {
        val lines = content.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.equals("WEBVTT", ignoreCase = true)) continue
            if (trimmed.all { it.isDigit() }) continue // Index number
            if (TIMESTAMP_PATTERN.matcher(trimmed).find()) continue // Timestamp line

            // This is likely a text line
            // Remove music notes and other common subtitle markers
            var cleanText = trimmed.replace("♪", "").replace("♫", "").trim()

            // Remove HTML-like tags (e.g., <i>, <b>, <c.color>)
            cleanText = TAG_PATTERN.matcher(cleanText).replaceAll("")

            // If text is still empty (e.g. was just music notes), continue
            if (cleanText.isEmpty()) continue

            // Truncate to ~50 chars
            return if (cleanText.length > 50) {
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
        }
        return null
    }
}
