// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.logic

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class ResearchItem(
    val title: String,
    val content: String,
    val timestamp: Long,
    val pdfFileName: String? = null
)

object ResearchHistoryEngine {
    private const val TAG = "ResearchHistoryEngine"
    private const val PREFS_NAME = "jago_research_history"
    private const val KEY_HISTORY = "research_list"
    private const val KEY_N8N_URL = "n8n_server_url"
    private const val KEY_TOPIC = "research_topic"
    private const val KEY_INTERVAL = "research_interval"
    private const val MAX_HISTORY_SIZE = 100
    private val gson = Gson()

    fun getHistory(context: Context): List<ResearchItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ResearchItem>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load research history", e)
            emptyList()
        }
    }

    fun addResearchItem(context: Context, item: ResearchItem) {
        val current = getHistory(context).toMutableList()
        current.add(0, item)
        if (current.size > MAX_HISTORY_SIZE) {
            val toRemove = current.subList(MAX_HISTORY_SIZE, current.size)
            // Clean up PDF files for trimmed history items
            val dir = File(context.filesDir, "research")
            toRemove.forEach { oldItem ->
                oldItem.pdfFileName?.let { fileName ->
                    val file = File(dir, fileName)
                    if (file.exists()) file.delete()
                }
            }
            saveHistory(context, current.subList(0, MAX_HISTORY_SIZE))
        } else {
            saveHistory(context, current)
        }
        Log.d(TAG, "Added research item: '${item.title}'")
    }

    fun saveResearchPdf(context: Context, title: String, pdfBytes: ByteArray): ResearchItem {
        val dir = File(context.filesDir, "research")
        if (!dir.exists()) dir.mkdirs()

        val fileName = "research_${System.currentTimeMillis()}.pdf"
        val pdfFile = File(dir, fileName)
        pdfFile.writeBytes(pdfBytes)

        val item = ResearchItem(
            title = title,
            content = "PDF Report generated for $title",
            timestamp = System.currentTimeMillis(),
            pdfFileName = fileName
        )
        addResearchItem(context, item)
        return item
    }

    fun clearHistory(context: Context) {
        val dir = File(context.filesDir, "research")
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        saveHistory(context, emptyList())
        Log.d(TAG, "Cleared research history and PDF files")
    }

    private fun saveHistory(context: Context, history: List<ResearchItem>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_HISTORY, gson.toJson(history)).apply()
    }

    // Settings helpers
    @Suppress("UNUSED_PARAMETER")
    fun getN8nServerUrl(context: Context): String {
        return "https://lazydracko.app.n8n.cloud"
    }

    @Suppress("UNUSED_PARAMETER")
    fun saveN8nServerUrl(context: Context, url: String) {
        // Hardcoded server URL, saving is disabled
    }

    fun getResearchTopic(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_TOPIC, "") ?: ""
    }

    fun saveResearchTopic(context: Context, topic: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TOPIC, topic.trim()).apply()
    }

    fun getResearchInterval(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_INTERVAL, 1)
    }

    fun saveResearchInterval(context: Context, interval: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_INTERVAL, maxOf(1, interval)).apply()
    }
}
