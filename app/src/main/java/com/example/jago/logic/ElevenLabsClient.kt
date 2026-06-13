// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.logic

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ElevenLabsClient {
    private const val TAG = "ElevenLabsClient"
    private val API_KEY: String
        get() = com.example.jago.BuildConfig.ELEVENLABS_API_KEY
    private const val VOICE_ID = "21m00Tcm4TlvDq8ikWAM" // Default Rachel voice
    private const val ENDPOINT = "https://api.elevenlabs.io/v1/text-to-speech/$VOICE_ID"

    suspend fun generateTTS(context: Context, text: String): File? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(15000L) {
                if (API_KEY.isBlank()) {
                    Log.w(TAG, "ElevenLabs API Key is blank, skipping generateTTS")
                    return@withTimeoutOrNull null
                }
                try {
                    val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("xi-api-key", API_KEY)
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Accept", "audio/mpeg")
                        doOutput = true
                        connectTimeout = 8000
                        readTimeout = 10000
                    }

                    val body = JSONObject().apply {
                        put("text", text)
                        put("model_id", "eleven_multilingual_v2")
                        put("output_format", "mp3_44100_128")
                    }.toString()

                    connection.outputStream.use { it.write(body.toByteArray()) }

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val tempFile = File(context.cacheDir, "elevenlabs_tts_${System.currentTimeMillis()}.mp3")
                        connection.inputStream.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        Log.d(TAG, "Successfully downloaded ElevenLabs audio to ${tempFile.absolutePath}")
                        tempFile
                    } else {
                        val err = connection.errorStream?.bufferedReader()?.readText()
                        Log.e(TAG, "Error ${connection.responseCode}: $err")
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ElevenLabs TTS request failed", e)
                    null
                }
            }
        }
}
