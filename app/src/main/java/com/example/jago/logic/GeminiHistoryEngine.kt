// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.logic

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class GeminiHistoryItem(
    val query: String,
    val timestamp: Long,
    val rawResponse: String
)

object GeminiHistoryEngine {
    private const val TAG = "GeminiHistoryEngine"
    private const val PREFS_NAME = "jago_gemini_history"
    private const val KEY_HISTORY = "history_list"
    private const val MAX_HISTORY_SIZE = 50
    private val gson = Gson()

    fun getHistory(context: Context): List<GeminiHistoryItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<GeminiHistoryItem>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load history", e)
            emptyList()
        }
    }

    fun addHistoryItem(context: Context, query: String, rawResponse: String) {
        val current = getHistory(context).toMutableList()
        // Add new item at the beginning of the list (newest first)
        current.add(0, GeminiHistoryItem(query, System.currentTimeMillis(), rawResponse))
        
        // Trim history if it exceeds max size
        if (current.size > MAX_HISTORY_SIZE) {
            val trimmed = current.subList(0, MAX_HISTORY_SIZE)
            saveHistory(context, trimmed)
        } else {
            saveHistory(context, current)
        }
        Log.d(TAG, "Added history item for query: '$query'")
    }

    fun clearHistory(context: Context) {
        saveHistory(context, emptyList())
        Log.d(TAG, "Cleared Gemini history")
    }

    private fun saveHistory(context: Context, history: List<GeminiHistoryItem>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_HISTORY, gson.toJson(history)).apply()
    }
}
