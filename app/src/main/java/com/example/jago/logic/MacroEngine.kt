// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.logic

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class MacroStep(
    val actionType: String, // "LAUNCH_APP", "CLICK", "TEXT_ENTRY", "BACK", "WAIT"
    val packageName: String?,
    val targetText: String? = null,
    val targetId: String? = null,
    val contentDescription: String? = null, // New field for robust fallback matching
    val textToEnter: String? = null,
    val xPercent: Float? = null, // Store screen relative coords (0.0f - 1.0f)
    val yPercent: Float? = null,
    val delayMs: Long = 1200L
)

data class VoiceMacro(
    val voiceShortcut: String,
    val steps: List<MacroStep>,
    val embedding: List<Float>? = null,
    val template: String? = null
)

object MacroEngine {
    private const val TAG = "MacroEngine"
    private const val PREFS_NAME = "jago_macros"
    private const val KEY_MACROS = "macros_list"
    private val gson = Gson()

    fun getMacros(context: Context): List<VoiceMacro> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_MACROS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<VoiceMacro>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load macros", e)
            emptyList()
        }
    }

    fun saveMacros(context: Context, macros: List<VoiceMacro>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MACROS, gson.toJson(macros)).apply()
    }

    fun getMacro(context: Context, voiceShortcut: String): VoiceMacro? {
        val normalizedShortcut = voiceShortcut.trim().lowercase()
        return getMacros(context).find { it.voiceShortcut.trim().lowercase() == normalizedShortcut }
    }

    fun addMacro(context: Context, macro: VoiceMacro) {
        val current = getMacros(context).toMutableList()
        current.removeAll { it.voiceShortcut.trim().lowercase() == macro.voiceShortcut.trim().lowercase() }
        current.add(macro)
        saveMacros(context, current)
        Log.d(TAG, "Added macro: ${macro.voiceShortcut} with ${macro.steps.size} steps")
    }

    fun deleteMacro(context: Context, voiceShortcut: String) {
        val current = getMacros(context).toMutableList()
        current.removeAll { it.voiceShortcut.trim().lowercase() == voiceShortcut.trim().lowercase() }
        saveMacros(context, current)
        Log.d(TAG, "Deleted macro: $voiceShortcut")
    }
}
