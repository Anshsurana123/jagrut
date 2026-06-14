// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/**
 * FuturisticBackgroundView — Neural HUD Sentient AI Background
 *
 * Renders a deep space "Abyssal Black Void" background inspired by the Stitch
 * WebGL starfield/nebula shader from the jagrut_home_core design:
 *
 *   - Abyssal Black (#050508) base
 *   - Twinkling star field (random white dots that blink)
 *   - Nebula clouds: soft Electric Violet + Neural Teal radial gradients
 *   - Calm floating blobs using Screen blend mode (medium speed)
 */
class FuturisticBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ─── Base background — Abyssal Black ───
    private val basePaint = Paint().apply {
        color = Color.parseColor("#050508")
        style = Paint.Style.FILL
    }

    // ─── Nebula blob blending ───
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }

    // ─── Star dots ───
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private data class Star(val x: Float, val y: Float, val r: Float, val phase: Float)
    private data class Blob(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        val radius: Float,
        val colorStart: Int
    )

    private val stars = ArrayList<Star>()
    private val blobs = ArrayList<Blob>()
    private var isInitialized = false
    private var lastTime = System.currentTimeMillis()
    private var elapsedSec = 0f

    private fun initScene(w: Float, h: Float) {
        // ─── Starfield ───
        stars.clear()
        val rng = Random(42)
        repeat(120) {
            stars.add(
                Star(
                    x = rng.nextFloat() * w,
                    y = rng.nextFloat() * h,
                    r = rng.nextFloat() * 1.5f + 0.5f,
                    phase = rng.nextFloat() * 6.28f
                )
            )
        }

        // ─── Nebula blobs (Violet + Teal, calm medium speed) ───
        blobs.clear()
        val colorViolet  = Color.argb(28, 123, 94, 246)  // Electric Violet #7B5EF6 at ~11% opacity
        val colorTeal    = Color.argb(22,   0, 223, 198)  // Neural Teal    #00DFC6 at ~9% opacity
        val colorViolet2 = Color.argb(18, 100, 64, 218)   // Deep Violet

        blobs.add(Blob(w * 0.15f, h * 0.3f,  70f,  80f,  w * 0.9f,  colorViolet))
        blobs.add(Blob(w * 0.8f,  h * 0.65f, -85f,  65f,  w * 1.0f,  colorTeal))
        blobs.add(Blob(w * 0.5f,  h * 0.5f,   75f, -75f,  w * 0.8f,  colorViolet2))

        isInitialized = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (!isInitialized) initScene(w, h)

        val now = System.currentTimeMillis()
        val dt = ((now - lastTime) / 1000f).coerceAtMost(0.05f)
        lastTime = now
        elapsedSec += dt

        // 1. Abyssal Black base
        canvas.drawRect(0f, 0f, w, h, basePaint)

        // 2. Nebula blobs (soft violet/teal radial glow)
        for (blob in blobs) {
            blob.x += blob.vx * dt
            blob.y += blob.vy * dt
            if (blob.x < -blob.radius / 2) { blob.x = -blob.radius / 2; blob.vx = -blob.vx }
            else if (blob.x > w + blob.radius / 2) { blob.x = w + blob.radius / 2; blob.vx = -blob.vx }
            if (blob.y < -blob.radius / 2) { blob.y = -blob.radius / 2; blob.vy = -blob.vy }
            else if (blob.y > h + blob.radius / 2) { blob.y = h + blob.radius / 2; blob.vy = -blob.vy }

            val gradient = RadialGradient(
                blob.x, blob.y, blob.radius,
                intArrayOf(blob.colorStart, Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP
            )
            blobPaint.shader = gradient
            canvas.drawCircle(blob.x, blob.y, blob.radius, blobPaint)
        }

        // 3. Twinkling star field
        for (star in stars) {
            val blink = (0.5f + 0.5f * sin(elapsedSec * 2.0f + star.phase))
            starPaint.alpha = (blink * 200 + 55).toInt().coerceIn(0, 255)
            canvas.drawCircle(star.x, star.y, star.r, starPaint)
        }

        postInvalidateOnAnimation()
    }
}
