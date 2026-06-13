// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.logic

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object SarvamClient {
    private const val TAG = "SarvamClient"
    private val API_KEY: String
        get() = com.example.jago.BuildConfig.SARVAM_API_KEY
    private const val ENDPOINT = "https://api.sarvam.ai/v1/chat/completions"
    private const val MODEL = "sarvam-30b"


    suspend fun askAI(query: String): String? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(15000L) {
                if (API_KEY.isBlank()) {
                    Log.w(TAG, "Sarvam API Key is blank, skipping askAI")
                    return@withTimeoutOrNull null
                }
                try {
                    val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Authorization", "Bearer $API_KEY")
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Accept", "application/json")
                        doOutput = true
                        connectTimeout = 8000
                        readTimeout = 10000
                    }

                    val body = JSONObject().apply {
                        put("model", MODEL)
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "system")
                                put("content", "You are a helpful voice assistant named Jagrut. Respond in 1-2 short sentences maximum. Be extremely concise. Use Hinglish or English as appropriate for an Indian user.")
                            })
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", query)
                            })
                        })
                        put("max_tokens", 100)
                    }.toString()

                    connection.outputStream.use { it.write(body.toByteArray()) }

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val response = connection.inputStream
                            .bufferedReader()
                            .use(BufferedReader::readText)
                        JSONObject(response)
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                            .trim()
                    } else {
                        val err = connection.errorStream?.bufferedReader()?.readText()
                        Log.e(TAG, "Error ${connection.responseCode}: $err")
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Sarvam failed", e)
                    null
                }
            }
        }

    suspend fun generateTTS(context: Context, text: String, languageCode: String): File? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(15000L) {
                if (API_KEY.isBlank()) {
                    Log.w(TAG, "Sarvam API Key is blank, skipping generateTTS")
                    return@withTimeoutOrNull null
                }
                try {
                    val connection = (URL("https://api.sarvam.ai/text-to-speech").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("api-subscription-key", API_KEY)
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Accept", "application/json")
                        doOutput = true
                        connectTimeout = 8000
                        readTimeout = 10000
                    }

                    val body = JSONObject().apply {
                        put("text", text)
                        put("target_language_code", languageCode)
                        put("speaker", "meera")
                        put("pace", 1.0)
                    }.toString()

                    connection.outputStream.use { it.write(body.toByteArray()) }

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(response)
                        val audios = json.getJSONArray("audios")
                        if (audios.length() > 0) {
                            val audioContent = audios.getString(0)
                            val bytes = android.util.Base64.decode(audioContent, android.util.Base64.DEFAULT)
                            val tempFile = File(context.cacheDir, "sarvam_tts_${System.currentTimeMillis()}.wav")
                            tempFile.writeBytes(bytes)
                            Log.d(TAG, "Successfully downloaded Sarvam audio to ${tempFile.absolutePath}")
                            tempFile
                        } else {
                            Log.e(TAG, "Sarvam TTS returned empty audios array")
                            null
                        }
                    } else {
                        val err = connection.errorStream?.bufferedReader()?.readText()
                        Log.e(TAG, "Error ${connection.responseCode}: $err")
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Sarvam TTS request failed", e)
                    null
                }
            }
        }

    suspend fun transcribeAudio(audioFile: File, languageCode: String? = null): String? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(20000L) {
                if (API_KEY.isBlank()) {
                    Log.w(TAG, "Sarvam API Key is blank, skipping speech-to-text")
                    return@withTimeoutOrNull null
                }
                try {
                    val boundary = "Boundary-" + System.currentTimeMillis()
                    val LINE_FEED = "\r\n"
                    val connection = (URL("https://api.sarvam.ai/speech-to-text").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("api-subscription-key", API_KEY)
                        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                        doOutput = true
                        connectTimeout = 8000
                        readTimeout = 12000
                    }

                    connection.outputStream.use { outputStream ->
                        val writer = outputStream.writer(Charsets.UTF_8)
                        
                        // File parameter
                        writer.write("--$boundary$LINE_FEED")
                        writer.write("Content-Disposition: form-data; name=\"file\"; filename=\"${audioFile.name}\"$LINE_FEED")
                        writer.write("Content-Type: audio/wav$LINE_FEED$LINE_FEED")
                        writer.flush()
                        
                        audioFile.inputStream().use { input ->
                            input.copyTo(outputStream)
                        }
                        outputStream.flush()
                        writer.write(LINE_FEED)
                        
                        // Optional language_code parameter
                        if (!languageCode.isNullOrEmpty()) {
                            writer.write("--$boundary$LINE_FEED")
                            writer.write("Content-Disposition: form-data; name=\"language_code\"$LINE_FEED$LINE_FEED")
                            writer.write("$languageCode$LINE_FEED")
                        }
                        
                        // End of multipart
                        writer.write("--$boundary--$LINE_FEED")
                        writer.flush()
                    }

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(response)
                        json.optString("transcript", "").trim()
                    } else {
                        val err = connection.errorStream?.bufferedReader()?.readText()
                        Log.e(TAG, "Sarvam STT Error ${connection.responseCode}: $err")
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Sarvam STT request failed", e)
                    null
                }
            }
        }
}
