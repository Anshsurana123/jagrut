// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.service.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.jago.logic.JagoTTS
import com.example.jago.logic.SarvamClient
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SarvamSTTAdapter(private val context: Context) : SpeechAdapter {
    private val TAG = "SarvamSTTAdapter"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordingJob: Job? = null
    
    @Volatile private var isListening = false
    @Volatile private var isCancelled = false
    
    override var isFollowUpListening = false

    override fun startListening(callback: SpeechAdapter.Callback) {
        Log.d(TAG, "startListening (follow-up: $isFollowUpListening)...")
        isCancelled = false
        isListening = true
        
        recordingJob?.cancel()
        recordingJob = scope.launch {
            val tempPcmFile = File(context.cacheDir, "sarvam_temp_${System.currentTimeMillis()}.raw")
            recordAudio(tempPcmFile, callback)
        }
    }

    override fun stopListening() {
        Log.d(TAG, "stopListening requested")
        isListening = false
    }

    override fun destroy() {
        if (isFollowUpListening) {
            Log.d(TAG, "destroy skipped: isFollowUpListening is true")
            return
        }
        Log.d(TAG, "destroy called")
        isCancelled = true
        isListening = false
        recordingJob?.cancel()
        recordingJob = null
    }

    private suspend fun recordAudio(tempFile: File, callback: SpeechAdapter.Callback) {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                maxOf(bufferSize, 2048)
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "MIC permission missing", e)
            withContext(Dispatchers.Main) {
                if (!isCancelled) callback.onError("Permission denied: MIC")
            }
            return
        }
        
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            withContext(Dispatchers.Main) {
                if (!isCancelled) callback.onError("Failed to initialize AudioRecord")
            }
            return
        }
        
        try {
            audioRecord.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            audioRecord.release()
            withContext(Dispatchers.Main) {
                if (!isCancelled) callback.onError("Failed to start recording: ${e.message}")
            }
            return
        }
        
        val buffer = ShortArray(1024)
        var speechStarted = false
        var silenceStartTime = 0L
        val recordingStartTime = System.currentTimeMillis()
        
        try {
            tempFile.outputStream().use { out ->
                while (isListening && !isCancelled) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        // Write to PCM file
                        val byteBuffer = ByteBuffer.allocate(read * 2).apply {
                            order(ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until read) {
                                putShort(buffer[i])
                            }
                        }
                        out.write(byteBuffer.array())
                        
                        // Compute average amplitude
                        var sum = 0.0
                        for (i in 0 until read) {
                            sum += Math.abs(buffer[i].toInt())
                        }
                        val avgAmplitude = sum / read
                        
                        val now = System.currentTimeMillis()
                        
                        // Silence detection logic
                        if (avgAmplitude > 1000) {
                            speechStarted = true
                            silenceStartTime = 0L
                        } else if (speechStarted) {
                            if (silenceStartTime == 0L) {
                                silenceStartTime = now
                            } else if (now - silenceStartTime > 1500) {
                                Log.d(TAG, "Silence detected. Stopping recording.")
                                break
                            }
                        }
                        
                        // Max recording duration check (12 seconds)
                        if (now - recordingStartTime > 12000) {
                            Log.d(TAG, "Max recording duration reached.")
                            break
                        }
                    } else if (read < 0) {
                        Log.e(TAG, "AudioRecord read error: $read")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during recording", e)
        } finally {
            try {
                audioRecord.stop()
            } catch (e: Exception) {}
            audioRecord.release()
        }
        
        if (isCancelled) {
            tempFile.delete()
            return
        }
        
        // Convert to WAV and transcribe
        if (tempFile.exists() && tempFile.length() > 0) {
            val wavFile = File(context.cacheDir, "sarvam_input_${System.currentTimeMillis()}.wav")
            try {
                convertToWav(tempFile, wavFile, sampleRate)
                tempFile.delete()
                
                withContext(Dispatchers.Main) {
                    if (!isCancelled) {
                        com.example.jago.ui.AssistantUIBridge.updateStatus("Transcribing...")
                    }
                }
                
                // Call Sarvam STT API
                val lang = if (JagoTTS.currentLanguage == "hi") "hi-IN" else null
                val transcript = SarvamClient.transcribeAudio(wavFile, lang)
                
                wavFile.delete()
                
                if (isCancelled) return
                
                withContext(Dispatchers.Main) {
                    if (transcript != null) {
                        Log.d(TAG, "Transcribed successfully: $transcript")
                        callback.onResult(transcript)
                    } else {
                        callback.onError("Transcription failed or empty response")
                    }
                }
            } catch (e: Exception) {
                tempFile.delete()
                wavFile.delete()
                withContext(Dispatchers.Main) {
                    if (!isCancelled) callback.onError("Error compiling audio: ${e.message}")
                }
            }
        } else {
            tempFile.delete()
            withContext(Dispatchers.Main) {
                if (!isCancelled) callback.onError("No audio recorded")
            }
        }
    }

    private fun convertToWav(pcmFile: File, wavFile: File, sampleRate: Int) {
        val pcmSize = pcmFile.length()
        val totalDataLen = pcmSize + 36
        val channels = 1
        val byteRate = sampleRate * channels * 2
        
        pcmFile.inputStream().use { pcmIn ->
            wavFile.outputStream().use { wavOut ->
                writeWavHeader(wavOut, pcmSize, totalDataLen, sampleRate.toLong(), channels, byteRate.toLong())
                pcmIn.copyTo(wavOut)
            }
        }
    }

    private fun writeWavHeader(out: java.io.OutputStream, totalAudioLen: Long, totalDataLen: Long, longSampleRate: Long, channels: Int, byteRate: Long) {
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = ((longSampleRate shr 8) and 0xff).toByte()
        header[26] = ((longSampleRate shr 16) and 0xff).toByte()
        header[27] = ((longSampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte()
        header[33] = 0
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()
        out.write(header, 0, 44)
    }
}
