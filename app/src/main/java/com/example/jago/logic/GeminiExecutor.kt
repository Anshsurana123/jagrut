// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.logic

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.jago.ui.AssistantUIBridge
import com.example.jago.ui.TelemetryEvent
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GeminiIntentPayload(
    val intent: String,              // human-readable description
    val action_type: String,         // "NATIVE_INTENT" | "REFLECTIVE_CALL" | "UNKNOWN" | "SHELL_COMMAND"
    val target_package: String?,
    val intent_action: String?,
    val intent_flags: List<String>?,
    val extras: Map<String, String>?,
    val reflective_class: String?,
    val reflective_method: String?,
    val reflective_args: List<String>?,
    val steps: List<MacroStep>? = null,
    val code: String? = null,         // code to execute on the phone
    val template: String? = null      // dynamic macro query template
) {
    companion object {
        fun fromJson(jsonStr: String): GeminiIntentPayload {
            val json = JSONObject(jsonStr)
            val intent = json.optString("intent", "")
            val action_type = json.optString("action_type", "UNKNOWN")
            val target_package = if (json.has("target_package") && !json.isNull("target_package")) json.getString("target_package") else null
            val intent_action = if (json.has("intent_action") && !json.isNull("intent_action")) json.getString("intent_action") else null
            
            val intent_flags = if (json.has("intent_flags") && !json.isNull("intent_flags")) {
                val arr = json.getJSONArray("intent_flags")
                List(arr.length()) { arr.getString(it) }
            } else null
            
            val extras = if (json.has("extras") && !json.isNull("extras")) {
                val obj = json.getJSONObject("extras")
                val map = mutableMapOf<String, String>()
                obj.keys().forEach { key ->
                    map[key] = obj.getString(key)
                }
                map
            } else null
            
            val reflective_class = if (json.has("reflective_class") && !json.isNull("reflective_class")) json.getString("reflective_class") else null
            val reflective_method = if (json.has("reflective_method") && !json.isNull("reflective_method")) json.getString("reflective_method") else null
            
            val reflective_args = if (json.has("reflective_args") && !json.isNull("reflective_args")) {
                val arr = json.getJSONArray("reflective_args")
                List(arr.length()) { arr.getString(it) }
            } else null

            val steps = if (json.has("steps") && !json.isNull("steps")) {
                val arr = json.getJSONArray("steps")
                List(arr.length()) { i ->
                    val obj = arr.getJSONObject(i)
                    MacroStep(
                        actionType = obj.optString("actionType", "CLICK"),
                        packageName = if (obj.has("packageName") && !obj.isNull("packageName")) obj.getString("packageName") else null,
                        targetText = if (obj.has("targetText") && !obj.isNull("targetText")) obj.getString("targetText") else null,
                        targetId = if (obj.has("targetId") && !obj.isNull("targetId")) obj.getString("targetId") else null,
                        contentDescription = if (obj.has("contentDescription") && !obj.isNull("contentDescription")) obj.getString("contentDescription") else null,
                        textToEnter = if (obj.has("textToEnter") && !obj.isNull("textToEnter")) obj.getString("textToEnter") else null,
                        xPercent = if (obj.has("xPercent") && !obj.isNull("xPercent")) obj.optDouble("xPercent").toFloat() else null,
                        yPercent = if (obj.has("yPercent") && !obj.isNull("yPercent")) obj.optDouble("yPercent").toFloat() else null,
                        delayMs = obj.optLong("delayMs", 1200L)
                    )
                }
            } else null
            
            val code = if (json.has("code") && !json.isNull("code")) json.getString("code") else null
            val template = if (json.has("template") && !json.isNull("template")) json.getString("template") else null
            
            return GeminiIntentPayload(
                intent = intent,
                action_type = action_type,
                target_package = target_package,
                intent_action = intent_action,
                intent_flags = intent_flags,
                extras = extras,
                reflective_class = reflective_class,
                reflective_method = reflective_method,
                reflective_args = reflective_args,
                steps = steps,
                code = code,
                template = template
            )
        }
    }
}

object ShellExecutor {
    fun runCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", command))
            val outputReader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val errorReader = java.io.BufferedReader(java.io.InputStreamReader(process.errorStream))
            val output = StringBuilder()
            var line: String?
            while (outputReader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            while (errorReader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
            output.toString().trim()
        } catch (e: Exception) {
            Log.e("ShellExecutor", "Command execution failed: $command", e)
            "Error executing command: ${e.message}"
        }
    }
}

object GeminiExecutor {
    private const val TAG = "GeminiExecutor"

    private val ALLOWED_CLASS_PREFIXES = listOf(
        "android.media.",
        "android.hardware.",
        "android.net.wifi.",
        "android.provider.Settings"
    )

    suspend fun execute(context: Context, payload: GeminiIntentPayload, latencyMs: Long): Boolean {
        Log.d(TAG, "Executing payload type: ${payload.action_type}")
        return when (payload.action_type) {
            "NATIVE_INTENT" -> {
                executeNativeIntent(context, payload, latencyMs)
            }
            "REFLECTIVE_CALL" -> {
                executeReflectiveCall(context, payload, latencyMs)
            }
            "AUTOMATION_SEQUENCE" -> {
                executeAutomationSequence(context, payload, latencyMs)
            }
            "SHELL_COMMAND" -> {
                executeShellCommand(context, payload, latencyMs)
            }
            else -> {
                Log.d(TAG, "Unknown action_type: ${payload.action_type}")
                false
            }
        }
    }

    private suspend fun executeShellCommand(context: Context, payload: GeminiIntentPayload, latencyMs: Long): Boolean {
        val cmd = payload.code ?: payload.reflective_args?.firstOrNull()
        if (cmd.isNullOrEmpty()) {
            Log.e(TAG, "Shell command is empty")
            return false
        }
        Log.d(TAG, "Executing Shell Command: $cmd")
        
        val output = withContext(Dispatchers.IO) {
            ShellExecutor.runCommand(cmd)
        }
        Log.d(TAG, "Shell Output: $output")
        
        val speakText = if (output.isNotEmpty()) {
            val cleanOutput = output.replace("\n", " ").replace("\r", " ").trim()
            val trimmedOutput = if (cleanOutput.length > 200) cleanOutput.substring(0, 197) + "..." else cleanOutput
            "${payload.intent}. Output: $trimmedOutput"
        } else {
            payload.intent
        }
        
        JagoTTS.speak(speakText, isAIResponse = true)
        
        AssistantUIBridge.emitTelemetry(TelemetryEvent(
            source = "Gemini",
            latencyMs = latencyMs,
            routingPath = "Gemini → SHELL_COMMAND",
            rawAction = cmd
        ))
        return true
    }

    private fun executeAutomationSequence(context: Context, payload: GeminiIntentPayload, latencyMs: Long): Boolean {
        val steps = payload.steps
        if (steps.isNullOrEmpty()) {
            Log.e(TAG, "Automation sequence steps are null or empty")
            return false
        }
        
        Log.d(TAG, "Executing dynamic automation sequence with ${steps.size} steps")
        val voiceMacro = VoiceMacro(payload.intent, steps)
        com.example.jago.service.JagoAccessibilityService.playMacro(context, voiceMacro)
        
        // Emit telemetry
        AssistantUIBridge.emitTelemetry(TelemetryEvent(
            source = "Gemini",
            latencyMs = latencyMs,
            routingPath = "Gemini → AUTOMATION_SEQUENCE",
            rawAction = "playMacro (${steps.size} steps)"
        ))
        return true
    }

    private fun executeNativeIntent(context: Context, payload: GeminiIntentPayload, latencyMs: Long): Boolean {
        if (payload.intent_action.isNullOrEmpty()) {
            Log.e(TAG, "Native intent action is empty")
            return false
        }

        try {
            val intent = Intent(payload.intent_action).apply {
                payload.target_package?.let { setPackage(it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                
                // Parse intent flags from payload if present
                payload.intent_flags?.forEach { flagStr ->
                    try {
                        val flagField = Intent::class.java.getField(flagStr)
                        val flagVal = flagField.getInt(null)
                        addFlags(flagVal)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to resolve intent flag: $flagStr", e)
                    }
                }
                
                payload.extras?.forEach { (key, value) -> putExtra(key, value) }
            }

            context.startActivity(intent)
            Log.d(TAG, "Native Intent started successfully: ${payload.intent_action}")
            
            // Speak confirmation
            JagoTTS.speak(payload.intent, isAIResponse = true)
            
            // Emit telemetry
            AssistantUIBridge.emitTelemetry(TelemetryEvent(
                source = "Gemini",
                latencyMs = latencyMs,
                routingPath = "Gemini → NATIVE_INTENT",
                rawAction = payload.intent_action
            ))
            return true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Activity not found for intent action", e)
            JagoTTS.speakBilingual(
                "I couldn't find an application to handle this action.",
                "Is action ko chalane ke liye koi app nahi mila."
            )
            return true // handled, but error spoken
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start native intent", e)
            JagoTTS.speakBilingual(
                "Failed to perform this action.",
                "Yeh action chalane mein dikkat aayi."
            )
            return true // handled, but error spoken
        }
    }

    private fun executeReflectiveCall(context: Context, payload: GeminiIntentPayload, latencyMs: Long): Boolean {
        val className = payload.reflective_class
        val methodName = payload.reflective_method
        
        if (className.isNullOrEmpty() || methodName.isNullOrEmpty()) {
            Log.e(TAG, "Reflective class or method name is null/empty")
            return false
        }

        // Whitelist security check
        val isWhitelisted = ALLOWED_CLASS_PREFIXES.any { className.startsWith(it) }
        if (!isWhitelisted) {
            Log.w(TAG, "Reflective call blocked: $className is not whitelisted.")
            AssistantUIBridge.emitTelemetry(TelemetryEvent(
                source = "Gemini",
                latencyMs = latencyMs,
                routingPath = "Gemini → REFLECTIVE_CALL (Blocked)",
                rawAction = "Security violation: $className"
            ))
            return false // Trigger fallback to Sarvam
        }

        try {
            val clazz = Class.forName(className)
            val expectedArgCount = payload.reflective_args?.size ?: 0

            // Retrieve System Service if possible
            val systemService: Any? = try {
                context.getSystemService(clazz)
            } catch (e: Exception) {
                null
            }

            // Find matching method by name and parameter count
            val methods = clazz.methods.filter { 
                it.name == methodName && 
                getEffectiveArgCount(it.parameterTypes) == expectedArgCount 
            }

            if (methods.isEmpty()) {
                Log.e(TAG, "No method $methodName matching $expectedArgCount arguments found in $className")
                return false
            }

            val method = methods.first()
            val parameterTypes = method.parameterTypes
            
            // Build coerced arguments
            var argIdx = 0
            val parsedArgs = Array<Any?>(parameterTypes.size) { i ->
                val paramType = parameterTypes[i]
                when (paramType) {
                    Context::class.java -> context
                    android.content.ContentResolver::class.java -> context.contentResolver
                    else -> {
                        val argStr = payload.reflective_args?.getOrNull(argIdx++) ?: ""
                        coerceArgument(argStr, paramType)
                    }
                }
            }

            // Invoke Method
            method.invoke(systemService ?: clazz, *parsedArgs)
            Log.d(TAG, "Reflective call executed successfully: $className.$methodName")

            // Speak confirmation
            JagoTTS.speak(payload.intent, isAIResponse = true)

            // Emit telemetry
            val rawActionStr = "$className.$methodName(${payload.reflective_args?.joinToString(", ") ?: ""})"
            AssistantUIBridge.emitTelemetry(TelemetryEvent(
                source = "Gemini",
                latencyMs = latencyMs,
                routingPath = "Gemini → REFLECTIVE_CALL",
                rawAction = rawActionStr
            ))
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Reflective call execution failed for $className.$methodName", e)
            AssistantUIBridge.emitTelemetry(TelemetryEvent(
                source = "Gemini",
                latencyMs = latencyMs,
                routingPath = "Gemini → REFLECTIVE_CALL (Failure)",
                rawAction = "Error: ${e.message}"
            ))
            return false // Fall back to Sarvam
        }
    }

    private fun getEffectiveArgCount(parameterTypes: Array<Class<*>>): Int {
        return parameterTypes.count { 
            it != Context::class.java && it != android.content.ContentResolver::class.java 
        }
    }

    private fun coerceArgument(argStr: String, type: Class<*>): Any? {
        return try {
            when (type) {
                Int::class.java, java.lang.Integer::class.java -> argStr.toDouble().toInt()
                Float::class.java, java.lang.Float::class.java -> argStr.toFloat()
                Double::class.java, java.lang.Double::class.java -> argStr.toDouble()
                Long::class.java, java.lang.Long::class.java -> argStr.toDouble().toLong()
                Boolean::class.java, java.lang.Boolean::class.java -> {
                    // Handle "true", "1", "false", "0"
                    argStr.equals("true", ignoreCase = true) || argStr == "1"
                }
                String::class.java -> argStr
                else -> argStr // Default fallback
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to coerce '$argStr' to type ${type.name}", e)
        }
    }
}
