// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.ui

import android.util.Log

data class TelemetryEvent(
    val source: String,         // "Local", "Gemini", "Sarvam", "ElevenLabs"
    val latencyMs: Long,        // 0 for local, actual ms for network
    val routingPath: String,    // "Local match → PLAY_MEDIA", etc.
    val rawAction: String       // "AudioManager.setStreamVolume" or intent action string
)

object AssistantUIBridge {
    private const val TAG = "AssistantUIBridge"

    interface AssistantUIListener {
        fun onUpdateStatus(text: String)
        fun onUpdatePartial(text: String)
        fun onTelemetryUpdate(event: TelemetryEvent)
        fun onClose()
    }

    private var listener: AssistantUIListener? = null
    
    // Maintain state for activity re-creation
    var statusText: String = "Listening..."
        private set
    var partialText: String = ""
        private set
    var isProcessing: Boolean = false
        private set
    var lastTelemetryEvent: TelemetryEvent? = null
        private set

    fun setListener(newListener: AssistantUIListener) {
        listener = newListener
        // Immediately sync state
        listener?.onUpdateStatus(statusText)
        listener?.onUpdatePartial(partialText)
        lastTelemetryEvent?.let { listener?.onTelemetryUpdate(it) }
    }

    fun removeListener() {
        listener = null
    }

    fun updateStatus(text: String) {
        statusText = text
        listener?.onUpdateStatus(text)
    }

    fun updatePartial(text: String) {
        partialText = text
        listener?.onUpdatePartial(text)
    }

    fun emitTelemetry(event: TelemetryEvent) {
        lastTelemetryEvent = event
        listener?.onTelemetryUpdate(event)
    }

    fun closeUI() {
        Log.d(TAG, "Requesting UI closure")
        statusText = "Listening..." // Reset for next time
        partialText = ""
        lastTelemetryEvent = null
        listener?.onClose()
    }
}
