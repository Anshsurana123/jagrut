// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import kotlinx.coroutines.*
import android.text.Spanned
import android.text.style.ClickableSpan

class JagoAccessibilityService : AccessibilityService() {

    private var lastVolumeDownTime: Long = 0L
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private var instance: JagoAccessibilityService? = null
        private var targetApp: String? = null
        private var targetContact: String? = null
        private var pendingAutoSend: Boolean = false
        private var isAutoSending: Boolean = false
        private var automationActive: Boolean = false

        // Telegram sending variables
        var pendingTelegramSend: Boolean = false
        var pendingTelegramMessage: String? = null
        var isTelegramSending: Boolean = false

        // Recording states
        var isRecording: Boolean = false
            private set
        var recordingShortcutName: String? = null
            private set
        val recordedSteps = mutableListOf<com.example.jago.logic.MacroStep>()
        private var activeRecorderController: com.example.jago.ui.FloatingRecorderController? = null

        fun startRecording(context: Context, shortcutName: String): Boolean {
            if (instance == null) {
                Log.e("JagoAccessibility", "Service not running, cannot start recording")
                return false
            }
            isRecording = true
            recordingShortcutName = shortcutName
            recordedSteps.clear()
            
            // Launch Home screen to give user a fresh start
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(homeIntent)

            // Start Floating recorder controller bubble overlay
            instance?.handler?.postDelayed({
                activeRecorderController = com.example.jago.ui.FloatingRecorderController(
                    instance!!,
                    shortcutName
                ) {
                    stopRecording(instance!!)
                }
                activeRecorderController?.show()
            }, 800)

            Log.d("JagoAccessibility", "Started recording shortcut: $shortcutName")
            return true
        }

        fun stopRecording(context: Context) {
            if (!isRecording) return
            isRecording = false
            activeRecorderController?.dismiss()
            activeRecorderController = null
            
            val name = recordingShortcutName ?: "shortcut"
            recordingShortcutName = null

            if (recordedSteps.isNotEmpty()) {
                val voiceMacro = com.example.jago.logic.VoiceMacro(name, recordedSteps.toList())
                com.example.jago.logic.MacroEngine.addMacro(context, voiceMacro)
                android.widget.Toast.makeText(context, "Shortcut '$name' saved successfully!", android.widget.Toast.LENGTH_LONG).show()
                Log.d("JagoAccessibility", "Stopped recording. Saved voice macro '$name' with ${recordedSteps.size} steps.")
            } else {
                android.widget.Toast.makeText(context, "No actions recorded.", android.widget.Toast.LENGTH_SHORT).show()
                Log.w("JagoAccessibility", "Stopped recording. No actions were captured.")
            }
            recordedSteps.clear()

            // Return to Jago dashboard
            val intent = Intent(context, com.example.jago.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }

        fun playMacro(context: Context, macro: com.example.jago.logic.VoiceMacro) {
            if (instance == null) {
                Log.e("JagoAccessibility", "Service not running, cannot play macro")
                return
            }
            instance?.executeMacroSteps(macro)
        }
        
        // Notification storage is now handled by NotificationStore singleton
        
        // Polling variables
        private var chooserRetryCount = 0
        private const val MAX_CHOOSER_RETRIES = 10
        private const val CHOOSER_POLL_INTERVAL = 300L

        fun primeDirectShare() {
            pendingAutoSend = true
            isAutoSending = true
            automationActive = true
            Log.d("JagoAccessibility", "Direct Share Auto-Send primed (Automation Active)")
            instance?.startAutoSendPolling()
        }

        fun primeAutomation(app: String?, contact: String?) {
            targetApp = app
            targetContact = contact
            pendingAutoSend = false // Reset just in case
            isAutoSending = false
            Log.d("JagoAccessibility", "Automation primed: App=$app, Contact=$contact")
            
            // Start polling for the app in the chooser immediately if an app is targeted
            if (app != null && !pendingTelegramSend) {
                instance?.startChooserPolling()
            }
        }

        fun takeScreenshot(): Boolean {
            val result = instance?.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) ?: false
            if (result) {
                Log.d("JagoAccessibility", "Screenshot triggered")
            } else {
                Log.e("JagoAccessibility", "Failed to trigger screenshot or service not running")
            }
            return result
        }

        fun performBack(): Boolean {
            val result = instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
            if (result) {
                Log.d("JagoAccessibility", "Back action performed")
            }
            return result
        }

        fun clickShutter(): Boolean {
            val root = getCameraRootNode() ?: instance?.rootInActiveWindow
            if (root == null) {
                Log.e("JagoAccessibility", "clickShutter: Root node is null")
                return false
            }
            val nodes = findShutterNodes(root)
            Log.d("JagoAccessibility", "clickShutter: Found ${nodes.size} candidate shutter nodes")
            for (node in nodes) {
                val result = performClickOnNode(node)
                if (result) {
                    Log.d("JagoAccessibility", "Shutter triggered successfully")
                    return true
                }
            }
            Log.e("JagoAccessibility", "Shutter button not found or not clickable")
            return false
        }

        fun getCameraRootNode(): AccessibilityNodeInfo? {
            val inst = instance ?: return null
            val activeRoot = inst.rootInActiveWindow
            if (activeRoot != null && activeRoot.packageName?.toString()?.contains("camera", ignoreCase = true) == true) {
                return activeRoot
            }
            try {
                val windows = inst.windows
                for (window in windows) {
                    val root = window.root
                    if (root != null && root.packageName?.toString()?.contains("camera", ignoreCase = true) == true) {
                        Log.d("JagoAccessibility", "Found camera root in window list: ${root.packageName}")
                        return root
                    }
                }
            } catch (e: Exception) {
                Log.e("JagoAccessibility", "Failed to retrieve windows list", e)
            }
            return activeRoot
        }

        fun clickFirstSpotifyResult(): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            Log.d("JagoAccessibility", "Scanning for Spotify results...")
            
            val nodes = findSpotifyNodes(root)
            if (nodes.isNotEmpty()) {
                for (node in nodes) {
                    if (performClickOnNode(node)) {
                        Log.d("JagoAccessibility", "Clicked Spotify node: ${node.viewIdResourceName} / ${node.className}")
                        return true
                    }
                }
            }
            Log.e("JagoAccessibility", "No suitable Spotify result found")
            return false
        }

        private fun findSpotifyNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
            val list = mutableListOf<AccessibilityNodeInfo>()
            val stack = java.util.Stack<AccessibilityNodeInfo>()
            stack.push(root)
            
            // We want to find list items.
            // In Spotify, these are usually ViewGroups containing TextViews.
            
            while (stack.isNotEmpty()) {
                val node = stack.pop()
                if (node == null) continue
                
                // Exclude common non-content areas
                val id = node.viewIdResourceName?.lowercase() ?: ""
                if (id.contains("navigation") || id.contains("tab") || id.contains("toolbar") || id.contains("search_box")) {
                    continue
                }

                // Check text content to see if it looks like a song row
                // We are looking for a container that has text, but isn't just a filter chip
                if ((node.className == "android.view.ViewGroup" || node.className == "android.widget.FrameLayout" || node.className == "android.widget.LinearLayout") && node.isClickable) {
                     // Check children for text
                     if (hasTextChild(node)) {
                         list.add(node)
                     }
                }
                
                // Add children to stack (reverse order to process top-down naturally if using stack)
                for (i in node.childCount - 1 downTo 0) {
                     node.getChild(i)?.let { stack.push(it) }
                }
            }
            return list
        }

        private fun hasTextChild(node: AccessibilityNodeInfo): Boolean {
             for (i in 0 until node.childCount) {
                 val child = node.getChild(i) ?: continue
                 if (!child.text.isNullOrEmpty()) {
                     return true
                 }
                 // Deep check? Maybe just 1 level is enough for performance
             }
             return false
        }

        private fun findShutterNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
            val list = mutableListOf<AccessibilityNodeInfo>()
            val stack = java.util.Stack<AccessibilityNodeInfo>()
            stack.push(root)
            
            while (stack.isNotEmpty()) {
                val node = stack.pop()
                if (node == null) continue
                
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                val text = node.text?.toString()?.lowercase() ?: ""
                val id = node.viewIdResourceName?.lowercase() ?: ""
                
                val isShutter = desc.contains("shutter") || desc.contains("take") || desc.contains("capture") || 
                                desc.contains("photo") || desc.contains("record") || desc.contains("video") ||
                                desc.contains("start") || desc.contains("stop") ||
                                text.contains("shutter") || text.contains("take") || text.contains("capture") || 
                                text.contains("photo") || text.contains("record") || text.contains("video") ||
                                id.contains("shutter") || id.contains("capture") || id.contains("record") || 
                                id.contains("video") || id.contains("center")
                
                if (isShutter) {
                    list.add(node)
                }
                
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { stack.push(it) }
                }
            }
            return list
        }

        fun isServiceRunning(): Boolean = instance != null

        fun getActiveRootNode(): AccessibilityNodeInfo? {
            return instance?.rootInActiveWindow
        }

        fun performClickOnNode(node: AccessibilityNodeInfo, targetText: String? = null): Boolean {
            val inst = instance ?: return false
            return inst.performRobustClick(node, targetText)
        }

        fun performTextEntryOnNode(node: AccessibilityNodeInfo, text: String): Boolean {
            val arguments = android.os.Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }

        fun performGlobalBackAction(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
        }

        fun clickCoordinate(x: Float, y: Float) {
            instance?.performGestureClick(x, y)
        }


        fun readNotifications(): List<com.example.jago.logic.NotificationStore.NotificationItem> {
            return com.example.jago.logic.NotificationStore.getAndClear()
        }

        fun hasNotifications(): Boolean {
            return com.example.jago.logic.NotificationStore.hasAny()
        }

        // Now delegates to NotificationStore
        fun addNotification(item: com.example.jago.logic.NotificationStore.NotificationItem) {
            com.example.jago.logic.NotificationStore.add(item)
        }

        fun readScreen(): String {
            val root = instance?.rootInActiveWindow
                ?: return "I cannot read the screen right now. Make sure accessibility is enabled."

            // Get the app name currently on screen
            val appName = root.packageName?.toString()?.let { pkg ->
                try {
                    instance!!.packageManager.getApplicationLabel(
                        instance!!.packageManager.getApplicationInfo(pkg, 0)
                    ).toString()
                } catch (e: Exception) { null }
            }

            val texts = mutableListOf<String>()
            collectVisibleText(root, texts, depth = 0)

            if (texts.isEmpty()) return "Nothing readable on screen."

            // Smart deduplication — remove substrings that are already part of longer strings
            val deduped = texts.filter { candidate ->
                texts.none { other -> other != candidate && other.contains(candidate) }
            }.distinct()

            val prefix = if (appName != null) "On $appName. " else ""
            val result = prefix + deduped.joinToString(". ")

            Log.d("JagoAccessibility", "Screen read: ${deduped.size} items")
            return result
        }

        private fun collectVisibleText(
            node: AccessibilityNodeInfo?,
            texts: MutableList<String>,
            depth: Int
        ) {
            if (node == null || depth > 15) return // limit recursion depth

            if (node.isVisibleToUser) {
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()

                // Skip very short strings, numbers alone, and common UI noise
                val noiseWords = setOf(
                    "ok", "cancel", "back", "menu", "more", "close", "done",
                    "next", "skip", "yes", "no", "send", "edit", "copy",
                    "share", "delete", "search", "home", "settings"
                )

                val candidate = when {
                    !text.isNullOrEmpty() && text.length > 2 -> text
                    !desc.isNullOrEmpty() && desc.length > 2 && text.isNullOrEmpty() -> desc
                    else -> null
                }

                if (candidate != null) {
                    val lower = candidate.lowercase()
                    // Skip pure noise, pure numbers under 4 digits, and duplicates
                    val isPureNumber = candidate.all { it.isDigit() } && candidate.length < 4
                    val isNoise = noiseWords.contains(lower)
                    val isDuplicate = texts.any { it.equals(candidate, ignoreCase = true) }

                    if (!isPureNumber && !isNoise && !isDuplicate) {
                        texts.add(candidate)
                    }
                }
            }

            for (i in 0 until node.childCount) {
                collectVisibleText(node.getChild(i), texts, depth + 1)
            }
        }

        private var isSearchingForContact = false

        private fun waitForUI(timeoutMs: Long = 4000): AccessibilityNodeInfo? {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                val root = instance?.rootInActiveWindow
                if (root != null && root.childCount > 0) {
                    Log.d("JagoAccessibility", "UI frame detected")
                    return root
                }
                try { Thread.sleep(120) } catch (e: InterruptedException) { e.printStackTrace() }
            }
            Log.d("JagoAccessibility", "UI frame NOT detected (timeout)")
            return null
        }


        private fun selectContactViaSearch(contactName: String) {
            Log.d("JagoAccessibility", "Starting Search Automation for: $contactName")
            
            // Step 1: Find and Click Search Button
            // We use a short polling to ensure UI is ready, or just one shot if we assume waitForUI was done (but we are replacing logic)
            // Let's rely on a helper that re-posts itself if not found, or use the requested flow.
            // Prompt says: "Find Search Button... If found -> ACTION_CLICK... Wait ~500ms... etc"
            
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            
            val step1FindSearch = object : Runnable {
                var attempts = 0
                override fun run() {
                    val root = instance?.rootInActiveWindow
                    if (root == null) {
                        if (attempts < 5) {
                            attempts++
                            handler.postDelayed(this, 500) 
                            return
                        }
                        Log.e("JagoAccessibility", "Failed to access root window for Search")
                        Companion.isSearchingForContact = false
                        return
                    }

                    val searchNode = findSearchNode(root)
                    if (searchNode != null) {
                        Log.d("JagoAccessibility", "WhatsApp Search clicked")
                        // Use CLICKABLE parent if needed, similar to previous robust logic
                        var clickable = searchNode
                        while (clickable != null && !clickable.isClickable) {
                            clickable = clickable.parent
                        }
                        clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: searchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        
                        // Step 2: Wait for Search Field (500ms)
                        handler.postDelayed({ 
                            enterContactName(contactName) 
                        }, 500)
                    } else {
                        if (attempts < 5) {
                            attempts++
                            handler.postDelayed(this, 500)
                        } else {
                            Log.e("JagoAccessibility", "Search button not found")
                            Companion.isSearchingForContact = false
                        }
                    }
                }
            }
            handler.post(step1FindSearch)
        }

        private fun enterContactName(contactName: String) {
             val handler = android.os.Handler(android.os.Looper.getMainLooper())
             val root = instance?.rootInActiveWindow ?: return
             
             // Step 3: Enter Contact Name
             // Find EditText
             val editText = findNodeByClassName(root, "android.widget.EditText")
             if (editText != null) {
                 val arguments = android.os.Bundle()
                 arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, contactName)
                 editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                 Log.d("JagoAccessibility", "Contact name entered: $contactName")
                 
                 // Step 4: Wait Results (700ms)
                 handler.postDelayed({
                     clickFirstResult()
                 }, 700)
             } else {
                 Log.e("JagoAccessibility", "Search field (EditText) not found")
                 Companion.isSearchingForContact = false
             }
        }

        private fun getNodeTextOrChildText(node: AccessibilityNodeInfo): String {
            val sb = java.lang.StringBuilder()
            val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            if (text.isNotEmpty()) {
                sb.append(text).append(" ")
            }
            
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val childText = getNodeTextOrChildText(child)
                if (childText.isNotEmpty()) {
                    sb.append(childText).append(" ")
                }
            }
            return sb.toString().trim()
        }

        private fun findMessageInputEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val windowBounds = android.graphics.Rect()
            root.getBoundsInScreen(windowBounds)
            val minBottom = windowBounds.top + (0.6f * windowBounds.height()).toInt()

            val editTexts = mutableListOf<AccessibilityNodeInfo>()
            val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (!queue.isEmpty()) {
                val node = queue.poll() ?: continue
                if (node.className == "android.widget.EditText") {
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    // The message input field is always in the lower portion of the screen (bottom 40%)
                    if (bounds.bottom > minBottom) {
                        editTexts.add(node)
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            if (editTexts.isEmpty()) return null
            if (editTexts.size == 1) return editTexts[0]
            return editTexts.maxByOrNull {
                val bounds = android.graphics.Rect()
                it.getBoundsInScreen(bounds)
                bounds.bottom
            }
        }

        private fun findTelegramSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (!queue.isEmpty()) {
                val node = queue.poll() ?: continue
                val desc = node.contentDescription?.toString() ?: ""
                val id = node.viewIdResourceName?.toString() ?: ""
                
                if (node.isClickable && (desc.equals("Send", ignoreCase = true) || id.contains("send", ignoreCase = true))) {
                    return node
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            return null
        }

        private fun findSendButtonNearEditText(root: AccessibilityNodeInfo, editText: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val editBounds = android.graphics.Rect()
            editText.getBoundsInScreen(editBounds)
            
            val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            while (!queue.isEmpty()) {
                val node = queue.poll() ?: continue
                if (node.isClickable && node.className != "android.widget.EditText") {
                    val nodeBounds = android.graphics.Rect()
                    node.getBoundsInScreen(nodeBounds)
                    if (nodeBounds.left >= editBounds.right - 50 && 
                        nodeBounds.centerY() >= editBounds.top && 
                        nodeBounds.centerY() <= editBounds.bottom) {
                        candidates.add(node)
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            return candidates.minByOrNull {
                val nodeBounds = android.graphics.Rect()
                it.getBoundsInScreen(nodeBounds)
                nodeBounds.left - editBounds.right
            }
        }
        private fun findFirstSearchResultNode(root: AccessibilityNodeInfo, targetContactName: String?): AccessibilityNodeInfo? {
            // 1. Try to find by text match first (most accurate)
            if (!targetContactName.isNullOrEmpty()) {
                val node = instance?.findNodeByText(root, targetContactName)
                if (node != null) return node
            }

            // 2. Otherwise, find the RecyclerView/ListView and get its first child
            val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var listNode: AccessibilityNodeInfo? = null
            while (!queue.isEmpty()) {
                val node = queue.poll() ?: continue
                val className = node.className?.toString() ?: ""
                if (className.contains("RecyclerView") || className.contains("ListView")) {
                    listNode = node
                    break
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }

            if (listNode != null && listNode.childCount > 0) {
                // Find the first child that is not a header/divider
                for (i in 0 until listNode.childCount) {
                    val child = listNode.getChild(i) ?: continue
                    // Skip header/titles if possible (e.g. "Chats", "Contacts" labels)
                    val text = child.text?.toString() ?: ""
                    if (text.equals("Chats", ignoreCase = true) || text.equals("Contacts", ignoreCase = true) || 
                        text.equals("Messages", ignoreCase = true) || text.equals("More", ignoreCase = true)) {
                        continue
                    }
                    return child
                }
                return listNode.getChild(0)
            }
            
            return null
        }

        private fun clickFirstResult() {
             val root = instance?.rootInActiveWindow
             var clicked = false
             if (root != null) {
                 val resultNode = findFirstSearchResultNode(root, Companion.targetContact)
                 if (resultNode != null) {
                     clicked = performClickOnNode(resultNode)
                     if (clicked) {
                         Log.d("JagoAccessibility", "clickFirstResult: Dynamically clicked search result node")
                     }
                 }
             }
             
             if (!clicked) {
                 Log.d("JagoAccessibility", "clickFirstResult: Falling back to hardcoded coordinate click at (697.0, 661.0)")
                 instance?.performGestureClick(697f, 661f)
             }
             
             Companion.targetContact = null
             Companion.isSearchingForContact = false
             if (Companion.pendingTelegramSend) {
                 instance?.startTelegramSendPolling()
             } else {
                 com.example.jago.logic.JagoTTS.speak("Ready to send")
             }
        }
        
        private fun findSearchNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (!queue.isEmpty()) {
                val node = queue.poll() ?: continue
                val desc = node.contentDescription?.toString() ?: ""
                val id = node.viewIdResourceName?.toString() ?: ""
                
                if (desc.contains("Search", ignoreCase = true) || id.contains("search", ignoreCase = true)) {
                    return node
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            return null
        }
        
        private fun findNodeByClassName(root: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
             val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (!queue.isEmpty()) {
                val node = queue.poll() ?: continue
                if (node.className == className) {
                    return node
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            return null
        }
        
        private fun flattenNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
            val list = mutableListOf<AccessibilityNodeInfo>()
            val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (!queue.isEmpty()) {
                val node = queue.poll() ?: continue
                list.add(node)
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            return list
        }
    }
    
    // Polling Handler
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    
    private fun startChooserPolling() {
        Log.d("JagoAccessibility", "Starting chooser polling loop")
        Companion.chooserRetryCount = 0
        handler.post(chooserPollingRunnable)
    }

    private val chooserPollingRunnable = object : Runnable {
        override fun run() {
            if (Companion.targetApp == null) {
                return
            }
            
            if (Companion.chooserRetryCount >= Companion.MAX_CHOOSER_RETRIES) {
                 Log.e("JagoAccessibility", "Chooser target not found after ${Companion.MAX_CHOOSER_RETRIES} attempts")
                 Companion.targetApp = null // Stop trying
                 return
            }

            Log.d("JagoAccessibility", "Chooser scan attempt ${Companion.chooserRetryCount + 1}")
            
            val root = rootInActiveWindow
            if (root != null) {
                if (checkForAppInChooser(root, Companion.targetApp!!)) {
                    Companion.targetApp = null // Success
                    return
                }
            } else {
                 Log.d("JagoAccessibility", "Root window is null, waiting...")
            }

            Companion.chooserRetryCount++
            handler.postDelayed(this, Companion.CHOOSER_POLL_INTERVAL)
        }
    }
    
    private fun checkForAppInChooser(root: AccessibilityNodeInfo, appName: String): Boolean {
        val matchedNode = findNodeByText(root, appName)
        if (matchedNode != null) {
             if (matchedNode.isClickable) {
                 Log.d("JagoAccessibility", "Chooser target clicked: $appName")
                 matchedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                 return true
             } else {
                 var parent = matchedNode.parent
                 while (parent != null) {
                     if (parent.isClickable) {
                          Log.d("JagoAccessibility", "Chooser target clicked (Parent): $appName")
                          parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                          return true
                     }
                     parent = parent.parent
                 }
             }
        }
        return false
    }

    override fun onServiceConnected() {
        try {
            super.onServiceConnected()
            Log.d("JagoAccessibility", "Service Connected")
            instance = this
            
            // Explicitly request key filtering to ensure it works
            val info = serviceInfo
            if (info != null) {
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                serviceInfo = info
                Log.d("JagoAccessibility", "Key filtering flag set explicitly")
            }
        } catch (e: Exception) {
            Log.e("JagoAccessibility", "Error in onServiceConnected", e)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        try {
            Log.d("JagoAccessibility", "Service Unbound")
            instance = null
            handler.removeCallbacks(chooserPollingRunnable)
        } catch (e: Exception) {
            Log.e("JagoAccessibility", "Error in onUnbind", e)
        }
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            // 0. Visual Macro Recorder Interception
            if (Companion.isRecording && event != null) {
                val pkgName = event.packageName?.toString() ?: event.source?.packageName?.toString() ?: ""
                val className = event.className?.toString() ?: event.source?.className?.toString() ?: ""
                Log.d("JagoAccessibility", "RecEvent: type=${event.eventType}, pkg=$pkgName, class=$className, text=${event.text}")
                
                // Do not record interactions inside Jago itself
                if (pkgName != "com.example.jago" && pkgName.isNotEmpty()) {
                    val text = event.text.firstOrNull()?.toString() ?: event.source?.text?.toString()
                    val desc = event.contentDescription?.toString() ?: event.source?.contentDescription?.toString()
                    val id = event.source?.viewIdResourceName
                    
                    val bounds = android.graphics.Rect()
                    val sourceNode = event.source
                    if (sourceNode != null) {
                        sourceNode.getBoundsInScreen(bounds)
                    } else {
                        // Safe fallback: try to find a matching node in the root window
                        val root = instance?.rootInActiveWindow
                        val matchedNode = if (root != null) {
                            if (!text.isNullOrEmpty()) {
                                findNodeByText(root, text) ?: findNodeByClassName(root, className)
                            } else if (!desc.isNullOrEmpty()) {
                                findNodesByDescription(root, desc).firstOrNull() ?: findNodeByClassName(root, className)
                            } else {
                                findNodeByClassName(root, className)
                            }
                        } else null
                        if (matchedNode != null) {
                            matchedNode.getBoundsInScreen(bounds)
                        }
                    }
                    
                    val metrics = instance?.getAbsoluteDisplayMetrics() ?: resources.displayMetrics
                    val xPercent = if (metrics.widthPixels > 0) bounds.centerX().toFloat() / metrics.widthPixels else 0.5f
                    val yPercent = if (metrics.heightPixels > 0) bounds.centerY().toFloat() / metrics.heightPixels else 0.5f

                    if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                        val isKeyboard = className.contains("keyboard", ignoreCase = true) ||
                                         pkgName.contains("inputmethod", ignoreCase = true) ||
                                         pkgName.contains("swiftkey", ignoreCase = true) ||
                                         pkgName.endsWith(".kb")
                        if (pkgName != "com.android.systemui" && !isKeyboard) {
                            Companion.recordedSteps.add(com.example.jago.logic.MacroStep(
                                actionType = "CLICK",
                                packageName = pkgName,
                                targetText = text,
                                targetId = id,
                                contentDescription = desc,
                                xPercent = xPercent,
                                yPercent = yPercent
                            ))
                            Log.d("JagoAccessibility", "Recorded Click Step: Class=$className, ID=$id, Text=$text, Desc=$desc in package: $pkgName")
                        }
                    } else if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                        val textToEnter = text ?: ""
                        if (textToEnter.isNotEmpty()) {
                            val lastStep = Companion.recordedSteps.lastOrNull()
                            if (lastStep != null && lastStep.packageName == pkgName && lastStep.actionType == "TEXT_ENTRY") {
                                Companion.recordedSteps.removeAt(Companion.recordedSteps.size - 1)
                            }
                            
                            Companion.recordedSteps.add(com.example.jago.logic.MacroStep(
                                actionType = "TEXT_ENTRY",
                                packageName = pkgName,
                                targetText = text,
                                targetId = id,
                                contentDescription = desc,
                                textToEnter = textToEnter,
                                xPercent = xPercent,
                                yPercent = yPercent
                            ))
                            Log.d("JagoAccessibility", "Recorded Text Entry Step: '$textToEnter', Desc=$desc inside package: $pkgName")
                        }
                    }
                }
            }

            // NOTE: Notification capture is now handled entirely by JagoNotificationListener
            // via NotificationStore singleton. TYPE_NOTIFICATION_STATE_CHANGED removed.

            // Global Window State Monitoring for TTS Interruption
            if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (com.example.jago.logic.JagoTTS.isSpeaking) {
                    val packageName = event.packageName?.toString()
                    // Only interrupt if it's a real foreground app change
                    // NOT system UI, notification shade, or our own app
                    val systemPackages = listOf(
                        "com.example.jago",
                        "com.android.systemui",
                        "android",
                        "com.android.launcher",
                        "com.google.android.inputmethod",
                        "com.preff.kb",        // Xiaomi Keyboard
                        "com.sec.android.inputmethod", // Samsung Keyboard
                        "com.touchtype.swiftkey", // SwiftKey
                        "com.miui.home",       // Xiaomi Launcher
                        "com.sec.android.app.launcher" // Samsung Launcher
                    )
                    if (packageName != null && systemPackages.none { packageName.startsWith(it) }) {
                        Log.d("JagoAccessibility", "App changed to $packageName -> Stopping speech")
                        com.example.jago.logic.JagoTTS.stopSpeaking()
                    }
                }
            }

            // Hybrid Automation Logic
            if (event != null && (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
                
                // Stage 1 is now handled by startChooserPolling() triggered from primeAutomation
                // We keep this purely for Stage 2 (Contact Selection inside the app)
                // or if polling misses it (which it shouldn't, but redundancy is okay if managed carefully)
                // Actually, let's let polling handle Stage 1 entirely to avoid double clicks.

                val eventPackage = event.packageName?.toString() ?: ""
                if (Companion.targetContact != null && !Companion.isSearchingForContact &&
                    (Companion.targetApp == null || eventPackage == Companion.targetApp)) {
                     Companion.isSearchingForContact = true
                     // Use Handler-based search automation instead of Thread.sleep
                     Companion.selectContactViaSearch(Companion.targetContact!!)
                }

                // Direct Share Auto-Send Logic
                if (Companion.pendingAutoSend && event.packageName == "com.whatsapp" && !Companion.isAutoSending) {
                    Log.d("JagoAccessibility", "WhatsApp UI detected. Starting Auto-Send Polling...")
                    Companion.isAutoSending = true
                    instance?.startAutoSendPolling()
                }

                // Telegram Auto-Send Logic (Direct Chat opened via deep link)
                if (Companion.pendingTelegramSend && event.packageName == "org.telegram.messenger" && !Companion.isTelegramSending && Companion.targetContact == null) {
                    Log.d("JagoAccessibility", "Telegram UI detected. Starting Auto-Send Polling...")
                    Companion.isTelegramSending = true
                    instance?.startTelegramSendPolling()
                }
            }
        } catch (e: Exception) {
            Log.e("JagoAccessibility", "Error in onAccessibilityEvent", e)
        }
    }

    private fun startTelegramSendPolling() {
        Log.d("JagoAccessibility", "Telegram Auto-Send Polling Started")
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        
        val telegramSendRunnable = object : Runnable {
            var attempts = 0
            val maxAttempts = 15 // 4.5 seconds (300ms * 15)
            var textEntered = false

            override fun run() {
                if (!Companion.pendingTelegramSend) {
                    Log.d("JagoAccessibility", "Telegram send aborted or completed")
                    Companion.isTelegramSending = false
                    return
                }

                if (attempts >= maxAttempts) {
                    Log.e("JagoAccessibility", "Telegram Auto-Send timed out")
                    Companion.pendingTelegramSend = false
                    Companion.pendingTelegramMessage = null
                    Companion.isTelegramSending = false
                    return
                }

                val root = rootInActiveWindow
                if (root != null) {
                    val editText = Companion.findMessageInputEditText(root)
                    if (editText != null) {
                        if (!textEntered) {
                            val msg = Companion.pendingTelegramMessage ?: ""
                            val arguments = android.os.Bundle()
                            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, msg)
                            val success = editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                            if (success) {
                                Log.d("JagoAccessibility", "Telegram message text entered successfully")
                                textEntered = true
                                // Wait 300ms for send button to appear on UI before trying to click it
                                attempts++
                                handler.postDelayed(this, 300)
                                return
                            }
                        } else {
                            // Text is entered, now find and click the send button
                            var sendBtn = Companion.findTelegramSendButton(root)
                            if (sendBtn == null) {
                                Log.d("JagoAccessibility", "Send button not found by description/ID, trying layout fallback...")
                                sendBtn = Companion.findSendButtonNearEditText(root, editText)
                            }

                            if (sendBtn != null) {
                                val clicked = Companion.performClickOnNode(sendBtn)
                                if (clicked) {
                                    Log.d("JagoAccessibility", "Telegram Send button clicked successfully")
                                    finishTelegramSend()
                                    return
                                }
                            }
                        }
                    }
                }

                attempts++
                handler.postDelayed(this, 300)
            }
        }
        handler.post(telegramSendRunnable)
    }

    private fun finishTelegramSend() {
        Companion.pendingTelegramSend = false
        Companion.pendingTelegramMessage = null
        Companion.isTelegramSending = false
        
        if (Companion.automationActive) {
            Log.d("JagoAccessibility", "Telegram automation success. Returning to previous app in 500ms...")
            handler.postDelayed({
                returnToPreviousApp()
            }, 500)
            Companion.automationActive = false
        }
    }

    private fun startAutoSendPolling() {
        Log.d("JagoAccessibility", "Auto-Send Polling Started")
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        
        val autoSendRunnable = object : Runnable {
            var attempts = 0
            val maxAttempts = 20 // 6 seconds (300ms * 20) to handle slow animations

            override fun run() {
                if (!Companion.pendingAutoSend) {
                     Log.d("JagoAccessibility", "Auto-Send aborted or completed")
                     Companion.isAutoSending = false
                     return
                }

                if (attempts >= maxAttempts) {
                    Log.e("JagoAccessibility", "Auto-Send timed out after 6 seconds")
                    
                    // Final Resort: Blind Gesture Click (User Requested)
                    Log.w("JagoAccessibility", "Attempting Backup Gesture Click at (1121, 2522)")
                    instance?.performGestureClick(1121f, 2522f)
                    
                    Companion.pendingAutoSend = false
                    Companion.isAutoSending = false
                    return
                }

                // Strategy 1: Iterate ALL Windows (Popup logic)
                val windows = instance?.windows
                if (windows != null && windows.isNotEmpty()) {
                    for (window in windows) {
                        val root = window.root
                        if (root != null) {
                            if (tryFindAndClickSend(root)) {
                                return
                            }
                        }
                    }
                }

                // Strategy 2: Fallback to Active Window Root (Main logic)
                val activeRoot = rootInActiveWindow
                if (activeRoot != null) {
                    if (tryFindAndClickSend(activeRoot)) {
                        return
                    }
                } else {
                     Log.d("JagoAccessibility", "Auto-Send: Root window null")
                }

                attempts++
                handler.postDelayed(this, 300)
            }
        }
        handler.post(autoSendRunnable)
    }

    private fun performGestureClick(x: Float, y: Float) {
        val path = android.graphics.Path()
        path.moveTo(x, y)
        path.lineTo(x + 1, y + 1) // Small movement to ensure path is valid
        
        val builder = android.accessibilityservice.GestureDescription.Builder()
        builder.addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100)) // 100ms tap
        
        val gesture = builder.build()
        val dispatched = dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.d("JagoAccessibility", "Gesture Click Completed at ($x, $y)")
            }

            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.e("JagoAccessibility", "Gesture Click Cancelled")
            }
        }, null)
        
        Log.d("JagoAccessibility", "dispatchGesture dispatched: $dispatched")
    }

    private fun performGestureLongClick(x: Float, y: Float) {
        val path = android.graphics.Path()
        path.moveTo(x, y)
        path.lineTo(x + 1, y + 1)
        
        val builder = android.accessibilityservice.GestureDescription.Builder()
        builder.addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 800)) // 800ms for long click
        
        val gesture = builder.build()
        val dispatched = dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.d("JagoAccessibility", "Gesture Long Click Completed at ($x, $y)")
            }

            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.e("JagoAccessibility", "Gesture Long Click Cancelled")
            }
        }, null)
        Log.d("JagoAccessibility", "Long Click dispatched: $dispatched")
    }

    private fun performGestureSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) {
        val path = android.graphics.Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        
        val builder = android.accessibilityservice.GestureDescription.Builder()
        builder.addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs))
        
        val gesture = builder.build()
        val dispatched = dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.d("JagoAccessibility", "Gesture Swipe Completed from ($startX, $startY) to ($endX, $endY)")
            }

            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.e("JagoAccessibility", "Gesture Swipe Cancelled")
            }
        }, null)
        Log.d("JagoAccessibility", "Swipe dispatched: $dispatched")
    }

    private fun performGestureScroll(direction: String) {
        val metrics = getAbsoluteDisplayMetrics()
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        
        val startX: Float
        val startY: Float
        val endX: Float
        val endY: Float
        
        when (direction.lowercase()) {
            "down" -> {
                // Scroll down: swipe from bottom to top
                startX = width * 0.5f
                startY = height * 0.8f
                endX = width * 0.5f
                endY = height * 0.2f
            }
            "up" -> {
                // Scroll up: swipe from top to bottom
                startX = width * 0.5f
                startY = height * 0.2f
                endX = width * 0.5f
                endY = height * 0.8f
            }
            "left" -> {
                // Scroll left: swipe from right to left
                startX = width * 0.8f
                startY = height * 0.5f
                endX = width * 0.2f
                endY = height * 0.5f
            }
            "right" -> {
                // Scroll right: swipe from left to right
                startX = width * 0.2f
                startY = height * 0.5f
                endX = width * 0.8f
                endY = height * 0.5f
            }
            else -> {
                Log.e("JagoAccessibility", "Unknown scroll direction: $direction")
                return
            }
        }
        
        Log.d("JagoAccessibility", "Performing scroll gesture '$direction': ($startX, $startY) -> ($endX, $endY)")
        performGestureSwipe(startX, startY, endX, endY, 400L) // 400ms duration for scroll
    }

    private fun tryFindAndClickSend(root: AccessibilityNodeInfo): Boolean {
        // 1. WhatsApp View ID
        val sendNodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
        if (sendNodes != null && sendNodes.isNotEmpty()) {
            val sendButton = sendNodes[0]
            if (sendButton.isVisibleToUser && performRobustClick(sendButton)) {
                Log.d("JagoAccessibility", "Auto-Send SUCCESS via WhatsApp ID")
                finishAutoSend()
                return true
            }
        }

        // 2. Generic Common Email/Messaging App IDs (Gmail, Outlook, Samsung Email, etc.)
        val commonIds = listOf(
            "com.google.android.gm:id/send",
            "com.google.android.gm:id/send_button",
            "com.google.android.gm:id/send_button_action",
            "com.samsung.android.email.provider:id/send",
            "com.microsoft.office.outlook:id/send"
        )
        for (id in commonIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes != null && nodes.isNotEmpty()) {
                for (node in nodes) {
                    if (node.isVisibleToUser && performRobustClick(node)) {
                        Log.d("JagoAccessibility", "Auto-Send SUCCESS via ID: $id")
                        finishAutoSend()
                        return true
                    }
                }
            }
        }

        // 3. Text/Description Search "Send" (Fallback for all email apps and WhatsApp/Telegram)
        val textNodes = root.findAccessibilityNodeInfosByText("Send")
        if (textNodes != null) {
            for (node in textNodes) {
                val desc = node.contentDescription?.toString()
                val text = node.text?.toString()
                
                if (desc.equals("Send", ignoreCase = true) || text.equals("Send", ignoreCase = true)) {
                    if (node.isVisibleToUser && performRobustClick(node)) {
                         Log.d("JagoAccessibility", "Auto-Send SUCCESS via Description/Text")
                         finishAutoSend()
                         return true
                    }
                }
            }
        }
        return false
    }

    private fun finishAutoSend() {
        Companion.pendingAutoSend = false
        Companion.isAutoSending = false
        
        if (Companion.automationActive) {
            Log.d("JagoAccessibility", "Automation success. Returning to previous app in 500ms...")
            handler.postDelayed({
                returnToPreviousApp()
            }, 500)
            Companion.automationActive = false
        }
    }

    private fun returnToPreviousApp() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (pkg == "com.example.jago") {
            Log.d("JagoAccessibility", "Already returned to Jago, skipping back action")
            return
        }
        Log.d("JagoAccessibility", "Performing Double Back Action to return to previous app")
        performGlobalAction(GLOBAL_ACTION_BACK)
        
        handler.postDelayed({
             val currentRoot = rootInActiveWindow
             if (currentRoot?.packageName?.toString() != "com.example.jago") {
                 Log.d("JagoAccessibility", "Performing Second Back Action")
                 performGlobalAction(GLOBAL_ACTION_BACK)
             }
        }, 300)
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        // Deprecated, use performRobustClick
        return performRobustClick(node)
    }

    private fun performRobustClick(node: AccessibilityNodeInfo, targetText: String? = null): Boolean {
        // 1. Spanned link check: if it contains clickable spans (links), click the matching span!
        val text = node.text
        if (text is Spanned) {
            val spans = text.getSpans(0, text.length, ClickableSpan::class.java)
            if (spans.isNotEmpty()) {
                val indexToClick = if (!targetText.isNullOrEmpty()) {
                    var foundIndex = -1
                    for (i in spans.indices) {
                        val span = spans[i]
                        val start = text.getSpanStart(span)
                        val end = text.getSpanEnd(span)
                        val spanText = text.subSequence(start, end).toString().trim()
                        if (spanText.contains(targetText, ignoreCase = true)) {
                            foundIndex = i
                            break
                        }
                    }
                    foundIndex
                } else {
                    0
                }
                
                if (indexToClick >= 0) {
                    Log.d("JagoAccessibility", "performRobustClick: Found clickable span at index $indexToClick. Triggering via ACTION_CLICK.")
                    val arguments = android.os.Bundle()
                    arguments.putInt("android.view.accessibility.action.ARGUMENT_CLICK_SPAN_INDEX_INT", indexToClick)
                    val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK, arguments)
                    if (result) return true
                }
            }
        }

        // 2. Precise gesture click if the node has valid center coordinates on-screen (and is not full screen)
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val metrics = getAbsoluteDisplayMetrics()
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val isOnScreen = cx > 0 && cx < metrics.widthPixels && cy > 0 && cy < metrics.heightPixels
        val isTooLarge = bounds.width() > metrics.widthPixels * 0.8f && bounds.height() > metrics.heightPixels * 0.8f

        if (isOnScreen && !isTooLarge) {
            Log.d("JagoAccessibility", "performRobustClick: Node has valid on-screen center at ($cx, $cy). Performing precise gesture click.")
            performGestureClick(cx.toFloat(), cy.toFloat())
            return true
        }

        // 3. Fallback: programmatic ACTION_CLICK up the parent tree for off-screen/partially visible elements
        val bannedClasses = listOf(
            "ListView", "GridView", "RecyclerView", "ScrollView", "ViewPager", "AdapterView"
        )
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            val className = current.className?.toString() ?: ""
            val isBanned = bannedClasses.any { className.contains(it, ignoreCase = true) }
            
            val parentBounds = android.graphics.Rect()
            current.getBoundsInScreen(parentBounds)
            val parentTooLarge = parentBounds.width() > metrics.widthPixels * 0.8f && parentBounds.height() > metrics.heightPixels * 0.8f

            if (current.isClickable && !isBanned && !parentTooLarge) {
                current.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val result = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (result) {
                    Log.d("JagoAccessibility", "performRobustClick: Programmatic parent click succeeded for ${current.className}")
                    return true
                }
            }
            current = current.parent
        }

        // 4. Last resort coordinate tap if positive coordinates are found
        if (cx > 0 && cy > 0) {
            Log.d("JagoAccessibility", "performRobustClick: Last resort gesture click at ($cx, $cy)")
            performGestureClick(cx.toFloat(), cy.toFloat())
            return true
        }

        return false
    }

    private fun findNodesByDescription(root: AccessibilityNodeInfo, description: String): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (!queue.isEmpty()) {
            val node = queue.poll() ?: continue
            val desc = node.contentDescription?.toString() ?: ""
            if (desc.equals(description, ignoreCase = true)) {
                list.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return list
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        
        // Pass 1: Exact match (case-insensitive)
        queue.add(root)
        while (!queue.isEmpty()) {
            val node = queue.poll() ?: continue
            val nodeText = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            
            if (nodeText.equals(text, ignoreCase = true) || desc.equals(text, ignoreCase = true)) {
                return node
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        
        // Pass 2: Contains match (case-insensitive)
        queue.clear()
        queue.add(root)
        while (!queue.isEmpty()) {
            val node = queue.poll() ?: continue
            val nodeText = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            
            if ((nodeText.isNotEmpty() && nodeText.contains(text, ignoreCase = true)) || 
                (desc.isNotEmpty() && desc.contains(text, ignoreCase = true))) {
                Log.d("JagoAccessibility", "findNodeByText: Found substring match for '$text' in '${nodeText.ifEmpty { desc }}'")
                return node
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        
        return null
    }



    override fun onKeyEvent(event: android.view.KeyEvent?): Boolean {
        try {
            if (event != null) {
                Log.d("JagoAccessibility", "Key Event: ${event.keyCode}, Action: ${event.action}")
                
                if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN && event.action == android.view.KeyEvent.ACTION_DOWN) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastVolumeDownTime < 500) {
                        Log.d("JagoAccessibility", "Volume Down Double Press Detected. Activating Saathi.")
                        lastVolumeDownTime = 0L
                        
                        if (!com.example.jago.service.WakeWordService.isServiceRunning) {
                            try {
                                startForegroundService(Intent(this, com.example.jago.service.WakeWordService::class.java))
                            } catch (e: Exception) {
                                Log.e("JagoAccessibility", "Failed to start WakeWordService", e)
                            }
                        }
                        
                        sendBroadcast(Intent("com.example.jago.ACTIVATE_SAATHI").apply {
                            setPackage(packageName)
                        })
                        return true // Consume the event
                    } else {
                        lastVolumeDownTime = currentTime
                    }
                }

                if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_DOWN) {
                    if (com.example.jago.logic.JagoTTS.isSpeaking) {
                        Log.d("JagoAccessibility", "Back button detected")
                        Log.d("JagoAccessibility", "Stopping TTS from AccessibilityService")
                        com.example.jago.logic.JagoTTS.stopSpeaking()
                        // Consume the event so it doesn't trigger back navigation in the foreground app
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("JagoAccessibility", "Error in onKeyEvent", e)
        }
        return super.onKeyEvent(event)
    }

    private fun getAbsoluteDisplayMetrics(): android.util.DisplayMetrics {
        val metrics = android.util.DisplayMetrics()
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                this.display?.getRealMetrics(metrics) ?: wm.defaultDisplay.getRealMetrics(metrics)
            } else {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
            }
        } catch (e: Exception) {
            metrics.setTo(resources.displayMetrics)
        }
        return metrics
    }

    private fun clickSpotifyPlayButton(root: AccessibilityNodeInfo): Boolean {
        val playIds = listOf(
            "com.spotify.music:id/play_button",
            "com.spotify.music:id/button_play_pause",
            "com.spotify.music:id/play_pause_button",
            "com.spotify.music:id/button_play",
            "com.spotify.music:id/play"
        )
        val playTexts = listOf("play", "play music", "shuffle play", "shuffle", "play playlist")

        // 1. Search by IDs
        for (id in playIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes != null && nodes.isNotEmpty()) {
                for (node in nodes) {
                    if (performRobustClick(node)) {
                        Log.d("JagoAccessibility", "Natively clicked Spotify Play button via ID: $id")
                        return true
                    }
                }
            }
        }

        // 2. Search by Content Descriptions / Texts
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (!queue.isEmpty()) {
            val node = queue.poll() ?: continue
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            
            val matches = playTexts.any { desc.contains(it) || text.contains(it) }
            if (matches) {
                if (performRobustClick(node)) {
                    Log.d("JagoAccessibility", "Natively clicked Spotify Play button via Text/Desc: desc=$desc, text=$text")
                    return true
                }
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private suspend fun ensureSpotifySearchScreen(root: AccessibilityNodeInfo): Boolean {
        Log.d("JagoAccessibility", "Smart Assist: Checking if Spotify Search screen needs to be opened...")
        // If the query edit text is already visible, we are already on the Search screen!
        val queryNodes = root.findAccessibilityNodeInfosByViewId("com.spotify.music:id/query")
        if (queryNodes != null && queryNodes.isNotEmpty() && queryNodes[0].isVisibleToUser) {
            Log.d("JagoAccessibility", "Smart Assist: Spotify Search screen is already open.")
            return true
        }
        
        // Otherwise, we need to click the "Search" tab at the bottom!
        Log.d("JagoAccessibility", "Smart Assist: Search field not visible. Attempting to click Search tab...")
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var searchTabNode: AccessibilityNodeInfo? = null
        while (!queue.isEmpty()) {
            val node = queue.poll() ?: continue
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            
            // Check if it's the Search tab (usually has text/description containing "search" and is at the bottom of the screen)
            if ((text.contains("search") || desc.contains("search")) && node.packageName == "com.spotify.music") {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                
                // Get the real physical screen size
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    this.display ?: wm.defaultDisplay
                } else {
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay
                }
                val size = android.graphics.Point()
                display.getRealSize(size)
                
                val yPercent = bounds.centerY().toFloat() / size.y
                if (yPercent > 0.75f) {
                    searchTabNode = node
                    break
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        
        if (searchTabNode != null) {
            Log.d("JagoAccessibility", "Smart Assist: Found Spotify Search tab node at bottom of screen. Clicking it...")
            val clicked = performRobustClick(searchTabNode)
            if (clicked) {
                delay(1200) // Wait for screen transition
                return true
            }
        }
        
        // Dynamic coordinate-based click fallback (x ~ 38% for Search tab, y ~ 95% of absolute real display height)
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            this.display ?: wm.defaultDisplay
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay
        }
        val size = android.graphics.Point()
        display.getRealSize(size)
        
        val x = 0.38f * size.x
        val y = 0.95f * size.y
        Log.w("JagoAccessibility", "Smart Assist: Search tab node not found or click failed. Using dynamic coordinates fallback at ($x, $y) of real size (${size.x}x${size.y})")
        performGestureClick(x, y)
        delay(1200)
        return true
    }

    private fun executeMacroSteps(macro: com.example.jago.logic.VoiceMacro) {
        Log.d("JagoAccessibility", "Replaying macro: ${macro.voiceShortcut} with ${macro.steps.size} steps")
        com.example.jago.logic.JagoTTS.speak("Executing shortcut ${macro.voiceShortcut}")
        
        serviceScope.launch {
            try {
                val systemOrLauncherPackages = listOf(
                    "com.example.jago",
                    "com.android.systemui",
                    "android",
                    "com.android.launcher",
                    "com.android.launcher3",
                    "com.google.android.apps.nexuslauncher",
                    "com.sec.android.app.launcher",
                    "com.miui.home",
                    "com.huawei.android.launcher",
                    "com.oppo.launcher",
                    "com.coloros.launcher"
                )

                // 1. Identify the actual target app that isn't a launcher or Jago
                val targetAppPackage = macro.steps.firstOrNull { step ->
                    val pkg = step.packageName ?: ""
                    pkg.isNotEmpty() && systemOrLauncherPackages.none { pkg.startsWith(it) }
                }?.packageName

                if (!targetAppPackage.isNullOrEmpty()) {
                    Log.d("JagoAccessibility", "Replay: Launching real target app directly -> $targetAppPackage")
                    val launchIntent = packageManager.getLaunchIntentForPackage(targetAppPackage)
                    if (launchIntent != null) {
                        launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(launchIntent)
                        delay(2600) // Wait 2.6 seconds for app launch & draw
                    }
                }

                // 2. Filter out launcher click steps so they are not executed in the target app
                val executableSteps = macro.steps.filter { step ->
                    val pkg = step.packageName ?: ""
                    pkg.isNotEmpty() && systemOrLauncherPackages.none { pkg.startsWith(it) }
                }

                Log.d("JagoAccessibility", "Replay steps - Total: ${macro.steps.size}, Executable: ${executableSteps.size}")

                for ((index, step) in executableSteps.withIndex()) {
                    Log.d("JagoAccessibility", "Executing replayed Step ${index + 1}/${executableSteps.size}: ${step.actionType} in: ${step.packageName}")
                    
                    // Launch app if package changes mid-macro to another app
                    if (index > 0 && step.packageName != executableSteps[index - 1].packageName && !step.packageName.isNullOrEmpty()) {
                        Log.d("JagoAccessibility", "Replay: Mid-macro app transition detected to ${step.packageName}")
                        val launchIntent = packageManager.getLaunchIntentForPackage(step.packageName)
                        if (launchIntent != null) {
                            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            startActivity(launchIntent)
                            delay(2200)
                        }
                    }

                    // Perform the action
                    when (step.actionType) {
                        "CLICK" -> {
                            var clicked = false
                            val root = rootInActiveWindow
                            if (root != null) {
                                if (!step.targetId.isNullOrEmpty()) {
                                    val nodes = root.findAccessibilityNodeInfosByViewId(step.targetId)
                                    if (nodes != null && nodes.isNotEmpty()) {
                                        clicked = performRobustClick(nodes[0], step.targetText)
                                    }
                                }
                                if (!clicked && !step.targetText.isNullOrEmpty()) {
                                    val textNode = findNodeByText(root, step.targetText)
                                    if (textNode != null) {
                                        clicked = performRobustClick(textNode, step.targetText)
                                    }
                                }
                                if (!clicked && !step.contentDescription.isNullOrEmpty()) {
                                    var descNodes = findNodesByDescription(root, step.contentDescription)
                                    if (descNodes.isEmpty() && step.packageName?.contains("spotify") == true) {
                                        // Handle state changes of Spotify play/pause toggle button
                                        if (step.contentDescription.equals("Play", ignoreCase = true)) {
                                            descNodes = findNodesByDescription(root, "Pause")
                                        } else if (step.contentDescription.equals("Pause", ignoreCase = true)) {
                                            descNodes = findNodesByDescription(root, "Play")
                                        }
                                    }
                                    if (descNodes.isNotEmpty()) {
                                        clicked = performRobustClick(descNodes[0], step.targetText)
                                        if (clicked) {
                                            Log.d("JagoAccessibility", "Replay: Clicked node via contentDescription: ${descNodes[0].contentDescription}")
                                        }
                                    }
                                }
                            }
                            
                            if (!clicked && step.packageName?.contains("whatsapp") == true && root != null) {
                                val resolvedNode = resolveWhatsAppNodeHeuristically(root, step)
                                if (resolvedNode != null) {
                                    clicked = performRobustClick(resolvedNode, step.targetText)
                                    if (clicked) {
                                        Log.d("JagoAccessibility", "Replay: Clicked WhatsApp node heuristically resolved for step: ${step.targetId ?: step.targetText ?: step.contentDescription}")
                                    }
                                }
                            }
                            
                            // Coords Fallback using Absolute Display metrics
                            if (!clicked && step.xPercent != null && step.yPercent != null) {
                                val metrics = getAbsoluteDisplayMetrics()
                                val x = if (step.xPercent > 1.0f) step.xPercent else step.xPercent * metrics.widthPixels
                                val y = if (step.yPercent > 1.0f) step.yPercent else step.yPercent * metrics.heightPixels
                                Log.d("JagoAccessibility", "Replay: View not found in layout node tree. Fallback coordinates tap click at ($x, $y)")
                                performGestureClick(x, y)
                                clicked = true
                            }
                            
                            if (!clicked) {
                                Log.e("JagoAccessibility", "Failed to click step ${index + 1}")
                            }
                        }
                        "TEXT_ENTRY" -> {
                            var textEntered = false
                            val root = rootInActiveWindow
                            if (root != null && !step.textToEnter.isNullOrEmpty()) {
                                var activeRoot = root
                                if (step.packageName?.contains("spotify") == true) {
                                    ensureSpotifySearchScreen(activeRoot)
                                    activeRoot = rootInActiveWindow ?: activeRoot
                                }
                                
                                var editTextNode: AccessibilityNodeInfo? = null
                                if (!step.targetId.isNullOrEmpty()) {
                                    val nodes = activeRoot.findAccessibilityNodeInfosByViewId(step.targetId)
                                    if (nodes != null && nodes.isNotEmpty()) {
                                        editTextNode = nodes[0]
                                    }
                                }
                                if (editTextNode == null && !step.targetText.isNullOrEmpty()) {
                                    editTextNode = findNodeByText(activeRoot, step.targetText)
                                }
                                if (editTextNode == null) {
                                    editTextNode = findNodeByClassName(activeRoot, "android.widget.EditText")
                                }
                                
                                if (editTextNode != null) {
                                    val arguments = android.os.Bundle()
                                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, step.textToEnter)
                                    textEntered = editTextNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                                    Log.d("JagoAccessibility", "Text entered: ${step.textToEnter}")
                                }
                            }
                            
                            if (!textEntered) {
                                Log.e("JagoAccessibility", "Failed to enter text for step ${index + 1}")
                            }
                        }
                        "BACK" -> {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        }
                        "GLOBAL_ACTION" -> {
                            val action = step.textToEnter ?: ""
                            if (action.equals("BACK", ignoreCase = true)) {
                                performGlobalAction(GLOBAL_ACTION_BACK)
                            } else if (action.equals("TAKE_SCREENSHOT", ignoreCase = true)) {
                                performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                            } else if (action.equals("HOME", ignoreCase = true)) {
                                performGlobalAction(GLOBAL_ACTION_HOME)
                            } else if (action.equals("RECENTS", ignoreCase = true)) {
                                performGlobalAction(GLOBAL_ACTION_RECENTS)
                            } else if (action.equals("NOTIFICATIONS", ignoreCase = true)) {
                                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                            } else if (action.equals("QUICK_SETTINGS", ignoreCase = true)) {
                                performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                            }
                        }
                        "LAUNCH_APP" -> {
                            val pkg = step.packageName
                            if (!pkg.isNullOrEmpty()) {
                                Log.d("JagoAccessibility", "Replay: Dynamic LAUNCH_APP -> $pkg")
                                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                                if (launchIntent != null) {
                                    launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    startActivity(launchIntent)
                                }
                            }
                        }
                        "RUN_COMMAND" -> {
                            val cmdText = step.textToEnter
                            if (!cmdText.isNullOrEmpty()) {
                                Log.d("JagoAccessibility", "Replay: Dynamic RUN_COMMAND -> $cmdText")
                                handler.post {
                                    WakeWordService.instance?.processCommand(cmdText)
                                }
                            }
                        }
                        "LONG_CLICK" -> {
                            var clicked = false
                            val root = rootInActiveWindow
                            if (root != null) {
                                if (!step.targetId.isNullOrEmpty()) {
                                    val nodes = root.findAccessibilityNodeInfosByViewId(step.targetId)
                                    if (nodes != null && nodes.isNotEmpty()) {
                                        clicked = nodes[0].performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                                    }
                                }
                                if (!clicked && !step.targetText.isNullOrEmpty()) {
                                    val textNode = findNodeByText(root, step.targetText)
                                    if (textNode != null) {
                                        clicked = textNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                                    }
                                }
                                if (!clicked && !step.contentDescription.isNullOrEmpty()) {
                                    val descNodes = findNodesByDescription(root, step.contentDescription)
                                    if (descNodes.isNotEmpty()) {
                                        clicked = descNodes[0].performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                                    }
                                }
                            }
                            if (!clicked && step.xPercent != null && step.yPercent != null) {
                                val metrics = getAbsoluteDisplayMetrics()
                                val x = if (step.xPercent > 1.0f) step.xPercent else step.xPercent * metrics.widthPixels
                                val y = if (step.yPercent > 1.0f) step.yPercent else step.yPercent * metrics.heightPixels
                                performGestureLongClick(x, y)
                                clicked = true
                            }
                            if (!clicked) {
                                Log.e("JagoAccessibility", "Failed to long click step ${index + 1}")
                            }
                        }
                        "SCROLL" -> {
                            val direction = step.textToEnter ?: "down"
                            performGestureScroll(direction)
                        }
                        "SWIPE" -> {
                            if (step.xPercent != null && step.yPercent != null && step.textToEnter != null) {
                                val parts = step.textToEnter.split(",")
                                if (parts.size >= 2) {
                                    val endXPercent = parts[0].toFloatOrNull() ?: 0.5f
                                    val endYPercent = parts[1].toFloatOrNull() ?: 0.5f
                                    val metrics = getAbsoluteDisplayMetrics()
                                    val startX = if (step.xPercent > 1.0f) step.xPercent else step.xPercent * metrics.widthPixels
                                    val startY = if (step.yPercent > 1.0f) step.yPercent else step.yPercent * metrics.heightPixels
                                    val endX = if (endXPercent > 1.0f) endXPercent else endXPercent * metrics.widthPixels
                                    val endY = if (endYPercent > 1.0f) endYPercent else endYPercent * metrics.heightPixels
                                    performGestureSwipe(startX, startY, endX, endY, step.delayMs.coerceAtMost(1000L))
                                }
                            }
                        }
                    }
                    
                    // Wait for the step delay
                    delay(step.delayMs)
                }

                // 3. Post-Replay Smart Assist for Spotify (Ensure music plays successfully)
                val isSpotifyMacro = targetAppPackage?.contains("spotify") == true || executableSteps.any { it.packageName?.contains("spotify") == true }
                if (isSpotifyMacro) {
                    delay(1200) // Wait for screen to settle
                    val root = rootInActiveWindow
                    if (root != null) {
                        val playClicked = clickSpotifyPlayButton(root)
                        if (playClicked) {
                            Log.d("JagoAccessibility", "Smart Assist: Successfully clicked Spotify Play/Shuffle button!")
                        } else {
                            Log.d("JagoAccessibility", "Smart Assist: Play button not found or already playing.")
                        }
                    }
                }

                Log.d("JagoAccessibility", "Completed replaying macro: ${macro.voiceShortcut}")
                com.example.jago.logic.JagoTTS.speak("Shortcut execution complete")
            } catch (e: Exception) {
                Log.e("JagoAccessibility", "Error replaying macro", e)
                com.example.jago.logic.JagoTTS.speak("Failed to execute shortcut due to an error")
            }
        }
    }

    private fun findFirstListChild(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (!queue.isEmpty()) {
            val node = queue.poll() ?: continue
            val className = node.className?.toString() ?: ""
            if (className.contains("RecyclerView") || className.contains("ListView")) {
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        val clickableNode = findFirstClickableDescendantOrSelf(child)
                        if (clickableNode != null) {
                            return clickableNode
                        }
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findFirstClickableDescendantOrSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val res = findFirstClickableDescendantOrSelf(child)
                if (res != null) return res
            }
        }
        return null
    }

    private fun findWhatsAppProfilePhotoNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ids = listOf(
            "com.whatsapp:id/profile_photo",
            "com.whatsapp:id/change_photo_btn",
            "com.whatsapp:id/profile_transition_photo",
            "com.whatsapp:id/profile_image",
            "com.whatsapp:id/avatar"
        )
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes != null && nodes.isNotEmpty()) return nodes[0]
        }
        
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (!queue.isEmpty()) {
            val node = queue.poll() ?: continue
            val id = node.viewIdResourceName ?: ""
            if (id.contains("profile_photo") || id.contains("profile_image") || id.contains("change_photo_btn") || id.contains("transition_photo")) {
                if (node.isClickable) return node
                var p = node.parent
                while (p != null) {
                    if (p.isClickable) return p
                    p = p.parent
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        
        queue.clear()
        queue.add(root)
        while (!queue.isEmpty()) {
            val node = queue.poll() ?: continue
            val className = node.className?.toString() ?: ""
            if (className.contains("ImageView") && node.isClickable) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findWhatsAppEditPhotoButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ids = listOf(
            "com.whatsapp:id/change_photo_btn",
            "com.whatsapp:id/menu_item_edit",
            "com.whatsapp:id/edit",
            "com.whatsapp:id/edit_photo",
            "com.whatsapp:id/camera_button"
        )
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes != null && nodes.isNotEmpty()) return nodes[0]
        }
        
        val descs = listOf("Edit", "Change photo", "Edit photo", "Camera", "Change profile photo")
        for (desc in descs) {
            val nodes = findNodesByDescription(root, desc)
            if (nodes.isNotEmpty()) return nodes[0]
        }
        
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (!queue.isEmpty()) {
            val node = queue.poll() ?: continue
            val id = node.viewIdResourceName ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            if (id.contains("edit") || id.contains("camera") || desc.contains("edit", ignoreCase = true) || desc.contains("photo", ignoreCase = true)) {
                if (node.isClickable) return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findWhatsAppRemovePhotoButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val texts = listOf("Remove photo", "Remove picture", "Delete photo", "Delete picture", "Remove", "Delete")
        for (text in texts) {
            val node = findNodeByText(root, text)
            if (node != null) return node
        }
        
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (!queue.isEmpty()) {
            val node = queue.poll() ?: continue
            val id = node.viewIdResourceName ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            if (id.contains("delete") || id.contains("remove") || id.contains("trash") || id.contains("bin") ||
                desc.contains("delete", ignoreCase = true) || desc.contains("remove", ignoreCase = true) ||
                text.contains("delete", ignoreCase = true) || text.contains("remove", ignoreCase = true)) {
                if (node.isClickable) return node
                var p = node.parent
                while (p != null) {
                    if (p.isClickable) return p
                    p = p.parent
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findWhatsAppConfirmRemoveButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByViewId("android:id/button1")
        if (nodes != null && nodes.isNotEmpty()) return nodes[0]
        
        val nodes2 = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/button1")
        if (nodes2 != null && nodes2.isNotEmpty()) return nodes2[0]

        val texts = listOf("Remove", "Delete", "OK", "Yes")
        for (text in texts) {
            val node = findNodeByText(root, text)
            if (node != null) return node
        }
        return null
    }

    private fun findWhatsAppProfileCardRow(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val rowIds = listOf(
            "com.whatsapp:id/settings_profile_info",
            "com.whatsapp:id/profile_settings_row",
            "com.whatsapp:id/profile_info_layout",
            "com.whatsapp:id/profile_settings_info",
            "com.whatsapp:id/profile_settings_layout"
        )
        for (id in rowIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes != null && nodes.isNotEmpty()) {
                return nodes[0]
            }
        }
        
        val subIds = listOf(
            "com.whatsapp:id/profile_name",
            "com.whatsapp:id/name",
            "com.whatsapp:id/profile_photo",
            "com.whatsapp:id/avatar",
            "com.whatsapp:id/settings_profile_image",
            "com.whatsapp:id/settings_profile_name"
        )
        for (id in subIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes != null && nodes.isNotEmpty()) {
                return nodes[0]
            }
        }

        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (!queue.isEmpty()) {
            val node = queue.poll() ?: continue
            val className = node.className?.toString() ?: ""
            if (className.contains("RecyclerView") || className.contains("ListView")) {
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        val clickableNode = findClickableProfileCardDescendant(child)
                        if (clickableNode != null) {
                            return clickableNode
                        }
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findClickableProfileCardDescendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val id = node.viewIdResourceName ?: ""
        if (id.contains("qr_code") || id.contains("add_account") || id.contains("multi_account") || id.contains("plus")) {
            return null
        }
        
        if (node.isClickable) {
            return node
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val res = findClickableProfileCardDescendant(child)
                if (res != null) return res
            }
        }
        return null
    }

    private fun resolveWhatsAppNodeHeuristically(root: AccessibilityNodeInfo, step: com.example.jago.logic.MacroStep): AccessibilityNodeInfo? {
        val targetId = step.targetId ?: ""
        val targetText = step.targetText ?: ""
        val desc = step.contentDescription ?: ""
        
        if (targetId.contains("settings_profile") || targetId.contains("profile_settings") || targetId.contains("profile_info") || 
            targetText.contains("profile", ignoreCase = true) || targetText.contains("name", ignoreCase = true)) {
            Log.d("JagoAccessibility", "Heuristic: Resolving settings profile card row")
            return findWhatsAppProfileCardRow(root)
        }
        
        if (targetId.contains("profile_photo") || targetId.contains("profile_image") || targetId.contains("change_photo") || 
            targetId.contains("transition_photo") || targetId.contains("photo") || targetText.contains("photo", ignoreCase = true)) {
            Log.d("JagoAccessibility", "Heuristic: Resolving profile photo button")
            return findWhatsAppProfilePhotoNode(root)
        }
        
        if (targetId.contains("edit") || targetId.contains("camera") || desc.contains("edit", ignoreCase = true) || 
            desc.contains("change", ignoreCase = true) || targetText.contains("edit", ignoreCase = true)) {
            Log.d("JagoAccessibility", "Heuristic: Resolving edit photo button")
            return findWhatsAppEditPhotoButton(root)
        }
        
        if (targetText.contains("remove", ignoreCase = true) || targetText.contains("delete", ignoreCase = true) || 
            targetId.contains("delete") || targetId.contains("remove") || targetId.contains("trash")) {
            Log.d("JagoAccessibility", "Heuristic: Resolving remove/delete photo option")
            return findWhatsAppRemovePhotoButton(root)
        }
        
        if (targetId.contains("button1") || targetText.contains("remove", ignoreCase = true) || targetText.contains("delete", ignoreCase = true) || 
            targetText.contains("confirm", ignoreCase = true) || targetText.contains("ok", ignoreCase = true)) {
            Log.d("JagoAccessibility", "Heuristic: Resolving confirmation dialog button")
            return findWhatsAppConfirmRemoveButton(root)
        }
        
        return null
    }

    override fun onInterrupt() {
        try {
            Log.d("JagoAccessibility", "Service Interrupted")
        } catch (e: Exception) {
            Log.e("JagoAccessibility", "Error in onInterrupt", e)
        }
    }

    override fun onDestroy() {
        try {
            super.onDestroy()
            serviceScope.cancel()
            instance = null
        } catch (e: Exception) {
            Log.e("JagoAccessibility", "Error in onDestroy", e)
        }
    }
}
