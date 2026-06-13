// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.logic

object TranslationClient {

    private const val TAG = "TranslationClient"

    // Translates Hinglish/English text to Hindi Devanagari using Bhashini NMT
    suspend fun toDevanagari(text: String): String? {
        return BhashiniClient.translateToDevanagari(text)
    }

    // Translates Hindi/Devanagari text to English using Bhashini NMT
    suspend fun toEnglish(text: String): String? {
        return BhashiniClient.translateToEnglish(text)
    }
}