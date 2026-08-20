package com.omymaxz.download

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class LogcatViewerActivity : AppCompatActivity() {



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logcat_viewer)

        val tvLogs = findViewById<TextView>(R.id.tvLogs)
        val etFilter = findViewById<EditText>(R.id.etFilter)
        val btnRefresh = findViewById<Button>(R.id.btnRefresh)
        val btnClear = findViewById<Button>(R.id.btnClear)
        val btnCopy = findViewById<Button>(R.id.btnCopy)

        btnRefresh.setOnClickListener {
            loadLogs(tvLogs, etFilter.text.toString())
        }

        btnClear.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                Runtime.getRuntime().exec("logcat -c")
                withContext(Dispatchers.Main) {
                    tvLogs.text = ""
                }
            }
        }

        btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Logcat", tvLogs.text.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        loadLogs(tvLogs, "")
    }

    private fun loadLogs(tvLogs: TextView, filter: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val pid = Process.myPid().toString()
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val logOutput = StringBuilder()

            reader.forEachLine { line ->
                if (line.contains(pid)) {
                    if (filter.isEmpty() || line.contains(filter, ignoreCase = true)) {
                        logOutput.append(line).append("\n")
                    }
                }
            }

            withContext(Dispatchers.Main) {
                tvLogs.text = logOutput.toString()
            }
        }
    }
}
