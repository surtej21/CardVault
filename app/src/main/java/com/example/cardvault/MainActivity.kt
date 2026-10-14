package com.example.cardvault

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.WindowInsets
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.text.InputType
import java.util.UUID
import java.io.File
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MainActivity : Activity() {
    companion object { private const val REQUEST_CARD_IMAGE = 7412; private const val REQUEST_CARD_CAMERA = 7413 }
    private data class CardPrefill(val number: String = "", val expiry: String = "", val cvv: String = "")
    private data class RetainedActivityState(val session: VaultStore.Session?, val importingCard: Boolean)
    private lateinit var root: LinearLayout
    private lateinit var contentScroll: ScrollView
    private var session: VaultStore.Session? = null
    private var recoveryToShow: String? = null
    private var recoveryContinuation: (() -> Unit)? = null
    private var copiedText: String? = null
    private var cameraCaptureUri: Uri? = null
    private var cardImportInProgress = false
    private val cameraCaptureFile by lazy { File(cacheDir, "card-capture.jpg") }
    private var activeSection = "Overview"
    private var searchQuery = ""
    private val expandedCards = mutableSetOf<String>()
    private val ink = Color.rgb(239, 242, 250)
    private val muted = Color.rgb(145, 154, 176)
    private val surface = Color.rgb(22, 25, 35)
    private val accent = Color.rgb(117, 148, 255)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleLock = Runnable { if (session != null && recoveryToShow == null && !cardImportInProgress) lockNow() }
    private val clearClipboard = Runnable {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (clipText == copiedText) clipboard.clearPrimaryClip()
        copiedText = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (lastNonConfigurationInstance as? RetainedActivityState)?.let {
            session = it.session
            cardImportInProgress = it.importingCard
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        render()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(idleLock)
        mainHandler.removeCallbacks(clearClipboard)
        if (!isChangingConfigurations) session?.dek?.fill(0)
        super.onDestroy()
    }

    override fun onRetainNonConfigurationInstance(): Any? = RetainedActivityState(session, cardImportInProgress)

    override fun onResume() {
        super.onResume()
        if (session != null && !cardImportInProgress) {
            mainHandler.removeCallbacks(idleLock)
            mainHandler.postDelayed(idleLock, 60_000)
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (session != null && recoveryToShow == null && !cardImportInProgress) {
            mainHandler.removeCallbacks(idleLock)
            mainHandler.postDelayed(idleLock, 60_000)
        }
    }

    private fun render() {
        val centeredAuth = session == null && recoveryToShow == null && !recoveryUnlockMode
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (centeredAuth) Gravity.CENTER else Gravity.TOP
            setPadding(dp(24), dp(28), dp(24), dp(22))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(9, 13, 24), Color.rgb(10, 10, 17), Color.rgb(19, 13, 28)))
        }
        val scroll = object : ScrollView(this) {
            private var startX = 0f
            private var startY = 0f
            private var horizontalPageSwipe = false
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startX = event.x; startY = event.y; horizontalPageSwipe = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - startX
                        val dy = event.y - startY
                        if (kotlin.math.abs(dx) > dp(48) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.25f) {
                            horizontalPageSwipe = true
                            return true
                        }
                    }
                }
                return super.onInterceptTouchEvent(event)
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_UP && horizontalPageSwipe) {
                    val sections = listOf("Overview", "Cards", "Bank logins", "Passwords", "Custom")
                    val index = sections.indexOf(activeSection).coerceAtLeast(0)
                    val dx = event.x - startX
                    val next = (index + if (dx < 0) 1 else -1).coerceIn(0, sections.lastIndex)
                    horizontalPageSwipe = false
                    if (next != index) { activeSection = sections[next]; render() }
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
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(dp(24), dp(24) + bars.top, dp(24), dp(18) + bars.bottom)
            insets
        }
        setContentView(scroll)
        root.requestApplyInsets()
        when {
            recoveryToShow != null -> showRecovery()
            session != null -> showVault()
            !VaultStore.exists(this) -> showSetup()
            recoveryUnlockMode -> renderRecoveryUnlock()
            else -> showUnlock()
        }
    }

    private fun title(text: String) {
        root.addView(TextView(this).apply {
            this.text = text; textSize = if (session == null) 31f else 27f
            typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
            setTextColor(ink); setPadding(0, 8, 0, 16)
            if (session == null) gravity = Gravity.CENTER
        })
    }

    private fun hint(text: String) {
        root.addView(TextView(this).apply { this.text = text; textSize = 14f; setTextColor(muted); setPadding(0, 0, 0, 16); if (session == null) gravity = Gravity.CENTER })
    }

    private fun field(label: String, secret: Boolean = false): EditText {
        val e = EditText(this).apply { hint = label; textSize = 16f; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO; setTextColor(ink); setHintTextColor(muted); background = rounded(surface, 18) }
        if (secret) e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        e.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) view.postDelayed({ contentScroll.smoothScrollTo(0, root.top + view.top) }, 220)
        }
        root.addView(e, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(10) })
        return e
    }

    private fun button(text: String, color: Int = surface, action: () -> Unit) {
        root.addView(Button(this).apply { this.text = text; setTextColor(ink); background = rounded(color, 16); isAllCaps = false; textSize = 14f; setOnClickListener { action() } }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8; height = dp(52) })
    }
    private fun primaryButton(text: String, action: () -> Unit) {
        root.addView(Button(this).apply {
            this.text = text; setTextColor(Color.WHITE); background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(105, 128, 232), Color.rgb(139, 99, 214))).apply { cornerRadius = dp(18).toFloat() }
            isAllCaps = false; textSize = 15f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(10) })
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), Color.argb(30, 255, 255, 255)) }
    private fun animateIn(view: View) {
        view.alpha = 0f; view.translationY = dp(9).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(280).start()
    }
    private fun label(text: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun sectionNav() {
        val rail = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Overview", "Cards", "Bank logins", "Passwords", "Custom").forEach { name ->
            val selected = activeSection == name
            row.addView(TextView(this).apply {
                text = name; textSize = 13f; setTextColor(if (selected) Color.WHITE else muted); gravity = Gravity.CENTER
                background = rounded(if (selected) Color.rgb(48, 66, 125) else surface, 18); setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener { activeSection = name; render() }
            }, LinearLayout.LayoutParams(-2, dp(42)).apply { rightMargin = dp(8) })
        }
        rail.addView(row); root.addView(rail, LinearLayout.LayoutParams(-1, dp(50)))
    }

    private fun showSetup() {
        authMark()
        title("A quieter kind\nof security.")
        hint("Create your private vault. Your data stays encrypted on this device.")
        val password = field("Master password", true)
        val confirm = field("Confirm master password", true)
        primaryButton("Create my vault") {
            if (password.text.length < 12) return@primaryButton message("Use at least 12 characters.")
            if (password.text.toString() != confirm.text.toString()) return@primaryButton message("The passwords do not match.")
            runCatching { VaultStore.create(this, password.text.toString().toCharArray()) }
                .onSuccess { result -> session = result.session; recoveryToShow = result.recoveryKey; render() }
                .onFailure { message(it.message ?: "Could not create the vault.") }
        }
        hint("This prototype stores the vault only in this app's private storage. There is no sign-in or server.")
    }

    private fun showRecovery() {
        title("Save your recovery key")
        hint("Write this key down or store it in another secure place. Anyone with it can open your vault. It is shown only now.")
        root.addView(TextView(this).apply {
            text = recoveryToShow
            textSize = 17f
            setTextColor(Color.rgb(20, 24, 36))
            setTextIsSelectable(true)
            setPadding(14, 18, 14, 18)
            background = rounded(Color.rgb(229, 233, 244), 18)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16 })
        val saved = CheckBox(this).apply { text = "I saved this recovery key somewhere safe" }
        root.addView(saved)
        button("Continue") {
            if (!saved.isChecked) return@button message("Save the recovery key before continuing.")
            recoveryToShow = null
            recoveryContinuation?.invoke()
            recoveryContinuation = null
            if (session != null) mainHandler.postDelayed(idleLock, 60_000)
            render()
        }
    }

    private fun showUnlock() {
        authMark()
        title("Welcome back.")
        hint("Your private space is waiting.")
        val biometric = VaultStore.biometricPayload(this)
        if (biometric != null) primaryButton("◎   Unlock with fingerprint") { unlockBiometric(biometric) }
        else hint("Tip: enable fingerprint unlock from inside your vault for quicker access.")
        val password = field("Master password", true)
        button("Unlock with password") {
            runCatching { VaultStore.unlockWithPassword(this, password.text.toString().toCharArray()) }
                .onSuccess { session = it; mainHandler.postDelayed(idleLock, 60_000); render() }
                .onFailure { message("Could not unlock. Check the password or use your recovery key.") }
        }
        button("Forgot master password? Use recovery key") { showRecoveryUnlock() }
    }

    private fun authMark() {
        root.addView(TextView(this).apply {
            text = "✳"; textSize = 34f; gravity = Gravity.CENTER; setTextColor(Color.rgb(168, 178, 255))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(42, 50, 88), Color.rgb(43, 32, 68))).apply { cornerRadius = dp(25).toFloat() }
            setPadding(dp(15), dp(8), dp(15), dp(8))
        }, LinearLayout.LayoutParams(dp(68), dp(68)).apply { gravity = Gravity.CENTER; bottomMargin = dp(18) })
        root.addView(label("A U R A   /   PRIVATE VAULT", 11f, accent, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun renderRecoveryUnlock() {
        root.removeAllViews()
        title("Recover your vault")
        hint("Enter your 64-character recovery key and choose a new master password. A new recovery key will be created.")
        val recovery = field("Recovery key")
        val password = field("New master password (12+ characters)", true)
        val confirm = field("Confirm new master password", true)
        button("Recover and reset password") {
            if (password.text.length < 12 || password.text.toString() != confirm.text.toString()) return@button message("Enter matching passwords with at least 12 characters.")
            runCatching { VaultStore.resetPassword(this, recovery.text.toString(), password.text.toString().toCharArray()) }
                .onSuccess { (newKey, newSession) -> session = newSession; recoveryToShow = newKey; render() }
                .onFailure { message("Recovery failed. Check the recovery key and try again.") }
        }
        button("Back") { recoveryUnlockMode = false; render() }
    }

    private fun showVault() {
        val brandBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        brandBar.addView(label("A U R A   /   P E R S O N A L   V A U L T", 11f, accent, true), LinearLayout.LayoutParams(0, dp(44), 1f))
        brandBar.addView(TextView(this).apply {
            text = "⋮"; textSize = 27f; gravity = Gravity.CENTER; setTextColor(ink); background = rounded(surface, 16)
            contentDescription = "Settings"; setOnClickListener { showSettingsMenu() }
        }, LinearLayout.LayoutParams(dp(48), dp(44)))
        root.addView(brandBar)
        title(if (activeSection == "Overview") "Your space, secured." else activeSection)
        root.addView(label("Private by design  ·  Stored on this device", 13f, muted))
        val entries = session!!.entries
        val countPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(Color.rgb(18, 22, 34), 24); setPadding(dp(20), dp(18), dp(20), dp(18)) }
        countPanel.addView(label("YOUR VAULT", 11f, muted, true))
        countPanel.addView(label("${entries.size} saved ${if (entries.size == 1) "item" else "items"}", 24f, ink, true).apply { setPadding(0, dp(6), 0, dp(3)) })
        countPanel.addView(label("Encrypted locally · Auto-lock enabled", 12f, muted))
        root.addView(countPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18); bottomMargin = dp(18) })
        sectionNav()
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addRow.addView(Button(this).apply { text = "+ Add"; isAllCaps = false; setTextColor(Color.WHITE); background = rounded(Color.rgb(73, 99, 194), 16); setOnClickListener { chooseItemType() } }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(6) })
        addRow.addView(Button(this).apply { text = "⌕ Search"; isAllCaps = false; setTextColor(ink); background = rounded(surface, 16); setOnClickListener { searchEntries() } }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(6) })
        addRow.addView(Button(this).apply { text = "✣ Quick"; isAllCaps = false; setTextColor(ink); background = rounded(surface, 16); setOnClickListener { showCommandPalette() } }, LinearLayout.LayoutParams(0, dp(50), 1f))
        root.addView(addRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(10) })
        if (activeSection == "Overview") {
            root.addView(label("Your collections", 18f, ink, true).apply { setPadding(0, dp(14), 0, dp(10)) })
            listOf("Cards" to "Payment cards, ready when you need them", "Bank logins" to "Account numbers and net banking access", "Passwords" to "Sign-ins and secure credentials", "Custom" to "Your own private records").forEach { (name, desc) ->
                val tile = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(surface, 20); setPadding(dp(16), dp(14), dp(16), dp(14)); isClickable = true; setOnClickListener { activeSection = name; render() } }
                animateIn(tile)
                tile.addView(label(name, 16f, ink, true)); tile.addView(label(desc, 12f, muted).apply { setPadding(0, dp(4), 0, 0) })
                root.addView(tile, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            }
        }
        val shown = entries.toList().filter { activeSection == "Overview" || it.kind == activeSection.removeSuffix("s").let { s -> if (s == "Bank login") "Bank" else if (s == "Password") "Password" else if (s == "Card") "Card" else "Custom" } }
            .filter { searchQuery.isBlank() || it.title.contains(searchQuery, true) || it.username.contains(searchQuery, true) }
        if (shown.isEmpty() && activeSection != "Overview") hint("Nothing here yet. Add your first ${activeSection.lowercase().removeSuffix("s")} to get started.")
        shown.forEach { entry ->
            val cardExpanded = entry.kind == "Card" && expandedCards.contains(entry.id)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; background = rounded(Color.rgb(19, 23, 34), 22)
                setPadding(dp(18), dp(16), dp(18), dp(14))
                if (entry.kind == "Card") {
                    isClickable = true
                    setOnClickListener { if (cardExpanded) expandedCards.remove(entry.id) else expandedCards.add(entry.id); render() }
                }
            }
            card.addView(label("${kindGlyph(entry.kind)}   ${entry.kind.uppercase()}", 10f, accent, true))
            card.addView(label(entry.title, 19f, ink, true).apply { setPadding(0, dp(8), 0, dp(7)) })
            when (entry.kind) {
                "Password" -> { if (entry.username.isNotBlank()) card.addView(label(entry.username, 13f, muted)); card.addView(label("••••••••••••", 14f, muted).apply { setPadding(0, dp(7), 0, 0) }) }
                "Card" -> {
                    card.addView(label("••••  ${entry.cardNumber.takeLast(4)}     ${entry.expiry}", 13f, muted))
                    card.addView(label(if (cardExpanded) "Tap to hide details" else "Tap to view details", 11f, accent).apply { setPadding(0, dp(8), 0, 0) })
                    if (cardExpanded) {
                        card.addView(label("Card number", 11f, muted).apply { setPadding(0, dp(14), 0, dp(2)) })
                        card.addView(label(entry.cardNumber, 15f, ink, true))
                        if (entry.cardholderName.isNotBlank()) card.addView(label("Cardholder  ${entry.cardholderName}", 13f, muted).apply { setPadding(0, dp(8), 0, 0) })
                        card.addView(label("Expiry  ${entry.expiry.ifBlank { "Not added" }}", 13f, muted).apply { setPadding(0, dp(4), 0, 0) })
                    }
                }
                else -> entry.customFields.entries.take(3).forEach { (key, value) -> card.addView(label("$key   ${if (key.lowercase().contains("password") || key.lowercase().contains("pin")) "••••••••" else value}", 12f, muted).apply { setPadding(0, dp(4), 0, 0) }) }
            }
            root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(4) })
            animateIn(card)
            if (entry.kind == "Password") button("Copy password", Color.rgb(28, 75, 82)) { copySensitive(entry.secret) }
            if (entry.kind == "Card") {
                if (cardExpanded) button("Copy card number", Color.rgb(28, 75, 82)) { copySensitive(entry.cardNumber) }
                if (cardExpanded && entry.expiry.isNotBlank()) button("Copy expiry date", Color.rgb(47, 65, 103)) { copySensitive(entry.expiry) }
                if (cardExpanded && entry.cvv.isNotBlank()) button("Copy CVV · confirm each time", Color.rgb(84, 53, 88)) {
                    AlertDialog.Builder(this).setTitle("Copy CVV?").setMessage("The CVV will be placed on the clipboard for up to 30 seconds.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Copy") { _, _ -> copySensitive(entry.cvv) }.show()
                }
            }
            if (entry.kind == "Bank" || entry.kind == "Custom") entry.customFields.forEach { (key, value) ->
                button("Copy $key", Color.rgb(28, 75, 82)) { if (key.lowercase().contains("cvv") || key.lowercase().contains("mpin") || key.lowercase().contains("password") || key.lowercase().contains("pin")) confirmCopy(key, value) else copySensitive(value) }
            }
            button("Share selected details…") { shareEntry(entry) }
            button("Delete item", Color.rgb(91, 38, 48)) {
                AlertDialog.Builder(this).setTitle("Delete ${entry.title}?").setMessage("This permanently removes it from the vault.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> entries.removeAll { it.id == entry.id }; persistAndRender() }.show()
            }
        }
    }

    private fun showSettingsMenu() {
        val biometricEnabled = VaultStore.biometricPayload(this) != null
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), dp(4)) }
        val dialog = AlertDialog.Builder(this).setTitle("Settings").setView(panel).setNegativeButton("Close", null).create()
        fun option(text: String, color: Int, action: () -> Unit) {
            panel.addView(Button(this).apply {
                this.text = text; isAllCaps = false; textSize = 14f; setTextColor(ink); background = rounded(color, 16)
                setOnClickListener { dialog.dismiss(); action() }
            }, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) })
        }
        option(if (biometricEnabled) "Turn off fingerprint unlock" else "Enable fingerprint unlock", Color.rgb(67, 47, 98)) {
            if (biometricEnabled) AlertDialog.Builder(this).setTitle("Turn off fingerprint unlock?")
                .setMessage("You can still unlock with your master password.")
                .setNegativeButton("Cancel", null).setPositiveButton("Turn off") { _, _ -> VaultStore.clearBiometric(this); render() }.show()
            else enableBiometric()
        }
        option("Replace recovery key", Color.rgb(83, 68, 39)) {
            AlertDialog.Builder(this).setTitle("Replace recovery key?")
                .setMessage("The current recovery key will stop working. Save the new key somewhere safe.")
                .setNegativeButton("Cancel", null).setPositiveButton("Create new key") { _, _ ->
                    runCatching { VaultStore.rotateRecoveryKey(this, session!!.dek) }
                        .onSuccess { recoveryToShow = it; render() }
                        .onFailure { message("Could not create a new recovery key.") }
                }.show()
        }
        option("Lock vault", Color.rgb(45, 57, 86)) { lockNow() }
        dialog.show()
    }

    private fun kindGlyph(kind: String) = when (kind) { "Bank" -> "◈"; "Password" -> "⌑"; "Card" -> "▭"; else -> "✳" }
    private fun chooseItemType() {
        val types = arrayOf("Payment card", "Take card photo", "Choose card image", "Bank login", "Password", "Custom item")
        AlertDialog.Builder(this).setTitle("Add to your vault").setItems(types) { _, which ->
            when (which) {
                0 -> addEntry("Card")
                1 -> captureCardImage()
                2 -> pickCardImage()
                3 -> addEntry("Bank")
                4 -> addEntry("Password")
                else -> addEntry("Custom")
            }
        }.show()
    }

    private fun pickCardImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        beginCardImport()
        runCatching { startActivityForResult(Intent.createChooser(intent, "Choose a card image"), REQUEST_CARD_IMAGE) }
            .onFailure { finishCardImport(); message("Could not open the image picker.") }
    }

    @Deprecated("Use of the document picker result for this simple Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CARD_CAMERA) {
            val uri = cameraCaptureUri
            cameraCaptureUri = null
            if (resultCode == RESULT_OK && uri != null) readCardImage(uri, cameraCaptureFile)
            else { cameraCaptureFile.delete(); finishCardImport(); message("Card photo cancelled.") }
            return
        }
        if (requestCode == REQUEST_CARD_IMAGE) {
            if (resultCode != RESULT_OK) { finishCardImport(); return }
            val uri = data?.data ?: run { finishCardImport(); return message("No image was selected.") }
            readCardImage(uri)
        }
    }

    private fun captureCardImage() {
        cameraCaptureFile.delete()
        val uri = Uri.parse("content://${packageName}.camera/capture/card-capture.jpg")
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newUri(contentResolver, "Card photo", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        beginCardImport()
        cameraCaptureUri = uri
        runCatching { startActivityForResult(intent, REQUEST_CARD_CAMERA) }
            .onFailure { cameraCaptureUri = null; cameraCaptureFile.delete(); finishCardImport(); message("No camera app is available on this device.") }
    }

    private fun beginCardImport() {
        cardImportInProgress = true
        mainHandler.removeCallbacks(idleLock)
    }

    private fun finishCardImport() {
        cardImportInProgress = false
        if (session != null) {
            mainHandler.removeCallbacks(idleLock)
            mainHandler.postDelayed(idleLock, 60_000)
        }
    }

    private fun readCardImage(uri: Uri, cleanupFile: File? = null) {
        message("Reading card text on this device…")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val task = runCatching { recognizer.process(InputImage.fromFilePath(this, uri)) }
            .getOrElse { recognizer.close(); cleanupFile?.delete(); finishCardImport(); message("Could not open that image."); return }
        task.addOnSuccessListener { result ->
            recognizer.close()
            cleanupFile?.delete()
            finishCardImport()
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
            cleanupFile?.delete()
            finishCardImport()
            message("Could not read the image. You can enter the card details manually.")
            addEntry("Card")
        }
    }

    private fun parseCardText(text: String): CardPrefill {
        val numberPattern = Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")
        val number = numberPattern.findAll(text).map { match -> match.value.filter { it.isDigit() } }
            .firstOrNull { it.length in 13..19 && passesLuhn(it) }.orEmpty()
        val expiryPattern = Regex("(?<!\\d)(0?[1-9]|1[0-2])\\s*[/|-]\\s*(\\d{2}|\\d{4})(?!\\d)")
        val expiryMatch = expiryPattern.find(text)
        val expiry = expiryMatch?.let {
            val month = it.groupValues[1].padStart(2, '0')
            val year = it.groupValues[2].takeLast(2)
            "$month/$year"
        }.orEmpty()
        val cvv = Regex("(?i)(?:CVV|CVC|security code)\\D{0,8}(\\d{3,4})").find(text)?.groupValues?.get(1).orEmpty()
        return CardPrefill(number, expiry, cvv)
    }

    private fun passesLuhn(number: String): Boolean {
        val sum = number.reversed().mapIndexed { index, char ->
            var digit = char.digitToInt()
            if (index % 2 == 1) { digit *= 2; if (digit > 9) digit -= 9 }
            digit
        }.sum()
        return sum % 10 == 0
    }
    private fun searchEntries() {
        val input = EditText(this).apply { hint = "Search names, banks, websites"; setText(searchQuery) }
        AlertDialog.Builder(this).setTitle("Search your vault").setView(input).setNegativeButton("Clear") { _, _ -> searchQuery = ""; render() }
            .setPositiveButton("Show results") { _, _ -> searchQuery = input.text.toString().trim(); activeSection = "Overview"; render() }.show()
    }
    private fun showCommandPalette() {
        val actions = arrayOf("Add payment card", "Add bank login", "Add password", "Create custom item", "Search vault", "Lock vault")
        AlertDialog.Builder(this).setTitle("Quick actions").setItems(actions) { _, which ->
            when (which) {
                0 -> addEntry("Card")
                1 -> addEntry("Bank")
                2 -> addEntry("Password")
                3 -> addEntry("Custom")
                4 -> searchEntries()
                else -> lockNow()
            }
        }.show()
    }
    private fun confirmCopy(name: String, value: String) {
        AlertDialog.Builder(this).setTitle("Copy $name?").setMessage("Sensitive values are cleared from the clipboard after 30 seconds.")
            .setNegativeButton("Cancel", null).setPositiveButton("Copy") { _, _ -> copySensitive(value) }.show()
    }

    private fun addEntry(kind: String, prefill: CardPrefill? = null) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 8, 20, 0) }
        if (prefill != null) form.addView(TextView(this).apply {
            text = "Imported suggestions — check every character before saving. The selected image is not stored in your vault."
            textSize = 13f; setTextColor(muted); setPadding(0, 0, 0, dp(12))
        })
        fun edit(h: String, isSecret: Boolean = false): EditText = EditText(this).apply {
            hint = h
            setTextColor(ink); setHintTextColor(muted); background = rounded(Color.rgb(34, 38, 51), 14); setPadding(dp(14), dp(8), dp(14), dp(8))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            if (isSecret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            form.addView(this, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) })
        }
        val title = edit(when(kind) { "Bank" -> "Bank name"; "Card" -> "Card nickname"; "Password" -> "Website or app"; else -> "Item name" })
        val cardholder = if (kind == "Card") edit("Cardholder name") else null
        val user = if (kind == "Password") edit("Username or email") else null
        val secret = if (kind == "Password") edit("Password", true) else null
        if (kind == "Password") Button(this).apply {
            text = "Generate strong password"
            setOnClickListener {
                val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%&*_-+=?"
                val random = java.security.SecureRandom()
                secret?.setText((1..24).map { alphabet[random.nextInt(alphabet.length)] }.joinToString(""))
            }
        }.also { form.addView(it) }
        val number = if (kind == "Card") edit("Card number").apply { setText(prefill?.number.orEmpty()) } else null
        val expiry = if (kind == "Card") edit("Expiry date").apply { setText(prefill?.expiry.orEmpty()) } else null
        val cvv = if (kind == "Card") edit("CVV", true).apply { setText(prefill?.cvv.orEmpty()) } else null
        val custom = linkedMapOf<String, EditText>()
        if (kind == "Bank") {
            listOf("Account number", "IFSC code", "Customer ID", "Username", "Net banking password", "Mobile MPIN").forEach { key -> custom[key] = edit(key, key.contains("password", true) || key.contains("MPIN")) }
        }
        if (kind == "Custom") {
            val keysInput = edit("Your field names, comma separated (e.g. Policy number, Renewal date)")
            form.addView(Button(this).apply {
                text = "Create fields from names"; isAllCaps = false; setOnClickListener {
                    val names = keysInput.text.toString().split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()
                    if (names.isEmpty()) { message("Enter at least one custom field name."); return@setOnClickListener }
                    names.forEach { key -> if (!custom.containsKey(key)) custom[key] = edit(key, key.contains("password", true) || key.contains("pin", true) || key.contains("secret", true)) }
                    it.isEnabled = false
                }
            })
        }
        val notes = edit("Notes (optional)")
        AlertDialog.Builder(this).setTitle(when(kind) { "Bank" -> "Add bank login"; "Card" -> "Add payment card"; "Password" -> "Add password"; else -> "Create custom item" }).setView(form).setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (title.text.isBlank() || (kind == "Password" && secret?.text.isNullOrBlank()) || (kind == "Card" && number?.text.isNullOrBlank()) || (kind == "Custom" && custom.isEmpty())) {
                            message("Enter a name, required details, and any custom fields."); return@setOnClickListener
                        }
                        val e = VaultStore.Entry(UUID.randomUUID().toString(), kind, title.text.toString(), cardholder?.text.toString(), user?.text.toString(),
                            secret?.text.toString(), number?.text.toString(), expiry?.text.toString(), cvv?.text.toString(), notes.text.toString(),
                            custom.mapValues { it.value.text.toString() }.filterValues { it.isNotBlank() })
                        session!!.entries.add(e); persistAndRender(); dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun shareEntry(entry: VaultStore.Entry) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0) }
        val fields = linkedMapOf<String, String>()
        fields["Name"] = entry.title
        if (entry.kind == "Password") {
            if (entry.username.isNotBlank()) fields["Username"] = entry.username
            fields["Password"] = entry.secret
        } else {
            if (entry.kind == "Card") {
                if (entry.cardholderName.isNotBlank()) fields["Cardholder"] = entry.cardholderName
                fields["Card number"] = entry.cardNumber
                if (entry.expiry.isNotBlank()) fields["Expiry"] = entry.expiry
                if (entry.cvv.isNotBlank()) fields["CVV"] = entry.cvv
            }
            entry.customFields.forEach { (key, value) -> if (value.isNotBlank()) fields[key] = value }
        }
        if (entry.notes.isNotBlank()) fields["Notes"] = entry.notes
        val selected = fields.mapValues { CheckBox(this).apply { text = it.key; isChecked = it.key != "CVV"; box.addView(this) } }
        AlertDialog.Builder(this).setTitle("Choose fields to share").setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("Continue", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val chosen = selected.filterValues { it.isChecked }.keys
                        if (chosen.isEmpty()) return@setOnClickListener message("Select at least one field.")
                        val cvvIncluded = chosen.contains("CVV")
                        val confirmText = if (cvvIncluded) "This includes the CVV. The selected values will leave the vault through Android's share sheet." else "The selected values will leave the vault through Android's share sheet."
                        AlertDialog.Builder(this).setTitle("Confirm sharing") .setMessage(confirmText)
                            .setNegativeButton("Cancel", null).setPositiveButton("Share") { _, _ ->
                                val body = chosen.joinToString("\n") { "$it: ${fields[it]}" }
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body), "Share vault details"))
                            }.show()
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun copySensitive(value: String) {
        if (value.isBlank()) return message("There is nothing to copy.")
        val clip = ClipData.newPlainText("Sensitive vault value", value)
        clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(clip)
        copiedText = value
        mainHandler.removeCallbacks(clearClipboard)
        mainHandler.postDelayed(clearClipboard, 30_000)
        message("Copied. Clipboard clears in 30 seconds.")
    }

    private fun persistAndRender() {
        runCatching { VaultStore.save(this, session!!.entries, session!!.dek) }
            .onSuccess { render() }
            .onFailure { message("Could not save the encrypted vault: ${it.message}") }
    }

    private fun enableBiometric() {
        runCatching { VaultStore.newBiometricEncryptCipher() }.onFailure {
            message("Fingerprint unlock is unavailable on this device."); return
        }.onSuccess { cipher ->
            val prompt = BiometricPrompt.Builder(this).setTitle("Enable fingerprint unlock")
                .setSubtitle("Confirm your fingerprint to protect vault access")
                .setNegativeButton("Cancel", mainExecutor) { _, _ -> VaultStore.clearBiometric(this) }.build()
            prompt.authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        runCatching {
                            val authorizedCipher = result.cryptoObject!!.cipher!!
                            VaultStore.saveBiometricPayload(this@MainActivity, authorizedCipher.iv, authorizedCipher.doFinal(session!!.dek))
                        }.onSuccess { message("Fingerprint unlock enabled."); render() }
                            .onFailure { VaultStore.clearBiometric(this@MainActivity); message("Could not enable fingerprint unlock.") }
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        VaultStore.clearBiometric(this@MainActivity); message("Fingerprint setup cancelled or unavailable."); render()
                    }
                })
        }
    }

    private fun unlockBiometric(payload: VaultStore.BiometricPayload) {
        runCatching { VaultStore.biometricDecryptCipher(payload) }.onFailure {
            VaultStore.clearBiometric(this); message("Fingerprint key is unavailable. Use your master password or recovery key."); render(); return
        }
        val cipher = VaultStore.biometricDecryptCipher(payload)
        val prompt = BiometricPrompt.Builder(this).setTitle("Unlock Aura Vault").setSubtitle("Use your enrolled fingerprint")
            .setNegativeButton("Cancel", mainExecutor) { _, _ -> }.build()
        prompt.authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    runCatching { VaultStore.decryptBiometric(result.cryptoObject!!.cipher!!, payload) }
                        .onSuccess { dek -> session = VaultStore.Session(dek, VaultStore.entriesWithDek(this@MainActivity, dek)); recoveryUnlockMode = false; mainHandler.postDelayed(idleLock, 60_000); render() }
                        .onFailure { message("Fingerprint unlock failed. Use your master password or recovery key.") }
                }
            })
    }

    private fun showRecoveryUnlock() { recoveryUnlockMode = true; render() }
    private var recoveryUnlockMode = false

    private fun lockNow() { mainHandler.removeCallbacks(idleLock); session?.dek?.fill(0); session = null; recoveryUnlockMode = false; render() }
    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
