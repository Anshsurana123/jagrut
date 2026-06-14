// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago

import android.app.Application
import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.jago.logic.BhashiniClient
import com.example.jago.logic.JagoTTS
import com.example.jago.logic.MongoDBClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class JagoApp : Application() {
    companion object {
        const val CHANNEL_ID = "JagoServiceChannel"
    }

    private val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // Load MongoDB preference
        val prefs = getSharedPreferences("JagoSettings", Context.MODE_PRIVATE)
        MongoDBClient.isMongoDBEnabled = prefs.getBoolean("mongodb_enabled", true)
        
        // Initialize JagoTTS on main thread so TextToSpeech binds properly
        JagoTTS.init(this)

        // Initialize BhashiniClient asynchronously
        applicationScope.launch {
            BhashiniClient.init(this@JagoApp)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Jago Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
