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
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.View
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
    companion object {
        private const val REQUEST_CARD_IMAGE = 7412
        private const val REQUEST_CARD_CAMERA = 7413
        private const val CLIP_TOKEN_EXTRA = "com.example.cardvault.extra.CLIP_TOKEN"
        private const val CLIP_TOKEN_PREFIX = "aura-vault:"
    }
    private data class CardPrefill(val number: String = "", val expiry: String = "", val cvv: String = "")
    private data class RetainedActivityState(val session: VaultStore.Session?, val importingCard: Boolean)
    private lateinit var root: LinearLayout
    private lateinit var contentScroll: ScrollView
    private var session: VaultStore.Session? = null
    private var recoveryToShow: String? = null
    private var recoveryContinuation: (() -> Unit)? = null
    private var pendingClipboardClear: Runnable? = null
    private var searchMode = false
    private var searchInput: EditText? = null
    private var searchFeedback: Runnable? = null
    private var cameraCaptureUri: Uri? = null
    private var cardImportInProgress = false
    private val cameraCaptureFile by lazy { File(cacheDir, "card-capture.jpg") }
    private var activeSection = "Overview"
    private var searchQuery = ""
    private val expandedCards = mutableSetOf<String>()
    private val selectedCopyFields = mutableMapOf<String, MutableSet<String>>()
    private val ink = Color.rgb(239, 242, 250)
    private val muted = Color.rgb(145, 154, 176)
    private val surface = Color.rgb(22, 25, 35)
    private val accent = Color.rgb(117, 148, 255)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleLock = Runnable { if (session != null && recoveryToShow == null && !cardImportInProgress) lockNow() }
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
        // Do not cancel the clipboard timeout here. Android may destroy this Activity
        // during rotation or while another app is opened to paste the copied value.
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
            setPadding(dp(24), dp(28), dp(24), dp(if (session != null && recoveryToShow == null) 92 else 22))
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
                    if (next != index) { activeSection = sections[next]; clearSearchState(); render() }
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
            view.setPadding(dp(24), dp(24) + bars.top, dp(24), dp(if (session != null && recoveryToShow == null) 82 else 18) + bars.bottom)
            insets
        }
        val screen = FrameLayout(this).apply { addView(scroll, FrameLayout.LayoutParams(-1, -1)) }
        if (session != null && recoveryToShow == null) {
            val fab = TextView(this).apply {
                text = "+"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                contentDescription = "Add item"; isClickable = true; isFocusable = true
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(106, 133, 239), Color.rgb(143, 93, 215))).apply { shape = GradientDrawable.OVAL }
                elevation = dp(8).toFloat()
                setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CONFIRM); chooseItemType() }
            }
            screen.addView(fab, FrameLayout.LayoutParams(dp(62), dp(62), Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(22); bottomMargin = dp(82)
            })
        }
        setContentView(screen)
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
    private fun clearSearchState() {
        searchFeedback?.let(mainHandler::removeCallbacks)
        searchFeedback = null
        searchInput = null
        searchQuery = ""
        searchMode = false
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(Color.argb(242, Color.red(color), Color.green(color), Color.blue(color)))
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), Color.argb(42, 255, 255, 255))
    }
    private fun auraGradient() = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(Color.rgb(91, 129, 239), Color.rgb(162, 91, 210))).apply { cornerRadius = dp(17).toFloat() }
    private fun styleAuraDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(rounded(Color.rgb(19, 22, 34), 26))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(Color.WHITE); background = auraGradient(); isAllCaps = false; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
            setTextColor(Color.rgb(194, 202, 233)); background = rounded(Color.rgb(43, 48, 68), 15); isAllCaps = false
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
            setTextColor(Color.rgb(194, 202, 233)); background = rounded(Color.rgb(43, 48, 68), 15); isAllCaps = false
        }
    }
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
                setOnClickListener { activeSection = name; clearSearchState(); render() }
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
            setTextColor(ink)
            setTextIsSelectable(true)
            setPadding(14, 18, 14, 18)
            background = rounded(Color.rgb(28, 34, 52), 18)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16 })
        button("▣  Copy Secret Key", Color.rgb(49, 63, 112)) {
            recoveryToShow?.let { copySensitive(it) }
        }
        val saved = CheckBox(this).apply { text = "I saved this recovery key somewhere safe" }
        root.addView(saved)
        button("Continue") {
            if (!saved.isChecked) return@button message("Save the recovery key before continuing.")
            recoveryToShow = null
            recoveryContinuation?.invoke()
            recoveryContinuation = null
            if (session != null) mainHandler.postDelayed(idleLock, 60_000)
            render()
            if (session != null) root.post { maybeOfferFirstFingerprintPrompt() }
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
        val unlock = Button(this).apply {
            text = "Unlock with password"; setTextColor(Color.WHITE); background = rounded(Color.rgb(49, 55, 77), 17)
            isAllCaps = false; textSize = 15f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); isEnabled = false
            setOnClickListener {
            runCatching { VaultStore.unlockWithPassword(this@MainActivity, password.text.toString().toCharArray()) }
                .onSuccess { session = it; password.performHapticFeedback(HapticFeedbackConstants.CONFIRM); mainHandler.postDelayed(idleLock, 60_000); render(); root.post { maybeOfferFirstFingerprintPrompt() } }
                .onFailure { message("Could not unlock. Check the password or use your recovery key.") }
            }
        }
        root.addView(unlock, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(10) })
        password.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val ready = !s.isNullOrEmpty()
                unlock.isEnabled = ready
                unlock.background = if (ready) auraGradient() else rounded(Color.rgb(49, 55, 77), 17)
                unlock.alpha = if (ready) 1f else .72f
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
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
        val entries = session!!.entries
        if (activeSection == "Overview" && !searchMode) {
            root.addView(label("Your Collections", 20f, ink, true).apply { setPadding(0, dp(24), 0, dp(12)) })
            listOf("Cards" to "Card", "Bank logins" to "Bank", "Passwords" to "Password", "Custom entries" to "Custom").forEach { (name, kind) ->
                val count = entries.count { it.kind == kind }
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = rounded(Color.rgb(22, 26, 39), 22); setPadding(dp(18), dp(18), dp(16), dp(18))
                    elevation = dp(3).toFloat()
                    isClickable = true; isFocusable = true
                    setOnClickListener { activeSection = if (kind == "Bank") "Bank logins" else if (kind == "Password") "Passwords" else if (kind == "Custom") "Custom" else "Cards"; clearSearchState(); render() }
                }
                tile.addView(label(kindGlyph(kind), 21f, accent, true), LinearLayout.LayoutParams(dp(42), -2))
                tile.addView(label(name, 17f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
                tile.addView(label(count.toString(), 15f, muted, true).apply { gravity = Gravity.CENTER; background = rounded(Color.rgb(37, 43, 63), 14); setPadding(dp(12), dp(7), dp(12), dp(7)) })
                root.addView(tile, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
                animateIn(tile)
            }
            return
        }

        title(if (searchMode) "Search" else activeSection)
        sectionNav()
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addRow.addView(Button(this).apply { text = "+ Add"; isAllCaps = false; setTextColor(Color.WHITE); background = rounded(Color.rgb(73, 99, 194), 16); setOnClickListener { chooseItemType() } }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(6) })
        addRow.addView(Button(this).apply { text = "⌕ Search"; isAllCaps = false; setTextColor(ink); background = rounded(surface, 16); setOnClickListener { searchEntries() } }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(6) })
        addRow.addView(Button(this).apply { text = "✣ Quick"; isAllCaps = false; setTextColor(ink); background = rounded(surface, 16); setOnClickListener { showCommandPalette() } }, LinearLayout.LayoutParams(0, dp(50), 1f))
        root.addView(addRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(10) })

        searchInput = null
        if (searchMode) {
            val input = EditText(this).apply {
                hint = "Search names, banks, websites"; setSingleLine(true); setTextColor(ink); setHintTextColor(muted)
                background = rounded(Color.rgb(32, 37, 53), 18); setPadding(dp(16), dp(8), dp(16), dp(8)); setText(searchQuery)
            }
            root.addView(input, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(8) })
            searchInput = input
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, LinearLayout.LayoutParams(-1, -2))
        refreshEntryList(list, entries)
        searchInput?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                refreshEntryList(list, entries)
                searchFeedback?.let(mainHandler::removeCallbacks)
                searchFeedback = null
                if (searchQuery.isNotBlank()) {
                    val query = searchQuery
                    searchFeedback = Runnable {
                        if (searchQuery == query && matchingEntries(entries, query).isEmpty()) {
                            message("No results found", Toast.LENGTH_SHORT)
                            val clearSearch = Runnable { if (searchQuery == query) searchInput?.setText("") }
                            searchFeedback = clearSearch
                            mainHandler.postDelayed(clearSearch, 2_000)
                        }
                    }
                    mainHandler.postDelayed(searchFeedback!!, 400)
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
    }

    private fun matchingEntries(entries: List<VaultStore.Entry>, query: String = searchQuery): List<VaultStore.Entry> {
        val kind = when (activeSection) { "Cards" -> "Card"; "Bank logins" -> "Bank"; "Passwords" -> "Password"; "Custom" -> "Custom"; else -> null }
        return entries.filter { entry ->
            (kind == null || entry.kind == kind) && (query.isBlank() || entry.title.contains(query, true) || entry.username.contains(query, true) || entry.customFields.any { (key, value) -> key.contains(query, true) || value.contains(query, true) })
        }
    }

    private fun refreshEntryList(host: LinearLayout, entries: MutableList<VaultStore.Entry>) {
        host.removeAllViews()
        val matches = matchingEntries(entries)
        if (matches.isEmpty()) {
            val emptyTitle = if (searchQuery.isNotBlank()) "No matching items" else if (activeSection == "Overview") "Your vault is ready" else "No ${activeSection.lowercase()} yet"
            host.addView(label(emptyTitle, 19f, ink, true).apply { setPadding(0, dp(26), 0, dp(6)) })
            host.addView(label(if (searchQuery.isNotBlank()) "Try another name or clear your search." else "Add your first item to this collection.", 13f, muted).apply { setPadding(0, 0, 0, dp(14)) })
            if (searchQuery.isBlank()) host.addView(Button(this).apply {
                text = "＋  Add item"; isAllCaps = false; setTextColor(Color.WHITE); background = rounded(Color.rgb(73, 99, 194), 16)
                setOnClickListener { chooseItemType() }
            }, LinearLayout.LayoutParams(-1, dp(52)))
            return
        }
        matches.forEach { entry -> renderEntryCard(entry, entries, host) }
    }

    private fun renderEntryCard(entry: VaultStore.Entry, entries: MutableList<VaultStore.Entry>, host: LinearLayout) {
        val cardExpanded = expandedCards.contains(entry.id)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(Color.rgb(19, 23, 34), 22)
            setPadding(dp(18), dp(16), dp(14), dp(14))
            elevation = dp(5).toFloat()
            isClickable = true; isFocusable = true
        }
        card.setOnClickListener { if (cardExpanded) expandedCards.remove(entry.id) else expandedCards.add(entry.id); render() }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label("${kindGlyph(entry.kind)}   ${entry.kind.uppercase()}", 10f, accent, true), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(heading)
        val titleView = label(entry.title, 19f, ink, true).apply { setPadding(0, dp(8), 0, dp(7)) }
        highlightMatch(titleView, searchQuery)
        card.addView(titleView)
        when (entry.kind) {
            "Password" -> {
                if (entry.username.isNotBlank()) {
                    val username = label(entry.username, 13f, muted)
                    highlightMatch(username, searchQuery)
                    card.addView(username)
                }
                card.addView(label("••••••••••••", 14f, muted).apply { setPadding(0, dp(7), 0, 0) })
                card.addView(label(if (cardExpanded) "Hide details" else "Tap to view details", 11f, accent).apply {
                    setPadding(0, dp(8), 0, 0); setOnClickListener { if (cardExpanded) expandedCards.remove(entry.id) else expandedCards.add(entry.id); render() }
                })
                if (cardExpanded) {
                    copyableFields(entry).forEach { (id, name, value) -> addCopyableField(card, entry, id, name, value) }
                    addCopySelectedControl(card, entry)
                    addEntryActionRows(card, entry, entries)
                }
            }
            "Card" -> {
                card.addView(label("••••  ${entry.cardNumber.takeLast(4)}     ${entry.expiry}", 13f, muted))
                val expand = label(if (cardExpanded) "Hide card details" else "Tap to view details", 11f, accent).apply { setPadding(0, dp(8), 0, 0); isClickable = true }
                expand.setOnClickListener { if (cardExpanded) expandedCards.remove(entry.id) else expandedCards.add(entry.id); render() }
                card.addView(expand)
                if (cardExpanded) {
                    copyableFields(entry).forEach { (id, name, value) -> addCopyableField(card, entry, id, name, value) }
                    addCopySelectedControl(card, entry)
                    addEntryActionRows(card, entry, entries)
                }
            }
            else -> if (!cardExpanded) entry.customFields.entries.take(3).forEach { (key, value) ->
                val isSecret = key.contains("password", true) || key.contains("pin", true) || key.contains("secret", true) || key.contains("cvv", true)
                val line = label("$key   ${if (isSecret) "••••••••" else value}", 12f, muted).apply { setPadding(0, dp(4), 0, 0) }
                highlightMatch(line, searchQuery)
                card.addView(line)
            }
        }
        if (entry.kind == "Bank" || entry.kind == "Custom") {
            card.addView(label(if (cardExpanded) "Hide details" else "Tap to view details", 11f, accent).apply {
                setPadding(0, dp(8), 0, 0); setOnClickListener { if (cardExpanded) expandedCards.remove(entry.id) else expandedCards.add(entry.id); render() }
            })
            if (cardExpanded) {
                copyableFields(entry).forEach { (id, name, value) -> addCopyableField(card, entry, id, name, value) }
                addCopySelectedControl(card, entry)
                addEntryActionRows(card, entry, entries)
            }
        }
        host.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(4) })
        animateIn(card)
    }

    private fun addEntryActionRows(card: LinearLayout, entry: VaultStore.Entry, entries: MutableList<VaultStore.Entry>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        val buttons: List<Pair<String, () -> Unit>> = listOf(
            "Share" to { shareEntry(entry) },
            "Edit" to { editEntry(entry) },
            "Delete" to { deleteEntry(entry, entries) }
        )
        val colors = listOf(Color.rgb(51, 109, 153), Color.rgb(104, 82, 157), Color.rgb(166, 58, 69))
        buttons.forEachIndexed { index, (title, action) ->
            row.addView(Button(this).apply {
                text = title; isAllCaps = false; textSize = 11f; setTextColor(Color.WHITE); background = rounded(colors[index], 14)
                minHeight = dp(42); setPadding(dp(6), 0, dp(6), 0); setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        }
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

    private fun addCopyableField(container: LinearLayout, entry: VaultStore.Entry, fieldId: String, title: String, value: String) {
        val selected = selectedCopyFields.getOrPut(entry.id) { mutableSetOf() }
        val secret = title.contains("password", true) || title.contains("pin", true) || title.equals("CVV", true) || title.contains("secret", true)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5))
            isClickable = true; isFocusable = true; setOnClickListener { }
        }
        val checkbox = CheckBox(this).apply {
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            isChecked = fieldId in selected
            setOnCheckedChangeListener { _, checked -> if (checked) selected.add(fieldId) else selected.remove(fieldId) }
        }
        row.addView(checkbox, LinearLayout.LayoutParams(dp(42), dp(42)))
        val valueText = label("$title  ${if (secret) "••••••••" else value}", 13f, muted)
        row.addView(valueText, LinearLayout.LayoutParams(0, -2, 1f))
        if (secret) {
            val eye = TextView(this).apply {
                text = "◉"; textSize = 17f; gravity = Gravity.CENTER; setTextColor(accent); contentDescription = "Press and hold to reveal $title"
                background = rounded(Color.rgb(37, 44, 62), 11); isClickable = true; isFocusable = true; setOnClickListener { }
            }
            eye.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { valueText.text = "$title  $value"; true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { valueText.text = "$title  ••••••••"; view.performClick(); true }
                    else -> true
                }
            }
            row.addView(eye, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(4) })
        }
        val copy = TextView(this).apply {
            text = "⧉"; textSize = 19f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); contentDescription = "Copy $title"
            background = rounded(Color.rgb(39, 129, 101), 11); isClickable = true; isFocusable = true
            setOnClickListener { copySensitive(value) }
        }
        row.addView(copy, LinearLayout.LayoutParams(dp(40), dp(38)))
        container.addView(row)
    }

    private fun addCopySelectedControl(container: LinearLayout, entry: VaultStore.Entry) {
        val selection = selectedCopyFields.getOrPut(entry.id) { mutableSetOf() }
        val control = TextView(this).apply {
            text = "⧉  Copy checked fields"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = rounded(Color.rgb(39, 129, 101), 13); isClickable = true; isFocusable = true
            setPadding(dp(12), 0, dp(12), 0); contentDescription = "Copy selected fields"
            setOnClickListener {
                val chosen = copyableFields(entry).filter { it.first in selection }
                if (chosen.isEmpty()) message("Select the fields you want to copy first.")
                else {
                    selectedCopyFields.remove(entry.id)
                    copySensitive(chosen.joinToString("\n") { "${it.second}: ${it.third}" })
                    render()
                }
            }
        }
        container.addView(control, LinearLayout.LayoutParams(-2, dp(42)).apply { topMargin = dp(10) })
    }

    private fun highlightMatch(view: TextView, query: String) {
        if (query.isBlank()) return
        val text = view.text.toString()
        val index = text.indexOf(query, ignoreCase = true)
        if (index >= 0) view.text = android.text.SpannableString(text).apply {
            setSpan(android.text.style.BackgroundColorSpan(Color.rgb(95, 75, 32)), index, index + query.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.ForegroundColorSpan(Color.rgb(255, 221, 139)), index, index + query.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        styleAuraDialog(dialog)
    }

    private fun kindGlyph(kind: String) = when (kind) { "Bank" -> "◈"; "Password" -> "⌑"; "Card" -> "▭"; else -> "✳" }
    private fun chooseItemType() {
        val types = arrayOf("Payment card", "Take card photo", "Choose card image", "Bank login", "Password", "Custom item")
        val icons = arrayOf("▭", "◎", "▧", "◈", "⌑", "✳")
        val colors = arrayOf(Color.rgb(43, 104, 184), Color.rgb(41, 134, 112), Color.rgb(118, 77, 170), Color.rgb(190, 127, 39), Color.rgb(45, 126, 155), Color.rgb(179, 75, 106))
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        val chooser = AlertDialog.Builder(this).setTitle("Add to your vault").setView(panel).setNegativeButton("Cancel", null).create()
        types.forEachIndexed { index, type ->
            panel.addView(Button(this).apply {
                text = "${icons[index]}     $type"; textSize = 15f; isAllCaps = false; gravity = Gravity.CENTER_VERTICAL
                setTextColor(Color.WHITE); background = rounded(colors[index], 18); setPadding(dp(18), 0, dp(18), 0)
                setOnClickListener {
                    chooser.dismiss()
                    when (index) {
                0 -> addEntry("Card")
                1 -> captureCardImage()
                2 -> pickCardImage()
                3 -> addEntry("Bank")
                4 -> addEntry("Password")
                else -> addEntry("Custom")
                    }
                }
            }, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(9) })
            }
        chooser.show()
        styleAuraDialog(chooser)
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
        searchFeedback?.let(mainHandler::removeCallbacks)
        searchFeedback = null
        activeSection = "Overview"
        searchQuery = ""
        searchMode = true
        render()
        val input = searchInput ?: return
        input.requestFocus()
        input.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
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
    private fun maybeOfferFirstFingerprintPrompt() {
        val preferences = getSharedPreferences("security_preferences", Context.MODE_PRIVATE)
        if (preferences.getBoolean("first_fingerprint_prompt_seen", false)) return
        preferences.edit().putBoolean("first_fingerprint_prompt_seen", true).apply()
        if (VaultStore.biometricPayload(this) != null) return
        AlertDialog.Builder(this)
            .setTitle("Enable Fingerprint Authentication?")
            .setMessage("Use your fingerprint for faster and secure access to your vault.")
            .setPositiveButton("Enable") { _, _ -> enableBiometric() }
            .setNegativeButton("Maybe Later", null)
            .show()
    }

    private fun editEntry(entry: VaultStore.Entry) {
        addEntry(entry.kind, editing = entry)
    }

    private fun deleteEntry(entry: VaultStore.Entry, entries: MutableList<VaultStore.Entry>) {
        AlertDialog.Builder(this).setTitle("Delete ${entry.title}?")
            .setMessage("This permanently removes it from the vault.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                entries.removeAll { it.id == entry.id }
                persistAndRender("✓ Item Deleted")
            }.show()
    }

    private fun addEntry(kind: String, prefill: CardPrefill? = null, editing: VaultStore.Entry? = null) {
        val formContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(12)) }
        val form = ScrollView(this).apply { isFillViewport = true; addView(formContent) }
        if (prefill != null) formContent.addView(TextView(this).apply {
            text = "Imported suggestions — check every character before saving. The selected image is not stored in your vault."
            textSize = 13f; setTextColor(muted); setPadding(0, 0, 0, dp(12))
        })
        fun edit(h: String, isSecret: Boolean = false, initialValue: String = ""): EditText = EditText(this).apply {
            hint = h
            gravity = Gravity.CENTER
            setTextColor(ink); setHintTextColor(muted); background = rounded(Color.rgb(34, 38, 51), 18); setPadding(dp(14), dp(8), dp(14), dp(8))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            if (isSecret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(initialValue)
            formContent.addView(this, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(10) })
        }
        val title = edit(when(kind) { "Bank" -> "Bank name"; "Card" -> "Card nickname"; "Password" -> "Website or app"; else -> "Item name" }, initialValue = editing?.title.orEmpty())
        val cardholder = if (kind == "Card") edit("Cardholder name", initialValue = editing?.cardholderName.orEmpty()) else null
        val user = if (kind == "Password") edit("Username or email", initialValue = editing?.username.orEmpty()) else null
        val secret = if (kind == "Password") edit("Password", true, editing?.secret.orEmpty()) else null
        if (kind == "Password") Button(this).apply {
            text = "Generate strong password"
            isAllCaps = false; textSize = 14f; setTextColor(Color.WHITE); background = rounded(Color.rgb(55, 74, 127), 16)
            minHeight = dp(48)
            setOnClickListener {
                val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%&*_-+=?"
                val random = java.security.SecureRandom()
                secret?.setText((1..24).map { alphabet[random.nextInt(alphabet.length)] }.joinToString(""))
            }
        }.also { formContent.addView(it) }
        val number = if (kind == "Card") edit("Card number", initialValue = prefill?.number ?: editing?.cardNumber.orEmpty()) else null
        val expiry = if (kind == "Card") edit("Expiry date", initialValue = prefill?.expiry ?: editing?.expiry.orEmpty()) else null
        val cvv = if (kind == "Card") edit("CVV", true, prefill?.cvv ?: editing?.cvv.orEmpty()) else null
        val custom = linkedMapOf<String, EditText>()
        if (kind == "Bank") {
            val keys = (listOf("Account number", "IFSC code", "Customer ID", "Username", "Net banking password", "Mobile MPIN") + editing?.customFields?.keys.orEmpty()).distinct()
            keys.forEach { key -> custom[key] = edit(key, key.contains("password", true) || key.contains("MPIN"), editing?.customFields?.get(key).orEmpty()) }
        }
        if (kind == "Custom") {
            val keysInput = edit("Your field names, comma separated (e.g. Policy number, Renewal date)", initialValue = editing?.customFields?.keys?.joinToString(", ").orEmpty())
            fun addCustomField(key: String) {
                if (custom.containsKey(key)) return
                val isSecret = key.contains("password", true) || key.contains("pin", true) || key.contains("secret", true) || key.contains("cvv", true)
                custom[key] = edit(key, isSecret, editing?.customFields?.get(key).orEmpty())
            }
            formContent.addView(Button(this).apply {
                text = "Create fields from names"; isAllCaps = false; setOnClickListener {
                    val names = keysInput.text.toString().split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()
                    if (names.isEmpty()) { message("Enter at least one custom field name."); return@setOnClickListener }
                    names.forEach(::addCustomField)
                }
                textSize = 14f; setTextColor(Color.WHITE); background = rounded(Color.rgb(65, 82, 139), 16); minHeight = dp(48)
            })
            editing?.customFields?.keys?.forEach(::addCustomField)
        }
        val notes = edit("Notes (optional)", initialValue = editing?.notes.orEmpty())
        AlertDialog.Builder(this).setTitle(if (editing != null) "Edit ${editing.title}" else when(kind) { "Bank" -> "Add bank login"; "Card" -> "Add payment card"; "Password" -> "Add password"; else -> "Create custom item" }).setView(form).setNegativeButton("Cancel", null)
            .setPositiveButton(if (editing != null) "Save changes" else "Save", null).create().also { dialog ->
                dialog.setOnShowListener {
                    styleAuraDialog(dialog)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (title.text.isBlank() || (kind == "Password" && secret?.text.isNullOrBlank()) || (kind == "Card" && number?.text.isNullOrBlank()) || (kind == "Custom" && custom.isEmpty())) {
                            message("Enter a name, required details, and any custom fields."); return@setOnClickListener
                        }
                        val e = VaultStore.Entry(editing?.id ?: UUID.randomUUID().toString(), kind, title.text.toString(), cardholder?.text.toString(), user?.text.toString(),
                            secret?.text.toString(), number?.text.toString(), expiry?.text.toString(), cvv?.text.toString(), notes.text.toString(),
                            custom.mapValues { it.value.text.toString() }.filterValues { it.isNotBlank() })
                        if (editing == null) session!!.entries.add(e)
                        else {
                            val index = session!!.entries.indexOfFirst { it.id == editing.id }
                            if (index >= 0) session!!.entries[index] = e
                        }
                        persistAndRender(if (editing == null) "✓ Item Saved" else "✓ Changes Saved"); dialog.dismiss()
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
                                success("✓ Details Ready to Share")
                            }.show()
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun copySensitive(value: String) {
        if (value.isBlank()) return message("There is nothing to copy.")
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
        // Capture only the application context and unique clip token. The callback
        // remains safe to run after this Activity has been destroyed.
        val clearTask = Runnable {
            val currentToken = clipboard.primaryClip?.description?.extras?.getString(CLIP_TOKEN_EXTRA)
            if (currentToken == token) clipboard.clearPrimaryClip()
        }
        pendingClipboardClear = clearTask
        mainHandler.postDelayed(clearTask, 30_000)
        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    private fun clearVaultClipboardNow() {
        pendingClipboardClear?.let(mainHandler::removeCallbacks)
        pendingClipboardClear = null
        val clipboard = applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val currentToken = clipboard.primaryClip?.description?.extras?.getString(CLIP_TOKEN_EXTRA)
        if (currentToken?.startsWith(CLIP_TOKEN_PREFIX) == true) clipboard.clearPrimaryClip()
    }

    private fun persistAndRender(successMessage: String? = null) {
        runCatching { VaultStore.save(this, session!!.entries, session!!.dek) }
            .onSuccess { render(); successMessage?.let(::success) }
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
                        }.onSuccess { this@MainActivity.window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM); success("✓ Fingerprint unlock enabled."); render() }
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
                        .onSuccess { dek -> this@MainActivity.window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM); session = VaultStore.Session(dek, VaultStore.entriesWithDek(this@MainActivity, dek)); recoveryUnlockMode = false; mainHandler.postDelayed(idleLock, 60_000); render(); root.post { maybeOfferFirstFingerprintPrompt() } }
                        .onFailure { message("Fingerprint unlock failed. Use your master password or recovery key.") }
                }
            })
    }

    private fun showRecoveryUnlock() { recoveryUnlockMode = true; render() }
    private var recoveryUnlockMode = false

    private fun lockNow() {
        mainHandler.removeCallbacks(idleLock)
        clearVaultClipboardNow()
        session?.dek?.fill(0)
        session = null
        recoveryUnlockMode = false
        clearSearchState()
        render()
    }
    private fun success(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun message(text: String, duration: Int = Toast.LENGTH_LONG) = Toast.makeText(this, text, duration).show()
}
