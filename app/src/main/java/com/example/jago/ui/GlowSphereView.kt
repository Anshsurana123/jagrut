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

    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val brandPrimary by lazy { ContextCompat.getColor(context, R.color.brand_primary) }
    private val brandSecondary by lazy { ContextCompat.getColor(context, R.color.brand_secondary) }
    private val brandTertiary by lazy { ContextCompat.getColor(context, R.color.brand_tertiary) }
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
        rotationAngle += deltaTime * 0.5f // Rotation speed
        pulseTime += deltaTime * 2.2f     // Pulse speed
        val pulseFactor = sin(pulseTime) // -1.0 to 1.0
        val normalPulse = (pulseFactor + 1f) / 2f // 0.0 to 1.0

        // 1. Draw central atmospheric glow
        val glowRadius = maxRadius * (0.7f + 0.2f * normalPulse)
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

        // 2. Draw central 3D pulsating glass core orb
        val orbRadius = maxRadius * (0.35f + 0.06f * normalPulse)
        if (orbRadius > 0f) {
            val orbGradient = RadialGradient(
                centerX - orbRadius * 0.2f, centerY - orbRadius * 0.2f, orbRadius * 1.2f,
                intArrayOf(brandSecondary, brandTertiary, 0x00000000),
                floatArrayOf(0.0f, 0.7f, 1.0f),
                Shader.TileMode.CLAMP
            )
            orbPaint.shader = orbGradient
            canvas.drawCircle(centerX, centerY, orbRadius, orbPaint)
        }

        // 3. Generate points for 3D sphere and outer gyro ring
        val sphereRadius = maxRadius * (0.75f + 0.02f * normalPulse)
        val points = mutableListOf<ThreeDPoint>()

        // Add sphere points
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

                points.add(ThreeDPoint(x, y, z, lat, isRing = false))
            }
        }

        // Add 3D tilted gyro orbit ring points
        val ringRadius = maxRadius * 1.15f
        val ringDotsCount = 20
        val tiltRad = Math.toRadians(35.0) // 35 degrees tilt
        val cosTilt = cos(tiltRad).toFloat()
        val sinTilt = sin(tiltRad).toFloat()

        for (i in 0 until ringDotsCount) {
            val theta = 2.0 * Math.PI * i / ringDotsCount - rotationAngle * 1.2f
            
            // Unrotated ring coordinates in tilted plane
            val rx = ringRadius * cos(theta).toFloat()
            val ryPrime = ringRadius * sin(theta).toFloat()
            val ry = ryPrime * cosTilt
            val rz = ryPrime * sinTilt

            points.add(ThreeDPoint(rx, ry, rz, latIndex = i, isRing = true))
        }

        // Sort points by depth (z) to render back elements first (painter's algorithm)
        points.sortBy { pt -> pt.z }

        // Draw points with depth styling
        for (pt in points) {
            val px = centerX + pt.x
            val py = centerY + pt.y

            // Normalized depth from 0.0 (farthest back) to 1.0 (nearest front)
            val maxZ = ringRadius.coerceAtLeast(sphereRadius)
            val depthScale = ((pt.z / maxZ) + 1f) / 2f
            
            if (pt.isRing) {
                // Gyro ring dot properties
                val minDotRadius = width * 0.007f
                val maxDotRadius = width * 0.018f
                val dotRadius = minDotRadius + (maxDotRadius - minDotRadius) * depthScale

                val minAlpha = 60
                val maxAlpha = 255
                val alpha = (minAlpha + (maxAlpha - minAlpha) * depthScale).toInt()

                // White / Cyan accent for the ring
                dotPaint.color = brandSecondary
                dotPaint.alpha = alpha
                canvas.drawCircle(px, py, dotRadius, dotPaint)
            } else {
                // Sphere dot properties
                val minDotRadius = width * 0.006f
                val maxDotRadius = width * 0.018f
                val dotRadius = minDotRadius + (maxDotRadius - minDotRadius) * depthScale

                val minAlpha = 35
                val maxAlpha = 235
                val alpha = (minAlpha + (maxAlpha - minAlpha) * depthScale).toInt()

                val color = if (pt.latIndex % 2 == 0) brandSecondary else brandPrimary
                dotPaint.color = color
                dotPaint.alpha = alpha
                canvas.drawCircle(px, py, dotRadius, dotPaint)
            }
        }

        postInvalidateOnAnimation()
    }

    private data class ThreeDPoint(
        val x: Float,
        val y: Float,
        val z: Float,
        val latIndex: Int,
        val isRing: Boolean
    )
}