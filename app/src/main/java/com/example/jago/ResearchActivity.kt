// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.jago.logic.ResearchHistoryEngine
import com.example.jago.logic.ResearchItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.util.Date
import java.util.Locale

class ResearchActivity : AppCompatActivity() {

    private lateinit var etResearchTopic: EditText
    private lateinit var etResearchInterval: EditText
    private lateinit var btnSaveSyncSettings: Button
    private lateinit var btnClearResearch: Button
    private lateinit var tvLocalIp: TextView
    private lateinit var layoutEmptyState: View
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ResearchAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_research)

        etResearchTopic = findViewById(R.id.etResearchTopic)
        etResearchInterval = findViewById(R.id.etResearchInterval)
        btnSaveSyncSettings = findViewById(R.id.btnSaveSyncSettings)
        btnClearResearch = findViewById(R.id.btnClearResearch)
        tvLocalIp = findViewById(R.id.tvLocalIp)
        layoutEmptyState = findViewById(R.id.layoutEmptyState)
        recyclerView = findViewById(R.id.recyclerViewResearch)

        adapter = ResearchAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Load settings
        etResearchTopic.setText(ResearchHistoryEngine.getResearchTopic(this))
        etResearchInterval.setText(ResearchHistoryEngine.getResearchInterval(this).toString())

        // Save & Sync Settings
        btnSaveSyncSettings.setOnClickListener {
            val urlStr = ResearchHistoryEngine.getN8nServerUrl(this)
            val topicStr = etResearchTopic.text.toString().trim()
            val intervalStr = etResearchInterval.text.toString().trim()
            val intervalInt = intervalStr.toIntOrNull() ?: 1

            if (topicStr.isEmpty()) {
                Toast.makeText(this, "Research Topic cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (intervalInt < 1) {
                Toast.makeText(this, "Interval must be at least 1 day", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Save locally
            ResearchHistoryEngine.saveResearchTopic(this, topicStr)
            ResearchHistoryEngine.saveResearchInterval(this, intervalInt)

            // Sync with n8n server asynchronously
            syncSettingsToN8n(urlStr, topicStr, intervalInt)
        }

        btnClearResearch.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Clear History")
                .setMessage("Are you sure you want to delete all research reports?")
                .setPositiveButton("Clear") { _, _ ->
                    ResearchHistoryEngine.clearHistory(this)
                    loadHistory()
                    Toast.makeText(this, "Research reports cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        loadHistory()
        updateLocalIpDisplay()

        // Handle auto-open if requested via Intent
        val openTimestamp = intent.getLongExtra("open_item_timestamp", -1L)
        if (openTimestamp != -1L) {
            val items = ResearchHistoryEngine.getHistory(this)
            val matchingItem = items.find { it.timestamp == openTimestamp }
            if (matchingItem != null) {
                openPdf(matchingItem)
            }
        }
    }

    private fun loadHistory() {
        val history = ResearchHistoryEngine.getHistory(this)
        if (history.isEmpty()) {
            layoutEmptyState.visibility = View.VISIBLE
            btnClearResearch.visibility = View.GONE
        } else {
            layoutEmptyState.visibility = View.GONE
            btnClearResearch.visibility = View.VISIBLE
        }
        adapter.updateList(history)
    }

    private fun updateLocalIpDisplay() {
        tvLocalIp.text = "Assistant Connection: Active"
    }

    private fun syncSettingsToN8n(serverUrl: String, topic: String, intervalDays: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            var success = false
            var errorMsg = ""
            try {
                val cleanUrl = serverUrl.trim().removeSuffix("/")
                val url = URL("$cleanUrl/webhook/research-settings")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")

                val payload = JSONObject().apply {
                    put("topic", topic)
                    put("intervalDays", intervalDays)
                }.toString()

                conn.outputStream.use { os ->
                    os.write(payload.toByteArray(Charsets.UTF_8))
                }

                val code = conn.responseCode
                if (code == 200) {
                    success = true
                } else {
                    errorMsg = "Server returned code $code"
                }
            } catch (e: Exception) {
                Log.e("ResearchActivity", "Failed to sync settings with n8n", e)
                errorMsg = e.message ?: "Unknown network error"
            }

            withContext(Dispatchers.Main) {
                updateLocalIpDisplay()
                if (success) {
                    Toast.makeText(this@ResearchActivity, "Settings synced successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    androidx.appcompat.app.AlertDialog.Builder(this@ResearchActivity)
                        .setTitle("Sync Failed")
                        .setMessage("Settings saved locally, but failed to sync to the server: $errorMsg\n\nPlease ensure you have an active internet connection.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun openPdf(item: ResearchItem) {
        val pdfFileName = item.pdfFileName ?: return
        val dir = File(filesDir, "research")
        val file = File(dir, pdfFileName)
        if (!file.exists()) {
            Toast.makeText(this, "PDF file not found", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                this,
                "com.example.jago.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("ResearchActivity", "Failed to launch PDF viewer", e)
            Toast.makeText(this, "No PDF viewer app found on device", Toast.LENGTH_LONG).show()
        }
    }

    private inner class ResearchAdapter : RecyclerView.Adapter<ResearchAdapter.ViewHolder>() {
        private var items: List<ResearchItem> = emptyList()

        fun updateList(newItems: List<ResearchItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtTitle: TextView = view.findViewById(R.id.txtResearchTitle)
            val txtTimestamp: TextView = view.findViewById(R.id.txtResearchTimestamp)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_research, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.txtTitle.text = item.title

            val date = Date(item.timestamp)
            val dateStr = android.text.format.DateFormat.format("MMM dd, yyyy HH:mm:ss", date).toString()
            holder.txtTimestamp.text = dateStr

            holder.itemView.setOnClickListener {
                openPdf(item)
            }
        }

        override fun getItemCount() = items.size
    }
}
