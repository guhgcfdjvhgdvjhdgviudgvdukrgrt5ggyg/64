package com.ghosttype.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ghosttype.R
import com.ghosttype.data.db.AppDatabase
import com.ghosttype.ime.FloatingPointerService
import com.ghosttype.data.db.ClipboardItem
import com.ghosttype.utils.BuiltInFonts
import com.ghosttype.utils.FontManager
import com.ghosttype.utils.FontEntry
import com.ghosttype.utils.SettingsStore
import com.ghosttype.utils.ThemeManager
import com.ghosttype.utils.UnicodeFonts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

enum class PanelMode { KEYS, EMOJI, AUTOTYPE, TOOLS, CLIPBOARD, MATH, FYT, CAPS, POINTER, FONT }

// ── Cute keyboard sticker / number-hint data ────────────────────────────────
// NUMBER_HINTS: small number shown at top-left of Q-P keys (matches the
//   pastel keyboard aesthetic where numbers are embedded in the letter row).
private val NUMBER_HINTS = mapOf(
    "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
    "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0"
)

class KeyboardView(
    ctx: Context,
    private val onKey: (KeyDef) -> Unit,
    /** Returns the current ranked list of emoji suggestions (max 2). */
    private val getEmojiSuggestions: () -> List<String> = { emptyList() },
    /** Called when the user taps an emoji chip — inserts at cursor. */
    private val onEmojiSuggestion: (String) -> Unit = { },
    private val onClipboardOpen: () -> Unit,
    private val onSwitchLanguage: () -> Unit,
    private val onOpenSettings: () -> Unit,
    /**
     * Open a specific section of the host app. Tools-grid tiles route
     * through this.
     */
    private val onOpenSection: (String) -> Unit = { onOpenSettings() },
    /** Returns the text immediately before the cursor (or empty). */
    private val readBeforeCursor: () -> String = { "" },
    /** Replace [length] characters before the cursor with [text]. */
    private val replaceBeforeCursor: (length: Int, text: String) -> Unit = { _, _ -> },
    /** Commit the given string into the focused field via the IME injector. */
    private val commitToField: (String) -> Unit = { }
) : LinearLayout(ctx) {

    // `var` (not `val`) so reload() can pick up theme changes — without this
    // the keyboard would keep the colors captured at first construction even
    // after the user picked a new built-in theme. Bug fix for "Built in
    // theme not working".
    private var theme = ThemeManager.current(ctx)
    private var typeface: Typeface = FontManager.loadKeyTypeface(ctx)
    private val vibrator: Vibrator? = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
    private val prefs = SettingsStore.prefs(ctx)
    private var currentLayout: KbLayout = KeyboardLayouts.forLanguage(prefs.getString(SettingsStore.KEY_LANGUAGE, "en") ?: "en")
    private var shifted = false
    private var symbolsMode = false
    private var panelMode: PanelMode = PanelMode.KEYS
    private val capsMode: Boolean
        get() = prefs.getBoolean(SettingsStore.KEY_CAPS_MODE, false)

    // Single unified strip at the top of the keyboard (suggestions + toolbar).
    // The old design had two stacked rows: a separate suggestion row above
    // a separate scrolling toolbar. Per user request these are now collapsed
    // into ONE row — when the user is typing it shows suggestion words, and
    // when there's nothing to suggest it shows the toolbar icons inline. A
    // small "≡" menu button on the left is always present so the icons are
    // reachable even mid-typing via a popup menu.
    private val suggestionRow: LinearLayout
    private val panelContainer: FrameLayout
    private var atObserver: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Map of "key output → TextView" for the currently rendered keyboard
     * layout. Used by `flashKeyForChar()` so the AutoTypeEngine can briefly
     * highlight the matching key — this is the per-key press animation the
     * user sees while the auto-typer is running.
     */
    private val keyViewMap = HashMap<String, TextView>()
    private val flashHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Live (TextView, KeyDef) pairs for every CHAR-type key in the current
     * keys panel. Cleared and re-filled on every [rebuild]. Used by
     * [updateLetterCase] for fast shift-toggle repaint.
     *
     * MUST be declared BEFORE the `init {}` block — Kotlin initializes
     * properties top-to-bottom interleaved with init blocks, and `init`
     * calls `rebuild()` which calls `charKeyViews.clear()` on the very
     * first frame. If this field is below `init`, it is still null at
     * that point and we crash with NPE on `List.clear()`.
     */
    private val charKeyViews = mutableListOf<Pair<TextView, KeyDef>>()

    /**
     * v1.8 — tracks BgImageKeyDrawables created during the current
     * rebuild() pass so we can feed them the keyboard size + key offset
     * after layout. The "pending" list is filled inside the inner loop
     * (where the View doesn't exist yet) and drained immediately after
     * the View is created — so [keyImageBindings] always carries triples
     * of (real key view, its normal-state drawable, its pressed-state
     * drawable) ready for [applyContinuousKeyWallpaper].
     *
     * IMPORTANT: declared HERE (above `init`) for the same reason as
     * [charKeyViews] — `rebuild()` runs from the init block and clears
     * both lists. Putting these declarations below `init` would leave
     * them null at clear()-time → instant NPE on every single
     * onCreateInputView() call (which is exactly the crash users hit
     * after the first v1.8 build).
     */
    private val pendingKeyImageBindings = mutableListOf<Triple<View?, BgImageKeyDrawable, BgImageKeyDrawable>>()
    private val keyImageBindings = mutableListOf<Triple<View, BgImageKeyDrawable, BgImageKeyDrawable>>()

    init {
        orientation = VERTICAL
        applyKeyboardBackground()
        val padPx = dp(4)
        setPadding(padPx, padPx, padPx, padPx)

        suggestionRow = buildSuggestionRow()
        panelContainer = FrameLayout(ctx)

        // Vertical order (top → bottom):
        //   1) unified suggestion+toolbar row (single strip)
        //   2) keys panel (or emoji / autotype panel)
        addView(suggestionRow)
        addView(panelContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        rebuild()
    }

    private fun applyKeyboardBackground() {
        // Forget any previously cached bitmap so the per-key drawable
        // path doesn't paint a stale image after the user picks a new
        // wallpaper or clears it.
        bgBitmap = null
        val bgUri = prefs.getString(SettingsStore.KEY_BG_IMAGE_URI, null)
        if (!bgUri.isNullOrBlank()) {
            try {
                val opacity = (prefs.getInt(SettingsStore.KEY_BG_IMAGE_OPACITY, 60) / 100f).coerceIn(0f, 1f)
                val ins = context.contentResolver.openInputStream(Uri.parse(bgUri))
                if (ins != null) {
                    try {
                        val bmp = BitmapFactory.decodeStream(ins)
                        if (bmp != null) {
                            bgBitmap = bmp
                            val baseColor = ColorDrawable(theme.keyboardBg)
                            // ===== Custom BG must NOT inflate keyboard height (issue #4) =====
                            // BitmapDrawable.getMinimumHeight()/getMinimumWidth() return
                            // the bitmap's intrinsic pixel dimensions. Android's view
                            // measure pass treats the background's minimum size as a
                            // floor for the view's measured size — so a 1500×2000 user
                            // photo would balloon the keyboard to 2000 px tall (the
                            // exact "keyboard size badal jata hai" complaint). Wrapping
                            // the BitmapDrawable so all four "minimum/intrinsic" methods
                            // return -1 / 0 lets the keyboard keep its configured size
                            // and simply fits the image inside the available bounds.
                            val img = NoIntrinsicBitmapDrawable(resources, bmp).apply {
                                alpha = (opacity * 255).toInt().coerceIn(0, 255)
                                gravity = Gravity.BOTTOM or Gravity.FILL_HORIZONTAL
                            }
                            background = LayerDrawable(arrayOf(baseColor, img))
                            return
                        }
                    } finally {
                        ins.close()
                    }
                }
            } catch (_: Exception) {}
        }
        setBackgroundColor(theme.keyboardBg)
    }

    /**
     * BitmapDrawable wrapper that hides the bitmap's intrinsic size from the
     * view system. Used as the keyboard background so a tall/wide user photo
     * doesn't force the keyboard to grow with it. The bitmap still PAINTS at
     * its full quality (gravity decides how it fits), it just can't dictate
     * the parent view's measured size anymore.
     */
    private class NoIntrinsicBitmapDrawable(
        res: android.content.res.Resources,
        bmp: android.graphics.Bitmap
    ) : BitmapDrawable(res, bmp) {
        override fun getIntrinsicWidth(): Int = -1
        override fun getIntrinsicHeight(): Int = -1
        override fun getMinimumWidth(): Int = 0
        override fun getMinimumHeight(): Int = 0
    }

    /**
     * Cached decoded copy of the user's custom keyboard background image.
     * Populated by [applyKeyboardBackground] so the per-key
     * [BgImageKeyDrawable] (issue #2 — "image keys pr bhi apply ho")
     * doesn't have to re-decode the URI for every key on every rebuild.
     */
    private var bgBitmap: Bitmap? = null

    /**
     * Per-key background that paints the SAME bitmap on every key, scaled
     * to fit the key's bounds (center-crop) and clipped to a rounded
     * rectangle. Activated by the user's "Apply background image to keys"
     * toggle in Theme settings (issue #2).
     */
    private class BgImageKeyDrawable(
        private val bmp: Bitmap,
        private val cornerRadius: Float,
        opacity: Float
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (opacity * 255).toInt().coerceIn(0, 255)
        }
        private val path = Path()
        private val matrix = Matrix()
        private val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        init { paint.shader = shader }

        // ===== v1.8 — single continuous wallpaper across all keys =====
        // The previous build painted one center-cropped copy of the
        // bitmap inside EACH key, so the same image showed up 30+ times
        // (once per key) instead of one big wallpaper visible "through"
        // the keys (the user's exact complaint: "har key me alag pic
        // lagti ha"). Now the host view tells us the FULL keyboard size
        // and where this key sits within it, and we set up the bitmap
        // shader so it covers the whole keyboard once — each key just
        // becomes a window onto the part of that one image directly
        // behind it. Falls back to per-key center-crop while we wait
        // for the layout pass to deliver the position (single frame).
        private var kbW = 0
        private var kbH = 0
        private var keyX = 0f
        private var keyY = 0f
        private var hasWindow = false

        /**
         * Tell the drawable the full keyboard size and this key's offset
         * inside it. Called once after the keyboard's layout pass settles.
         */
        fun setKeyboardWindow(kbWidth: Int, kbHeight: Int, keyOffsetX: Float, keyOffsetY: Float) {
            kbW = kbWidth; kbH = kbHeight
            keyX = keyOffsetX; keyY = keyOffsetY
            hasWindow = kbW > 0 && kbH > 0
            recomputeMatrix()
            invalidateSelf()
        }

        private fun recomputeMatrix() {
            if (bmp.width <= 0 || bmp.height <= 0) return
            val b = bounds
            if (hasWindow) {
                // Center-crop the bitmap to fill the WHOLE keyboard area,
                // then translate it back by this key's offset so canvas
                // (0,0) for this key shows the part of the wallpaper that
                // sits behind it. Result: ONE continuous wallpaper, keys
                // are transparent windows cut out of it.
                val scale = maxOf(kbW.toFloat() / bmp.width, kbH.toFloat() / bmp.height)
                val tx = (kbW - bmp.width * scale) / 2f - keyX
                val ty = (kbH - bmp.height * scale) / 2f - keyY
                matrix.setScale(scale, scale)
                matrix.postTranslate(tx, ty)
            } else {
                // Fallback (first frame, before layout) — center-crop in
                // local key bounds so we don't render a blank flash.
                val w = b.width().toFloat()
                val h = b.height().toFloat()
                if (w <= 0 || h <= 0) return
                val scale = maxOf(w / bmp.width, h / bmp.height)
                val tx = (w - bmp.width * scale) / 2f + b.left
                val ty = (h - bmp.height * scale) / 2f + b.top
                matrix.setScale(scale, scale)
                matrix.postTranslate(tx, ty)
            }
            shader.setLocalMatrix(matrix)
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            recomputeMatrix()
            path.reset()
            path.addRoundRect(
                bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.right.toFloat(), bounds.bottom.toFloat(),
                cornerRadius, cornerRadius, Path.Direction.CW
            )
        }

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            canvas.save()
            canvas.clipPath(path)
            canvas.drawRect(bounds, paint)
            canvas.restore()
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        // v1.7 — feed the View's outline provider with the rounded shape
        // so View.elevation casts a shadow that follows the key's pill
        // outline (instead of the default rectangular one). Without this
        // override the 3D shadow appears as a rectangle around the
        // bitmap and ruins the soft pill look on per-key bg image mode.
        override fun getOutline(outline: android.graphics.Outline) {
            if (bounds.isEmpty) return
            outline.setRoundRect(bounds, cornerRadius)
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    /**
     * One toolbar / tools-panel action. [icon] is the small glyph rendered
     * inline in the suggestion strip; [name] is the long label shown in the
     * Gboard-style tools grid (`PanelMode.TOOLS`). Splitting them lets us
     * use compact icons in the cramped inline strip while still showing
     * readable names in the grid.
     */
    private data class ToolAction(
        val icon: String,
        val name: String,
        // Optional vector drawable resource. When non-null the tile / inline
        // chip renders this icon instead of the [icon] text glyph — used
        // for the font picker so it gets a proper "Aa" graphic instead of
        // the 🔤 emoji (which several Android phones rendered as a blank
        // tofu box and which couldn't be tinted to match dark themes).
        val iconRes: Int? = null,
        // v1.7 — set false for colourful icons that should NOT be
        // recoloured to match the active theme (e.g. the new generated
        // gradient font-picker icon — tinting it monochrome ruins the
        // glossy gradient the user explicitly asked for).
        val tintIcon: Boolean = true,
        val onClick: (View) -> Unit
    )
    /**
     * Full set of tiles shown in the Gboard-style tools panel
     * (`PanelMode.TOOLS`, opened by tapping the ≡ button on the suggestion
     * strip). Lays out as a 4-column grid — see [rebuildTools]. Order is
     * deliberate: the tools the user reaches for most (themes, font,
     * clipboard, auto-type) sit on the top row.
     */
    private fun toggleCapsMode() {
        val on = !capsMode
        prefs.edit().putBoolean(SettingsStore.KEY_CAPS_MODE, on).apply()
        Toast.makeText(context, if (on) "Caps ON" else "Caps OFF", Toast.LENGTH_SHORT).show()
        reload()
    }

    private fun toggleMathMode() {
        val on = !prefs.getBoolean(SettingsStore.KEY_MATH_ENABLED, false)
        prefs.edit().putBoolean(SettingsStore.KEY_MATH_ENABLED, on).apply()
        Toast.makeText(context, if (on) "Math Mode ON 🔢" else "Math Mode OFF", Toast.LENGTH_SHORT).show()
        reload()
    }

    private fun toggleFytMode() {
        val on = !prefs.getBoolean(SettingsStore.KEY_FYT_ENABLED, false)
        prefs.edit().putBoolean(SettingsStore.KEY_FYT_ENABLED, on).apply()
        Toast.makeText(context, if (on) "FYT ON" else "FYT OFF", Toast.LENGTH_SHORT).show()
        reload()
    }

    private fun allToolActions(): List<ToolAction> = listOf(
        ToolAction("🔢", if (prefs.getBoolean(SettingsStore.KEY_MATH_ENABLED, false)) "Math ✓" else "Math",
            iconRes = R.drawable.ic_tool_math, tintIcon = true) { showMathPanel() },
        ToolAction("🔁", if (prefs.getBoolean(SettingsStore.KEY_FYT_ENABLED, false)) "FYT ✓" else "FYT",
            iconRes = R.drawable.ic_tool_fyt, tintIcon = true) { showFytPanel() },
        ToolAction("⬆",  "Caps",      iconRes = R.drawable.ic_tool_caps,        tintIcon = true) { showCapsPanel() },
        ToolAction("Aa", "Font",      iconRes = R.drawable.ic_tool_font,      tintIcon = true) { showFontPanel() },
        ToolAction("📋", "Clipboard", iconRes = R.drawable.ic_tool_clipboard,  tintIcon = true) { showClipboardPanel() },
        ToolAction("💣", "Auto-Type", iconRes = R.drawable.ic_tool_autotype,   tintIcon = true) { showAutoTypePanel() },
        ToolAction("😀", "Emoji",     iconRes = R.drawable.ic_tool_emoji,      tintIcon = true) { showEmojiPanel() },
        ToolAction("🎯", pointerLabel(), iconRes = R.drawable.ic_tool_pointer, tintIcon = true) { showPointerPanel() },
        ToolAction("⌨",  "Keyboard",  iconRes = R.drawable.ic_tool_keyboard,   tintIcon = true) { showKeysPanel() },
        ToolAction("⚙",  "Settings",  iconRes = R.drawable.ic_tool_settings,   tintIcon = true) { onOpenSettings() }
    )

    private fun addToolbarButton(
        parent: LinearLayout,
        label: String,
        iconRes: Int? = null,
        tintIcon: Boolean = true,
        onClick: (View) -> Unit
    ) {
        // When the action carries a vector icon (e.g. font picker's "Aa"
        // glyph) we prefer it over the text glyph so it renders sharply
        // and gets recolored to match the active theme. Otherwise fall
        // back to the unicode label so emoji-based tiles still work.
        val view: View = if (iconRes != null) {
            ImageView(context).apply {
                setImageResource(iconRes)
                // Only tint when the caller asks for it. The new glossy
                // gradient font-picker icon is meant to keep its colours
                // — tinting would flatten the gradient to a single hue.
                if (tintIcon) setColorFilter(theme.keyText)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { onClick(it) }
            }
        } else {
            TextView(context).apply {
                text = label
                setTextColor(theme.keyText)
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                textSize = 14f
                setTypeface(typeface)
                setOnClickListener { onClick(it) }
            }
        }
        parent.addView(view, LayoutParams(dp(44), LayoutParams.MATCH_PARENT))
    }

    /** Force reload theme/font/background/keys. Call after settings change. */
    fun reload() {
        try {
            // Re-read theme from prefs FIRST — applyKeyboardBackground() and
            // every key-build path below reads `theme.*`, so without this
            // refresh a theme change wouldn't actually recolor the keys.
            theme = ThemeManager.current(context)
            typeface = FontManager.loadKeyTypeface(context)
            applyKeyboardBackground()
            removeAllViews()
            val padPx = dp(4)
            setPadding(padPx, padPx, padPx, padPx)
            // Re-attach in the new top-down order: unified strip → panel.
            addView(suggestionRow)
            addView(panelContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            when (panelMode) {
                PanelMode.KEYS -> rebuild()
                PanelMode.EMOJI -> rebuildEmoji()
                PanelMode.AUTOTYPE -> rebuildAutoType()
                PanelMode.MATH -> rebuildMath()
                PanelMode.FYT -> rebuildFyt()
                PanelMode.CAPS -> rebuildCaps()
                PanelMode.POINTER -> rebuildPointer()
                PanelMode.FONT -> rebuildFont()
                PanelMode.TOOLS -> rebuildTools()
                PanelMode.CLIPBOARD -> rebuildClipboard()
            }
            // refreshSuggestions() rebuilds the strip's contents (icons or
            // suggestion words) using the freshly-loaded theme + typeface.
            refreshSuggestions()
        } catch (_: Exception) { rebuild() }
    }

    /**
     * Builds the unified top strip: [≡ menu] [scroll area]. The scroll area
     * gets refilled on every refreshSuggestions() — either with suggestion
     * words (while the user is typing) or with the toolbar icons (when the
     * field is empty / cleared). The menu button is always present so the
     * full toolbar is reachable even mid-sentence.
     */
    private fun buildSuggestionRow(): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(42))
            setBackgroundColor(theme.suggestionBg)
        }
        // Left-side menu button — opens a popup containing every toolbar
        // action so the user can still reach AT / clipboard / settings even
        // while suggestions are filling the strip.
        val menuBtn = TextView(context).apply {
            text = "≡"
            setTextColor(theme.accent)
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(10), 0)
            textSize = 20f
            setTypeface(typeface)
            setOnClickListener { showToolsPanel() }
        }
        container.addView(menuBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        // Caps indicator badge
        val capsBadge = TextView(context).apply {
            text = "CAPS"
            setTextColor(theme.accent)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            textSize = 11f
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setBackgroundColor(Color.argb(40, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent)))
            visibility = if (capsMode) VISIBLE else GONE
        }
        container.addView(capsBadge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        // Scrollable content area (suggestions OR inline icons)
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
        }
        val inner = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER_VERTICAL
        }
        scroll.addView(inner)
        container.addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        container.tag = inner
        return container
    }

    fun refreshSuggestions() {
        val inner = suggestionRow.tag as LinearLayout
        inner.removeAllViews()
        val emojis = getEmojiSuggestions()
        if (emojis.isEmpty()) return
        emojis.forEach { emoji ->
            val tv = TextView(context).apply {
                text = emoji
                setTextColor(theme.keyText)
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                textSize = 20f
                setOnClickListener { onEmojiSuggestion(emoji) }
            }
            inner.addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
    }

    /**
     * Open the Gboard-style tools panel inside the keyboard area. Replaces
     * the old floating popup that anchored above the ≡ button — the user
     * specifically asked for the menu to "open in the keyboard like Gboard"
     * with a tile grid (see attached screenshot reference). Cancels any
     * Auto-Type observer first so we don't keep firing rebuilds at this
     * panel from the engine's StateFlow.
     */
    private fun showToolsPanel() {
        panelMode = PanelMode.TOOLS
        atObserver?.cancel(); atObserver = null
        rebuildTools()
    }

    /**
     * Render the tools panel: a 4-column grid of icon-over-label tiles
     * (Themes / Font / Clipboard / Auto-Type / Language / Emoji / Keyboard
     * / Settings). Lays out inside `panelContainer` so it occupies the
     * exact same on-screen real estate as the keys panel — the user sees
     * a clean swap, not an overlay.
     *
     * Sized to the same total height as the keys panel
     * (`keyHeight × 5`) so the keyboard window doesn't grow / shrink
     * jarringly when the user opens the tools view.
     */
    private fun rebuildTools() {
        panelContainer.removeAllViews()
        val gapOuter = dp(12)
        val keyHeight = prefs.getInt(SettingsStore.KEY_KEY_HEIGHT_DP, 50).coerceIn(36, 80)
        val totalH = dp(keyHeight) * 5

        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, totalH)
            setPadding(gapOuter, dp(10), gapOuter, dp(10))
            setBackgroundColor(theme.keyboardBg)
        }

        // ── "Tools" header ────────────────────────────────────
        root.addView(TextView(context).apply {
            text = "Tools"
            setTextColor(theme.accent)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), 0, 0, dp(10))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── 4-column icon-only grid ────────────────────────────
        val tools = allToolActions()
        val cols  = 4
        val rows  = (tools.size + cols - 1) / cols
        val gapTile = dp(6)

        // Lock Font, Auto-Type, and Pointer tiles when the user's plan has expired
        val planExpired = try { SettingsStore.isPlanExpired(context) } catch (_: Throwable) { false }
        val lockedNames = if (planExpired) setOf("Font", "Auto-Type", "Pointer", "Pointer ON") else emptySet()

        val grid = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        for (r in 0 until rows) {
            val rowView = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            }
            for (c in 0 until cols) {
                val idx = r * cols + c
                val params = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(gapTile, gapTile, gapTile, gapTile)
                }
                if (idx < tools.size) {
                    val tool = tools[idx]
                    val isLocked = tool.name in lockedNames
                    rowView.addView(buildToolTile(tool, locked = isLocked), params)
                } else {
                    rowView.addView(View(context), params)
                }
            }
            grid.addView(rowView)
        }
        root.addView(grid)

        // ── "Back to keyboard" pill ───────────────────────────
        val pillBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            // White-ish pill — slightly tinted with keyboard bg so it works
            // on both light and dark themes: blend white at 85% over bg.
            val r = ((android.graphics.Color.red(theme.keyboardBg) * 0.30f) + (255 * 0.70f)).toInt().coerceIn(0, 255)
            val g = ((android.graphics.Color.green(theme.keyboardBg) * 0.30f) + (255 * 0.70f)).toInt().coerceIn(0, 255)
            val b = ((android.graphics.Color.blue(theme.keyboardBg) * 0.30f) + (255 * 0.70f)).toInt().coerceIn(0, 255)
            setColor(android.graphics.Color.rgb(r, g, b))
        }
        root.addView(TextView(context).apply {
            text = "⌨  Back to keyboard"
            setTextColor(theme.accent)
            setTypeface(null, Typeface.BOLD)
            textSize = 14f
            gravity = Gravity.CENTER
            background = pillBg
            isClickable = true; isFocusable = true
            setOnClickListener { showKeysPanel() }
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })

        panelContainer.addView(root)
    }

    /**
     * Single tool tile — icon-only, large centered icon, no text label.
     * Rounded square tile with accent tint background matching the screenshot.
     */
    private fun buildToolTile(tool: ToolAction, locked: Boolean = false): View {
        val accentArgb = if (locked) android.graphics.Color.GRAY else theme.accent

        // Tile bg: accent color at ~22% alpha — visible but soft on pastel themes
        val tileBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(android.graphics.Color.argb(
                if (locked) 30 else 56,
                android.graphics.Color.red(accentArgb),
                android.graphics.Color.green(accentArgb),
                android.graphics.Color.blue(accentArgb)
            ))
        }

        // Pressed state: only meaningful when not locked
        val pressedBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(android.graphics.Color.argb(
                if (locked) 30 else 100,
                android.graphics.Color.red(accentArgb),
                android.graphics.Color.green(accentArgb),
                android.graphics.Color.blue(accentArgb)
            ))
        }
        val stateList = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressedBg)
            addState(intArrayOf(), tileBg)
        }

        // Use a FrameLayout as outer wrapper so we can overlay the lock badge
        val wrapper = FrameLayout(context).apply {
            background = stateList
            isClickable = !locked
            isFocusable = !locked
            alpha = if (locked) 0.45f else 1f
            if (!locked) setOnClickListener { doFeedback(this); tool.onClick(it) }
        }

        // Inner vertical LinearLayout: icon + label stacked
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(5), dp(4), dp(4))
        }

        // Icon: vector drawable tinted with accent, or emoji text fallback
        val iconView: View = if (tool.iconRes != null) {
            ImageView(context).apply {
                setImageResource(tool.iconRes)
                setColorFilter(accentArgb)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        } else {
            TextView(context).apply {
                text = tool.icon
                textSize = 20f
                gravity = Gravity.CENTER
            }
        }
        tile.addView(iconView, LinearLayout.LayoutParams(dp(24), dp(24)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // Label below the icon
        val labelView = TextView(context).apply {
            text = tool.name
            textSize = 9.5f
            gravity = Gravity.CENTER
            setTextColor(accentArgb)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        tile.addView(labelView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        wrapper.addView(tile, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 🔒 Lock badge — small icon in top-right corner when locked
        if (locked) {
            val lockBadge = TextView(context).apply {
                text = "🔒"
                textSize = 9f
                gravity = Gravity.CENTER
                setPadding(0, dp(4), dp(4), 0)
            }
            wrapper.addView(lockBadge, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ))
        }

        return wrapper
    }

    fun setLanguage(code: String) {
        prefs.edit().putString(SettingsStore.KEY_LANGUAGE, code).apply()
        currentLayout = KeyboardLayouts.forLanguage(code)
        symbolsMode = false
        showKeysPanel()
    }

    private fun pointerLabel(): String {
        val on = SettingsStore.prefs(context).getBoolean(SettingsStore.KEY_POINTER_ENABLED, false)
        if (!on) return "Pointer"
        val multi = SettingsStore.prefs(context).getBoolean(SettingsStore.KEY_POINTER_MULTI_CLICK, false)
        return if (multi) "Multi" else "Single"
    }

    private fun togglePointerMode() {
        val p = SettingsStore.prefs(context)
        val on = p.getBoolean(SettingsStore.KEY_POINTER_ENABLED, false)
        if (!on) {
            p.edit().putBoolean(SettingsStore.KEY_POINTER_ENABLED, true)
                .putBoolean(SettingsStore.KEY_POINTER_MULTI_CLICK, false).apply()
            FloatingPointerService.start(context)
        } else {
            val multi = p.getBoolean(SettingsStore.KEY_POINTER_MULTI_CLICK, false)
            if (!multi) {
                p.edit().putBoolean(SettingsStore.KEY_POINTER_MULTI_CLICK, true).apply()
            } else {
                p.edit().putBoolean(SettingsStore.KEY_POINTER_ENABLED, false).apply()
                FloatingPointerService.stop(context)
            }
        }
        // If the tools panel is currently open, rebuild it to reflect the new label.
        if (panelMode == PanelMode.TOOLS) rebuildTools()
    }

    /**
     * Shift toggle. Critical perf-fix: instead of running the full [rebuild]
     * (which destroys & re-creates ~30 TextViews + their backgrounds + their
     * touch listeners), we just walk the existing CHAR-key TextViews and
     * flip their text upper/lower case. On a mid-range device this drops
     * the per-tap shift cost from ~25ms to <1ms — the user reported the
     * keyboard "felt slow / laggy"; that lag was the rebuild storm.
     *
     * Symbols toggle still goes through full [rebuild] because the layout
     * itself changes (different keys, different widths).
     */
    fun toggleShift() {
        shifted = !shifted
        if (panelMode == PanelMode.KEYS) updateLetterCase()
    }
    fun toggleSymbols() { symbolsMode = !symbolsMode; if (panelMode == PanelMode.KEYS) rebuild() }

    /**
     * Programmatically set the shift state — called by GhostTypeIMEService
     * when auto-capitalization decides the next character should be upper
     * case (start of field, right after `.`/`?`/`!`/`۔` + space, or right
     * after a newline). Early-returns when the state hasn't actually
     * changed so we never trigger a needless re-layout from
     * `onUpdateSelection` floods.
     */
    fun setShifted(s: Boolean) {
        if (shifted == s) return
        shifted = s
        if (panelMode == PanelMode.KEYS) updateLetterCase()
    }

    /** Read-only accessor so the IME service can inspect the current state. */
    fun isShifted(): Boolean = shifted

    /**
     * Walk every CHAR-type key currently on screen and flip its visible
     * text between lowercase and the layout's natural label (which is
     * uppercase on the lettered rows). Only mutates `tv.text` — no view
     * recreation, no background re-allocation, no listener re-binding —
     * so it's cheap enough to call on every cursor move from
     * `onUpdateSelection` without making typing feel sticky.
     *
     * `charKeyViews` is populated by [rebuild] in lock-step with the views
     * it inflates, so the references here are always live for the current
     * keys panel.
     */
    private fun updateLetterCase() {
        if (panelMode != PanelMode.KEYS) return
        for ((tv, key) in charKeyViews) {
            tv.text = if (shifted) key.label.uppercase() else key.label
        }
    }

    private fun activeLayout(): KbLayout = if (symbolsMode) KeyboardLayouts.SYMBOLS else currentLayout

    private fun showKeysPanel() {
        panelMode = PanelMode.KEYS
        atObserver?.cancel(); atObserver = null
        rebuild()
    }

    /**
     * v1.10 — public entry point for the IME service to force the keyboard
     * back to the KEYS panel every time it's shown. Without this, the
     * KeyboardView instance is reused across show/hide cycles (Android
     * recycles the view returned from onCreateInputView), so closing the
     * keyboard while on Emoji / Auto-Type / Tools meant the next open
     * still landed on that panel — which the user found disorienting.
     * Now every onStartInputView call resets the panel to keys.
     *
     * No-op when already on the KEYS panel so we don't waste a rebuild
     * on the common case (most opens already start there).
     */
    fun resetToKeysPanel() {
        if (panelMode == PanelMode.KEYS) return
        showKeysPanel()
    }

    private fun showEmojiPanel() {
        panelMode = PanelMode.EMOJI
        atObserver?.cancel(); atObserver = null
        rebuildEmoji()
    }

    private fun showAutoTypePanel() {
        panelMode = PanelMode.AUTOTYPE
        rebuildAutoType()
        atObserver?.cancel()
        atObserver = scope.launch {
            AutoTypeEngine.state.collectLatest { rebuildAutoType() }
        }
    }

    private fun showMathPanel() {
        panelMode = PanelMode.MATH
        rebuildMath()
    }

    private fun showFytPanel() {
        panelMode = PanelMode.FYT
        rebuildFyt()
    }

    private fun showCapsPanel() {
        panelMode = PanelMode.CAPS
        rebuildCaps()
    }

    private fun showPointerPanel() {
        panelMode = PanelMode.POINTER
        rebuildPointer()
    }

    private fun showFontPanel() {
        panelMode = PanelMode.FONT
        rebuildFont()
    }

    private fun rebuild() {
        panelContainer.removeAllViews()
        // Default key height bumped 50→58 dp + min raised 36→44 dp so the
        // out-of-the-box keys feel comfortably tall (user complaint:
        // "keys boht choti hain, thori or bari kro"). Max kept at 90 dp
        // so power users on tablets can still go bigger.
        val keyHeight = prefs.getInt(SettingsStore.KEY_KEY_HEIGHT_DP, 58).coerceIn(44, 90)
        val rowHeight = dp(keyHeight)
        val keyTextSize = prefs.getInt(SettingsStore.KEY_KEY_TEXT_SIZE, 17).toFloat()
        val storedBorder = prefs.getString(SettingsStore.KEY_BORDER_STYLE, "rounded") ?: "rounded"
        val storedOpacity = prefs.getInt(SettingsStore.KEY_KEY_OPACITY, 100) / 100f
        val hasBgImage = !prefs.getString(SettingsStore.KEY_BG_IMAGE_URI, null).isNullOrBlank()
        val showBordersOverBg = prefs.getBoolean(SettingsStore.KEY_BG_SHOW_BORDERS, false)
        // ===== Per-key bg image (issue #2) =====
        // When the user enables "Apply background image to keys", every
        // key gets the same wallpaper as its background (clipped to its
        // rounded shape). We still keep the keyboard-wide image too — the
        // toggle is additive.
        val bgImageOnKeys = prefs.getBoolean(SettingsStore.KEY_BG_IMAGE_ON_KEYS, false)
        val applyBgToKeys = bgImageOnKeys && hasBgImage && bgBitmap != null
        // When custom background image is active, default to transparent borderless keys
        // so the picture shows through. User can re-enable borders in Theme settings.
        val borderStyle = if (hasBgImage && !showBordersOverBg && !applyBgToKeys) "none" else storedBorder
        val keyOpacity = if (hasBgImage && !showBordersOverBg && !applyBgToKeys) 0f else storedOpacity
        val perKeyBgOpacity = (prefs.getInt(SettingsStore.KEY_BG_IMAGE_OPACITY, 60) / 100f).coerceIn(0.2f, 1f)
        // ===== v1.7 — per-key spacing slider =====
        // Default 3 dp gives a tight Gboard-class packed grid. Earlier
        // v1.6 hard-coded 6 dp made the keys *look* smaller because
        // each key lost ~12 dp of width to gaps. Slider goes 1..8 dp.
        val keyMarginPx = dp(prefs.getInt(SettingsStore.KEY_KEY_MARGIN_DP, 3).coerceIn(1, 8))
        // ===== v1.7 — Gboard-style 3D shadow =====
        // ON (default) = each key gets View.elevation so Android renders
        // a soft drop-shadow underneath, and the touch listener animates
        // the elevation down to 0 on press for a real "pressed in" feel.
        val use3dShadow = prefs.getBoolean(SettingsStore.KEY_KEY_3D_SHADOW, true)
        val restElevationPx = if (use3dShadow) dp(3).toFloat() else 0f

        val keys = LinearLayout(context).apply { orientation = VERTICAL }

        // Re-build key registry every layout pass so flashKeyForChar() resolves
        // against the currently visible keys (handles language switch etc.).
        keyViewMap.clear()
        // v1.8 — drop any per-key wallpaper bindings from the previous
        // rebuild so the list doesn't grow without bound and stale views
        // (now detached) don't get position queries.
        pendingKeyImageBindings.clear()
        keyImageBindings.clear()
        // ===== PERF =====
        // Char-key registry (tv ↔ KeyDef) used by [updateLetterCase] for
        // the lightweight shift-toggle path. Cleared here to stay in sync
        // with the views we're about to inflate.
        charKeyViews.clear()
        val normalBg = theme.keyBg
        val pressedBg = theme.pressedKey

        activeLayout().rows.forEach { row ->
            val rowView = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, rowHeight)
            }
            row.forEach { key ->
                // ===== PERF =====
                // Pre-build the normal + pressed-state drawables ONCE per
                // key here. Previously the touch listener recreated a fresh
                // GradientDrawable on every ACTION_DOWN and ACTION_UP — two
                // allocations per keystroke × hundreds of keystrokes per
                // minute = noticeable GC stalls (≈8-12 ms hitches the user
                // described as "slow / laggy" typing). Caching reuses the
                // exact same drawable instances across taps so the touch
                // listener now just swaps two object references.
                val normalBgDrawable: Drawable
                val pressedBgDrawable: Drawable
                if (applyBgToKeys) {
                    // Per-key bitmap drawable. The pressed state reuses the
                    // same image but tints it darker via a LayerDrawable
                    // overlay so the user still gets visual feedback.
                    val cornerR = when (borderStyle) {
                        "none" -> 0f
                        "thin" -> dp(4).toFloat()
                        "thick" -> dp(2).toFloat()
                        else -> dp(8).toFloat()
                    }
                    val bmp = bgBitmap!!
                    val normalBmpDrawable = BgImageKeyDrawable(bmp, cornerR, perKeyBgOpacity)
                    val pressedBmpDrawable = BgImageKeyDrawable(bmp, cornerR, perKeyBgOpacity)
                    normalBgDrawable = normalBmpDrawable
                    val pressedOverlay = makeKeyBackground(
                        // Press state = bg image + 35 % darkening overlay,
                        // mimics Gboard's "tap glow" without hiding the
                        // wallpaper underneath.
                        Color.argb(90, Color.red(pressedBg), Color.green(pressedBg), Color.blue(pressedBg)),
                        borderStyle, 1f
                    )
                    pressedBgDrawable = LayerDrawable(arrayOf(
                        pressedBmpDrawable,
                        pressedOverlay
                    ))
                    // v1.8 — register this key so we can feed it the
                    // keyboard size + its offset once layout finishes.
                    // Without this the BgImageKeyDrawable would just
                    // center-crop in its own bounds and the user would
                    // see the same picture stamped on every single key.
                    pendingKeyImageBindings.add(
                        Triple(null, normalBmpDrawable, pressedBmpDrawable)
                    )
                } else {
                    normalBgDrawable  = makeKeyBackground(normalBg,  borderStyle, keyOpacity)
                    pressedBgDrawable = makeKeyBackground(pressedBg, borderStyle, keyOpacity)
                }

                val tv = TextView(context).apply {
                    val display = when {
                        // v1.10 — space-bar branding label is XOR-encrypted
                        // (ObfConstants.SPACE_LABEL bound to the keystore SHA).
                        // Decoded here at render time so the literal "I love
                        // CHAND" string is NOT visible inside the APK to anyone
                        // editing it with apk-tools. Decode failure (shouldn't
                        // happen in a properly-signed release) falls back to a
                        // single space so the bar is still tappable.
                        key.type == KeyType.SPACE -> com.ghosttype.security.Obf
                            .decode(context, com.ghosttype.security.ObfConstants.SPACE_LABEL)
                            .ifBlank { " " }
                        key.type == KeyType.SHIFT -> if (capsMode) "⇧*" else key.label
                        else -> if (shifted && key.type == KeyType.CHAR) key.label.uppercase() else key.label
                    }
                    text = display
                    setTextColor(theme.keyText)
                    gravity = Gravity.CENTER
                    setTypeface(typeface)
                    textSize = keyTextSize
                    background = normalBgDrawable
                    val mPx = dp(2)
                    setPadding(mPx, mPx, mPx, mPx)
                    // ===== v1.7 — baseline 3D shadow under each key =====
                    // We use View.elevation (with outlineProvider derived
                    // from the bg drawable's rounded shape) so Android
                    // renders a real soft drop shadow — same technique
                    // Gboard uses for its "pill" key look. Skip the
                    // outline override when applyBgToKeys is on, because
                    // BgImageKeyDrawable's clipPath already defines the
                    // visual shape and the system's auto-outline gives a
                    // matching shadow.
                    if (use3dShadow) {
                        elevation = restElevationPx
                        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                        clipToOutline = false
                    }
                    // Tag carries: (a) the per-key cached drawables so
                    // [flashKeyForChar] and the touch listener can swap
                    // backgrounds without allocating, and (b) the styling
                    // info needed when the theme/background changes.
                    tag = KeyStyle(borderStyle, keyOpacity, normalBgDrawable, pressedBgDrawable)
                    setOnTouchListener { v, ev ->
                        when (ev.action) {
                            MotionEvent.ACTION_DOWN -> {
                                // Reuse the cached pressed-state drawable
                                // — no per-touch allocation.
                                v.background = pressedBgDrawable
                                // ===== v1.7 — Gboard-style press-IN animation =====
                                // The key now PRESSES INTO the surface
                                // (scale ↓ + translationZ ↓) instead of
                                // floating up. Combined with the rest-
                                // state elevation this produces the
                                // tactile "button pressed" feel of
                                // Gboard / SwiftKey instead of the
                                // backwards "key floats up" effect the
                                // user complained about.
                                v.animate().cancel()
                                v.animate()
                                    .scaleX(0.96f).scaleY(0.96f)
                                    .translationZ(-restElevationPx)
                                    .setInterpolator(DecelerateInterpolator())
                                    .setDuration(60).start()
                                doFeedback(v)
                                handleKeyTap(key)
                                // ===== Backspace press-and-hold (issue #3) =====
                                // After ~350 ms of holding ⌫, start firing
                                // additional delete events on a repeat loop
                                // that ACCELERATES with hold time:
                                //   • starts at 80 ms / delete (gentle),
                                //   • ramps down to 25 ms / delete after
                                //     ~30 deletes (≈ 1.5 s of holding),
                                // so a quick tap is still one character but
                                // a long press wipes a whole sentence in
                                // a couple of seconds. Released on ACTION_UP
                                // / ACTION_CANCEL below.
                                if (key.type == KeyType.BACKSPACE) {
                                    startBackspaceRepeat(key)
                                }
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                v.background = normalBgDrawable
                                // Spring the key back to rest with a
                                // gentle overshoot — feels like a real
                                // physical key bouncing back.
                                v.animate().cancel()
                                v.animate()
                                    .scaleX(1f).scaleY(1f)
                                    .translationZ(0f)
                                    .setInterpolator(OvershootInterpolator(1.6f))
                                    .setDuration(120).start()
                                if (key.type == KeyType.BACKSPACE) {
                                    stopBackspaceRepeat()
                                }
                                true
                            }
                            else -> false
                        }
                    }
                    // ===== Long-press char alternates =====
                    // BACKSPACE is intentionally NOT given a long-click
                    // listener anymore — its press-and-hold behaviour is
                    // handled inline above (continuous delete with
                    // acceleration, the user's explicit request). The old
                    // Select-All / Copy / Cut / Paste menu is still
                    // reachable through the keyboard's ≡ menu, so the
                    // editor actions aren't lost.
                    if (key.type != KeyType.BACKSPACE && !key.longPress.isNullOrEmpty()) {
                        setOnLongClickListener {
                            onKey(key.copy(output = key.longPress.first().toString()))
                            true
                        }
                    }
                }
                // Register both the lowercase and shifted-uppercase form so
                // AutoType matches "h" against the [h] key whether the
                // keyboard is currently in lowercase or shifted state.
                if (key.type == KeyType.CHAR) {
                    keyViewMap[key.output.lowercase()] = tv
                    keyViewMap[key.output.uppercase()] = tv
                    charKeyViews.add(tv to key)
                } else if (key.type == KeyType.SPACE) {
                    keyViewMap[" "] = tv
                } else if (key.output.isNotEmpty()) {
                    keyViewMap[key.output] = tv
                }
                val params = LayoutParams(0, LayoutParams.MATCH_PARENT, key.widthWeight)
                params.setMargins(keyMarginPx, keyMarginPx, keyMarginPx, keyMarginPx)

                // ── Number-hint overlays ──────────────────
                val numHint = if (key.type == KeyType.CHAR && !applyBgToKeys)
                    NUMBER_HINTS[key.output.lowercase()] else null

                if (numHint != null) {
                    val frame = FrameLayout(context).apply {
                        clipChildren = false
                        clipToPadding = false
                    }
                    tv.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    frame.addView(tv)
                    frame.addView(TextView(context).apply {
                        text = numHint
                        textSize = 9f
                        setTextColor((theme.keyText and 0x00FFFFFF) or 0x99000000.toInt())
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.START
                        ).also { it.setMargins(dp(5), dp(4), 0, 0) }
                    })
                    rowView.addView(frame, params)
                } else {
                    rowView.addView(tv, params)
                }
                // v1.8 — if we just registered an image-key binding for
                // this key, attach the actual View now (we couldn't pass
                // it earlier because tv is created inside the same loop).
                if (applyBgToKeys && pendingKeyImageBindings.isNotEmpty()) {
                    val last = pendingKeyImageBindings.removeAt(pendingKeyImageBindings.size - 1)
                    if (last.first == null) {
                        keyImageBindings.add(Triple(tv, last.second, last.third))
                    } else {
                        // Shouldn't happen, but keep it safe.
                        pendingKeyImageBindings.add(last)
                    }
                }
            }
            keys.addView(rowView)
        }
        panelContainer.addView(keys, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        refreshSuggestions()
        // v1.8 — once the keyboard has been laid out, walk every image-
        // key we registered above and tell its drawable the full
        // keyboard size + the key's position inside it. That turns the
        // 30 independent "stamped" images into one continuous wallpaper
        // visible THROUGH each key. Done via post() so we run after the
        // layout pass settles every TextView's left/top.
        if (applyBgToKeys && keyImageBindings.isNotEmpty()) {
            this.post { applyContinuousKeyWallpaper() }
        }
    }

    private fun applyContinuousKeyWallpaper() {
        val kw = width
        val kh = height
        if (kw <= 0 || kh <= 0) {
            // Layout still pending — try once more on the next frame.
            this.post { applyContinuousKeyWallpaper() }
            return
        }
        val tmp = Rect()
        keyImageBindings.forEach { (v, normalBmp, pressedBmp) ->
            tmp.set(0, 0, v.width, v.height)
            // Translates the rect from v's coord space into ours
            // (KeyboardView), so tmp.left/tmp.top == v's offset within
            // the full keyboard.
            this.offsetDescendantRectToMyCoords(v, tmp)
            normalBmp.setKeyboardWindow(kw, kh, tmp.left.toFloat(), tmp.top.toFloat())
            pressedBmp.setKeyboardWindow(kw, kh, tmp.left.toFloat(), tmp.top.toFloat())
        }
    }

    /**
     * Per-key cached state stored in `View.tag`. The drawable references
     * let the touch handler and `flashKeyForChar()` swap visuals without
     * allocating new GradientDrawables on every press.
     */
    private data class KeyStyle(
        val borderStyle: String,
        val opacity: Float,
        val normalBg: android.graphics.drawable.Drawable,
        val pressedBg: android.graphics.drawable.Drawable
    )

    /**
     * Briefly highlight the key matching [ch] so the user can SEE the
     * AutoTypeEngine "press" each character on the visible keyboard. Safe
     * to call from any thread — runs on the UI handler. Uses the cached
     * pressed/normal drawables stored in the key's tag so even visualizing
     * a fast auto-type stream allocates nothing per flash.
     */
    fun flashKeyForChar(ch: String) {
        val key = ch.firstOrNull()?.toString() ?: return
        val tv = keyViewMap[key] ?: keyViewMap[key.lowercase()] ?: return
        flashHandler.post {
            val style = tv.tag as? KeyStyle ?: return@post
            // Press down — swap background + scale in + elevation drop
            tv.background = style.pressedBg
            tv.animate().cancel()
            tv.animate()
                .scaleX(0.88f)
                .scaleY(0.88f)
                .translationZ(0f)
                .setDuration(45L)
                .withEndAction {
                    // Release — scale back up + restore normal bg
                    flashHandler.postDelayed({
                        tv.background = style.normalBg
                        tv.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .translationZ(2f)
                            .setDuration(55L)
                            .start()
                    }, 55L)
                }
                .start()
        }
    }

    // ===== Backspace press-and-hold (issue #3) =====
    // The user wants holding ⌫ to delete characters continuously and the
    // delete speed to ramp up the longer they hold (so a long sentence
    // wipes in 1-2 seconds instead of having to tap dozens of times).
    // Implementation: Handler-driven repeat loop, kicks in after a short
    // initial delay so a normal tap still removes exactly one character.
    private val backspaceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var backspaceRepeats = 0
    private var backspaceRunnable: Runnable? = null

    private fun startBackspaceRepeat(key: KeyDef) {
        // Cancel any leftover loop from a previous press.
        stopBackspaceRepeat()
        backspaceRepeats = 0
        val r = object : Runnable {
            override fun run() {
                // Re-fire backspace through the regular IME pipeline so
                // selection-aware delete and stylized-glyph handling still
                // work the same as a manual tap.
                onKey(key)
                backspaceRepeats++
                // Acceleration curve: start gentle, ramp aggressive.
                //   0-3   → 80 ms  (~12.5 char/s)
                //   4-9   → 60 ms  (~16.7 char/s)
                //   10-19 → 40 ms  (~25 char/s)
                //   20+   → 25 ms  (~40 char/s, full erase speed)
                val nextDelayMs = when {
                    backspaceRepeats < 4  -> 80L
                    backspaceRepeats < 10 -> 60L
                    backspaceRepeats < 20 -> 40L
                    else                  -> 25L
                }
                backspaceHandler.postDelayed(this, nextDelayMs)
            }
        }
        backspaceRunnable = r
        // Initial 350 ms grace so a fast tap doesn't accidentally delete
        // a second character before the user lifts their finger.
        backspaceHandler.postDelayed(r, 350L)
    }

    private fun stopBackspaceRepeat() {
        backspaceRunnable?.let { backspaceHandler.removeCallbacks(it) }
        backspaceRunnable = null
        backspaceRepeats = 0
    }

    private fun handleKeyTap(key: KeyDef) {
        when (key.type) {
            KeyType.SHIFT -> toggleShift()
            KeyType.SYMBOLS -> toggleSymbols()
            KeyType.LANGUAGE -> onSwitchLanguage()
            KeyType.EMOJI -> showEmojiPanel()
            KeyType.CLIPBOARD -> onClipboardOpen()
            else -> {
                val out = if (shifted && key.type == KeyType.CHAR) key.output.uppercase() else key.output
                onKey(key.copy(output = out))
                if (shifted && key.type == KeyType.CHAR) { shifted = false; rebuild() }
            }
        }
    }

    // ===== EMOJI PANEL =====
    private fun rebuildEmoji() {
        panelContainer.removeAllViews()
        // Cap the emoji panel so it never balloons to fullscreen.
        // Layout = category strip (40dp) + scrollable grid (3 emoji rows × 44dp = 132dp)
        // + bottom bar (44dp) = ~216dp total. Internal ScrollView lets the user scroll
        // through long categories without making the keyboard taller.
        val catStripH = dp(40)
        val bottomBarH = dp(44)
        val emojiRowH = dp(44)
        val gridRows = 3
        val totalH = catStripH + (emojiRowH * gridRows) + bottomBarH
        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, totalH)
        }

        // Category strip
        val catScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(theme.suggestionBg)
        }
        val catRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        val grid = LinearLayout(context).apply {
            orientation = VERTICAL
        }

        // v1.10 — `loadCategory` now reads optional per-category column count
        // and font size from `EmojiData.Category` so the new "Lines" tab can
        // use 2 wide cells with a 13sp font (a 12-15 char divider doesn't fit
        // a normal 22sp / 8-col emoji cell — the text would truncate or look
        // micro-tiny). Single-glyph categories continue to default to 8 × 22sp.
        fun loadCategory(emojis: List<String>, perRow: Int = 8, textSizeSp: Float = 22f) {
            grid.removeAllViews()
            if (emojis.isEmpty()) return
            var rowView: LinearLayout? = null
            var cellsInRow = 0
            emojis.forEachIndexed { idx, e ->
                if (idx % perRow == 0) {
                    rowView = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(44))
                    }
                    grid.addView(rowView)
                    cellsInRow = 0
                }
                val tv = TextView(context).apply {
                    text = e
                    textSize = textSizeSp
                    // Multi-character "Lines" entries can still slightly
                    // overflow at 13sp on narrow screens; ellipsize end so
                    // the cell never breaks the row layout. Single-glyph
                    // emojis at 22sp are well under one cell's width and
                    // won't trigger any ellipsis.
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setTextColor(theme.keyText)
                    setTypeface(typeface)
                    setOnClickListener {
                        onKey(KeyDef(label = e, output = e, type = KeyType.CHAR))
                        addRecentEmoji(e)
                    }
                }
                rowView!!.addView(tv, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
                cellsInRow++
            }
            // Pad the final row with empty placeholders so cells stay the same
            // size on the Recent tab (where only 3-4 items might exist) instead
            // of stretching to fill the row.
            if (cellsInRow in 1 until perRow) {
                repeat(perRow - cellsInRow) {
                    rowView!!.addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
                }
            }
        }

        // Recent first
        val recents = recentEmojis()
        if (recents.isNotEmpty()) {
            val btn = catTab("⏱") {
                loadCategory(recents)
            }
            catRow.addView(btn)
        }
        EmojiData.CATEGORIES.forEach { cat ->
            val btn = catTab(cat.emojis.firstOrNull() ?: "★") {
                loadCategory(cat.emojis, cat.columnsPerRow, cat.textSizeSp)
            }
            catRow.addView(btn)
        }

        // Default to first category (Recents if available, else Smileys).
        val firstCat = EmojiData.CATEGORIES.first()
        if (recents.isNotEmpty()) loadCategory(recents)
        else loadCategory(firstCat.emojis, firstCat.columnsPerRow, firstCat.textSizeSp)

        catScroll.addView(catRow)
        root.addView(catScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))

        val scroll = ScrollView(context)
        scroll.addView(grid)
        root.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // bottom bar with backspace + return
        val bottom = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(44))
            setBackgroundColor(theme.suggestionBg)
        }
        val abc = TextView(context).apply {
            text = "ABC"
            setTextColor(theme.keyText); setTypeface(typeface)
            gravity = Gravity.CENTER
            setOnClickListener { showKeysPanel() }
        }
        val back = TextView(context).apply {
            text = "⌫"
            setTextColor(theme.keyText); setTypeface(typeface)
            gravity = Gravity.CENTER
            setOnClickListener { onKey(KeyDef("⌫", "back", type = KeyType.BACKSPACE)) }
        }
        val space = TextView(context).apply {
            text = "space"
            setTextColor(theme.keyText); setTypeface(typeface)
            gravity = Gravity.CENTER
            setOnClickListener { onKey(KeyDef("space", " ", type = KeyType.SPACE)) }
        }
        val enter = TextView(context).apply {
            text = "⏎"
            setTextColor(theme.keyText); setTypeface(typeface)
            gravity = Gravity.CENTER
            setOnClickListener { onKey(KeyDef("⏎", "enter", type = KeyType.ENTER)) }
        }
        bottom.addView(abc, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        bottom.addView(space, LayoutParams(0, LayoutParams.MATCH_PARENT, 4f))
        bottom.addView(back, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        bottom.addView(enter, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        root.addView(bottom)

        // IMPORTANT: pass the explicit totalH so the bottom bar stays docked.
        // If we passed WRAP_CONTENT here, the inner ScrollView (weight=1) would
        // collapse to its content height, so on the Recent tab (one short row)
        // the ABC/space/⌫/⏎ bar would shift up against the emojis.
        panelContainer.addView(
            root,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, totalH)
        )
    }

    private fun catTab(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 18f
        setTextColor(theme.keyText)
        setTypeface(typeface)
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { onClick() }
    }

    private fun recentEmojis(): List<String> {
        return try {
            val s = prefs.getString(SettingsStore.KEY_EMOJI_RECENT, "[]") ?: "[]"
            val a = JSONArray(s)
            (0 until a.length()).map { a.getString(it) }.take(24)
        } catch (_: Exception) { emptyList() }
    }

    private fun addRecentEmoji(e: String) {
        val cur = recentEmojis().toMutableList()
        cur.remove(e); cur.add(0, e)
        val arr = JSONArray()
        cur.take(24).forEach { arr.put(it) }
        prefs.edit().putString(SettingsStore.KEY_EMOJI_RECENT, arr.toString()).apply()
    }

    // ===== AUTO-TYPE PANEL =====
    /**
     * In-keyboard Auto-Type control panel. Rebuilt per user feedback to be
     *   • BIGGER     — taller rows, larger title, bigger START/STOP button
     *                 so it feels like a real control surface, not a cramped
     *                 strip of pill buttons.
     *   • CLEANER    — only the controls that matter while typing live in
     *                 the keyboard: status, delay, loop, START/STOP, pause.
     *   • SIMPLER    — a SINGLE START/STOP button that toggles its color +
     *                 label from green "▶ START" to red "■ STOP" depending
     *                 on engine state. Removed: AutoSend toggle, separate
     *                 Stop button, "Open settings" shortcut — those still
     *                 live in the full Settings screen, the keyboard panel
     *                 only needs the live runtime controls.
     *
     * IMPORTANT: panel-level settings such as AutoSend and target line are
     * still respected by the engine; we just don't expose toggles for them
     * in the cramped in-keyboard view. They're configurable from the main
     * Settings → Auto-Type screen.
     */
    private fun rebuildAutoType() {
        panelContainer.removeAllViews()
        val pad = dp(14)
        val keyHeight = prefs.getInt(SettingsStore.KEY_KEY_HEIGHT_DP, 50).coerceIn(36, 80)
        // Bigger panel — was keyHeight*5, now keyHeight*6 with a min of
        // 320dp so even when the user has shrunk their keys the panel
        // still has room for readable status text + finger-friendly buttons.
        val totalH = maxOf(dp(keyHeight) * 6, dp(320))

        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, totalH)
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(theme.keyboardBg)
        }

        val state = AutoTypeEngine.state.value
        val delaySec = prefs.getInt(SettingsStore.KEY_AT_DELAY, 5)
        val loop = prefs.getBoolean(SettingsStore.KEY_AT_LOOP, false)

        // ===== HEADER =====  Title on the left, live status badge on right.
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "💣 Auto-Type"
            setTextColor(theme.accent)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val badgeText = when {
            state.running && state.paused -> "⏸ PAUSED"
            state.running                 -> "▶ RUNNING"
            else                          -> "⏹ STOPPED"
        }
        val badgeColor = if (state.running) theme.accent else theme.keyText
        header.addView(TextView(context).apply {
            text = badgeText
            setTextColor(badgeColor)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = makeKeyBackground(theme.keyBg, "rounded", 1f)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        root.addView(header)

        // ===== STATUS BLOCK =====  Source file → line counter → live line.
        val sourceText = if (state.total == 0) "📂 No file/text loaded" else "📂 ${state.sourceName}"
        root.addView(TextView(context).apply {
            text = sourceText
            setTextColor(theme.keyText)
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        root.addView(TextView(context).apply {
            text = if (state.total > 0) "Line ${state.current} / ${state.total}" else " "
            setTextColor(theme.keyText)
            textSize = 13f
        })
        root.addView(TextView(context).apply {
            text = if (state.currentLine.isNotEmpty()) "-> ${state.currentLine}" else " "
            setTextColor(Color.LTGRAY)
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, dp(10))
        })

        // ===== SETTINGS ROW =====  Delay − value + and Loop toggle.
        val rowSettings = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(48))
            gravity = Gravity.CENTER_VERTICAL
        }
        val minusBtn = bigBtn("−", theme.keyBg) {
            val v = (prefs.getInt(SettingsStore.KEY_AT_DELAY, 5) - 1).coerceAtLeast(1)
            prefs.edit().putInt(SettingsStore.KEY_AT_DELAY, v).apply(); rebuildAutoType()
        }
        val delayLabel = TextView(context).apply {
            text = "Delay  ${delaySec}s"
            setTextColor(theme.keyText)
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        val plusBtn = bigBtn("+", theme.keyBg) {
            val v = (prefs.getInt(SettingsStore.KEY_AT_DELAY, 5) + 1).coerceAtMost(60)
            prefs.edit().putInt(SettingsStore.KEY_AT_DELAY, v).apply(); rebuildAutoType()
        }
        val loopBtn = bigBtn(if (loop) "🔁 Loop ON" else "🔁 Loop OFF",
            if (loop) theme.accent else theme.keyBg) {
            prefs.edit().putBoolean(SettingsStore.KEY_AT_LOOP, !loop).apply(); rebuildAutoType()
        }
        rowSettings.addView(minusBtn,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 0.7f).apply { setMargins(0, 0, dp(4), 0) })
        rowSettings.addView(delayLabel,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1.2f))
        rowSettings.addView(plusBtn,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 0.7f).apply { setMargins(dp(4), 0, dp(8), 0) })
        rowSettings.addView(loopBtn,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1.6f))
        root.addView(rowSettings)

        // ===== ACTION ROW =====  Unified START/STOP toggle + Pause/Resume.
        // The user explicitly asked for ONE button that flips between
        // start and stop instead of two separate buttons. Color tells the
        // story: accent green-ish when ready to start, red when running.
        val rowAct = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(10) }
        }
        val startStopColor = if (state.running) Color.parseColor("#E53935") else theme.accent
        val startStopLabel = if (state.running) "■  STOP" else "▶  START"
        val startStopBtn = bigBtn(startStopLabel, startStopColor) {
            if (state.running) {
                AutoTypeEngine.stop()
                try { AutoTypeForegroundService.stop(context.applicationContext) } catch (_: Throwable) {}
            } else {
                try { AutoTypeForegroundService.start(context.applicationContext) } catch (_: Throwable) {}
                AutoTypeEngine.start(
                    context.applicationContext,
                    prefs.getInt(SettingsStore.KEY_AT_START_LINE, 0)
                )
            }
        }
        val pauseLabel = if (state.paused) "▶ Resume" else "⏸ Pause"
        val pauseBtn = bigBtn(pauseLabel, theme.keyBg) {
            if (!state.running) return@bigBtn   // Pause is meaningless while stopped.
            if (state.paused) AutoTypeEngine.resume() else AutoTypeEngine.pause()
        }
        // Visually dim the Pause button when not actionable so users can
        // tell at a glance it's a no-op until Auto-Type is running.
        if (!state.running) pauseBtn.alpha = 0.45f
        rowAct.addView(startStopBtn,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 2f).apply { setMargins(0, 0, dp(8), 0) })
        rowAct.addView(pauseBtn, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        root.addView(rowAct)

        // ===== BACK ROW =====  Return to the keys panel.
        root.addView(pillBtn("⌨ Back to keyboard") { showKeysPanel() },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(10) })

        state.lastError?.let {
            root.addView(TextView(context).apply {
                text = "⚠ $it"
                setTextColor(Color.parseColor("#FF6A6A"))
                textSize = 11f
                setPadding(0, dp(6), 0, 0)
            })
        }

        panelContainer.addView(root, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun rebuildMath() {
        panelContainer.removeAllViews()
        val pad = dp(14)
        val keyHeight = prefs.getInt(SettingsStore.KEY_KEY_HEIGHT_DP, 50).coerceIn(36, 80)
        val totalH = maxOf(dp(keyHeight) * 5, dp(260))

        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, totalH)
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(theme.keyboardBg)
        }

        val mathOn = prefs.getBoolean(SettingsStore.KEY_MATH_ENABLED, false)
        val mathCount = prefs.getInt(SettingsStore.KEY_MATH_COUNT, 1).coerceIn(1, 50)

        // ===== HEADER =====
        val header = TextView(context).apply {
            text = "🔢 Math"
            setTextColor(theme.accent)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(header)

        // ===== MATH ON/OFF TOGGLE =====
        val toggleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding(0, dp(16), 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        toggleRow.addView(TextView(context).apply {
            text = "Math Mode"
            setTextColor(theme.keyText)
            textSize = 16f
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val toggle = android.widget.Switch(context).apply {
            isChecked = mathOn
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(SettingsStore.KEY_MATH_ENABLED, isChecked).apply()
                Toast.makeText(context, if (isChecked) "Math Mode ON 🔢" else "Math Mode OFF", Toast.LENGTH_SHORT).show()
                rebuildMath()
            }
        }
        toggleRow.addView(toggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        root.addView(toggleRow)

        // ===== TYPE COUNT =====
        val countSection = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding(0, dp(20), 0, 0)
        }
        countSection.addView(TextView(context).apply {
            text = "Type Count"
            setTextColor(theme.keyText)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        })
        countSection.addView(TextView(context).apply {
            text = "Each character typed this many times"
            setTextColor(theme.keyText.copy(alpha = 0.6f))
            textSize = 11f
            setPadding(0, dp(2), 0, 0)
        })

        val countRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(10) }
            gravity = Gravity.CENTER_VERTICAL
        }

        val minusBtn = bigBtn("−", theme.keyBg) {
            val v = (prefs.getInt(SettingsStore.KEY_MATH_COUNT, 1) - 1).coerceAtLeast(1)
            prefs.edit().putInt(SettingsStore.KEY_MATH_COUNT, v).apply(); rebuildMath()
        }
        val countLabel = TextView(context).apply {
            text = "$mathCount"
            setTextColor(theme.accent)
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1.2f)
        }
        val plusBtn = bigBtn("+", theme.keyBg) {
            val v = (prefs.getInt(SettingsStore.KEY_MATH_COUNT, 1) + 1).coerceAtMost(50)
            prefs.edit().putInt(SettingsStore.KEY_MATH_COUNT, v).apply(); rebuildMath()
        }

        countRow.addView(minusBtn,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 0.7f).apply { setMargins(0, 0, dp(4), 0) })
        countRow.addView(countLabel)
        countRow.addView(plusBtn,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 0.7f).apply { setMargins(dp(4), 0, 0, 0) })
        countSection.addView(countRow)
        root.addView(countSection)

        // ===== BACK ROW =====
        root.addView(pillBtn("⌨ Back to keyboard") { showKeysPanel() },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(16) })

        panelContainer.addView(root, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun rebuildFyt() {
        panelContainer.removeAllViews()
        val pad = dp(14)
        val keyHeight = prefs.getInt(SettingsStore.KEY_KEY_HEIGHT_DP, 50).coerceIn(36, 80)
        val totalH = maxOf(dp(keyHeight) * 5, dp(260))

        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, totalH)
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(theme.keyboardBg)
        }

        val fytOn = prefs.getBoolean(SettingsStore.KEY_FYT_ENABLED, false)
        val fytCount = prefs.getInt(SettingsStore.KEY_FYT_COUNT, 3).coerceIn(1, 50)

        // ===== HEADER =====
        val header = TextView(context).apply {
            text = "🔁 FYT Type"
            setTextColor(theme.accent)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(header)

        // ===== FYT ON/OFF TOGGLE =====
        val toggleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding(0, dp(16), 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        toggleRow.addView(TextView(context).apply {
            text = "FYT Mode"
            setTextColor(theme.keyText)
            textSize = 16f
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val toggle = android.widget.Switch(context).apply {
            isChecked = fytOn
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(SettingsStore.KEY_FYT_ENABLED, isChecked).apply()
                Toast.makeText(context, if (isChecked) "FYT ON" else "FYT OFF", Toast.LENGTH_SHORT).show()
                rebuildFyt()
            }
        }
        toggleRow.addView(toggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        root.addView(toggleRow)

        // ===== TYPE COUNT =====
        val countSection = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding(0, dp(20), 0, 0)
        }
        countSection.addView(TextView(context).apply {
            text = "Type Count"
            setTextColor(theme.keyText)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        })
        countSection.addView(TextView(context).apply {
            text = "Each character typed this many times"
            setTextColor(theme.keyText.copy(alpha = 0.6f))
            textSize = 11f
            setPadding(0, dp(2), 0, 0)
        })

        val countRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(10) }
            gravity = Gravity.CENTER_VERTICAL
        }

        val minusBtn = bigBtn("−", theme.keyBg) {
            val v = (prefs.getInt(SettingsStore.KEY_FYT_COUNT, 3) - 1).coerceAtLeast(1)
            prefs.edit().putInt(SettingsStore.KEY_FYT_COUNT, v).apply(); rebuildFyt()
        }
        val countLabel = TextView(context).apply {
            text = "$fytCount"
            setTextColor(theme.accent)
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1.2f)
        }
        val plusBtn = bigBtn("+", theme.keyBg) {
            val v = (prefs.getInt(SettingsStore.KEY_FYT_COUNT, 3) + 1).coerceAtMost(50)
            prefs.edit().putInt(SettingsStore.KEY_FYT_COUNT, v).apply(); rebuildFyt()
        }

        countRow.addView(minusBtn,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 0.7f).apply { setMargins(0, 0, dp(4), 0) })
        countRow.addView(countLabel)
        countRow.addView(plusBtn,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 0.7f).apply { setMargins(dp(4), 0, 0, 0) })
        countSection.addView(countRow)
        root.addView(countSection)

        // ===== BACK ROW =====
        root.addView(pillBtn("⌨ Back to keyboard") { showKeysPanel() },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(16) })

        panelContainer.addView(root, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun rebuildCaps() {
        panelContainer.removeAllViews()
        val pad = dp(14)
        val keyHeight = prefs.getInt(SettingsStore.KEY_KEY_HEIGHT_DP, 50).coerceIn(36, 80)
        val totalH = maxOf(dp(keyHeight) * 4, dp(200))

        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, totalH)
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(theme.keyboardBg)
        }

        val capsOn = capsMode

        // ===== HEADER =====
        root.addView(TextView(context).apply {
            text = "⬆ Caps"
            setTextColor(theme.accent)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        })

        // ===== CAPS ON/OFF TOGGLE =====
        val toggleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding(0, dp(16), 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        toggleRow.addView(TextView(context).apply {
            text = "Caps Mode"
            setTextColor(theme.keyText)
            textSize = 16f
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val toggle = android.widget.Switch(context).apply {
            isChecked = capsOn
            setOnCheckedChangeListener { _, isChecked ->
                capsMode = isChecked
                prefs.edit().putBoolean(SettingsStore.KEY_CAPS_MODE, isChecked).apply()
                Toast.makeText(context, if (isChecked) "Caps ON" else "Caps OFF", Toast.LENGTH_SHORT).show()
                rebuildCaps()
            }
        }
        toggleRow.addView(toggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        root.addView(toggleRow)

        // ===== BACK ROW =====
        root.addView(pillBtn("⌨ Back to keyboard") { showKeysPanel() },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(16) })

        panelContainer.addView(root, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun rebuildPointer() {
        panelContainer.removeAllViews()
        val pad = dp(14)
        val keyHeight = prefs.getInt(SettingsStore.KEY_KEY_HEIGHT_DP, 50).coerceIn(36, 80)
        val totalH = maxOf(dp(keyHeight) * 6, dp(300))

        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, totalH)
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(theme.keyboardBg)
        }

        val pointerOn = prefs.getBoolean(SettingsStore.KEY_POINTER_ENABLED, false)
        val multiClick = prefs.getBoolean(SettingsStore.KEY_POINTER_MULTI_CLICK, false)
        val clickDelayMs = prefs.getInt(SettingsStore.KEY_POINTER_CLICK_DELAY_MS, 0).coerceIn(0, 400000)

        // ===== HEADER =====
        root.addView(TextView(context).apply {
            text = "🎯 Pointer"
            setTextColor(theme.accent)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        })

        // ===== POINTER ON/OFF TOGGLE =====
        val toggleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding(0, dp(16), 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        toggleRow.addView(TextView(context).apply {
            text = "Pointer"
            setTextColor(theme.keyText)
            textSize = 16f
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val toggle = android.widget.Switch(context).apply {
            isChecked = pointerOn
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    prefs.edit().putBoolean(SettingsStore.KEY_POINTER_ENABLED, true)
                        .putBoolean(SettingsStore.KEY_POINTER_MULTI_CLICK, multiClick).apply()
                    FloatingPointerService.start(context)
                } else {
                    prefs.edit().putBoolean(SettingsStore.KEY_POINTER_ENABLED, false).apply()
                    FloatingPointerService.stop(context)
                }
                if (panelMode == PanelMode.POINTER) rebuildPointer()
                else if (panelMode == PanelMode.TOOLS) rebuildTools()
            }
        }
        toggleRow.addView(toggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        root.addView(toggleRow)

        // ===== CLICK DELAY =====
        val delaySection = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding(0, dp(16), 0, 0)
        }
        delaySection.addView(TextView(context).apply {
            text = "Click Delay"
            setTextColor(theme.keyText)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        })
        delaySection.addView(TextView(context).apply {
            text = "Wait before clicking (seconds)"
            setTextColor(theme.keyText.copy(alpha = 0.6f))
            textSize = 11f
            setPadding(0, dp(2), 0, 0)
        })

        val delayRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(10) }
            gravity = Gravity.CENTER_VERTICAL
        }

        val delaySec = clickDelayMs / 1000
        val minusDelay = bigBtn("−", theme.keyBg) {
            val v = (prefs.getInt(SettingsStore.KEY_POINTER_CLICK_DELAY_MS, 0) - 1000).coerceAtLeast(0)
            prefs.edit().putInt(SettingsStore.KEY_POINTER_CLICK_DELAY_MS, v).apply(); rebuildPointer()
        }
        val delayLabel = TextView(context).apply {
            text = "${delaySec}s"
            setTextColor(theme.accent)
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1.2f)
        }
        val plusDelay = bigBtn("+", theme.keyBg) {
            val v = (prefs.getInt(SettingsStore.KEY_POINTER_CLICK_DELAY_MS, 0) + 1000).coerceAtMost(400000)
            prefs.edit().putInt(SettingsStore.KEY_POINTER_CLICK_DELAY_MS, v).apply(); rebuildPointer()
        }

        delayRow.addView(minusDelay,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 0.7f).apply { setMargins(0, 0, dp(4), 0) })
        delayRow.addView(delayLabel)
        delayRow.addView(plusDelay,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 0.7f).apply { setMargins(dp(4), 0, 0, 0) })
        delaySection.addView(delayRow)
        root.addView(delaySection)

        // ===== BACK ROW =====
        root.addView(pillBtn("⌨ Back to keyboard") { showKeysPanel() },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(16) })

        panelContainer.addView(root, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun rebuildFont() {
        panelContainer.removeAllViews()
        val pad = dp(14)
        val keyHeight = prefs.getInt(SettingsStore.KEY_KEY_HEIGHT_DP, 50).coerceIn(36, 80)
        val totalH = maxOf(dp(keyHeight) * 6, dp(320))

        val scroll = ScrollView(context).apply {
            setBackgroundColor(theme.keyboardBg)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, totalH)
        }
        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // ===== HEADER =====
        root.addView(TextView(context).apply {
            text = "Aa Font"
            setTextColor(theme.accent)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        })

        // ===== Bold / Italic / Default =====
        val toggleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(40))
            setPadding(0, dp(10), 0, 0)
        }
        val boldOn = prefs.getBoolean(SettingsStore.KEY_FONT_BOLD, false)
        val italicOn = prefs.getBoolean(SettingsStore.KEY_FONT_ITALIC, false)
        toggleRow.addView(makePill(if (boldOn) "Bold ✓" else "Bold ✗") {
            prefs.edit().putBoolean(SettingsStore.KEY_FONT_BOLD, !boldOn).apply()
            reload()
        }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        toggleRow.addView(makePill(if (italicOn) "Italic ✓" else "Italic ✗") {
            prefs.edit().putBoolean(SettingsStore.KEY_FONT_ITALIC, !italicOn).apply()
            reload()
        }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        toggleRow.addView(makePill("Default") {
            FontManager.setActive(context, null)
            prefs.edit()
                .putBoolean(SettingsStore.KEY_FONT_BOLD, false)
                .putBoolean(SettingsStore.KEY_FONT_ITALIC, false)
                .apply()
            reload()
            Toast.makeText(context, "Font reset to system default", Toast.LENGTH_SHORT).show()
        }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        root.addView(toggleRow)

        // ===== Output styles =====
        root.addView(sectionHeader("Output style (works in chat apps)"))
        val activeStyleId = prefs.getString(SettingsStore.KEY_FONT_STYLE, "normal")
        UnicodeFonts.STYLES_ALL.forEach { st ->
            root.addView(unicodeStyleRow(st, st.id == activeStyleId) {
                UnicodeFonts.setActive(context, st.id)
                Toast.makeText(context, "Output style: ${st.name}", Toast.LENGTH_SHORT).show()
                showFontPanel()
            })
        }

        // ===== Custom fonts =====
        val all = FontManager.all(context)
        val custom = all.filter { !it.builtIn }
        if (custom.isNotEmpty()) {
            root.addView(sectionHeader("Custom fonts (${custom.size})"))
            val activePath = FontManager.activePath(context)
            custom.forEach { f ->
                root.addView(fontRow(f, activePath == f.path) {
                    FontManager.setActive(context, f.path)
                    reload()
                    Toast.makeText(context, "Font: ${f.name}", Toast.LENGTH_SHORT).show()
                    showFontPanel()
                })
            }
        }

        // ===== Footer =====
        root.addView(TextView(context).apply {
            text = "Tip: change the active font from Settings → Theme tab"
            setTextColor(Color.LTGRAY); textSize = 10f
            setPadding(dp(6), dp(8), dp(6), dp(4))
        })

        // ===== BACK ROW =====
        root.addView(pillBtn("⌨ Back to keyboard") { showKeysPanel() },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(8) })

        scroll.addView(root)
        panelContainer.addView(scroll, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun pillBtn(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextColor(theme.keyText)
        setTypeface(typeface)
        gravity = Gravity.CENTER
        textSize = 13f
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background = makeKeyBackground(theme.keyBg, "rounded", 1f)
        val lp = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) }
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    private fun bigBtn(label: String, bg: Int, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextColor(if (bg == theme.accent) Color.BLACK else theme.keyText)
        gravity = Gravity.CENTER
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        background = makeKeyBackground(bg, "rounded", 1f)
        val lp = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    private fun doFeedback(v: View) {
        if (prefs.getBoolean(SettingsStore.KEY_HAPTIC, true)) {
            val intensity = prefs.getInt(SettingsStore.KEY_HAPTIC_INTENSITY, 50)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator != null) {
                    vibrator.vibrate(VibrationEffect.createOneShot(15L, (intensity * 2).coerceIn(1, 255)))
                } else {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            } catch (_: Exception) { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
        }
        // ===== Click sound (issue #4) =====
        // Use AudioManager.playSoundEffect with an explicit volume so the
        // keyboard tap is reliably audible even when the View's own
        // playSoundEffect path is gated (some launchers / IME hosts
        // disable view sound effects globally). FX_KEYPRESS_STANDARD is
        // the standard "tap" sound the system ships for IMEs — sounds
        // identical to Gboard / SwiftKey clicks.
        if (prefs.getBoolean(SettingsStore.KEY_SOUND, false)) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val vol = (prefs.getInt(SettingsStore.KEY_SOUND_VOLUME, 35) / 100f).coerceIn(0.05f, 1f)
                am?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, vol)
                // Belt-and-suspenders fallback to the View-level click —
                // some Android skins only honour one path or the other.
                v.playSoundEffect(android.view.SoundEffectConstants.CLICK)
            } catch (_: Exception) {
                v.playSoundEffect(android.view.SoundEffectConstants.CLICK)
            }
        }
    }

    private fun makeKeyBackground(color: Int, borderStyle: String, opacity: Float): GradientDrawable {
        val a = (Color.alpha(color) * opacity).toInt().coerceIn(0, 255)
        // ===== v1.7 — soft vertical gradient for the 3D pill look =====
        // Top of the key is rendered ~10 % lighter, bottom ~10 % darker.
        // Combined with View.elevation (set by the touch handler) this
        // produces the soft glossy pill that Gboard / SwiftKey ship —
        // a flat solid colour read as cheap / "stickered on" in the
        // user's screenshot. Falls back to a solid colour when the
        // requested opacity is 0 (fully transparent keys over a custom
        // background image — the mode where keys must vanish entirely).
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        val top = Color.argb(a,
            (r + (255 - r) * 0.10f).toInt().coerceIn(0, 255),
            (g + (255 - g) * 0.10f).toInt().coerceIn(0, 255),
            (b + (255 - b) * 0.10f).toInt().coerceIn(0, 255)
        )
        val bottom = Color.argb(a,
            (r * 0.88f).toInt().coerceIn(0, 255),
            (g * 0.88f).toInt().coerceIn(0, 255),
            (b * 0.88f).toInt().coerceIn(0, 255)
        )
        val d = if (a == 0) {
            GradientDrawable().apply { setColor(Color.TRANSPARENT) }
        } else {
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
        }
        d.cornerRadius = when (borderStyle) {
            "none" -> 0f
            "thin" -> dp(6).toFloat()
            "thick" -> dp(4).toFloat()
            else -> dp(22).toFloat() // large pill/capsule shape matching cute-keyboard aesthetic
        }
        when (borderStyle) {
            "thin" -> d.setStroke(dp(1), Color.argb(80, 255, 255, 255))
            "thick" -> d.setStroke(dp(2), Color.argb(120, 255, 255, 255))
        }
        return d
    }

    // ===== ON-KEYBOARD FONT PICKER (popup) =====
    private fun showThemePicker(anchor: View) {
        val container = ScrollView(context).apply {
            setBackgroundColor(theme.keyboardBg)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val list = LinearLayout(context).apply { orientation = VERTICAL }

        val popupRef = arrayOfNulls<PopupWindow>(1)
        val activeId = prefs.getString(SettingsStore.KEY_THEME, "cute_pill_blue") ?: "cute_pill_blue"

        ThemeManager.BUILT_IN.forEach { t ->
            val isActive = t.id == activeId
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(52))
                background = makeKeyBackground(
                    if (isActive) theme.accent else theme.keyBg, "rounded", 1f
                )
                setPadding(dp(10), 0, dp(10), 0)
                val lp = layoutParams as LayoutParams
                lp.setMargins(0, dp(3), 0, dp(3))
                layoutParams = lp
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    ThemeManager.setTheme(context, t.id)
                    reload()
                    Toast.makeText(context, "Theme: ${t.name}", Toast.LENGTH_SHORT).show()
                    popupRef[0]?.dismiss()
                }
            }
            // Swatch: keyboardBg circle
            val bgSwatch = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(t.keyboardBg)
                    setStroke(dp(1), Color.parseColor("#40000000"))
                }
            }
            row.addView(bgSwatch, LayoutParams(dp(22), dp(22)).also {
                it.marginEnd = dp(4)
                it.gravity = Gravity.CENTER_VERTICAL
            })
            // Swatch: keyBg circle
            val keySwatch = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(t.keyBg)
                    setStroke(dp(1), Color.parseColor("#40000000"))
                }
            }
            row.addView(keySwatch, LayoutParams(dp(18), dp(18)).also {
                it.marginEnd = dp(10)
                it.gravity = Gravity.CENTER_VERTICAL
            })
            // Theme name
            val nameTv = TextView(context).apply {
                text = t.name
                setTextColor(if (isActive) Color.WHITE else theme.keyText)
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(nameTv, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            if (isActive) {
                val checkTv = TextView(context).apply {
                    text = "✓"
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    gravity = Gravity.CENTER_VERTICAL
                    setTypeface(null, Typeface.BOLD)
                }
                row.addView(checkTv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            }
            list.addView(row)
        }

        container.addView(list)
        val popupWidth = (resources.displayMetrics.widthPixels * 0.88f).toInt()
        val popupHeight = (resources.displayMetrics.heightPixels * 0.50f).toInt()
        val popup = PopupWindow(container, popupWidth, popupHeight, true).apply {
            elevation = dp(8).toFloat()
            isOutsideTouchable = true
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(theme.keyboardBg)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), theme.accent)
            })
        }
        popupRef[0] = popup
        try { popup.showAtLocation(this, Gravity.CENTER, 0, -dp(40)) } catch (_: Throwable) { }
    }

    private fun fontRow(f: FontEntry, isActive: Boolean, onTap: () -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(48))
            background = makeKeyBackground(
                if (isActive) theme.accent else theme.keyBg, "rounded", 1f
            )
            setPadding(dp(10), 0, dp(10), 0)
            val lp = layoutParams as LayoutParams
            lp.setMargins(0, dp(3), 0, dp(3))
            layoutParams = lp
            setOnClickListener { onTap() }
        }
        // Preview using that font — share the same robust loader the
        // keyboard uses so the picker preview matches what the user
        // will actually see on the keys after tapping.
        val previewTf: Typeface = try {
            if (BuiltInFonts.isBuiltIn(f.path)) {
                BuiltInFonts.byPath(f.path)?.let { BuiltInFonts.typefaceFor(context, it) } ?: Typeface.DEFAULT
            } else {
                FontManager.loadCustomTypeface(f.path) ?: Typeface.DEFAULT
            }
        } catch (_: Throwable) { Typeface.DEFAULT }

        val name = TextView(context).apply {
            text = f.name
            setTextColor(if (isActive) Color.BLACK else theme.keyText)
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setTypeface(previewTf)
        }
        val sample = TextView(context).apply {
            text = "Aa Bb 123"
            setTextColor(if (isActive) Color.BLACK else Color.LTGRAY)
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setTypeface(previewTf)
        }
        row.addView(name, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        row.addView(sample, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        return row
    }

    /**
     * Row in the font picker that represents one Unicode "output style"
     * (Bold, Italic, Script, …). Tapping it sets the active style; the next
     * key the user presses goes to the field as that variant Unicode glyph.
     */
    private fun unicodeStyleRow(style: UnicodeFonts.Style, isActive: Boolean, onTap: () -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(46))
            background = makeKeyBackground(
                if (isActive) theme.accent else theme.keyBg, "rounded", 1f
            )
            setPadding(dp(10), 0, dp(10), 0)
            val lp = layoutParams as LayoutParams
            lp.setMargins(0, dp(3), 0, dp(3))
            layoutParams = lp
            setOnClickListener { onTap() }
        }
        val name = TextView(context).apply {
            text = style.name + if (isActive) "  ✓" else ""
            setTextColor(if (isActive) Color.BLACK else theme.keyText)
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
        }
        // Right-side preview rendered in the actual Unicode glyphs so the
        // user can see what their messages will look like before they pick.
        val sample = TextView(context).apply {
            text = style.sample
            setTextColor(if (isActive) Color.BLACK else Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        row.addView(name, LayoutParams(0, LayoutParams.MATCH_PARENT, 1.2f))
        row.addView(sample, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        return row
    }

    private fun sectionHeader(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(theme.accent)
        setTypeface(typeface, Typeface.BOLD)
        textSize = 12f
        setPadding(dp(4), dp(10), dp(4), dp(4))
    }

    private fun makePill(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextColor(theme.keyText)
        gravity = Gravity.CENTER
        textSize = 12f
        background = makeKeyBackground(theme.keyBg, "rounded", 1f)
        val lp = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        atObserver?.cancel()
        scope.cancel()
    }

    // ===================== CLIPBOARD POPUP =====================
    /**
     * Switches the panel to the dedicated Clipboard view (replaces old popup).
     */
    private fun showClipboardPanel() {
        panelMode = PanelMode.CLIPBOARD
        atObserver?.cancel(); atObserver = null
        rebuildClipboard()
    }

    /**
     * Renders the Clipboard panel inline — same real-estate as the keys panel.
     * Style matches the Gboard clipboard reference: light-tinted bg, accent
     * header, pin icon, delete icon, "Add a new clip" row, footer buttons.
     */
    private fun rebuildClipboard() {
        panelContainer.removeAllViews()
        val ctx = context
        val dao = AppDatabase.get(ctx).clipboardDao()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val keyHeight = prefs.getInt(SettingsStore.KEY_KEY_HEIGHT_DP, 50).coerceIn(36, 80)
        val totalH = dp(keyHeight) * 5

        val root = LinearLayout(ctx).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, totalH)
            setBackgroundColor(theme.keyboardBg)
            setPadding(dp(10), dp(8), dp(10), dp(6))
        }

        // ── Header: ← Clipboard ──────────────────────────────────
        val header = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val backBtn = TextView(ctx).apply {
            text = "←"
            setTextColor(theme.accent)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, dp(10), 0)
            setOnClickListener { showKeysPanel() }
        }
        val titleTv = TextView(ctx).apply {
            text = "Clipboard"
            setTextColor(theme.accent)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(backBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        header.addView(titleTv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(32)))

        // ── Scrollable item list ─────────────────────────────────
        val scroll = ScrollView(ctx)
        val list = LinearLayout(ctx).apply { orientation = VERTICAL }
        scroll.addView(list)

        fun renderItems(items: List<ClipboardItem>) {
            list.removeAllViews()

            // "Add a new clip" row (always shown at top)
            val addRow = LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(6))
                isClickable = true
                setOnClickListener { onClipboardOpen() }
            }
            val pencilTv = TextView(ctx).apply {
                text = "✏"
                setTextColor(theme.accent)
                textSize = 14f
                setPadding(0, 0, dp(8), 0)
            }
            val addLabel = TextView(ctx).apply {
                text = "Add a new clip"
                setTextColor(theme.accent)
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
            }
            addRow.addView(pencilTv)
            addRow.addView(addLabel)
            list.addView(addRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

            // Thin divider
            list.addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#22AAAAAA"))
            }, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).also { it.bottomMargin = dp(4) })

            if (items.isEmpty()) {
                list.addView(TextView(ctx).apply {
                    text = "Clipboard is empty.\nCopy something from any app\nand it will appear here."
                    setTextColor(theme.keyText)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(20), dp(16), dp(20))
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                return
            }

            items.forEach { item ->
                val row = LinearLayout(ctx).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    lp.setMargins(0, dp(2), 0, dp(2))
                    layoutParams = lp
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    isClickable = true
                    setOnClickListener { commitToField(item.text); showKeysPanel() }
                }
                // Pin icon
                val pinTv = TextView(ctx).apply {
                    text = if (item.pinned) "📌" else "▲"
                    setTextColor(theme.accent)
                    textSize = 13f
                    setPadding(0, 0, dp(8), 0)
                }
                // Item text
                val textTv = TextView(ctx).apply {
                    text = item.text.take(160)
                    setTextColor(theme.keyText)
                    textSize = 13f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                // Delete button
                val delTv = TextView(ctx).apply {
                    text = "🗑"
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(dp(8), 0, 0, 0)
                    setOnClickListener {
                        scope.launch {
                            withContext(Dispatchers.IO) { dao.delete(item) }
                            val refreshed = withContext(Dispatchers.IO) { dao.allOnce() }
                            renderItems(refreshed)
                        }
                    }
                }
                row.addView(pinTv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                row.addView(textTv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                row.addView(delTv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

                row.setOnLongClickListener {
                    showClipItemMenu(item, dao, cm) {
                        scope.launch {
                            val refreshed = withContext(Dispatchers.IO) { dao.allOnce() }
                            renderItems(refreshed)
                        }
                    }
                    true
                }
                list.addView(row)

                // Thin separator
                list.addView(View(ctx).apply {
                    setBackgroundColor(Color.parseColor("#18AAAAAA"))
                }, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))
            }
        }

        root.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // ── Footer: Manage all | Close ───────────────────────────
        val footer = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, dp(36))
            lp.topMargin = dp(4)
            layoutParams = lp
        }
        footer.addView(TextView(ctx).apply {
            text = "Manage all"
            setTextColor(theme.accent)
            textSize = 13f
            gravity = Gravity.CENTER
            setOnClickListener { showKeysPanel(); onClipboardOpen() }
        }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        footer.addView(TextView(ctx).apply {
            text = "Close"
            setTextColor(theme.keyText)
            textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, 0)
            setOnClickListener { showKeysPanel() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        root.addView(footer)

        panelContainer.addView(root)

        // Fetch items and render
        scope.launch {
            val items = withContext(Dispatchers.IO) { dao.allOnce() }
            renderItems(items)
        }
    }

    private fun showClipItemMenu(
        item: ClipboardItem,
        dao: com.ghosttype.data.db.ClipboardDao,
        cm: android.content.ClipboardManager,
        onChanged: () -> Unit
    ) {
        val ctx = context
        val container = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setBackgroundColor(theme.keyboardBg)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val popup = PopupWindow(container, dp(180), LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(theme.keyboardBg))
            elevation = dp(8).toFloat()
            isOutsideTouchable = true
        }
        fun action(label: String, run: () -> Unit) {
            container.addView(TextView(ctx).apply {
                text = label
                setTextColor(theme.keyText)
                setTypeface(typeface)
                textSize = 14f
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setOnClickListener { run(); popup.dismiss() }
            })
        }
        action("Paste") {
            commitToField(item.text)
        }
        action("Copy to clipboard") {
            // Tell the watcher this is an intentional re-copy so it
            // doesn't add a duplicate row to clipboard history.
            com.ghosttype.utils.ClipboardWatcher.suppressNext(item.text)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ghosttype", item.text))
            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        }
        action(if (item.pinned) "Unpin" else "Pin") {
            scope.launch {
                withContext(Dispatchers.IO) { dao.update(item.copy(pinned = !item.pinned)) }
                onChanged()
            }
        }
        action("Delete") {
            scope.launch {
                withContext(Dispatchers.IO) { dao.delete(item) }
                onChanged()
            }
        }
        popup.showAtLocation(this, Gravity.CENTER, 0, 0)
    }


    /**
     * Long-press on backspace pops this menu (issue #2). Each row routes
     * back through the normal onKey channel so GhostTypeIMEService can
     * call performContextMenuAction on the live InputConnection — keeps
     * IPC and selection bookkeeping in one place.
     */
    private fun showBackspaceMenu(anchor: View) {
        val ctx = context
        val container = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setBackgroundColor(theme.keyboardBg)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val popup = PopupWindow(container, dp(190), LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(theme.keyboardBg))
            elevation = dp(8).toFloat()
            isOutsideTouchable = true
        }
        fun row(label: String, key: KeyDef) {
            container.addView(TextView(ctx).apply {
                text = label
                setTextColor(theme.keyText)
                setTypeface(typeface)
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener {
                    popup.dismiss()
                    onKey(key)
                }
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        row("⬚  Select All", KeyDef("selAll", "selAll", type = KeyType.SELECT_ALL))
        row("📋  Copy",       KeyDef("copy",   "copy",   type = KeyType.COPY))
        row("✂  Cut",         KeyDef("cut",    "cut",    type = KeyType.CUT))
        row("📥  Paste",      KeyDef("paste",  "paste",  type = KeyType.PASTE))
        row("⌫  Delete one",  KeyDef("⌫",      "back",   type = KeyType.BACKSPACE))
        // Show ABOVE the backspace key so it doesn't get clipped under the
        // (already short) keyboard area.
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        popup.showAtLocation(this, Gravity.NO_GRAVITY,
            loc[0] - dp(140), loc[1] - dp(220))
    }
}
