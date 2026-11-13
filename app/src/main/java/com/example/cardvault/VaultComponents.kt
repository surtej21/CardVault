package com.example.cardvault

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

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
        sizeDp: Int = 44,
        tinted: Boolean = false,
        tint: Int = VaultTheme.textSecondary,
        onClick: (FrameLayout) -> Unit
    ): FrameLayout = FrameLayout(context).apply {
        background = VaultTheme.iconButtonDrawable(context, 10, tinted)
        contentDescription = description
        isClickable = true
        isFocusable = true
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick(this)
        }
        VaultTheme.applySpringTouch(this)
        addView(icon(context, drawableRes, tint, 20), FrameLayout.LayoutParams(
            VaultTheme.dp(context, 20), VaultTheme.dp(context, 20), Gravity.CENTER
        ))
    }

    fun primaryButton(
        context: Context,
        label: String,
        iconRes: Int? = null,
        heightDp: Int = 54,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = VaultTheme.accentButtonDrawable(context, 12)
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
        addView(text(context, label, 15f, VaultTheme.buttonInk, 650, letterSpacing = 0.01f))
        layoutParams = LinearLayout.LayoutParams(-1, VaultTheme.dp(context, heightDp))
    }

    fun secondaryButton(
        context: Context,
        label: String,
        iconRes: Int? = null,
        heightDp: Int = 52,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = VaultTheme.secondaryButtonDrawable(context, 12)
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
        addView(text(context, label, 14.5f, VaultTheme.textPrimary, 550))
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
        setPadding(VaultTheme.dp(context, 16), 0, VaultTheme.dp(context, 16), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
        VaultTheme.applySpringTouch(this)
        layoutParams = LinearLayout.LayoutParams(-2, VaultTheme.dp(context, 40)).apply {
            marginEnd = VaultTheme.dp(context, 7)
        }
    }

    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(VaultTheme.borderFaint)
        layoutParams = LinearLayout.LayoutParams(-1, VaultTheme.dp(context, 1))
    }

    fun brassRule(context: Context, widthDp: Int = 44): View = View(context).apply {
        background = VaultTheme.brassRuleDrawable(context)
        layoutParams = LinearLayout.LayoutParams(VaultTheme.dp(context, widthDp), VaultTheme.dp(context, 1))
    }

    /** Flat engraved plate with neutral initials — no tint gradient. */
    fun articleMark(context: Context, title: String, tint: Int, sizeDp: Int = 40): TextView {
        val initials = title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            .take(2).joinToString("") { it.take(1).uppercase() }
            .ifBlank { "•" }
        return TextView(context).apply {
            text = initials
            gravity = Gravity.CENTER
            VaultTheme.styleText(this, VaultTheme.textSecondary, 13.5f, 600, heading = true)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = VaultTheme.dp(context, 10).toFloat()
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
            cornerRadius = VaultTheme.dp(context, 9).toFloat()
            setColor(Color.argb(12, 255, 255, 255))
            setStroke(VaultTheme.dp(context, 1), VaultTheme.border)
        }
        layoutParams = LinearLayout.LayoutParams(VaultTheme.dp(context, sizeDp), VaultTheme.dp(context, sizeDp))
        addView(icon(context, drawableRes, VaultTheme.textSecondary, (sizeDp * 0.52f).toInt()), FrameLayout.LayoutParams(
            VaultTheme.dp(context, (sizeDp * 0.52f).toInt()),
            VaultTheme.dp(context, (sizeDp * 0.52f).toInt()),
            Gravity.CENTER
        ))
    }

    /**
     * A flat instrument cell for metric clusters. No card background — the
     * parent panel supplies the hairlines, so four metrics read as one dial.
     */
    fun instrumentCell(
        context: Context,
        iconRes: Int,
        label: String,
        value: String,
        caption: String,
        tint: Int,
        onClick: () -> Unit = {}
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(VaultTheme.dp(context, 14), VaultTheme.dp(context, 13), VaultTheme.dp(context, 14), VaultTheme.dp(context, 13))
        isClickable = true
        isFocusable = true
        contentDescription = "$label, $value. $caption"
        background = VaultTheme.rowRipple(context)
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(icon(context, iconRes, VaultTheme.textMuted, 15), LinearLayout.LayoutParams(
            VaultTheme.dp(context, 15), VaultTheme.dp(context, 15)
        ).apply { marginEnd = VaultTheme.dp(context, 7) })
        top.addView(text(context, label.uppercase(), 9.5f, VaultTheme.textMuted, 600, letterSpacing = 0.1f))
        addView(top, LinearLayout.LayoutParams(-1, -2))

        addView(text(context, value, 28f, VaultTheme.textPrimary, 500, lineSpacing = 1f).apply {
            typeface = VaultTheme.mono(context)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = VaultTheme.dp(context, 7) })

        addView(text(context, caption, 10.5f, VaultTheme.textFaint, 450, lineSpacing = 1.22f).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = VaultTheme.dp(context, 3) })
    }

    /** Kept for call-site compatibility; now a flat instrument cell. */
    fun statTile(
        context: Context,
        iconRes: Int,
        tint: Int,
        label: String,
        value: String,
        caption: String,
        onClick: () -> Unit
    ): LinearLayout = instrumentCell(context, iconRes, label, value, caption, tint, onClick)

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
        center.addView(text(context, score.toString(), sizeDp * 0.25f, VaultTheme.textPrimary, 500, gravity = Gravity.CENTER).apply {
            typeface = VaultTheme.mono(context)
        })
        center.addView(text(context, label.uppercase(), 8.5f, VaultTheme.textFaint, 600, letterSpacing = 0.14f, gravity = Gravity.CENTER).apply {
            setPadding(0, VaultTheme.dp(context, 1), 0, 0)
        })
        addView(center, FrameLayout.LayoutParams(
            VaultTheme.dp(context, sizeDp), VaultTheme.dp(context, sizeDp), Gravity.CENTER
        ))
        layoutParams = LinearLayout.LayoutParams(VaultTheme.dp(context, sizeDp), VaultTheme.dp(context, sizeDp))
    }

    /** The signature element: a real vault door, drawn locally. */
    fun vaultDoor(context: Context, sizeDp: Int = 150): View = VaultDoorView(context).apply {
        layoutParams = LinearLayout.LayoutParams(VaultTheme.dp(context, sizeDp), VaultTheme.dp(context, sizeDp))
    }

    /** Kept for call-site compatibility. */
    fun orb(context: Context, sizeDp: Int = 150): View = vaultDoor(context, sizeDp)

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
                setColor(VaultTheme.surfaceElevated)
                cornerRadii = floatArrayOf(
                    VaultTheme.dp(context, 26).toFloat(), VaultTheme.dp(context, 26).toFloat(),
                    VaultTheme.dp(context, 26).toFloat(), VaultTheme.dp(context, 26).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setStroke(VaultTheme.dp(context, 1), VaultTheme.borderStrong)
            }
            setPadding(VaultTheme.dp(context, 16), VaultTheme.dp(context, 10), VaultTheme.dp(context, 16), VaultTheme.dp(context, 26))
        }
        val handle = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = VaultTheme.dp(context, 99).toFloat()
                setColor(Color.argb(56, 255, 255, 255))
            }
            layoutParams = LinearLayout.LayoutParams(VaultTheme.dp(context, 38), VaultTheme.dp(context, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = VaultTheme.dp(context, 4)
                bottomMargin = VaultTheme.dp(context, 14)
            }
        }
        content.addView(handle)
        content.addView(brassRule(context, 32).apply {
            layoutParams = LinearLayout.LayoutParams(VaultTheme.dp(context, 32), VaultTheme.dp(context, 1)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = VaultTheme.dp(context, 14)
            }
        })
        content.addView(text(context, eyebrow.uppercase(), 10f, VaultTheme.accent, 600, letterSpacing = 0.16f))
        content.addView(text(context, title, 22f, VaultTheme.textPrimary, 600, heading = true).apply {
            setPadding(0, VaultTheme.dp(context, 5), 0, VaultTheme.dp(context, 16))
        })

        // One ledger group, not a stack of little cards.
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = VaultTheme.glassShape(context, 12)
        }
        actions.forEachIndexed { index, action ->
            if (index > 0) {
                group.addView(View(context).apply {
                    setBackgroundColor(VaultTheme.borderFaint)
                }, LinearLayout.LayoutParams(-1, VaultTheme.dp(context, 1)).apply {
                    marginStart = VaultTheme.dp(context, 60)
                    marginEnd = VaultTheme.dp(context, 14)
                })
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(VaultTheme.dp(context, 12), VaultTheme.dp(context, 10), VaultTheme.dp(context, 12), VaultTheme.dp(context, 10))
                minimumHeight = VaultTheme.dp(context, 64)
                isClickable = true
                isFocusable = true
                contentDescription = action.title
                background = VaultTheme.rowRipple(context)
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    dialog.dismiss()
                    action.onClick()
                }
            }
            row.addView(tintedBadge(context, action.iconRes, action.tint, 38))
            val copy = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(VaultTheme.dp(context, 12), 0, VaultTheme.dp(context, 8), 0)
            }
            copy.addView(text(context, action.title, 14.5f, VaultTheme.textPrimary, 600))
            copy.addView(text(context, action.subtitle, 11f, VaultTheme.textMuted, 430).apply {
                setPadding(0, VaultTheme.dp(context, 2), 0, 0)
            })
            row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(icon(context, R.drawable.ic_chevron_right, VaultTheme.textFaint, 17))
            group.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        content.addView(group, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.68f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(android.R.style.Animation_Dialog)
            decorView.setPadding(VaultTheme.dp(context, 8), 0, VaultTheme.dp(context, 8), VaultTheme.dp(context, 8))
        }
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        return dialog
    }

    /**
     * The security score — a brass dial with engraved ticks, not a progress
     * bar. Solid arc on a quiet track, no sweep rainbow, no glow.
     */
    private class ScoreRingView(context: Context) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = VaultTheme.dp(context, 7).toFloat()
            color = Color.argb(26, 255, 255, 255)
            strokeCap = Paint.Cap.ROUND
        }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = VaultTheme.dp(context, 7).toFloat()
            strokeCap = Paint.Cap.ROUND
            color = VaultTheme.accent
        }
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = VaultTheme.dp(context, 1).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = VaultTheme.accentBright
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
            val inset = VaultTheme.dp(context, 7).toFloat()
            val radius = min(width, height) / 2f - inset
            canvas.drawCircle(cx, cy, radius, trackPaint)

            val tickOuter = radius - VaultTheme.dp(context, 9).toFloat()
            for (i in 0 until 60) {
                val angle = Math.toRadians((-90 + i * 6).toDouble())
                val major = i % 5 == 0
                val length = if (major) VaultTheme.dp(context, 5).toFloat() else VaultTheme.dp(context, 2.5f).toFloat()
                tickPaint.color = if (major) Color.argb(58, 255, 255, 255) else Color.argb(28, 255, 255, 255)
                val startR = tickOuter - length
                val c = cos(angle).toFloat()
                val s = sin(angle).toFloat()
                canvas.drawLine(
                    cx + c * startR, cy + s * startR,
                    cx + c * tickOuter, cy + s * tickOuter,
                    tickPaint
                )
            }

            val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(rect, -90f, 360f * progress / 100f, false, valuePaint)
            if (progress > 0f) {
                val end = Math.toRadians((-90 + 360 * progress / 100).toDouble())
                canvas.drawCircle(
                    cx + cos(end).toFloat() * radius,
                    cy + sin(end).toFloat() * radius,
                    VaultTheme.dp(context, 3).toFloat(),
                    dotPaint
                )
            }
        }
    }

    /**
     * The vault door: brushed steel plate, concentric rings, eight bolts, and
     * a brass wheel. Drawn entirely on-device — no assets, no network.
     */
    private class VaultDoorView(context: Context) : View(context) {
        private val dp = { n: Int -> VaultTheme.dp(context, n) }
        private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            color = Color.argb(120, 120, 126, 138)
        }
        private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            color = Color.argb(46, 255, 255, 255)
        }
        private val brushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.argb(8, 255, 255, 255)
        }
        private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(58, 62, 70)
        }
        private val boltEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            color = Color.argb(120, 8, 9, 11)
        }
        private val brassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val brassRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(4).toFloat()
            strokeCap = Paint.Cap.ROUND
            color = VaultTheme.accent
        }
        private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = VaultTheme.accent
        }
        private val hubEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            color = Color.argb(150, 30, 23, 12)
        }
        private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(12, 13, 16)
        }
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = VaultTheme.dp(context, 1.5f).toFloat()
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(30, 255, 255, 255)
        }
        private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = VaultTheme.dp(context, 1.5f).toFloat()
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(64, 201, 168, 107)
        }
        private val clipPath = Path()
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) / 2f - dp(2)

            // The door sits in a shallow recess.
            facePaint.color = Color.argb(110, 0, 0, 0)
            canvas.drawCircle(cx, cy + dp(2), radius, facePaint)

            facePaint.shader = RadialGradient(
                cx - radius * 0.28f, cy - radius * 0.32f, radius * 1.45f,
                intArrayOf(Color.rgb(46, 50, 58), Color.rgb(24, 26, 31), Color.rgb(10, 11, 14)),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius, facePaint)
            facePaint.shader = null

            canvas.drawCircle(cx, cy, radius, rimPaint)
            canvas.drawCircle(cx, cy, radius - dp(7), innerRingPaint)
            canvas.drawCircle(cx, cy, radius * 0.74f, innerRingPaint)
            canvas.drawCircle(cx, cy, radius * 0.56f, innerRingPaint)

            // Brushed steel: radial hairlines, clipped to the door.
            clipPath.reset()
            clipPath.addCircle(cx, cy, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)
            for (i in 0 until 48) {
                val angle = Math.toRadians((i * 7.5).toDouble())
                val c = cos(angle).toFloat()
                val s = sin(angle).toFloat()
                canvas.drawLine(
                    cx + c * radius * 0.2f, cy + s * radius * 0.2f,
                    cx + c * radius, cy + s * radius,
                    brushPaint
                )
            }
            canvas.restore()

            // Eight bolts around the rim.
            val boltR = radius * 0.9f
            val boltSize = radius * 0.045f
            for (i in 0 until 8) {
                val angle = Math.toRadians((i * 45 + 22.5).toDouble())
                val bx = cx + cos(angle).toFloat() * boltR
                val by = cy + sin(angle).toFloat() * boltR
                canvas.drawCircle(bx, by, boltSize, boltPaint)
                canvas.drawCircle(bx, by, boltSize, boltEdgePaint)
            }

            // Brass wheel: ring, four spokes, hub.
            val wheelR = radius * 0.36f
            canvas.drawCircle(cx, cy, wheelR, brassRingPaint)
            for (i in 0 until 4) {
                val angle = Math.toRadians((i * 90 + 45).toDouble())
                canvas.drawLine(
                    cx, cy,
                    cx + cos(angle).toFloat() * wheelR,
                    cy + sin(angle).toFloat() * wheelR,
                    brassRingPaint
                )
            }
            brassPaint.shader = LinearGradient(
                cx - wheelR, cy - wheelR, cx + wheelR, cy + wheelR,
                intArrayOf(VaultTheme.accentBright, VaultTheme.accent, VaultTheme.accentDeep),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius * 0.115f, brassPaint)
            canvas.drawCircle(cx, cy, radius * 0.115f, hubEdgePaint)
            brassPaint.shader = null
            canvas.drawCircle(cx, cy, radius * 0.038f, darkPaint)

            // A single highlight on the upper-left rim.
            rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(rect, -145f, 74f, false, highlightPaint)

            // The opening seam, right of the wheel.
            canvas.drawLine(
                cx + radius * 0.96f, cy - radius * 0.52f,
                cx + radius * 0.96f, cy + radius * 0.52f,
                seamPaint
            )
        }
    }
}
