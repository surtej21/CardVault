package com.example.cardvault

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.TextureView
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_CARD_CAMERA_PERM = 9011
        private const val REQUEST_CARD_IMAGE = 9013
        private const val CLIP_TOKEN_EXTRA = "com.example.cardvault.extra.CLIP_TOKEN"
        private const val CLIP_TOKEN_PREFIX = "aurelia-vault:"
        private val PRIMARY_SECTIONS = listOf("Overview", "Cards", "Bank logins", "Passwords", "Custom")
    }

    private data class CardPrefill(val number: String = "", val expiry: String = "", val cvv: String = "")
    private data class SectionSpec(
        val eyebrow: String,
        val title: String,
        val description: String,
        val kind: String?,
        val icon: Int,
        val tint: Int,
        val searchHint: String
    )

    private lateinit var root: LinearLayout
    private lateinit var contentScroll: ScrollView

    /** Session lives on the process (VaultRuntime), not on this Activity, so
     *  camera/picker detours don't log the user out. */
    private var session: VaultStore.Session?
        get() = VaultRuntime.session
        set(value) { VaultRuntime.session = value }

    private var recoveryToShow: String? = null
    private var recoveryContinuation: (() -> Unit)? = null
    private var pendingClipboardClear: Runnable? = null
    private val galleryExecutor by lazy { Executors.newSingleThreadExecutor() }
    private var cameraEngine: CardCamera? = null
    private var importCleanupFile: File? = null
    private var importGeneration = 0
    private var pendingReturnToVault = false
    private val importPrefs by lazy { getSharedPreferences("import_resume", MODE_PRIVATE) }

    /** In-app import screens: "camera" | "picker" | "resume" | null. */
    private var importMode: String? = null

    private var activeSection = "Overview"
    private var searchQuery = ""
    private var activeDialog: Dialog? = null
    private val expandedCards = mutableSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoLockMillis = 60_000L
    private val idleLock = Runnable {
        if (session != null && recoveryToShow == null && importMode == null && activeDialog == null) lockNow()
    }
    private var recoveryUnlockMode = false
    private val predictiveBackCallback = OnBackInvokedCallback {
        if (!handleBackPress()) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeSection = VaultRuntime.section
        // If the OS killed the process during an import, restore the session
        // from the Keystore-wrapped token instead of showing the unlock screen.
        if (VaultRuntime.session == null && VaultStore.exists(this)) {
            SessionKeeper.restore(this)?.let { dek ->
                runCatching { VaultStore.Session(dek, VaultStore.entriesWithDek(this, dek)) }
                    .onSuccess { VaultRuntime.session = it }
                    .onFailure {
                        dek.fill(0)
                        SessionKeeper.clear(this)
                    }
            }
        }
        // If an import photo is waiting, keep the import screen up instead of
        // drawing the vault homepage for a frame before OCR resumes. The same
        // applies when the picker/camera was open and the process was killed:
        // restore the import screen until the result arrives.
        if (session != null && importMode == null) {
            val savedMode = importPrefs.getString("mode", null)
            val savedAt = importPrefs.getLong("at", 0L)
            if (savedMode != null && System.currentTimeMillis() - savedAt <= 15 * 60_000L) {
                importMode = if (importPrefs.getString("file", null) != null) "resume" else savedMode
            } else if (savedMode != null) {
                clearPendingImport()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                predictiveBackCallback
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        render()
        root.post { resumePendingImportIfAny() }
        window.insetsController?.setSystemBarsAppearance(
            0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(idleLock)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(predictiveBackCallback)
        }
        cameraEngine?.close()
        cameraEngine = null
        // VaultRuntime still holds the Session if the system merely destroyed
        // this Activity (rotation / don't-keep-activities) — only wipe the key
        // when the user is actually exiting the app.
        if (isFinishing) {
            SessionKeeper.clear(this)
            session?.dek?.fill(0)
            VaultRuntime.session = null
        }
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        VaultRuntime.section = activeSection
        // Refresh the resume token while an import is in flight. The picker
        // takes the foreground here, and this is exactly when the process is
        // most likely to be killed by the OS.
        if (importMode != null) session?.let { SessionKeeper.save(this, it.dek) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (requestCode == REQUEST_CARD_CAMERA_PERM) {
                importMode = "camera"
                render()
            }
        } else {
            val permissionName = Manifest.permission.CAMERA
            val explain = "Camera permission is needed to scan a card."
            // "Don't ask again" makes this false even though we asked just now —
            // the only way forward is the system Settings page for this app.
            if (!shouldShowRequestPermissionRationale(permissionName)) {
                AlertDialog.Builder(this)
                    .setTitle("Permission turned off")
                    .setMessage("$explain Tap Open settings, then switch it on — it stays on.")
                    .setPositiveButton("Open settings") { _, _ -> openAppSettings() }
                    .setNegativeButton("Not now", null)
                    .show()
            } else {
                message(explain)
            }
        }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        }.onFailure { message("Open phone Settings → Apps → Aura Vault → Permissions.") }
    }

    override fun onResume() {
        super.onResume()
        if (session != null && importMode == null) {
            mainHandler.removeCallbacks(idleLock)
            mainHandler.postDelayed(idleLock, autoLockMillis)
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (session != null && recoveryToShow == null && importMode == null) {
            mainHandler.removeCallbacks(idleLock)
            mainHandler.postDelayed(idleLock, autoLockMillis)
        }
    }

    @Deprecated("Deprecated in Java")
    @SuppressLint("GestureBackNavigation") // API 33+ predictive back is handled by predictiveBackCallback.
    override fun onBackPressed() {
        if (!handleBackPress()) super.onBackPressed()
    }

    private fun handleBackPress(): Boolean {
        if (importMode != null) {
            // Same cleanup as the on-screen Cancel: close the camera, delete the
            // pending photo, drop the resume token and return to the vault.
            cancelImport()
            return true
        }
        if (session != null && recoveryToShow == null) {
            if (searchQuery.isNotBlank()) {
                searchQuery = ""
                render()
                return true
            }
            if (activeSection != "Overview") {
                activeSection = "Overview"
                render()
                return true
            }
        }
        return false
    }

    private fun dp(n: Int): Int = VaultTheme.dp(this, n)

    // ==========================================
    // SCREEN SHELL
    // ==========================================
    private fun render(preserveScroll: Boolean = false) {
        val previousScroll = if (preserveScroll && ::contentScroll.isInitialized) contentScroll.scrollY else 0
        val vaultVisible = session != null && recoveryToShow == null && importMode == null

        val screen = FrameLayout(this).apply { background = VaultTheme.auroraBackground() }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val scroll = object : ScrollView(this) {
            private var startX = 0f
            private var startY = 0f
            private var horizontalPageSwipe = false

            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.x
                        startY = event.y
                        horizontalPageSwipe = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - startX
                        val dy = event.y - startY
                        if (kotlin.math.abs(dx) > dp(48) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.3f) {
                            horizontalPageSwipe = true
                            return true
                        }
                    }
                }
                return super.onInterceptTouchEvent(event)
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_UP && horizontalPageSwipe) {
                    val index = PRIMARY_SECTIONS.indexOf(activeSection).coerceAtLeast(0)
                    val dx = event.x - startX
                    val next = (index + if (dx < 0) 1 else -1).coerceIn(0, PRIMARY_SECTIONS.lastIndex)
                    horizontalPageSwipe = false
                    if (next != index) {
                        activeSection = PRIMARY_SECTIONS[next]
                        searchQuery = ""
                        render()
                    }
                    return true
                }
                return super.onTouchEvent(event)
            }

            override fun performClick(): Boolean = super.performClick()
        }.apply {
            isFillViewport = true
            isSmoothScrollingEnabled = true
            addView(root, FrameLayout.LayoutParams(-1, -2))
        }

        contentScroll = scroll
        screen.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val topBar = if (vaultVisible) buildTopBar() else null
        val navWrap = if (vaultVisible) buildBottomNav() else null
        topBar?.let { screen.addView(it, FrameLayout.LayoutParams(-1, -2, Gravity.TOP)) }
        navWrap?.let { screen.addView(it, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)) }

        screen.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            if (vaultVisible) {
                topBar?.setPadding(dp(20), bars.top + dp(8), dp(20), dp(10))
                root.setPadding(dp(20), bars.top + dp(80), dp(20), bars.bottom + dp(120))
                navWrap?.setPadding(dp(16), 0, dp(16), bars.bottom + dp(12))
            } else {
                root.setPadding(dp(24), bars.top + dp(24), dp(24), bars.bottom + dp(24))
            }
            insets
        }

        setContentView(screen)
        screen.requestApplyInsets()

        when {
            importMode != null && session != null -> showCardImport()
            recoveryToShow != null -> showRecovery()
            session != null -> showVault()
            !VaultStore.exists(this) -> showSetup()
            recoveryUnlockMode -> renderRecoveryUnlock()
            else -> showUnlock()
        }

        contentScroll.post {
            if (previousScroll > 0) contentScroll.scrollTo(0, previousScroll)
        }
        root.alpha = 0f
        root.translationY = dp(10).toFloat()
        root.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.argb(248, 7, 8, 12),
                    Color.argb(232, 7, 8, 12),
                    Color.argb(0, 7, 8, 12)
                )
            )
            setPadding(dp(20), dp(8), dp(20), dp(10))
        }

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brand.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(VaultTheme.surfaceElevated)
                setStroke(dp(1), VaultTheme.border)
            }
            addView(VaultUi.icon(this@MainActivity, R.drawable.ic_shield, VaultTheme.accent, 18), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        val wordmark = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(9), 0, 0, dp(1))
        }
        wordmark.addView(VaultUi.text(this, "Aura", 15.5f, VaultTheme.textPrimary, 600, heading = true, letterSpacing = 0.005f))
        wordmark.addView(VaultUi.text(this, "vault", 10.5f, VaultTheme.textMuted, 500, letterSpacing = 0.02f).apply {
            setPadding(dp(4), 0, 0, dp(1))
        })
        brand.addView(wordmark)
        bar.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))

        val add = FrameLayout(this).apply {
            background = VaultTheme.accentButtonDrawable(this@MainActivity, 11)
            contentDescription = "Add item"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                chooseItemType()
            }
            VaultTheme.applySpringTouch(this)
            addView(VaultUi.icon(this@MainActivity, R.drawable.ic_plus, Color.WHITE, 19), FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER))
        }
        bar.addView(add, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(7) })
        bar.addView(VaultUi.iconButton(this, R.drawable.ic_settings, "Settings", 38) { openSettings() }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(7) })
        bar.addView(VaultUi.iconButton(this, R.drawable.ic_lock, "Lock vault", 38) {
            window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            lockNow()
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        return bar
    }

    private fun buildBottomNav(): FrameLayout {
        val wrap = FrameLayout(this).apply {
            // Fade scrolled content under the bar instead of letting it hard-overlap the pill.
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.argb(0, 7, 8, 12),
                    Color.argb(150, 7, 8, 12),
                    Color.argb(240, 7, 8, 12),
                    Color.argb(252, 7, 8, 12)
                )
            )
            setPadding(dp(16), dp(10), dp(16), dp(12))
            elevation = dp(12).toFloat()
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = VaultTheme.glassShape(this@MainActivity, 22, strong = true)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        PRIMARY_SECTIONS.forEach { section ->
            val selected = activeSection == section
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = navLabel(section)
                if (selected) {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(17).toFloat()
                        setColor(VaultTheme.surfaceElevated)
                        setStroke(dp(1), VaultTheme.borderStrong)
                    }
                }
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (activeSection != section) {
                        activeSection = section
                        searchQuery = ""
                        render()
                    }
                }
            }
            item.addView(VaultUi.icon(this, navIcon(section), if (selected) VaultTheme.accent else VaultTheme.textMuted, 20))
            item.addView(VaultUi.text(
                this, navLabel(section), 9f,
                if (selected) VaultTheme.textPrimary else VaultTheme.textMuted,
                if (selected) 600 else 500,
                gravity = Gravity.CENTER
            ).apply { setPadding(0, dp(3), 0, 0) })
            bar.addView(item, LinearLayout.LayoutParams(0, dp(54), 1f))
        }
        wrap.addView(bar, FrameLayout.LayoutParams(-1, dp(64)))
        return wrap
    }

    private fun navLabel(section: String) = when (section) {
        "Overview" -> "Home"
        "Cards" -> "Cards"
        "Bank logins" -> "Bank"
        "Passwords" -> "Logins"
        else -> "Custom"
    }

    private fun navIcon(section: String) = when (section) {
        "Overview" -> R.drawable.ic_grid
        "Cards" -> R.drawable.ic_card
        "Bank logins" -> R.drawable.ic_bank
        "Passwords" -> R.drawable.ic_key
        else -> R.drawable.ic_folder
    }

    // ==========================================
    // AUTH SCREENS
    // ==========================================
    private fun authHeader(eyebrow: String, title: String, subtitle: String) {
        val lockup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        lockup.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(VaultTheme.surfaceElevated)
                setStroke(dp(1), VaultTheme.border)
            }
            addView(VaultUi.icon(this@MainActivity, R.drawable.ic_shield_check, VaultTheme.accent, 27), FrameLayout.LayoutParams(dp(27), dp(27), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        lockup.addView(VaultUi.text(this, "Aura vault", 11f, VaultTheme.accent, 600, heading = true, letterSpacing = 0.03f, gravity = Gravity.CENTER).apply {
            setPadding(0, dp(14), 0, 0)
        })
        root.addView(lockup, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(26) })

        root.addView(VaultUi.text(this, eyebrow, 9.5f, VaultTheme.textMuted, 500, letterSpacing = 0.01f, gravity = Gravity.CENTER))
        root.addView(VaultUi.gradientText(this, title, 27f, weight = 600).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(10))
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(VaultUi.text(this, subtitle, 13f, VaultTheme.textSecondary, 430, gravity = Gravity.CENTER, lineSpacing = 1.35f).apply {
            setPadding(dp(10), 0, dp(10), dp(22))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun formPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = VaultTheme.glassShape(this@MainActivity, 20, strong = true)
        setPadding(dp(16), dp(16), dp(16), dp(8))
    }

    private fun fieldRow(
        host: LinearLayout,
        label: String,
        hint: String = "",
        secret: Boolean = false,
        initialValue: String = ""
    ): EditText {
        if (label.isNotBlank()) {
            host.addView(VaultUi.text(this, label, 9.5f, VaultTheme.textMuted, 500, letterSpacing = 0.01f).apply {
                setPadding(dp(2), 0, 0, dp(6))
            })
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 13)
            setPadding(dp(14), 0, dp(8), 0)
        }
        val edit = EditText(this).apply {
            this.hint = hint
            textSize = 14f
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setTextColor(VaultTheme.textPrimary)
            setHintTextColor(VaultTheme.textFaint)
            background = null
            typeface = VaultTheme.typeface(this@MainActivity, 450)
            setPadding(0, dp(14), 0, dp(14))
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
            imeOptions = if (secret) android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            else android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            if (initialValue.isNotBlank()) {
                setText(initialValue)
                setSelection(initialValue.length)
            }
        }
        container.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))

        if (secret) {
            var visible = false
            val eye = FrameLayout(this).apply {
                background = VaultTheme.iconButtonDrawable(this@MainActivity, 9)
                contentDescription = "Show $label"
                isClickable = true
                isFocusable = true
                addView(VaultUi.icon(this@MainActivity, R.drawable.ic_eye, VaultTheme.textMuted, 17), FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER))
                setOnClickListener {
                    visible = !visible
                    edit.inputType = if (visible) {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    } else {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                    edit.setSelection(edit.text.length)
                    (getChildAt(0) as ImageView).setImageResource(if (visible) R.drawable.ic_eye_off else R.drawable.ic_eye)
                    contentDescription = if (visible) "Hide $label" else "Show $label"
                }
            }
            container.addView(eye, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(4) })
        }

        edit.setOnFocusChangeListener { _, hasFocus ->
            container.background = VaultTheme.glassShape(
                this@MainActivity,
                13,
                strong = hasFocus,
                borderColor = if (hasFocus) VaultTheme.accent else VaultTheme.border
            )
        }
        host.addView(container, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(12) })
        return edit
    }

    private fun primaryBtn(text: String, iconRes: Int? = null, action: () -> Unit): LinearLayout {
        val button = VaultUi.primaryButton(this, text, iconRes, 52, action)
        root.addView(button, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(10) })
        return button
    }

    private fun secondaryBtn(text: String, iconRes: Int? = null, action: () -> Unit): LinearLayout {
        val button = VaultUi.secondaryButton(this, text, iconRes, 50, action)
        root.addView(button, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(10) })
        return button
    }

    private fun textBtn(text: String, color: Int = VaultTheme.textSecondary, action: () -> Unit) {
        val tv = VaultUi.text(this, text, 12.5f, color, 500, gravity = Gravity.CENTER).apply {
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        root.addView(tv, LinearLayout.LayoutParams(-1, -2))
    }

    private fun showSetup() {
        authHeader(
            "Private by design",
            "Create your vault",
            "One master password protects everything. It never leaves this device."
        )

        val form = formPanel()
        val password = fieldRow(form, "Master password", "Use at least 12 characters", secret = true)
        val confirm = fieldRow(form, "Confirm password", "Repeat your master password", secret = true)
        val strength = VaultUi.text(this, "Strength  ·  waiting", 10.5f, VaultTheme.textFaint, 500)
        strength.setPadding(dp(2), 0, 0, dp(12))
        form.addView(strength)
        root.addView(form, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        password.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s.isNullOrEmpty()) {
                    strength.text = "Strength  ·  waiting"
                    strength.setTextColor(VaultTheme.textFaint)
                } else {
                    val (label, color) = SecurityAudit.getStrengthLabel(s.toString())
                    strength.text = "Strength  ·  $label"
                    strength.setTextColor(color)
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        primaryBtn("Create encrypted vault", R.drawable.ic_shield_check) {
            if (password.text.length < 12) return@primaryBtn message("Password must be at least 12 characters.")
            if (!sameText(password.text, confirm.text)) return@primaryBtn message("Passwords do not match.")
            val chars = charsOf(password.text)
            try {
                runCatching { VaultStore.create(this, chars) }
                    .onSuccess { result ->
                        password.text.clear()
                        confirm.text.clear()
                        session = result.session
                        recoveryToShow = result.recoveryKey
                        render()
                    }
                    .onFailure { message(it.message ?: "Could not create vault.") }
            } finally {
                chars.fill('\u0000')
            }
        }

        val note = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        note.addView(VaultUi.icon(this, R.drawable.ic_lock, VaultTheme.accentMint, 15), LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(7) })
        note.addView(VaultUi.text(this, "AES-256-GCM  ·  Local only  ·  No cloud", 10f, VaultTheme.textFaint, 500), LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    private fun showUnlock() {
        authHeader(
            "Welcome back",
            "Unlock your vault",
            "Your vault is encrypted on this device and nowhere else."
        )

        val biometric = VaultStore.biometricPayload(this)
        if (biometric != null) {
            secondaryBtn("Unlock with fingerprint", R.drawable.ic_fingerprint) { unlockBiometric(biometric) }
        }

        val form = formPanel()
        val password = fieldRow(form, "Master password", "Enter your master password", secret = true)
        root.addView(form, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        val unlock = VaultUi.primaryButton(this, "Unlock vault", R.drawable.ic_lock, 52) {
            val chars = charsOf(password.text)
            try {
                runCatching { VaultStore.unlockWithPassword(this, chars) }
                    .onSuccess {
                        password.text.clear()
                        session = it
                        recoveryUnlockMode = false
                        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        mainHandler.postDelayed(idleLock, autoLockMillis)
                        render()
                        root.post { resumePendingImportIfAny() }
                        root.post { maybeOfferFirstFingerprintPrompt() }
                    }
                    .onFailure { message("Invalid password.") }
            } finally {
                chars.fill('\u0000')
            }
        }.apply {
            isEnabled = false
            alpha = 0.55f
        }
        root.addView(unlock, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(10) })

        password.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val ready = !s.isNullOrEmpty()
                unlock.isEnabled = ready
                unlock.alpha = if (ready) 1f else 0.55f
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        textBtn("Forgot password?  Use recovery key", VaultTheme.accent) { showRecoveryUnlock() }
    }

    private fun showRecovery() {
        authHeader(
            "Keep this safe",
            "Your recovery key",
            "Save this key outside your phone. It is the only way back in if you forget your master password."
        )

        val keyPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 18, strong = true)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        keyPanel.addView(VaultUi.text(this, "Recovery key  ·  shown once", 9.5f, VaultTheme.accent, 500, letterSpacing = 0.01f))
        keyPanel.addView(TextView(this).apply {
            text = recoveryToShow.orEmpty()
            textSize = 13f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextColor(VaultTheme.textPrimary)
            setTextIsSelectable(true)
            setLineSpacing(dp(4).toFloat(), 1.1f)
            setPadding(0, dp(10), 0, 0)
        })
        root.addView(keyPanel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        secondaryBtn("Copy recovery key", R.drawable.ic_copy) {
            recoveryToShow?.let { copySensitive(it) }
        }

        val saved = CheckBox(this).apply {
            text = "I have stored this key somewhere safe"
            setTextColor(VaultTheme.textSecondary)
            buttonTintList = ColorStateList.valueOf(VaultTheme.accent)
            textSize = 12.5f
            setPadding(dp(4), dp(2), 0, dp(10))
        }
        root.addView(saved)

        primaryBtn("Continue to vault", R.drawable.ic_check) {
            if (!saved.isChecked) return@primaryBtn message("Please confirm you have saved your recovery key.")
            recoveryToShow = null
            recoveryContinuation?.invoke()
            recoveryContinuation = null
            if (session != null) mainHandler.postDelayed(idleLock, autoLockMillis)
            render()
            if (session != null) root.post { resumePendingImportIfAny() }
            if (session != null) root.post { maybeOfferFirstFingerprintPrompt() }
        }

        root.addView(VaultUi.text(
            this,
            "Aura cannot recover this key for you. Losing both means losing the vault.",
            10f, VaultTheme.textFaint, 430, gravity = Gravity.CENTER, lineSpacing = 1.3f
        ).apply { setPadding(dp(12), dp(10), dp(12), 0) }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun renderRecoveryUnlock() {
        authHeader(
            "Recovery",
            "Reset your password",
            "Enter your recovery key and choose a new master password."
        )

        val form = formPanel()
        val recovery = fieldRow(form, "Recovery key", "64-character recovery key")
        val password = fieldRow(form, "New master password", "At least 12 characters", secret = true)
        val confirm = fieldRow(form, "Confirm new password", "Repeat your new password", secret = true)
        root.addView(form, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        primaryBtn("Reset password & unlock", R.drawable.ic_shield_check) {
            if (password.text.length < 12 || !sameText(password.text, confirm.text)) {
                return@primaryBtn message("Passwords must match and have at least 12 characters.")
            }
            val chars = charsOf(password.text)
            try {
                runCatching {
                    VaultStore.resetPassword(this, recovery.text.toString(), chars)
                }.onSuccess { (newKey, newSession) ->
                    password.text.clear()
                    confirm.text.clear()
                    session = newSession
                    recoveryToShow = newKey
                    recoveryUnlockMode = false
                    render()
                }.onFailure {
                    message("Recovery failed: Invalid recovery key.")
                }
            } finally {
                chars.fill('\u0000')
            }
        }
        textBtn("Cancel") {
            recoveryUnlockMode = false
            render()
        }
    }

    private fun charsOf(text: CharSequence): CharArray = CharArray(text.length) { text[it] }

    private fun sameText(a: CharSequence, b: CharSequence): Boolean {
        if (a.length != b.length) return false
        for (i in a.indices) if (a[i] != b[i]) return false
        return true
    }

    // ==========================================
    // VAULT SCREENS
    // ==========================================
    private fun showVault() {
        val entries = session?.entries ?: return
        when (activeSection) {
            "Overview" -> showOverview(entries)
            "Security" -> showSecurityCenter(entries)
            "Settings" -> showSettingsScreen()
            else -> showCollection(specFor(activeSection), entries)
        }
        root.addView(footerNote(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun specFor(section: String): SectionSpec = when (section) {
        "Cards" -> SectionSpec(
            "Cards", "Payment cards", "Numbers, expiry dates and CVVs, encrypted on this device.",
            "Card", R.drawable.ic_card, VaultTheme.accentBlue, "Search cards and numbers"
        )
        "Bank logins" -> SectionSpec(
            "Bank logins", "Bank accounts", "Account numbers and net-banking details, stored locally.",
            "Bank", R.drawable.ic_bank, VaultTheme.accent, "Search banks and accounts"
        )
        "Passwords" -> SectionSpec(
            "Passwords", "Password vault", "Logins for sites and apps, protected by your master password.",
            "Password", R.drawable.ic_key, VaultTheme.accentRose, "Search logins and usernames"
        )
        "Custom" -> SectionSpec(
            "Custom", "Custom items", "Your own fields for anything else worth locking up.",
            "Custom", R.drawable.ic_folder, VaultTheme.accentMint, "Search custom records"
        )
        else -> SectionSpec(
            "Vault", "Your vault", "Everything you've saved, encrypted on this device.",
            null, R.drawable.ic_shield, VaultTheme.accent, "Search your vault"
        )
    }

    private fun addPageHeading(eyebrow: String, title: String, description: String) {
        root.addView(VaultUi.text(this, eyebrow, 9.5f, VaultTheme.textMuted, 500, letterSpacing = 0.01f))
        root.addView(VaultUi.text(this, title, 26f, VaultTheme.textPrimary, 600, heading = true, lineSpacing = 1.02f).apply {
            setPadding(0, dp(5), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(VaultUi.text(this, description, 12.5f, VaultTheme.textSecondary, 430, lineSpacing = 1.3f).apply {
            setPadding(0, dp(7), 0, dp(18))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun sectionLabel(text: String) {
        root.addView(VaultUi.text(this, text, 9.5f, VaultTheme.textFaint, 500, letterSpacing = 0.01f).apply {
            setPadding(dp(2), dp(18), 0, dp(9))
        })
    }

    private fun footerNote(): TextView = VaultUi.text(
        this, "Aura vault   ·   local only   ·   v0.1",
        8.5f, VaultTheme.textFaint, 500, letterSpacing = 0.02f, gravity = Gravity.CENTER
    ).apply { setPadding(0, dp(14), 0, dp(8)) }

    private fun go(section: String) {
        activeSection = section
        searchQuery = ""
        render()
    }

    private fun openSettings() {
        activeSection = "Settings"
        searchQuery = ""
        render()
    }

    private fun showOverview(entries: MutableList<VaultStore.Entry>) {
        val date = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()).format(LocalDate.now())
        addPageHeading(date, "Your vault", "Cards, logins and passwords, stored only on this device.")

        // Welcome hero
        val hero = FrameLayout(this).apply {
            background = VaultTheme.glassShape(this@MainActivity, 22, strong = true)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(-1, dp(226))
        }
        val heroCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(19), dp(100), dp(38))
        }
        val kicker = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        kicker.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(VaultTheme.accentMint)
            }
        }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(7) })
        kicker.addView(VaultUi.text(this, "Private space", 8.5f, VaultTheme.textMuted, 500, letterSpacing = 0.01f))
        heroCopy.addView(kicker)
        heroCopy.addView(VaultUi.text(this, "Everything important,", 20f, VaultTheme.textPrimary, 600, heading = true, lineSpacing = 1.06f).apply {
            setPadding(0, dp(10), 0, 0)
        })
        heroCopy.addView(VaultUi.text(this, "kept in one place.", 20f, VaultTheme.accent, 600, heading = true, lineSpacing = 1.06f).apply {
            setPadding(0, 0, 0, dp(8))
        })
        heroCopy.addView(VaultUi.text(this, "Cards and logins, encrypted on this device.", 10.5f, VaultTheme.textMuted, 430, lineSpacing = 1.25f))
        val securityLink = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            isClickable = true
            isFocusable = true
            contentDescription = "Open security center"
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                go("Security")
            }
        }
        securityLink.addView(VaultUi.text(this, "View security overview", 10.5f, VaultTheme.accent, 550), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(5) })
        securityLink.addView(VaultUi.icon(this, R.drawable.ic_arrow_right, VaultTheme.accent, 13))
        heroCopy.addView(securityLink)
        hero.addView(heroCopy, FrameLayout.LayoutParams(-1, -1))
        hero.addView(VaultUi.orb(this, 126), FrameLayout.LayoutParams(dp(126), dp(126), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = -dp(4)
        })
        val heroFoot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
        }
        val healthy = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        healthy.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(VaultTheme.accentMint)
            }
        }, LinearLayout.LayoutParams(dp(5), dp(5)).apply { marginEnd = dp(6) })
        healthy.addView(VaultUi.text(this, "Vault healthy", 9f, VaultTheme.textMuted, 500))
        heroFoot.addView(healthy, LinearLayout.LayoutParams(0, -2, 1f))
        heroFoot.addView(VaultUi.text(this, "Local only", 8.5f, VaultTheme.textFaint, 500, letterSpacing = 0.01f))
        hero.addView(heroFoot, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(20)
            rightMargin = dp(20)
            bottomMargin = dp(13)
        })
        root.addView(hero, LinearLayout.LayoutParams(-1, dp(226)).apply { bottomMargin = dp(12) })

        // Collection tiles
        val counts = mapOf(
            "Card" to entries.count { it.kind == "Card" },
            "Bank" to entries.count { it.kind == "Bank" },
            "Password" to entries.count { it.kind == "Password" },
            "Custom" to entries.count { it.kind == "Custom" }
        )
        fun tileRow(first: View, second: View) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(8))
            }
            row.addView(first, LinearLayout.LayoutParams(0, dp(138), 1f).apply { marginEnd = dp(8) })
            row.addView(second, LinearLayout.LayoutParams(0, dp(138), 1f))
            root.addView(row)
        }
        tileRow(
            VaultUi.statTile(this, R.drawable.ic_card, VaultTheme.accentBlue, "Payment cards", counts.getValue("Card").toString(), "Protected cards") { go("Cards") },
            VaultUi.statTile(this, R.drawable.ic_bank, VaultTheme.accent, "Bank accounts", counts.getValue("Bank").toString(), "Secure accounts") { go("Bank logins") }
        )
        tileRow(
            VaultUi.statTile(this, R.drawable.ic_key, VaultTheme.accentRose, "Passwords", counts.getValue("Password").toString(), "Saved logins") { go("Passwords") },
            VaultUi.statTile(this, R.drawable.ic_folder, VaultTheme.accentMint, "Custom items", counts.getValue("Custom").toString(), "Made for you") { go("Custom") }
        )

        // Security health panel
        val audit = SecurityAudit.audit(entries)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 18, strong = true)
            setPadding(dp(16), dp(15), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headCopy.addView(VaultUi.text(this, "Security health", 15.5f, VaultTheme.textPrimary, 600, heading = true))
        headCopy.addView(VaultUi.text(this, "Encrypted on this device, last checked just now.", 10.5f, VaultTheme.textMuted, 430).apply {
            setPadding(0, dp(3), 0, 0)
        })
        head.addView(headCopy, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(VaultUi.iconButton(this, R.drawable.ic_arrow_up_right, "Open security center", 32) { go("Security") })
        panel.addView(head, LinearLayout.LayoutParams(-1, -2))

        val scoreRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        scoreRow.addView(VaultUi.scoreRing(this, audit.score, 96).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply { marginEnd = dp(14) }
        })
        val scoreCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val color = scoreColor(audit.score)
        scoreCopy.addView(statusPill(audit.statusText, color))
        scoreCopy.addView(VaultUi.text(this, "Current standing", 14.5f, VaultTheme.textPrimary, 600, heading = true).apply {
            setPadding(0, dp(7), 0, 0)
        })
        scoreCopy.addView(VaultUi.text(this, if (audit.recommendations.isEmpty()) "No weak or reused passwords right now." else audit.recommendations.joinToString("  ·  "), 10f, VaultTheme.textMuted, 430, lineSpacing = 1.3f).apply {
            setPadding(0, dp(4), 0, 0)
        })
        scoreRow.addView(scoreCopy, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(scoreRow)

        val mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        mini.addView(miniStat("${audit.weakPasswordsCount}", "weak passwords"), LinearLayout.LayoutParams(0, -2, 1f))
        mini.addView(miniStat("${audit.reusedPasswordsCount}", "reused passwords"), LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(mini)
        val open = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, dp(12), 0, 0)
            setOnClickListener { go("Security") }
        }
        open.addView(VaultUi.text(this, "Open security center", 10.5f, VaultTheme.accent, 550), LinearLayout.LayoutParams(0, -2, 1f))
        open.addView(VaultUi.icon(this, R.drawable.ic_arrow_right, VaultTheme.accent, 14))
        panel.addView(open)
        root.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // Quick access
        val sectionHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(9))
        }
        val sectionCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sectionCopy.addView(VaultUi.text(this, "Quick access", 17f, VaultTheme.textPrimary, 600, heading = true))
        sectionCopy.addView(VaultUi.text(this, "Your pins, one tap away.", 10.5f, VaultTheme.textMuted, 430).apply {
            setPadding(0, dp(2), 0, 0)
        })
        sectionHead.addView(sectionCopy, LinearLayout.LayoutParams(0, -2, 1f))
        sectionHead.addView(VaultUi.text(this, "View all", 10.5f, VaultTheme.accent, 550).apply {
            setPadding(dp(10), dp(8), 0, dp(8))
            isClickable = true
            setOnClickListener { go("Cards") }
        })
        root.addView(sectionHead)

        if (entries.isEmpty()) {
            root.addView(emptyPrompt("Your vault is ready", "Add a card, account, or login to see it here.", R.drawable.ic_plus) {
                chooseItemType()
            }, LinearLayout.LayoutParams(-1, -2))
        } else {
            entries.take(3).forEach { entry ->
                root.addView(quickRow(entry), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
    }

    private fun miniStat(value: String, label: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(VaultUi.icon(this, R.drawable.ic_check, VaultTheme.accentMint, 13), LinearLayout.LayoutParams(dp(20), dp(13)).apply {
            marginEnd = dp(2)
        })
        row.addView(VaultUi.text(this, value, 12f, VaultTheme.textPrimary, 650))
        row.addView(VaultUi.text(this, " $label", 9.5f, VaultTheme.textMuted, 430))
        return row
    }

    private fun statusPill(text: String, color: Int): TextView = VaultUi.text(
        this, text, 8.5f, color, 600, letterSpacing = 0.01f
    ).apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(99).toFloat()
            setColor(Color.argb(22, Color.red(color), Color.green(color), Color.blue(color)))
            setStroke(dp(1), Color.argb(46, Color.red(color), Color.green(color), Color.blue(color)))
        }
        layoutParams = LinearLayout.LayoutParams(-2, -2)
    }

    private fun scoreColor(score: Int): Int = when {
        score >= 90 -> VaultTheme.positive
        score >= 75 -> VaultTheme.accentBlue
        score >= 50 -> VaultTheme.warning
        else -> VaultTheme.danger
    }

    private fun quickRow(entry: VaultStore.Entry): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 14)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            isClickable = true
            isFocusable = true
            contentDescription = entry.title
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                openEntry(entry)
            }
            VaultTheme.applySpringTouch(this)
        }
        row.addView(VaultUi.articleMark(this, entry.title, kindTint(entry.kind), 38))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, dp(6), 0)
        }
        copy.addView(VaultUi.text(this, entry.title.ifBlank { "Untitled" }, 13.5f, VaultTheme.textPrimary, 550).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        copy.addView(VaultUi.text(this, entrySubtitle(entry), 10.5f, VaultTheme.textMuted, 430).apply {
            setPadding(0, dp(2), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))

        val copyValue = quickCopyValue(entry)
        if (copyValue.isNotBlank()) {
            row.addView(VaultUi.iconButton(this, R.drawable.ic_copy, "Copy ${entry.title}", 34) {
                copySensitive(copyValue)
            }, LinearLayout.LayoutParams(dp(34), dp(34)))
        } else {
            row.addView(VaultUi.icon(this, R.drawable.ic_chevron_right, VaultTheme.textFaint, 17))
        }
        return row
    }

    private fun quickCopyValue(entry: VaultStore.Entry): String = when (entry.kind) {
        "Password" -> entry.secret
        "Card" -> entry.cardNumber
        "Bank" -> entry.customFields["Account number"].orEmpty().ifBlank { entry.cardNumber }
        else -> entry.customFields.entries.firstOrNull()?.value.orEmpty()
    }

    private fun kindTint(kind: String) = when (kind) {
        "Card" -> VaultTheme.accentBlue
        "Bank" -> VaultTheme.accent
        "Password" -> VaultTheme.accentRose
        else -> VaultTheme.accentMint
    }

    private fun kindIcon(kind: String) = when (kind) {
        "Card" -> R.drawable.ic_card
        "Bank" -> R.drawable.ic_bank
        "Password" -> R.drawable.ic_key
        else -> R.drawable.ic_folder
    }

    private fun entrySubtitle(entry: VaultStore.Entry): String = when (entry.kind) {
        "Card" -> {
            val digits = entry.cardNumber.filter { it.isDigit() }
            if (digits.length >= 4) "••••  ${digits.takeLast(4)}" else "Payment card"
        }
        "Bank" -> {
            val account = entry.customFields["Account number"].orEmpty().ifBlank { entry.cardNumber }
            val digits = account.filter { it.isDigit() }
            if (digits.length >= 4) "Account  ••••  ${digits.takeLast(4)}" else "Bank account"
        }
        "Password" -> entry.username.ifBlank { "Saved login" }
        else -> entry.customFields.entries.firstOrNull()?.let { (key, value) ->
            if (isSecretLabel(key)) "$key  ••••" else "$key: $value"
        } ?: "Custom item"
    }

    private fun isSecretLabel(label: String): Boolean {
        val lower = label.lowercase(Locale.ROOT)
        return lower.contains("password") || lower.contains("pin") || lower.contains("secret") || lower == "cvv"
    }

    private fun openEntry(entry: VaultStore.Entry) {
        activeSection = when (entry.kind) {
            "Card" -> "Cards"
            "Bank" -> "Bank logins"
            "Password" -> "Passwords"
            else -> "Custom"
        }
        searchQuery = ""
        expandedCards.add(entry.id)
        render()
    }

    private fun showCollection(spec: SectionSpec, entries: MutableList<VaultStore.Entry>) {
        addPageHeading(spec.eyebrow, spec.title, spec.description)

        val kindEntries = entries.filter { spec.kind == null || it.kind == spec.kind }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(11))
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        left.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(spec.tint)
            }
        }, LinearLayout.LayoutParams(dp(5), dp(5)).apply { marginEnd = dp(6) })
        left.addView(VaultUi.text(this, "${kindEntries.size} ${if (kindEntries.size == 1) "item" else "items"}  ·  encrypted locally", 10f, VaultTheme.textMuted, 500))
        toolbar.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(VaultUi.text(this, "Updated just now", 8.5f, VaultTheme.textFaint, 500, letterSpacing = 0.01f))
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addSearchBar(spec.searchHint) { refreshEntryList(list, entries) }
        root.addView(list, LinearLayout.LayoutParams(-1, -2))
        refreshEntryList(list, entries)

        val tip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 15)
            setPadding(dp(13), dp(12), dp(13), dp(12))
        }
        tip.addView(VaultUi.tintedBadge(this, R.drawable.ic_shield_check, VaultTheme.accentMint, 34))
        val tipCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        tipCopy.addView(VaultUi.text(this, "On security", 11.5f, VaultTheme.textPrimary, 550))
        tipCopy.addView(VaultUi.text(this, "Everything stays encrypted on this device.", 9.5f, VaultTheme.textMuted, 430).apply {
            setPadding(0, dp(2), 0, 0)
        })
        tip.addView(tipCopy, LinearLayout.LayoutParams(0, -2, 1f))
        tip.addView(VaultUi.icon(this, R.drawable.ic_lock, VaultTheme.textFaint, 16))
        root.addView(tip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }

    private fun addSearchBar(hint: String, onChanged: () -> Unit) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 14)
            setPadding(dp(13), 0, dp(6), 0)
        }
        bar.addView(VaultUi.icon(this, R.drawable.ic_search, VaultTheme.textFaint, 17), LinearLayout.LayoutParams(dp(17), dp(17)))
        val edit = EditText(this).apply {
            this.hint = hint
            setSingleLine(true)
            setTextColor(VaultTheme.textPrimary)
            setHintTextColor(VaultTheme.textFaint)
            textSize = 13f
            typeface = VaultTheme.typeface(this@MainActivity, 450)
            background = null
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setPadding(dp(9), dp(12), dp(6), dp(12))
            setText(searchQuery)
            setSelection(text.length)
        }
        bar.addView(edit, LinearLayout.LayoutParams(0, dp(48), 1f))
        val clear = FrameLayout(this).apply {
            background = VaultTheme.iconButtonDrawable(this@MainActivity, 9)
            contentDescription = "Clear search"
            visibility = if (searchQuery.isBlank()) View.GONE else View.VISIBLE
            isClickable = true
            isFocusable = true
            addView(VaultUi.icon(this@MainActivity, R.drawable.ic_close, VaultTheme.textMuted, 14), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
            setOnClickListener {
                edit.setText("")
                edit.requestFocus()
            }
        }
        bar.addView(clear, LinearLayout.LayoutParams(dp(30), dp(30)))
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                clear.visibility = if (searchQuery.isBlank()) View.GONE else View.VISIBLE
                onChanged()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(12) })
    }

    private fun matchingEntries(entries: List<VaultStore.Entry>): List<VaultStore.Entry> {
        val spec = specFor(activeSection)
        val query = searchQuery.trim()
        return entries.filter { entry ->
            (spec.kind == null || entry.kind == spec.kind) && (
                query.isBlank() ||
                    entry.title.contains(query, true) ||
                    entry.username.contains(query, true) ||
                    entry.cardNumber.contains(query, true) ||
                    entry.notes.contains(query, true) ||
                    entry.customFields.any { (key, value) -> key.contains(query, true) || value.contains(query, true) }
            )
        }
    }

    private fun refreshEntryList(host: LinearLayout, entries: MutableList<VaultStore.Entry>) {
        host.removeAllViews()
        val matches = matchingEntries(entries)
        val spec = specFor(activeSection)
        if (matches.isEmpty()) {
            host.addView(emptyPrompt(
                if (searchQuery.isNotBlank()) "No matching items" else "Nothing here yet",
                if (searchQuery.isNotBlank()) "Try another search or add something new."
                else "Your ${spec.title.lowercase(Locale.ROOT)} will appear here, encrypted and private.",
                spec.icon
            ) {
                if (searchQuery.isBlank() && spec.kind != null) addEntry(spec.kind)
            }, LinearLayout.LayoutParams(-1, -2))
            return
        }
        matches.forEach { renderEntryCard(it, entries, host) }
    }

    private fun emptyPrompt(title: String, body: String, iconRes: Int, onAdd: () -> Unit): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = VaultTheme.glassShape(this@MainActivity, 18)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        panel.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(22, 155, 134, 255))
                setStroke(dp(1), Color.argb(44, 155, 134, 255))
            }
            addView(VaultUi.icon(this@MainActivity, iconRes, VaultTheme.accent, 23), FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        panel.addView(VaultUi.text(this, title, 14.5f, VaultTheme.textPrimary, 600, heading = true, gravity = Gravity.CENTER).apply {
            setPadding(0, dp(13), 0, 0)
        })
        panel.addView(VaultUi.text(this, body, 10.5f, VaultTheme.textMuted, 430, gravity = Gravity.CENTER, lineSpacing = 1.3f).apply {
            setPadding(dp(8), dp(5), dp(8), 0)
        }, LinearLayout.LayoutParams(-1, -2))
        val cta = VaultUi.primaryButton(this, "Add new", R.drawable.ic_plus, 44) { onAdd() }
        panel.addView(cta, LinearLayout.LayoutParams(dp(156), dp(44)).apply { topMargin = dp(14) })
        return panel
    }

    private fun renderEntryCard(entry: VaultStore.Entry, entries: MutableList<VaultStore.Entry>, host: LinearLayout) {
        val expanded = expandedCards.contains(entry.id)
        val toggle = { toggleExpanded(entry.id) }

        if (entry.kind == "Card") {
            val widget = CardVisuals.createCardWidget(this, entry, expanded) { toggle() }
            host.addView(widget, LinearLayout.LayoutParams(-1, dp(178)).apply {
                topMargin = dp(6)
                bottomMargin = if (expanded) dp(0) else dp(10)
            })
            if (expanded) {
                host.addView(detailsPanel(entry, entries), LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(10)
                })
            }
            return
        }

        if (entry.kind == "Bank") {
            val widget = CardVisuals.createBankWidget(this, entry, expanded) { toggle() }
            host.addView(widget, LinearLayout.LayoutParams(-1, dp(152)).apply {
                topMargin = dp(6)
                bottomMargin = if (expanded) dp(0) else dp(10)
            })
            if (expanded) {
                host.addView(detailsPanel(entry, entries), LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(10)
                })
            }
            return
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 16)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            isClickable = true
            isFocusable = true
            contentDescription = "${entry.title}. Tap for details."
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                toggle()
            }
            VaultTheme.applySpringTouch(this)
        }
        card.addView(VaultUi.articleMark(this, entry.title, kindTint(entry.kind), 40))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, dp(6), 0)
        }
        info.addView(VaultUi.text(this, entry.title.ifBlank { "Untitled" }, 14f, VaultTheme.textPrimary, 600).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val subRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, 0)
        }
        subRow.addView(VaultUi.text(this, entrySubtitle(entry), 10.5f, VaultTheme.textMuted, 430).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (entry.kind == "Password" && entry.secret.isNotBlank()) {
            val (label, color) = SecurityAudit.getStrengthLabel(entry.secret)
            subRow.addView(VaultUi.text(this, label, 8.5f, color, 600, letterSpacing = 0.01f).apply {
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = GradientDrawable().apply {
                    cornerRadius = dp(99).toFloat()
                    setColor(Color.argb(20, Color.red(color), Color.green(color), Color.blue(color)))
                    setStroke(dp(1), Color.argb(42, Color.red(color), Color.green(color), Color.blue(color)))
                }
            })
        }
        info.addView(subRow, LinearLayout.LayoutParams(-1, -2))
        card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(VaultUi.icon(
            this,
            if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right,
            VaultTheme.textFaint, 17
        ), LinearLayout.LayoutParams(dp(17), dp(17)).apply { marginStart = dp(4) })
        host.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(6)
            bottomMargin = if (expanded) dp(0) else dp(8)
        })
        if (expanded) {
            host.addView(detailsPanel(entry, entries), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(8)
                bottomMargin = dp(10)
            })
        }
    }

    private fun toggleExpanded(id: String) {
        if (expandedCards.contains(id)) expandedCards.remove(id) else expandedCards.add(id)
        render(preserveScroll = true)
    }

    private fun detailsPanel(entry: VaultStore.Entry, entries: MutableList<VaultStore.Entry>): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 16, strong = true)
            setPadding(dp(14), dp(13), dp(14), dp(13))
        }
        panel.addView(VaultUi.text(this, "Stored details", 8.5f, VaultTheme.textFaint, 500, letterSpacing = 0.01f).apply {
            setPadding(dp(2), 0, 0, dp(4))
        })
        val fields = copyableFields(entry)
        fields.forEachIndexed { index, (_, title, value) ->
            addCopyableField(panel, entry, title, value)
            if (index < fields.lastIndex) panel.addView(VaultUi.divider(this), LinearLayout.LayoutParams(-1, dp(1)).apply {
                topMargin = dp(2)
                bottomMargin = dp(2)
            })
        }
        addEntryActionRows(panel, entry, entries)
        return panel
    }

    private fun addCopyableField(container: LinearLayout, entry: VaultStore.Entry, title: String, value: String) {
        val secret = isSecretLabel(title)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(VaultUi.text(this, title, 9f, VaultTheme.textFaint, 500, letterSpacing = 0.01f))
        val valueText = VaultUi.text(this, if (secret) "••••••••" else value, 12.5f, VaultTheme.textPrimary, 450, lineSpacing = 1.3f).apply {
            if (secret) typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, dp(3), 0, 0)
        }
        textCol.addView(valueText)
        row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))

        if (secret) {
            var revealed = false
            val eye = VaultUi.iconButton(this, R.drawable.ic_eye, "Show $title", 32) {
                revealed = !revealed
                valueText.text = if (revealed) value else "••••••••"
                val image = it.getChildAt(0) as ImageView
                image.setImageResource(if (revealed) R.drawable.ic_eye_off else R.drawable.ic_eye)
                it.contentDescription = if (revealed) "Hide $title" else "Show $title"
            }
            row.addView(eye, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(6) })
        }

        val copy = VaultUi.iconButton(this, R.drawable.ic_copy, "Copy $title", 32) {
            copyFieldValue(title, value)
        }
        row.addView(copy, LinearLayout.LayoutParams(dp(32), dp(32)))
        container.addView(row)
    }

    private fun copyFieldValue(title: String, value: String) {
        if (title.equals("CVV", true)) {
            confirm("Copy CVV?", "This security code is highly sensitive. Copy it only when you are ready to use it.", "Copy") {
                copySensitive(value)
            }
        } else {
            copySensitive(value)
        }
    }

    private fun addEntryActionRows(card: LinearLayout, entry: VaultStore.Entry, entries: MutableList<VaultStore.Entry>) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        fun action(label: String, iconRes: Int, tint: Int, onClick: () -> Unit) {
            val button = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = VaultTheme.iconButtonDrawable(this@MainActivity, 10, tinted = tint == VaultTheme.danger)
                isClickable = true
                isFocusable = true
                contentDescription = label
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                }
                VaultTheme.applySpringTouch(this)
                addView(VaultUi.icon(this@MainActivity, iconRes, tint, 14), LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(5) })
                addView(VaultUi.text(this@MainActivity, label, 10.5f, tint, 550))
            }
            row.addView(button, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) })
        }
        action("Share", R.drawable.ic_share, VaultTheme.textSecondary) { shareEntry(entry) }
        action("Edit", R.drawable.ic_edit, VaultTheme.textSecondary) { editEntry(entry) }
        action("Delete", R.drawable.ic_trash, VaultTheme.danger) { deleteEntry(entry, entries) }
        card.addView(row)
    }

    private fun copyableFields(entry: VaultStore.Entry): List<Triple<String, String, String>> = buildList {
        add(Triple("name", "Name", entry.title))
        if (entry.kind == "Card") {
            if (entry.cardholderName.isNotBlank()) add(Triple("cardholder", "Cardholder", entry.cardholderName))
            if (entry.cardNumber.isNotBlank()) add(Triple("cardNumber", "Card number", entry.cardNumber))
            if (entry.expiry.isNotBlank()) add(Triple("expiry", "Expiry", entry.expiry))
            if (entry.cvv.isNotBlank()) add(Triple("cvv", "CVV", entry.cvv))
        }
        if (entry.username.isNotBlank()) add(Triple("username", "Username", entry.username))
        if (entry.secret.isNotBlank()) add(Triple("password", "Password", entry.secret))
        entry.customFields.forEach { (key, value) -> if (value.isNotBlank()) add(Triple("custom:$key", key, value)) }
        if (entry.notes.isNotBlank()) add(Triple("notes", "Notes", entry.notes))
    }

    // ==========================================
    // SETTINGS, SECURITY & EDITOR
    // ==========================================
    private fun showSettingsScreen() {
        addPageHeading("Settings", "Settings", "Tailor how the vault protects and behaves.")
        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 18, strong = true)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        summary.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(13).toFloat()
                setColor(VaultTheme.surfaceElevated)
                setStroke(dp(1), VaultTheme.border)
            }
            addView(VaultUi.icon(this@MainActivity, R.drawable.ic_shield_check, VaultTheme.accent, 21), FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(45), dp(45)))
        val summaryCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, dp(8), 0)
        }
        summaryCopy.addView(VaultUi.text(this, "Aura local vault", 13.5f, VaultTheme.textPrimary, 600))
        summaryCopy.addView(VaultUi.text(this, "AES-256-GCM  ·  no cloud  ·  no network", 9.5f, VaultTheme.textMuted, 430).apply {
            setPadding(0, dp(3), 0, 0)
        })
        summary.addView(summaryCopy, LinearLayout.LayoutParams(0, -2, 1f))
        summary.addView(statusPill("Protected", VaultTheme.accentMint))
        root.addView(summary, LinearLayout.LayoutParams(-1, -2))

        sectionLabel("Security")
        val biometricOn = VaultStore.biometricPayload(this) != null
        settingsRow(
            R.drawable.ic_shield_check, VaultTheme.accentMint,
            "Security center", "Review your vault health and recommendations",
            "Open"
        ) { go("Security") }
        settingsRow(
            R.drawable.ic_fingerprint, VaultTheme.accent,
            "Fingerprint unlock", "Unlock with a strong biometric instead of typing",
            if (biometricOn) "On" else "Off"
        ) {
            if (biometricOn) {
                confirm("Disable fingerprint?", "You will unlock with your master password.", "Disable") {
                    VaultStore.clearBiometric(this)
                    render()
                }
            } else {
                enableBiometric()
            }
        }
        settingsRow(
            R.drawable.ic_key, VaultTheme.accentAmber,
            "Recovery key", "Replace the emergency key used to reset your password",
            "Rotate"
        ) {
            confirm("Replace recovery key?", "Your current recovery key will stop working immediately.", "Create new key") {
                val current = session
                if (current == null) {
                    message("Vault is locked. Unlock to rotate the recovery key.")
                    return@confirm
                }
                runCatching { VaultStore.rotateRecoveryKey(this, current.dek) }
                    .onSuccess {
                        recoveryToShow = it
                        render()
                    }
                    .onFailure { message("Failed to create new key.") }
            }
        }
        settingsRow(
            R.drawable.ic_lock, VaultTheme.accentRose,
            "Lock vault", "Close the session and clear sensitive clipboard data",
            "Lock"
        ) { lockNow() }

        sectionLabel("Privacy")
        infoRow(R.drawable.ic_image, VaultTheme.accentBlue, "Screenshots", "Screen capture and recent-app previews", "Blocked")
        infoRow(R.drawable.ic_globe, VaultTheme.accentMint, "Network access", "The app has no internet permission", "None")
        infoRow(R.drawable.ic_copy, VaultTheme.accentAmber, "Clipboard", "Sensitive values are cleared automatically", "30 sec")
        infoRow(R.drawable.ic_lock, VaultTheme.textSecondary, "Auto-lock", "The vault locks after inactivity", "1 min")

        sectionLabel("About")
        infoRow(R.drawable.ic_sparkles, VaultTheme.accent, "Aura Vault", "Local-only encrypted vault.", "v0.1.0")
        infoRow(R.drawable.ic_info, VaultTheme.textMuted, "Prototype notice", "Review the security model before real use", "")

        root.addView(VaultUi.text(
            this,
            "This is an offline prototype. It has not been independently security-reviewed. Keep using dummy details until device-level checks are complete.",
            9.5f, VaultTheme.textFaint, 430, gravity = Gravity.CENTER, lineSpacing = 1.35f
        ).apply { setPadding(dp(6), dp(14), dp(6), 0) }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun settingsRow(
        iconRes: Int,
        tint: Int,
        title: String,
        detail: String,
        actionLabel: String,
        onClick: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 15)
            setPadding(dp(11), dp(10), dp(11), dp(10))
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $actionLabel"
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            VaultTheme.applySpringTouch(this)
        }
        row.addView(VaultUi.tintedBadge(this, iconRes, tint, 34))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
        }
        copy.addView(VaultUi.text(this, title, 12.5f, VaultTheme.textPrimary, 550))
        copy.addView(VaultUi.text(this, detail, 9.5f, VaultTheme.textFaint, 430, lineSpacing = 1.25f).apply {
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(VaultUi.text(this, actionLabel, 9.5f, VaultTheme.accent, 650).apply {
            setPadding(dp(7), dp(4), dp(7), dp(4))
            background = VaultTheme.pillDrawable(this@MainActivity, true)
        })
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun infoRow(iconRes: Int, tint: Int, title: String, detail: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 15)
            setPadding(dp(11), dp(10), dp(11), dp(10))
        }
        row.addView(VaultUi.tintedBadge(this, iconRes, tint, 34))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
        }
        copy.addView(VaultUi.text(this, title, 12.5f, VaultTheme.textPrimary, 550))
        copy.addView(VaultUi.text(this, detail, 9.5f, VaultTheme.textFaint, 430, lineSpacing = 1.25f).apply {
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        if (value.isNotBlank()) {
            row.addView(VaultUi.text(this, value, 9.5f, VaultTheme.textMuted, 600))
        }
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun showSecurityCenter(entries: MutableList<VaultStore.Entry>) {
        addPageHeading("Security", "Security center", "How well your vault is holding up right now.")
        val audit = SecurityAudit.audit(entries)
        val color = scoreColor(audit.score)

        val hero = FrameLayout(this).apply {
            background = VaultTheme.glassShape(this@MainActivity, 20, strong = true)
            clipToOutline = true
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(130), dp(18))
        }
        val kicker = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        kicker.addView(VaultUi.icon(this, R.drawable.ic_shield_check, VaultTheme.accentMint, 14), LinearLayout.LayoutParams(dp(14), dp(14)).apply {
            marginEnd = dp(6)
        })
        kicker.addView(VaultUi.text(this, "Security picture", 8.5f, VaultTheme.textMuted, 500, letterSpacing = 0.01f))
        copy.addView(kicker)
        copy.addView(VaultUi.text(this, "A strong foundation", 20f, VaultTheme.textPrimary, 600, heading = true).apply {
            setPadding(0, dp(10), 0, 0)
        })
        copy.addView(VaultUi.text(this, "with room to tighten.", 20f, VaultTheme.accent, 600, heading = true, lineSpacing = 1.06f).apply {
            setPadding(0, 0, 0, dp(8))
        })
        copy.addView(VaultUi.text(this, "Your vault is encrypted, on-device, and looking healthy.", 10.5f, VaultTheme.textMuted, 430, lineSpacing = 1.3f))
        copy.addView(statusPill(audit.statusText, color), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        hero.addView(copy)
        hero.addView(VaultUi.scoreRing(this, audit.score, 118, "score"), FrameLayout.LayoutParams(dp(118), dp(118), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = dp(14)
        })
        root.addView(hero, LinearLayout.LayoutParams(-1, dp(238)).apply { bottomMargin = dp(10) })

        fun metricRow(first: View, second: View) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(8))
            }
            row.addView(first, LinearLayout.LayoutParams(0, dp(138), 1f).apply { marginEnd = dp(8) })
            row.addView(second, LinearLayout.LayoutParams(0, dp(138), 1f))
            root.addView(row)
        }
        val biometricOn = VaultStore.biometricPayload(this) != null
        metricRow(
            VaultUi.statTile(this, R.drawable.ic_key, if (audit.weakPasswordsCount == 0) VaultTheme.accentMint else VaultTheme.warning, "Weak passwords", audit.weakPasswordsCount.toString(), if (audit.weakPasswordsCount == 0) "Everything is strong" else "Review these soon") {},
            VaultUi.statTile(this, R.drawable.ic_copy, if (audit.reusedPasswordsCount == 0) VaultTheme.accentBlue else VaultTheme.warning, "Reused passwords", audit.reusedPasswordsCount.toString(), if (audit.reusedPasswordsCount == 0) "Every password is unique" else "Use unique credentials") {}
        )
        metricRow(
            VaultUi.statTile(this, R.drawable.ic_card, if (audit.expiringCardsCount == 0) VaultTheme.accent else VaultTheme.warning, "Expiring cards", audit.expiringCardsCount.toString(), if (audit.expiringCardsCount == 0) "No cards need attention" else "Check your cards") {},
            VaultUi.statTile(this, R.drawable.ic_fingerprint, VaultTheme.accentRose, "Biometric unlock", if (biometricOn) "On" else "Off", if (biometricOn) "Last used today" else "Master password only") { openSettings() }
        )

        val recommendations = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 18, strong = true)
            setPadding(dp(15), dp(14), dp(15), dp(14))
        }
        recommendations.addView(VaultUi.text(this, "Recommendations", 15.5f, VaultTheme.textPrimary, 600, heading = true))
        recommendations.addView(VaultUi.text(this, "Fix these when you have a moment.", 10f, VaultTheme.textMuted, 430).apply {
            setPadding(0, dp(3), 0, dp(10))
        })
        if (audit.recommendations.isEmpty()) {
            val clear = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(3))
            }
            clear.addView(VaultUi.tintedBadge(this, R.drawable.ic_check, VaultTheme.accentMint, 32))
            clear.addView(VaultUi.text(this, "Nothing needs attention right now.", 10.5f, VaultTheme.textSecondary, 450).apply {
                setPadding(dp(9), 0, 0, 0)
            })
            recommendations.addView(clear)
        } else {
            audit.recommendations.forEach { rec ->
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                }
                line.addView(VaultUi.tintedBadge(this, R.drawable.ic_info, VaultTheme.warning, 30))
                line.addView(VaultUi.text(this, rec.replaceFirstChar { it.uppercase() }, 10.5f, VaultTheme.textSecondary, 450).apply {
                    setPadding(dp(9), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                line.addView(VaultUi.icon(this, R.drawable.ic_chevron_right, VaultTheme.textFaint, 15))
                recommendations.addView(line)
            }
        }
        root.addView(recommendations, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val protection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 18)
            setPadding(dp(15), dp(14), dp(15), dp(8))
        }
        protection.addView(VaultUi.text(this, "Local protection", 15f, VaultTheme.textPrimary, 600, heading = true).apply {
            setPadding(0, 0, 0, dp(7))
        })
        listOf(
            "AES-256-GCM vault encryption" to R.drawable.ic_lock,
            "Hardware-backed biometric key" to R.drawable.ic_fingerprint,
            "Screen capture blocked" to R.drawable.ic_image,
            "Zero network access" to R.drawable.ic_globe,
            "Sensitive clipboard clears in 30 seconds" to R.drawable.ic_copy
        ).forEach { (label, icon) ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            line.addView(VaultUi.icon(this, R.drawable.ic_check, VaultTheme.accentMint, 13), LinearLayout.LayoutParams(dp(20), dp(13)))
            line.addView(VaultUi.icon(this, icon, VaultTheme.textFaint, 14), LinearLayout.LayoutParams(dp(22), dp(14)))
            line.addView(VaultUi.text(this, label, 10.5f, VaultTheme.textSecondary, 450))
            protection.addView(line)
        }
        root.addView(protection, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun chooseItemType() {
        val actions = listOf(
            VaultUi.SheetAction(R.drawable.ic_card, VaultTheme.accentBlue, "Payment card", "Enter a card manually") { addEntry("Card") },
            VaultUi.SheetAction(R.drawable.ic_camera, VaultTheme.accent, "Scan a card", "Read a card with the camera") { captureCardImage() },
            VaultUi.SheetAction(R.drawable.ic_image, VaultTheme.accentMint, "Import card image", "Recognise a card from your gallery") { pickCardImage() },
            VaultUi.SheetAction(R.drawable.ic_bank, VaultTheme.accentAmber, "Bank account", "Save account and login details") { addEntry("Bank") },
            VaultUi.SheetAction(R.drawable.ic_key, VaultTheme.accentRose, "Password", "Save a secure login") { addEntry("Password") },
            VaultUi.SheetAction(R.drawable.ic_folder, VaultTheme.textSecondary, "Custom item", "Build a record with your own fields") { addEntry("Custom") }
        )
        VaultUi.showActionSheet(this, "Add to vault", "What would you like to keep?", actions)
    }

    private fun addEntry(kind: String, prefill: CardPrefill? = null, editing: VaultStore.Entry? = null) {
        val formContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(4))
        }
        fun field(label: String, hint: String = "", secret: Boolean = false, initialValue: String = ""): EditText =
            fieldRow(formContent, label, hint, secret, initialValue)

        val titleLabel = when (kind) {
            "Bank" -> "Bank name"
            "Card" -> "Card nickname"
            "Password" -> "Website or app"
            else -> "Item name"
        }
        val title = field(titleLabel, when (kind) {
            "Bank" -> "e.g. Northstar Bank"
            "Card" -> "e.g. Travel card"
            "Password" -> "e.g. Studio portal"
            else -> "e.g. Travel documents"
        }, initialValue = editing?.title.orEmpty())

        val cardholder = if (kind == "Card") field("Cardholder name", "Name on the card", initialValue = editing?.cardholderName.orEmpty()) else null
        val user = if (kind == "Password") field("Username or email", "you@example.com", initialValue = editing?.username.orEmpty()) else null
        val secret = if (kind == "Password") field("Password", "Enter a strong password", true, editing?.secret.orEmpty()) else null

        if (kind == "Password") {
            val strength = VaultUi.text(this, "Strength  ·  waiting", 9.5f, VaultTheme.textFaint, 500)
            strength.setPadding(dp(2), 0, 0, dp(8))
            formContent.addView(strength)
            secret?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (s.isNullOrEmpty()) {
                        strength.text = "Strength  ·  waiting"
                        strength.setTextColor(VaultTheme.textFaint)
                    } else {
                        val (label, color) = SecurityAudit.getStrengthLabel(s.toString())
                        strength.text = "Strength  ·  $label"
                        strength.setTextColor(color)
                    }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            val generate = VaultUi.secondaryButton(this, "Generate strong password", R.drawable.ic_sparkles, 42) {
                val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#\$%&*_-+=?"
                val random = java.security.SecureRandom()
                secret?.setText((1..20).map { alphabet[random.nextInt(alphabet.length)] }.joinToString(""))
                secret?.setSelection(secret.text.length)
            }
            formContent.addView(generate, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(12) })
        }

        val number = if (kind == "Card") field("Card number", "0000 0000 0000 0000", initialValue = prefill?.number ?: editing?.cardNumber.orEmpty()) else null
        val expiry = if (kind == "Card") field("Expiry", "MM/YY", initialValue = prefill?.expiry ?: editing?.expiry.orEmpty()) else null
        val cvv = if (kind == "Card") field("CVV", "3 or 4 digits", true, prefill?.cvv ?: editing?.cvv.orEmpty()) else null

        val custom = linkedMapOf<String, EditText>()
        if (kind == "Bank") {
            val hints = mapOf(
                "Account number" to "Account number",
                "Routing / IFSC" to "Routing number or IFSC code",
                "Customer ID" to "Customer or CIF ID",
                "Login ID" to "Internet banking login ID",
                "Mobile PIN" to "UPI / app quick PIN",
                "MPIN" to "Bank MPIN (4-6 digits)",
                "Internet banking password" to "Net-banking password"
            )
            val keys = (hints.keys.toList() + editing?.customFields?.keys.orEmpty()).distinct()
            keys.forEach { key ->
                custom[key] = field(key, hints[key].orEmpty(), isSecretLabel(key), editing?.customFields?.get(key).orEmpty())
            }
        }

        if (kind == "Custom") {
            val keysInput = field("Field names", "Comma-separated, e.g. Policy, Renewal", initialValue = editing?.customFields?.keys?.joinToString(", ").orEmpty())
            fun addCustomField(key: String) {
                if (custom.containsKey(key)) return
                custom[key] = field(key, "", isSecretLabel(key), editing?.customFields?.get(key).orEmpty())
            }
            val create = VaultUi.secondaryButton(this, "Add fields", R.drawable.ic_plus, 42) {
                val names = keysInput.text.toString().split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()
                if (names.isEmpty()) {
                    message("Enter at least one field name.")
                } else {
                    names.forEach(::addCustomField)
                }
            }
            formContent.addView(create, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(12) })
            editing?.customFields?.keys?.forEach(::addCustomField)
        }

        val notes = field("Notes", "Optional private note", initialValue = editing?.notes.orEmpty())

        val dialogTitle = editing?.let { "Edit ${it.title}" } ?: when (kind) {
            "Bank" -> "Add bank account"
            "Card" -> "Add payment card"
            "Password" -> "Add password"
            else -> "Add custom item"
        }
        val eyebrow = if (editing != null) "Edit entry" else "New entry"

        showPanelDialog(eyebrow, dialogTitle, formContent, if (editing != null) "Save changes" else "Save to vault", onSubmit = {
            val current = session
            if (current == null) {
                lockNow()
                message("Vault locked. Unlock to save this item.")
                return@showPanelDialog false
            }
            val missing = title.text.isBlank() ||
                (kind == "Password" && secret?.text.isNullOrBlank()) ||
                (kind == "Card" && number?.text.isNullOrBlank()) ||
                (kind == "Custom" && custom.isEmpty())
            if (missing) {
                message("Please fill in the required fields.")
                false
            } else {
                val entry = VaultStore.Entry(
                    id = editing?.id ?: UUID.randomUUID().toString(),
                    kind = kind,
                    title = title.text.toString(),
                    cardholderName = cardholder?.text?.toString().orEmpty(),
                    username = user?.text?.toString().orEmpty(),
                    secret = secret?.text?.toString().orEmpty(),
                    cardNumber = number?.text?.toString().orEmpty(),
                    expiry = expiry?.text?.toString().orEmpty(),
                    cvv = cvv?.text?.toString().orEmpty(),
                    notes = notes.text.toString(),
                    customFields = custom.mapValues { it.value.text.toString() }.filterValues { it.isNotBlank() }
                )
                if (editing == null) {
                    current.entries.add(entry)
                } else {
                    val index = current.entries.indexOfFirst { it.id == editing.id }
                    if (index >= 0) current.entries[index] = entry
                }
                persistAndRender(if (editing == null) "Item saved" else "Changes saved")
                true
            }
        })
    }

    private fun showPanelDialog(
        eyebrow: String,
        title: String,
        content: View,
        submitLabel: String,
        onSubmit: () -> Boolean
    ): Dialog {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(VaultTheme.surface)
                cornerRadii = floatArrayOf(
                    dp(26).toFloat(), dp(26).toFloat(),
                    dp(26).toFloat(), dp(26).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setStroke(dp(1), VaultTheme.borderStrong)
            }
            setPadding(dp(18), dp(8), dp(18), dp(16))
        }
        panel.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(99).toFloat()
                setColor(Color.argb(60, 255, 255, 255))
            }
        }, LinearLayout.LayoutParams(dp(38), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(3)
            bottomMargin = dp(15)
        })

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(8), 0)
        }
        headerCopy.addView(VaultUi.text(this, eyebrow, 9.5f, VaultTheme.textMuted, 500, letterSpacing = 0.01f))
        headerCopy.addView(VaultUi.text(this, title, 21f, VaultTheme.textPrimary, 600, heading = true).apply {
            setPadding(0, dp(4), 0, 0)
        })
        header.addView(headerCopy, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(VaultUi.iconButton(this, R.drawable.ic_close, "Close", 34) { dialog.dismiss() })
        panel.addView(header, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val cancel = VaultUi.secondaryButton(this, "Cancel", null, 46) { dialog.dismiss() }
        footer.addView(cancel, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) })
        val submit = VaultUi.primaryButton(this, submitLabel, null, 46) {
            if (onSubmit()) dialog.dismiss()
        }
        footer.addView(submit, LinearLayout.LayoutParams(0, dp(46), 1f))
        panel.addView(footer, LinearLayout.LayoutParams(-1, -2))

        dialog.setContentView(panel, ViewGroup.LayoutParams(-1, -1))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.7f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setWindowAnimations(android.R.style.Animation_Dialog)
            decorView.setPadding(dp(8), 0, dp(8), dp(8))
        }
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
            // An OCR import opened this form over the import screen. Now that
            // the form is closed, return to the vault and clean up the photo.
            if (pendingReturnToVault) {
                pendingReturnToVault = false
                importGeneration++
                importCleanupFile?.delete()
                importCleanupFile = null
                clearPendingImport()
                importMode = null
                SessionKeeper.clear(this)
                render()
            }
            // Re-arm the idle timer when a panel closes, so auto-lock stays armed
            // even if the pending idleLock fired (and no-op'd) while the dialog was open.
            if (session != null && recoveryToShow == null) {
                mainHandler.removeCallbacks(idleLock)
                mainHandler.postDelayed(idleLock, autoLockMillis)
            }
        }
        activeDialog = dialog
        dialog.show()
        val height = min((resources.displayMetrics.heightPixels * 0.88f).toInt(), dp(760))
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, height)
        return dialog
    }

    private fun shareEntry(entry: VaultStore.Entry) {
        val fields = linkedMapOf<String, String>()
        fields["Name"] = entry.title
        if (entry.kind == "Password") {
            if (entry.username.isNotBlank()) fields["Username"] = entry.username
            fields["Password"] = entry.secret
        } else {
            if (entry.kind == "Card") {
                if (entry.cardholderName.isNotBlank()) fields["Cardholder"] = entry.cardholderName
                fields["Card Number"] = entry.cardNumber
                if (entry.expiry.isNotBlank()) fields["Expiry"] = entry.expiry
                if (entry.cvv.isNotBlank()) fields["CVV"] = entry.cvv
            }
            entry.customFields.forEach { (key, value) -> if (value.isNotBlank()) fields[key] = value }
        }
        if (entry.notes.isNotBlank()) fields["Notes"] = entry.notes

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(VaultUi.text(
            this,
            "Choose exactly what leaves the vault. The share sheet will open for you to pick an app.",
            10.5f, VaultTheme.textMuted, 430, lineSpacing = 1.3f
        ).apply { setPadding(dp(2), 0, dp(2), dp(10)) })
        val selected = fields.mapValues { (key, _) ->
            CheckBox(this).apply {
                text = key
                setTextColor(VaultTheme.textPrimary)
                textSize = 12.5f
                buttonTintList = ColorStateList.valueOf(VaultTheme.accent)
                isChecked = key != "CVV"
                setPadding(dp(4), dp(2), 0, dp(2))
                content.addView(this)
            }
        }
        val warning = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 12)
            setPadding(dp(10), dp(9), dp(10), dp(9))
        }
        warning.addView(VaultUi.icon(this, R.drawable.ic_info, VaultTheme.warning, 15), LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(7) })
        warning.addView(VaultUi.text(this, "CVV stays hidden by default. Shared details leave the vault.", 9.5f, VaultTheme.textMuted, 430, lineSpacing = 1.25f))
        content.addView(warning, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        showPanelDialog("Share safely", "Share ${entry.title}", content, "Share details", onSubmit = {
            val chosen = selected.filterValues { it.isChecked }.keys
            if (chosen.isEmpty()) {
                message("Select at least one field.")
                false
            } else {
                val notice = if (chosen.contains("CVV")) {
                    "CVV is included. These details will leave your vault through Android's share sheet."
                } else {
                    "The selected details will leave your vault through Android's share sheet."
                }
                confirm("Confirm sharing", notice, "Share") {
                    val body = chosen.joinToString("\n") { "$it: ${fields[it]}" }
                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body),
                        "Share details"
                    ))
                }
                true
            }
        })
    }

    private fun confirm(title: String, message: String, positive: String, onConfirm: () -> Unit) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(positive) { _, _ -> onConfirm() }
            .create()
        dialog.show()
        styleDialog(dialog)
        // Keep the two actions visually separate. The default AlertDialog
        // button bar lets adjacent backgrounds touch, which makes the brass
        // Enable button look like it is bleeding into the trailing edge.
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.let { button ->
            button.minimumWidth = dp(104)
            (button.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                marginEnd = dp(8)
                button.layoutParams = this
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { button ->
            button.minimumWidth = dp(104)
            (button.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                marginStart = dp(8)
                button.layoutParams = this
            }
        }
    }

    private fun editEntry(entry: VaultStore.Entry) {
        addEntry(entry.kind, editing = entry)
    }

    private fun deleteEntry(entry: VaultStore.Entry, entries: MutableList<VaultStore.Entry>) {
        confirm("Delete ${entry.title}?", "This item will be permanently removed from your vault. This cannot be undone.", "Delete") {
            window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            entries.removeAll { it.id == entry.id }
            expandedCards.remove(entry.id)
            persistAndRender("Item deleted")
        }
    }

    // ==========================================
    // BIOMETRIC UNLOCK
    // ==========================================
    private fun maybeOfferFirstFingerprintPrompt() {
        if (isFinishing || session == null) return
        // Never stack this over a card import that is still being recovered.
        if (importPrefs.getString("file", null) != null) return
        val preferences = getSharedPreferences("security_preferences", Context.MODE_PRIVATE)
        if (preferences.getBoolean("first_fingerprint_prompt_seen", false)) return
        if (VaultStore.biometricPayload(this) != null) return
        preferences.edit().putBoolean("first_fingerprint_prompt_seen", true).apply()

        // A purpose-built sheet: the two actions are full-width plates with a
        // real gap between them. The system AlertDialog packed the buttons into
        // one row and its accent highlight bled into the button edges.
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(VaultTheme.surface)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), VaultTheme.borderStrong)
            }
            setPadding(dp(22), dp(24), dp(22), dp(20))
        }
        panel.addView(
            VaultUi.icon(this, R.drawable.ic_fingerprint, VaultTheme.accentMint, 34),
            LinearLayout.LayoutParams(dp(34), dp(34))
        )
        panel.addView(VaultUi.text(this, "Add fingerprint unlock?", 21f, VaultTheme.textPrimary, 600, heading = true).apply {
            setPadding(0, dp(14), 0, 0)
        })
        panel.addView(VaultUi.text(
            this,
            "Open Aura with the fingerprint enrolled on this device. Your master password always remains available.",
            12.5f, VaultTheme.textMuted, 430, lineSpacing = 1.35f
        ).apply { setPadding(0, dp(8), 0, dp(22)) })

        val enable = VaultUi.primaryButton(this, "Enable fingerprint", R.drawable.ic_fingerprint, 50) {
            dialog.dismiss()
            enableBiometric()
        }
        panel.addView(enable, LinearLayout.LayoutParams(-1, dp(50)))
        val notNow = VaultUi.secondaryButton(this, "Not now", null, 50) { dialog.dismiss() }
        panel.addView(notNow, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })

        dialog.setContentView(panel, ViewGroup.LayoutParams(-1, -2))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.7f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(android.R.style.Animation_Dialog)
            decorView.setPadding(dp(16), 0, dp(16), dp(20))
        }
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        activeDialog = dialog
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun enableBiometric() {
        val cipher = runCatching { VaultStore.newBiometricEncryptCipher() }.getOrElse {
            message("Fingerprint unlock is unavailable on this device.")
            return
        }
        val prompt = BiometricPrompt.Builder(this)
            .setTitle("Enable fingerprint unlock")
            .setSubtitle("Confirm your fingerprint to protect this vault")
            .setNegativeButton("Cancel", mainExecutor) { _, _ -> VaultStore.clearBiometric(this) }
            .build()
        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher),
            CancellationSignal(),
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val current = session
                    if (current == null) {
                        message("Vault is locked. Unlock and try again.")
                        lockNow()
                        return
                    }
                    runCatching {
                        val authorized = result.cryptoObject!!.cipher!!
                        VaultStore.saveBiometricPayload(
                            this@MainActivity,
                            authorized.iv,
                            authorized.doFinal(current.dek)
                        )
                    }.onSuccess {
                        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        success("Fingerprint unlock enabled")
                        render()
                    }.onFailure {
                        VaultStore.clearBiometric(this@MainActivity)
                        message("Could not enable fingerprint unlock.")
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    VaultStore.clearBiometric(this@MainActivity)
                    message("Fingerprint setup cancelled or unavailable.")
                    render()
                }
            }
        )
    }

    private fun unlockBiometric(payload: VaultStore.BiometricPayload) {
        val cipher = runCatching { VaultStore.biometricDecryptCipher(payload) }.getOrElse {
            VaultStore.clearBiometric(this)
            message("Fingerprint key is unavailable. Use your master password or recovery key.")
            render()
            return
        }
        val prompt = BiometricPrompt.Builder(this)
            .setTitle("Unlock Aura Vault")
            .setSubtitle("Use your enrolled fingerprint")
            .setNegativeButton("Cancel", mainExecutor) { _, _ -> }
            .build()
        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher),
            CancellationSignal(),
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    runCatching { VaultStore.decryptBiometric(result.cryptoObject!!.cipher!!, payload) }
                        .onSuccess { dek ->
                            window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            session = VaultStore.Session(dek, VaultStore.entriesWithDek(this@MainActivity, dek))
                            recoveryUnlockMode = false
                            mainHandler.removeCallbacks(idleLock)
                            mainHandler.postDelayed(idleLock, autoLockMillis)
                            render()
                            root.post { resumePendingImportIfAny() }
                        }
                        .onFailure { message("Fingerprint unlock failed. Use your master password or recovery key.") }
                }
            }
        )
    }

    // ==========================================
    // CARD IMAGE IMPORT
    // ==========================================
    private fun pickCardImage() {
        // Use the system photo picker: Google Photos, albums and the device
        // gallery all appear here. No media permission is needed, and the
        // vault no longer draws its own incomplete grid.
        importMode = "picker"
        mainHandler.removeCallbacks(idleLock)
        session?.let { SessionKeeper.save(this, it.dek) }
        importPrefs.edit().putString("mode", "picker").putLong("at", System.currentTimeMillis()).apply()
        render()
        val picker = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply { type = "image/*" }
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        runCatching { startActivityForResult(picker, REQUEST_CARD_IMAGE) }
            .onFailure {
                cancelImport()
                message("Could not open the phone's photo picker.")
            }
    }

    private fun captureCardImage() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            importMode = "camera"
            mainHandler.removeCallbacks(idleLock)
            session?.let { SessionKeeper.save(this, it.dek) }
            importPrefs.edit().putString("mode", "camera").putLong("at", System.currentTimeMillis()).apply()
            render()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CARD_CAMERA_PERM)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CARD_IMAGE) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            cancelImport()
            return
        }
        message("Reading card text on this device…")
        galleryExecutor.execute {
            val importFile = newImportFile()
            var copied = false
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    importFile.outputStream().use { output -> input.copyTo(output) }
                    copied = true
                }
            } catch (_: Exception) { }
            mainHandler.post {
                if (!copied) {
                    importFile.delete()
                    cancelImport()
                    message("Could not open that photo.")
                    return@post
                }
                savePendingImport(importFile)
                readCardImage(Uri.fromFile(importFile), importFile)
            }
        }
    }

    /** The camera remains in-app. Image upload uses the phone's real picker. */
    private fun showCardImport() {
        val mode = importMode ?: return
        cameraEngine?.close()
        cameraEngine = null

        val panel = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(9, 10, 12))
            }
        }

        if (mode == "camera") {
            showCardCamera(panel)
        } else {
            showPickerWaiting(panel, resuming = mode == "resume")
        }

        root.removeAllViews()
        root.addView(panel, LinearLayout.LayoutParams(-1, -2))
    }

    private fun showPickerWaiting(panel: FrameLayout, resuming: Boolean = false) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(24), dp(26), dp(24))
        }
        column.addView(VaultUi.icon(this, R.drawable.ic_image, VaultTheme.accentMint, 42))
        column.addView(VaultUi.text(
            this,
            if (resuming) "Finishing your import" else "Import card image",
            20f, VaultTheme.textPrimary, 600, heading = true
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        })
        column.addView(VaultUi.text(
            this,
            if (resuming) {
                "Reading the card photo you picked. This happens on this device — nothing is uploaded."
            } else {
                "Choose a photo from Google Photos or your phone's gallery. The card is read on this device — nothing is uploaded."
            },
            11f, VaultTheme.textMuted, 430, gravity = Gravity.CENTER, lineSpacing = 1.3f
        ).apply {
            setPadding(0, dp(8), 0, dp(20))
        })
        if (resuming) {
            val progress = android.widget.ProgressBar(this).apply {
                indeterminateTintList = ColorStateList.valueOf(VaultTheme.accentMint)
            }
            column.addView(progress, LinearLayout.LayoutParams(dp(30), dp(30)))
        } else {
            // If the OS killed the process and dropped the picker, this screen
            // is what the user returns to. Give them a way to reopen the picker
            // instead of leaving only a Cancel button.
            column.addView(VaultUi.primaryButton(this, "Choose photo", R.drawable.ic_image, 48) {
                pickCardImage()
            }, LinearLayout.LayoutParams(-1, dp(48)))
            column.addView(VaultUi.secondaryButton(this, "Cancel", R.drawable.ic_close, 48) {
                cancelImport()
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        }
        panel.addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
    }

    private fun showCardCamera(panel: FrameLayout) {
        val captureFile = newImportFile()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(10), dp(18), dp(18))
        }
        column.addView(VaultUi.text(this, "Scan a card", 20f, VaultTheme.textPrimary, 600, heading = true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        column.addView(VaultUi.text(this, "Hold the card flat, steady and well lit. The number is read on this device — the photo never leaves the phone.", 11f, VaultTheme.textMuted, 430, gravity = Gravity.CENTER, lineSpacing = 1.3f).apply {
            setPadding(0, dp(4), 0, dp(10))
        })

        val previewHost = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.rgb(14, 15, 18))
                setStroke(dp(1), VaultTheme.border)
            }
            clipToOutline = true
        }
        val lens = TextureView(this)
        previewHost.addView(lens, FrameLayout.LayoutParams(-1, dp(400), Gravity.CENTER))
        column.addView(previewHost, LinearLayout.LayoutParams(-1, dp(400)).apply { topMargin = dp(8) })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        val back = VaultUi.secondaryButton(this, "Cancel", R.drawable.ic_close, 50) {
            cameraEngine?.close()
            cameraEngine = null
            cancelImport()
        }
        controls.addView(back, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(10) })
        val shutter = VaultUi.primaryButton(this, "Capture", R.drawable.ic_camera, 50) { cameraEngine?.capture() }
        controls.addView(shutter, LinearLayout.LayoutParams(0, dp(50), 1f))
        column.addView(controls, LinearLayout.LayoutParams(-1, -2))

        panel.addView(column, FrameLayout.LayoutParams(-1, -2))

        val engine = CardCamera(
            this, lens, captureFile,
            onCaptured = { file ->
                cameraEngine = null
                savePendingImport(file)
                readCardImage(Uri.fromFile(file), file)
            },
            onFailure = { reason ->
                cameraEngine = null
                captureFile.delete()
                cancelImport()
                message(reason)
            }
        )
        cameraEngine = engine
        engine.start()
    }

    private fun newImportFile(): File =
        File(cacheDir, "card-import-${System.currentTimeMillis()}.jpg")

    private fun savePendingImport(file: File) {
        importPrefs.edit()
            .putString("file", file.absolutePath)
            .putLong("at", System.currentTimeMillis())
            .apply()
    }

    private fun clearPendingImport() {
        importPrefs.edit().clear().apply()
    }

    /** Re-run OCR after a process kill, using the photo saved during import. */
    private fun resumePendingImportIfAny() {
        if (session == null) return
        val path = importPrefs.getString("file", null) ?: return
        val savedAt = importPrefs.getLong("at", 0L)
        val pending = File(path)
        if (System.currentTimeMillis() - savedAt > 15 * 60_000L ||
            !pending.exists() || pending.length() == 0L
        ) {
            clearPendingImport()
            return
        }
        message("Finishing the card photo from your last import…")
        readCardImage(Uri.fromFile(pending), pending)
    }

    /** Cancels any import screen and returns to the vault. */
    private fun cancelImport() {
        cameraEngine?.close()
        cameraEngine = null
        importGeneration++
        importCleanupFile?.delete()
        importCleanupFile = null
        pendingReturnToVault = false
        importMode = null
        clearPendingImport()
        SessionKeeper.clear(this)
        if (session != null) render()
        rearmIdle()
    }

    /** Import screens are gone — restore the normal idle auto-lock countdown. */
    private fun rearmIdle() {
        if (session != null && recoveryToShow == null) {
            mainHandler.removeCallbacks(idleLock)
            mainHandler.postDelayed(idleLock, autoLockMillis)
        }
    }

    private fun readCardImage(uri: Uri, cleanupFile: File? = null) {
        val generation = importGeneration
        importCleanupFile = cleanupFile
        message("Reading card text on this device…")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val task = runCatching { recognizer.process(InputImage.fromFilePath(this, uri)) }
            .getOrElse {
                recognizer.close()
                cleanupFile?.delete()
                importCleanupFile = null
                clearPendingImport()
                importMode = null
                render()
                rearmIdle()
                message("Could not open that image.")
                return
            }
        task.addOnSuccessListener { result ->
            recognizer.close()
            if (generation != importGeneration) return@addOnSuccessListener
            // Keep the import screen visible behind the form. The vault
            // homepage is only drawn after the form is dismissed, so the user
            // never sees the app jump back to the home page mid-import.
            pendingReturnToVault = true
            val detected = parseCardText(result.text)
            if (detected.number.isBlank()) {
                message("I could not confidently read a card number. Try a clear, well-lit image or enter it manually.")
                addEntry("Card")
            } else {
                message("Card number and expiry detected. Review every field before saving.")
                addEntry("Card", detected)
            }
        }.addOnFailureListener {
            recognizer.close()
            if (generation != importGeneration) return@addOnFailureListener
            pendingReturnToVault = true
            message("Could not read the image. You can enter the card details manually.")
            addEntry("Card")
        }
    }

    private fun parseCardText(text: String): CardPrefill {
        val numberPattern = Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")
        val number = numberPattern.findAll(text)
            .map { match -> match.value.filter { it.isDigit() } }
            .firstOrNull { it.length in 13..19 && passesLuhn(it) }
            .orEmpty()
        val expiryPattern = Regex("(?<!\\d)(0?[1-9]|1[0-2])\\s*[/|-]\\s*(\\d{2}|\\d{4})(?!\\d)")
        val expiry = expiryPattern.find(text)?.let {
            val month = it.groupValues[1].padStart(2, '0')
            val year = it.groupValues[2].takeLast(2)
            "$month/$year"
        }.orEmpty()
        val cvv = Regex("(?i)(?:CVV|CVC|security code)\\D{0,8}(\\d{3,4})")
            .find(text)?.groupValues?.get(1).orEmpty()
        return CardPrefill(number, expiry, cvv)
    }

    private fun passesLuhn(number: String): Boolean {
        val sum = number.reversed().mapIndexed { index, char ->
            var digit = char.digitToInt()
            if (index % 2 == 1) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            digit
        }.sum()
        return sum % 10 == 0
    }

    // ==========================================
    // CLIPBOARD & PERSISTENCE
    // ==========================================
    private fun copySensitive(value: String) {
        if (value.isBlank()) {
            message("There is nothing to copy.")
            return
        }
        pendingClipboardClear?.let(mainHandler::removeCallbacks)
        val appContext = applicationContext
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val token = CLIP_TOKEN_PREFIX + UUID.randomUUID().toString()
        val clip = ClipData.newPlainText("Sensitive vault value", value)
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
            putString(CLIP_TOKEN_EXTRA, token)
        }
        clipboard.setPrimaryClip(clip)
        // Only the application context and a unique token are retained. The task is
        // safe to run after this Activity is destroyed, and it never clears a clip
        // that the user has replaced in the meantime.
        val clearTask = Runnable {
            val currentToken = clipboard.primaryClip?.description?.extras?.getString(CLIP_TOKEN_EXTRA)
            if (currentToken == token) clipboard.clearPrimaryClip()
        }
        pendingClipboardClear = clearTask
        mainHandler.postDelayed(clearTask, 30_000)
        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        success("Copied securely · clears in 30s")
    }

    private fun clearVaultClipboardNow() {
        pendingClipboardClear?.let(mainHandler::removeCallbacks)
        pendingClipboardClear = null
        val clipboard = applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val currentToken = clipboard.primaryClip?.description?.extras?.getString(CLIP_TOKEN_EXTRA)
        if (currentToken?.startsWith(CLIP_TOKEN_PREFIX) == true) clipboard.clearPrimaryClip()
    }

    private fun persistAndRender(successMessage: String? = null) {
        val current = session
        if (current == null) {
            lockNow()
            message("Vault is locked. Unlock to save changes.")
            return
        }
        runCatching { VaultStore.save(this, current.entries, current.dek) }
            .onSuccess {
                render()
                successMessage?.let(::success)
            }
            .onFailure { message("Could not save the encrypted vault: ${it.message}") }
    }

    // ==========================================
    // SESSION
    // ==========================================
    private fun showRecoveryUnlock() {
        recoveryUnlockMode = true
        render()
    }

    private fun lockNow() {
        mainHandler.removeCallbacks(idleLock)
        clearVaultClipboardNow()
        activeDialog?.dismiss()
        activeDialog = null
        SessionKeeper.clear(this)
        clearPendingImport()
        importGeneration++
        importCleanupFile?.delete()
        importCleanupFile = null
        pendingReturnToVault = false
        importMode = null
        session?.dek?.fill(0)
        session = null
        recoveryUnlockMode = false
        recoveryContinuation = null
        searchQuery = ""
        expandedCards.clear()
        activeSection = "Overview"
        render()
    }

    // ==========================================
    // DIALOG STYLING & FEEDBACK
    // ==========================================
    private fun styleDialog(dialog: AlertDialog) {
        dialog.window?.apply {
            setBackgroundDrawable(VaultTheme.cardShape(this@MainActivity, 24, Color.rgb(19, 21, 31), VaultTheme.borderStrong))
            setDimAmount(0.72f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            decorView.setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        dialog.findViewById<TextView>(android.R.id.title)?.let {
            VaultTheme.styleText(it, VaultTheme.textPrimary, 19f, 650, heading = true)
        }
        dialog.findViewById<TextView>(android.R.id.message)?.let {
            VaultTheme.styleText(it, VaultTheme.textSecondary, 12.5f, 430)
            it.setLineSpacing(0f, 1.3f)
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(Color.WHITE)
            background = VaultTheme.accentButtonDrawable(this@MainActivity, 12)
            isAllCaps = false
            typeface = VaultTheme.typeface(this@MainActivity, 600, heading = true)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
            setTextColor(VaultTheme.textSecondary)
            background = VaultTheme.secondaryButtonDrawable(this@MainActivity, 12)
            isAllCaps = false
            typeface = VaultTheme.typeface(this@MainActivity, 500)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
            setTextColor(VaultTheme.textMuted)
            isAllCaps = false
        }
    }

    private fun success(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun message(text: String, duration: Int = Toast.LENGTH_LONG) =
        Toast.makeText(this, text, duration).show()
}
