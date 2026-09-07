import re

with open('app/src/main/java/com/omymaxz/download/LogcatViewerActivity.kt', 'r') as f:
    content = f.read()

target = """    private fun loadLogs(tvLogs: TextView, filter: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val pid = Process.myPid().toString()
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val logOutput = StringBuilder()

            val exportLogFile = java.io.File(filesDir, "export_logs.txt")
            if (exportLogFile.exists()) {
                logOutput.append("=== EXPORT LOGS ===\\n")
                val exportLogs = exportLogFile.readText()
                if (filter.isEmpty()) {
                    logOutput.append(exportLogs)
                } else {
                    exportLogs.lines().forEach { line ->
                        if (line.contains(filter, ignoreCase = true)) {
                            logOutput.append(line).append("\\n")
                        }
                    }
                }
                logOutput.append("\\n=== SYSTEM LOGCAT ===\\n")
            }

            reader.forEachLine { line ->
                if (line.contains(pid) || line.contains("MediaCodec") || line.contains("ExoPlayer") || line.contains("Transformer")) {
                    if (filter.isEmpty() || line.contains(filter, ignoreCase = true)) {
                        logOutput.append(line).append("\\n")
                    }
                }
            }

            withContext(Dispatchers.Main) {
                tvLogs.text = logOutput.toString()
            }
        }
    }"""

# A more robust regex replacement since there might be windows line endings etc
match_str = r'    private fun loadLogs\(tvLogs: TextView, filter: String\) \{.*?\n    \}'
content = re.sub(match_str, target, content, flags=re.DOTALL)

with open('app/src/main/java/com/omymaxz/download/LogcatViewerActivity.kt', 'w') as f:
    f.write(content)
print("Patched LogcatViewerActivity via regex")
