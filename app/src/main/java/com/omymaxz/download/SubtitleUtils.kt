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
        val lower = trimmed.lowercase()

        // YouTube may return WebVTT, TTML, or its older <transcript><text start dur>
        // XML format even when the URL ends in .vtt. Do not save XML as if it were VTT:
        // players can show a few cues and then stop, or reject the track entirely.
        val isCaptionXml = lower.startsWith("<tt") ||
            lower.startsWith("<?xml") ||
            lower.startsWith("<transcript") ||
            Regex("""<(?:text|p)\b[^>]*(?:\bstart=|\bbegin=|\bt=)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(trimmed)

        if (!isCaptionXml) {
            if (!trimmed.startsWith("WEBVTT", ignoreCase = true)) return content
            return normalizeSpeakerMarkersInVtt(content)
        }

        fun timeToMillis(value: String, numericUnit: String): Long? {
            val time = value.trim()
            val clock = Regex("""^(\d{2,}):(\d{2}):(\d{2})(?:[.,](\d+))?$""").matchEntire(time)
            if (clock != null) {
                val (hh, mm, ss, fraction) = clock.destructured
                val millis = fraction.padEnd(3, '0').take(3).ifEmpty { "000" }.toLong()
                return (((hh.toLong() * 60 + mm.toLong()) * 60 + ss.toLong()) * 1000 + millis)
            }

            val withUnit = Regex("""^(\d+(?:\.\d+)?)(ms|s|m|h)$""", RegexOption.IGNORE_CASE)
                .matchEntire(time)
            val amount: Double
            val factor: Double
            if (withUnit != null) {
                amount = withUnit.groupValues[1].toDoubleOrNull() ?: return null
                factor = when (withUnit.groupValues[2].lowercase()) {
                    "ms" -> 1.0
                    "m" -> 60_000.0
                    "h" -> 3_600_000.0
                    else -> 1_000.0
                }
            } else {
                amount = time.toDoubleOrNull() ?: return null
                factor = if (numericUnit == "milliseconds") 1.0 else 1_000.0
            }
            val result = amount * factor
            if (!result.isFinite() || result < 0 || result > Long.MAX_VALUE.toDouble()) return null
            return result.toLong()
        }

        fun formatMillis(value: Long): String {
            val total = value.coerceAtLeast(0)
            return "%02d:%02d:%02d.%03d".format(
                total / 3_600_000, (total / 60_000) % 60,
                (total / 1_000) % 60, total % 1_000
            )
        }

        fun attr(attrs: String, name: String): String? =
            Regex("""\b${Regex.escape(name)}=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(1)

        val cues = Regex(
            """<(p|text)\b([^>]*)>(.*?)</\1\s*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(trimmed).mapNotNull { match ->
            val attrs = match.groupValues[2]
            val begin = attr(attrs, "begin")
            val startSeconds = attr(attrs, "start")
            val startTicks = attr(attrs, "t")
            val startValue = begin ?: startSeconds ?: startTicks
                ?: return@mapNotNull null
            val startMillis = timeToMillis(
                startValue,
                if (startTicks != null && begin == null && startSeconds == null) "milliseconds" else "seconds"
            ) ?: return@mapNotNull null

            val explicitEnd = attr(attrs, "end")
            val durationSeconds = attr(attrs, "dur")
            val durationTicks = attr(attrs, "d")
            val endMillis = when {
                explicitEnd != null -> timeToMillis(explicitEnd, "seconds")
                durationSeconds != null -> timeToMillis(durationSeconds, "seconds")?.let { startMillis + it }
                durationTicks != null -> timeToMillis(durationTicks, "milliseconds")?.let { startMillis + it }
                else -> null
            } ?: return@mapNotNull null
            if (endMillis <= startMillis) return@mapNotNull null

            val text = match.groupValues[3]
                .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("""<[^>]+>"""), "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&apos;", "'").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&nbsp;", " ").replace("&#160;", " ")
                .trim()
            if (text.isBlank()) null
            else "${formatMillis(startMillis)} --> ${formatMillis(endMillis)}\n$text"
        }.toList()

        if (cues.isEmpty()) {
            // Never prepend a WEBVTT header to unconverted XML. That creates a corrupt
            // subtitle file that may appear to work only until the player hits bad data.
            return content
        }
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