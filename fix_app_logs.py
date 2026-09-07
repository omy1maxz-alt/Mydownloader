import re
with open('app/src/main/java/com/omymaxz/download/MainActivity.kt', 'r') as f:
    content = f.read()

target = """    private fun showAppLogsDialog() {
        val logContent = StringBuilder()

        // Fetch Export Logs
        val exportLogFile = java.io.File(filesDir, "export_logs.txt")
        if (exportLogFile.exists()) {
            logContent.append("=== EXPORT LOGS ===\\n")
            logContent.append(exportLogFile.readText())
            logContent.append("\\n\\n")
        }

        // Fetch System Logcat for the app
        try {
            val process = Runtime.getRuntime().exec("logcat -d -v threadtime -t 500")
            val bufferedReader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            logContent.append("=== SYSTEM LOGCAT ===\\n")
            bufferedReader.useLines { lines ->
                lines.forEach { line ->
                    if (line.contains(packageName) || line.contains("HlsExport") || line.contains("MainActivity") || line.contains("MediaCodec")) {
                        logContent.append(line).append("\\n")
                    }
                }
            }
        } catch (e: Exception) {
            logContent.append("Could not fetch logcat: ${e.message}\\n")
        }

        val finalLogs = if (logContent.isEmpty()) "No logs found." else logContent.toString()

        val scrollView = android.widget.ScrollView(this)
        val textView = android.widget.TextView(this).apply {
            text = finalLogs
            setPadding(32, 32, 32, 32)
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)

        createThemedDialogBuilder(this)
            .setTitle("App Logs")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .setNegativeButton("Copy All") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("App Logs", finalLogs)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Clear Export Logs") { _, _ ->
                if (exportLogFile.exists()) exportLogFile.delete()
                Toast.makeText(this, "Export logs cleared", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showExportLogsDialog() {"""

# We need to find if `showAppLogsDialog` exists, and if not, add it near `showExportLogsDialog`
# Looking at line 4210, `5 -> {` something is there.
