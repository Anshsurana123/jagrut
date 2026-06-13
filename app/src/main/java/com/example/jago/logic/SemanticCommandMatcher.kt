// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.logic

import android.content.Context
import android.util.Log
import com.example.jago.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

/**
 * SemanticCommandMatcher — An embedding-based semantic matching layer that sits between
 * FuzzyCommandMatcher (~1ms) and Gemini API (~20s) in the routing pipeline.
 *
 * Pipeline position:
 *   Exact Match (0ms) → Fuzzy (1ms) → Semantic Embedding (~1-2s) → Gemini API (20s)
 *
 * How it works:
 * 1. Each of the 62 CommandTypes has a rich natural-language description
 * 2. On first use, embeddings are generated for all descriptions via Gemini Embedding API
 *    (single batch call) and cached to disk (~forever)
 * 3. On each query, the user's text is embedded (1 API call, ~1-2s)
 * 4. Cosine similarity is computed against all cached command embeddings
 * 5. If the best match exceeds the threshold → the command is returned instantly
 */
object SemanticCommandMatcher {
    private const val TAG = "SemanticCommandMatcher"
    private const val CACHE_FILE_NAME = "semantic_command_embeddings.json"
    private const val SIMILARITY_THRESHOLD = 0.75f
    private const val EMBEDDING_MODEL = "gemini-embedding-001"

    /**
     * Result of a semantic match attempt.
     */
    data class SemanticMatchResult(
        val commandType: CommandType,
        val similarity: Float,
        val matchedDescription: String
    )

    // ─── Command Descriptions ──────────────────────────────────────────────
    // Each CommandType maps to a rich description capturing diverse ways a user
    // might express that intent. More diverse = better embedding coverage.
    private val commandDescriptions: Map<CommandType, String> = mapOf(
        // Connectivity
        CommandType.TOGGLE_WIFI_ON to "turn on wifi enable wifi connect to wireless internet wifi chalu karo",
        CommandType.TOGGLE_WIFI_OFF to "turn off wifi disable wifi disconnect wireless internet wifi band karo",
        CommandType.TOGGLE_BLUETOOTH_ON to "turn on bluetooth enable bluetooth pair bluetooth bluetooth chalu karo",
        CommandType.TOGGLE_BLUETOOTH_OFF to "turn off bluetooth disable bluetooth disconnect bluetooth bluetooth band karo",
        CommandType.TOGGLE_AIRPLANE_ON to "turn on airplane mode enable flight mode airplane mode chalu karo",
        CommandType.TOGGLE_AIRPLANE_OFF to "turn off airplane mode disable flight mode airplane mode band karo",

        // Flashlight
        CommandType.FLASHLIGHT_ON to "turn on flashlight enable torch light jala do batti on karo",
        CommandType.FLASHLIGHT_OFF to "turn off flashlight disable torch light bujha do batti band karo",
        CommandType.QUERY_FLASHLIGHT to "is the flashlight on is the torch on check flashlight status flashlight chalu hai kya batti on hai kya",

        // Volume
        CommandType.VOLUME_UP to "increase volume louder turn up the sound awaz badha do volume zyada karo",
        CommandType.VOLUME_DOWN to "decrease volume quieter turn down the sound awaz kam karo volume ghata do",
        CommandType.VOLUME_MUTE to "mute volume silence the sound awaz band karo mute karo",
        CommandType.VOLUME_MAX to "maximum volume full volume volume poora karo sabse zyada awaz",
        CommandType.QUERY_VOLUME to "what is the volume level how loud is it kitni awaz hai volume kitna hai",

        // Brightness
        CommandType.BRIGHTNESS_INCREASE to "increase brightness make screen brighter chamak badha do screen aur chamkao",
        CommandType.BRIGHTNESS_DECREASE to "decrease brightness make screen dimmer chamak kam karo screen dhundla karo",
        CommandType.BRIGHTNESS_MAX to "maximum brightness full brightness poori chamak brightness poora karo",
        CommandType.QUERY_BRIGHTNESS to "what is the brightness level kitni chamak hai brightness kitna hai",

        // Device Info
        CommandType.DEVICE_INFO to "what phone is this device information phone model specifications phone ki jankari",
        CommandType.STORAGE_CHECK to "how much storage space is left check memory available free space kitni jagah bachi hai",
        CommandType.TIME_CHECK to "what time is it tell me the current time kitne baje hain time kya hai abhi samay kya hai",
        CommandType.DATE_CHECK to "what is today's date what day is it aaj ki date aaj kaun sa din hai tarikh batao",
        CommandType.BATTERY_CHECK to "how much battery is left check battery percentage charge level battery kitni hai",

        // Device Control
        CommandType.LOCK_DEVICE to "lock the phone lock screen phone band karo lock karo",
        CommandType.TAKE_SCREENSHOT to "take a screenshot capture screen screenshot lo screen capture karo",
        CommandType.RESTART_PHONE to "restart the phone reboot phone phone restart karo dobara chalu karo",
        CommandType.POWER_OFF to "power off phone shut down phone band karo switch off karo",

        // Media
        CommandType.PLAY_MEDIA to "play music resume playback start playing gaana chalao music bajao",
        CommandType.PAUSE_MEDIA to "pause music pause playback stop playing gaana roko music rok do",
        CommandType.STOP_MEDIA to "stop music stop all playback music band karo gaana band karo",
        CommandType.NEXT_MEDIA to "next song skip track agla gaana next track chalao",
        CommandType.PREVIOUS_MEDIA to "previous song go back track pichla gaana pehle wala chalao",
        CommandType.PLAY_SPOTIFY to "play on spotify open spotify and play spotify pe chalao",

        // DND / Modes
        CommandType.ENABLE_DND to "enable do not disturb turn on dnd silence notifications disturb mat karo",
        CommandType.DISABLE_DND to "disable do not disturb turn off dnd allow notifications dnd band karo",
        CommandType.SILENT_MODE to "silent mode put phone on silent phone chup karo shant mode",
        CommandType.FOCUS_MODE to "enable focus mode concentration mode study mode padhai mode laga do",

        // Navigation & Apps
        CommandType.OPEN_MAPS to "open maps navigate directions show map rasta batao directions do navigate karo",
        CommandType.OPEN_CALENDAR to "open calendar show my schedule calendar dikhao mera calendar kholo",
        CommandType.OPEN_CONTACTS to "open contacts show phone book contacts dikhao phone book kholo",
        CommandType.OPEN_CLOCK to "open clock open timer start stopwatch ghadi kholo timer lagao stopwatch shuru karo",
        CommandType.OPEN_SETTINGS to "open settings phone settings settings dikhao settings kholo",
        CommandType.OPEN_DIALER to "open phone dialer keypad dial pad phone lagao dialer kholo",
        CommandType.OPEN_WHATSAPP to "open whatsapp launch whatsapp whatsapp kholo whatsapp chalu karo whatsapp app",
        CommandType.OPEN_APP to "open app launch application app kholo app start karo start application run application open",
        CommandType.CLOSE_APP to "close app exit application stop app terminate app app band karo exit karo app close karo",

        // Communication
        CommandType.CALL to "call contact place a phone call ring someone dial a phone number phone milao call lagao call details",
        CommandType.REDIAL to "redial last number call again phir se call karo wapas call karo dobara call",
        CommandType.SPEAKER_PHONE to "turn on speaker phone loudspeaker speaker chalu karo speaker on karo",
        CommandType.SEND_WHATSAPP_MESSAGE to "send whatsapp message message on whatsapp whatsapp message bhejo whatsapp par text karo send message whatsapp",
        CommandType.SEND_TELEGRAM_MESSAGE to "send telegram message message on telegram telegram message bhejo telegram par text karo telegram send",
        CommandType.SEND_EMAIL to "send email send mail write email email bhejo mail karo compose mail send email to",
        CommandType.TAKE_VIDEO_AND_SEND to "take a video and send record video and share video banakar bhejo video capture send",
        CommandType.TAKE_PHOTO_AND_SEND to "take a photo and send capture picture and share photo khich kar bhejo photo capture send",
        CommandType.SCREENSHOT_AND_WHATSAPP to "take screenshot and send on whatsapp screenshot share on whatsapp screenshot le kar whatsapp par bhejo screen capture whatsapp",
        CommandType.SEND_RECENT_PHOTO to "send recent photo share latest picture recent pic bhejo latest photo send karo image share",

        // Clipboard
        CommandType.COPY_TO_CLIPBOARD to "copy text to clipboard clipboard mein copy karo copy karo save karo",
        CommandType.READ_CLIPBOARD to "read clipboard what is copied clipboard mein kya hai paste kya hai clipboard padhao",
        CommandType.SHARE_TEXT to "share this text send text share karo text bhejo",

        // System Toggles
        CommandType.TOGGLE_AUTO_ROTATE to "toggle auto rotate screen rotation auto rotate chalu band karo rotation lock",
        CommandType.TOGGLE_HOTSPOT_ON to "turn on hotspot enable tethering wifi sharing hotspot chalu karo internet share karo",
        CommandType.TOGGLE_HOTSPOT_OFF to "turn off hotspot disable tethering stop sharing hotspot band karo",
        CommandType.TOGGLE_LOCATION to "toggle location gps on off location services enable disable gps chalu band karo",
        CommandType.OPEN_WIFI_SETTINGS to "open wifi settings wifi config wireless settings wifi ki setting kholo wifi configuration",
        CommandType.OPEN_BLUETOOTH_SETTINGS to "open bluetooth settings pair settings bluetooth configuration bluetooth ki setting kholo bluetooth configuration",

        // Camera
        CommandType.CLICK_PHOTO to "take a photo capture picture click photo tasveer lo photo khicho",

        // Notifications & Screen
        CommandType.READ_NOTIFICATIONS to "read my notifications what are my notifications notification padhao",
        CommandType.READ_SCREEN to "read the screen what is on screen screen padho screen kya dikh raha hai",

        // Search & Utilities
        CommandType.SEARCH to "search for something look up find information search karo dhundho",
        CommandType.CALCULATE to "calculate math solve expression equation plus minus multiply divide hisab karo calculate karo math solver",
        CommandType.SET_REMINDER to "set a reminder remind me notify me of task reminder lagao yaad dilana schedule reminder",
        CommandType.SET_ALARM to "set an alarm wake me up alarm lagao alarm set karo wake up alarm alarm clock",
        CommandType.SET_ALARM_CUSTOM to "set a custom alarm alarm for specific time custom alarm set karo specific alarm",
        CommandType.SCHEDULED_ACTION to "schedule an action do this later schedule task timed action bad mein karo schedule karo delay action",
        CommandType.OPEN_SCHEDULE to "open schedule show scheduled actions pending tasks list schedule kholo calendar schedule dekho schedule list",
        CommandType.SET_LANGUAGE to "set language speak in english speak in hindi bhasha badlo language set karo change speaking language",

        // AI & Fallback
        CommandType.AI_RESPONSE to "conversational chat chat with assistant ask assistant talk to AI talk with me ask query chat"
    )

    /**
     * Attempts semantic matching of the query against all known command descriptions.
     * Returns the best matching CommandType if similarity exceeds threshold.
     *
     * @param context Android context for file caching
     * @param queryText The user's raw voice input
     * @return SemanticMatchResult if a confident match is found, null otherwise
     */
    suspend fun findBestMatch(context: Context, queryText: String): SemanticMatchResult? {
        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            Log.w(TAG, "No API key, skipping semantic matching")
            return null
        }

        try {
            // 1. Generate embedding for the user's query (~1-2s)
            val queryEmbedding = generateEmbedding(queryText) ?: return null

            // 2. Load or generate cached command embeddings
            val commandEmbeddings = getOrGenerateCommandEmbeddings(context) ?: return null

            // 3. Find best cosine similarity match
            var bestType: CommandType? = null
            var bestSimilarity = 0f
            var bestDescription = ""

            for ((commandType, embedding) in commandEmbeddings) {
                val similarity = cosineSimilarity(queryEmbedding, embedding)
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestType = commandType
                    bestDescription = commandDescriptions[commandType] ?: ""
                }
            }

            return if (bestType != null && bestSimilarity >= SIMILARITY_THRESHOLD) {
                if (isIncompatibleMatch(queryText, bestType)) {
                    Log.d(TAG, "⚠️ Semantic match rejected as incompatible: '$queryText' -> $bestType")
                    null
                } else {
                    Log.d(TAG, "✅ Semantic Match! '$queryText' → $bestType (similarity=${String.format("%.3f", bestSimilarity)}, desc='${bestDescription.take(50)}...')")
                    SemanticMatchResult(bestType, bestSimilarity, bestDescription)
                }
            } else {
                if (bestType != null) {
                    Log.d(TAG, "❌ Below threshold: '$queryText' → best=$bestType (similarity=${String.format("%.3f", bestSimilarity)}, threshold=$SIMILARITY_THRESHOLD)")
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Semantic matching failed", e)
            return null
        }
    }

    // ─── Embedding Cache ───────────────────────────────────────────────────

    /**
     * Loads cached command embeddings from disk, or generates them via a single
     * batch API call and caches the result.
     */
    private suspend fun getOrGenerateCommandEmbeddings(
        context: Context
    ): Map<CommandType, List<Float>>? {
        // Try loading from cache first
        val cached = loadEmbeddingsFromCache(context)
        if (cached != null && cached.size == commandDescriptions.size) {
            Log.d(TAG, "Loaded ${cached.size} cached command embeddings")
            return cached
        }

        // Generate fresh embeddings via batch API
        Log.d(TAG, "Generating command embeddings for ${commandDescriptions.size} descriptions...")
        val embeddings = batchGenerateEmbeddings(commandDescriptions.values.toList())
        if (embeddings == null || embeddings.size != commandDescriptions.size) {
            Log.e(TAG, "Batch embedding generation failed or returned wrong count")
            return null
        }

        // Map back to CommandType
        val result = mutableMapOf<CommandType, List<Float>>()
        val keys = commandDescriptions.keys.toList()
        for (i in keys.indices) {
            result[keys[i]] = embeddings[i]
        }

        // Cache to disk
        saveEmbeddingsToCache(context, result)
        Log.d(TAG, "Generated and cached ${result.size} command embeddings")
        return result
    }

    private fun loadEmbeddingsFromCache(context: Context): Map<CommandType, List<Float>>? {
        return try {
            val file = File(context.filesDir, CACHE_FILE_NAME)
            if (!file.exists()) return null

            val json = JSONObject(file.readText())
            val result = mutableMapOf<CommandType, List<Float>>()

            for (key in json.keys()) {
                val commandType = try {
                    CommandType.valueOf(key)
                } catch (e: Exception) {
                    continue
                }
                val valuesArray = json.getJSONArray(key)
                val embedding = mutableListOf<Float>()
                for (i in 0 until valuesArray.length()) {
                    embedding.add(valuesArray.getDouble(i).toFloat())
                }
                result[commandType] = embedding
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached embeddings", e)
            null
        }
    }

    private fun saveEmbeddingsToCache(context: Context, embeddings: Map<CommandType, List<Float>>) {
        try {
            val json = JSONObject()
            for ((commandType, embedding) in embeddings) {
                val valuesArray = JSONArray()
                for (value in embedding) {
                    valuesArray.put(value.toDouble())
                }
                json.put(commandType.name, valuesArray)
            }
            val file = File(context.filesDir, CACHE_FILE_NAME)
            file.writeText(json.toString())
            Log.d(TAG, "Saved ${embeddings.size} command embeddings to cache (${file.length() / 1024}KB)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save embeddings cache", e)
        }
    }

    // ─── Embedding API ─────────────────────────────────────────────────────

    /**
     * Generates embedding for a single text using Gemini Embedding API.
     */
    private suspend fun generateEmbedding(text: String): List<Float>? = withContext(Dispatchers.IO) {
        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$EMBEDDING_MODEL:embedContent?key=${BuildConfig.GEMINI_API_KEY}"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                doOutput = true
                connectTimeout = 8000
                readTimeout = 10000
            }

            val body = JSONObject().apply {
                put("content", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", text)
                        })
                    })
                })
                put("taskType", "SEMANTIC_SIMILARITY")
            }.toString()

            connection.outputStream.use { it.write(body.toByteArray()) }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                val valuesArray = JSONObject(response)
                    .getJSONObject("embedding")
                    .getJSONArray("values")
                val list = mutableListOf<Float>()
                for (i in 0 until valuesArray.length()) {
                    list.add(valuesArray.getDouble(i).toFloat())
                }
                list
            } else {
                val err = connection.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "Embedding API Error ${connection.responseCode}: $err")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate query embedding", e)
            null
        }
    }

    /**
     * Generates embeddings for multiple texts in a single batch API call.
     * This is used once to pre-compute all command description embeddings.
     */
    private suspend fun batchGenerateEmbeddings(texts: List<String>): List<List<Float>>? = withContext(Dispatchers.IO) {
        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$EMBEDDING_MODEL:batchEmbedContents?key=${BuildConfig.GEMINI_API_KEY}"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 30000 // Batch may take longer
            }

            // Build batch request
            val requestsArray = JSONArray()
            for (text in texts) {
                requestsArray.put(JSONObject().apply {
                    put("model", "models/$EMBEDDING_MODEL")
                    put("content", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", text)
                            })
                        })
                    })
                    put("taskType", "SEMANTIC_SIMILARITY")
                })
            }

            val body = JSONObject().apply {
                put("requests", requestsArray)
            }.toString()

            connection.outputStream.use { it.write(body.toByteArray()) }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                val embeddingsArray = JSONObject(response).getJSONArray("embeddings")

                val result = mutableListOf<List<Float>>()
                for (i in 0 until embeddingsArray.length()) {
                    val valuesArray = embeddingsArray.getJSONObject(i).getJSONArray("values")
                    val embedding = mutableListOf<Float>()
                    for (j in 0 until valuesArray.length()) {
                        embedding.add(valuesArray.getDouble(j).toFloat())
                    }
                    result.add(embedding)
                }

                Log.d(TAG, "Batch embedding: generated ${result.size} embeddings (dim=${result.firstOrNull()?.size})")
                result
            } else {
                val err = connection.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "Batch Embedding API Error ${connection.responseCode}: $err")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Batch embedding generation failed", e)
            null
        }
    }

    private fun isIncompatibleMatch(query: String, commandType: CommandType): Boolean {
        val lowerQuery = query.lowercase().trim()
        val removalVerbs = listOf("remove", "delete", "clear", "erase", "uninstall", "reset")
        
        if (removalVerbs.none { lowerQuery.contains(it) }) {
            return false
        }
        
        val allowedForRemoval = listOf(
            CommandType.TOGGLE_WIFI_OFF,
            CommandType.TOGGLE_BLUETOOTH_OFF,
            CommandType.TOGGLE_AIRPLANE_OFF,
            CommandType.FLASHLIGHT_OFF,
            CommandType.VOLUME_DOWN,
            CommandType.VOLUME_MUTE,
            CommandType.BRIGHTNESS_DECREASE,
            CommandType.DISABLE_DND,
            CommandType.CLOSE_APP,
            CommandType.TOGGLE_HOTSPOT_OFF,
            CommandType.STOP_MEDIA,
            CommandType.PAUSE_MEDIA,
            CommandType.POWER_OFF,
            CommandType.UNKNOWN,
            CommandType.AI_RESPONSE
        )
        
        return commandType !in allowedForRemoval
    }

    // ─── Math ──────────────────────────────────────────────────────────────

    private fun cosineSimilarity(v1: List<Float>, v2: List<Float>): Float {
        if (v1.size != v2.size || v1.isEmpty()) return 0.0f
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }
        return if (normA == 0.0f || normB == 0.0f) 0.0f
        else (dotProduct / (sqrt(normA.toDouble()) * sqrt(normB.toDouble())).toFloat())
    }
}
