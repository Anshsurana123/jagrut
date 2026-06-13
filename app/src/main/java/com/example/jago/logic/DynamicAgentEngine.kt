// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.logic

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.jago.BuildConfig
import com.example.jago.service.JagoAccessibilityService
import com.example.jago.service.WakeWordService
import com.example.jago.ui.AssistantUIBridge
import com.example.jago.ui.TelemetryEvent
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

object DynamicAgentEngine {
    private const val TAG = "DynamicAgentEngine"
    private const val MAX_STEPS = 10

    data class ScreenElement(
        val index: Int,
        val text: String,
        val contentDescription: String,
        val resourceId: String,
        val className: String,
        val node: AccessibilityNodeInfo
    )

    fun startDynamicExecution(context: Context, goal: String) {
        val service = WakeWordService.instance
        if (service == null) {
            Log.e(TAG, "WakeWordService instance is null, cannot start dynamic execution")
            return
        }

        service.serviceScope.launch {
            try {
                executeLoop(context, goal, service)
            } catch (e: Exception) {
                Log.e(TAG, "Error in dynamic agent execution loop", e)
                withContext(Dispatchers.Main) {
                    JagoTTS.speakBilingual(
                        "Dynamic automation failed due to an error.",
                        "Dynamic automation chalane mein error aaya."
                    )
                    service.isMidFlow = false
                    service.hideOverlayWithDelay()
                }
            }
        }
    }

    private suspend fun executeLoop(context: Context, goal: String, service: WakeWordService) = withContext(Dispatchers.Default) {
        Log.d(TAG, "Starting Dynamic Closed-Loop Execution for goal: '$goal'")
        
        withContext(Dispatchers.Main) {
            JagoTTS.speakBilingual(
                "Starting dynamic exploration for your request.",
                "Aapki request ke liye dynamic automation shuru kar raha hoon."
            )
            AssistantUIBridge.emitTelemetry(TelemetryEvent(
                source = "DynamicAgent",
                latencyMs = 0L,
                routingPath = "Cache Miss → Start Dynamic Agent",
                rawAction = "Goal: $goal"
            ))
        }

        val config = generationConfig {
            responseMimeType = "application/json"
        }
        
        val systemPrompt = """
            You are an autonomous Android GUI navigation agent. Your objective is to help the user achieve their goal step-by-step.
            You will receive:
            1. The user's overall GOAL.
            2. The step HISTORY of actions you have executed so far.
            3. The list of active INTERACTIVE ELEMENTS on the current screen.

            Decide the next single action to take.
            Return a raw JSON object conforming exactly to this schema:
            {
              "thought": "Briefly explain what you see and what you plan to do next.",
              "action": "CLICK" | "TEXT_ENTRY" | "BACK" | "WAIT" | "FINISH",
              "target_index": 12, // The index of the screen element to click or enter text into. Required for CLICK or TEXT_ENTRY.
              "text_to_enter": "text text", // Required ONLY for TEXT_ENTRY.
              "delay_ms": 1500 // Optional delay in milliseconds after executing this step. Defaults to 1500.
            }

            Rules:
            - If the goal is fully accomplished, return action "FINISH".
            - If you are stuck or need to return to the previous screen, return action "BACK".
            - If a loader is visible or a transition is occurring, return action "WAIT".
            - If you need to type something, perform a TEXT_ENTRY on the input element.
            - Ensure you only choose indices that are listed in the Screen Elements list.
        """.trimIndent()

        val model = GenerativeModel(
            modelName = "gemini-3.5-flash",
            apiKey = BuildConfig.GEMINI_API_KEY,
            generationConfig = config,
            systemInstruction = content { text(systemPrompt) }
        )

        val recordedSteps = mutableListOf<MacroStep>()
        val stepHistory = mutableListOf<String>()
        var stepCount = 0
        var success = false

        while (stepCount < MAX_STEPS) {
            stepCount++
            Log.d(TAG, "Starting step $stepCount/$MAX_STEPS")

            // Wait briefly for UI to settle
            delay(800)

            // 1. Observe: Capture interactive elements
            val elements = withContext(Dispatchers.Main) {
                val root = JagoAccessibilityService.getActiveRootNode()
                val list = mutableListOf<ScreenElement>()
                if (root != null) {
                    traverseAndCollect(root, list, 1)
                }
                list
            }

            if (elements.isEmpty()) {
                Log.d(TAG, "No interactive elements found on screen. Waiting...")
                delay(1000)
                continue
            }

            // Format layout for LLM
            val screenDesc = elements.joinToString("\n") { elem ->
                val classLabel = elem.className.substringAfterLast(".")
                "[${elem.index}] $classLabel - Text: \"${elem.text}\", Desc: \"${elem.contentDescription}\", ID: \"${elem.resourceId}\""
            }

            // Build Prompt
            val historyText = if (stepHistory.isEmpty()) "None" else stepHistory.joinToString("\n")
            val prompt = """
                GOAL: $goal
                
                HISTORY:
                $historyText
                
                CURRENT INTERACTIVE ELEMENTS:
                $screenDesc
            """.trimIndent()

            Log.d(TAG, "Sending screen observation to Gemini...")
            val startMs = System.currentTimeMillis()
            val response = try {
                withContext(Dispatchers.IO) {
                    model.generateContent(prompt)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gemini call failed during dynamic step", e)
                break
            }
            val latency = System.currentTimeMillis() - startMs

            var rawJson = response.text?.trim() ?: ""
            if (rawJson.startsWith("```json")) {
                rawJson = rawJson.substringAfter("```json").substringBeforeLast("```").trim()
            } else if (rawJson.startsWith("```")) {
                rawJson = rawJson.substringAfter("```").substringBeforeLast("```").trim()
            }

            Log.d(TAG, "Dynamic Step Response: $rawJson")

            val json = try {
                JSONObject(rawJson)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse JSON response from dynamic agent", e)
                break
            }

            val thought = json.optString("thought", "")
            val action = json.optString("action", "WAIT").uppercase(Locale.US)
            val targetIndex = json.optInt("target_index", -1)
            val textToEnter = json.optString("text_to_enter", "")
            val delayMs = json.optLong("delay_ms", 1500L)

            // Update Telemetry / UI Status
            withContext(Dispatchers.Main) {
                AssistantUIBridge.emitTelemetry(TelemetryEvent(
                    source = "DynamicAgent",
                    latencyMs = latency,
                    routingPath = "Observe → Decide Step $stepCount",
                    rawAction = "Thought: $thought | Action: $action"
                ))
            }

            if (action == "FINISH") {
                Log.d(TAG, "Goal achieved! Dynamic Agent complete.")
                success = true
                break
            }

            if (action == "BACK") {
                Log.d(TAG, "Action: Perform Back")
                withContext(Dispatchers.Main) {
                    JagoAccessibilityService.performGlobalBackAction()
                }
                stepHistory.add("Step $stepCount: BACK")
                recordedSteps.add(MacroStep(actionType = "BACK", packageName = null, delayMs = delayMs))
                delay(delayMs)
                continue
            }

            if (action == "WAIT") {
                Log.d(TAG, "Action: Wait")
                stepHistory.add("Step $stepCount: WAIT")
                recordedSteps.add(MacroStep(actionType = "WAIT", packageName = null, delayMs = delayMs))
                delay(delayMs)
                continue
            }

            // Retrieve screen element
            val targetElement = elements.find { it.index == targetIndex }
            if (targetElement == null) {
                Log.w(TAG, "Invalid target_index $targetIndex returned by Gemini. Skipping step.")
                stepHistory.add("Step $stepCount: Failed action (Invalid index $targetIndex)")
                continue
            }

            val node = targetElement.node
            val pkgName = node.packageName?.toString()
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val metrics = context.resources.displayMetrics
            val xPercent = if (metrics.widthPixels > 0) bounds.centerX().toFloat() / metrics.widthPixels else 0.5f
            val yPercent = if (metrics.heightPixels > 0) bounds.centerY().toFloat() / metrics.heightPixels else 0.5f

            when (action) {
                "CLICK" -> {
                    Log.d(TAG, "Action: CLICK element index $targetIndex - Text: ${targetElement.text}")
                    val clicked = withContext(Dispatchers.Main) {
                        JagoAccessibilityService.performClickOnNode(node)
                    }
                    if (clicked) {
                        stepHistory.add("Step $stepCount: CLICK element [${targetElement.text} / ${targetElement.resourceId}]")
                        recordedSteps.add(MacroStep(
                            actionType = "CLICK",
                            packageName = pkgName,
                            targetText = targetElement.text.ifEmpty { null },
                            targetId = targetElement.resourceId.ifEmpty { null },
                            contentDescription = targetElement.contentDescription.ifEmpty { null },
                            xPercent = xPercent,
                            yPercent = yPercent,
                            delayMs = delayMs
                        ))
                    } else {
                        Log.e(TAG, "Failed to click element index $targetIndex")
                        stepHistory.add("Step $stepCount: Failed to CLICK element $targetIndex")
                    }
                }
                "TEXT_ENTRY" -> {
                    Log.d(TAG, "Action: TEXT_ENTRY element index $targetIndex - Text: $textToEnter")
                    val typed = withContext(Dispatchers.Main) {
                        JagoAccessibilityService.performTextEntryOnNode(node, textToEnter)
                    }
                    if (typed) {
                        stepHistory.add("Step $stepCount: TEXT_ENTRY \"$textToEnter\" in element [${targetElement.resourceId}]")
                        recordedSteps.add(MacroStep(
                            actionType = "TEXT_ENTRY",
                            packageName = pkgName,
                            targetText = targetElement.text.ifEmpty { null },
                            targetId = targetElement.resourceId.ifEmpty { null },
                            textToEnter = textToEnter,
                            xPercent = xPercent,
                            yPercent = yPercent,
                            delayMs = delayMs
                        ))
                    } else {
                        Log.e(TAG, "Failed to enter text in element index $targetIndex")
                        stepHistory.add("Step $stepCount: Failed to TEXT_ENTRY element $targetIndex")
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown action: $action")
                }
            }

            delay(delayMs)
        }

        // 4. Wrap up and Caching (Deployment Save)
        withContext(Dispatchers.Main) {
            service.isMidFlow = false
            service.hideOverlayWithDelay()
            
            if (success && recordedSteps.isNotEmpty()) {
                Log.d(TAG, "Successfully learned dynamic macro path. Caching to MacroEngine...")
                service.serviceScope.launch(Dispatchers.Default) {
                    try {
                        val embedding = JagrutExecutionEngine.generateEmbedding(goal)
                        if (embedding != null) {
                            val newMacro = VoiceMacro(voiceShortcut = goal, steps = recordedSteps, embedding = embedding, template = null)
                            MacroEngine.addMacro(context, newMacro)
                            MongoDBClient.insertMacro(goal, recordedSteps, embedding, null)
                            Log.d(TAG, "Cached dynamic macro with embedding in local & MongoDB for: '$goal'")
                        } else {
                            val newMacro = VoiceMacro(voiceShortcut = goal, steps = recordedSteps)
                            MacroEngine.addMacro(context, newMacro)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to cache dynamic macro with embedding", e)
                        val newMacro = VoiceMacro(voiceShortcut = goal, steps = recordedSteps)
                        MacroEngine.addMacro(context, newMacro)
                    }
                }
                
                JagoTTS.speakBilingual(
                    "Dynamic automation completed successfully. Path has been cached.",
                    "Dynamic automation poori ho gayi hai. Isse save kar liya gaya hai."
                )
            } else {
                Log.w(TAG, "Dynamic automation failed to complete successfully or recorded 0 steps.")
                JagoTTS.speakBilingual(
                    "I couldn't finish the automation sequence.",
                    "Main automation sequence poora nahi kar saka."
                )
            }
        }
    }

    fun traverseAndCollect(node: AccessibilityNodeInfo?, elements: MutableList<ScreenElement>, counter: Int): Int {
        if (node == null) return counter
        var currentCounter = counter

        if (node.isVisibleToUser) {
            val isClickable = node.isClickable
            val isEditable = node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true
            val isFocusable = node.isFocusable

            if (isClickable || isEditable || isFocusable) {
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                val id = node.viewIdResourceName ?: ""

                // Only capture if it has some identifiable attributes or is editable
                if (text.isNotEmpty() || desc.isNotEmpty() || id.isNotEmpty() || isEditable) {
                    elements.add(ScreenElement(
                        index = currentCounter++,
                        text = text,
                        contentDescription = desc,
                        resourceId = id,
                        className = node.className?.toString() ?: "android.view.View",
                        node = node
                    ))
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            currentCounter = traverseAndCollect(child, elements, currentCounter)
        }
        return currentCounter
    }
}
