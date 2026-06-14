// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.logic

import android.util.Log
import com.example.jago.BuildConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bson.Document
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.json.JSONArray

object MongoDBClient {
    private const val TAG = "MongoDBClient"
    private val gson = Gson()
    
    @Volatile
    var isMongoDBEnabled: Boolean = true
    
    private val mongoClientRef = AtomicReference<MongoClient?>(null)

    private fun getClient(): MongoClient? {
        val connStr = BuildConfig.MONGODB_CONNECTION_STRING
        if (connStr.isBlank()) {
            return null
        }
        val current = mongoClientRef.get()
        if (current != null) {
            return current
        }
        synchronized(this) {
            val syncCurrent = mongoClientRef.get()
            if (syncCurrent != null) {
                return syncCurrent
            }
            return try {
                val resolvedConnStr = resolveSrvConnectionString(connStr)
                val client = MongoClients.create(resolvedConnStr)
                mongoClientRef.set(client)
                client
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to create MongoClient (often JNDI/SRV resolution is unsupported on Android)", e)
                null
            }
        }
    }

    private fun resolveSrvConnectionString(connStr: String): String {
        if (!connStr.startsWith("mongodb+srv://")) {
            return connStr
        }
        try {
            Log.d(TAG, "Resolving SRV connection string: $connStr")
            // Format: mongodb+srv://[username:password@]host/[database][?options]
            val cleanStr = connStr.substring("mongodb+srv://".length)
            
            // Find credentials and host
            val atIndex = cleanStr.indexOf('@')
            val credentials = if (atIndex != -1) cleanStr.substring(0, atIndex + 1) else ""
            val hostAndRest = if (atIndex != -1) cleanStr.substring(atIndex + 1) else cleanStr
            
            // Find end of host part (either /, ?, or end of string)
            var endHostIndex = hostAndRest.length
            val slashIndex = hostAndRest.indexOf('/')
            val questionIndex = hostAndRest.indexOf('?')
            if (slashIndex != -1 && questionIndex != -1) {
                endHostIndex = minOf(slashIndex, questionIndex)
            } else if (slashIndex != -1) {
                endHostIndex = slashIndex
            } else if (questionIndex != -1) {
                endHostIndex = questionIndex
            }
            
            val host = hostAndRest.substring(0, endHostIndex).trim()
            val rest = hostAndRest.substring(endHostIndex)
            
            // Resolve SRV records: _mongodb._tcp.host
            val srvUrl = "https://dns.google/resolve?name=_mongodb._tcp.$host&type=SRV"
            val srvResponse = makeHttpQuery(srvUrl) ?: throw Exception("Failed to query SRV record")
            val srvJson = JSONObject(srvResponse)
            val answers = srvJson.optJSONArray("Answer") ?: throw Exception("No SRV answers returned")
            
            val resolvedHosts = mutableListOf<String>()
            for (i in 0 until answers.length()) {
                val answer = answers.getJSONObject(i)
                val data = answer.optString("data", "")
                val parts = data.trim().split(" ")
                if (parts.size >= 4) {
                    val port = parts[2]
                    var target = parts[3]
                    if (target.endsWith(".")) {
                        target = target.substring(0, target.length - 1)
                    }
                    resolvedHosts.add("$target:$port")
                }
            }
            if (resolvedHosts.isEmpty()) {
                throw Exception("No resolved hosts found in SRV record")
            }
            val hostsStr = resolvedHosts.joinToString(",")
            
            // Resolve TXT records for options (like replicaSet)
            val txtUrl = "https://dns.google/resolve?name=$host&type=TXT"
            val txtResponse = makeHttpQuery(txtUrl)
            var txtOptions = ""
            if (txtResponse != null) {
                val txtJson = JSONObject(txtResponse)
                val txtAnswers = txtJson.optJSONArray("Answer")
                if (txtAnswers != null && txtAnswers.length() > 0) {
                    val dataList = mutableListOf<String>()
                    for (i in 0 until txtAnswers.length()) {
                        val answer = txtAnswers.getJSONObject(i)
                        var data = answer.optString("data", "")
                        if (data.startsWith("\"") && data.endsWith("\"")) {
                            data = data.substring(1, data.length - 1)
                        }
                        dataList.add(data)
                    }
                    txtOptions = dataList.joinToString("&")
                }
            }
            
            // Combine options
            val finalRest = StringBuilder()
            if (rest.startsWith("/")) {
                val dbPart = rest.substringBefore('?')
                finalRest.append(dbPart)
                val originalOptions = rest.substringAfter('?', "")
                val combinedOptions = mutableListOf<String>()
                if (originalOptions.isNotEmpty()) {
                    combinedOptions.add(originalOptions)
                }
                if (txtOptions.isNotEmpty()) {
                    combinedOptions.add(txtOptions)
                }
                if (combinedOptions.isNotEmpty()) {
                    finalRest.append("?").append(combinedOptions.joinToString("&"))
                }
            } else {
                val originalOptions = rest.trim().removePrefix("?")
                val combinedOptions = mutableListOf<String>()
                if (originalOptions.isNotEmpty()) {
                    combinedOptions.add(originalOptions)
                }
                if (txtOptions.isNotEmpty()) {
                    combinedOptions.add(txtOptions)
                }
                finalRest.append("/")
                if (combinedOptions.isNotEmpty()) {
                    finalRest.append("?").append(combinedOptions.joinToString("&"))
                }
            }
            
            val newConnStr = "mongodb://${credentials}${hostsStr}${finalRest}"
            Log.d(TAG, "Successfully resolved SRV connection string: $newConnStr")
            return newConnStr
        } catch (e: Exception) {
            Log.e(TAG, "SRV resolution failed, falling back to original connection string: ${e.message}", e)
            return connStr
        }
    }

    private fun makeHttpQuery(urlStr: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP query failed for $urlStr: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun getCollection(): MongoCollection<Document>? {
        val client = getClient() ?: return null
        return try {
            val database = client.getDatabase(BuildConfig.MONGODB_DATABASE)
            database.getCollection(BuildConfig.MONGODB_COLLECTION)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get MongoCollection", e)
            null
        }
    }

    fun isConfigured(): Boolean {
        return isMongoDBEnabled && BuildConfig.MONGODB_CONNECTION_STRING.isNotBlank()
    }

    suspend fun insertMacro(voiceShortcut: String, steps: List<MacroStep>, embedding: List<Float>, template: String?): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            Log.w(TAG, "MongoDB Connection String is blank, skipping insertMacro")
            return@withContext false
        }

        try {
            val collection = getCollection() ?: return@withContext false

            val stepsJson = gson.toJson(steps)
            val stepsList = Document.parse("{ \"steps\": $stepsJson }").getList("steps", Document::class.java)

            val doc = Document().apply {
                put("voiceShortcut", voiceShortcut)
                put("steps", stepsList)
                put("embedding", embedding)
                if (template != null) {
                    put("template", template)
                }
            }

            collection.insertOne(doc)
            Log.d(TAG, "Successfully inserted macro into MongoDB Atlas: '$voiceShortcut'")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to insert macro into MongoDB Atlas", e)
            false
        }
    }

    suspend fun vectorSearch(queryEmbedding: List<Float>): VoiceMacro? = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            Log.w(TAG, "MongoDB Connection String is blank, skipping vectorSearch")
            return@withContext null
        }

        try {
            val collection = getCollection() ?: return@withContext null

            // MongoDB Vector Search pipeline using standard Document constructors
            val vectorSearchStage = Document("\$vectorSearch", Document().apply {
                put("index", BuildConfig.MONGODB_VECTOR_INDEX)
                put("path", "embedding")
                put("queryVector", queryEmbedding)
                put("numCandidates", 10)
                put("limit", 1)
            })

            val projectStage = Document("\$project", Document().apply {
                put("voiceShortcut", 1)
                put("steps", 1)
                put("template", 1)
                put("embedding", 1)
                put("score", Document("\$meta", "vectorSearchScore"))
            })

            val pipeline = listOf(vectorSearchStage, projectStage)
            val matchDoc = collection.aggregate(pipeline).first()

            if (matchDoc != null) {
                val score = matchDoc.getDouble("score") ?: 0.0
                val voiceShortcut = matchDoc.getString("voiceShortcut") ?: ""
                val template = matchDoc.getString("template")
                val stepsList = matchDoc.getList("steps", Document::class.java)

                Log.d(TAG, "Vector search found potential match: '$voiceShortcut' with score: $score")

                if (score >= 0.82) {
                    val stepsJson = gson.toJson(stepsList)
                    val stepsListType = object : TypeToken<List<MacroStep>>() {}.type
                    val steps: List<MacroStep> = gson.fromJson(stepsJson, stepsListType)

                    val embeddingList = mutableListOf<Float>()
                    val returnedEmbedding = matchDoc.get("embedding", List::class.java)
                    if (returnedEmbedding != null) {
                        for (item in returnedEmbedding) {
                            if (item is Number) {
                                embeddingList.add(item.toFloat())
                            }
                        }
                    }

                    VoiceMacro(
                        voiceShortcut = voiceShortcut,
                        steps = steps,
                        embedding = if (embeddingList.isNotEmpty()) embeddingList else null,
                        template = template
                    )
                } else {
                    Log.d(TAG, "Best match score $score is below similarity threshold 0.82")
                    null
                }
            } else {
                Log.d(TAG, "Vector search returned 0 documents")
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to execute vectorSearch in MongoDB Atlas", e)
            null
        }
    }
}
