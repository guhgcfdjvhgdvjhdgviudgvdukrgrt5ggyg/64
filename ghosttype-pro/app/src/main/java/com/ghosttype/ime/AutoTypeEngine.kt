package com.ghosttype.ime

import android.content.Context
import android.net.Uri
import com.ghosttype.utils.SettingsStore
import com.ghosttype.utils.UnicodeFonts
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.coroutineContext
import java.io.BufferedReader
import java.io.InputStreamReader

data class AutoTypeState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val currentLine: String = "",
    val sourceName: String = "",
    val lastError: String? = null
)

object AutoTypeEngine {
    private val _state = MutableStateFlow(AutoTypeState())
    val state: StateFlow<AutoTypeState> = _state

    private var lines: List<String> = emptyList()
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Inject text into current field. Returns true on success. */
    var injector: ((String) -> Boolean)? = null
        @Synchronized set

    /** True when the GhostType IME is alive and ready to type. */
    val isImeReady: Boolean get() = injector != null

    /** Press send via IME action / accessibility scan. Returns true on success. */
    var sender: (() -> Boolean)? = null
        @Synchronized set

    /**
     * Called on the typing thread for every single character right after it has
     * been committed to the target field. The IME wires this so the on-screen
     * keyboard can flash the matching key (real per-key press animation that
     * the user can see while the auto-typer runs).
     */
    var onKeyPressed: ((String) -> Unit)? = null
        @Synchronized set

    /**
     * The MainActivity calls this from its `onDestroy()` so that swiping the
     * GhostType app away from Recents (or a Force Stop) immediately halts the
     * auto-typer. The IME itself keeps running independently for normal
     * keyboard use.
     */
    fun onAppDestroyed() { stop() }

    fun loadFromUri(ctx: Context, uri: Uri, displayName: String? = null): Int {
        val list = mutableListOf<String>()
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { seq ->
                seq.forEach { raw ->
                    val t = raw.trim()
                    if (t.isNotEmpty()) list.add(t)
                }
            }
        }
        lines = list
        val name = displayName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file.txt"
        SettingsStore.prefs(ctx).edit()
            .putString(SettingsStore.KEY_AT_LAST_FILE, uri.toString())
            .putString(SettingsStore.KEY_AT_LAST_FILE_NAME, name)
            .putInt(SettingsStore.KEY_AT_START_LINE, 0)
            .apply()
        _state.value = _state.value.copy(total = list.size, current = 0, currentLine = "", sourceName = name)
        return list.size
    }

    fun loadFromText(ctx: Context, text: String, label: String = "Custom text") {
        lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        SettingsStore.prefs(ctx).edit()
            .putString(SettingsStore.KEY_AT_CUSTOM_TEXT, text)
            .putInt(SettingsStore.KEY_AT_START_LINE, 0)
            .apply()
        _state.value = _state.value.copy(total = lines.size, current = 0, currentLine = "", sourceName = label)
    }

    fun start(ctx: Context, startLine: Int = 0) {
        if (lines.isEmpty()) {
            _state.value = _state.value.copy(lastError = "No lines loaded")
            return
        }
        stop()
        val prefs = SettingsStore.prefs(ctx)
        val delaySec = prefs.getInt(SettingsStore.KEY_AT_DELAY, 5).coerceIn(1, 60)
        val loop = true
        // ALWAYS use direct (per-character IME typing). Paste mode is removed —
        // paste was the source of the "loop me copy" bug where the same line was
        // re-copied to clipboard each cycle without ever being typed.
        val autoSend = prefs.getBoolean(SettingsStore.KEY_AT_AUTO_SEND, true)
        val sendDelayMs = prefs.getInt(SettingsStore.KEY_AT_SEND_DELAY_MS, 300).coerceIn(0, 5000).toLong()
        val sendMethod = prefs.getString(SettingsStore.KEY_AT_SEND_METHOD, "auto") ?: "auto"
        val pointerOn = prefs.getBoolean(SettingsStore.KEY_POINTER_ENABLED, false)
        val px = prefs.getInt(SettingsStore.KEY_POINTER_X, -1)
        val py = prefs.getInt(SettingsStore.KEY_POINTER_Y, -1)
        val charDelayMs = prefs.getInt(SettingsStore.KEY_AT_CHAR_DELAY_MS, 35).coerceIn(0, 500).toLong()
        val pointerClickDelayMs = prefs.getInt(SettingsStore.KEY_POINTER_CLICK_DELAY_MS, 0).coerceIn(0, 400000).toLong()
        // v1.10 — optional "Target name" prefix. Read once at session start
        // (not per-line) so toggling it mid-run doesn't produce mixed output.
        // When blank/unset we skip the prefix entirely → no behavioural
        // change for users who don't fill the field.
        val targetName = prefs.getString(SettingsStore.KEY_AT_TARGET_NAME, "")?.trim().orEmpty()
        val targetPrefix = if (targetName.isNotEmpty()) "$targetName " else ""

        // FYT — Fancy Repeat Typing
        // When enabled, every character in a line is repeated fytCount times
        // before typing. "hello" + count 3 → "hhheeelllooo"
        val fytEnabled = prefs.getBoolean(SettingsStore.KEY_FYT_ENABLED, false)
        val fytCount   = prefs.getInt(SettingsStore.KEY_FYT_COUNT, 3).coerceIn(1, 50)

        // Math mode — convert letters to math-style numbers (E→3, T→7 etc.)
        // and type each line mathCount times.
        val mathEnabled = prefs.getBoolean(SettingsStore.KEY_MATH_ENABLED, false)
        val mathCount   = prefs.getInt(SettingsStore.KEY_MATH_COUNT, 1).coerceIn(1, 50)

        val multiClick = prefs.getBoolean(SettingsStore.KEY_POINTER_MULTI_CLICK, false)
        job = scope.launch {
            do {
                var i = startLine.coerceIn(0, lines.size)
                _state.value = _state.value.copy(running = true, paused = false, current = i, lastError = null)
                while (i < lines.size) {
                    while (_state.value.paused) delay(200)
                    if (!isActive) return@launch
                    // Each line gets the configured target prefix prepended
                    // before BOTH the on-screen progress display AND the
                    // text actually injected — so what the user sees matches
                    // what gets typed/sent.
                    var line = targetPrefix + lines[i]
                    // Math transform: convert letters to 1337-style numbers,
                    // then repeat each word mathCount times (e.g. "hi" + 3 → "hi hi hi").
                    // Takes priority over FYT — matches keyboard stylize() behavior.
                    if (mathEnabled) {
                        line = UnicodeFonts.toMath(line)
                        line = line.split(" ").joinToString(" ") { word ->
                            (1..mathCount.coerceAtLeast(1)).joinToString(" ") { word }
                        }
                    } else if (fytEnabled) {
                        // FYT transform: repeat each Unicode code-point N times.
                        // Operates on code points so multi-byte emoji stay intact.
                        val sb = StringBuilder()
                        var cp_idx = 0
                        while (cp_idx < line.length) {
                            val cp = line.codePointAt(cp_idx)
                            val ch = String(Character.toChars(cp))
                            repeat(fytCount) { sb.append(ch) }
                            cp_idx += Character.charCount(cp)
                        }
                        line = sb.toString()
                    }

                    _state.value = _state.value.copy(current = i + 1, currentLine = line)

                    val ok = sendLine(line, charDelayMs)
                    if (!ok) {
                        _state.value = _state.value.copy(lastError = "Inject failed at line ${i + 1}")
                    } else if (autoSend) {
                        delay(sendDelayMs)
                        val sent = trySend(sendMethod, pointerOn, px, py, multiClick, pointerClickDelayMs)
                        if (!sent) {
                            _state.value = _state.value.copy(lastError = "Send failed at line ${i + 1} (no Send action found)")
                        }
                    }
                    i++
                    if (i < lines.size) delay(delaySec * 1000L)
                }
            } while (loop && isActive)
            _state.value = _state.value.copy(running = false, paused = false)
        }
    }

    private suspend fun trySend(method: String, pointerOn: Boolean, px: Int, py: Int, multiClick: Boolean = false, pointerClickDelayMs: Long = 0L): Boolean {
        // ===== HARD SAFETY GUARD (issue #8) =====
        // The pointer click path dispatches a real (x, y) tap into whatever
        // window is on screen — including the home screen, recents, or any
        // unrelated app the user navigated to between typing the line and
        // the send delay finishing. That was causing "kya kya open ho jata
        // hai" (random apps opening / random buttons clicked).
        //
        // Rule: NEVER fire send / pointer click / accessibility click unless
        // the GhostType IME is currently bound to a real editable input
        // field. `injector != null` is true only when the IME service has a
        // live currentInputConnection AND a visible input view (the strict
        // check set up in GhostTypeIMEService.onCreate). If that contract
        // is broken (user backed out, opened another app, etc.) we just
        // skip this send — typing pauses gracefully until the user taps
        // back into a chat field.
        if (injector == null) {
            _state.value = _state.value.copy(
                lastError = "Send skipped — GhostType keyboard is not in a chat field."
            )
            return false
        }
        val acc = GhostTypeAccessibilityService.instance
        // ===== Foreground-app guard (issue #5: rogue clicks on launcher) =====
        // Even with the IME-injector guard above, the user reported the
        // pointer firing into the OS launcher or back into GhostType's
        // own UI when they manually minimized the chat app. Belt-and-
        // braces check: if the foreground package is a known home
        // launcher, the system UI, the recents/screen-off, or our own
        // app, refuse to send. The typer just shows a soft error until
        // the user is back inside the chat app.
        val fg = acc?.currentForegroundPackage().orEmpty()
        if (fg.isNotEmpty() && shouldBlockSendForPackage(fg)) {
            _state.value = _state.value.copy(
                lastError = "Send skipped — open the chat app's message field first."
            )
            return false
        }
        val clickCount = if (multiClick) 3 else 1
        /** Dispatch a tap at [x],[y] using whichever gesture service is connected. */
        fun gestureClickAt(x: Float, y: Float): Boolean =
            acc?.clickAt(x, y)
                ?: GhostTypePointerService.instance?.clickAt(x, y)
                ?: false

        suspend fun pointerClick(): Boolean {
            if (px < 0 || py < 0) return false
            // Require at least one gesture service to be running.
            if (acc == null && GhostTypePointerService.instance == null) return false
            // ROOT CAUSE OF "POINTER CLICK NOT WORKING":
            //   The floating dot sits at exactly (px, py) — the same spot we
            //   want to tap. On Android 9+, accessibility gestures can still
            //   land on TYPE_APPLICATION_OVERLAY windows even when those
            //   windows are FLAG_NOT_TOUCHABLE, so the SEND button under the
            //   dot never sees the touch.
            //
            //   Fix: have the overlay service detach the dot from the window
            //   stack entirely, dispatch the gesture only AFTER giving the
            //   main looper enough time to process the removeView() call,
            //   then re-add the dot.
            // If the user set a custom click delay, honour it now —
            // BEFORE hiding the dot so the dot stays visible during the
            // user-facing wait (gives visual feedback that the engine is
            // counting down) and is hidden only for the actual tap window.
            if (pointerClickDelayMs > 0) delay(pointerClickDelayMs)
            FloatingPointerService.instance?.temporarilyHideForClick(900L)
            // 650 ms gives the main-looper removeView() call plenty of
            // headroom even on budget phones under heavy load (a full
            // layout pass is typically < 16 ms, but we add extra margin
            // so the dot is definitely gone before the gesture fires).
            delay(650)
            var ok = false
            repeat(clickCount) {
                ok = gestureClickAt(px.toFloat(), py.toFloat())
                // Wait longer than the stroke duration (120 ms) so the
                // gesture fully completes before we fire the next click.
                if (ok) delay(200)
            }
            // Give the chat app time to react (button animation, message
            // added to list, keyboard possibly resizing) before we move on.
            if (ok) delay(300)
            return ok
        }
        return when (method) {
            "ime" -> sender?.invoke() ?: false
            "accessibility" -> acc?.pressSend() ?: false
            "pointer" -> pointerClick()
            else -> {
                // ===== SMART AUTO-DETECT (default) =====
                // Look at the foreground app and split into two routes:
                //   (a) Known chat apps  → click the visible Send / paper-
                //       plane button via accessibility (live position, so
                //       the button moving up/down when the keyboard opens
                //       or closes doesn't matter).
                //   (b) Everything else  → fire the field's IME action:
                //       real KEYCODE_ENTER + performEditorAction fallback.
                val pkg = acc?.currentForegroundPackage()
                val isChat = GhostTypeAccessibilityService.instance
                    ?.isKnownChatApp(pkg) == true

                if (isChat) {
                    // (a) Chat app branch.
                    //
                    // Try the send button up to 3 times — right after the
                    // text commit some apps (WhatsApp, Telegram) take a
                    // frame or two to swap the mic icon for the send icon
                    // and to actually mark it clickable. Without retries
                    // the very first send of a session can miss.
                    var sentViaAcc = false
                    repeat(3) { attempt ->
                        if (acc?.pressSend() == true) { sentViaAcc = true; return@repeat }
                        delay(150L)
                    }
                    if (sentViaAcc) return true
                    // All 3 accessibility attempts failed — fall back to the
                    // floating pointer if the user enabled it. This covers:
                    //   • Chat-app updates that change resource IDs before
                    //     our per-app id list catches up.
                    //   • Apps that render the send button inside a Compose
                    //     or WebView surface with no accessibility resource id.
                    if (pointerOn && pointerClick()) return true
                    // Don't fire IME Enter here — in a multi-line chat
                    // field that just inserts "\n" instead of sending.
                    return false
                }

                // (b) Non-chat app → IME action (browser GO / search box
                //     SEARCH / login form DONE / SMS-style single-line).
                if (sender?.invoke() == true) return true

                // (c) Last-resort: accessibility scan (handles odd cases
                //     where currentForegroundPackage returned null during
                //     a window transition but the chat app is right there).
                if (acc?.pressSend() == true) return true

                // (d) Optional floating pointer fallback for unsupported
                //     apps — only when the user explicitly enabled it.
                if (pointerOn && pointerClick()) return true

                false
            }
        }
    }

    private suspend fun sendLine(line: String, charDelayMs: Long): Boolean {
        // Real per-character typing — keys "press" one at a time so the keyboard
        // stays open and it looks like a human typing instead of paste.
        // Iterate over Unicode code points so emoji surrogate pairs stay intact.
        // Re-fetch the injector per character so if the GhostType keyboard becomes
        // active mid-line (user opens chat app), typing kicks in immediately.
        // We NEVER fall back to clipboard — that was the "loop me copy" bug.
        if (!waitForInjector(timeoutMs = 60_000L)) {
            _state.value = _state.value.copy(
                lastError = "Open GhostType keyboard inside the target app first " +
                        "(tap the message field). Direct typing needs the keyboard to be active."
            )
            return false
        }
        // Clear any earlier "waiting" message now that we're really typing.
        _state.value = _state.value.copy(lastError = null)
        var idx = 0
        while (idx < line.length) {
            val cp = line.codePointAt(idx)
            val ch = String(Character.toChars(cp))
            val inj = injector ?: run {
                if (!waitForInjector(timeoutMs = 15_000L)) return false
                injector ?: return false
            }
            val sent = inj(ch)
            if (!sent) {
                // Wait briefly and retry once — user might be switching apps.
                delay(400)
                if (injector?.invoke(ch) != true) return false
            }
            // Per-key animation hook — runs on UI thread inside the IME.
            try { onKeyPressed?.invoke(ch) } catch (_: Throwable) {}
            idx += Character.charCount(cp)
            if (charDelayMs > 0 && idx < line.length) delay(charDelayMs)
            while (_state.value.paused) delay(200)
            if (!coroutineContext.isActive) return false
        }
        return true
    }

    /** Wait until the IME injector becomes available (user opens GhostType keyboard). */
    private suspend fun waitForInjector(timeoutMs: Long): Boolean {
        if (injector != null) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        var notified = false
        while (System.currentTimeMillis() < deadline) {
            if (!coroutineContext.isActive) return false
            if (injector != null) return true
            if (!notified) {
                _state.value = _state.value.copy(
                    lastError = "Waiting for GhostType keyboard — open the target app and tap the message field…"
                )
                notified = true
            }
            delay(250)
        }
        return injector != null
    }

    fun pause() { _state.value = _state.value.copy(paused = true) }
    fun resume() { _state.value = _state.value.copy(paused = false) }
    fun stop() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(running = false, paused = false)
    }
    /** Stop and persist the current line so the next Start resumes from here. */
    fun stopAndSave(ctx: android.content.Context) {
        val resumeAt = (_state.value.current - 1).coerceAtLeast(0)
        stop()
        SettingsStore.prefs(ctx).edit()
            .putInt(SettingsStore.KEY_AT_START_LINE, resumeAt)
            .apply()
    }
    fun clearMessages(ctx: android.content.Context) {
        stop()
        lines = emptyList()
        SettingsStore.prefs(ctx).edit()
            .remove(SettingsStore.KEY_AT_CUSTOM_TEXT)
            .remove(SettingsStore.KEY_AT_LAST_FILE)
            .remove(SettingsStore.KEY_AT_LAST_FILE_NAME)
            .putInt(SettingsStore.KEY_AT_START_LINE, 0)
            .apply()
        _state.value = _state.value.copy(total = 0, current = 0, currentLine = "", sourceName = "", lastError = "")
    }

    /**
     * Foreground-package allowlist gate. Returns `true` when the package
     * is one we MUST refuse to type/click into:
     *   • Our own app (com.ghosttype / com.ghosttype.*) — the user
     *     reported clicks landing on GhostType's settings screen.
     *   • Common home launchers (Pixel, Samsung, MIUI, OneUI, Nova,
     *     Microsoft, OnePlus, Realme) — random app icons would launch.
     *   • The Android system UI (notification shade / recents screen).
     *   • A blank package string (window transition, lock screen, etc.).
     */
    private fun shouldBlockSendForPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        if (pkg.startsWith("com.ghosttype")) return true
        if (pkg in BLOCKED_PACKAGES) return true
        // Heuristic: any package whose name ends with ".launcher" or
        // contains ".launcher." is almost certainly a home screen.
        if (pkg.endsWith(".launcher") || pkg.contains(".launcher.")) return true
        return false
    }

    private val BLOCKED_PACKAGES = setOf(
        // Android system / shell
        "com.android.systemui",
        "com.android.settings",
        // Stock + AOSP launchers
        "com.google.android.apps.nexuslauncher",   // Pixel
        "com.android.launcher",
        "com.android.launcher2",
        "com.android.launcher3",
        // OEM launchers
        "com.sec.android.app.launcher",            // Samsung One UI
        "com.miui.home",                           // Xiaomi MIUI
        "com.mi.android.globallauncher",           // POCO
        "com.huawei.android.launcher",             // Huawei EMUI
        "com.bbk.launcher2",                       // Vivo
        "com.oppo.launcher",                       // Oppo
        "com.coloros.launcher",                    // Realme / Oppo ColorOS
        "com.realme.launcher",                     // Realme
        "com.oneplus.launcher",                    // OnePlus OxygenOS
        // Popular third-party launchers
        "com.teslacoilsw.launcher",                // Nova
        "com.microsoft.launcher",                  // Microsoft
        "com.actionlauncher.playstore",            // Action
        "ginlemon.flowerfree", "ginlemon.flowerpro" // Smart Launcher
    )
}
