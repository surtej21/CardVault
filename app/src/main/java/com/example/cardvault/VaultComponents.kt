package com.example.cardvault

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/**
 * Small view builders shared by the vault screens. Keeping these here leaves
 * MainActivity focused on state, navigation, and vault behaviour.
 *
 * Visual rules (unslop): no gradient text, no rainbow instruments, no glow
 * orbs. Chrome stays quiet ink-steel; the brass accent appears only where the
 * vault does its job.
 */
object VaultUi {

    fun text(
        context: Context,
        value: String,
        sizeSp: Float = 14f,
        color: Int = VaultTheme.textPrimary,
        weight: Int = 500,
        heading: Boolean = false,
        letterSpacing: Float = 0f,
        gravity: Int = Gravity.START,
        lineSpacing: Float = 1.08f
    ): TextView = TextView(context).apply {
        text = value
        VaultTheme.styleText(this, color, sizeSp, weight, heading, letterSpacing)
        this.gravity = gravity
        setLineSpacing(0f, lineSpacing)
    }

    /** Kept for call-site compatibility; renders solid text, no gradient. */
    fun gradientText(
        context: Context,
        value: String,
        sizeSp: Float,
        colors: IntArray? = null,
        weight: Int = 600,
        lineSpacing: Float = 1.12f
    ): TextView = TextView(context).apply {
        text = value
        VaultTheme.styleText(this, VaultTheme.textPrimary, sizeSp, weight, heading = true)
        setLineSpacing(0f, lineSpacing)
    }

    fun icon(
        context: Context,
        drawableRes: Int,
        color: Int = VaultTheme.textSecondary,
        sizeDp: Int = 20
    ): ImageView = ImageView(context).apply {
        setImageResource(drawableRes)
        imageTintList = android.content.res.ColorStateList.valueOf(color)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        contentDescription = null
        layoutParams = LinearLayout.LayoutParams(VaultTheme.dp(context, sizeDp), VaultTheme.dp(context, sizeDp))
    }

    fun iconButton(
        context: Context,
        drawableRes: Int,
        description: String,
        sizeDp: Int = 40,
        tinted: Boolean = false,
        tint: Int = VaultTheme.textSecondary,
        onClick: (FrameLayout) -> Unit
    ): FrameLayout = FrameLayout(context).apply {
        background = VaultTheme.iconButtonDrawable(context, 12, tinted)
        contentDescription = description
        isClickable = true
        isFocusable = true
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick(this)
        }
        VaultTheme.applySpringTouch(this)
        addView(icon(context, drawableRes, tint, 19), FrameLayout.LayoutParams(
            VaultTheme.dp(context, 19), VaultTheme.dp(context, 19), Gravity.CENTER
        ))
    }

    fun primaryButton(
        context: Context,
        label: String,
        iconRes: Int? = null,
        heightDp: Int = 52,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = VaultTheme.accentButtonDrawable(context, 14)
        isClickable = true
        isFocusable = true
        contentDescription = label
        elevation = VaultTheme.dp(context, 2).toFloat()
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
        VaultTheme.applySpringTouch(this)
        if (iconRes != null) {
            addView(icon(context, iconRes, VaultTheme.buttonInk, 18), LinearLayout.LayoutParams(
                VaultTheme.dp(context, 18), VaultTheme.dp(context, 18)
            ).apply { marginEnd = VaultTheme.dp(context, 8) })
        }
        addView(text(context, label, 14.5f, VaultTheme.buttonInk, 600, letterSpacing = 0.01f))
        layoutParams = LinearLayout.LayoutParams(-1, VaultTheme.dp(context, heightDp))
    }

    fun secondaryButton(
        context: Context,
        label: String,
        iconRes: Int? = null,
        heightDp: Int = 50,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = VaultTheme.secondaryButtonDrawable(context, 14)
        isClickable = true
        isFocusable = true
        contentDescription = label
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
        VaultTheme.applySpringTouch(this)
        if (iconRes != null) {
            addView(icon(context, iconRes, VaultTheme.textSecondary, 18), LinearLayout.LayoutParams(
                VaultTheme.dp(context, 18), VaultTheme.dp(context, 18)
            ).apply { marginEnd = VaultTheme.dp(context, 8) })
        }
        addView(text(context, label, 14f, VaultTheme.textPrimary, 550))
        layoutParams = LinearLayout.LayoutParams(-1, VaultTheme.dp(context, heightDp))
    }

    fun pill(
        context: Context,
        label: String,
        selected: Boolean,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        VaultTheme.styleText(this, if (selected) VaultTheme.textPrimary else VaultTheme.textSecondary, 12.5f, 550)
        gravity = Gravity.CENTER
        background = VaultTheme.pillDrawable(context, selected)
        setPadding(VaultTheme.dp(context, 15), 0, VaultTheme.dp(context, 15), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
        VaultTheme.applySpringTouch(this)
        layoutParams = LinearLayout.LayoutParams(-2, VaultTheme.dp(context, 36)).apply {
            marginEnd = VaultTheme.dp(context, 7)
        }
    }

    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(VaultTheme.borderFaint)
        layoutParams = LinearLayout.LayoutParams(-1, VaultTheme.dp(context, 1))
    }

    /** Flat engraved plate with neutral initials — no tint gradient. */
    fun articleMark(context: Context, title: String, tint: Int, sizeDp: Int = 40): TextView {
        val initials = title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            .take(2).joinToString("") { it.take(1).uppercase() }
            .ifBlank { "•" }
        return TextView(context).apply {
            text = initials
            gravity = Gravity.CENTER
            VaultTheme.styleText(this, VaultTheme.textSecondary, 13f, 550)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = VaultTheme.dp(context, 12).toFloat()
                setColor(VaultTheme.surfaceElevated)
                setStroke(VaultTheme.dp(context, 1), VaultTheme.border)
            }
            layoutParams = LinearLayout.LayoutParams(VaultTheme.dp(context, sizeDp), VaultTheme.dp(context, sizeDp))
        }
    }

    /** Neutral chip; the icon carries no per-kind hue. */
    fun tintedBadge(
        context: Context,
        drawableRes: Int,
        tint: Int,
        sizeDp: Int = 34
    ): FrameLayout = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = VaultTheme.dp(context, 10).toFloat()
            setColor(Color.argb(14, 255, 255, 255))
            setStroke(VaultTheme.dp(context, 1), VaultTheme.border)
        }
        layoutParams = LinearLayout.LayoutParams(VaultTheme.dp(context, sizeDp), VaultTheme.dp(context, sizeDp))
        addView(icon(context, drawableRes, VaultTheme.textSecondary, (sizeDp * 0.52f).toInt()), FrameLayout.LayoutParams(
            VaultTheme.dp(context, (sizeDp * 0.52f).toInt()),
            VaultTheme.dp(context, (sizeDp * 0.52f).toInt()),
            Gravity.CENTER
        ))
    }

    fun statTile(
        context: Context,
        iconRes: Int,
        tint: Int,
        label: String,
        value: String,
        caption: String,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = VaultTheme.glassShape(context, 16)
        setPadding(VaultTheme.dp(context, 14), VaultTheme.dp(context, 13), VaultTheme.dp(context, 14), VaultTheme.dp(context, 12))
        isClickable = true
        isFocusable = true
        contentDescription = "$label, $value. $caption"
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
        VaultTheme.applySpringTouch(this)

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(tintedBadge(context, iconRes, tint, 30), LinearLayout.LayoutParams(
            VaultTheme.dp(context, 30), VaultTheme.dp(context, 30)
        ))
        top.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(icon(context, R.drawable.ic_arrow_up_right, VaultTheme.textFaint, 15), LinearLayout.LayoutParams(
            VaultTheme.dp(context, 15), VaultTheme.dp(context, 15)
        ))
        addView(top, LinearLayout.LayoutParams(-1, -2))

        addView(text(context, label, 11.5f, VaultTheme.textMuted, 500).apply {
            setPadding(0, VaultTheme.dp(context, 11), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))

        addView(text(context, value, 24f, VaultTheme.textPrimary, 600, heading = true, lineSpacing = 1f).apply {
            setLineSpacing(0f, 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = VaultTheme.dp(context, 2)
        })

        addView(text(context, caption, 10f, VaultTheme.textFaint, 450).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = VaultTheme.dp(context, 3) })
    }

    fun scoreRing(
        context: Context,
        score: Int,
        sizeDp: Int = 116,
        label: String = "score"
    ): FrameLayout = FrameLayout(context).apply {
        val ring = ScoreRingView(context).apply { setScore(score) }
        addView(ring, FrameLayout.LayoutParams(
            VaultTheme.dp(context, sizeDp), VaultTheme.dp(context, sizeDp)
        ))
        val center = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        center.addView(text(context, score.toString(), sizeDp * 0.24f, VaultTheme.textPrimary, 600, heading = true, gravity = Gravity.CENTER))
        center.addView(text(context, label, 9f, VaultTheme.textMuted, 500, letterSpacing = 0f, gravity = Gravity.CENTER))
        addView(center, FrameLayout.LayoutParams(
            VaultTheme.dp(context, sizeDp), VaultTheme.dp(context, sizeDp), Gravity.CENTER
        ))
        layoutParams = LinearLayout.LayoutParams(VaultTheme.dp(context, sizeDp), VaultTheme.dp(context, sizeDp))
    }

    fun orb(context: Context, sizeDp: Int = 150): View = VaultEmblemView(context).apply {
        layoutParams = LinearLayout.LayoutParams(VaultTheme.dp(context, sizeDp), VaultTheme.dp(context, sizeDp))
    }

    data class SheetAction(
        val iconRes: Int,
        val tint: Int,
        val title: String,
        val subtitle: String,
        val onClick: () -> Unit
    )

    fun showActionSheet(
        context: Context,
        eyebrow: String,
        title: String,
        actions: List<SheetAction>
    ): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(VaultTheme.surface)
                cornerRadii = floatArrayOf(
                    VaultTheme.dp(context, 26).toFloat(), VaultTheme.dp(context, 26).toFloat(),
                    VaultTheme.dp(context, 26).toFloat(), VaultTheme.dp(context, 26).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setStroke(VaultTheme.dp(context, 1), VaultTheme.borderStrong)
            }
            setPadding(VaultTheme.dp(context, 18), VaultTheme.dp(context, 10), VaultTheme.dp(context, 18), VaultTheme.dp(context, 28))
        }
        val handle = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = VaultTheme.dp(context, 99).toFloat()
                setColor(Color.argb(60, 255, 255, 255))
            }
            layoutParams = LinearLayout.LayoutParams(VaultTheme.dp(context, 38), VaultTheme.dp(context, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = VaultTheme.dp(context, 4)
                bottomMargin = VaultTheme.dp(context, 20)
            }
        }
        content.addView(handle)
        content.addView(text(context, eyebrow, 11f, VaultTheme.textMuted, 500, letterSpacing = 0.01f))
        content.addView(text(context, title, 21f, VaultTheme.textPrimary, 600, heading = true).apply {
            setPadding(0, VaultTheme.dp(context, 4), 0, VaultTheme.dp(context, 16))
        })
        actions.forEach { action ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = VaultTheme.glassShape(context, 14)
                setPadding(VaultTheme.dp(context, 12), VaultTheme.dp(context, 11), VaultTheme.dp(context, 12), VaultTheme.dp(context, 11))
                isClickable = true
                isFocusable = true
                contentDescription = action.title
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    dialog.dismiss()
                    action.onClick()
                }
                VaultTheme.applySpringTouch(this)
            }
            row.addView(tintedBadge(context, action.iconRes, action.tint, 38))
            val copy = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(VaultTheme.dp(context, 12), 0, VaultTheme.dp(context, 8), 0)
            }
            copy.addView(text(context, action.title, 14f, VaultTheme.textPrimary, 550))
            copy.addView(text(context, action.subtitle, 11f, VaultTheme.textFaint, 430).apply {
                setPadding(0, VaultTheme.dp(context, 2), 0, 0)
            })
            row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(icon(context, R.drawable.ic_chevron_right, VaultTheme.textFaint, 17))
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = VaultTheme.dp(context, 8) })
        }
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.66f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(android.R.style.Animation_Dialog)
            decorView.setPadding(VaultTheme.dp(context, 8), 0, VaultTheme.dp(context, 8), VaultTheme.dp(context, 8))
        }
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        return dialog
    }

    /**
     * The security score — the one brass instrument. Solid arc on a quiet
     * track, no sweep rainbow, no glow.
     */
    private class ScoreRingView(context: Context) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = VaultTheme.dp(context, 9).toFloat()
            color = Color.argb(24, 255, 255, 255)
            strokeCap = Paint.Cap.ROUND
        }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = VaultTheme.dp(context, 9).toFloat()
            strokeCap = Paint.Cap.ROUND
            color = VaultTheme.accent
        }
        private var progress = 0f

        fun setScore(score: Int) {
            val target = score.coerceIn(0, 100).toFloat()
            ValueAnimator.ofFloat(0f, target).apply {
                duration = 700
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val inset = VaultTheme.dp(context, 8).toFloat()
            val radius = min(width, height) / 2f - inset
            canvas.drawCircle(cx, cy, radius, trackPaint)
            val rect = android.graphics.RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(rect, -90f, 360f * progress / 100f, false, valuePaint)
        }
    }

    /**
     * The vault emblem: a flat raised plate, hairline ring, brass shield.
     * No radial glows, no sparkle dots.
     */
    private class VaultEmblemView(context: Context) : View(context) {
        private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = VaultTheme.surfaceElevated
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = VaultTheme.border
            strokeWidth = VaultTheme.dp(context, 1).toFloat()
        }
        private val shield = context.getDrawable(R.drawable.ic_shield_check)?.mutate()?.apply {
            setTint(VaultTheme.accent)
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = min(width, height) * 0.27f
            canvas.drawCircle(cx, cy, r, platePaint)
            canvas.drawCircle(cx, cy, r, ringPaint)
            shield?.let {
                val size = (r * 0.78f).toInt()
                it.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
                it.draw(canvas)
            }
        }
    }
}
