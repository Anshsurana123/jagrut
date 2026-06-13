package com.example.jago.logic

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object BhashiniClient {

    private const val TAG = "BhashiniClient"
    private const val CONFIG_URL = "https://meity-auth.ulcacontrib.org/ulca/apis/v0/model/getModelsPipeline"
    private const val PIPELINE_ID = "64392f96daac500b55c543cd"

    // Populated from BuildConfig — set during init()
    private var userId: String = ""
    private var ulcaApiKey: String = ""
    private var appContext: Context? = null

    // Cached from Pipeline Config Call
    @Volatile private var isInitialized = false
    @Volatile private var isAvailable = false  // false if config call failed
    private val initMutex = Mutex()

    private var inferenceEndpoint: String = "https://dhruva-api.bhashini.gov.in/services/inference/pipeline"
    private var inferenceAuthValue: String = ""
    private val asrServiceIds = mutableMapOf<String, String>()
    private val ttsServiceIds = mutableMapOf<String, String>()
    private val nmtServiceIds = mutableMapOf<Pair<String, String>, String>()

    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext
        initMutex.withLock {
            if (isInitialized) return@withContext
            appContext = context.applicationContext
            userId = com.example.jago.BuildConfig.BHASHINI_USER_ID
            ulcaApiKey = com.example.jago.BuildConfig.BHASHINI_ULCA_API_KEY

            if (userId.isBlank() || ulcaApiKey.isBlank()) {
                Log.w(TAG, "Bhashini credentials not set — Bhashini features disabled")
                isInitialized = true
                isAvailable = false
                return@withContext
            }

            try {
                val body = JSONObject().apply {
                    put("pipelineTasks", JSONArray().apply {
                        put(JSONObject().put("taskType", "asr"))
                        put(JSONObject().put("taskType", "translation"))
                        put(JSONObject().put("taskType", "tts"))
                    })
                    put("pipelineRequestConfig", JSONObject().put("pipelineId", PIPELINE_ID))
                }

                val conn = (URL(CONFIG_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("userID", userId)
                    setRequestProperty("ulcaApiKey", ulcaApiKey)
                    connectTimeout = 8000
                    readTimeout = 10000
                    doOutput = true
                    outputStream.write(body.toString().toByteArray())
                }

                val responseCode = conn.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "Config call failed: HTTP $responseCode")
                    isInitialized = true
                    isAvailable = false
                    return@withContext
                }

                val responseText = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(responseText)

                // Parse inference endpoint
                val endpointObj = json.getJSONObject("pipelineInferenceAPIEndPoint")
                inferenceEndpoint = endpointObj.getString("callbackUrl")
                inferenceAuthValue = endpointObj.getJSONObject("inferenceApiKey").getString("value")

                // Parse service IDs
                val configs = json.getJSONArray("pipelineResponseConfig")
                for (i in 0 until configs.length()) {
                    val taskObj = configs.getJSONObject(i)
                    val taskType = taskObj.getString("taskType")
                    val configArr = taskObj.getJSONArray("config")
                    for (j in 0 until configArr.length()) {
                        val cfg = configArr.getJSONObject(j)
                        val serviceId = cfg.getString("serviceId")
                        val lang = cfg.getJSONObject("language")
                        when (taskType) {
                            "asr" -> asrServiceIds[lang.getString("sourceLanguage")] = serviceId
                            "tts" -> ttsServiceIds[lang.getString("sourceLanguage")] = serviceId
                            "translation" -> {
                                val src = lang.getString("sourceLanguage")
                                val tgt = lang.optString("targetLanguage", "")
                                if (tgt.isNotEmpty()) nmtServiceIds[src to tgt] = serviceId
                            }
                        }
                    }
                }

                Log.d(TAG, "Init success. TTS langs: ${ttsServiceIds.keys}, NMT pairs: ${nmtServiceIds.keys}")
                isAvailable = true
            } catch (e: Exception) {
                Log.e(TAG, "Init failed", e)
                isAvailable = false
            } finally {
                isInitialized = true
            }
        }
    }

    suspend fun tts(text: String, language: String = "hi", gender: String = "female"): File? = withContext(Dispatchers.IO) {
        ensureInitialized()
        if (!isAvailable) return@withContext null

        val serviceId = ttsServiceIds[language]
        if (serviceId == null) {
            Log.w(TAG, "No TTS serviceId for language: $language")
            return@withContext null
        }

        try {
            val body = JSONObject().apply {
                put("pipelineTasks", JSONArray().apply {
                    put(JSONObject().apply {
                        put("taskType", "tts")
                        put("config", JSONObject().apply {
                            put("language", JSONObject().put("sourceLanguage", language))
                            put("serviceId", serviceId)
                            put("gender", gender)
                        })
                    })
                })
                put("inputData", JSONObject().apply {
                    put("input", JSONArray().apply {
                        put(JSONObject().put("source", text))
                    })
                    put("audio", JSONArray().apply {
                        put(JSONObject().put("audioContent", JSONObject.NULL))
                    })
                })
            }

            val response = postCompute(body) ?: return@withContext null
            val audioContent = response
                .getJSONArray("pipelineResponse")
                .getJSONObject(0)
                .getJSONArray("audio")
                .getJSONObject(0)
                .getString("audioContent")

            val ctx = appContext ?: return@withContext null
            val bytes = Base64.decode(audioContent, Base64.DEFAULT)
            val file = File(ctx.cacheDir, "bhashini_tts_${System.currentTimeMillis()}.wav")
            file.writeBytes(bytes)
            Log.d(TAG, "TTS success: ${bytes.size} bytes for text '$text'")
            file
        } catch (e: Exception) {
            Log.e(TAG, "TTS failed for text '$text'", e)
            null
        }
    }

    suspend fun translateToDevanagari(text: String): String? = withContext(Dispatchers.IO) {
        ensureInitialized()
        if (!isAvailable) return@withContext null
        translate(text, "en", "hi")
    }

    suspend fun translateToEnglish(text: String): String? = withContext(Dispatchers.IO) {
        ensureInitialized()
        if (!isAvailable) return@withContext null
        translate(text, "hi", "en")
    }

    private suspend fun translate(text: String, sourceLang: String, targetLang: String): String? {
        val serviceId = nmtServiceIds[sourceLang to targetLang]
        if (serviceId == null) {
            Log.w(TAG, "No NMT serviceId for $sourceLang → $targetLang")
            return null
        }

        return try {
            val body = JSONObject().apply {
                put("pipelineTasks", JSONArray().apply {
                    put(JSONObject().apply {
                        put("taskType", "translation")
                        put("config", JSONObject().apply {
                            put("language", JSONObject().apply {
                                put("sourceLanguage", sourceLang)
                                put("targetLanguage", targetLang)
                            })
                            put("serviceId", serviceId)
                        })
                    })
                })
                put("inputData", JSONObject().apply {
                    put("input", JSONArray().apply {
                        put(JSONObject().put("source", text))
                    })
                    put("audio", JSONArray().apply {
                        put(JSONObject().put("audioContent", JSONObject.NULL))
                    })
                })
            }

            val response = postCompute(body) ?: return null
            val target = response
                .getJSONArray("pipelineResponse")
                .getJSONObject(0)
                .getJSONArray("output")
                .getJSONObject(0)
                .getString("target")

            Log.d(TAG, "Translation $sourceLang→$targetLang: '$text' → '$target'")
            target.ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "Translation $sourceLang→$targetLang failed for '$text'", e)
            null
        }
    }

    private fun postCompute(body: JSONObject): JSONObject? {
        return try {
            val conn = (URL(inferenceEndpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", inferenceAuthValue)
                connectTimeout = 6000
                readTimeout = 12000
                doOutput = true
                outputStream.write(body.toString().toByteArray())
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "no body"
                Log.e(TAG, "Compute call failed: HTTP $code — $error")
                return null
            }
            JSONObject(conn.inputStream.bufferedReader().readText())
        } catch (e: Exception) {
            Log.e(TAG, "postCompute network error", e)
            null
        }
    }

    private suspend fun ensureInitialized() {
        if (!isInitialized) {
            val ctx = appContext
            if (ctx != null) init(ctx)
        }
    }
}
