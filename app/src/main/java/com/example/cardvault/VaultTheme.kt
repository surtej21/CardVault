package com.example.cardvault

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

/**
 * Visual tokens for the Aura Vault design language.
 *
 * Direction: exact, steady, cool — the iron vault room. One tinted neutral
 * ramp for everything, one warm brass accent reserved for the vault "doing
 * its job", real card metal as the only showpiece. No washes, no glow.
 *
 * Everything here is local and offline: the fonts are bundled in assets, and
 * the surfaces are drawn with the platform graphics stack. No network.
 */
object VaultTheme {

    // Core neutrals — ink-steel ramp (the world: iron, plate, low brass light).
    val bgPrimary = Color.rgb(9, 10, 12)
    val bgSecondary = Color.rgb(12, 13, 16)
    val surface = Color.rgb(17, 18, 21)
    val surfaceElevated = Color.rgb(21, 22, 26)
    val surfaceInput = Color.rgb(13, 14, 17)

    // Accent — brass. The one hue that survives the unslop.
    val accent = Color.rgb(198, 164, 104)
    val accentDeep = Color.rgb(174, 141, 84)

    // Functional / category tints — calm steps of neutral steel, sage and rose,
    // toned to sit inside the ramp. Used for status dots and small marks only.
    val accentBlue = Color.rgb(126, 144, 168)   // steel — cards / info
    val accentMint = Color.rgb(124, 172, 138)   // sage — custom / positive
    val accentRose = Color.rgb(152, 148, 158)   // warm steel — passwords
    val accentAmber = Color.rgb(214, 168, 110)  // warning
    val accentSand = Color.rgb(168, 160, 146)   // muted plate

    val textPrimary = Color.rgb(236, 235, 233)
    val textSecondary = Color.rgb(178, 179, 184)
    val textMuted = Color.rgb(132, 134, 141)
    val textFaint = Color.rgb(101, 103, 110)

    val border = Color.argb(26, 255, 255, 255)
    val borderStrong = Color.argb(40, 255, 255, 255)
    val borderFaint = Color.argb(14, 255, 255, 255)

    // Buttons are off-white plates with ink text — the accent carries no glow.
    val buttonFace = Color.rgb(226, 223, 216)
    val buttonInk = Color.rgb(21, 20, 17)

    // Functional colors -------------------------------------------------------
    val positive = Color.rgb(124, 172, 138)
    val warning = Color.rgb(214, 168, 110)
    val danger = Color.rgb(206, 120, 120)
    val info = Color.rgb(126, 144, 168)

    // Typography --------------------------------------------------------------
    private data class FontKey(val path: String, val weight: Int)
    private val fontCache = mutableMapOf<FontKey, Typeface>()

    fun typeface(context: Context, weight: Int = 500, heading: Boolean = false): Typeface {
        val path = if (heading) "fonts/Manrope.ttf" else "fonts/Inter.ttf"
        val key = FontKey(path, weight)
        return fontCache.getOrPut(key) {
            runCatching {
                Typeface.Builder(context.applicationContext.assets, path)
                    .setFontVariationSettings("'wght' $weight")
                    .build()
            }.getOrElse {
                val style = when {
                    weight >= 700 -> Typeface.BOLD
                    weight >= 500 -> Typeface.NORMAL
                    else -> Typeface.NORMAL
                }
                Typeface.create("sans-serif", style)
            }
        }
    }

    fun styleText(
        view: TextView,
        color: Int = textPrimary,
        sizeSp: Float = 14f,
        weight: Int = 400,
        heading: Boolean = false,
        letterSpacingEm: Float = 0f,
        includeFontPadding: Boolean = false
    ): TextView {
        view.setTextColor(color)
        view.textSize = sizeSp
        view.typeface = typeface(view.context, weight, heading)
        view.letterSpacing = letterSpacingEm
        view.includeFontPadding = includeFontPadding
        return view
    }

    // Dimensions --------------------------------------------------------------
    fun dp(context: Context, n: Int): Int = (n * context.resources.displayMetrics.density).toInt()
    fun dp(context: Context, n: Float): Int = (n * context.resources.displayMetrics.density).toInt()

    // Drawables ---------------------------------------------------------------
    fun auroraBackground(): Drawable = AuroraDrawable()

    fun cardShape(
        context: Context,
        radiusDp: Int = 18,
        bg: Int = surface,
        borderColor: Int = border
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(bg)
        cornerRadius = dp(context, radiusDp).toFloat()
        if (borderColor != Color.TRANSPARENT) setStroke(dp(context, 1), borderColor)
    }

    /** Flat raised surface with a hairline — no glass gradient. */
    fun glassShape(
        context: Context,
        radiusDp: Int = 18,
        strong: Boolean = false,
        borderColor: Int = if (strong) borderStrong else border
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(if (strong) surfaceElevated else surface)
        cornerRadius = dp(context, radiusDp).toFloat()
        setStroke(dp(context, 1), borderColor)
    }

    /** Primary action: a solid off-white plate (dark theme) with ink text. */
    fun accentButtonDrawable(context: Context, radiusDp: Int = 14): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(buttonFace)
            cornerRadius = dp(context, radiusDp).toFloat()
        }

    fun secondaryButtonDrawable(context: Context, radiusDp: Int = 14): GradientDrawable =
        glassShape(context, radiusDp, strong = true, borderColor = borderStrong)

    fun iconButtonDrawable(context: Context, radiusDp: Int = 12, tinted: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(if (tinted) Color.argb(22, 198, 164, 104) else Color.argb(12, 255, 255, 255))
            setStroke(dp(context, 1), if (tinted) Color.argb(46, 198, 164, 104) else border)
        }

    /** Chips: solid surface steps, never a gradient wash. */
    fun pillDrawable(context: Context, selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 99).toFloat()
            setColor(if (selected) surfaceElevated else Color.argb(10, 255, 255, 255))
            setStroke(dp(context, 1), if (selected) Color.argb(70, 198, 164, 104) else border)
        }

    fun glowDrawable(color: Int, radiusDp: Int = 180): Drawable =
        RadialGlowDrawable(color, radiusDp)

    fun tint(context: Context, drawableRes: Int, color: Int): Drawable? =
        context.getDrawable(drawableRes)?.mutate()?.apply {
            setTint(color)
            setTintList(ColorStateList.valueOf(color))
        }

    // Tactile motion — a tiny press, nothing springy.
    fun applySpringTouch(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate()
                    .scaleX(0.985f).scaleY(0.985f)
                    .setDuration(60).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(90).start()
            }
            false
        }
    }

    private class RadialGlowDrawable(
        private val color: Int,
        private val radiusDp: Int
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            val maxRadius = minOf(bounds.width(), bounds.height()) / 2f
            val r = (maxRadius * (radiusDp / 180f)).coerceIn(1f, maxRadius)
            paint.shader = RadialGradient(
                bounds.exactCenterX(), bounds.exactCenterY(), r,
                color, Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawRect(bounds, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** The vault room: dark ink steel, one very quiet vignette. No washes. */
    private class AuroraDrawable : Drawable() {
        private val base = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            base.color = bgPrimary
            canvas.drawRect(bounds, base)
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            val left = bounds.left.toFloat()
            val top = bounds.top.toFloat()
            base.shader = RadialGradient(
                left + w * 0.5f, top + h * 0.4f, w * 1.2f,
                Color.TRANSPARENT, Color.argb(26, 2, 2, 3), Shader.TileMode.CLAMP
            )
            canvas.drawRect(bounds, base)
            base.shader = null
        }

        override fun setAlpha(alpha: Int) { base.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { base.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}
