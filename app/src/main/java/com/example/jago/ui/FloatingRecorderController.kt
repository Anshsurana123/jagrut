// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.example.jago.R

class FloatingRecorderController(
    private val context: Context,
    private val shortcutName: String,
    private val onStopCallback: () -> Unit
) {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var pulseAnimator: ObjectAnimator? = null

    fun show() {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val inflater = LayoutInflater.from(context)
        floatingView = inflater.inflate(R.layout.floating_recorder_bubble, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 150

        // Set shortcut label text
        floatingView?.findViewById<TextView>(R.id.txtShortcutName)?.text = "Rec: $shortcutName"

        // Pulse Animation for Red Dot
        val pulsingDot = floatingView?.findViewById<View>(R.id.pulsingDot)
        if (pulsingDot != null) {
            pulseAnimator = ObjectAnimator.ofFloat(pulsingDot, "alpha", 1.0f, 0.2f).apply {
                duration = 800
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }

        // Draggable touch listener
        floatingView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0f
            private var initialTouchY: Float = 0f

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                if (event == null) return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(floatingView, params)
                        return true
                    }
                }
                return false
            }
        })

        // Stop Record Click Listener
        floatingView?.findViewById<Button>(R.id.btnStopRecord)?.setOnClickListener {
            onStopCallback()
            dismiss()
        }

        try {
            windowManager?.addView(floatingView, params)
        } catch (e: Exception) {
            Toast.makeText(context, "Overlay permission required to record shortcuts", Toast.LENGTH_LONG).show()
        }
    }

    fun dismiss() {
        pulseAnimator?.cancel()
        floatingView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                // Already removed
            }
        }
        floatingView = null
        windowManager = null
    }
}
