// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.jago.R
import com.example.jago.ResearchActivity
import com.example.jago.logic.ResearchHistoryEngine

class ResearchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.example.jago.ADD_RESEARCH") {
            val title = intent.getStringExtra("title") ?: "Daily Research"
            val pdfBase64 = intent.getStringExtra("pdf") ?: ""
            Log.i("ResearchReceiver", "Received ADD_RESEARCH broadcast: $title")
            
            if (pdfBase64.isNotEmpty()) {
                try {
                    val pdfBytes = Base64.decode(pdfBase64, Base64.DEFAULT)
                    ResearchHistoryEngine.saveResearchPdf(context, title, pdfBytes)
                    showResearchNotification(context, title)
                } catch (e: Exception) {
                    Log.e("ResearchReceiver", "Failed to decode/save PDF from broadcast", e)
                }
            } else {
                Log.w("ResearchReceiver", "Received broadcast with empty PDF content")
            }
        }
    }

    companion object {
        fun showResearchNotification(context: Context, title: String) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "JagoResearchChannel",
                    "Jago Research Reports",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifications for new daily research reports"
                }
                notificationManager.createNotificationChannel(channel)
            }

            val intent = Intent(context, ResearchActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, "JagoResearchChannel")
                .setContentTitle("New Research Available")
                .setContentText(title)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(1002, notification)
        }
    }
}
