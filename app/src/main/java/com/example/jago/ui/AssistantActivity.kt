// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.ui

import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.jago.R
import com.example.jago.logic.JagoTTS

class AssistantActivity : AppCompatActivity(), AssistantUIBridge.AssistantUIListener {

    private lateinit var statusText: TextView
    private lateinit var partialText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutTelemetry: android.widget.LinearLayout
    private lateinit var txtTelemetry: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.assistant_overlay) // Reusing the overlay layout

        statusText = findViewById(R.id.overlayStatusText)
        partialText = findViewById(R.id.overlayPartialText)
        progressBar = findViewById(R.id.overlayProgressBar)
        layoutTelemetry = findViewById(R.id.layoutTelemetry)
        txtTelemetry = findViewById(R.id.txtTelemetry)

        findViewById<android.view.View>(R.id.btnSchedule).setOnClickListener {
             val intent = android.content.Intent(this, com.example.jago.ui.ScheduledTasksActivity::class.java).apply {
                 addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
             }
             startActivity(intent)
             finish()
        }

        // Register with Bridge
        AssistantUIBridge.setListener(this)

        // Handle Back Press
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (JagoTTS.isSpeaking) {
                    JagoTTS.stopSpeaking()
                }
                finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Ensure listener is active if activity was paused
        AssistantUIBridge.setListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        AssistantUIBridge.removeListener()
    }

    // Bridge Callbacks
    override fun onUpdateStatus(text: String) {
        runOnUiThread {
            statusText.text = text
            // Dynamic premium color shifting based on state
            when {
                text.contains("Error", ignoreCase = true) -> {
                    progressBar.indeterminateTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(this, R.color.alert_red)
                    )
                }
                text.contains("Processing", ignoreCase = true) || text.contains("Speaking", ignoreCase = true) -> {
                    progressBar.indeterminateTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(this, R.color.brand_primary)
                    )
                }
                else -> {
                    progressBar.indeterminateTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(this, R.color.brand_secondary)
                    )
                }
            }
        }
    }

    override fun onUpdatePartial(text: String) {
        runOnUiThread {
            partialText.text = text
        }
    }

    override fun onTelemetryUpdate(event: TelemetryEvent) {
        runOnUiThread {
            layoutTelemetry.visibility = android.view.View.VISIBLE
            val latencyStr = if (event.latencyMs > 0) "${event.latencyMs}ms" else "<1ms"
            txtTelemetry.text = "[${event.source}] $latencyStr\n${event.routingPath}\n${event.rawAction}"
        }
    }

    override fun onClose() {
        runOnUiThread {
            layoutTelemetry.visibility = android.view.View.GONE
            finish()
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                 overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                 @Suppress("DEPRECATION")
                 overridePendingTransition(0, 0)
            }
        }
    }
}
