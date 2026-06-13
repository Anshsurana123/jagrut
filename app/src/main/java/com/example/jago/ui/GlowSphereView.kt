// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.jago.R
import kotlin.math.cos
import kotlin.math.sin

class GlowSphereView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var rotationAngle = 0f
    private var pulseTime = 0f
    private var lastTime = System.currentTimeMillis()

    private val latBands = 8
    private val lonDots = 16

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val brandPrimary by lazy { ContextCompat.getColor(context, R.color.brand_primary) }
    private val brandSecondary by lazy { ContextCompat.getColor(context, R.color.brand_secondary) }
    private val glowColor by lazy { ContextCompat.getColor(context, R.color.glow_cyan) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0 || height <= 0) return

        val centerX = width / 2f
        val centerY = height / 2f
        val maxRadius = (width.coerceAtMost(height) / 2f) * 0.8f

        val currentTime = System.currentTimeMillis()
        val deltaTime = (currentTime - lastTime) / 1000f
        lastTime = currentTime

        // Update animation values
        rotationAngle += deltaTime * 0.6f // Rotation speed
        pulseTime += deltaTime * 2.5f     // Pulse speed
        val pulseFactor = sin(pulseTime) // -1.0 to 1.0
        val normalPulse = (pulseFactor + 1f) / 2f // 0.0 to 1.0

        // 1. Draw central glow
        val glowRadius = maxRadius * (0.6f + 0.15f * normalPulse)
        if (glowRadius > 0f) {
            val gradient = RadialGradient(
                centerX, centerY, glowRadius,
                intArrayOf(glowColor, 0x00000000),
                floatArrayOf(0.1f, 1f),
                Shader.TileMode.CLAMP
            )
            glowPaint.shader = gradient
            canvas.drawCircle(centerX, centerY, glowRadius, glowPaint)
        }

        // 2. Draw 3D-like rotating dotted sphere
        val sphereRadius = maxRadius * (0.8f + 0.03f * normalPulse)

        // Draw points, sorting by depth (z)
        val points = mutableListOf<SpherePoint>()

        for (lat in 0 until latBands) {
            val phi = Math.PI * (lat + 1) / (latBands + 1) - Math.PI / 2
            val cosPhi = cos(phi).toFloat()
            val sinPhi = sin(phi).toFloat()

            val r = sphereRadius * cosPhi
            val y = sphereRadius * sinPhi

            for (lon in 0 until lonDots) {
                val theta = 2.0 * Math.PI * lon / lonDots + rotationAngle
                val x = r * sin(theta).toFloat()
                val z = r * cos(theta).toFloat()

                points.add(SpherePoint(x, y, z, lat))
            }
        }

        points.sortBy { pt -> pt.z }

        for (pt in points) {
            val px = centerX + pt.x
            val py = centerY + pt.y

            val depthScale = ((pt.z / sphereRadius) + 1f) / 2f
            
            val minDotRadius = width * 0.008f
            val maxDotRadius = width * 0.022f
            val dotRadius = minDotRadius + (maxDotRadius - minDotRadius) * depthScale

            val minAlpha = 40
            val maxAlpha = 245
            val alpha = (minAlpha + (maxAlpha - minAlpha) * depthScale).toInt()

            val color = if (pt.lat % 2 == 0) brandSecondary else brandPrimary
            dotPaint.color = color
            dotPaint.alpha = alpha

            canvas.drawCircle(px, py, dotRadius, dotPaint)
        }

        postInvalidateOnAnimation()
    }

    private data class SpherePoint(val x: Float, val y: Float, val z: Float, val lat: Int)
}