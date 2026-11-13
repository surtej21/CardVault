package com.example.cardvault

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
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
import android.os.Environment
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
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_CARD_CAMERA_PERM = 9011
        private const val REQUEST_CARD_IMAGE = 9013
        private const val REQUEST_BACKUP_IMPORT = 9014
        private const val BACKUP_MIME = "application/octet-stream"
        private const val MAX_BACKUP_BYTES = 8 * 1024 * 1024
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

    /** Encrypted backup transfer state. Kept separate from card-import state. */
    private var backupMode = false
    private var backupWorking = false
    private var backupError: String? = null
    private var backupRestoredNotice = false
    private val backupPrefs by lazy { getSharedPreferences("backup_transfer", MODE_PRIVATE) }
    private val backupExecutor by lazy { Executors.newSingleThreadExecutor() }

    private var activeSection = "Overview"
    private var searchQuery = ""
    private var activeDialog: Dialog? = null
    private val expandedCards = mutableSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoLockMillis = 60_000L
    private val idleLock = Runnable {
        if (session != null && recoveryToShow == null && importMode == null && !backupMode && activeDialog == null) lockNow()
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
        // A backup picker may have had the process killed underneath it. Keep
        // the transfer screen up until the picker result arrives.
        val backupAt = backupPrefs.getLong("at", 0L)
        if (backupAt > 0L && System.currentTimeMillis() - backupAt <= 15 * 60_000L) {
            backupMode = true
        } else if (backupAt > 0L) {
            backupPrefs.edit().clear().apply()
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                predictiveBackCallback
            )
        }
        // Release builds block screen capture and recent-app previews. Debug
        // builds leave it off so the emulator can capture design screenshots.
        if (!BuildConfig.DEBUG) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
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
        if (backupMode) session?.let { SessionKeeper.save(this, it.dek) }
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
        if (session != null && importMode == null && !backupMode) {
            mainHandler.removeCallbacks(idleLock)
            mainHandler.postDelayed(idleLock, autoLockMillis)
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (session != null && recoveryToShow == null && importMode == null && !backupMode) {
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
        if (backupMode) {
            cancelBackupImport()
            return true
        }
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
        val vaultVisible = session != null && recoveryToShow == null && importMode == null && !backupMode

        val screen = FrameLayout(this).apply { background = VaultTheme.auroraBackground(this@MainActivity) }

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
            backupMode -> showBackupTransfer()
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
                    Color.argb(252, 8, 9, 11),
                    Color.argb(252, 8, 9, 11),
                    Color.argb(250, 8, 9, 11),
                    Color.argb(244, 8, 9, 11),
                    Color.argb(0, 8, 9, 11)
                )
            )
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brand.addView(VaultUi.vaultDoor(this, 34), LinearLayout.LayoutParams(dp(34), dp(34)))
        val wordmark = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        wordmark.addView(VaultUi.text(this, "AURA", 15.5f, VaultTheme.textPrimary, 650, heading = true, letterSpacing = 0.14f))
        wordmark.addView(VaultUi.text(this, "VAULT", 8f, VaultTheme.accent, 650, heading = true, letterSpacing = 0.32f).apply {
            setPadding(0, dp(1), 0, 0)
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
            addView(VaultUi.icon(this@MainActivity, R.drawable.ic_plus, VaultTheme.buttonInk, 20), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }
        bar.addView(add, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) })
        bar.addView(VaultUi.iconButton(this, R.drawable.ic_settings, "Settings", 44) { openSettings() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) })
        bar.addView(VaultUi.iconButton(this, R.drawable.ic_lock, "Lock vault", 44) {
            window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            lockNow()
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        return bar
    }

    private fun buildBottomNav(): FrameLayout {
        val wrap = FrameLayout(this).apply {
            // Fade scrolled content under the bar instead of letting it hard-overlap the pill.
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.argb(0, 8, 9, 11),
                    Color.argb(160, 8, 9, 11),
                    Color.argb(246, 8, 9, 11),
                    Color.argb(252, 8, 9, 11)
                )
            )
            setPadding(dp(16), dp(10), dp(16), dp(12))
            elevation = dp(12).toFloat()
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = VaultTheme.glassShape(this@MainActivity, 20, strong = true)
            setPadding(dp(6), dp(5), dp(6), dp(5))
        }
        PRIMARY_SECTIONS.forEach { section ->
            val selected = activeSection == section
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = navLabel(section)
                background = VaultTheme.rowRipple(this@MainActivity)
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (activeSection != section) {
                        activeSection = section
                        searchQuery = ""
                        render()
                    }
                }
            }
            item.addView(View(this).apply {
                background = if (selected) VaultTheme.accentButtonDrawable(this@MainActivity, 99) else null
            }, LinearLayout.LayoutParams(dp(18), dp(2)).apply {
                bottomMargin = dp(5)
            })
            item.addView(VaultUi.icon(this, navIcon(section), if (selected) VaultTheme.accent else VaultTheme.textMuted, 21))
            item.addView(VaultUi.text(
                this, navLabel(section), 9.5f,
                if (selected) VaultTheme.textPrimary else VaultTheme.textMuted,
                if (selected) 650 else 500,
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
            setPadding(0, dp(6), 0, 0)
        }
        lockup.addView(VaultUi.vaultDoor(this, 132), LinearLayout.LayoutParams(dp(132), dp(132)))
        lockup.addView(VaultUi.text(
            this, "AURA VAULT", 10f, VaultTheme.accent, 650,
            heading = true, letterSpacing = 0.34f, gravity = Gravity.CENTER
        ).apply { setPadding(0, dp(16), 0, 0) })
        lockup.addView(VaultUi.brassRule(this, 48), LinearLayout.LayoutParams(dp(48), dp(1)).apply {
            topMargin = dp(12)
        })
        root.addView(lockup, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(26) })

        root.addView(VaultUi.text(this, eyebrow.uppercase(), 10f, VaultTheme.textFaint, 600, letterSpacing = 0.16f, gravity = Gravity.CENTER))
        root.addView(VaultUi.gradientText(this, title, 29f, weight = 600).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(10))
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(VaultUi.text(this, subtitle, 13.5f, VaultTheme.textSecondary, 430, gravity = Gravity.CENTER, lineSpacing = 1.35f).apply {
            setPadding(dp(8), 0, dp(8), dp(24))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun formPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = VaultTheme.glassShape(this@MainActivity, 14, strong = true)
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
            host.addView(VaultUi.text(this, label.uppercase(), 10.5f, VaultTheme.textMuted, 600, letterSpacing = 0.1f).apply {
                setPadding(dp(2), 0, 0, dp(7))
            })
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 10, strong = false)
            setPadding(dp(14), 0, dp(8), 0)
        }
        val edit = EditText(this).apply {
            this.hint = hint
            textSize = 15f
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setTextColor(VaultTheme.textPrimary)
            setHintTextColor(VaultTheme.textFaint)
            background = null
            typeface = VaultTheme.typeface(this@MainActivity, 450)
            setPadding(0, dp(16), 0, dp(16))
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
                addView(VaultUi.icon(this@MainActivity, R.drawable.ic_eye, VaultTheme.textMuted, 18), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
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
            container.addView(eye, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(4) })
        }

        edit.setOnFocusChangeListener { _, hasFocus ->
            container.background = VaultTheme.glassShape(
                this@MainActivity,
                10,
                strong = hasFocus,
                borderColor = if (hasFocus) VaultTheme.accent else VaultTheme.border
            )
        }
        host.addView(container, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(12) })
        return edit
    }

    private fun primaryBtn(text: String, iconRes: Int? = null, action: () -> Unit): LinearLayout {
        val button = VaultUi.primaryButton(this, text, iconRes, 54, action)
        root.addView(button, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(10) })
        return button
    }

    private fun secondaryBtn(text: String, iconRes: Int? = null, action: () -> Unit): LinearLayout {
        val button = VaultUi.secondaryButton(this, text, iconRes, 52, action)
        root.addView(button, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(10) })
        return button
    }

    private fun textBtn(text: String, color: Int = VaultTheme.textSecondary, action: () -> Unit) {
        val tv = VaultUi.text(this, text, 13f, color, 500, gravity = Gravity.CENTER).apply {
            setPadding(0, dp(12), 0, dp(12))
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

        textBtn("Restore from encrypted backup", VaultTheme.accent) { beginBackupImport() }
    }

    private fun showUnlock() {
        authHeader(
            "Welcome back",
            "Unlock your vault",
            "Your vault is encrypted on this device and nowhere else."
        )

        if (backupRestoredNotice) {
            val notice = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = VaultTheme.glassShape(this@MainActivity, 14, strong = true)
                setPadding(dp(13), dp(12), dp(13), dp(12))
            }
            notice.addView(VaultUi.tintedBadge(this, R.drawable.ic_check, VaultTheme.accentMint, 34))
            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), 0, 0, 0)
            }
            copy.addView(VaultUi.text(this, "Backup restored", 12.5f, VaultTheme.textPrimary, 600))
            copy.addView(VaultUi.text(
                this,
                "Unlock with the master password or recovery key that protected this backup.",
                10.5f, VaultTheme.textMuted, 430, lineSpacing = 1.3f
            ).apply { setPadding(0, dp(2), 0, 0) })
            notice.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(notice, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        }

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
                        backupRestoredNotice = false
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
        keyPanel.addView(VaultUi.text(this, "RECOVERY KEY  ·  SHOWN ONCE", 9.5f, VaultTheme.accent, 600, letterSpacing = 0.12f))
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
            11f, VaultTheme.textFaint, 430, gravity = Gravity.CENTER, lineSpacing = 1.3f
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
                    backupRestoredNotice = false
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
        root.addView(VaultUi.text(this, eyebrow.uppercase(), 10f, VaultTheme.textFaint, 600, letterSpacing = 0.16f))
        root.addView(VaultUi.text(this, title, 28f, VaultTheme.textPrimary, 600, heading = true, lineSpacing = 1.02f).apply {
            setPadding(0, dp(5), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(VaultUi.text(this, description, 13f, VaultTheme.textSecondary, 430, lineSpacing = 1.3f).apply {
            setPadding(0, dp(7), 0, dp(18))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun sectionLabel(text: String) {
        root.addView(VaultUi.text(this, text.uppercase(), 9.5f, VaultTheme.textFaint, 600, letterSpacing = 0.16f).apply {
            setPadding(dp(2), dp(20), 0, dp(9))
        })
    }

    private fun footerNote(): TextView = VaultUi.text(
        this, "AURA VAULT   ·   LOCAL ONLY   ·   v0.2.3",
        9f, VaultTheme.textFaint, 600, letterSpacing = 0.14f, gravity = Gravity.CENTER
    ).apply { setPadding(0, dp(16), 0, dp(8)) }

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

        // Vault-door hero — the signature plate.
        val hero = FrameLayout(this).apply {
            background = VaultTheme.plateShape(this@MainActivity, 18)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(-1, dp(246))
        }
        addRivet(hero, Gravity.TOP or Gravity.START)
        addRivet(hero, Gravity.TOP or Gravity.END)
        addRivet(hero, Gravity.BOTTOM or Gravity.START)
        addRivet(hero, Gravity.BOTTOM or Gravity.END)

        val heroCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(116), dp(44))
        }
        val kicker = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        kicker.addView(View(this).apply {
            background = VaultTheme.brassRuleDrawable(this@MainActivity)
        }, LinearLayout.LayoutParams(dp(18), dp(1)).apply { marginEnd = dp(8) })
        kicker.addView(VaultUi.text(this, "AURA VAULT  ·  SEALED", 9f, VaultTheme.accent, 650, letterSpacing = 0.18f))
        heroCopy.addView(kicker)
        heroCopy.addView(VaultUi.text(this, "Everything important,", 22f, VaultTheme.textPrimary, 600, heading = true, lineSpacing = 1.04f).apply {
            setPadding(0, dp(11), 0, 0)
        })
        heroCopy.addView(VaultUi.text(this, "kept in one place.", 22f, VaultTheme.accent, 600, heading = true, lineSpacing = 1.04f))
        heroCopy.addView(VaultUi.text(this, "Cards and logins, encrypted on this device.", 11.5f, VaultTheme.textMuted, 430, lineSpacing = 1.3f).apply {
            setPadding(0, dp(9), 0, 0)
        })
        val securityLink = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
            isClickable = true
            isFocusable = true
            contentDescription = "Open security center"
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                go("Security")
            }
        }
        securityLink.addView(VaultUi.text(this, "View security overview", 11.5f, VaultTheme.accent, 550), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        securityLink.addView(VaultUi.icon(this, R.drawable.ic_arrow_right, VaultTheme.accent, 14))
        heroCopy.addView(securityLink)
        hero.addView(heroCopy, FrameLayout.LayoutParams(-1, -1))
        hero.addView(VaultUi.vaultDoor(this, 148), FrameLayout.LayoutParams(dp(148), dp(148), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = -dp(4)
        })
        val heroFoot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroFoot.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(VaultTheme.accent)
            }
        }, LinearLayout.LayoutParams(dp(5), dp(5)).apply { marginEnd = dp(7) })
        heroFoot.addView(VaultUi.text(this, "Vault sealed", 9.5f, VaultTheme.textSecondary, 500))
        heroFoot.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        heroFoot.addView(VaultUi.text(this, "LOCAL ONLY", 9f, VaultTheme.textFaint, 600, letterSpacing = 0.14f))
        hero.addView(heroFoot, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(20)
            rightMargin = dp(20)
            bottomMargin = dp(13)
        })
        root.addView(hero, LinearLayout.LayoutParams(-1, dp(246)).apply { bottomMargin = dp(12) })

        // Ledger: one plate, four rows — not four little cards.
        val counts = mapOf(
            "Card" to entries.count { it.kind == "Card" },
            "Bank" to entries.count { it.kind == "Bank" },
            "Password" to entries.count { it.kind == "Password" },
            "Custom" to entries.count { it.kind == "Custom" }
        )
        val total = entries.size
        val ledger = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 14)
            setPadding(0, dp(13), 0, dp(4))
        }
        val ledgerHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), dp(6))
        }
        ledgerHead.addView(VaultUi.text(this, "IN THE VAULT", 9.5f, VaultTheme.textFaint, 600, letterSpacing = 0.16f), LinearLayout.LayoutParams(0, -2, 1f))
        ledgerHead.addView(VaultUi.text(this, "$total ${if (total == 1) "ITEM" else "ITEMS"}", 9.5f, VaultTheme.textMuted, 600, letterSpacing = 0.1f))
        ledger.addView(ledgerHead)
        ledger.addView(ledgerRow(R.drawable.ic_card, "Payment cards", counts.getValue("Card").toString()) { go("Cards") })
        ledger.addView(insetDivider(48))
        ledger.addView(ledgerRow(R.drawable.ic_bank, "Bank accounts", counts.getValue("Bank").toString()) { go("Bank logins") })
        ledger.addView(insetDivider(48))
        ledger.addView(ledgerRow(R.drawable.ic_key, "Passwords", counts.getValue("Password").toString()) { go("Passwords") })
        ledger.addView(insetDivider(48))
        ledger.addView(ledgerRow(R.drawable.ic_folder, "Custom items", counts.getValue("Custom").toString()) { go("Custom") })
        root.addView(ledger, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // Security health panel
        val audit = SecurityAudit.audit(entries)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 14, strong = true)
            setPadding(dp(16), dp(16), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headCopy.addView(VaultUi.text(this, "Security health", 16.5f, VaultTheme.textPrimary, 600, heading = true))
        headCopy.addView(VaultUi.text(this, "Encrypted on this device, last checked just now.", 11f, VaultTheme.textMuted, 430).apply {
            setPadding(0, dp(3), 0, 0)
        })
        head.addView(headCopy, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(VaultUi.iconButton(this, R.drawable.ic_arrow_up_right, "Open security center", 40) { go("Security") })
        panel.addView(head, LinearLayout.LayoutParams(-1, -2))

        val scoreRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        scoreRow.addView(VaultUi.scoreRing(this, audit.score, 100).apply {
            layoutParams = LinearLayout.LayoutParams(dp(100), dp(100)).apply { marginEnd = dp(14) }
        })
        val scoreCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val color = scoreColor(audit.score)
        scoreCopy.addView(statusPill(audit.statusText, color))
        scoreCopy.addView(VaultUi.text(this, "Current standing", 15.5f, VaultTheme.textPrimary, 600, heading = true).apply {
            setPadding(0, dp(8), 0, 0)
        })
        scoreCopy.addView(VaultUi.text(this, if (audit.recommendations.isEmpty()) "No weak or reused passwords right now." else audit.recommendations.joinToString("  ·  "), 10.5f, VaultTheme.textMuted, 430, lineSpacing = 1.3f).apply {
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
        open.addView(VaultUi.text(this, "Open security center", 11f, VaultTheme.accent, 550), LinearLayout.LayoutParams(0, -2, 1f))
        open.addView(VaultUi.icon(this, R.drawable.ic_arrow_right, VaultTheme.accent, 15))
        panel.addView(open)
        root.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // Quick access
        val sectionHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(9))
        }
        val sectionCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sectionCopy.addView(VaultUi.text(this, "Quick access", 18f, VaultTheme.textPrimary, 600, heading = true))
        sectionCopy.addView(VaultUi.text(this, "Your pins, one tap away.", 11f, VaultTheme.textMuted, 430).apply {
            setPadding(0, dp(2), 0, 0)
        })
        sectionHead.addView(sectionCopy, LinearLayout.LayoutParams(0, -2, 1f))
        sectionHead.addView(VaultUi.text(this, "View all", 11f, VaultTheme.accent, 600).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), 0, dp(12))
            isClickable = true
            isFocusable = true
            contentDescription = "View all cards"
            setOnClickListener { go("Cards") }
        })
        root.addView(sectionHead)

        if (entries.isEmpty()) {
            root.addView(emptyPrompt("Your vault is ready", "Add a card, account, or login to see it here.", R.drawable.ic_plus) {
                chooseItemType()
            }, LinearLayout.LayoutParams(-1, -2))
        } else {
            val quickPanel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = VaultTheme.glassShape(this@MainActivity, 14)
            }
            entries.take(3).forEachIndexed { index, entry ->
                if (index > 0) quickPanel.addView(insetDivider(54))
                quickPanel.addView(quickRow(entry), LinearLayout.LayoutParams(-1, -2))
            }
            root.addView(quickPanel, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun addRivet(host: FrameLayout, gravity: Int) {
        host.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(64, 220, 224, 230))
            }
        }, FrameLayout.LayoutParams(dp(3), dp(3), gravity).apply {
            if (gravity and Gravity.TOP != 0) topMargin = dp(11) else bottomMargin = dp(11)
            if (gravity and Gravity.START != 0) leftMargin = dp(11) else rightMargin = dp(11)
        })
    }

    private fun insetDivider(startDp: Int): View = View(this).apply {
        setBackgroundColor(VaultTheme.borderFaint)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply {
            marginStart = dp(startDp)
            marginEnd = dp(16)
        }
    }

    private fun ledgerRow(iconRes: Int, label: String, value: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(-1, dp(54))
            isClickable = true
            isFocusable = true
            contentDescription = "$label, $value"
            background = VaultTheme.rowRipple(this@MainActivity)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }
        row.addView(VaultUi.icon(this, iconRes, VaultTheme.textMuted, 17), LinearLayout.LayoutParams(dp(17), dp(17)))
        row.addView(VaultUi.text(this, label.uppercase(), 11f, VaultTheme.textSecondary, 600, letterSpacing = 0.08f), LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = dp(12)
        })
        row.addView(VaultUi.text(this, value, 20f, VaultTheme.accent, 500).apply {
            typeface = VaultTheme.mono(this@MainActivity)
        })
        row.addView(VaultUi.icon(this, R.drawable.ic_chevron_right, VaultTheme.textFaint, 16), LinearLayout.LayoutParams(dp(16), dp(16)).apply {
            marginStart = dp(10)
        })
        return row
    }

    private fun miniStat(value: String, label: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(VaultUi.icon(this, R.drawable.ic_check, VaultTheme.accentMint, 14), LinearLayout.LayoutParams(dp(20), dp(14)).apply {
            marginEnd = dp(2)
        })
        row.addView(VaultUi.text(this, value, 13f, VaultTheme.textPrimary, 650))
        row.addView(VaultUi.text(this, " $label", 10.5f, VaultTheme.textMuted, 430))
        return row
    }

    private fun statusPill(text: String, color: Int): TextView = VaultUi.text(
        this, text.uppercase(), 9.5f, color, 650, letterSpacing = 0.08f
    ).apply {
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(5), dp(9), dp(5))
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
            setPadding(dp(12), dp(10), dp(10), dp(10))
            minimumHeight = dp(62)
            isClickable = true
            isFocusable = true
            contentDescription = entry.title
            background = VaultTheme.rowRipple(this@MainActivity)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                openEntry(entry)
            }
        }
        row.addView(VaultUi.articleMark(this, entry.title, kindTint(entry.kind), 40))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(6), 0)
        }
        copy.addView(VaultUi.text(this, entry.title.ifBlank { "Untitled" }, 14f, VaultTheme.textPrimary, 600).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        copy.addView(VaultUi.text(this, entrySubtitle(entry), 11f, VaultTheme.textMuted, 430).apply {
            setPadding(0, dp(2), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))

        val copyValue = quickCopyValue(entry)
        if (copyValue.isNotBlank()) {
            row.addView(VaultUi.iconButton(this, R.drawable.ic_copy, "Copy ${entry.title}", 40) {
                copySensitive(copyValue)
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
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
        left.addView(VaultUi.text(this, "${kindEntries.size} ${if (kindEntries.size == 1) "item" else "items"}  ·  encrypted locally", 10.5f, VaultTheme.textMuted, 500))
        toolbar.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(VaultUi.text(this, "UPDATED JUST NOW", 9f, VaultTheme.textFaint, 600, letterSpacing = 0.12f))
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addSearchBar(spec.searchHint) { refreshEntryList(list, entries) }
        root.addView(list, LinearLayout.LayoutParams(-1, -2))
        refreshEntryList(list, entries)

        val tip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(18), dp(2), 0)
        }
        tip.addView(VaultUi.icon(this, R.drawable.ic_lock, VaultTheme.textFaint, 15), LinearLayout.LayoutParams(dp(15), dp(15)).apply {
            marginEnd = dp(8)
        })
        tip.addView(VaultUi.text(this, "Encrypted on this device. Nothing leaves the vault.", 10.5f, VaultTheme.textFaint, 450), LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(tip, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addSearchBar(hint: String, onChanged: () -> Unit) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 12)
            setPadding(dp(14), 0, dp(7), 0)
        }
        bar.addView(VaultUi.icon(this, R.drawable.ic_search, VaultTheme.textFaint, 18), LinearLayout.LayoutParams(dp(18), dp(18)))
        val edit = EditText(this).apply {
            this.hint = hint
            setSingleLine(true)
            setTextColor(VaultTheme.textPrimary)
            setHintTextColor(VaultTheme.textFaint)
            textSize = 14f
            typeface = VaultTheme.typeface(this@MainActivity, 450)
            background = null
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setPadding(dp(10), dp(13), dp(6), dp(13))
            setText(searchQuery)
            setSelection(text.length)
        }
        bar.addView(edit, LinearLayout.LayoutParams(0, dp(50), 1f))
        val clear = FrameLayout(this).apply {
            background = VaultTheme.iconButtonDrawable(this@MainActivity, 9)
            contentDescription = "Clear search"
            visibility = if (searchQuery.isBlank()) View.GONE else View.VISIBLE
            isClickable = true
            isFocusable = true
            addView(VaultUi.icon(this@MainActivity, R.drawable.ic_close, VaultTheme.textMuted, 15), FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER))
            setOnClickListener {
                edit.setText("")
                edit.requestFocus()
            }
        }
        bar.addView(clear, LinearLayout.LayoutParams(dp(38), dp(38)))
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                clear.visibility = if (searchQuery.isBlank()) View.GONE else View.VISIBLE
                onChanged()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(12) })
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
            background = VaultTheme.glassShape(this@MainActivity, 14)
            setPadding(dp(24), dp(26), dp(24), dp(24))
        }
        panel.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(VaultTheme.brassWash)
                setStroke(dp(1), VaultTheme.brassFaint)
            }
            addView(VaultUi.icon(this@MainActivity, iconRes, VaultTheme.accent, 24), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        panel.addView(VaultUi.text(this, title, 16.5f, VaultTheme.textPrimary, 600, heading = true, gravity = Gravity.CENTER).apply {
            setPadding(0, dp(14), 0, 0)
        })
        panel.addView(VaultUi.text(this, body, 11.5f, VaultTheme.textMuted, 430, gravity = Gravity.CENTER, lineSpacing = 1.3f).apply {
            setPadding(dp(8), dp(6), dp(8), 0)
        }, LinearLayout.LayoutParams(-1, -2))
        val cta = VaultUi.primaryButton(this, "Add new", R.drawable.ic_plus, 48) { onAdd() }
        panel.addView(cta, LinearLayout.LayoutParams(dp(168), dp(48)).apply { topMargin = dp(16) })
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
            setPadding(dp(4), dp(12), dp(4), dp(12))
            minimumHeight = dp(64)
            isClickable = true
            isFocusable = true
            contentDescription = "${entry.title}. Tap for details."
            background = VaultTheme.rowRipple(this@MainActivity)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                toggle()
            }
        }
        card.addView(VaultUi.articleMark(this, entry.title, kindTint(entry.kind), 42))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(6), 0)
        }
        info.addView(VaultUi.text(this, entry.title.ifBlank { "Untitled" }, 14.5f, VaultTheme.textPrimary, 600).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val subRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, 0)
        }
        subRow.addView(VaultUi.text(this, entrySubtitle(entry), 11.5f, VaultTheme.textMuted, 430).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (entry.kind == "Password" && entry.secret.isNotBlank()) {
            val (label, color) = SecurityAudit.getStrengthLabel(entry.secret)
            subRow.addView(VaultUi.text(this, label.uppercase(), 9.5f, color, 650, letterSpacing = 0.08f).apply {
                setPadding(dp(7), dp(3), dp(7), dp(3))
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
            VaultTheme.textFaint, 18
        ), LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(4) })
        host.addView(card, LinearLayout.LayoutParams(-1, -2))
        if (expanded) {
            host.addView(detailsPanel(entry, entries), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(6)
                bottomMargin = dp(10)
            })
        }
        host.addView(insetDivider(58))
    }

    private fun toggleExpanded(id: String) {
        if (expandedCards.contains(id)) expandedCards.remove(id) else expandedCards.add(id)
        render(preserveScroll = true)
    }

    private fun detailsPanel(entry: VaultStore.Entry, entries: MutableList<VaultStore.Entry>): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 12, strong = true)
            setPadding(dp(14), dp(14), dp(14), dp(12))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, 0, dp(6))
        }
        head.addView(VaultUi.text(this, "STORED DETAILS", 9.5f, VaultTheme.textFaint, 600, letterSpacing = 0.16f), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(VaultUi.text(this, "LOCAL", 8.5f, VaultTheme.accent, 650, letterSpacing = 0.14f))
        panel.addView(head)
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
            setPadding(0, dp(8), 0, dp(8))
        }
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(VaultUi.text(this, title.uppercase(), 9.5f, VaultTheme.textFaint, 600, letterSpacing = 0.12f))
        val valueText = VaultUi.text(this, if (secret) "••••••••" else value, 13.5f, VaultTheme.textPrimary, 450, lineSpacing = 1.3f).apply {
            if (secret) typeface = VaultTheme.mono(this@MainActivity)
            setPadding(0, dp(4), 0, 0)
        }
        textCol.addView(valueText)
        row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))

        if (secret) {
            var revealed = false
            val eye = VaultUi.iconButton(this, R.drawable.ic_eye, "Show $title", 40) {
                revealed = !revealed
                valueText.text = if (revealed) value else "••••••••"
                val image = it.getChildAt(0) as ImageView
                image.setImageResource(if (revealed) R.drawable.ic_eye_off else R.drawable.ic_eye)
                it.contentDescription = if (revealed) "Hide $title" else "Show $title"
            }
            row.addView(eye, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(6) })
        }

        val copy = VaultUi.iconButton(this, R.drawable.ic_copy, "Copy $title", 40) {
            copyFieldValue(title, value)
        }
        row.addView(copy, LinearLayout.LayoutParams(dp(40), dp(40)))
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
                addView(VaultUi.icon(this@MainActivity, iconRes, tint, 15), LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(5) })
                addView(VaultUi.text(this@MainActivity, label, 11.5f, tint, 600))
            }
            row.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
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
            background = VaultTheme.glassShape(this@MainActivity, 14, strong = true)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        summary.addView(VaultUi.vaultDoor(this, 46), LinearLayout.LayoutParams(dp(46), dp(46)))
        val summaryCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        summaryCopy.addView(VaultUi.text(this, "Aura local vault", 14f, VaultTheme.textPrimary, 600, heading = true))
        summaryCopy.addView(VaultUi.text(this, "AES-256-GCM  ·  no cloud  ·  no network", 10.5f, VaultTheme.textMuted, 430).apply {
            setPadding(0, dp(3), 0, 0)
        })
        summary.addView(summaryCopy, LinearLayout.LayoutParams(0, -2, 1f))
        summary.addView(statusPill("Protected", VaultTheme.accentMint))
        root.addView(summary, LinearLayout.LayoutParams(-1, -2))

        sectionLabel("Security")
        val biometricOn = VaultStore.biometricPayload(this) != null
        root.addView(settingsGroup(listOf(
            settingsRow(
                R.drawable.ic_shield_check, VaultTheme.accentMint,
                "Security center", "Review your vault health and recommendations",
                "Open"
            ) { go("Security") },
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
            },
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
            },
            settingsRow(
                R.drawable.ic_lock, VaultTheme.accentRose,
                "Lock vault", "Close the session and clear sensitive clipboard data",
                "Lock"
            ) { lockNow() }
        )), LinearLayout.LayoutParams(-1, -2))

        sectionLabel("Transfer")
        root.addView(settingsGroup(listOf(
            settingsRow(
                R.drawable.ic_download, VaultTheme.accentMint,
                "Export encrypted backup", "Save to Downloads or share it with another phone",
                "Export"
            ) { showExportSheet() },
            settingsRow(
                R.drawable.ic_upload, VaultTheme.accentAmber,
                "Import encrypted backup", "Replace this vault with a backup file",
                "Import"
            ) { confirmBackupImport() }
        )), LinearLayout.LayoutParams(-1, -2))

        sectionLabel("Privacy")
        root.addView(settingsGroup(listOf(
            infoRow(R.drawable.ic_image, VaultTheme.accentBlue, "Screenshots", "Screen capture and recent-app previews", if (BuildConfig.DEBUG) "Debug" else "Blocked"),
            infoRow(R.drawable.ic_globe, VaultTheme.accentMint, "Network access", "The app has no internet permission", "None"),
            infoRow(R.drawable.ic_copy, VaultTheme.accentAmber, "Clipboard", "Sensitive values are cleared automatically", "30 sec"),
            infoRow(R.drawable.ic_lock, VaultTheme.textSecondary, "Auto-lock", "The vault locks after inactivity", "1 min")
        )), LinearLayout.LayoutParams(-1, -2))

        sectionLabel("About")
        root.addView(settingsGroup(listOf(
            infoRow(R.drawable.ic_sparkles, VaultTheme.accent, "Aura Vault", "Local-only encrypted vault.", "v0.2.3"),
            infoRow(R.drawable.ic_info, VaultTheme.textMuted, "Prototype notice", "Review the security model before real use", "")
        )), LinearLayout.LayoutParams(-1, -2))

        root.addView(VaultUi.text(
            this,
            "This is an offline prototype. It has not been independently security-reviewed. Keep using dummy details until device-level checks are complete.",
            10.5f, VaultTheme.textFaint, 430, gravity = Gravity.CENTER, lineSpacing = 1.35f
        ).apply { setPadding(dp(6), dp(16), dp(6), 0) }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun settingsGroup(rows: List<View>): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 14)
        }
        rows.forEachIndexed { index, row ->
            if (index > 0) panel.addView(insetDivider(58))
            panel.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        return panel
    }

    private fun settingsRow(
        iconRes: Int,
        tint: Int,
        title: String,
        detail: String,
        actionLabel: String,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            minimumHeight = dp(68)
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $actionLabel"
            background = VaultTheme.rowRipple(this@MainActivity)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }
        row.addView(VaultUi.tintedBadge(this, iconRes, tint, 36))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, dp(8), 0)
        }
        copy.addView(VaultUi.text(this, title, 13.5f, VaultTheme.textPrimary, 600))
        copy.addView(VaultUi.text(this, detail, 10.5f, VaultTheme.textFaint, 430, lineSpacing = 1.25f).apply {
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(VaultUi.text(this, actionLabel.uppercase(), 9.5f, VaultTheme.accent, 650, letterSpacing = 0.08f).apply {
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = VaultTheme.pillDrawable(this@MainActivity, true)
        })
        return row
    }

    private fun infoRow(iconRes: Int, tint: Int, title: String, detail: String, value: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            minimumHeight = dp(64)
        }
        row.addView(VaultUi.tintedBadge(this, iconRes, tint, 36))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, dp(8), 0)
        }
        copy.addView(VaultUi.text(this, title, 13.5f, VaultTheme.textPrimary, 600))
        copy.addView(VaultUi.text(this, detail, 10.5f, VaultTheme.textFaint, 430, lineSpacing = 1.25f).apply {
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        if (value.isNotBlank()) {
            row.addView(VaultUi.text(this, value.uppercase(), 9.5f, VaultTheme.textMuted, 650, letterSpacing = 0.08f))
        }
        return row
    }

    // ==========================================
    // ENCRYPTED BACKUP TRANSFER
    // ==========================================
    private fun showExportSheet() {
        val actions = listOf(
            VaultUi.SheetAction(
                R.drawable.ic_download, VaultTheme.accentMint,
                "Save to Downloads", "Download/Aura Vault — ready to copy anywhere"
            ) { exportBackup(share = false) },
            VaultUi.SheetAction(
                R.drawable.ic_share, VaultTheme.accentBlue,
                "Share backup", "Send to Drive, WhatsApp or another app"
            ) { exportBackup(share = true) }
        )
        VaultUi.showActionSheet(this, "Encrypted backup", "Export a copy", actions)
    }

    private fun exportBackup(share: Boolean) {
        val bytes = runCatching { VaultStore.backupBytes(this) }
            .getOrElse {
                message(it.message ?: "Could not read the encrypted vault.")
                return
            }
        val name = backupFileName()
        message(if (share) "Preparing encrypted backup…" else "Saving encrypted backup…")
        backupExecutor.execute {
            val result = runCatching { writeBackupToDownloads(name, bytes) }
            mainHandler.post {
                result.onSuccess { uri ->
                    if (share) {
                        shareBackup(uri)
                    } else {
                        success("Saved to Downloads/Aura Vault")
                    }
                }.onFailure {
                    message("Could not save the backup: ${it.message ?: "storage error"}")
                }
            }
        }
    }

    private fun backupFileName(): String =
        "aura-vault-backup-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.auravault"

    private fun writeBackupToDownloads(name: String, bytes: ByteArray): Uri {
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, BACKUP_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Aura Vault")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Downloads is unavailable.")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("Could not open the backup file.")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun shareBackup(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = BACKUP_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Aura Vault encrypted backup")
            clipData = ClipData.newRawUri("Aura Vault backup", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(send, "Share encrypted backup")) }
            .onFailure { message("No app can share this file.") }
    }

    private fun confirmBackupImport() {
        confirm(
            "Replace this vault?",
            "Importing a backup replaces the vault on this phone. Export the current vault first if you may need it. You will unlock the backup with the password that protected it.",
            "Choose backup",
            danger = true
        ) { beginBackupImport() }
    }

    private fun beginBackupImport() {
        backupError = null
        backupWorking = false
        backupMode = true
        backupPrefs.edit().putLong("at", System.currentTimeMillis()).apply()
        session?.let { SessionKeeper.save(this, it.dek) }
        mainHandler.removeCallbacks(idleLock)
        render()
        launchBackupPicker()
    }

    private fun launchBackupPicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivityForResult(picker, REQUEST_BACKUP_IMPORT) }
            .onFailure {
                backupMode = false
                backupPrefs.edit().clear().apply()
                render()
                message("No file picker is available on this device.")
            }
    }

    private fun handleBackupImportResult(resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            cancelBackupImport()
            return
        }
        backupWorking = true
        backupError = null
        backupMode = true
        render()
        backupExecutor.execute {
            val result = runCatching { readBackupFile(uri) }
            mainHandler.post {
                if (!backupMode) return@post
                backupWorking = false
                result.onSuccess { bytes ->
                    // Drop the old resume token before the vault file changes:
                    // if the process dies right after the atomic write, it must
                    // not restore a session that belongs to the previous vault.
                    SessionKeeper.clear(this)
                    runCatching { VaultStore.importBackup(this, bytes) }
                        .onSuccess { completeBackupImport() }
                        .onFailure {
                            backupError = it.message ?: "Could not import this backup."
                            render()
                        }
                }.onFailure {
                    backupError = it.message ?: "This file is not a valid Aura backup."
                    render()
                }
            }
        }
    }

    private fun readBackupFile(uri: Uri): ByteArray {
        val resolver = contentResolver
        val reported = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (reported > MAX_BACKUP_BYTES) throw IllegalArgumentException("The backup file is too large.")
        val input = resolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Could not open that file.")
        return input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BACKUP_BYTES) throw IllegalArgumentException("The backup file is too large.")
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    /** Swap the local vault for the backup, then require its original password. */
    private fun completeBackupImport() {
        mainHandler.removeCallbacks(idleLock)
        clearVaultClipboardNow()
        activeDialog?.dismiss()
        activeDialog = null
        SessionKeeper.clear(this)
        VaultStore.clearBiometric(this)
        clearPendingImport()
        importGeneration++
        importCleanupFile?.delete()
        importCleanupFile = null
        pendingReturnToVault = false
        importMode = null
        session?.dek?.fill(0)
        session = null
        recoveryToShow = null
        recoveryUnlockMode = false
        recoveryContinuation = null
        searchQuery = ""
        expandedCards.clear()
        activeSection = "Overview"
        VaultRuntime.section = "Overview"
        backupMode = false
        backupWorking = false
        backupError = null
        backupRestoredNotice = true
        backupPrefs.edit().clear().apply()
        render()
    }

    private fun cancelBackupImport() {
        backupMode = false
        backupWorking = false
        backupError = null
        backupPrefs.edit().clear().apply()
        SessionKeeper.clear(this)
        render()
        rearmIdle()
    }

    private fun showBackupTransfer() {
        val panel = FrameLayout(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(24), dp(6), dp(24))
            minimumHeight = dp(520)
        }
        column.addView(VaultUi.brassRule(this, 36), LinearLayout.LayoutParams(dp(36), dp(1)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(20)
        })
        column.addView(
            VaultUi.tintedBadge(this, R.drawable.ic_upload, VaultTheme.accentAmber, 64),
            LinearLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )
        column.addView(VaultUi.text(
            this, "ENCRYPTED TRANSFER", 9.5f, VaultTheme.textMuted, 600, letterSpacing = 0.14f
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(18), 0, 0)
        })
        column.addView(VaultUi.text(
            this, "Restore your vault", 23f, VaultTheme.textPrimary, 600, heading = true
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(5), 0, 0)
        })
        column.addView(VaultUi.text(
            this,
            "Choose the .auravault file you exported from your old phone. It is already encrypted; you will unlock it with the password that protected it.",
            12.5f, VaultTheme.textMuted, 430, gravity = Gravity.CENTER, lineSpacing = 1.35f
        ).apply {
            setPadding(0, dp(9), 0, dp(22))
        })

        if (backupWorking) {
            column.addView(VaultUi.text(
                this, "Checking the encrypted backup…", 11.5f, VaultTheme.textSecondary, 450, gravity = Gravity.CENTER
            ).apply { setPadding(0, 0, 0, dp(12)) })
            val progress = android.widget.ProgressBar(this).apply {
                indeterminateTintList = ColorStateList.valueOf(VaultTheme.accent)
            }
            column.addView(progress, LinearLayout.LayoutParams(dp(30), dp(30)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        } else {
            backupError?.let { error ->
                val errorPanel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = VaultTheme.glassShape(this@MainActivity, 12, borderColor = VaultTheme.warning)
                    setPadding(dp(13), dp(11), dp(13), dp(11))
                }
                errorPanel.addView(VaultUi.text(this, "That file could not be used", 11.5f, VaultTheme.warning, 600))
                errorPanel.addView(VaultUi.text(this, error, 10.5f, VaultTheme.textMuted, 430, lineSpacing = 1.3f).apply {
                    setPadding(0, dp(3), 0, 0)
                })
                column.addView(errorPanel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            }
            val choose = VaultUi.primaryButton(
                this,
                if (backupError == null) "Choose backup file" else "Choose another file",
                R.drawable.ic_upload,
                52
            ) { launchBackupPicker() }
            column.addView(choose, LinearLayout.LayoutParams(-1, dp(52)))
            val cancel = VaultUi.secondaryButton(this, "Cancel", R.drawable.ic_close, 52) {
                cancelBackupImport()
            }
            column.addView(cancel, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
        }

        panel.addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.removeAllViews()
        root.addView(panel, LinearLayout.LayoutParams(-1, -1))
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
        kicker.addView(VaultUi.text(this, "SECURITY PICTURE", 9.5f, VaultTheme.textMuted, 600, letterSpacing = 0.14f))
        copy.addView(kicker)
        copy.addView(VaultUi.text(this, "A strong foundation", 22f, VaultTheme.textPrimary, 600, heading = true).apply {
            setPadding(0, dp(10), 0, 0)
        })
        copy.addView(VaultUi.text(this, "with room to tighten.", 22f, VaultTheme.accent, 600, heading = true, lineSpacing = 1.06f).apply {
            setPadding(0, 0, 0, dp(9))
        })
        copy.addView(VaultUi.text(this, "Your vault is encrypted, on-device, and looking healthy.", 11.5f, VaultTheme.textMuted, 430, lineSpacing = 1.32f))
        copy.addView(statusPill(audit.statusText, color), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(13) })
        hero.addView(copy)
        hero.addView(VaultUi.scoreRing(this, audit.score, 122, "score"), FrameLayout.LayoutParams(dp(122), dp(122), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = dp(14)
        })
        root.addView(hero, LinearLayout.LayoutParams(-1, dp(236)).apply { bottomMargin = dp(12) })

        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 18, strong = true)
        }
        val metricsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(9))
        }
        metricsHeader.addView(
            VaultUi.text(this, "Vault health", 16.5f, VaultTheme.textPrimary, 600, heading = true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        metricsHeader.addView(VaultUi.text(this, "4 SIGNALS", 9f, VaultTheme.textFaint, 600, letterSpacing = 0.14f))
        metrics.addView(metricsHeader, LinearLayout.LayoutParams(-1, -2))
        metrics.addView(VaultUi.divider(this), LinearLayout.LayoutParams(-1, dp(1)).apply {
            setMargins(dp(15), 0, dp(15), 0)
        })

        fun metricRow(first: View, second: View, dividerAbove: Boolean) {
            if (dividerAbove) {
                metrics.addView(VaultUi.divider(this), LinearLayout.LayoutParams(-1, dp(1)).apply {
                    setMargins(dp(15), 0, dp(15), 0)
                })
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(first, LinearLayout.LayoutParams(0, dp(112), 1f))
            row.addView(View(this).apply { setBackgroundColor(VaultTheme.borderFaint) }, LinearLayout.LayoutParams(dp(1), -1).apply {
                setMargins(0, dp(14), 0, dp(14))
            })
            row.addView(second, LinearLayout.LayoutParams(0, dp(112), 1f))
            metrics.addView(row, LinearLayout.LayoutParams(-1, dp(112)))
        }
        val biometricOn = VaultStore.biometricPayload(this) != null
        val weakCell = VaultUi.statTile(this, R.drawable.ic_key, if (audit.weakPasswordsCount == 0) VaultTheme.accentMint else VaultTheme.warning, "Weak passwords", audit.weakPasswordsCount.toString(), if (audit.weakPasswordsCount == 0) "Everything is strong" else "Review these soon") {}
        val reusedCell = VaultUi.statTile(this, R.drawable.ic_copy, if (audit.reusedPasswordsCount == 0) VaultTheme.accentBlue else VaultTheme.warning, "Reused passwords", audit.reusedPasswordsCount.toString(), if (audit.reusedPasswordsCount == 0) "Every password is unique" else "Use unique credentials") {}
        val expiringCell = VaultUi.statTile(this, R.drawable.ic_card, if (audit.expiringCardsCount == 0) VaultTheme.accent else VaultTheme.warning, "Expiring cards", audit.expiringCardsCount.toString(), if (audit.expiringCardsCount == 0) "No cards need attention" else "Check your cards") {}
        val biometricCell = VaultUi.statTile(this, R.drawable.ic_fingerprint, VaultTheme.accentRose, "Biometric unlock", if (biometricOn) "ON" else "OFF", if (biometricOn) "Last used today" else "Master password only") { openSettings() }
        metricRow(weakCell, reusedCell, dividerAbove = false)
        metricRow(expiringCell, biometricCell, dividerAbove = true)
        root.addView(metrics, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val recommendations = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = VaultTheme.glassShape(this@MainActivity, 18, strong = true)
            setPadding(dp(15), dp(14), dp(15), dp(14))
        }
        recommendations.addView(VaultUi.text(this, "Recommendations", 16.5f, VaultTheme.textPrimary, 600, heading = true))
        recommendations.addView(VaultUi.text(this, "Fix these when you have a moment.", 11f, VaultTheme.textMuted, 430).apply {
            setPadding(0, dp(3), 0, dp(10))
        })
        if (audit.recommendations.isEmpty()) {
            val clear = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(3))
            }
            clear.addView(VaultUi.tintedBadge(this, R.drawable.ic_check, VaultTheme.accentMint, 32))
            clear.addView(VaultUi.text(this, "Nothing needs attention right now.", 11.5f, VaultTheme.textSecondary, 450).apply {
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
                line.addView(VaultUi.text(this, rec.replaceFirstChar { it.uppercase() }, 11.5f, VaultTheme.textSecondary, 450).apply {
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
        protection.addView(VaultUi.text(this, "Local protection", 16.5f, VaultTheme.textPrimary, 600, heading = true).apply {
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
            line.addView(VaultUi.text(this, label, 11.5f, VaultTheme.textSecondary, 450))
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
            val strength = VaultUi.text(this, "Strength  ·  waiting", 10.5f, VaultTheme.textFaint, 500)
            strength.setPadding(dp(2), 0, 0, dp(9))
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
            val generate = VaultUi.secondaryButton(this, "Generate strong password", R.drawable.ic_sparkles, 46) {
                val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#\$%&*_-+=?"
                val random = java.security.SecureRandom()
                secret?.setText((1..20).map { alphabet[random.nextInt(alphabet.length)] }.joinToString(""))
                secret?.setSelection(secret.text.length)
            }
            formContent.addView(generate, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(12) })
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
            val create = VaultUi.secondaryButton(this, "Add fields", R.drawable.ic_plus, 46) {
                val names = keysInput.text.toString().split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()
                if (names.isEmpty()) {
                    message("Enter at least one field name.")
                } else {
                    names.forEach(::addCustomField)
                }
            }
            formContent.addView(create, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(12) })
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
                setColor(VaultTheme.surfaceElevated)
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
            bottomMargin = dp(13)
        })
        panel.addView(VaultUi.brassRule(this, 32), LinearLayout.LayoutParams(dp(32), dp(1)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(13)
        })

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(8), 0)
        }
        headerCopy.addView(VaultUi.text(this, eyebrow.uppercase(), 10f, VaultTheme.accent, 600, letterSpacing = 0.16f))
        headerCopy.addView(VaultUi.text(this, title, 22f, VaultTheme.textPrimary, 600, heading = true).apply {
            setPadding(0, dp(5), 0, 0)
        })
        header.addView(headerCopy, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(VaultUi.iconButton(this, R.drawable.ic_close, "Close", 44) { dialog.dismiss() })
        panel.addView(header, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14) })

        panel.addView(VaultUi.divider(this), LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12) })
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        val cancel = VaultUi.secondaryButton(this, "Cancel", null, 50) { dialog.dismiss() }
        footer.addView(cancel, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(8) })
        val submit = VaultUi.primaryButton(this, submitLabel, null, 50) {
            if (onSubmit()) dialog.dismiss()
        }
        footer.addView(submit, LinearLayout.LayoutParams(0, dp(50), 1f))
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
        val height = min((resources.displayMetrics.heightPixels * 0.9f).toInt(), dp(780))
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

    private fun confirm(title: String, message: String, positive: String, danger: Boolean = false, onConfirm: () -> Unit) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(VaultTheme.surfaceElevated)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), VaultTheme.borderStrong)
            }
            setPadding(dp(22), dp(22), dp(22), dp(18))
        }
        panel.addView(VaultUi.brassRule(this, 36), LinearLayout.LayoutParams(dp(36), dp(1)).apply {
            bottomMargin = dp(14)
        })
        panel.addView(VaultUi.text(this, "PLEASE CONFIRM", 9.5f, VaultTheme.textMuted, 600, letterSpacing = 0.14f))
        panel.addView(VaultUi.text(this, title, 21f, VaultTheme.textPrimary, 600, heading = true).apply {
            setPadding(0, dp(5), 0, 0)
        })
        panel.addView(VaultUi.text(this, message, 13f, VaultTheme.textMuted, 430, lineSpacing = 1.35f).apply {
            setPadding(0, dp(9), 0, dp(20))
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(VaultUi.secondaryButton(this, "Cancel", null, 50) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(8) })
        if (danger) {
            val button = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.argb(22, 206, 126, 126))
                    setStroke(dp(1), Color.argb(120, 206, 126, 126))
                }
                isClickable = true
                isFocusable = true
                contentDescription = positive
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    dialog.dismiss()
                    onConfirm()
                }
                addView(
                    VaultUi.icon(this@MainActivity, R.drawable.ic_trash, VaultTheme.danger, 18),
                    LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) }
                )
                addView(VaultUi.text(this@MainActivity, positive, 14.5f, VaultTheme.danger, 600))
            }
            actions.addView(button, LinearLayout.LayoutParams(0, dp(50), 1f))
        } else {
            actions.addView(VaultUi.primaryButton(this, positive, null, 50) {
                dialog.dismiss()
                onConfirm()
            }, LinearLayout.LayoutParams(0, dp(50), 1f))
        }
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2))

        dialog.setContentView(panel, ViewGroup.LayoutParams(-1, -2))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.72f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(android.R.style.Animation_Dialog)
            decorView.setPadding(dp(20), 0, dp(20), 0)
        }
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        activeDialog = dialog
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun editEntry(entry: VaultStore.Entry) {
        addEntry(entry.kind, editing = entry)
    }

    private fun deleteEntry(entry: VaultStore.Entry, entries: MutableList<VaultStore.Entry>) {
        confirm("Delete ${entry.title}?", "This item will be permanently removed from your vault. This cannot be undone.", "Delete", danger = true) {
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
                setColor(VaultTheme.surfaceElevated)
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), VaultTheme.borderStrong)
            }
            setPadding(dp(22), dp(22), dp(22), dp(18))
        }
        panel.addView(VaultUi.brassRule(this, 36), LinearLayout.LayoutParams(dp(36), dp(1)).apply {
            bottomMargin = dp(16)
        })
        panel.addView(
            VaultUi.tintedBadge(this, R.drawable.ic_fingerprint, VaultTheme.accent, 48),
            LinearLayout.LayoutParams(dp(48), dp(48))
        )
        panel.addView(VaultUi.text(this, "BIOMETRIC UNLOCK", 9.5f, VaultTheme.textMuted, 600, letterSpacing = 0.14f).apply {
            setPadding(0, dp(14), 0, 0)
        })
        panel.addView(VaultUi.text(this, "Add fingerprint unlock?", 22f, VaultTheme.textPrimary, 600, heading = true).apply {
            setPadding(0, dp(5), 0, 0)
        })
        panel.addView(VaultUi.text(
            this,
            "Open Aura with the fingerprint enrolled on this device. Your master password always remains available.",
            13f, VaultTheme.textMuted, 430, lineSpacing = 1.35f
        ).apply { setPadding(0, dp(9), 0, dp(22)) })

        val enable = VaultUi.primaryButton(this, "Enable fingerprint", R.drawable.ic_fingerprint, 52) {
            dialog.dismiss()
            enableBiometric()
        }
        panel.addView(enable, LinearLayout.LayoutParams(-1, dp(52)))
        val notNow = VaultUi.secondaryButton(this, "Not now", null, 52) { dialog.dismiss() }
        panel.addView(notNow, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })

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
        if (requestCode == REQUEST_BACKUP_IMPORT) {
            handleBackupImportResult(resultCode, data)
            return
        }
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
        root.addView(panel, LinearLayout.LayoutParams(-1, -1))
    }

    private fun showPickerWaiting(panel: FrameLayout, resuming: Boolean = false) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(26), dp(8), dp(26))
            minimumHeight = dp(520)
        }
        column.addView(VaultUi.brassRule(this, 36), LinearLayout.LayoutParams(dp(36), dp(1)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(20)
        })
        column.addView(
            VaultUi.tintedBadge(this, R.drawable.ic_image, VaultTheme.accent, 64),
            LinearLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )
        column.addView(VaultUi.text(
            this,
            if (resuming) "FINISHING IMPORT" else "CARD IMPORT",
            9.5f, VaultTheme.textMuted, 600, letterSpacing = 0.14f
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(18), 0, 0)
        })
        column.addView(VaultUi.text(
            this,
            if (resuming) "Finishing your import" else "Import card image",
            23f, VaultTheme.textPrimary, 600, heading = true
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(5), 0, 0)
        })
        column.addView(VaultUi.text(
            this,
            if (resuming) {
                "Reading the card photo you picked. This happens on this device — nothing is uploaded."
            } else {
                "Choose a photo from Google Photos or your phone's gallery. The card is read on this device — nothing is uploaded."
            },
            12.5f, VaultTheme.textMuted, 430, gravity = Gravity.CENTER, lineSpacing = 1.35f
        ).apply {
            setPadding(0, dp(9), 0, dp(22))
        })
        if (resuming) {
            val progress = android.widget.ProgressBar(this).apply {
                indeterminateTintList = ColorStateList.valueOf(VaultTheme.accent)
            }
            column.addView(progress, LinearLayout.LayoutParams(dp(30), dp(30)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        } else {
            // If the OS killed the process and dropped the picker, this screen
            // is what the user returns to. Give them a way to reopen the picker
            // instead of leaving only a Cancel button.
            column.addView(VaultUi.primaryButton(this, "Choose photo", R.drawable.ic_image, 52) {
                pickCardImage()
            }, LinearLayout.LayoutParams(-1, dp(52)))
            column.addView(VaultUi.secondaryButton(this, "Cancel", R.drawable.ic_close, 52) {
                cancelImport()
            }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
        }
        panel.addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
    }

    private fun showCardCamera(panel: FrameLayout) {
        val captureFile = newImportFile()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
        }
        column.addView(VaultUi.brassRule(this, 36), LinearLayout.LayoutParams(dp(36), dp(1)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(12)
        })
        column.addView(VaultUi.text(this, "CARD IMPORT", 9.5f, VaultTheme.textMuted, 600, letterSpacing = 0.14f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        column.addView(VaultUi.text(this, "Scan a card", 23f, VaultTheme.textPrimary, 600, heading = true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        })
        column.addView(VaultUi.text(this, "Hold the card flat, steady and well lit. The number is read on this device — the photo never leaves the phone.", 12f, VaultTheme.textMuted, 430, gravity = Gravity.CENTER, lineSpacing = 1.32f).apply {
            setPadding(0, dp(6), 0, dp(12))
        })

        val previewHost = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.rgb(14, 15, 18))
                setStroke(dp(1), VaultTheme.borderStrong)
            }
            clipToOutline = true
        }
        val lens = TextureView(this)
        previewHost.addView(lens, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        previewHost.minimumHeight = dp(300)
        column.addView(previewHost, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        val back = VaultUi.secondaryButton(this, "Cancel", R.drawable.ic_close, 52) {
            cameraEngine?.close()
            cameraEngine = null
            cancelImport()
        }
        controls.addView(back, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(10) })
        val shutter = VaultUi.primaryButton(this, "Capture", R.drawable.ic_camera, 52) { cameraEngine?.capture() }
        controls.addView(shutter, LinearLayout.LayoutParams(0, dp(52), 1f))
        column.addView(controls, LinearLayout.LayoutParams(-1, -2))

        panel.addView(column, FrameLayout.LayoutParams(-1, -1))

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
            setBackgroundDrawable(VaultTheme.cardShape(this@MainActivity, 24, VaultTheme.surfaceElevated, VaultTheme.borderStrong))
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
            setTextColor(VaultTheme.buttonInk)
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
