package com.omymaxz.download

import android.net.Uri
import android.util.Log
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID
import java.util.regex.Pattern

@Entity(tableName = "user_scripts")
data class UserScript(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val script: String,
    var isEnabled: Boolean = true,
    /** Legacy primary include pattern. Retained for database/backup compatibility. */
    val targetUrl: String,
    val createdAt: Long = System.currentTimeMillis(),
    val runAt: RunAt = RunAt.DOCUMENT_END,
    val grants: List<String> = emptyList(),
    val requires: List<String> = emptyList(),
    /** Additional userscript metadata. Defaults keep existing Room rows readable. */
    val matches: List<String> = emptyList(),
    val excludes: List<String> = emptyList()
) {
    enum class RunAt {
        DOCUMENT_START, DOCUMENT_END, DOCUMENT_IDLE
    }

    companion object {
        const val MAX_SCRIPT_SIZE_BYTES: Long = 600_000
        private const val TAG = "UserScript"
        private val METADATA_PATTERN = Pattern.compile(
            "(?m)^[ \\t]*(?://[ \\t]*==UserScript==|/\\*+[ \\t]*==UserScript==)[\\s\\S]*?^[ \\t]*(?://[ \\t]*==/UserScript==|\\*+/[ \\t]*==/UserScript==)[ \\t]*$"
        )
    }

    fun shouldRunOnUrl(url: String): Boolean {
        if (!isEnabled || url.isBlank()) return false
        val includePatterns = (matches.ifEmpty { listOf(targetUrl) })
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (includePatterns.isEmpty() || includePatterns.none { matchesPattern(url, it) }) return false
        if (excludes.any { it.isNotBlank() && matchesPattern(url, it.trim()) }) return false
        return true
    }

    /**
     * Supports common userscript match patterns (scheme/host/path wildcards) and
     * the app's historical simple wildcard patterns. Unsupported match syntax fails
     * closed rather than unexpectedly running a script on unrelated pages.
     */
    internal fun matchesPattern(url: String, pattern: String): Boolean {
        if (pattern == "*") return true
        if (!pattern.contains("://")) {
            // Preserve the existing app's URL-substring behavior for legacy entries.
            return legacyWildcardMatch(url, pattern)
        }
        return try {
            val target = Uri.parse(url)
            val parts = pattern.split("://", limit = 2)
            if (parts.size != 2) return false
            val requestedScheme = parts[0].lowercase()
            val rest = parts[1]
            val slash = rest.indexOf('/')
            val hostPattern = if (slash >= 0) rest.substring(0, slash) else rest
            val pathPattern = if (slash >= 0) rest.substring(slash) else "/*"
            val actualScheme = target.scheme?.lowercase().orEmpty()
            val actualHost = target.host?.lowercase().orEmpty()
            if (actualHost.isEmpty()) return false
            if (requestedScheme != "*" && requestedScheme != actualScheme &&
                !(requestedScheme == "http*" && actualScheme in setOf("http", "https"))) return false

            val hostOk = when {
                hostPattern == "*" -> true
                hostPattern.startsWith("*.") -> {
                    val suffix = hostPattern.removePrefix("*").lowercase()
                    actualHost == suffix.removePrefix(".") || actualHost.endsWith(suffix)
                }
                hostPattern.contains("*") -> wildcardRegex(hostPattern.lowercase()).matches(actualHost)
                else -> actualHost.equals(hostPattern, ignoreCase = true)
            }
            if (!hostOk) return false
            val pathAndQuery = buildString {
                append(target.path.ifEmpty { "/" })
                target.encodedQuery?.let { append("?").append(it) }
            }
            wildcardRegex(pathPattern).matches(pathAndQuery)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid userscript URL pattern ignored")
            false
        }
    }

    private fun legacyWildcardMatch(url: String, pattern: String): Boolean {
        return when {
            pattern == "*" -> true
            pattern.startsWith("*") && pattern.endsWith("*") ->
                url.contains(pattern.removeSurrounding("*"), ignoreCase = true)
            pattern.startsWith("*") -> url.endsWith(pattern.removePrefix("*"), ignoreCase = true)
            pattern.endsWith("*") -> url.startsWith(pattern.removeSuffix("*"), ignoreCase = true)
            else -> url.contains(pattern, ignoreCase = true)
        }
    }

    private fun wildcardRegex(pattern: String): Regex =
        Regex("^" + pattern.split("*").joinToString(".*") { Regex.escape(it) } + "$", RegexOption.IGNORE_CASE)

    fun isValid(): Boolean =
        name.isNotBlank() && script.isNotBlank() &&
            script.toByteArray(Charsets.UTF_8).size <= MAX_SCRIPT_SIZE_BYTES &&
            (matches.ifEmpty { listOf(targetUrl) }).any { it.isNotBlank() }

    fun parseMetadata(): UserScript {
        val matcher = METADATA_PATTERN.matcher(script)
        if (!matcher.find()) return this
        val metadataBlock = matcher.group()
        var newName = name
        val newGrants = linkedSetOf<String>()
        val newRequires = linkedSetOf<String>()
        val newMatches = mutableListOf<String>()
        val newExcludes = mutableListOf<String>()
        var newRunAt = runAt
        for (line in metadataBlock.split("\\n")) {
            val trimmed = line.trim()
            val directive = trimmed
                .removePrefix("//")
                .removePrefix("/*")
                .removeSuffix("*/")
                .removePrefix("*")
                .trim()
            if (directive.isBlank() || directive.startsWith("==")) continue
            val key = directive.substringBefore(Regex("\\s")).trim()
            val value = directive.removePrefix(key).trim()
            when (key) {
                "@name" -> if (value.isNotBlank()) newName = value
                "@match", "@include" -> if (value.isNotBlank()) newMatches.add(value)
                "@exclude" -> if (value.isNotBlank()) newExcludes.add(value)
                "@grant" -> if (value.isNotBlank()) newGrants.add(value)
                "@require" -> if (value.isNotBlank()) newRequires.add(value)
                "@run-at" -> newRunAt = when (value) {
                    "document-start" -> RunAt.DOCUMENT_START
                    "document-end" -> RunAt.DOCUMENT_END
                    "document-idle" -> RunAt.DOCUMENT_IDLE
                    else -> RunAt.DOCUMENT_END
                }
            }
        }
        val effectiveMatches = newMatches.distinct().ifEmpty { matches }
        val effectiveExcludes = newExcludes.distinct().ifEmpty { excludes }
        return copy(
            name = newName,
            grants = newGrants.toList().ifEmpty { grants },
            requires = newRequires.toList().ifEmpty { requires },
            runAt = newRunAt,
            matches = effectiveMatches,
            excludes = effectiveExcludes
        )
    }
}
