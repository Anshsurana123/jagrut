// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.logic

import android.content.Context
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

object JagoTTS : TextToSpeech.OnInitListener {
    private const val TAG = "JagoTTS"
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val handler = Handler(Looper.getMainLooper())
    
    // Explicit state tracking
    var isSpeaking = false
        private set
    private var activeUtteranceId: String? = null
    private var hasActiveCallback = false
    
    // Language persistence
    private var appContext: Context? = null
    private const val PREFS_NAME = "jago_prefs"
    private const val KEY_LANGUAGE = "language"
    
    // Language setting — persists across commands
    // "en" = English (default), "hi" = Hindi
    var currentLanguage: String = "en"
        private set
    
    // Callback management
    private var pendingCallback: (() -> Unit)? = null
    var onSpeechStateChange: ((Boolean) -> Unit)? = null

    // Media player and coroutine scope for Bhashini TTS
    private var mediaPlayer: MediaPlayer? = null
    private var coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun init(context: Context) {
        appContext = context.applicationContext
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext, this)
        }
        // Load saved language preference
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentLanguage = prefs.getString(KEY_LANGUAGE, "en") ?: "en"
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (connectivityManager != null) {
            val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            if (capabilities != null) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return true
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return true
            }
        }
        return false
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Apply saved language preference
            val locale = if (currentLanguage == "hi") Locale("hi", "IN") else Locale.US
            tts?.language = locale
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS started: $utteranceId")
                    isSpeaking = true
                    handler.post { onSpeechStateChange?.invoke(true) }
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS finished: $utteranceId")
                    isSpeaking = false
                    handler.post { onSpeechStateChange?.invoke(false) }
                    
                    // Only execute callback if it matches the active utterance
                    if (utteranceId == activeUtteranceId) {
                        activeUtteranceId = null
                        hasActiveCallback = false
                        // Post delayed callback to ensure TTS audio clears (500ms buffer)
                        handler.postDelayed({
                            Log.d(TAG, "TTS finished → Starting follow-up")
                            val cb = pendingCallback
                            pendingCallback = null
                            cb?.invoke()
                        }, 500)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS error: $utteranceId")
                    isSpeaking = false
                    activeUtteranceId = null
                    hasActiveCallback = false
                    pendingCallback = null
                    handler.post { onSpeechStateChange?.invoke(false) }
                }
            })
            isInitialized = true
        } else {
            Log.e(TAG, "Initialization failed")
        }
    }

    fun speak(text: String, isAIResponse: Boolean = false) {
        val ctx = appContext
        val online = ctx != null && isNetworkAvailable(ctx)
        
        if (online) {
            if (currentLanguage == "hi") {
                coroutineScope.coroutineContext[Job]?.cancelChildren()
                mediaPlayer?.release()
                mediaPlayer = null
                
                coroutineScope.launch {
                    val sarvamWavFile = if (ctx != null) SarvamClient.generateTTS(ctx, text, "hi-IN") else null
                    if (sarvamWavFile != null) {
                        playWavFile(sarvamWavFile)
                    } else {
                        Log.w(TAG, "Sarvam Hindi TTS returned null, trying ElevenLabs Hindi TTS")
                        val elevenLabsFile = if (ctx != null) ElevenLabsClient.generateTTS(ctx, text) else null
                        if (elevenLabsFile != null) {
                            playWavFile(elevenLabsFile)
                        } else {
                            Log.w(TAG, "ElevenLabs Hindi TTS returned null, falling back to Android TTS")
                            speakAndroidTtsActual(text)
                        }
                    }
                }
            } else if (currentLanguage != "en") {
                // Cancel any previous active Bhashini tts requests before starting a new one
                coroutineScope.coroutineContext[Job]?.cancelChildren()
                
                // Release existing media player if playing
                mediaPlayer?.release()
                mediaPlayer = null
                
                coroutineScope.launch {
                    val wavFile = BhashiniClient.tts(text, currentLanguage, "female")
                    if (wavFile != null) {
                        playWavFile(wavFile)
                    } else {
                        Log.w(TAG, "Bhashini TTS returned null, falling back to Sarvam Hindi TTS")
                        val sarvamWavFile = if (ctx != null) SarvamClient.generateTTS(ctx, text, "hi-IN") else null
                        if (sarvamWavFile != null) {
                            playWavFile(sarvamWavFile)
                        } else {
                            Log.w(TAG, "Sarvam Hindi TTS returned null, trying ElevenLabs Hindi TTS")
                            val elevenLabsFile = if (ctx != null) ElevenLabsClient.generateTTS(ctx, text) else null
                            if (elevenLabsFile != null) {
                                playWavFile(elevenLabsFile)
                            } else {
                                Log.w(TAG, "ElevenLabs Hindi TTS returned null, falling back to Android TTS")
                                speakAndroidTtsActual(text)
                            }
                        }
                    }
                }
            } else {
                coroutineScope.coroutineContext[Job]?.cancelChildren()
                mediaPlayer?.release()
                mediaPlayer = null
                
                coroutineScope.launch {
                    val mp3File = if (ctx != null) ElevenLabsClient.generateTTS(ctx, text) else null
                    if (mp3File != null) {
                        playWavFile(mp3File)
                    } else {
                        Log.w(TAG, "ElevenLabs TTS failed, checking Sarvam or Android TTS")
                        val isMidFlow = com.example.jago.service.WakeWordService.instance?.isMidFlow == true
                        val sarvamWavFile = if (isAIResponse && !isMidFlow && ctx != null) {
                            SarvamClient.generateTTS(ctx, text, "en-IN")
                        } else null
                        if (sarvamWavFile != null) {
                            playWavFile(sarvamWavFile)
                        } else {
                            Log.w(TAG, "Sarvam and ElevenLabs failed, falling back to Android TTS")
                            speakAndroidTtsActual(text)
                        }
                    }
                }
            }
        } else {
            Log.d(TAG, "Offline: using default Android TTS")
            speakAndroidTtsActual(text)
        }
    }

    private fun speakAndroidTtsActual(text: String) {
        if (isInitialized) {
            val utteranceId = "JAGO_${System.currentTimeMillis()}"
            // Only update activeUtteranceId if no callback is waiting
            if (!hasActiveCallback) {
                activeUtteranceId = utteranceId
            }
            val queueMode = if (isSpeaking) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
            tts?.speak(text, queueMode, null, utteranceId)
        } else {
            Log.w(TAG, "TTS not initialized yet")
        }
    }

    fun speakWithCallback(text: String, isAIResponse: Boolean = false, onComplete: () -> Unit) {
        val ctx = appContext
        val online = ctx != null && isNetworkAvailable(ctx)
        
        if (online) {
            if (currentLanguage == "hi") {
                coroutineScope.coroutineContext[Job]?.cancelChildren()
                mediaPlayer?.release()
                mediaPlayer = null
                
                hasActiveCallback = true
                pendingCallback = onComplete
                
                coroutineScope.launch {
                    val sarvamWavFile = if (ctx != null) SarvamClient.generateTTS(ctx, text, "hi-IN") else null
                    if (sarvamWavFile != null) {
                        playWavFile(sarvamWavFile, onComplete)
                    } else {
                        Log.w(TAG, "Sarvam Hindi TTS returned null, trying ElevenLabs Hindi TTS with callback")
                        val elevenLabsFile = if (ctx != null) ElevenLabsClient.generateTTS(ctx, text) else null
                        if (elevenLabsFile != null) {
                            playWavFile(elevenLabsFile, onComplete)
                        } else {
                            Log.w(TAG, "ElevenLabs Hindi TTS returned null, falling back to Android TTS with callback")
                            speakAndroidTtsActualWithCallback(text, onComplete)
                        }
                    }
                }
            } else if (currentLanguage != "en") {
                // Cancel any previous active Bhashini tts requests before starting a new one
                coroutineScope.coroutineContext[Job]?.cancelChildren()
                
                // Release existing media player if playing
                mediaPlayer?.release()
                mediaPlayer = null
                
                hasActiveCallback = true
                pendingCallback = onComplete
                
                coroutineScope.launch {
                    val wavFile = BhashiniClient.tts(text, currentLanguage, "female")
                    if (wavFile != null) {
                        playWavFile(wavFile, onComplete)
                    } else {
                        Log.w(TAG, "Bhashini TTS returned null, falling back to Sarvam Hindi TTS with callback")
                        val sarvamWavFile = if (ctx != null) SarvamClient.generateTTS(ctx, text, "hi-IN") else null
                        if (sarvamWavFile != null) {
                            playWavFile(sarvamWavFile, onComplete)
                        } else {
                            Log.w(TAG, "Sarvam Hindi TTS returned null, trying ElevenLabs Hindi TTS with callback")
                            val elevenLabsFile = if (ctx != null) ElevenLabsClient.generateTTS(ctx, text) else null
                            if (elevenLabsFile != null) {
                                playWavFile(elevenLabsFile, onComplete)
                            } else {
                                Log.w(TAG, "ElevenLabs Hindi TTS returned null, falling back to Android TTS with callback")
                                speakAndroidTtsActualWithCallback(text, onComplete)
                            }
                        }
                    }
                }
            } else {
                coroutineScope.coroutineContext[Job]?.cancelChildren()
                mediaPlayer?.release()
                mediaPlayer = null
                
                hasActiveCallback = true
                pendingCallback = onComplete
                
                coroutineScope.launch {
                    val mp3File = if (ctx != null) ElevenLabsClient.generateTTS(ctx, text) else null
                    if (mp3File != null) {
                        playWavFile(mp3File, onComplete)
                    } else {
                        Log.w(TAG, "ElevenLabs TTS failed, checking Sarvam or Android TTS with callback")
                        val isMidFlow = com.example.jago.service.WakeWordService.instance?.isMidFlow == true
                        val sarvamWavFile = if (isAIResponse && !isMidFlow && ctx != null) {
                            SarvamClient.generateTTS(ctx, text, "en-IN")
                        } else null
                        if (sarvamWavFile != null) {
                            playWavFile(sarvamWavFile, onComplete)
                        } else {
                            Log.w(TAG, "Sarvam and ElevenLabs failed, falling back to Android TTS with callback")
                            speakAndroidTtsActualWithCallback(text, onComplete)
                        }
                    }
                }
            }
        } else {
            Log.d(TAG, "Offline: using default Android TTS with callback")
            speakAndroidTtsActualWithCallback(text, onComplete)
        }
    }

    private fun speakAndroidTtsActualWithCallback(text: String, onComplete: () -> Unit) {
        if (isInitialized) {
            hasActiveCallback = true
            pendingCallback = onComplete
            val utteranceId = "JAGO_${System.currentTimeMillis()}"
            activeUtteranceId = utteranceId
            val queueMode = if (isSpeaking) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
            tts?.speak(text, queueMode, null, utteranceId)
            Log.d(TAG, "Speaking with callback: $text (ID: $utteranceId)")
        } else {
            Log.w(TAG, "TTS not initialized yet, invoking callback immediately")
            onComplete()
        }
    }

    private fun playWavFile(file: File, onComplete: (() -> Unit)? = null) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    isSpeaking = false
                    handler.post { onSpeechStateChange?.invoke(false) }
                    if (onComplete != null) {
                        handler.postDelayed({
                            val cb = pendingCallback
                            pendingCallback = null
                            cb?.invoke()
                        }, 500)
                    }
                    try {
                        if (file.exists()) file.delete()
                    } catch (ex: Exception) {
                        Log.e(TAG, "Failed to delete temp TTS file", ex)
                    }
                    it.release()
                    mediaPlayer = null
                }
                setOnErrorListener { _, _, _ ->
                    isSpeaking = false
                    handler.post { onSpeechStateChange?.invoke(false) }
                    if (onComplete != null) {
                        val cb = pendingCallback
                        pendingCallback = null
                        cb?.invoke()
                    }
                    try {
                        if (file.exists()) file.delete()
                    } catch (ex: Exception) {
                        Log.e(TAG, "Failed to delete temp TTS file on error", ex)
                    }
                    release()
                    mediaPlayer = null
                    true
                }
                isSpeaking = true
                handler.post { onSpeechStateChange?.invoke(true) }
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer playback failed", e)
            isSpeaking = false
            if (onComplete != null) {
                val cb = pendingCallback
                pendingCallback = null
                cb?.invoke()
            }
            try {
                if (file.exists()) file.delete()
            } catch (ex: Exception) {
                // Ignore
            }
        }
    }

    fun setLanguage(lang: String) {
        currentLanguage = lang
        val locale = if (lang == "hi") Locale("hi", "IN") else Locale.US
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "Language $lang not supported, falling back to English")
            tts?.language = Locale.US
            currentLanguage = "en"
        }
        // Persist the setting
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_LANGUAGE, currentLanguage)?.apply()
        Log.d(TAG, "Language set to: $currentLanguage")
    }

    // Speaks in currently selected language
    fun speakBilingual(englishText: String, hindiText: String, isAIResponse: Boolean = false) {
        val text = if (currentLanguage == "hi") hindiText else englishText
        speak(text, isAIResponse)
    }

    fun speakBilingualWithCallback(
        englishText: String,
        hindiText: String,
        isAIResponse: Boolean = false,
        onComplete: () -> Unit
    ) {
        val text = if (currentLanguage == "hi") hindiText else englishText
        speakWithCallback(text, isAIResponse, onComplete)
    }

    fun stopSpeaking() {
        var stoppedSomething = false
        if (mediaPlayer != null) {
            try {
                mediaPlayer?.stop()
            } catch (e: Exception) {
                // Ignore
            }
            mediaPlayer?.release()
            mediaPlayer = null
            stoppedSomething = true
        }
        if (isInitialized && isSpeaking) {
            tts?.stop()
            stoppedSomething = true
        }
        
        // Cancel active coroutines to stop in-flight Bhashini requests
        coroutineScope.coroutineContext[Job]?.cancelChildren()
        
        if (stoppedSomething || isSpeaking) {
            Log.d(TAG, "Speech interrupted by user")
            isSpeaking = false
            activeUtteranceId = null
            hasActiveCallback = false
            pendingCallback = null
            handler.removeCallbacksAndMessages(null)
            handler.post { onSpeechStateChange?.invoke(false) }
        }
    }

    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        pendingCallback = null
        activeUtteranceId = null
        isSpeaking = false
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        
        coroutineScope.cancel()
        coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    }
}
