package com.omymaxz.download

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID
import java.util.regex.Pattern // Add this import for Pattern

@Entity(tableName = "user_scripts")
data class UserScript(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val script: String,
    var isEnabled: Boolean = true,
    val targetUrl: String,
    val createdAt: Long = System.currentTimeMillis(),
    // Add metadata fields
    val runAt: RunAt = RunAt.DOCUMENT_END, // Default to document-end
    val grants: List<String> = emptyList(),
    val requires: List<String> = emptyList()
) {
    enum class RunAt {
        DOCUMENT_START, DOCUMENT_END, DOCUMENT_IDLE
    }

    companion object {
        // Increased limit to 600KB (approx 600,000 characters)
        const val MAX_SCRIPT_SIZE_BYTES: Long = 600_000
        
        // Regex pattern to extract metadata block
        private val METADATA_PATTERN = Pattern.compile(
            "/\\*\\*?\\s*==UserScript==[\\s\\S]*?==/UserScript==\\s*\\*/", 
            Pattern.MULTILINE
        )
    }

    fun shouldRunOnUrl(url: String): Boolean {
        if (!isEnabled || targetUrl.isBlank()) return false

        // If targetUrl is "*", it should run on all sites.
        if (targetUrl == "*") return true

        return try {
            when {
                targetUrl.startsWith("*") && targetUrl.endsWith("*") -> {
                    val searchTerm = targetUrl.removeSurrounding("*")
                    url.contains(searchTerm, ignoreCase = true)
                }
                targetUrl.startsWith("*") -> {
                    val searchTerm = targetUrl.removePrefix("*")
                    url.endsWith(searchTerm, ignoreCase = true)
                }
                targetUrl.endsWith("*") -> {
                    val searchTerm = targetUrl.removeSuffix("*")
                    url.startsWith(searchTerm, ignoreCase = true)
                }
                else -> url.contains(targetUrl, ignoreCase = true)
            }
        } catch (e: Exception) {
            android.util.Log.e("UserScript", "Error matching URL pattern: ${e.message}")
            url.contains(targetUrl, ignoreCase = true)
        }
    }

    fun isValid(): Boolean {
        return name.isNotBlank() &&
                script.isNotBlank() &&
                script.toByteArray(Charsets.UTF_8).size < MAX_SCRIPT_SIZE_BYTES
    }
    
    // Parse metadata from the script content
    fun parseMetadata(): UserScript {
        val matcher = METADATA_PATTERN.matcher(script)
        if (!matcher.find()) return this // No metadata block found
        
        val metadataBlock = matcher.group()
        val lines = metadataBlock.split("\n")
        var newName = this.name
        val newGrants = mutableSetOf<String>()
        val newRequires = mutableSetOf<String>()
        var newRunAt = this.runAt
        
        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("@name ")) {
                newName = trimmedLine.substring("@name ".length).trim()
            } else if (trimmedLine.startsWith("@grant ")) {
                newGrants.add(trimmedLine.substring("@grant ".length).trim())
            } else if (trimmedLine.startsWith("@require ")) {
                newRequires.add(trimmedLine.substring("@require ".length).trim())
            } else if (trimmedLine.startsWith("@run-at ")) {
                newRunAt = when (trimmedLine.substring("@run-at ".length).trim()) {
                    "document-start" -> RunAt.DOCUMENT_START
                    "document-end" -> RunAt.DOCUMENT_END
                    "document-idle" -> RunAt.DOCUMENT_IDLE
                    else -> RunAt.DOCUMENT_END // Default
                }
            }
        }
        
        return this.copy(
            name = newName,
            grants = newGrants.toList(),
            requires = newRequires.toList(),
            runAt = newRunAt
        )
    }
}