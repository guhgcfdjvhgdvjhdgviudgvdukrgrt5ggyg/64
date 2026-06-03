package com.ghosttype.utils

import android.content.ClipboardManager
import android.content.Context
import com.ghosttype.data.db.AppDatabase
import com.ghosttype.data.db.ClipboardItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ClipboardWatcher(private val ctx: Context) {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: return@OnPrimaryClipChangedListener
        if (text.isBlank()) return@OnPrimaryClipChangedListener
        // ===== De-duplication (issue #6: "copy karne pe 2 baar entry") =====
        // Three sources can spam the same text into clipboard_items:
        //   1. Some OEM ROMs (Xiaomi MIUI, OnePlus OxygenOS) fire the
        //      OnPrimaryClipChangedListener twice for a single user copy.
        //   2. Re-copying from our own keyboard popup calls
        //      cm.setPrimaryClip(...), which trips this listener again.
        //   3. Re-copying from the Clipboard / Sentences settings screens
        //      does the same thing.
        // Skip the insert when:
        //   • the same text was already saved within the last 1.5 s, OR
        //   • the most recent row in the DB has identical text (handles
        //     the "doubled listener" case that fires more than 1.5 s
        //     apart on slow devices).
        val now = System.currentTimeMillis()
        val sinceLast = now - lastInsertedAtMs
        if (text == lastInsertedText && sinceLast < DEDUPE_WINDOW_MS) {
            return@OnPrimaryClipChangedListener
        }
        // Honor the manual suppression set by re-copy paths so a
        // setPrimaryClip immediately following a user pick doesn't
        // double-record. Window is short so legitimate re-copies of the
        // same text after a few seconds still get tracked as a fresh
        // event.
        if (now < suppressUntilMs && text == suppressedText) {
            return@OnPrimaryClipChangedListener
        }
        lastInsertedText = text
        lastInsertedAtMs = now
        scope.launch {
            val dao = AppDatabase.get(ctx).clipboardDao()
            // Belt-and-braces: also check the latest DB row in case the
            // process was just rebooted (in-memory dedupe state is empty
            // but the DB still has the previous copy).
            val latest = dao.allOnce().firstOrNull()?.text
            if (latest == text) return@launch
            dao.insert(ClipboardItem(text = text))
            // Cap to last 50 unpinned
            val count = dao.unpinnedCount()
            if (count > 50) dao.trimOldest(count - 50)
        }
    }

    fun start() {
        if (scope.isActive.not()) {
            @Suppress("DEPRECATION")
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        cm.addPrimaryClipChangedListener(listener)
    }
    fun stop() {
        cm.removePrimaryClipChangedListener(listener)
        scope.cancel()
    }

    companion object {
        /** Recent-copy window. Same text inside this window is ignored. */
        private const val DEDUPE_WINDOW_MS = 1_500L

        @Volatile private var lastInsertedText: String? = null
        @Volatile private var lastInsertedAtMs: Long = 0L

        @Volatile private var suppressedText: String? = null
        @Volatile private var suppressUntilMs: Long = 0L

        /**
         * Tell the watcher: "the next setPrimaryClip with this exact
         * text within the next ~1.5 s is intentional re-copy, don't add
         * it to history again." Call this RIGHT BEFORE the
         * cm.setPrimaryClip(...) call from any UI path (keyboard's
         * "Copy to clipboard" popup, ClipboardScreen tap, Sentences
         * tap, etc.) so the OnPrimaryClipChangedListener doesn't
         * record a fresh duplicate row.
         */
        fun suppressNext(text: String) {
            suppressedText = text
            suppressUntilMs = System.currentTimeMillis() + DEDUPE_WINDOW_MS
        }
    }
}
