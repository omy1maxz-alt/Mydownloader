package com.omymaxz.download

import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.TextView
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
        val btnRefresh = findViewById<Button>(R.id.btnRefresh)
        val btnClear = findViewById<Button>(R.id.btnClear)

        btnRefresh.setOnClickListener {
            loadLogs(tvLogs)
        }

        btnClear.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                Runtime.getRuntime().exec("logcat -c")
                withContext(Dispatchers.Main) {
                    tvLogs.text = ""
                }
            }
        }

        loadLogs(tvLogs)
    }

    private fun loadLogs(tvLogs: TextView) {
        CoroutineScope(Dispatchers.IO).launch {
            val pid = Process.myPid().toString()
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val logOutput = StringBuilder()

            reader.forEachLine { line ->
                if (line.contains(pid)) {
                    logOutput.append(line).append("\n")
                }
            }

            withContext(Dispatchers.Main) {
                tvLogs.text = logOutput.toString()
            }
        }
    }
}
