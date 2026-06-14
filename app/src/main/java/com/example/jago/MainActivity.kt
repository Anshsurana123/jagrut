// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago

import android.Manifest
import android.net.Uri
import android.provider.Settings
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.jago.logic.JagoTTS
import com.example.jago.service.JagoAdminReceiver
import com.example.jago.service.WakeWordService
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private val clockHandler = Handler(Looper.getMainLooper())
    private lateinit var tvSystemClock: TextView
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var adminButton: android.view.View
    private lateinit var overlayButton: android.view.View
    private lateinit var brightnessButton: android.view.View
    private lateinit var uploadRingtoneButton: android.view.View
    private lateinit var commandsButton: Button
    private lateinit var geminiButton: Button
    private lateinit var researchButton: Button

    private lateinit var lblAdminStatus: TextView
    private lateinit var lblOverlayStatus: TextView
    private lateinit var lblBrightnessStatus: TextView
    private lateinit var btnRecordMacro: Button
    private lateinit var containerMacros: android.widget.LinearLayout

    private val permissions: Array<String>
        get() {
            val list = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.CAMERA
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list.add(Manifest.permission.POST_NOTIFICATIONS)
                list.add(Manifest.permission.READ_MEDIA_IMAGES)
                list.add(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            return list.toTypedArray()
        }

    private val pickAudioLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            saveCustomRingtone(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inflate dashboard carousel tab layouts
        val inflater = layoutInflater
        val tabHome = inflater.inflate(R.layout.layout_tab_home, null)
        val tabMacros = inflater.inflate(R.layout.layout_tab_macros, null)
        val tabLogs = inflater.inflate(R.layout.layout_tab_logs, null)
        val tabPermissions = inflater.inflate(R.layout.layout_tab_permissions, null)

        // Bind view references to subviews in the respective tabs
        statusText = tabHome.findViewById(R.id.statusText)
        actionButton = tabHome.findViewById(R.id.actionButton)

        adminButton = tabPermissions.findViewById(R.id.adminButton)
        overlayButton = tabPermissions.findViewById(R.id.overlayButton)
        brightnessButton = tabPermissions.findViewById(R.id.brightnessButton)
        uploadRingtoneButton = tabPermissions.findViewById(R.id.uploadRingtoneButton)

        lblAdminStatus = tabPermissions.findViewById(R.id.lblAdminStatus)
        lblOverlayStatus = tabPermissions.findViewById(R.id.lblOverlayStatus)
        lblBrightnessStatus = tabPermissions.findViewById(R.id.lblBrightnessStatus)
        btnRecordMacro = tabMacros.findViewById(R.id.btnRecordMacro)
        containerMacros = tabMacros.findViewById(R.id.containerMacros)
        commandsButton = tabMacros.findViewById(R.id.commandsButton)

        geminiButton = tabLogs.findViewById(R.id.geminiButton)
        researchButton = tabLogs.findViewById(R.id.researchButton)

        // Setup ViewPager2 and BottomNavigationView
        val viewPager = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager)
        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)

        val viewList = listOf(tabHome, tabMacros, tabLogs, tabPermissions)

        viewPager.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val frameLayout = android.widget.FrameLayout(parent.context).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(frameLayout) {}
            }

            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val container = holder.itemView as android.widget.FrameLayout
                container.removeAllViews()
                val view = viewList[position]
                if (view.parent != null) {
                    (view.parent as android.view.ViewGroup).removeView(view)
                }
                container.addView(view)
            }

            override fun getItemCount(): Int = viewList.size
            override fun getItemViewType(position: Int): Int = position
        }

        // Disable swiping/user input if preferred, or leave enabled for carousel swiping.
        viewPager.isUserInputEnabled = true 

        // Sync BottomNavigationView selection to ViewPager2 page change
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.menu_home -> viewPager.currentItem = 0
                R.id.menu_macros -> viewPager.currentItem = 1
                R.id.menu_logs -> viewPager.currentItem = 2
                R.id.menu_security -> viewPager.currentItem = 3
            }
            true
        }

        // Sync ViewPager2 swipe changes back to BottomNavigationView selected item
        viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                when (position) {
                    0 -> bottomNav.selectedItemId = R.id.menu_home
                    1 -> bottomNav.selectedItemId = R.id.menu_macros
                    2 -> bottomNav.selectedItemId = R.id.menu_logs
                    3 -> bottomNav.selectedItemId = R.id.menu_security
                }
            }
        })

        // Setup click listeners
        adminButton.setOnClickListener {
            requestDeviceAdminExemption()
        }

        overlayButton.setOnClickListener {
            requestOverlayPermission()
        }

        brightnessButton.setOnClickListener {
            requestWriteSettingsPermission()
        }

        uploadRingtoneButton.setOnClickListener {
            pickAudioLauncher.launch("audio/*")
        }

        commandsButton.setOnClickListener {
            val intent = Intent(this, CommandListActivity::class.java)
            startActivity(intent)
        }

        geminiButton.setOnClickListener {
            val intent = Intent(this, GeminiActivity::class.java)
            startActivity(intent)
        }

        researchButton.setOnClickListener {
            val intent = Intent(this, ResearchActivity::class.java)
            startActivity(intent)
        }

        actionButton.setOnClickListener {
            if (WakeWordService.isServiceRunning) {
                stopService()
            } else {
                startService()
            }
        }

        btnRecordMacro.setOnClickListener {
            showRecordMacroDialog()
        }

        val switchMongoDb = tabMacros.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchMongoDb)
        val jagoPrefs = getSharedPreferences("JagoSettings", MODE_PRIVATE)
        switchMongoDb.isChecked = jagoPrefs.getBoolean("mongodb_enabled", true)
        switchMongoDb.setOnCheckedChangeListener { _, isChecked ->
            jagoPrefs.edit().putBoolean("mongodb_enabled", isChecked).apply()
            com.example.jago.logic.MongoDBClient.isMongoDBEnabled = isChecked
            Log.d("MainActivity", "MongoDB preference set to: $isChecked")
        }

        // Wire up HUD live system clock
        tvSystemClock = findViewById(R.id.tvSystemClock)
        startHudClock()

        checkPermissions()
        JagoTTS.init(this)

        // Check if notification listener permission is granted
        if (!isNotificationListenerEnabled()) {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                JagoTTS.stopSpeaking()
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    // onResume is now defined below with clock restart

    private fun updatePermissionStatuses() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, JagoAdminReceiver::class.java)
        val isAdminActive = dpm.isAdminActive(adminComponent)
        lblAdminStatus.text = if (isAdminActive) "✅" else "⚠️"

        val canDrawOverlays = Settings.canDrawOverlays(this)
        lblOverlayStatus.text = if (canDrawOverlays) "✅" else "⚠️"

        val canWriteSettings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(this)
        } else {
            true
        }
        lblBrightnessStatus.text = if (canWriteSettings) "✅" else "⚠️"
    }

    private fun refreshMacrosList() {
        containerMacros.removeAllViews()
        val macros = com.example.jago.logic.MacroEngine.getMacros(this)
        
        if (macros.isEmpty()) {
            val tv = TextView(this).apply {
                text = "No voice shortcuts yet. Tap '+ RECORD' to start."
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                gravity = android.view.Gravity.CENTER
                setPadding(24, 24, 24, 24)
            }
            containerMacros.addView(tv)
            return
        }

        for (macro in macros) {
            val itemView = layoutInflater.inflate(R.layout.item_voice_macro, containerMacros, false)
            itemView.findViewById<TextView>(R.id.txtShortcutVoice).text = "Trigger: \"${macro.voiceShortcut}\""
            itemView.findViewById<TextView>(R.id.txtShortcutSteps).text = "${macro.steps.size} automation step(s) recorded"

            itemView.findViewById<android.view.View>(R.id.btnPlayMacro).setOnClickListener {
                com.example.jago.service.JagoAccessibilityService.playMacro(this, macro)
                Toast.makeText(this, "Executing: \"${macro.voiceShortcut}\"", Toast.LENGTH_SHORT).show()
            }

            itemView.findViewById<android.view.View>(R.id.btnDeleteMacro).setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Delete Shortcut")
                    .setMessage("Are you sure you want to delete the shortcut: \"${macro.voiceShortcut}\"?")
                    .setPositiveButton("Delete") { _, _ ->
                        com.example.jago.logic.MacroEngine.deleteMacro(this, macro.voiceShortcut)
                        refreshMacrosList()
                        Toast.makeText(this, "Shortcut deleted", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            containerMacros.addView(itemView)
        }
    }

    private fun showRecordMacroDialog() {
        if (!com.example.jago.service.JagoAccessibilityService.isServiceRunning()) {
            Toast.makeText(this, "Please enable Jago Accessibility Service under accessibility settings.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Jago needs Overlay permission to draw recording bubble overlay.", Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Record Voice Shortcut")
        
        val input = android.widget.EditText(this).apply {
            hint = "e.g. book cab, order tea"
            setSingleLine(true)
        }
        builder.setView(input)

        builder.setPositiveButton("RECORD") { _, _ ->
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) {
                val success = com.example.jago.service.JagoAccessibilityService.startRecording(this, name)
                if (success) {
                    Toast.makeText(this, "Recording started! Go to target app and execute clicks.", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Voice trigger cannot be empty", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    private fun saveCustomRingtone(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val file = java.io.File(filesDir, "custom_alarm.mp3")
            val outputStream = java.io.FileOutputStream(file)
            
            inputStream?.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            Toast.makeText(this, "Ringtone uploaded successfully!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to save ringtone", e)
            Toast.makeText(this, "Failed to upload ringtone", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        JagoTTS.stopSpeaking()
        clockHandler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        startHudClock()
        updatePermissionStatuses()
        refreshMacrosList()
        updateUI(WakeWordService.isServiceRunning)
    }

    private fun startHudClock() {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val clockRunnable = object : Runnable {
            override fun run() {
                if (::tvSystemClock.isInitialized) {
                    tvSystemClock.text = fmt.format(Date())
                }
                clockHandler.postDelayed(this, 1000)
            }
        }
        clockHandler.removeCallbacksAndMessages(null)
        clockHandler.post(clockRunnable)
    }

    private fun checkPermissions() {
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, permissions, 100)
        }
    }

    private fun hasPermissions(): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // Permissions granted
            } else {
                // Permissions denied
                statusText.text = "Permissions required for Jago to work."
            }
        }
    }

    private fun startService() {
        val serviceIntent = Intent(this, WakeWordService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        updateUI(true)
    }

    private fun stopService() {
        val serviceIntent = Intent(this, WakeWordService::class.java)
        stopService(serviceIntent)
        updateUI(false)
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } else {
            Toast.makeText(this, "Overlay permission already granted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } else {
                Toast.makeText(this, "Brightness control permission already granted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestDeviceAdminExemption() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, JagoAdminReceiver::class.java)
        
        if (!dpm.isAdminActive(adminComponent)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Jago needs this to lock your screen via voice.")
            startActivity(intent)
        } else {
            Toast.makeText(this, "Device Admin is already active", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = android.provider.Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        return flat != null && flat.contains(packageName)
    }

    private fun updateUI(isRunning: Boolean) {
        if (isRunning) {
            statusText.text = getString(R.string.status_listening)
            actionButton.text = getString(R.string.action_stop)
        } else {
            statusText.text = getString(R.string.status_idle)
            actionButton.text = getString(R.string.action_start)
        }
    }
}
