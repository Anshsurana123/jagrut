// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.logic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.jago.BuildConfig
import com.example.jago.service.WakeWordService
import com.example.jago.ui.AssistantUIBridge
import com.example.jago.ui.TelemetryEvent
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

object JagrutExecutionEngine {
    private const val TAG = "JagrutExecutionEngine"

    @Volatile
    private var cachedRoutingModel: GenerativeModel? = null
    @Volatile
    private var cachedRoutingApiKey: String? = null
    @Volatile
    private var cachedRoutingPrompt: String? = null

    @Volatile
    private var cachedFallbackModel: GenerativeModel? = null
    @Volatile
    private var cachedFallbackApiKey: String? = null
    @Volatile
    private var cachedFallbackPrompt: String? = null

    private fun getRoutingModel(apiKey: String, systemPrompt: String): GenerativeModel {
        val model = cachedRoutingModel
        if (model != null && cachedRoutingApiKey == apiKey && cachedRoutingPrompt == systemPrompt) {
            return model
        }
        synchronized(this) {
            val modelSync = cachedRoutingModel
            if (modelSync != null && cachedRoutingApiKey == apiKey && cachedRoutingPrompt == systemPrompt) {
                return modelSync
            }
            val config = generationConfig {
                responseMimeType = "application/json"
            }
            val newModel = GenerativeModel(
                modelName = "gemini-3.5-flash",
                apiKey = apiKey,
                generationConfig = config,
                systemInstruction = content { text(systemPrompt) }
            )
            cachedRoutingModel = newModel
            cachedRoutingApiKey = apiKey
            cachedRoutingPrompt = systemPrompt
            return newModel
        }
    }

    private fun getFallbackModel(apiKey: String, systemPrompt: String): GenerativeModel {
        val model = cachedFallbackModel
        if (model != null && cachedFallbackApiKey == apiKey && cachedFallbackPrompt == systemPrompt) {
            return model
        }
        synchronized(this) {
            val modelSync = cachedFallbackModel
            if (modelSync != null && cachedFallbackApiKey == apiKey && cachedFallbackPrompt == systemPrompt) {
                return modelSync
            }
            val newModel = GenerativeModel(
                modelName = "gemini-3.5-flash",
                apiKey = apiKey,
                systemInstruction = content { text(systemPrompt) }
            )
            cachedFallbackModel = newModel
            cachedFallbackApiKey = apiKey
            cachedFallbackPrompt = systemPrompt
            return newModel
        }
    }

    suspend fun route(service: WakeWordService, text: String) = withContext(Dispatchers.Default) {
        val context = service.applicationContext
        
        // 1. Check semantic cache first
        val matchedMacro = findSemanticMatch(context, text)
        if (matchedMacro != null) {
            Log.d(TAG, "Semantic Cache Hit! matched: '${matchedMacro.voiceShortcut}'")
            
            withContext(Dispatchers.Main) {
                AssistantUIBridge.emitTelemetry(TelemetryEvent(
                    source = if (MongoDBClient.isConfigured()) "MongoDB Atlas" else "Local Cache",
                    latencyMs = 0L,
                    routingPath = "Cache Hit → Play Macro",
                    rawAction = "playMacro (${matchedMacro.voiceShortcut})"
                ))
                com.example.jago.service.JagoAccessibilityService.playMacro(context, matchedMacro)
                service.isMidFlow = false
                service.hideOverlayWithDelay()
            }
            return@withContext
        }

        // 2. Semantic Command Matching (Embedding-based, ~1-2s)
        // Catches paraphrases that exact/fuzzy matching missed
        try {
            val semanticMatch = SemanticCommandMatcher.findBestMatch(context, text)
            if (semanticMatch != null && semanticMatch.commandType != CommandType.UNKNOWN && semanticMatch.commandType != CommandType.AI_RESPONSE) {
                Log.d(TAG, "Semantic Command Match! '$text' → ${semanticMatch.commandType} (similarity=${String.format("%.3f", semanticMatch.similarity)})")
                withContext(Dispatchers.Main) {
                    AssistantUIBridge.emitTelemetry(TelemetryEvent(
                        source = "Semantic Command Matcher",
                        latencyMs = 0L,
                        routingPath = "Semantic Embedding → Direct Execute",
                        rawAction = "${semanticMatch.commandType} (sim=${String.format("%.2f", semanticMatch.similarity)})"
                    ))
                    val command = extractParametersFromQuery(semanticMatch.commandType, text)
                    ActionExecutor(context).execute(command)
                    service.isMidFlow = false
                    service.hideOverlayWithDelay()
                }
                return@withContext
            }
        } catch (e: Exception) {
            Log.e(TAG, "SemanticCommandMatcher error (non-fatal, falling through)", e)
        }

        val responded = AtomicBoolean(false)
        val thinkingJob = service.serviceScope.launch {
            delay(800)
            if (!responded.get()) {
                JagoTTS.speakBilingual("Let me think...", "Soch raha hoon...")
            }
        }

        try {
            val isAction = looksLikeDeviceAction(text)
            Log.d(TAG, "Routing query: '$text' | looksLikeDeviceAction: $isAction")

            var actionExecuted = false

            if (isAction) {
                actionExecuted = handleDeviceAction(service, text) { responded.set(true) }
            }

            if (!actionExecuted) {
                Log.d(TAG, "Query not executed as device action. Routing to conversational fallback...")
                handleConversationalFallback(service, text) { responded.set(true) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in JagrutExecutionEngine routing", e)
            service.isMidFlow = false
            service.hideOverlayWithDelay()
            JagoTTS.speakBilingual(
                "Sorry, I encountered an error. Please try again.",
                "Maaf karein, koi dikkat aayi. Dobara try karein."
            )
        } finally {
            thinkingJob.cancel()
        }
    }

    private fun looksLikeDeviceAction(text: String): Boolean {
        val lower = text.lowercase().trim()
        val verbs = listOf(
            "open", "turn on", "turn off", "set", "enable", "disable", "increase", "decrease",
            "connect", "disconnect", "send", "show", "close", "record", "click", "take", "capture",
            "type", "write", "run", "execute", "play", "search", "find", "message", "text", "mail",
            "email", "sms", "whatsapp", "telegram", "instagram", "snapchat", "facebook", "twitter",
            "post", "tweet", "tap", "long press", "scroll", "swipe", "lock", "unlock", "check", "do"
        )
        val nouns = listOf(
            "wifi", "bluetooth", "brightness", "volume", "settings", "battery", "airplane",
            "notification", "flashlight", "dnd", "silent", "video", "photo", "picture", "screenshot",
            "image", "camera", "command", "script", "code", "app", "application", "music", "song",
            "playlist", "spotify", "file", "folder", "directory", "download", "storage", "memory",
            "cpu", "process", "device", "phone"
        )
        return verbs.any { lower.contains(it) } || nouns.any { lower.contains(it) }
    }

    private suspend fun handleDeviceAction(
        service: WakeWordService, 
        text: String, 
        onResponse: () -> Unit
    ): Boolean {
        val context = service.applicationContext
        val startMs = System.currentTimeMillis()

        // 1. Retrieve all granted permissions dynamically
        val declaredPermissions = try {
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            info.requestedPermissions ?: emptyArray()
        } catch (e: Exception) {
            emptyArray<String>()
        }
        val grantedPermissions = declaredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        // 2. Build context-injected system prompt
        val systemPrompt = """
            You are an Android System Intent compiler running inside a secure application sandbox.

            The target host environment is:
            - Android Version: ${Build.VERSION.RELEASE} (API Level ${Build.VERSION.SDK_INT})
            - Device Manufacturer: ${Build.MANUFACTURER} (${Build.MODEL})
            - Granted permission scopes: ${grantedPermissions.joinToString(", ")}
            - Available Settings actions: android.settings.BLUETOOTH_SETTINGS, android.settings.WIFI_SETTINGS, android.settings.SOUND_SETTINGS, android.settings.DISPLAY_SETTINGS, android.settings.APPLICATION_DETAILS_SETTINGS, android.settings.LOCATION_SOURCE_SETTINGS, android.settings.SECURITY_SETTINGS, android.settings.BATTERY_SAVER_SETTINGS, android.settings.AIRPLANE_MODE_SETTINGS, android.settings.DATE_SETTINGS

            Your task is to generate a JSON execution schema for the user's intent.
            Map the user's request to:
            1. A valid Android Intent (NATIVE_INTENT)
            2. An explicit system reflective call (REFLECTIVE_CALL)
            3. A shell command or script to execute on the phone (SHELL_COMMAND)
            4. A sequence of UI accessibility actions (AUTOMATION_SEQUENCE)

            CRITICAL: Return ONLY a raw JSON object. No prose, no markdown, no explanation.
            If you cannot map the request to a device action or automation sequence, set action_type to "UNKNOWN".

            The JSON schema MUST conform to the following structure exactly:
            {
              "intent": "human-readable description (e.g. 'Listing files in downloads')",
              "action_type": "NATIVE_INTENT" | "REFLECTIVE_CALL" | "AUTOMATION_SEQUENCE" | "SHELL_COMMAND" | "UNKNOWN",
              "template": null,
              "target_package": "string or null",
              "intent_action": "string or null",
              "intent_flags": ["string", ...] or null,
              "extras": {"key": "value", ...} or null,
              "reflective_class": "string or null",
              "reflective_method": "string or null",
              "reflective_args": ["string", ...] or null,
              "code": "shell command string or null (required for SHELL_COMMAND)",
              "steps": [
                {
                  "actionType": "LAUNCH_APP" | "CLICK" | "LONG_CLICK" | "TEXT_ENTRY" | "SCROLL" | "SWIPE" | "BACK" | "WAIT" | "GLOBAL_ACTION" | "RUN_COMMAND",
                  "packageName": "string or null (e.g. 'com.instagram.android')",
                  "targetText": "string or null (text on button to click or search)",
                  "targetId": "string or null (view ID like 'com.instagram.android:id/shutter_button')",
                  "contentDescription": "string or null (content description of the element)",
                  "textToEnter": "string or null (text for TEXT_ENTRY, scroll direction like 'down' for SCROLL, or 'endXPercent,endYPercent' like '0.5,0.2' for SWIPE)",
                  "xPercent": float or null (0.0 to 1.0),
                  "yPercent": float or null (0.0 to 1.0),
                  "delayMs": long (delay after executing this step, defaults to 1200)
                }
              ] or null
            }

            Rules:
            - Write actual user input values directly into the "steps" fields (like "targetText" or "textToEnter") matching the query literally. Do NOT use templates, placeholders, curly braces, or variables.
            - Set the "template" field to null.
            - Use "SHELL_COMMAND" for programmable scripts or shell execution.
            - Use "AUTOMATION_SEQUENCE" for multi-step GUI automations, such as launching third-party apps, performing clicks, searching, typing messages, scrolling or swiping. Provide the steps in the "steps" array.
            - For Instagram DMs ("com.instagram.android"), after typing the contact name in the search field, the first contact search result is always at coordinates x = 669.0 and y = 430.0. To click it, use actionType "CLICK", xPercent = 669.0, yPercent = 430.0, targetText = null, targetId = null. Do not use text/ID matching for that step.
        """.trimIndent()

        // 3. Call Gemini
        try {
            val model = getRoutingModel(BuildConfig.GEMINI_API_KEY, systemPrompt)

            val response = withContext(Dispatchers.IO) {
                model.generateContent(text)
            }

            var rawJson = response.text?.trim() ?: ""
            if (rawJson.startsWith("```json")) {
                rawJson = rawJson.substringAfter("```json").substringBeforeLast("```").trim()
            } else if (rawJson.startsWith("```")) {
                rawJson = rawJson.substringAfter("```").substringBeforeLast("```").trim()
            }

            Log.d(TAG, "Gemini Raw Response: $rawJson")
            try {
                GeminiHistoryEngine.addHistoryItem(context, text, rawJson)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save Gemini response to history", e)
            }
            val latencyMs = System.currentTimeMillis() - startMs

            val payload = GeminiIntentPayload.fromJson(rawJson)
            if (payload.action_type == "UNKNOWN") {
                Log.d(TAG, "Gemini returned UNKNOWN intent.")
                return false
            }

            // We have a successful response decision from Gemini!
            onResponse()

            if (payload.action_type == "AUTOMATION_SEQUENCE") {
                val cached = MacroEngine.getMacro(context, text)
                if (cached != null) {
                    Log.d(TAG, "Cache Hit! Playing cached macro for: '$text'")
                    AssistantUIBridge.emitTelemetry(TelemetryEvent(
                        source = "Local",
                        latencyMs = 0L,
                        routingPath = "Cache Hit → Play Macro",
                        rawAction = "playMacro"
                    ))
                    com.example.jago.service.JagoAccessibilityService.playMacro(context, cached)
                    service.isMidFlow = false
                    service.hideOverlayWithDelay()
                    return true
                } else if (!payload.steps.isNullOrEmpty()) {
                    Log.d(TAG, "Cache Miss, but Gemini generated steps! Playing generated steps directly.")
                    service.isMidFlow = false
                    
                    // Asynchronously generate embedding and cache the macro locally & on MongoDB Atlas!
                    service.serviceScope.launch(Dispatchers.Default) {
                        try {
                            val embedding = generateEmbedding(text)
                            if (embedding != null) {
                                val newMacro = VoiceMacro(
                                    voiceShortcut = text,
                                    steps = payload.steps,
                                    embedding = embedding
                                )
                                MacroEngine.addMacro(context, newMacro)
                                MongoDBClient.insertMacro(text, payload.steps, embedding, null)
                                Log.d(TAG, "Cached generated macro in local & MongoDB for: '$text'")
                            } else {
                                val newMacro = VoiceMacro(voiceShortcut = text, steps = payload.steps)
                                MacroEngine.addMacro(context, newMacro)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to cache generated macro with embedding", e)
                            val newMacro = VoiceMacro(voiceShortcut = text, steps = payload.steps)
                            MacroEngine.addMacro(context, newMacro)
                        }
                    }
                    
                    val success = GeminiExecutor.execute(context, payload, latencyMs)
                    if (success) {
                        service.hideOverlayWithDelay()
                    }
                    return success
                } else {
                    Log.d(TAG, "Cache Miss! Handing over to DynamicAgentEngine for: '$text'")
                    service.isMidFlow = false
                    DynamicAgentEngine.startDynamicExecution(context, text)
                    return true
                }
            }

            service.isMidFlow = false // Clear isMidFlow before terminal execution speech starts
            val success = GeminiExecutor.execute(context, payload, latencyMs)
            if (success) {
                service.hideOverlayWithDelay()
            }
            return success
        } catch (e: Exception) {
            Log.e(TAG, "Gemini processing failed", e)
            return false
        }
    }

    private suspend fun handleConversationalFallback(
        service: WakeWordService, 
        text: String, 
        onResponse: () -> Unit
    ) {
        val startMs = System.currentTimeMillis()

        try {
            Log.d(TAG, "Calling Sarvam AI for conversational fallback...")
            val sarvamResponse = SarvamClient.askAI(text)
            val latency = System.currentTimeMillis() - startMs

            if (!sarvamResponse.isNullOrEmpty()) {
                Log.d(TAG, "Sarvam AI conversational fallback answered successfully: $sarvamResponse")
                onResponse()
                AssistantUIBridge.emitTelemetry(TelemetryEvent(
                    source = "Sarvam AI",
                    latencyMs = latency,
                    routingPath = "Sarvam AI → conversational response",
                    rawAction = "Chat fallback"
                ))
                service.isMidFlow = false
                service.hideOverlayWithDelay()
                JagoTTS.speak(sarvamResponse, isAIResponse = true)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sarvam conversational fallback failed", e)
        }

        // If Sarvam failed or was empty, fall back to Gemini 3.5 Flash
        Log.w(TAG, "Sarvam conversational fallback failed or returned empty. Falling back to Gemini 3.5 Flash...")
        val fallbackPrompt = "You are a helpful voice assistant named Jagrut. Respond in 1-2 short sentences maximum. Be extremely concise. Use Hinglish or English as appropriate for an Indian user."
        val geminiStartMs = System.currentTimeMillis()
        try {
            val model = getFallbackModel(BuildConfig.GEMINI_API_KEY, fallbackPrompt)
            val response = withContext(Dispatchers.IO) {
                model.generateContent(text)
            }
            val geminiResponse = response.text?.trim()
            val latency = System.currentTimeMillis() - geminiStartMs

            if (!geminiResponse.isNullOrEmpty()) {
                Log.d(TAG, "Gemini conversational fallback answered successfully: $geminiResponse")
                onResponse()
                AssistantUIBridge.emitTelemetry(TelemetryEvent(
                    source = "Gemini 3.5 Flash",
                    latencyMs = latency,
                    routingPath = "Gemini 3.5 Flash → conversational response (Sarvam Fallback)",
                    rawAction = "Chat fallback"
                ))
                service.isMidFlow = false
                service.hideOverlayWithDelay()
                JagoTTS.speak(geminiResponse, isAIResponse = true)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini conversational fallback failed", e)
        }

        // Complete system failure clean speech
        Log.e(TAG, "All AI fallback paths failed.")
        onResponse()
        AssistantUIBridge.emitTelemetry(TelemetryEvent(
            source = "System",
            latencyMs = 0,
            routingPath = "Failure Cascade",
            rawAction = "No AI fallback responded"
        ))
        service.isMidFlow = false
        service.hideOverlayWithDelay()
        JagoTTS.speakBilingual(
            "Sorry, I couldn't connect right now. Please try again.",
            "Maaf karein, abhi connect nahi ho saka. Dobara try karein."
        )
    }

    suspend fun generateEmbedding(text: String): List<Float>? = withContext(Dispatchers.IO) {
        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            Log.w(TAG, "Gemini API Key is blank, skipping generateEmbedding")
            return@withContext null
        }
        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent?key=${BuildConfig.GEMINI_API_KEY}"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                doOutput = true
                connectTimeout = 8000
                readTimeout = 10000
            }

            val body = JSONObject().apply {
                put("content", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", text)
                        })
                    })
                })
            }.toString()

            connection.outputStream.use { it.write(body.toByteArray()) }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream
                    .bufferedReader()
                    .use(BufferedReader::readText)
                
                val valuesArray = JSONObject(response)
                    .getJSONObject("embedding")
                    .getJSONArray("values")
                
                val list = mutableListOf<Float>()
                for (i in 0 until valuesArray.length()) {
                    list.add(valuesArray.getDouble(i).toFloat())
                }
                list
            } else {
                val err = connection.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "Embedding Error ${connection.responseCode}: $err")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate embedding for text: $text", e)
            null
        }
    }

    private suspend fun findSemanticMatch(context: Context, query: String): VoiceMacro? {
        val queryEmbedding = generateEmbedding(query) ?: return null

        // 1. Try MongoDB Atlas Vector Search first
        if (MongoDBClient.isConfigured()) {
            Log.d(TAG, "Attempting MongoDB Atlas Vector Search for query: '$query'")
            val mongoMatch = MongoDBClient.vectorSearch(queryEmbedding)
            if (mongoMatch != null) {
                Log.d(TAG, "MongoDB Atlas Vector Search Hit! matched: '${mongoMatch.voiceShortcut}'")
                // Keep local cache in sync
                MacroEngine.addMacro(context, mongoMatch)
                return mongoMatch
            }
            Log.d(TAG, "MongoDB Vector Search did not find a match. Falling back to local cache.")
        }

        // 2. Fallback to Local Cosine Similarity Cache
        val cachedMacros = MacroEngine.getMacros(context)
        
        var bestMatch: VoiceMacro? = null
        var highestSimilarity = 0.0f
        
        for (macro in cachedMacros) {
            val macroEmbedding = macro.embedding ?: continue
            val similarity = cosineSimilarity(queryEmbedding, macroEmbedding)
            Log.d(TAG, "Local semantic similarity between '$query' and '${macro.voiceShortcut}': $similarity")
            if (similarity > highestSimilarity) {
                highestSimilarity = similarity
                bestMatch = macro
            }
        }
        
        return if (highestSimilarity >= 0.82f) {
            bestMatch
        } else {
            null
        }
    }



    private fun extractParametersFromQuery(commandType: CommandType, query: String): Command {
        val lowerText = query.lowercase().trim()
        var contactName: String? = null
        var numericValue: Int? = null

        // 1. Try to extract duration if applicable (e.g. for video)
        if (commandType == CommandType.TAKE_VIDEO_AND_SEND) {
            val numRegex = Regex("(\\d+)\\s*(second|seconds|sec|secs|s|minute|minutes|min|mins|miniunte|miniuntes|m)?", RegexOption.IGNORE_CASE)
            val match = numRegex.find(lowerText)
            if (match != null) {
                val value = match.groupValues[1].toIntOrNull() ?: 5
                val unit = match.groupValues[2].lowercase()
                numericValue = if (unit.contains("min") || unit.contains("minute") || unit.contains("miniunte")) {
                    value * 60
                } else {
                    value
                }
            } else {
                numericValue = 5
            }
        }

        // 2. Try to extract contact name if command requires a contact
        val requiresContact = listOf(
            CommandType.CALL,
            CommandType.OPEN_WHATSAPP,
            CommandType.SEND_WHATSAPP_MESSAGE,
            CommandType.SEND_TELEGRAM_MESSAGE,
            CommandType.SEND_EMAIL,
            CommandType.TAKE_VIDEO_AND_SEND,
            CommandType.TAKE_PHOTO_AND_SEND,
            CommandType.SEND_RECENT_PHOTO,
            CommandType.SCREENSHOT_AND_WHATSAPP
        )

        if (commandType in requiresContact) {
            // Find "to [name]" or "call [name]" or "message [name]" or "send [name]"
            val toRegex = Regex("\\b(?:to|call|message|text|email|mail|send|share)\\s+([a-zA-Z0-9\\s]{1,15})(?:\\s+|$)", RegexOption.IGNORE_CASE)
            val match = toRegex.find(lowerText)
            if (match != null) {
                var extracted = match.groupValues[1].trim()
                // Clean up common fillers or pronouns
                val words = extracted.split(" ")
                val cleanWords = words.filter { word ->
                    word !in listOf("it", "this", "that", "them", "a", "an", "the", "video", "photo", "image", "pic", "picture", "on", "via", "in", "telegram", "whatsapp", "email", "gmail", "message")
                }
                if (cleanWords.isNotEmpty()) {
                    contactName = cleanWords.first().replaceFirstChar { it.uppercase() }
                }
            }
            
            // Fallback: search for capitalized words in the original query as they are likely names
            if (contactName == null) {
                val words = query.split(" ")
                for (word in words) {
                    val cleanWord = word.trim().replace(Regex("[^a-zA-Z]"), "")
                    if (cleanWord.isNotEmpty() && cleanWord[0].isUpperCase() && cleanWord.lowercase() !in listOf("i", "take", "record", "send", "share", "video", "photo", "ansh", "jago", "whatsapp", "telegram", "google")) {
                        contactName = cleanWord
                        break
                    }
                }
            }
        }

        return Command(
            type = commandType,
            contactName = contactName,
            numericValue = numericValue
        )
    }

    private fun cosineSimilarity(v1: List<Float>, v2: List<Float>): Float {
        if (v1.size != v2.size || v1.isEmpty()) return 0.0f
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }
        return if (normA == 0.0f || normB == 0.0f) 0.0f else (dotProduct / (Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())).toFloat())
    }
}
