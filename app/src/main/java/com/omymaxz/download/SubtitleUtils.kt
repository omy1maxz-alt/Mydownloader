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

    private val languageLabels = mapOf(
        "ar" to "Arabic", "bg" to "Bulgarian", "bn" to "Bengali", "ca" to "Catalan",
        "cs" to "Czech", "da" to "Danish", "de" to "German", "el" to "Greek",
        "en" to "English", "es" to "Spanish", "et" to "Estonian", "fa" to "Persian",
        "fi" to "Finnish", "fil" to "Filipino", "fr" to "French", "he" to "Hebrew",
        "hi" to "Hindi", "hr" to "Croatian", "hu" to "Hungarian", "id" to "Indonesian",
        "is" to "Icelandic", "it" to "Italian", "ja" to "Japanese", "jv" to "Javanese",
        "km" to "Khmer", "ko" to "Korean", "lt" to "Lithuanian", "lv" to "Latvian",
        "ms" to "Malay", "my" to "Burmese", "nl" to "Dutch", "no" to "Norwegian",
        "pl" to "Polish", "pt" to "Portuguese", "ro" to "Romanian", "ru" to "Russian",
        "sk" to "Slovak", "sl" to "Slovenian", "sr" to "Serbian", "sv" to "Swedish",
        "ta" to "Tamil", "te" to "Telugu", "th" to "Thai", "tl" to "Filipino",
        "tr" to "Turkish", "uk" to "Ukrainian", "ur" to "Urdu", "vi" to "Vietnamese",
        "zh" to "Chinese", "yue" to "Cantonese", "und" to "Unknown language"
    )
    private val languageAliases = languageLabels.entries.flatMap { (code, label) ->
        listOf(code to code, label.lowercase() to code)
    }.toMap() + mapOf("eng" to "en", "spa" to "es", "fra" to "fr", "fre" to "fr", "deu" to "de", "ger" to "de", "tha" to "th", "ind" to "id", "zho" to "zh", "chi" to "zh", "kor" to "ko", "jpn" to "ja", "ara" to "ar", "hin" to "hi", "por" to "pt", "vie" to "vi")

    fun normalizeLanguageCode(value: String?): String? {
        val raw = value?.trim()?.replace('_', '-')?.lowercase(java.util.Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val base = raw.substringBefore('-')
        val code = languageAliases[raw] ?: languageAliases[base] ?: return null
        val suffix = raw.substringAfter('-', "")
        return if (suffix.isEmpty()) code else if (suffix.matches(Regex("[a-z0-9]{2,8}(-[a-z0-9]{2,8})*"))) "$code-$suffix" else code
    }

    fun languageDisplayName(value: String?): String? {
        val code = normalizeLanguageCode(value) ?: return null
        return languageLabels[code.substringBefore('-')] ?: code
    }

    /** Ignore URL fragments only; preserve every query parameter, token and track selector. */
    fun canonicalMediaUrl(rawUrl: String): String {
        val raw = rawUrl.trim()
        return runCatching { java.net.URI(raw).normalize().toASCIIString().substringBefore('#') }
            .getOrElse { Uri.parse(raw).buildUpon().fragment(null).build().toString() }
    }

    fun extractTrustedLanguageFromUrl(rawUrl: String): String? = runCatching {
        val uri = Uri.parse(rawUrl)
        val host = uri.host?.lowercase(java.util.Locale.ROOT).orEmpty()
        val youtubeHost = host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") ||
            host == "googlevideo.com" || host.endsWith(".googlevideo.com")
        if (!youtubeHost || !uri.path.orEmpty().contains("/api/timedtext", ignoreCase = true)) return null
        // tlang is the requested translation output language; lang is the source track language.
        normalizeLanguageCode(uri.getQueryParameter("tlang") ?: uri.getQueryParameter("lang"))
    }.getOrNull()

    fun originalSubtitleFilename(rawUrl: String): String? = runCatching {
        val path = java.net.URI(rawUrl).path ?: return null
        val name = java.net.URLDecoder.decode(path.substringAfterLast('/'), "UTF-8").trim()
        name.takeIf { it.length in 1..180 && it !in setOf(".", "..") &&
            it.substringAfterLast('.', "").lowercase() in setOf("vtt", "srt", "ass", "ssa", "ttml", "dfxp", "srv1", "srv2", "srv3") }
    }.getOrNull()

    fun isValidSubtitleContent(content: String): Boolean {
        val text = content.trimStart().removePrefix("\uFEFF").trimStart()
        if (text.isBlank() || text.startsWith("<!DOCTYPE", true) || text.startsWith("<html", true) ||
            text.contains("<script", true) || text.contains("<iframe", true)) return false
        val hasTiming = Regex("""(?m)^\s*\d{2,}:\d{2}(?::\d{2})?[.,]\d{3}\s*-->\s*\d{2,}:\d{2}(?::\d{2})?[.,]\d{3}""").containsMatchIn(text)
        val ttmlCue = Regex("""(?is)<(?:tt|transcript)\b.*<(?:p\b[^>]*(?:begin|start|t)=|text\b[^>]*start=).*?</(?:tt|transcript)>""").containsMatchIn(text)
        return hasTiming || ttmlCue
    }

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
        return out.distinctBy { canonicalMediaUrl(it.uri) + "|" + it.language + "|" + it.name }
    }

    private fun parseOneTrack(tag: String, masterBaseUrl: String): SubtitleTrack? {
        val uri = readAttr(tag, "URI") ?: return null
        val language = normalizeLanguageCode(readAttr(tag, "LANGUAGE")) ?: "und"
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

    /**
     * Resolves every URI in an HLS subtitle playlist. For a master playlist, follow
     * the first listed variant; for a media playlist, return every segment in order.
     */
    fun parseHlsSubtitlePlaylistUris(playlist: String, playlistUrl: String): List<String> {
        val lines = playlist.replace("\r\n", "\n").replace('\r', '\n').lines()
        val isMasterPlaylist = lines.any { it.trimStart().startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }
        val rawUris = mutableListOf<String>()

        if (isMasterPlaylist) {
            var waitingForVariantUri = false
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                    waitingForVariantUri = true
                    continue
                }
                if (line.startsWith("#")) continue
                if (waitingForVariantUri) {
                    rawUris.add(line)
                    break
                }
            }
        } else {
            for (raw in lines) {
                val line = raw.trim()
                if (line.isNotEmpty() && !line.startsWith("#")) rawUris.add(line)
            }
        }

        return rawUris.mapNotNull { raw ->
            runCatching {
                java.net.URI(playlistUrl).resolve(java.net.URI(raw)).toString()
            }.getOrNull()
        }.take(2000)
    }

    /**
     * Joins complete WebVTT HLS segments into one standalone VTT document.
     * Segment headers and timestamp-map metadata belong to each segment, not the
     * combined file; cue bodies and cue identifiers are retained.
     */
    fun mergeHlsSubtitleSegments(segments: List<String>): String {
        val cueBodies = segments.mapNotNull { raw ->
            var text = raw.removePrefix("\uFEFF")
                .replace("\r\n", "\n")
                .replace('\r', '\n')

            // HLS WebVTT timestamps are local to a segment. Map them onto the media
            // timeline before removing X-TIMESTAMP-MAP so later segments do not restart
            // at zero or overlap the opening captions.
            val timestampMap = Regex(
                """(?i)X-TIMESTAMP-MAP\s*=\s*LOCAL:([^,]+),\s*MPEGTS:(\d+)"""
            ).find(text)
            val offsetMillis = timestampMap?.let { match ->
                val localMillis = parseWebVttTimestampMillis(match.groupValues[1])
                val mpegTicks = match.groupValues[2].toLongOrNull()
                if (localMillis != null && mpegTicks != null) mpegTicks / 90L - localMillis else null
            }

            val lines = text.lines().toMutableList()
            val firstContentLine = lines.indexOfFirst { it.isNotBlank() }
            if (firstContentLine >= 0 &&
                lines[firstContentLine].trimStart().startsWith("WEBVTT", ignoreCase = true)
            ) {
                lines.removeAt(firstContentLine)
            }
            text = lines.filterNot {
                it.trimStart().startsWith("X-TIMESTAMP-MAP=", ignoreCase = true)
            }.joinToString("\n").trim()

            if (offsetMillis != null) shiftWebVttCueTimes(text, offsetMillis) else text
        }.filter { it.isNotBlank() }

        if (cueBodies.isEmpty()) return ""
        return normalizeSpeakerMarkersInVtt("WEBVTT\n\n" + cueBodies.joinToString("\n\n") + "\n")
    }

    private fun parseWebVttTimestampMillis(value: String): Long? {
        val parts = value.trim().split(':')
        if (parts.size !in 2..3) return null
        val secondsPart = parts.last().split('.', limit = 2)
        val seconds = secondsPart[0].toLongOrNull() ?: return null
        val millis = secondsPart.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
    }

    private fun formatWebVttTimestampMillis(value: Long): String {
        val safe = value.coerceAtLeast(0)
        return "%02d:%02d:%02d.%03d".format(
            safe / 3_600_000, (safe / 60_000) % 60, (safe / 1_000) % 60, safe % 1_000
        )
    }

    private fun shiftWebVttCueTimes(content: String, offsetMillis: Long): String {
        val cueTiming = Regex(
            """(?m)^(\s*)(\d{2,}:\d{2}:\d{2}\.\d{3}|\d{2}:\d{2}\.\d{3})(\s*-->\s*)(\d{2,}:\d{2}:\d{2}\.\d{3}|\d{2}:\d{2}\.\d{3})(.*)$"""
        )
        return cueTiming.replace(content) { match ->
            val start = parseWebVttTimestampMillis(match.groupValues[2])
            val end = parseWebVttTimestampMillis(match.groupValues[4])
            if (start == null || end == null) match.value
            else match.groupValues[1] + formatWebVttTimestampMillis(start + offsetMillis) +
                match.groupValues[3] + formatWebVttTimestampMillis(end + offsetMillis) + match.groupValues[5]
        }
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
            val language = normalizeLanguageCode(
                Regex("""xml:lang=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(content)?.groupValues?.getOrNull(1)
            )
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
            if (langMatch.find()) {
                language = normalizeLanguageCode(langMatch.group(1))
                continue
            }

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

        // No bundled detector reliably classifies short snippets; leave unknown language unset.

        val snippetText = if (finalSnippet.length > 120) {
            val cut = finalSnippet.lastIndexOf(' ', 120)
            if (cut > 0) finalSnippet.substring(0, cut) + "..." else finalSnippet.substring(0, 120) + "..."
        } else finalSnippet

        return SubtitleResult(snippetText, language)
    }
}