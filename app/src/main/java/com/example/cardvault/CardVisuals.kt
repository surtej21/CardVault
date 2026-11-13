package com.example.cardvault

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.min

object CardVisuals {

    enum class CardBrand(val displayName: String) {
        VISA("VISA"),
        MASTERCARD("Mastercard"),
        AMEX("AMEX"),
        DISCOVER("Discover"),
        RUPAY("RuPay"),
        VAULT("Card")
    }

    private data class CardTheme(
        val start: Int,
        val end: Int,
        val accent: Int
    )

    fun detectBrand(cardNumber: String): CardBrand {
        val clean = cardNumber.filter { it.isDigit() }
        return when {
            clean.startsWith("4") -> CardBrand.VISA
            clean.matches(Regex("^(5[1-5]|2[2-7]).*")) -> CardBrand.MASTERCARD
            clean.startsWith("34") || clean.startsWith("37") -> CardBrand.AMEX
            clean.startsWith("6011") || clean.startsWith("65") -> CardBrand.DISCOVER
            clean.matches(Regex("^(60|65|81|82).*")) -> CardBrand.RUPAY
            else -> CardBrand.VAULT
        }
    }

    /** Metal stock, not a color wheel: graphite, gunmetal, slate, bronze. */
    private fun themeFor(context: Context, brand: CardBrand, id: String): CardTheme = when (brand) {
        CardBrand.VISA -> CardTheme(
            Color.rgb(40, 44, 52), Color.rgb(12, 14, 18), Color.rgb(214, 220, 230)
        )
        CardBrand.MASTERCARD -> CardTheme(
            Color.rgb(46, 41, 34), Color.rgb(18, 15, 12), Color.rgb(214, 181, 126)
        )
        CardBrand.AMEX -> CardTheme(
            Color.rgb(33, 43, 47), Color.rgb(10, 16, 19), Color.rgb(169, 198, 204)
        )
        CardBrand.DISCOVER -> CardTheme(
            Color.rgb(49, 39, 27), Color.rgb(20, 15, 10), Color.rgb(226, 192, 138)
        )
        CardBrand.RUPAY -> CardTheme(
            Color.rgb(31, 43, 37), Color.rgb(10, 17, 14), Color.rgb(184, 205, 184)
        )
        CardBrand.VAULT -> {
            val hashed = abs(id.hashCode()) % 2
            if (hashed == 0) CardTheme(
                Color.rgb(39, 41, 47), Color.rgb(13, 14, 17), VaultTheme.accent
            ) else CardTheme(
                Color.rgb(35, 42, 50), Color.rgb(12, 16, 21), Color.rgb(159, 176, 196)
            )
        }
    }

    fun createCardWidget(
        context: Context,
        entry: VaultStore.Entry,
        isExpanded: Boolean,
        onToggleExpand: () -> Unit
    ): View {
        val brand = detectBrand(entry.cardNumber)
        val theme = themeFor(context, brand, entry.id)
        val dp = { n: Int -> VaultTheme.dp(context, n) }

        val card = FrameLayout(context).apply {
            background = MetalPlateDrawable(context, theme.start, theme.end, theme.accent, 20)
            clipToOutline = true
            elevation = dp(8).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = "${entry.title.ifBlank { "Payment card" }}, ${brand.displayName}. Tap for details."
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onToggleExpand()
            }
            VaultTheme.applySpringTouch(this)
            layoutParams = LinearLayout.LayoutParams(-1, dp(178)).apply { bottomMargin = dp(4) }
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(15), dp(18), dp(13))
        }
        card.addView(content, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(VaultUi.text(context, entry.title.ifBlank { "Payment card" }, 15f, Color.WHITE, 600, heading = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(BrandLogoView(context, brand), LinearLayout.LayoutParams(dp(66), dp(24)).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        content.addView(top, LinearLayout.LayoutParams(-1, -2))

        val chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(15), 0, 0)
        }
        chipRow.addView(EmvChipView(context), LinearLayout.LayoutParams(dp(40), dp(30)))
        chipRow.addView(ContactlessView(context), LinearLayout.LayoutParams(dp(22), dp(22)).apply {
            marginStart = dp(10)
        })
        chipRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        chipRow.addView(VaultUi.text(
            context,
            if (isExpanded) "DETAILS OPEN" else "PRIVATE",
            8.5f, theme.accent, 700, letterSpacing = 0.2f
        ))
        content.addView(chipRow, LinearLayout.LayoutParams(-1, -2))

        val digits = entry.cardNumber.filter { it.isDigit() }
        val formatted = if (digits.length >= 4) {
            "••••  ••••  ••••  ${digits.takeLast(4)}"
        } else {
            "••••  ••••  ••••  ••••"
        }
        content.addView(VaultUi.text(
            context, formatted, 20f, Color.WHITE, 500, heading = true, letterSpacing = 0.05f
        ).apply {
            typeface = VaultTheme.mono(context)
            setPadding(0, dp(12), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))

        content.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))

        content.addView(View(context).apply {
            setBackgroundColor(Color.argb(
                44, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent)
            ))
        }, LinearLayout.LayoutParams(-1, dp(1)).apply { bottomMargin = dp(9) })

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val holder = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        holder.addView(VaultUi.text(context, "CARDHOLDER", 8f, Color.argb(160, 255, 255, 255), 600, letterSpacing = 0.18f))
        holder.addView(VaultUi.text(
            context,
            entry.cardholderName.ifBlank { "Your name" }.uppercase(),
            12f, Color.WHITE, 550, letterSpacing = 0.03f
        ).apply { setPadding(0, dp(3), 0, 0) })
        bottom.addView(holder, LinearLayout.LayoutParams(0, -2, 1f))

        val expiry = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        expiry.addView(VaultUi.text(context, "EXPIRES", 8f, Color.argb(160, 255, 255, 255), 600, letterSpacing = 0.18f, gravity = Gravity.END))
        expiry.addView(VaultUi.text(
            context,
            entry.expiry.ifBlank { "••/••" },
            12f, Color.WHITE, 600, heading = true, letterSpacing = 0.04f, gravity = Gravity.END
        ).apply { setPadding(0, dp(3), 0, 0) })
        bottom.addView(expiry, LinearLayout.LayoutParams(-2, -2))
        content.addView(bottom, LinearLayout.LayoutParams(-1, -2))

        return card
    }

    fun createBankWidget(
        context: Context,
        entry: VaultStore.Entry,
        isExpanded: Boolean,
        onToggleExpand: () -> Unit
    ): View {
        val dp = { n: Int -> VaultTheme.dp(context, n) }
        val theme = bankTheme(entry.id)
        val account = entry.customFields["Account number"].orEmpty().ifBlank { entry.cardNumber }
        val last4 = account.filter { it.isDigit() }.takeLast(4).ifBlank { "••••" }

        val card = FrameLayout(context).apply {
            background = MetalPlateDrawable(context, theme.start, theme.end, theme.accent, 20)
            clipToOutline = true
            elevation = dp(8).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = "${entry.title}, bank account ending $last4. Tap for details."
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onToggleExpand()
            }
            VaultTheme.applySpringTouch(this)
            layoutParams = LinearLayout.LayoutParams(-1, dp(152)).apply { bottomMargin = dp(4) }
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(13), dp(17), dp(12))
        }
        card.addView(content, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(VaultUi.articleMark(context, entry.title, theme.accent, 36))
        val nameCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }
        nameCol.addView(VaultUi.text(context, entry.title.ifBlank { "Bank account" }, 14.5f, Color.WHITE, 600).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        nameCol.addView(VaultUi.text(context, "BANK ACCOUNT", 8.5f, Color.argb(160, 255, 255, 255), 600, letterSpacing = 0.16f).apply {
            setPadding(0, dp(3), 0, 0)
        })
        top.addView(nameCol, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(VaultUi.icon(context, R.drawable.ic_bank, Color.argb(190, 255, 255, 255), 18), LinearLayout.LayoutParams(dp(18), dp(18)))
        content.addView(top, LinearLayout.LayoutParams(-1, -2))

        content.addView(VaultUi.text(context, "ACCOUNT NUMBER", 8f, Color.argb(160, 255, 255, 255), 600, letterSpacing = 0.18f).apply {
            setPadding(0, dp(12), 0, 0)
        })
        content.addView(VaultUi.text(
            context, "••••  ••••  ${last4.take(4)}", 18f, Color.WHITE, 500, heading = true, letterSpacing = 0.07f
        ).apply {
            typeface = VaultTheme.mono(context)
            setPadding(0, dp(4), 0, 0)
        })
        content.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))

        content.addView(View(context).apply {
            setBackgroundColor(Color.argb(
                40, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent)
            ))
        }, LinearLayout.LayoutParams(-1, dp(1)).apply { bottomMargin = dp(8) })

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottom.addView(VaultUi.text(context, "SECURED ON THIS DEVICE", 8.5f, Color.argb(170, 255, 255, 255), 600, letterSpacing = 0.1f), LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(VaultUi.icon(
            context,
            if (isExpanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right,
            Color.argb(200, 255, 255, 255), 17
        ))
        content.addView(bottom, LinearLayout.LayoutParams(-1, -2))

        return card
    }

    private data class BankTheme(val start: Int, val end: Int, val accent: Int)

    private fun bankTheme(id: String): BankTheme = when (abs(id.hashCode()) % 3) {
        0 -> BankTheme(Color.rgb(36, 42, 52), Color.rgb(12, 15, 20), Color.rgb(142, 154, 171))
        1 -> BankTheme(Color.rgb(46, 40, 32), Color.rgb(17, 14, 10), Color.rgb(201, 168, 107))
        else -> BankTheme(Color.rgb(32, 42, 38), Color.rgb(10, 15, 13), Color.rgb(143, 179, 154))
    }

    /**
     * Brushed metal plate: a quiet vertical falloff, fine diagonal grain, a
     * top-left sheen, and a one-pixel metal edge in the card's accent.
     */
    private class MetalPlateDrawable(
        context: Context,
        private val start: Int,
        private val end: Int,
        private val accent: Int,
        radiusDp: Int
    ) : android.graphics.drawable.Drawable() {
        private val density = context.resources.displayMetrics.density
        private val radius = radiusDp * density
        private val base = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
        private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.argb(7, 255, 255, 255)
        }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        private val rect = RectF()
        private val path = Path()

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            rect.set(bounds)
            path.reset()
            path.addRoundRect(rect, radius, radius, Path.Direction.CW)

            base.shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                start, end, Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, base)
            base.shader = null

            canvas.save()
            canvas.clipPath(path)
            val step = 3f * density
            val span = rect.height()
            var x = rect.left - span
            while (x < rect.right) {
                canvas.drawLine(x, rect.top, x + span, rect.bottom, brush)
                x += step
            }
            canvas.restore()

            sheen.shader = LinearGradient(
                rect.left, rect.top, rect.left + rect.width() * 0.78f, rect.top + rect.height() * 0.95f,
                Color.argb(26, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, sheen)
            sheen.shader = null

            edge.color = Color.argb(
                74, Color.red(accent), Color.green(accent), Color.blue(accent)
            )
            canvas.drawRoundRect(rect, radius, radius, edge)
        }

        override fun setAlpha(alpha: Int) { base.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            base.colorFilter = colorFilter
            sheen.colorFilter = colorFilter
        }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private class EmvChipView(context: Context) : View(context) {
        private val dp = { n: Int -> VaultTheme.dp(context, n) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            color = Color.argb(140, 110, 82, 34)
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val radius = dp(5).toFloat()
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            fill.shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(Color.rgb(234, 215, 166), Color.rgb(185, 147, 79)),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, fill)
            fill.shader = null
            canvas.drawRoundRect(rect, radius, radius, line)
            val midY = height / 2f
            canvas.drawLine(width * 0.25f, 0f, width * 0.25f, height.toFloat(), line)
            canvas.drawLine(width * 0.75f, 0f, width * 0.75f, height.toFloat(), line)
            canvas.drawLine(0f, midY, width.toFloat(), midY, line)
            canvas.drawLine(width * 0.25f, height * 0.3f, width * 0.75f, height * 0.3f, line)
            canvas.drawLine(width * 0.25f, height * 0.7f, width * 0.75f, height * 0.7f, line)
        }
    }

    private class ContactlessView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = VaultTheme.dp(context, 1.6f).toFloat()
            color = Color.argb(180, 255, 255, 255)
            strokeCap = Paint.Cap.ROUND
        }
        private val rect = RectF()
        override fun onDraw(canvas: Canvas) {
            val cx = width * 0.1f
            val cy = height / 2f
            for (i in 0..2) {
                val r = width * (0.22f + 0.19f * i)
                rect.set(cx - r, cy - r, cx + r, cy + r)
                canvas.drawArc(rect, -50f, 100f, false, paint)
            }
        }
    }

    private class BrandLogoView(context: Context, private val brand: CardBrand) : View(context) {
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
        }
        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            when (brand) {
                CardBrand.MASTERCARD -> {
                    val r = height * 0.38f
                    val cy = height / 2f
                    circlePaint.color = Color.rgb(235, 0, 27)
                    canvas.drawCircle(width * 0.38f, cy, r, circlePaint)
                    circlePaint.color = Color.rgb(247, 158, 27)
                    canvas.drawCircle(width * 0.62f, cy, r, circlePaint)
                    circlePaint.color = Color.argb(230, 255, 95, 0)
                    canvas.drawCircle(width * 0.5f, cy, r * 0.78f, circlePaint)
                }
                CardBrand.AMEX -> {
                    rect.set(0f, height * 0.08f, width.toFloat(), height * 0.92f)
                    circlePaint.color = Color.rgb(45, 111, 255)
                    canvas.drawRoundRect(rect, height * 0.12f, height * 0.12f, circlePaint)
                    textPaint.textSize = height * 0.42f
                    textPaint.textAlign = Paint.Align.CENTER
                    textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    canvas.drawText("AMEX", width / 2f, height * 0.63f, textPaint)
                }
                CardBrand.DISCOVER -> {
                    textPaint.textSize = height * 0.42f
                    textPaint.letterSpacing = 0.04f
                    canvas.drawText("DISCOVER", width.toFloat(), height * 0.66f, textPaint)
                    circlePaint.color = Color.rgb(255, 126, 45)
                    canvas.drawCircle(width * 0.94f, height * 0.24f, height * 0.06f, circlePaint)
                }
                CardBrand.RUPAY -> {
                    textPaint.textSize = height * 0.5f
                    textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    textPaint.textAlign = Paint.Align.LEFT
                    textPaint.color = Color.WHITE
                    canvas.drawText("RuPay", 0f, height * 0.68f, textPaint)
                }
                CardBrand.VISA -> {
                    textPaint.textSize = height * 0.56f
                    textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
                    textPaint.letterSpacing = 0.02f
                    canvas.drawText("VISA", width.toFloat(), height * 0.72f, textPaint)
                }
                CardBrand.VAULT -> {
                    textPaint.textSize = height * 0.34f
                    textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    textPaint.letterSpacing = 0.18f
                    canvas.drawText("AURA", width.toFloat(), height * 0.62f, textPaint)
                }
            }
        }
    }
}
