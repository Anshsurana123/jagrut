// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.logic

import android.util.Log
import java.util.Locale

/**
 * FuzzyCommandMatcher — A lightweight fuzzy matching engine that catches near-miss 
 * voice commands before they fall through to Gemini API (~20s latency).
 * 
 * It uses three strategies:
 * 1. Synonym expansion — maps Hindi/Hinglish/colloquial words to canonical seed words
 * 2. Token-level fuzzy matching — Levenshtein distance per-token to handle STT errors
 * 3. Phrase-level scoring — combines token overlap + fuzzy distance for overall confidence
 */
object FuzzyCommandMatcher {
    private const val TAG = "FuzzyCommandMatcher"

    /**
     * Minimum confidence score (0.0 to 1.0) to accept a fuzzy match.
     * 0.55 = fairly permissive — catches most paraphrases while avoiding false positives.
     */
    private const val CONFIDENCE_THRESHOLD = 0.55f

    /**
     * Max Levenshtein distance to consider two tokens as a fuzzy match.
     * 2 handles common STT errors like "volum" → "volume", "bluetoth" → "bluetooth".
     */
    private const val TOKEN_FUZZY_THRESHOLD = 2

    // ─── Synonym Dictionary ────────────────────────────────────────────────
    // Maps colloquial/Hindi/Hinglish words → canonical English seed words.
    // This lets "batti jala do" match "flashlight on" via synonym expansion.
    private val synonyms = mapOf(
        // Flashlight / Torch
        "batti" to "flashlight",
        "roshni" to "flashlight",
        "jala" to "on",
        "jalao" to "on",
        "bujha" to "off",
        "bujhao" to "off",

        // Volume / Sound
        "awaz" to "volume",
        "awaaz" to "volume",
        "aawaz" to "volume",
        "dhun" to "volume",
        "badha" to "increase",
        "badhao" to "increase",
        "kam" to "decrease",
        "ghata" to "decrease",
        "ghatao" to "decrease",
        "zyada" to "increase",
        "poora" to "max",
        "poori" to "max",
        "pura" to "max",
        "puri" to "max",
        "full" to "max",

        // Brightness
        "chamak" to "brightness",
        "ujala" to "brightness",
        "roshan" to "brightness",

        // WiFi
        "waifi" to "wifi",
        "wai fai" to "wifi",

        // Bluetooth
        "bluetuth" to "bluetooth",
        "blutu" to "bluetooth",
        "blutooth" to "bluetooth",

        // Battery / Power
        "charging" to "battery",
        "charger" to "battery",
        "bijli" to "battery",

        // Actions
        "chalu" to "on",
        "chalao" to "on",
        "shuru" to "on",
        "band" to "off",
        "karo" to "",       // filler verb, just remove
        "kar" to "",
        "kardo" to "",
        "do" to "",
        "de" to "",
        "laga" to "on",
        "lagao" to "on",
        "hata" to "off",
        "hatao" to "off",
        "kholo" to "open",
        "khol" to "open",
        "dikhao" to "show",
        "dikha" to "show",
        "batao" to "tell",
        "bata" to "tell",
        "padhao" to "read",
        "padho" to "read",
        "bolo" to "speak",
        "bol" to "speak",
        "suno" to "listen",
        "sun" to "listen",
        "bhejo" to "send",
        "bhej" to "send",
        "chalao" to "play",
        "bajao" to "play",
        "roko" to "stop",
        "rok" to "stop",
        "dabao" to "press",
        "daba" to "press",

        // Device
        "phone" to "phone",
        "mobile" to "phone",
        "handset" to "phone",

        // Directions / Maps
        "rasta" to "directions",
        "raasta" to "directions",
        "nakshe" to "maps",
        "naksha" to "maps",
        "pohchna" to "navigate",
        "pahuchna" to "navigate",
        "jana" to "navigate",
        "le chalo" to "navigate",
        "le chal" to "navigate",

        // Time / Date
        "waqt" to "time",
        "samay" to "time",
        "baje" to "time",
        "tarikh" to "date",
        "taarikh" to "date",
        "din" to "day",

        // Storage
        "jagah" to "space",
        "memory" to "storage",

        // Camera
        "tasveer" to "photo",
        "tasveir" to "photo",

        // Clipboard
        "paste" to "clipboard",

        // Media
        "gaana" to "music",
        "gana" to "music",
        "song" to "music",

        // Settings
        "setting" to "settings",
        "seting" to "settings"
    )

    /**
     * Result of a fuzzy match attempt.
     */
    data class FuzzyMatchResult(
        val intentKey: String,
        val confidence: Float,
        val matchedSeed: String
    )

    /**
     * Attempts to fuzzy-match the input text against all intent seeds.
     * Returns the best matching intent key if confidence exceeds threshold, or null.
     *
     * @param cleanText The normalized user input
     * @param intentSeeds The intent seed map from CommandParser
     * @return FuzzyMatchResult if a confident match is found, null otherwise
     */
    fun findBestMatch(
        cleanText: String,
        intentSeeds: Map<String, List<String>>
    ): FuzzyMatchResult? {
        val expandedInput = expandSynonyms(cleanText)
        val inputTokens = tokenize(expandedInput)

        if (inputTokens.isEmpty()) return null

        var bestResult: FuzzyMatchResult? = null
        var bestScore = 0f

        for ((intentKey, seeds) in intentSeeds) {
            for (seed in seeds) {
                val seedTokens = tokenize(seed)
                if (seedTokens.isEmpty()) continue

                val score = computeMatchScore(inputTokens, seedTokens, expandedInput, seed)

                if (score > bestScore) {
                    bestScore = score
                    bestResult = FuzzyMatchResult(intentKey, score, seed)
                }
            }
        }

        return if (bestResult != null && bestScore >= CONFIDENCE_THRESHOLD) {
            Log.d(TAG, "Fuzzy Match! '${cleanText}' → '${bestResult.intentKey}' (seed='${bestResult.matchedSeed}', confidence=${String.format("%.2f", bestResult.confidence)})")
            bestResult
        } else {
            if (bestResult != null) {
                Log.d(TAG, "Fuzzy match below threshold: '${cleanText}' → best='${bestResult.intentKey}' (confidence=${String.format("%.2f", bestResult.confidence)}, threshold=$CONFIDENCE_THRESHOLD)")
            }
            null
        }
    }

    /**
     * Computes a combined match score (0.0 to 1.0) between input tokens and seed tokens.
     * Uses two complementary strategies:
     * 1. Token overlap — what fraction of seed tokens are found (exactly or fuzzily) in the input?
     * 2. Substring containment — does the expanded input contain the entire seed phrase?
     */
    private fun computeMatchScore(
        inputTokens: List<String>,
        seedTokens: List<String>,
        expandedInput: String,
        seedPhrase: String
    ): Float {
        // Strategy 1: Token-level fuzzy overlap
        var matchedTokens = 0
        for (seedToken in seedTokens) {
            if (seedToken.length <= 1) {
                // Skip single-char tokens (articles, etc.)
                matchedTokens++
                continue
            }

            val hasMatch = inputTokens.any { inputToken ->
                // Exact match
                inputToken == seedToken ||
                // Starts-with match (handles "flashligh" → "flashlight")
                inputToken.startsWith(seedToken) || seedToken.startsWith(inputToken) ||
                // Fuzzy match (Levenshtein)
                (inputToken.length >= 3 && seedToken.length >= 3 &&
                    FuzzyMatcher.calculateDistance(inputToken, seedToken) <= TOKEN_FUZZY_THRESHOLD)
            }

            if (hasMatch) matchedTokens++
        }

        val tokenOverlapScore = if (seedTokens.isNotEmpty()) {
            matchedTokens.toFloat() / seedTokens.size
        } else 0f

        // Strategy 2: Substring containment (for multi-word seeds)
        val containsScore = if (seedPhrase.length >= 3 && expandedInput.contains(seedPhrase)) {
            1.0f
        } else {
            0f
        }

        // Combine: take the max of both strategies
        // Token overlap handles word-order independence and partial matches
        // Containment handles exact multi-word seeds that survive synonym expansion
        return maxOf(tokenOverlapScore, containsScore)
    }

    /**
     * Expands synonyms in the input text.
     * "batti jalao" → "flashlight on"
     * "awaz badha do" → "volume increase"
     */
    private fun expandSynonyms(text: String): String {
        val words = text.split(" ").toMutableList()
        val expanded = words.map { word ->
            synonyms[word] ?: word
        }.filter { it.isNotEmpty() } // Remove empty strings (from filler words like "karo")

        val result = expanded.joinToString(" ")

        if (result != text) {
            Log.d(TAG, "Synonym expansion: '$text' → '$result'")
        }

        return result
    }

    /**
     * Tokenizes a string into lowercase word tokens, filtering out single-char noise.
     */
    private fun tokenize(text: String): List<String> {
        return text.lowercase(Locale.getDefault())
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
