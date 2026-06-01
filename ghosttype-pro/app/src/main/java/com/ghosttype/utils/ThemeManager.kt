package com.ghosttype.utils

import android.content.Context
import android.graphics.Color

data class KeyboardTheme(
    val id: String,
    val name: String,
    val keyboardBg: Int,
    val keyBg: Int,
    val keyText: Int,
    val suggestionBg: Int,
    val pressedKey: Int,
    val accent: Int
)

object ThemeManager {
    val BUILT_IN: List<KeyboardTheme> = listOf(
        KeyboardTheme(
            id = "cute_pill_blue",
            name = "🩵 Cute Pill Blue",
            keyboardBg   = Color.parseColor("#BDD8EE"),
            keyBg        = Color.parseColor("#FFFFFF"),
            keyText      = Color.parseColor("#2E4A6A"),
            suggestionBg = Color.parseColor("#BDD8EE"),
            pressedKey   = Color.parseColor("#A8CCE4"),
            accent       = Color.parseColor("#5A9AC5")
        ),
        KeyboardTheme(
            id = "kawaii_bubble",
            name = "🫧 Kawaii Bubble",
            keyboardBg   = Color.parseColor("#B8D4EE"),
            keyBg        = Color.parseColor("#E4EFFA"),
            keyText      = Color.parseColor("#28436A"),
            suggestionBg = Color.parseColor("#B8D4EE"),
            pressedKey   = Color.parseColor("#C8DCF2"),
            accent       = Color.parseColor("#5A92C8")
        ),
        KeyboardTheme(
            id = "cute_sky_blue",
            name = "🩵 Cute Sky Blue",
            keyboardBg   = Color.parseColor("#C2DDED"),
            keyBg        = Color.parseColor("#FFFFFF"),
            keyText      = Color.parseColor("#3D5A7A"),
            suggestionBg = Color.parseColor("#C2DDED"),
            pressedKey   = Color.parseColor("#A0C8E4"),
            accent       = Color.parseColor("#5FA8D3")
        ),
        KeyboardTheme(
            id = "emoji_blue",
            name = "🌈 Emoji Blue",
            keyboardBg   = Color.parseColor("#C8E6F7"),
            keyBg        = Color.parseColor("#EEF7FD"),
            keyText      = Color.parseColor("#2A4A6A"),
            suggestionBg = Color.parseColor("#C8E6F7"),
            pressedKey   = Color.parseColor("#B0D8F0"),
            accent       = Color.parseColor("#5A9AC5")
        )
    )

    fun current(ctx: Context): KeyboardTheme {
        val prefs = SettingsStore.prefs(ctx)
        val id = prefs.getString(SettingsStore.KEY_THEME, "cute_pill_blue") ?: "cute_pill_blue"
        val base = BUILT_IN.firstOrNull { it.id == id } ?: BUILT_IN[0]
        return base.copy(
            keyBg = prefs.getInt(SettingsStore.KEY_KEY_BG, base.keyBg),
            keyText = prefs.getInt(SettingsStore.KEY_KEY_TEXT, base.keyText),
            keyboardBg = prefs.getInt(SettingsStore.KEY_KB_BG, base.keyboardBg),
            suggestionBg = prefs.getInt(SettingsStore.KEY_SUGG_BG, base.suggestionBg),
            pressedKey = prefs.getInt(SettingsStore.KEY_PRESSED, base.pressedKey)
        )
    }

    /**
     * Apply a built-in theme. ALL six prefs are written in a SINGLE edit so
     * the IME's `OnSharedPreferenceChangeListener` always sees the new colors
     * already committed when it reloads. Previously the theme id was written
     * first, fired the listener, and reload() ran while the per-color prefs
     * still held the previous theme's values — so picking "Neon Green" would
     * keep the keys orange. Bug fix for "Built in theme not working".
     */
    fun setTheme(ctx: Context, id: String) {
        val t = BUILT_IN.firstOrNull { it.id == id }
        val ed = SettingsStore.prefs(ctx).edit()
        ed.putString(SettingsStore.KEY_THEME, id)
        if (t != null) {
            ed.putInt(SettingsStore.KEY_KEY_BG, t.keyBg)
              .putInt(SettingsStore.KEY_KEY_TEXT, t.keyText)
              .putInt(SettingsStore.KEY_KB_BG, t.keyboardBg)
              .putInt(SettingsStore.KEY_SUGG_BG, t.suggestionBg)
              .putInt(SettingsStore.KEY_PRESSED, t.pressedKey)
        }
        ed.apply()
    }
}
