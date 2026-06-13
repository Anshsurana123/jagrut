// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.jago.logic.GeminiHistoryEngine
import com.example.jago.logic.GeminiHistoryItem
import com.example.jago.logic.GeminiIntentPayload
import java.util.Date

class GeminiActivity : AppCompatActivity() {

    private lateinit var adapter: GeminiHistoryAdapter
    private lateinit var layoutEmptyState: View
    private lateinit var btnClearHistory: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gemini)

        layoutEmptyState = findViewById(R.id.layoutEmptyState)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewHistory)

        adapter = GeminiHistoryAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnClearHistory.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Clear History")
                .setMessage("Are you sure you want to clear all Gemini response history?")
                .setPositiveButton("Clear") { _, _ ->
                    GeminiHistoryEngine.clearHistory(this)
                    loadHistory()
                    Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        loadHistory()
    }

    private fun loadHistory() {
        val history = GeminiHistoryEngine.getHistory(this)
        if (history.isEmpty()) {
            layoutEmptyState.visibility = View.VISIBLE
            btnClearHistory.visibility = View.GONE
        } else {
            layoutEmptyState.visibility = View.GONE
            btnClearHistory.visibility = View.VISIBLE
        }
        adapter.updateHistory(history)
    }

    private inner class GeminiHistoryAdapter : RecyclerView.Adapter<GeminiHistoryAdapter.ViewHolder>() {
        private var items: List<GeminiHistoryItem> = emptyList()

        fun updateHistory(newItems: List<GeminiHistoryItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtQuery: TextView = view.findViewById(R.id.txtQuery)
            val txtActionType: TextView = view.findViewById(R.id.txtActionType)
            val txtTimestamp: TextView = view.findViewById(R.id.txtTimestamp)
            val txtIntentDescription: TextView = view.findViewById(R.id.txtIntentDescription)
            val txtPayloadDetails: TextView = view.findViewById(R.id.txtPayloadDetails)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_gemini_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.txtQuery.text = "Query: \"${item.query}\""

            val date = Date(item.timestamp)
            val dateStr = android.text.format.DateFormat.format("MMM dd, yyyy HH:mm:ss", date).toString()
            holder.txtTimestamp.text = dateStr

            try {
                val payload = GeminiIntentPayload.fromJson(item.rawResponse)
                holder.txtActionType.text = payload.action_type
                holder.txtIntentDescription.text = payload.intent.ifEmpty { "No intent description" }

                // Style the tag based on Action Type
                when (payload.action_type) {
                    "AUTOMATION_SEQUENCE" -> {
                        holder.txtActionType.setTextColor(ContextCompat.getColor(this@GeminiActivity, R.color.brand_secondary))
                        holder.txtActionType.setBackgroundColor(Color.parseColor("#2206B6D4"))
                    }
                    "SHELL_COMMAND" -> {
                        holder.txtActionType.setTextColor(ContextCompat.getColor(this@GeminiActivity, R.color.brand_primary))
                        holder.txtActionType.setBackgroundColor(Color.parseColor("#227C3AED"))
                    }
                    "NATIVE_INTENT" -> {
                        holder.txtActionType.setTextColor(ContextCompat.getColor(this@GeminiActivity, R.color.green_accent))
                        holder.txtActionType.setBackgroundColor(Color.parseColor("#2210B981"))
                    }
                    "REFLECTIVE_CALL" -> {
                        holder.txtActionType.setTextColor(ContextCompat.getColor(this@GeminiActivity, R.color.amber_accent))
                        holder.txtActionType.setBackgroundColor(Color.parseColor("#22F59E0B"))
                    }
                    else -> {
                        holder.txtActionType.setTextColor(ContextCompat.getColor(this@GeminiActivity, R.color.alert_red))
                        holder.txtActionType.setBackgroundColor(Color.parseColor("#22EF4444"))
                    }
                }

                // Format Details/Steps text
                val details = StringBuilder()
                when (payload.action_type) {
                    "AUTOMATION_SEQUENCE" -> {
                        payload.steps?.forEachIndexed { index, step ->
                            details.append("${index + 1}. ${step.actionType}")
                            if (step.packageName != null) details.append(" in ${step.packageName}")
                            if (step.targetText != null) details.append(" ['${step.targetText}']")
                            if (step.targetId != null) details.append(" [id:${step.targetId}]")
                            if (step.textToEnter != null) details.append(" -> enter: \"${step.textToEnter}\"")
                            if (step.xPercent != null && step.yPercent != null) {
                                details.append(" at (${step.xPercent}%, ${step.yPercent}%)")
                            }
                            details.append("\n")
                        }
                        if (details.isEmpty()) details.append("No steps defined.")
                    }
                    "SHELL_COMMAND" -> {
                        details.append(payload.code ?: "No code defined.")
                    }
                    "NATIVE_INTENT" -> {
                        details.append("Action: ${payload.intent_action}\n")
                        if (payload.target_package != null) details.append("Package: ${payload.target_package}\n")
                        if (!payload.intent_flags.isNullOrEmpty()) details.append("Flags: ${payload.intent_flags.joinToString(", ")}\n")
                        if (!payload.extras.isNullOrEmpty()) {
                            details.append("Extras:\n")
                            payload.extras.forEach { (k, v) -> details.append("  $k = $v\n") }
                        }
                    }
                    "REFLECTIVE_CALL" -> {
                        details.append("Class: ${payload.reflective_class}\n")
                        details.append("Method: ${payload.reflective_method}\n")
                        if (!payload.reflective_args.isNullOrEmpty()) {
                            details.append("Args: ${payload.reflective_args.joinToString(", ")}")
                        }
                    }
                    else -> {
                        details.append("UNKNOWN action or conversational response.")
                    }
                }
                holder.txtPayloadDetails.text = details.toString().trim()

            } catch (e: Exception) {
                holder.txtActionType.text = "ERROR"
                holder.txtActionType.setTextColor(ContextCompat.getColor(this@GeminiActivity, R.color.alert_red))
                holder.txtActionType.setBackgroundColor(Color.parseColor("#22EF4444"))
                holder.txtIntentDescription.text = "Failed to parse Gemini payload response."
                holder.txtPayloadDetails.text = item.rawResponse
            }
        }

        override fun getItemCount() = items.size
    }
}
