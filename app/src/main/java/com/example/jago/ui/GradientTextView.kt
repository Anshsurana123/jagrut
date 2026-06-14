// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.ui

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.example.jago.R

class GradientTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    init {
        // Configure futuristic text shadow layer for neon glow effect
        val glowColor = ContextCompat.getColor(context, R.color.glow_purple)
        setShadowLayer(16f, 0f, 0f, glowColor)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) {
            val paint = paint
            val width = width.toFloat()
            // Gradient flowing from Neon Cyan (brand_secondary) to Amethyst (brand_primary)
            val startColor = ContextCompat.getColor(context, R.color.brand_secondary)
            val endColor = ContextCompat.getColor(context, R.color.brand_primary)
            paint.shader = LinearGradient(
                0f, 0f, width, 0f,
                startColor, endColor,
                Shader.TileMode.CLAMP
            )
        }
    }
}
