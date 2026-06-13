// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.ui

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.jago.R
import com.example.jago.logic.ActionExecutor
import java.io.File

class CameraActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "CameraActivity"
        private const val PERMISSIONS_REQUEST_CODE = 200
        const val EXTRA_DURATION = "EXTRA_DURATION"
        const val EXTRA_CONTACT = "EXTRA_CONTACT"
    }

    private lateinit var cameraPreview: SurfaceView
    private lateinit var recordingIndicator: View
    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnCancel: Button

    private var camera: Camera? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var surfaceHolder: SurfaceHolder? = null

    private var durationSeconds = 5
    private var contactName: String? = null
    private var videoFile: File? = null
    private var videoRotationHint = 90

    private val mainHandler = Handler(Looper.getMainLooper())
    private var secondsRemaining = 5
    private var recordingRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        durationSeconds = intent.getIntExtra(EXTRA_DURATION, 5)
        contactName = intent.getStringExtra(EXTRA_CONTACT)
        secondsRemaining = durationSeconds

        cameraPreview = findViewById(R.id.cameraPreview)
        recordingIndicator = findViewById(R.id.recordingIndicator)
        tvStatus = findViewById(R.id.tvStatus)
        tvTimer = findViewById(R.id.tvTimer)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        btnCancel = findViewById(R.id.btnCancel)

        // Set initial timer text
        tvTimer.text = String.format("00:%02d", secondsRemaining)
        tvStatus.text = "PREPARING..."
        recordingIndicator.visibility = View.INVISIBLE

        surfaceHolder = cameraPreview.holder
        surfaceHolder?.addCallback(this)

        btnCancel.setOnClickListener {
            cancelRecording()
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        } else {
            // Permissions already granted, surfaceHolder callbacks will handle camera preview initialization
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                // Permissions granted, start preview if surface is ready
                if (surfaceHolder != null) {
                    initCamera(surfaceHolder!!)
                }
            } else {
                Toast.makeText(this, "Camera and Microphone permissions are required to record video.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun initCamera(holder: SurfaceHolder) {
        if (camera != null) return

        try {
            // Open default back camera
            camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
            Toast.makeText(this, "Camera is in use or unavailable.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        try {
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(Camera.CameraInfo.CAMERA_FACING_BACK, info)
            
            val rotation = windowManager.defaultDisplay.rotation
            var degrees = 0
            when (rotation) {
                Surface.ROTATION_0 -> degrees = 0
                Surface.ROTATION_90 -> degrees = 90
                Surface.ROTATION_180 -> degrees = 180
                Surface.ROTATION_270 -> degrees = 270
            }
            
            val displayOrientation = (info.orientation - degrees + 360) % 360
            camera?.setDisplayOrientation(displayOrientation)
            
            // The video output orientation needs to match the orientation hint
            videoRotationHint = displayOrientation

            val parameters = camera?.parameters
            // Find a suitable preview size matching aspect ratio if possible
            val previewSize = parameters?.supportedPreviewSizes?.firstOrNull { it.width <= 1920 && it.height <= 1080 }
            previewSize?.let {
                parameters.setPreviewSize(it.width, it.height)
                camera?.parameters = parameters
            }

            camera?.setPreviewDisplay(holder)
            camera?.startPreview()
            Log.d(TAG, "Camera preview started successfully")

            // Auto-start recording after a 1 second delay to allow camera stabilization/focus
            mainHandler.postDelayed({
                if (!isFinishing && !isRecording) {
                    startVideoRecording()
                }
            }, 1200)

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up camera preview", e)
            releaseCamera()
            Toast.makeText(this, "Failed to initialize camera preview.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun startVideoRecording() {
        if (camera == null) return

        if (!prepareMediaRecorder()) {
            Toast.makeText(this, "Failed to initialize video recorder.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        try {
            mediaRecorder?.start()
            isRecording = true
            tvStatus.text = "RECORDING"
            recordingIndicator.visibility = View.VISIBLE
            
            // Start pulsing animation on the red indicator dot
            val pulseAnimation = AlphaAnimation(1.0f, 0.2f).apply {
                duration = 500
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            }
            recordingIndicator.startAnimation(pulseAnimation)

            // Start countdown timer
            startCountdown()
            Log.d(TAG, "Video recording started")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting MediaRecorder", e)
            releaseMediaRecorder()
            camera?.lock()
            Toast.makeText(this, "Could not start video recording.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun prepareMediaRecorder(): Boolean {
        val tempFile = File(externalCacheDir ?: cacheDir, "TEMP_VIDEO_${System.currentTimeMillis()}.mp4")
        videoFile = tempFile

        val recorder = MediaRecorder()
        mediaRecorder = recorder

        try {
            camera?.unlock()
            recorder.setCamera(camera)
            recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA)

            var profileLoaded = false
            try {
                // Try 720p or 480p first as standard options
                val profile = when {
                    CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_720P) -> CamcorderProfile.get(CamcorderProfile.QUALITY_720P)
                    CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_480P) -> CamcorderProfile.get(CamcorderProfile.QUALITY_480P)
                    else -> CamcorderProfile.get(CamcorderProfile.QUALITY_LOW)
                }
                recorder.setProfile(profile)
                profileLoaded = true
            } catch (pe: Exception) {
                Log.e(TAG, "Failed to load standard CamcorderProfile", pe)
            }

            if (!profileLoaded) {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                recorder.setVideoSize(1280, 720)
                recorder.setVideoFrameRate(30)
                recorder.setVideoEncodingBitRate(2500000)
            }

            recorder.setOutputFile(tempFile.absolutePath)
            recorder.setOrientationHint(videoRotationHint)
            recorder.prepare()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed preparing MediaRecorder", e)
            releaseMediaRecorder()
            return false
        }
    }

    private fun startCountdown() {
        secondsRemaining = durationSeconds
        tvTimer.text = String.format("00:%02d", secondsRemaining)

        recordingRunnable = object : Runnable {
            override fun run() {
                secondsRemaining--
                if (secondsRemaining <= 0) {
                    tvTimer.text = "00:00"
                    tvStatus.text = "SAVING..."
                    recordingIndicator.clearAnimation()
                    recordingIndicator.visibility = View.INVISIBLE
                    stopVideoRecordingAndShare()
                } else {
                    tvTimer.text = String.format("00:%02d", secondsRemaining)
                    mainHandler.postDelayed(this, 1000)
                }
            }
        }
        mainHandler.postDelayed(recordingRunnable!!, 1000)
    }

    private fun stopVideoRecordingAndShare() {
        if (!isRecording) return

        try {
            mediaRecorder?.stop()
            Log.d(TAG, "MediaRecorder stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder", e)
        } finally {
            releaseMediaRecorder()
            releaseCamera()
            isRecording = false
        }

        val file = videoFile
        if (file != null && file.exists() && file.length() > 0) {
            // Save video to MediaStore
            val videoUri = saveVideoToGallery(file)
            if (videoUri != null && !contactName.isNullOrEmpty()) {
                Log.d(TAG, "Sharing video file via ActionExecutor to: $contactName")
                val actionExecutor = ActionExecutor(applicationContext)
                actionExecutor.shareMediaUri(contactName, "whatsapp", videoUri)
            } else if (videoUri != null) {
                Toast.makeText(this, "Video saved, but no contact was specified.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to save the recorded video.", Toast.LENGTH_SHORT).show()
            }
            
            // Clean up temp file
            try {
                file.delete()
            } catch (e: Exception) {
                // ignore
            }
        } else {
            Toast.makeText(this, "Video recording failed or file was empty.", Toast.LENGTH_SHORT).show()
        }

        finish()
    }

    private fun saveVideoToGallery(videoFile: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Jago_Video_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Jago")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    videoFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                Log.d(TAG, "Video successfully saved to Gallery MediaStore: $uri")
                return uri
            } catch (e: Exception) {
                Log.e(TAG, "Error writing video to MediaStore output stream", e)
            }
        }
        return null
    }

    private fun cancelRecording() {
        cleanupAll()
        Toast.makeText(this, "Video recording cancelled.", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun cleanupAll() {
        recordingRunnable?.let { mainHandler.removeCallbacks(it) }
        recordingIndicator.clearAnimation()
        
        if (isRecording) {
            try {
                mediaRecorder?.stop()
            } catch (e: Exception) {
                // ignore
            }
        }
        releaseMediaRecorder()
        releaseCamera()
        isRecording = false

        videoFile?.let {
            if (it.exists()) {
                it.delete()
            }
        }
    }

    private fun releaseMediaRecorder() {
        mediaRecorder?.reset()
        mediaRecorder?.release()
        mediaRecorder = null
    }

    private fun releaseCamera() {
        camera?.stopPreview()
        camera?.release()
        camera = null
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceHolder = holder
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            initCamera(holder)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Handle surface orientation changes if needed, not necessary since screen is locked to portrait
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceHolder = null
        cleanupAll()
    }

    override fun onPause() {
        super.onPause()
        cleanupAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupAll()
    }
}
