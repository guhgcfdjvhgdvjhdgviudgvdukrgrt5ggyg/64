package com.ghosttype.ime

import android.content.Intent
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import com.ghosttype.security.DeviceId
import com.ghosttype.utils.SettingsStore
import com.ghosttype.utils.UnicodeFonts
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

class GhostTypeIMEService : InputMethodService() {

    /**
     * v1.9 — small placeholder view shown inside the IME pane when the
     * device isn't approved or the APK has been tampered with. The
     * user can tap it to launch MainActivity (which shows the full
     * lock screen with the WhatsApp button + device ID), or long-
     * press space on most launchers to switch to a different IME.
     */
    private fun makeImeLockView(): View {
        val tv = android.widget.TextView(this).apply {
            text = "🔒  GhostType Pro is locked\n\n" +
                    "Tap here to open GhostType and send your\n" +
                    "device ID to the developer for approval."
            setPadding(48, 64, 48, 64)
            setBackgroundColor(android.graphics.Color.parseColor("#0D0D0D"))
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
        }
        tv.setOnClickListener {
            try {
                startActivity(
                    Intent(this, com.ghosttype.ui.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) { /* ignore */ }
        }
        return tv
    }

    private var keyboardView: KeyboardView? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentSuggestions: List<String> = emptyList()
    private var currentEmojiSuggestions: List<String> = emptyList()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // Reload keyboard view when theme/font/bg/key-size related settings change
        when (key) {
            SettingsStore.KEY_FONT_PATH, SettingsStore.KEY_FONT_BOLD, SettingsStore.KEY_FONT_ITALIC -> {
                // Wipe the typeface cache so the next reload actually decodes
                // the newly-selected .ttf instead of returning the old one.
                com.ghosttype.utils.FontManager.invalidateCache()
                scope.launch { keyboardView?.reload() }
            }
            SettingsStore.KEY_FONT_STYLE,
            SettingsStore.KEY_MATH_ENABLED,
            SettingsStore.KEY_FYT_ENABLED -> {
                // Unicode output style / Math / FYT toggled —
                // reload toolbar so tile labels update (Math ✓ / FYT ✓).
                scope.launch { keyboardView?.reload() }
            }
            SettingsStore.KEY_BG_IMAGE_URI, SettingsStore.KEY_BG_IMAGE_OPACITY, SettingsStore.KEY_BG_SHOW_BORDERS,
            // v1.6 — toggling "apply bg image to keys" must rebuild the
            // key views so each TextView gets / drops its BgImageKeyDrawable.
            SettingsStore.KEY_BG_IMAGE_ON_KEYS,
            SettingsStore.KEY_BORDER_STYLE, SettingsStore.KEY_KEY_OPACITY,
            SettingsStore.KEY_KEY_HEIGHT_DP, SettingsStore.KEY_KEY_TEXT_SIZE,
            // v1.7 — new per-key spacing slider + 3D shadow toggle.
            // Both rebuild the keyboard so the change is instantly visible.
            SettingsStore.KEY_KEY_MARGIN_DP, SettingsStore.KEY_KEY_3D_SHADOW,
            SettingsStore.KEY_SUGGESTIONS_ON_TOP,
            SettingsStore.KEY_THEME,
            // Per-color overrides (built-in theme picks write all five
            // alongside KEY_THEME) — listener fires once per changed key in
            // the same edit, so safe to react to any of them.
            SettingsStore.KEY_KEY_BG, SettingsStore.KEY_KEY_TEXT, SettingsStore.KEY_KB_BG,
            SettingsStore.KEY_SUGG_BG, SettingsStore.KEY_PRESSED -> {
                scope.launch { keyboardView?.reload() }
            }
        }
    }

    /**
     * Apply the user's currently-selected Unicode style to [text] before
     * committing it to the focused field. Empty / null styles are a pass-through.
     * When Math Mode is ON it takes exclusive control — all other font styles
     * are bypassed so only 1337-style letter→number conversion is applied.
     */
    private fun stylize(text: String): String {
        if (text.isEmpty()) return text
        val p = SettingsStore.prefs(this)
        val t = when {
            p.getBoolean(SettingsStore.KEY_MATH_ENABLED, false) -> {
                // Math Mode: letters → 1337 numbers, all other styles bypassed
                UnicodeFonts.toMath(text)
            }
            p.getBoolean(SettingsStore.KEY_FYT_ENABLED, false) -> {
                // FYT Mode: each character repeated N times (e.g. "hi" + 3 → "hhhiii")
                // Other font styles bypassed when FYT is active.
                val count = p.getInt(SettingsStore.KEY_FYT_COUNT, 3).coerceIn(2, 9)
                val sb = StringBuilder()
                var i = 0
                while (i < text.length) {
                    val cp = text.codePointAt(i)
                    val ch = String(Character.toChars(cp))
                    if (cp > 32) repeat(count) { sb.append(ch) } else sb.append(ch)
                    i += Character.charCount(cp)
                }
                sb.toString()
            }
            else -> UnicodeFonts.transform(this, text)
        }
        return if (p.getBoolean(SettingsStore.KEY_CAPS_MODE, false)) t.uppercase() else t
    }

    /**
     * Commit text after applying the Unicode style. Returns false on failure.
     */
    private fun commitStyled(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        return try { ic.commitText(stylize(text), 1) } catch (_: Throwable) { false }
    }

    /**
     * v1.10 — Receiver that listens for the periodic background approval
     * worker telling us "this device's approval was just revoked from the
     * GitHub Users.json". When it fires we instantly swap the visible IME
     * view to the lock placeholder, so the user can't type even one more
     * character through the still-open keyboard. Without this the
     * keyboard view stays alive (cached by the framework) until the next
     * fresh field bind, leaving a 6-h cache window where typing still
     * worked after revocation.
     */
    private val approvalRevokedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != com.ghosttype.security.ApprovalRefreshWorker.ACTION_APPROVAL_REVOKED) return
            try {
                // setInputView() can only be called while the IME has a
                // window. If the keyboard isn't visible there's nothing to
                // do — the next onCreateInputView() will already render
                // the lock view because ApprovalGate.isApprovedCached()
                // now reads false from the freshly-updated SharedPrefs.
                if (window?.window?.isActive == true) {
                    setInputView(makeImeLockView())
                }
            } catch (_: Throwable) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = WeakReference(this)
        // Clipboard watcher is started in GhostTypeApp so it stays alive even
        // when the keyboard is closed (history persists across power cycles).
        try { SettingsStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener) } catch (_: Exception) {}
        // v1.10 — approval-revoked listener. Registered NOT_EXPORTED on
        // Android 13+ (Tiramisu) where the explicit flag is required for
        // app-internal broadcasts. The worker explicitly setPackage()s
        // the broadcast to our package so external apps can never deliver
        // a fake "you're revoked" signal that would lock out the user.
        try {
            val filter = android.content.IntentFilter(
                com.ghosttype.security.ApprovalRefreshWorker.ACTION_APPROVAL_REVOKED
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(approvalRevokedReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(approvalRevokedReceiver, filter)
            }
        } catch (_: Throwable) {}

        // Strict injector — auto-type ONLY succeeds when the GhostType IME is
        // currently bound to a real input field (currentInputConnection != null
        // AND we have a visible input view). The accessibility fallback was the
        // root cause of "typing into nowhere" — it would happily type when no
        // chat field was open and the user couldn't see anything happen. If the
        // keyboard isn't actually serving a target field we now return false
        // so AutoTypeEngine waits for the user to tap into the chat box.
        AutoTypeEngine.injector = { text ->
            val ic = currentInputConnection
            val view = keyboardView
            if (ic != null && view != null && view.isAttachedToWindow) {
                // Stylize each character — auto-type now respects the user's
                // chosen Unicode font, so loaded .txt files come out fancy too.
                val ok = try { ic.commitText(stylize(text), 1) } catch (_: Throwable) { false }
                ok
            } else false
        }
        // Per-key animation hook — flashes the matching key on the visible
        // keyboard so the user can SEE the typer working in real time.
        AutoTypeEngine.onKeyPressed = { ch ->
            try {
                scope.launch { keyboardView?.flashKeyForChar(ch) }
            } catch (_: Throwable) {}
        }
        AutoTypeEngine.sender = {
            val ic = currentInputConnection
            if (ic == null) false
            else {
                // Real Enter KeyEvent (down + up). This actually dispatches a
                // hardware-style Enter into the field instead of just inserting
                // a "\n" character, so chat apps like WhatsApp/Messenger that
                // listen for ENTER as "send" will fire their send action.
                val now = android.os.SystemClock.uptimeMillis()
                val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0)
                val up   = KeyEvent(now, now, KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_ENTER, 0)
                val a = try { ic.sendKeyEvent(down) } catch (_: Throwable) { false }
                val b = try { ic.sendKeyEvent(up)   } catch (_: Throwable) { false }
                if (a && b) true
                else {
                    // Last-resort: try the editor action (some message fields
                    // require performEditorAction even though they expose Enter).
                    val ei = currentInputEditorInfo
                    val actionId = ei?.actionId ?: 0
                    val maskedAction = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                    when {
                        actionId != 0 -> ic.performEditorAction(actionId)
                        maskedAction != 0 && maskedAction != EditorInfo.IME_ACTION_NONE ->
                            ic.performEditorAction(maskedAction)
                        else -> false
                    }
                }
            }
        }
    }

    override fun onCreateInputView(): View {
        // v1.9 — hard gate. If the device hasn't been approved by the
        // developer (Users.json on GitHub) OR if the APK has been
        // tampered with, refuse to render the keyboard and instead
        // show a static "locked" view that explains how to request
        // access. Android won't let us return null here (the OS
        // requires every IME to provide a view), so we hand back a
        // disabled placeholder. The user can still long-press space
        // to switch to a different keyboard.
        val approved = try { com.ghosttype.security.ApprovalGate.isApprovedCached(this) }
            catch (_: Throwable) { false }
        val tamperOk = try { com.ghosttype.security.SecurityGuard.verifyOrDie(this) }
            catch (_: Throwable) { false }
        val planExpired = try { com.ghosttype.utils.SettingsStore.isPlanExpired(this) }
            catch (_: Throwable) { false }
        if (planExpired) {
            try { DeviceId.reset(this) } catch (_: Throwable) {}
        }
        if (!approved || !tamperOk || planExpired) {
            return makeImeLockView()
        }
        val view = KeyboardView(
            ctx = this,
            onKey = { handleKey(it) },
            getEmojiSuggestions = { currentEmojiSuggestions },
            onEmojiSuggestion = { commitEmoji(it) },
            // "Manage all" link inside the popup still jumps to the Settings
            // screen for full clipboard management.
            onClipboardOpen = { openSettingsTo("clipboard") },
            onSwitchLanguage = { switchLanguage() },
            onOpenSettings = { openSettingsTo("home") },
            // Tools-grid tiles route their tap to a specific section.
            onOpenSection = { section -> openSettingsTo(section) },
            readBeforeCursor = {
                // Return RAW (normalized) text — the field may contain
                // stylized Unicode if the user typed with a fancy style on.
                val raw = currentInputConnection?.getTextBeforeCursor(2048, 0)?.toString().orEmpty()
                UnicodeFonts.normalize(raw)
            },
            replaceBeforeCursor = { length, text ->
                val ic = currentInputConnection
                if (ic != null) {
                    if (length > 0) ic.deleteSurroundingText(length, 0)
                    ic.commitText(stylize(text), 1)
                }
            },
            commitToField = { text ->
                currentInputConnection?.commitText(stylize(text), 1)
            }
        )
        keyboardView = view
        return view
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        updateSuggestions("")
        applyAutoCaps()
    }

    /**
     * v1.10 — every time the keyboard window is shown for a fresh field we
     * force the panel back to KEYS. The same KeyboardView instance is reused
     * across show/hide cycles (Android caches the view returned from
     * onCreateInputView), so without this reset the keyboard would re-open
     * on whatever panel the user had visible last (Emoji / Auto-Type / Tools)
     * — which the user complained felt broken. Now every open lands on the
     * familiar typing layout.
     */
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        try { keyboardView?.resetToKeysPanel() } catch (_: Throwable) {}
    }

    /**
     * Belt-and-braces: also reset on hide so the very next show is guaranteed
     * to start on KEYS even if the framework skips onStartInputView (some
     * launchers / lock-screen IMEs do this when re-binding to the same field).
     */
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        try { keyboardView?.resetToKeysPanel() } catch (_: Throwable) {}
    }

    /**
     * Mirror Android's standard sentence-capitalization behavior. Run after
     * onStartInput, after sentence terminators (`.`/`?`/`!`/`۔`), after
     * Enter/newline, and after the space that follows them — exactly the
     * moments where the next typed character is the START of a new sentence
     * and should therefore be uppercase.
     *
     * Honors [SettingsStore.KEY_AUTO_CAPS] (default ON), and skips fields
     * the user almost never wants auto-capitalized in (passwords, emails,
     * URIs, numbers — same exclusions Gboard / SwiftKey use).
     */
    private fun applyAutoCaps() {
        try {
            val view = keyboardView ?: return
            if (!SettingsStore.prefs(this).getBoolean(SettingsStore.KEY_AUTO_CAPS, true)) {
                return
            }
            val ei = currentInputEditorInfo
            if (ei != null && !isAutoCapEligibleField(ei)) {
                return
            }
            val ic = currentInputConnection
            // Read a generous chunk so we can scan past trailing spaces and
            // the (potentially multi-char) stylized sentence terminator.
            val raw = ic?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
            val plain = UnicodeFonts.normalize(raw)
            view.setShifted(shouldAutoCapitalize(plain))
        } catch (_: Throwable) { /* never crash typing on a UI hint */ }
    }

    /**
     * Returns true when the cursor sits at the start of a new sentence:
     *   • field is empty / contains only whitespace, or
     *   • the most recent non-whitespace char is `.`, `?`, `!`, `۔`, `\n`,
     *     or one of the closing quotes/brackets that typically wrap a
     *     finished sentence (`)` `]` `"` `'`).
     */
    private fun shouldAutoCapitalize(textBeforeCursor: String): Boolean {
        if (textBeforeCursor.isEmpty()) return true
        // Walk backwards past trailing whitespace; if we hit the start of
        // the field with only whitespace seen, this IS sentence start.
        var i = textBeforeCursor.length - 1
        while (i >= 0 && textBeforeCursor[i].isWhitespace()) i--
        if (i < 0) return true
        val c = textBeforeCursor[i]
        return c == '.' || c == '?' || c == '!' || c == '۔' || c == '\n'
    }

    /**
     * Auto-cap should NOT fire on fields where uppercase first letters are
     * actively unhelpful (passwords get harder to type, emails / URLs / IDs
     * are case-sensitive). Mirrors the variation classes Android exposes.
     */
    private fun isAutoCapEligibleField(ei: EditorInfo): Boolean {
        val it = ei.inputType
        val cls = it and InputType.TYPE_MASK_CLASS
        if (cls != InputType.TYPE_CLASS_TEXT) return false
        val variation = it and InputType.TYPE_MASK_VARIATION
        return when (variation) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_FILTER -> false
            else -> true
        }
    }

    /**
     * The IME framework calls this whenever the cursor moves — either
     * because the user tapped a different spot in the field, an external
     * source modified the text, or our own commit advanced the caret.
     * Re-evaluate auto-caps so dropping the cursor onto a fresh sentence
     * (e.g. after a "." in the middle of an existing paragraph) toggles
     * shift on the same way Gboard does.
     */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd,
            candidatesStart, candidatesEnd
        )
        // Only re-check when caret position is well-defined (no selection),
        // otherwise an in-progress drag-select would briefly flip the
        // keyboard between cases as the user dragged.
        if (newSelStart == newSelEnd) applyAutoCaps()
    }

    override fun onFinishInput() {
        super.onFinishInput()
    }

    /**
     * Fires when the IME window is dismissed — user tapped the system
     * back button / down arrow, swiped to another app, or the focused
     * field released its input session. The auto-typer relies on a live
     * IME injector to fire keystrokes; once the keyboard is hidden it
     * has no reliable target, so we HARD-STOP it here.
     *
     * Without this hook a running auto-typer would stay parked in the
     * background, then misfire its first batch of keys whenever the
     * user re-summoned the keyboard — landing on the wrong app or the
     * wrong field. User explicit ask: "keyboard close hony pr auto
     * typer stop ho jana chahiye".
     */
    override fun onWindowHidden() {
        super.onWindowHidden()
        try {
            val s = AutoTypeEngine.state.value
            if (s.running) {
                AutoTypeEngine.stop()
                try { AutoTypeForegroundService.stop(this) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        AutoTypeEngine.injector = null
        AutoTypeEngine.sender = null
        AutoTypeEngine.onKeyPressed = null
        try { SettingsStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener) } catch (_: Exception) {}
        // v1.10 — pair to the registerReceiver in onCreate. Wrapped in
        // try/catch because IllegalArgumentException is thrown if the
        // receiver was never registered (e.g., onCreate threw before the
        // register call) — harmless to ignore.
        try { unregisterReceiver(approvalRevokedReceiver) } catch (_: Throwable) {}
        scope.cancel()
        super.onDestroy()
    }

    private fun handleKey(key: KeyDef) {
        val ic = currentInputConnection ?: return
        when (key.type) {
            KeyType.BACKSPACE -> {
                // ===== Selection-aware backspace =====
                // If the user (or "Select All" from our long-press menu) has
                // a non-empty selection, replace it with empty so the whole
                // selected block disappears in a single key press — same as
                // every native keyboard. Without this check we'd happily
                // delete only the single glyph before the cursor while the
                // selection just sits there highlighted.
                val sel = try { ic.getSelectedText(0) } catch (_: Throwable) { null }
                if (!sel.isNullOrEmpty()) {
                    try { ic.commitText("", 1) } catch (_: Throwable) {
                        // Fallback: synthesize a DEL key event.
                        val now = android.os.SystemClock.uptimeMillis()
                        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0))
                        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_DEL, 0))
                    }
                    return
                }
                // Stylized glyphs may be surrogate pairs (2 char units) and
                // can carry combining marks. Read what's actually before the
                // cursor and delete exactly one *visible* glyph, otherwise
                // backspace would leave half a character in the field.
                val raw = ic.getTextBeforeCursor(8, 0)?.toString().orEmpty()
                val n = UnicodeFonts.lastGlyphLength(raw).coerceAtLeast(1)
                ic.deleteSurroundingText(n, 0)
            }
            KeyType.ENTER -> {
                val ei = currentInputEditorInfo
                val imeOptions = ei?.imeOptions ?: 0
                val inputType = ei?.inputType ?: 0
                val action = imeOptions and EditorInfo.IME_MASK_ACTION
                val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
                val isMultiLine =
                    (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ||
                    (inputType and InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0
                val prefs = SettingsStore.prefs(this)
                val forceNewline = prefs.getBoolean(SettingsStore.KEY_ENTER_NEWLINE, false)
                // LONG-PRESS escape hatch: when KeyboardView fires a long-press
                // on Enter it sends output = "\n" so the user can always insert
                // a real newline regardless of field type or smart-Enter logic.
                val longPressNewline = key.output == "\n"
                // BEHAVIOR: when the floating sender pointer is enabled the
                // user is using the dot to send → Enter must NEVER fire the
                // chat app's send action (would compete with the pointer and
                // produce double-sends or premature sends mid-message).
                val pointerEnabled = prefs.getBoolean(SettingsStore.KEY_POINTER_ENABLED, false)
                // SMART ENTER (issue #1):
                //   • Multi-line fields (WhatsApp / Messenger / Telegram chat
                //     box, IME_ACTION_NONE, IME_ACTION_UNSPECIFIED, the
                //     no-enter-action flag) → newline. This preserves the
                //     existing "Enter goes to next line" behavior in chat
                //     apps that the user wants to keep.
                //   • Single-line fields with a real IME action (browser
                //     address bar = GO, search box = SEARCH, login form =
                //     DONE/NEXT/SEND) → fire the field's action. This is
                //     the real fix for "browser me Enter naya line karta hai".
                val shouldNewline = longPressNewline ||
                        pointerEnabled ||
                        forceNewline ||
                        isMultiLine ||
                        noEnterAction ||
                        action == 0 ||
                        action == EditorInfo.IME_ACTION_NONE ||
                        action == EditorInfo.IME_ACTION_UNSPECIFIED
                if (shouldNewline) {
                    ic.commitText("\n", 1)
                } else {
                    if (!ic.performEditorAction(action)) ic.commitText("\n", 1)
                }
            }
            KeyType.SPACE -> {
                ic.commitText(stylize(" "), 1)
                updateSuggestions("")
            }
            KeyType.PERIOD, KeyType.COMMA -> {
                ic.commitText(stylize(key.output), 1)
            }
            // ===== Edit-context actions (issue #2 long-press menu) =====
            // performContextMenuAction is the public IME hook for these
            // exact actions; it works in every standard EditText (chat
            // boxes, browser fields, notes, etc.) and falls back to
            // synthesized key events for the rest.
            KeyType.SELECT_ALL -> {
                if (!ic.performContextMenuAction(android.R.id.selectAll)) {
                    val now = android.os.SystemClock.uptimeMillis()
                    ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON))
                    ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON))
                }
            }
            KeyType.COPY  -> { ic.performContextMenuAction(android.R.id.copy) }
            KeyType.CUT   -> { ic.performContextMenuAction(android.R.id.cut) }
            KeyType.PASTE -> { ic.performContextMenuAction(android.R.id.paste) }
            else -> {
                ic.commitText(stylize(key.output), 1)
                val before = UnicodeFonts.normalize(
                    ic.getTextBeforeCursor(32, 0)?.toString() ?: ""
                )
                val partial = before.takeLastWhile { it.isLetterOrDigit() }
                updateSuggestions(partial)
            }
        }
    }

    /**
     * Given how many ASCII chars a token covers, return how many UTF-16 char
     * units those characters take up in the field RIGHT NOW (could be 1× or
     * 2× per ASCII char depending on whether the user typed with a stylized
     * font on). Returns 0 if we can't safely determine it.
     */
    private fun matchedFieldLengthForRaw(
        ic: android.view.inputmethod.InputConnection,
        rawLength: Int
    ): Int {
        if (rawLength <= 0) return 0
        val before = ic.getTextBeforeCursor(rawLength * 4 + 8, 0)?.toString() ?: return 0
        val normalized = UnicodeFonts.normalize(before)
        if (normalized.length < rawLength) return 0
        val targetNormalizedStart = normalized.length - rawLength
        // Walk the original string glyph-by-glyph from the end, counting how
        // many normalized chars each glyph contributes; stop when we've covered
        // the desired raw range.
        var fieldUnits = 0
        var rawCovered = 0
        var i = before.length
        while (i > 0 && rawCovered < rawLength) {
            // Determine glyph length ending at i
            val sub = before.substring(0, i)
            val gLen = UnicodeFonts.lastGlyphLength(sub).coerceAtLeast(1)
            val glyph = sub.substring(sub.length - gLen)
            val n = UnicodeFonts.normalize(glyph)
            fieldUnits += gLen
            rawCovered += n.length.coerceAtLeast(1)
            i -= gLen
        }
        return if (rawCovered >= rawLength) fieldUnits else 0
    }

    private fun updateSuggestions(prefix: String) {
        currentSuggestions = emptyList()
        currentEmojiSuggestions = when {
            prefix.isNotBlank() -> EmojiSuggestions.forKeyword(prefix, 2)
            else -> emptyList()
        }
        keyboardView?.refreshSuggestions()
    }

    /**
     * Insert an emoji at the cursor. Emojis bypass the
     * unicode-stylizer because that pipeline is letter-only and would garble
     * surrogate pairs.
     */
    private fun commitEmoji(emoji: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(emoji, 1)
    }

    private fun switchLanguage() {
        val cur = SettingsStore.prefs(this).getString(SettingsStore.KEY_LANGUAGE, "en") ?: "en"
        val next = KeyboardLayouts.nextLanguage(cur)
        keyboardView?.setLanguage(next)
    }

    private fun openSettingsTo(section: String) {
        val i = Intent(this, com.ghosttype.ui.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("section", section)
        startActivity(i)
    }

    companion object {
        var instance: WeakReference<GhostTypeIMEService>? = null
    }
}
