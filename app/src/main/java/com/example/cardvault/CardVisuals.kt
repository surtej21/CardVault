package com.example.cardvault

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
        val glow: Int,
        val accent: Int,
        val darkText: Boolean = false
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

    private fun themeFor(context: Context, brand: CardBrand, id: String): CardTheme = when (brand) {
        CardBrand.VISA -> CardTheme(
            Color.rgb(28, 43, 73), Color.rgb(12, 18, 31),
            Color.rgb(88, 130, 255), Color.rgb(151, 183, 255)
        )
        CardBrand.MASTERCARD -> CardTheme(
            Color.rgb(50, 35, 65), Color.rgb(19, 15, 28),
            Color.rgb(177, 103, 255), Color.rgb(216, 181, 255)
        )
        CardBrand.AMEX -> CardTheme(
            Color.rgb(22, 55, 57), Color.rgb(9, 24, 28),
            Color.rgb(68, 211, 190), Color.rgb(148, 242, 220)
        )
        CardBrand.DISCOVER -> CardTheme(
            Color.rgb(59, 42, 27), Color.rgb(25, 18, 12),
            Color.rgb(255, 172, 88), Color.rgb(255, 213, 166)
        )
        CardBrand.RUPAY -> CardTheme(
            Color.rgb(29, 48, 37), Color.rgb(11, 21, 17),
            Color.rgb(82, 211, 145), Color.rgb(166, 241, 199)
        )
        CardBrand.VAULT -> {
            val hashed = abs(id.hashCode()) % 2
            if (hashed == 0) CardTheme(
                Color.rgb(43, 38, 63), Color.rgb(16, 15, 23),
                Color.rgb(143, 118, 255), Color.rgb(199, 187, 255)
            ) else CardTheme(
                Color.rgb(26, 39, 52), Color.rgb(11, 17, 23),
                Color.rgb(88, 158, 255), Color.rgb(162, 203, 255)
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
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(theme.start, theme.end)
            ).apply {
                cornerRadius = dp(21).toFloat()
                setStroke(dp(1), Color.argb(44, 255, 255, 255))
            }
            clipToOutline = true
            elevation = dp(9).toFloat()
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

        card.addView(View(context).apply {
            background = AuraGlowDrawable(theme.glow, 48)
        }, FrameLayout.LayoutParams(dp(190), dp(190), Gravity.TOP or Gravity.END).apply {
            topMargin = -dp(64)
            rightMargin = -dp(60)
        })

        card.addView(View(context).apply {
            background = AuraGlowDrawable(Color.argb(90, 255, 255, 255), 10)
        }, FrameLayout.LayoutParams(dp(134), dp(62), Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = -dp(38)
            leftMargin = -dp(50)
        })

        // Diagonal sheen: gives the card a glassy, lit-from-the-top-left depth cue.
        card.addView(View(context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.argb(36, 255, 255, 255),
                    Color.argb(8, 255, 255, 255),
                    Color.argb(0, 255, 255, 255)
                )
            )
        }, FrameLayout.LayoutParams(-1, -1))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(15), dp(18), dp(13))
        }
        card.addView(content, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(VaultUi.text(context, entry.title.ifBlank { "Payment card" }, 14f, Color.WHITE, 600).apply {
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
            setPadding(0, dp(14), 0, 0)
        }
        chipRow.addView(EmvChipView(context), LinearLayout.LayoutParams(dp(39), dp(29)))
        chipRow.addView(ContactlessView(context), LinearLayout.LayoutParams(dp(22), dp(22)).apply {
            marginStart = dp(10)
        })
        chipRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        chipRow.addView(VaultUi.text(
            context,
            if (isExpanded) "DETAILS OPEN" else "PRIVATE",
            8f, theme.accent, 700, letterSpacing = 0.16f
        ))
        content.addView(chipRow, LinearLayout.LayoutParams(-1, -2))

        val digits = entry.cardNumber.filter { it.isDigit() }
        val formatted = if (digits.length >= 4) {
            "••••  ••••  ••••  ${digits.takeLast(4)}"
        } else {
            "••••  ••••  ••••  ••••"
        }
        content.addView(VaultUi.text(
            context, formatted, 19f, Color.WHITE, 500, heading = true, letterSpacing = 0.08f
        ).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, dp(12), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))

        content.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val holder = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        holder.addView(VaultUi.text(context, "CARDHOLDER", 7.5f, Color.argb(160, 255, 255, 255), 600, letterSpacing = 0.14f))
        holder.addView(VaultUi.text(
            context,
            entry.cardholderName.ifBlank { "Your name" }.uppercase(),
            11.5f, Color.WHITE, 550, letterSpacing = 0.03f
        ).apply { setPadding(0, dp(3), 0, 0) })
        bottom.addView(holder, LinearLayout.LayoutParams(0, -2, 1f))

        val expiry = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        expiry.addView(VaultUi.text(context, "EXPIRES", 7.5f, Color.argb(160, 255, 255, 255), 600, letterSpacing = 0.14f, gravity = Gravity.END))
        expiry.addView(VaultUi.text(
            context,
            entry.expiry.ifBlank { "••/••" },
            11.5f, Color.WHITE, 600, heading = true, letterSpacing = 0.04f, gravity = Gravity.END
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
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(theme.start, theme.end)
            ).apply {
                cornerRadius = dp(19).toFloat()
                setStroke(dp(1), Color.argb(38, 255, 255, 255))
            }
            clipToOutline = true
            elevation = dp(6).toFloat()
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

        card.addView(View(context).apply {
            background = AuraGlowDrawable(theme.glow, 50)
        }, FrameLayout.LayoutParams(dp(168), dp(168), Gravity.TOP or Gravity.END).apply {
            topMargin = -dp(56)
            rightMargin = -dp(42)
        })

        // Sheen overlay for depth (clipped by the card outline).
        card.addView(View(context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.argb(32, 255, 255, 255),
                    Color.argb(7, 255, 255, 255),
                    Color.argb(0, 255, 255, 255)
                )
            )
        }, FrameLayout.LayoutParams(-1, -1))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(13), dp(17), dp(12))
        }
        card.addView(content, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = VaultUi.articleMark(context, entry.title, theme.mark, 36)
        top.addView(mark)
        val nameCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }
        nameCol.addView(VaultUi.text(context, entry.title.ifBlank { "Bank account" }, 14f, Color.WHITE, 600).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        nameCol.addView(VaultUi.text(context, "BANK ACCOUNT", 8f, Color.argb(155, 255, 255, 255), 600, letterSpacing = 0.13f).apply {
            setPadding(0, dp(3), 0, 0)
        })
        top.addView(nameCol, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(VaultUi.icon(context, R.drawable.ic_bank, Color.argb(190, 255, 255, 255), 18), LinearLayout.LayoutParams(dp(18), dp(18)))
        content.addView(top, LinearLayout.LayoutParams(-1, -2))

        content.addView(VaultUi.text(context, "ACCOUNT NUMBER", 7.5f, Color.argb(160, 255, 255, 255), 600, letterSpacing = 0.14f).apply {
            setPadding(0, dp(12), 0, 0)
        })
        content.addView(VaultUi.text(
            context, "••••  ••••  ${last4.take(4)}", 17f, Color.WHITE, 500, heading = true, letterSpacing = 0.08f
        ).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, dp(4), 0, 0)
        })
        content.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottom.addView(VaultUi.text(context, "SECURED ON THIS DEVICE", 8f, Color.argb(170, 255, 255, 255), 600, letterSpacing = 0.08f), LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(VaultUi.icon(
            context,
            if (isExpanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right,
            Color.argb(200, 255, 255, 255), 17
        ))
        content.addView(bottom, LinearLayout.LayoutParams(-1, -2))

        return card
    }

    private data class BankTheme(val start: Int, val end: Int, val glow: Int, val mark: Int)

    private fun bankTheme(id: String): BankTheme = when (abs(id.hashCode()) % 3) {
        0 -> BankTheme(Color.rgb(27, 47, 72), Color.rgb(12, 20, 31), Color.rgb(89, 145, 255), Color.rgb(113, 156, 255))
        1 -> BankTheme(Color.rgb(45, 35, 62), Color.rgb(16, 14, 24), Color.rgb(153, 112, 241), Color.rgb(155, 134, 255))
        else -> BankTheme(Color.rgb(24, 52, 44), Color.rgb(10, 21, 18), Color.rgb(72, 206, 153), Color.rgb(99, 214, 160))
    }

    /** Shared by the card widgets and the dashboard hero. */
    private class AuraGlowDrawable(private val color: Int, private val intensity: Int) : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            paint.shader = RadialGradient(
                bounds.exactCenterX(), bounds.exactCenterY(),
                min(bounds.width(), bounds.height()) / 2f,
                Color.argb(intensity, Color.red(color), Color.green(color), Color.blue(color)),
                Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawRect(bounds, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private class EmvChipView(context: Context) : View(context) {
        private val dp = { n: Int -> VaultTheme.dp(context, n) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            color = Color.argb(130, 92, 69, 23)
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val radius = dp(5).toFloat()
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            fill.shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(Color.rgb(245, 220, 154), Color.rgb(197, 157, 78)),
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
                    textPaint.color = Color.rgb(255, 255, 255)
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
