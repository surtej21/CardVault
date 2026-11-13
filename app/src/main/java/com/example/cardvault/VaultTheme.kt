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
import android.graphics.drawable.RippleDrawable
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
    val bgPrimary = Color.rgb(8, 9, 11)
    val bgSecondary = Color.rgb(12, 13, 16)
    val surface = Color.rgb(18, 19, 23)
    val surfaceElevated = Color.rgb(24, 26, 30)
    val surfaceInput = Color.rgb(14, 15, 18)

    // Accent — brass. The one hue that survives the unslop.
    val accent = Color.rgb(201, 168, 107)
    val accentDeep = Color.rgb(158, 124, 69)
    val accentBright = Color.rgb(227, 198, 140)

    // Functional / category tints — calm steps of neutral steel, sage and rose,
    // toned to sit inside the ramp. Used for status dots and small marks only.
    val accentBlue = Color.rgb(142, 154, 171)   // steel — cards / info
    val accentMint = Color.rgb(143, 179, 154)   // sage — custom / positive
    val accentRose = Color.rgb(167, 154, 166)   // warm steel — passwords
    val accentAmber = Color.rgb(214, 168, 110)  // warning
    val accentSand = Color.rgb(175, 166, 148)   // muted plate

    val textPrimary = Color.rgb(240, 239, 236)
    val textSecondary = Color.rgb(189, 189, 185)
    val textMuted = Color.rgb(147, 148, 154)
    val textFaint = Color.rgb(113, 115, 123)

    val border = Color.argb(28, 255, 255, 255)
    val borderStrong = Color.argb(48, 255, 255, 255)
    val borderFaint = Color.argb(15, 255, 255, 255)

    val brassLine = Color.argb(96, 201, 168, 107)
    val brassFaint = Color.argb(40, 201, 168, 107)
    val brassWash = Color.argb(18, 201, 168, 107)

    // Buttons are brass plates with ink text — the accent carries no glow.
    val buttonFace = Color.rgb(201, 168, 107)
    val buttonFaceDeep = Color.rgb(178, 145, 86)
    val buttonInk = Color.rgb(23, 19, 12)

    // Functional colors -------------------------------------------------------
    val positive = Color.rgb(143, 179, 154)
    val warning = Color.rgb(214, 168, 110)
    val danger = Color.rgb(206, 126, 126)
    val info = Color.rgb(142, 154, 171)

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

    fun mono(context: Context): Typeface =
        Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)

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
    fun auroraBackground(context: Context): Drawable = AuroraDrawable(context.resources.displayMetrics.density)

    fun cardShape(
        context: Context,
        radiusDp: Int = 14,
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
        radiusDp: Int = 14,
        strong: Boolean = false,
        borderColor: Int = if (strong) borderStrong else border
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(if (strong) surfaceElevated else surface)
        cornerRadius = dp(context, radiusDp).toFloat()
        setStroke(dp(context, 1), borderColor)
    }

    /** A steel plate: subtle vertical metal falloff for hero surfaces. */
    fun plateShape(context: Context, radiusDp: Int = 18): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(surfaceElevated, bgSecondary)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setStroke(dp(context, 1), borderStrong)
        }

    /** Primary action: a solid brass plate with ink text. */
    fun accentButtonDrawable(context: Context, radiusDp: Int = 12): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(accentBright, accent)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setStroke(dp(context, 1), Color.argb(90, 255, 236, 190))
        }

    fun secondaryButtonDrawable(context: Context, radiusDp: Int = 12): GradientDrawable =
        glassShape(context, radiusDp, strong = true, borderColor = borderStrong)

    fun iconButtonDrawable(context: Context, radiusDp: Int = 10, tinted: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(if (tinted) brassWash else Color.argb(12, 255, 255, 255))
            setStroke(dp(context, 1), if (tinted) brassFaint else border)
        }

    /** Chips: solid surface steps, never a gradient wash. */
    fun pillDrawable(context: Context, selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 99).toFloat()
            setColor(if (selected) surfaceElevated else Color.argb(10, 255, 255, 255))
            setStroke(dp(context, 1), if (selected) brassLine else border)
        }

    /** Hairline rule that fades at both ends — an engraving, not a divider. */
    fun brassRuleDrawable(context: Context): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.TRANSPARENT, brassLine, Color.TRANSPARENT)
        )

    /** Press feedback for flat ledger rows. */
    fun rowRipple(context: Context): Drawable =
        RippleDrawable(
            ColorStateList.valueOf(Color.argb(26, 201, 168, 107)),
            null,
            null
        )

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

    /** The vault room: dark ink steel, one very quiet overhead light. No washes. */
    private class AuroraDrawable(private val density: Float) : Drawable() {
        private val base = Paint(Paint.ANTI_ALIAS_FLAG)
        private var cache: android.graphics.Bitmap? = null
        private var cacheWidth = 0
        private var cacheHeight = 0

        override fun draw(canvas: Canvas) {
            val w = bounds.width()
            val h = bounds.height()
            if (w <= 0 || h <= 0) return

            // Full-screen radial gradients are expensive to rasterize on every
            // frame (especially without a GPU). Cache the room once per size.
            val cached = cache?.takeIf { cacheWidth == w && cacheHeight == h }
                ?: render(w, h).also {
                    cache = it
                    cacheWidth = w
                    cacheHeight = h
                }
            canvas.drawBitmap(cached, bounds.left.toFloat(), bounds.top.toFloat(), null)
        }

        private fun render(w: Int, h: Int): android.graphics.Bitmap {
            val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val width = w.toFloat()
            val height = h.toFloat()

            base.color = bgPrimary
            canvas.drawRect(0f, 0f, width, height, base)

            // A low, warm light from above the door — barely there.
            base.shader = RadialGradient(
                width * 0.5f, -height * 0.12f, height * 0.72f,
                Color.argb(15, 201, 168, 107), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width, height, base)

            // Vignette: the room falls away at the edges.
            base.shader = RadialGradient(
                width * 0.5f, height * 0.42f, width * 1.18f,
                Color.TRANSPARENT, Color.argb(52, 0, 0, 0), Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width, height, base)
            base.shader = null

            // Faint door-frame rules at the extreme edges.
            base.color = Color.argb(9, 255, 255, 255)
            val inset = 14f * density
            canvas.drawRect(inset, 0f, inset + 1f, height, base)
            canvas.drawRect(width - inset, 0f, width - inset + 1f, height, base)
            return bitmap
        }

        override fun setAlpha(alpha: Int) { base.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { base.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}
