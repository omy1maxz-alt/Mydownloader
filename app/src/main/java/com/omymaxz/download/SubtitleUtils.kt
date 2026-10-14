package com.omymaxz.download

import android.net.Uri
import java.util.regex.Pattern

object SubtitleUtils {

    private val TIMESTAMP_PATTERN =
        Pattern.compile("\\d{2}:\\d{2}:\\d{2}[.,]\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2}[.,]\\d{3}")
    private val TAG_PATTERN = Pattern.compile("<[^>]*>")
    private val LANGUAGE_PATTERN =
        Pattern.compile("^Language:\\s*([a-zA-Z-]+)", Pattern.CASE_INSENSITIVE)

    /** Single parsed subtitle track from a master HLS playlist. */
    data class SubtitleTrack(
        val uri: String,          // absolute, resolved URI
        val language: String,     // ISO code, fallback "und"
        val name: String,         // human-readable name
        val isDefault: Boolean,
        val isForced: Boolean
    )

    data class SubtitleResult(val snippet: String?, val language: String?)

    /**
     * Parses every #EXT-X-MEDIA:TYPE=SUBTITLES,... block out of a master m3u8.
     * Handles:
     *   - attributes in any order
     *   - values wrapped in quotes
     *   - relative URIs (resolved against [masterBaseUrl])
     *   - multi-line tags (continuation lines that don't start with '#')
     */
    fun parseSubtitleTracks(masterPlaylist: String, masterBaseUrl: String): List<SubtitleTrack> {
        val out = mutableListOf<SubtitleTrack>()
        val lines = masterPlaylist.lines()
        var i = 0
        while (i < lines.size) {
            var line = lines[i].trim()
            if (line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=SUBTITLES", ignoreCase = true)) {
                // Accumulate continuation lines (HLS allows wrapped tags)
                val sb = StringBuilder(line)
                while (i + 1 < lines.size) {
                    val next = lines[i + 1]
                    if (next.startsWith("#") || next.isBlank()) break
                    sb.append(next)
                    i++
                }
                parseOneTrack(sb.toString(), masterBaseUrl)?.let { out.add(it) }
            }
            i++
        }
        return out
    }

    private fun parseOneTrack(tag: String, masterBaseUrl: String): SubtitleTrack? {
        val uri = readAttr(tag, "URI") ?: return null
        val language = (readAttr(tag, "LANGUAGE") ?: "und").lowercase()
        val name = readAttr(tag, "NAME") ?: language
        val isDefault = readAttr(tag, "DEFAULT")?.equals("YES", ignoreCase = true) == true
        val isForced = readAttr(tag, "FORCED")?.equals("YES", ignoreCase = true) == true

        // Resolve relative URIs against the master playlist's base URL.
        val absoluteUri = if (uri.startsWith("http://") || uri.startsWith("https://")) {
            uri
        } else {
            Uri.parse(masterBaseUrl).buildUpon().path(
                // resolve relative path against master's directory
                resolveRelative(masterBaseUrl, uri)
            ).build().toString()
        }
        return SubtitleTrack(absoluteUri, language, name, isDefault, isForced)
    }

    /** Read a KEY="VALUE" attribute from an HLS tag string. */
    private fun readAttr(tag: String, key: String): String? {
        val needle = "$key=\""
        val start = tag.indexOf(needle, ignoreCase = true)
        if (start < 0) return null
        val valueStart = start + needle.length
        val valueEnd = tag.indexOf('"', valueStart)
        return if (valueEnd > valueStart) tag.substring(valueStart, valueEnd) else null
    }

    /** Resolve a relative path against a base URL's directory. */
    private fun resolveRelative(baseUrl: String, relative: String): String {
        val baseDir = baseUrl.substringBeforeLast('/')
        return if (relative.startsWith("/")) relative else "$baseDir/$relative"
    }

    fun isEnglishSubtitleTrack(track: SubtitleTrack): Boolean {
        val l = track.language.lowercase()
        val n = track.name.lowercase()
        return l == "en" || l == "eng" || n.contains("english")
    }

    /**
     * Convert downloaded subtitle payloads to WebVTT so subtitle players can read them.
     * YouTube sometimes serves TTML/XML even when the URL ends in .vtt.
     */
    fun normalizeForDownload(content: String): String {
        val trimmed = content.trimStart().removePrefix("\uFEFF").trimStart()
        val isTtml = trimmed.startsWith("<tt", ignoreCase = true) ||
            (trimmed.startsWith("<?xml", ignoreCase = true) && trimmed.contains("<p", ignoreCase = true))
        // Strip YouTube's speaker marker (">>") from cue text, not from the timestamps/header.
        // Handle both actual WebVTT input and TTML converted below.
        if (!isTtml) {
            if (!trimmed.startsWith("WEBVTT", ignoreCase = true)) return content
            return normalizeSpeakerMarkersInVtt(content)
        }

        fun timeToVtt(value: String): String? {
            val time = value.trim()
            val clock = Regex("""^(\d{2}):(\d{2}):(\d{2})(?:[.,](\d+))?$""").matchEntire(time)
            if (clock != null) {
                val (hh, mm, ss, fraction) = clock.destructured
                val millis = fraction.padEnd(3, '0').take(3).ifEmpty { "000" }
                return "%02d:%02d:%02d.%s".format(hh.toInt(), mm.toInt(), ss.toInt(), millis)
            }
            val seconds = Regex("""^(\d+(?:\.\d+)?)(ms|s|m|h)$""").matchEntire(time) ?: return null
            val amount = seconds.groupValues[1].toDouble()
            val factor = when (seconds.groupValues[2]) { "ms" -> 0.001; "m" -> 60.0; "h" -> 3600.0; else -> 1.0 }
            val totalMs = (amount * factor * 1000).toLong().coerceAtLeast(0)
            return "%02d:%02d:%02d.%03d".format(
                totalMs / 3_600_000, (totalMs / 60_000) % 60,
                (totalMs / 1_000) % 60, totalMs % 1_000
            )
        }

        val cues = Regex(
            """<p\b([^>]*)>(.*?)</p>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(trimmed).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val begin = Regex("""\bbegin=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
            val end = Regex("""\bend=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
            val startTime = timeToVtt(begin) ?: return@mapNotNull null
            val endTime = timeToVtt(end) ?: return@mapNotNull null
            val text = match.groupValues[2]
                .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("""<[^>]+>"""), "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&apos;", "'").replace("&quot;", "\"").replace("&#39;", "'")
                .trim()
            if (text.isBlank()) null else startTime + " --> " + endTime + "\n" + text
        }.toList()

        if (cues.isEmpty()) return content
        return normalizeSpeakerMarkersInVtt("WEBVTT\n\n" + cues.joinToString("\n\n") + "\n")
    }

    /**
     * Removes leading YouTube dialogue speaker markers without altering arrow-like text
     * later in a cue or the WebVTT timing lines.
     */
    fun normalizeSpeakerMarkersInVtt(content: String): String {
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').lines()
        var inCueText = false
        return lines.joinToString("\n") { raw ->
            val line = raw
            when {
                line.trimStart().startsWith("WEBVTT", ignoreCase = true) -> {
                    inCueText = false
                    line
                }
                Regex("""^\s*\d{2}:\d{2}:\d{2}[.,]\d{3}\s*-->""").containsMatchIn(line) ||
                    Regex("""^\s*\d{2}:\d{2}\.\d{3}\s*-->""").containsMatchIn(line) -> {
                    inCueText = true
                    line
                }
                line.isBlank() -> {
                    inCueText = false
                    line
                }
                inCueText -> line.replace(Regex("""^\s*>>\s*"""), "")
                else -> line
            }
        }
    }

    fun extractSnippet(content: String): SubtitleResult {
        // YouTube timed-text captions often arrive as TTML/XML rather than WebVTT.
        if (content.trimStart().startsWith("<tt", ignoreCase = true) ||
            content.contains("<tt ", ignoreCase = true) ||
            content.contains("<p begin=", ignoreCase = true)) {
            val language = Regex("""xml:lang=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(content)?.groupValues?.getOrNull(1)
            val cues = Regex(
                """<p\b[^>]*>(.*?)</p>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).findAll(content).mapNotNull { match ->
                val cue = match.groupValues[1]
                    .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("""<[^>]+>"""), " ")
                    .replace("&amp;", "&").replace("&lt;", "<")
                    .replace("&gt;", ">").replace("&apos;", "'")
                    .replace("&quot;", "\"").replace("&#39;", "'")
                    .replace(Regex("""\s+"""), " ").trim()
                cue.takeIf { it.isNotEmpty() }
            }.take(12).toList()
            val snippet = cues.joinToString(" ").take(120).trim()
            return SubtitleResult(snippet.takeIf { it.isNotEmpty() }, language)
        }

        val lines = content.lines()
        var language: String? = null
        val accumulatedSnippet = java.lang.StringBuilder()

        for (raw in lines) {
            var line = raw.replace("\uFEFF", "").trim()
            if (line.isEmpty()) continue
            if (line.contains("WEBVTT", ignoreCase = true)) continue

            val langMatch = LANGUAGE_PATTERN.matcher(line)
            if (langMatch.find()) { language = langMatch.group(1); continue }

            if (line.startsWith("Kind:", ignoreCase = true) ||
                line.startsWith("Style:", ignoreCase = true) ||
                line.startsWith("Region:", ignoreCase = true) ||
                line.startsWith("NOTE", ignoreCase = true)
            ) continue

            if (line.all { it.isDigit() }) continue
            if (TIMESTAMP_PATTERN.matcher(line).find()) continue

            var clean = line
                .replace("â ª", "").replace("â «", "")
                .replace(Regex("\\{[^}]*\\}"), "")   // strip SSA/ASS overrides
            clean = TAG_PATTERN.matcher(clean).replaceAll("").trim()
            if (clean.isEmpty()) continue

            if (accumulatedSnippet.isNotEmpty()) accumulatedSnippet.append(" ")
            accumulatedSnippet.append(clean)

            if (accumulatedSnippet.length > 120) break
        }

        var finalSnippet = accumulatedSnippet.toString()
        if (finalSnippet.isEmpty()) {
            return SubtitleResult(null, language)
        }

        // Smart Language Detection Heuristics
        if (language.isNullOrBlank()) {
            val lowerText = finalSnippet.lowercase()
            val words = lowerText.split(Regex("\\W+"))

            val englishCount = words.count { it in setOf("the", "be", "to", "of", "and", "a", "in", "that", "have", "i", "it", "for", "not", "on", "with", "he", "as", "you", "do", "at") }
            val spanishCount = words.count { it in setOf("el", "la", "de", "que", "y", "en", "un", "una", "los", "las", "por", "con", "para", "como", "su") }
            val tagalogCount = words.count { it in setOf("ang", "ng", "sa", "na", "at", "mga", "ay", "ako", "ito", "si", "mo", "ni", "niya", "kami", "kaya") }
            val indonesianCount = words.count { it in setOf("yang", "di", "ke", "dari", "untuk", "pada", "dengan", "adalah", "ini", "itu", "dan", "atau", "tidak") }
            val malayCount = words.count { it in setOf("yang", "di", "ke", "dari", "untuk", "pada", "dengan", "ini", "itu", "dan", "atau", "tidak", "boleh", "saya", "awak") }
            val khmerCount = words.count { it in setOf("ខ្ញុំ", "អ្នក", "គាត់", "នាង", "វា", "យើង", "ពួកគេ", "នេះ", "នោះ") }
            val portugueseCount = words.count { it in setOf("o", "a", "os", "as", "um", "uma", "de", "do", "da", "em", "no", "na", "para", "com", "por", "que", "e", "é", "não") }
            val arabicCount = words.count { it in setOf("في", "من", "على", "إلى", "عن", "مع", "هذا", "هذه", "ذلك", "تلك", "هو", "هي", "هم", "هن", "نحن") }
            val hindiCount = words.count { it in setOf("है", "कि", "की", "और", "में", "का", "के", "से", "को", "नहीं", "एक", "यह", "पर", "हो", "तो") }
            val germanCount = words.count { it in setOf("der", "die", "das", "und", "sein", "in", "ein", "zu", "haben", "ich", "werden", "sie", "von", "nicht", "mit", "es") }
            val frenchCount = words.count { it in setOf("le", "la", "les", "un", "une", "des", "de", "et", "à", "en", "que", "pour", "dans", "ce", "il", "qui", "ne", "sur", "se") }

            // Require a minimum threshold to avoid false positives on short names
            val maxCount = listOf(englishCount, spanishCount, tagalogCount, indonesianCount, malayCount, khmerCount, portugueseCount, arabicCount, hindiCount, germanCount, frenchCount).maxOrNull() ?: 0

            if (maxCount >= 2) {
                if (khmerCount == maxCount) language = "Khmer"
                else if (arabicCount == maxCount) language = "Arabic"
                else if (hindiCount == maxCount) language = "Hindi"
                else if (indonesianCount == maxCount && indonesianCount > malayCount) language = "Indonesian"
                else if (malayCount == maxCount) language = "Malay"
                else if (tagalogCount == maxCount) language = "Filipino"
                else if (portugueseCount == maxCount) language = "Portuguese"
                else if (germanCount == maxCount) language = "German"
                else if (frenchCount == maxCount) language = "French"
                else if (spanishCount == maxCount) language = "Spanish"
                else if (englishCount == maxCount) language = "English"
            }
        }

        val snippetText = if (finalSnippet.length > 120) {
            val cut = finalSnippet.lastIndexOf(' ', 120)
            if (cut > 0) finalSnippet.substring(0, cut) + "..." else finalSnippet.substring(0, 120) + "..."
        } else finalSnippet

        return SubtitleResult(snippetText, language)
    }
}