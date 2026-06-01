package com.ghosttype.utils

import android.content.Context
import android.graphics.Typeface

/**
 * UNICODE OUTPUT-FONT ENGINE
 * --------------------------
 * Why this exists: an Android IME can only `commitText(...)` Unicode strings
 * to the focused field. The destination app (WhatsApp, Messenger, Instagram …)
 * picks its own typeface and the keyboard CANNOT change it. So a `.ttf` file
 * the user picks in our font menu can never make their messages look fancy
 * inside another app — that was the long-standing "font select kr ke type
 * krta hu, normal font se aata ha" bug.
 *
 * Real fix: keep a table of Unicode "math alphanumeric" / fullwidth / circled
 * lookalike letters, transform every typed character before commit, so the
 * text the user sees in WhatsApp / FB / Insta is actually styled.
 *
 * Coverage today: Bold, Italic, Bold-Italic, Script, Bold Script, Fraktur,
 * Bold Fraktur, Double-struck, Sans, Sans Bold, Sans Italic, Sans Bold
 * Italic, Monospace, Circled, Negative-circled, Squared, Fullwidth, Small
 * Caps, Upside-down, Strikethrough, Underline.
 *
 * Each style ID is stable in SharedPreferences (`KEY_FONT_STYLE`).
 */
object UnicodeFonts {

    data class Style(
        val id: String,
        val name: String,
        val sample: String,
        val transform: (String) -> String
    )

    /** Canonical list shown in the on-keyboard "Aa" picker (in this order). */
    val STYLES: List<Style> by lazy {
        listOf(
            Style("normal",        "Normal",                  "Aa Bb 12") { it },
            Style("bold",          "Bold",                    apply("Aa Bb 12", ::toBold))            { toBold(it) },
            Style("italic",        "Italic",                  apply("Aa Bb 12", ::toItalic))          { toItalic(it) },
            Style("bold_italic",   "Bold Italic",             apply("Aa Bb 12", ::toBoldItalic))      { toBoldItalic(it) },
            Style("script",        "Script",                  apply("Aa Bb 12", ::toScript))          { toScript(it) },
            Style("bold_script",   "Bold Script",             apply("Aa Bb 12", ::toBoldScript))      { toBoldScript(it) },
            Style("fraktur",       "Fraktur (Gothic)",        apply("Aa Bb 12", ::toFraktur))         { toFraktur(it) },
            Style("bold_fraktur",  "Bold Fraktur",            apply("Aa Bb 12", ::toBoldFraktur))     { toBoldFraktur(it) },
            Style("double",        "Double-struck",           apply("Aa Bb 12", ::toDoubleStruck))    { toDoubleStruck(it) },
            Style("sans",          "Sans-Serif",              apply("Aa Bb 12", ::toSans))            { toSans(it) },
            Style("sans_bold",     "Sans-Serif Bold",         apply("Aa Bb 12", ::toSansBold))        { toSansBold(it) },
            Style("sans_italic",   "Sans-Serif Italic",       apply("Aa Bb 12", ::toSansItalic))      { toSansItalic(it) },
            Style("sans_bi",       "Sans-Serif Bold Italic",  apply("Aa Bb 12", ::toSansBoldItalic))  { toSansBoldItalic(it) },
            Style("monospace",     "Monospace",               apply("Aa Bb 12", ::toMonospace))       { toMonospace(it) },
            Style("circled",       "Circled",                 apply("Aa Bb 12", ::toCircled))         { toCircled(it) },
            Style("circled_neg",   "Negative Circled",        apply("AB 12", ::toCircledNeg))         { toCircledNeg(it) },
            Style("squared",       "Squared",                 apply("AB", ::toSquared))               { toSquared(it) },
            Style("squared_neg",   "Negative Squared",        apply("AB", ::toSquaredNeg))            { toSquaredNeg(it) },
            Style("fullwidth",     "Fullwidth",               apply("Aa Bb 12", ::toFullwidth))       { toFullwidth(it) },

            Style("small_caps",    "Small Caps",              apply("Aa Bb 12", ::toSmallCaps))       { toSmallCaps(it) },
            Style("upside_down",   "Upside Down",             apply("Aa Bb 12", ::toUpsideDown))      { toUpsideDown(it) },
            Style("strike",        "Strikethrough",           combineDemo("Aa Bb 12", '\u0336'))      { combine(it, '\u0336') },
            Style("underline",     "Underline",               combineDemo("Aa Bb 12", '\u0332'))      { combine(it, '\u0332') }
        )
    }

    private fun apply(sample: String, fn: (String) -> String): String = fn(sample)
    private fun combineDemo(sample: String, mark: Char): String = combine(sample, mark)

    /**
     * "Typeface-named" output styles — one entry per BuiltInFonts.ALL row.
     *
     * Why: a TTF file (Lobster, Bebas Neue, Jameel Noori Nastaleeq, …) only
     * controls how the keyboard's OWN keys are drawn. The chat app on the
     * other side (WhatsApp, Insta, Messenger) renders incoming text with
     * its OWN typeface — Android's IME API has no way to override that.
     *
     * The user wanted those 39 names to ALSO appear in the "Output style
     * (works in chat apps)" picker so picking "Lobster · Script" actually
     * delivers script-styled text to the chat window. We can't ship the
     * real font glyph through the wire, but we CAN map each typeface to
     * its closest Unicode-codepoint stylization (Lobster → Script,
     * Anton → Fullwidth, Bebas → Squared, Orbitron → Monospace, etc.).
     *
     * Each entry's id is "tf:" + the BuiltInFont path so it never collides
     * with the canonical [STYLES] list above. Sample text in the picker is
     * the actual transformed text so the user previews exactly what their
     * recipient will see.
     */
    val TYPEFACE_STYLES: List<Style> by lazy {
        BuiltInFonts.ALL.map { bf ->
            val fn = pickTransformForTypeface(bf.name, bf.style)
            Style(
                id = "tf:" + bf.path,
                name = bf.name,
                sample = fn("Aa Bb 12"),
                transform = fn
            )
        }
    }

    /**
     * Combined picker list: native Unicode styles first, then one entry
     * per built-in typeface. UI code should iterate THIS instead of
     * [STYLES] when it wants the full menu.
     */
    val STYLES_ALL: List<Style> by lazy { STYLES + TYPEFACE_STYLES }

    private fun pickTransformForTypeface(name: String, style: Int): (String) -> String {
        val n = name.lowercase()
        // Urdu Nastaliq — pass through (chat apps render Urdu code points
        // natively; no Latin-style transform makes sense).
        if ("nastaleeq" in n || "nastaliq" in n || "urdu" in n) return { it }
        // Script / handwriting families
        if ("permanent marker" in n) return { toBoldScript(it) }
        if ("script" in n || "lobster" in n || "pacifico" in n ||
            "dancing" in n || "caveat" in n || "indie" in n ||
            "shadows" in n || "cursive" in n) {
            return if (style == Typeface.BOLD) ({ toBoldScript(it) }) else ({ toScript(it) })
        }
        // Display / condensed display
        if ("anton" in n) return { toFullwidth(it) }
        if ("bebas" in n) {
            return if (style == Typeface.BOLD) ({ toSquaredNeg(it) }) else ({ toSquared(it) })
        }
        if ("shade blue" in n) return { toCircledNeg(it) }
        // Tech / monospaced
        if ("orbitron" in n || "monospace" in n) return { toMonospace(it) }
        // Serif / gothic
        if ("playfair" in n) {
            return if (style == Typeface.BOLD) ({ toBoldFraktur(it) }) else ({ toFraktur(it) })
        }
        if ("merriweather" in n) {
            return when (style) {
                Typeface.BOLD -> ({ toSansBold(it) })
                Typeface.ITALIC -> ({ toSansItalic(it) })
                else -> ({ toSans(it) })
            }
        }
        if ("slab" in n) {
            return if (style == Typeface.BOLD) ({ toBold(it) }) else ({ toSans(it) })
        }
        // System sans-serif aliases
        if ("sans-serif black" in n) return { toSansBold(it) }
        if ("sans-serif condensed bold" in n) return { toSansBold(it) }
        if ("sans-serif" in n) {
            return if (style == Typeface.BOLD) ({ toSansBold(it) }) else ({ toSans(it) })
        }
        if ("serif italic" in n) return { toItalic(it) }
        if ("serif" in n) return { it }
        // Fallback — System Default + style flag
        return when (style) {
            Typeface.BOLD -> ({ toBold(it) })
            Typeface.ITALIC -> ({ toItalic(it) })
            Typeface.BOLD_ITALIC -> ({ toBoldItalic(it) })
            else -> ({ it })
        }
    }

    fun byId(id: String?): Style = STYLES_ALL.firstOrNull { it.id == id } ?: STYLES.first()

    fun activeStyle(ctx: Context): Style {
        val id = SettingsStore.prefs(ctx).getString(SettingsStore.KEY_FONT_STYLE, "normal") ?: "normal"
        return byId(id)
    }

    fun setActive(ctx: Context, id: String) {
        SettingsStore.prefs(ctx).edit().putString(SettingsStore.KEY_FONT_STYLE, id).apply()
    }

    /** Transform [text] using the user's currently-selected style. */
    fun transform(ctx: Context, text: String): String =
        if (text.isEmpty()) text else activeStyle(ctx).transform(text)

    /**
     * Reverse-map a stylized character to its plain ASCII equivalent so word
     * suggestions still work after the user
     * picks a fancy style. Unknown glyphs pass through unchanged.
     */
    fun normalize(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val plain = REVERSE_MAP[cp]
            if (plain != null) sb.append(plain) else sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /**
     * Number of UTF-16 char units the LAST visible glyph in [text] occupies
     * (1 or 2 — astral plane characters are surrogate pairs). Used by the IME
     * service so backspace deletes one glyph instead of half a surrogate pair.
     */
    fun lastGlyphLength(text: String): Int {
        if (text.isEmpty()) return 0
        // Walk backward from the end consuming any trailing combining marks
        // first (these visually belong to the previous base character).
        var end = text.length
        while (end > 0 && isCombining(text[end - 1])) end--
        if (end == 0) return text.length // entire text is combining marks
        // Now `end` points one past the base character. If the base character
        // is the low half of a surrogate pair, include the high half too.
        val baseStart = if (end >= 2 && text[end - 1].isLowSurrogate() && text[end - 2].isHighSurrogate())
            end - 2 else end - 1
        return text.length - baseStart
    }

    private fun isCombining(c: Char): Boolean {
        val t = Character.getType(c)
        return t == Character.NON_SPACING_MARK.toInt() ||
                t == Character.ENCLOSING_MARK.toInt() ||
                t == Character.COMBINING_SPACING_MARK.toInt()
    }

    // =================== TRANSFORM TABLE ===================
    // Math alphanumeric blocks (https://www.unicode.org/charts/PDF/U1D400.pdf)
    // each cover A..Z then a..z (26+26 = 52 code points). Some blocks have
    // "holes" where a previously-assigned glyph from a different block must
    // be substituted. We handle those via per-block overrides.

    private fun mathAlpha(text: String, upperStart: Int, lowerStart: Int,
                          digitStart: Int = -1,
                          upperOverride: Map<Char, Int> = emptyMap(),
                          lowerOverride: Map<Char, Int> = emptyMap()): String {
        val sb = StringBuilder(text.length * 2)
        text.forEach { c ->
            when {
                c in 'A'..'Z' -> {
                    val o = upperOverride[c]
                    if (o != null) sb.appendCodePoint(o) else sb.appendCodePoint(upperStart + (c - 'A'))
                }
                c in 'a'..'z' -> {
                    val o = lowerOverride[c]
                    if (o != null) sb.appendCodePoint(o) else sb.appendCodePoint(lowerStart + (c - 'a'))
                }
                digitStart >= 0 && c in '0'..'9' -> sb.appendCodePoint(digitStart + (c - '0'))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun toBold(s: String) = mathAlpha(s, 0x1D400, 0x1D41A, 0x1D7CE)
    fun toItalic(s: String) = mathAlpha(s, 0x1D434, 0x1D44E,
        lowerOverride = mapOf('h' to 0x210E))
    fun toBoldItalic(s: String) = mathAlpha(s, 0x1D468, 0x1D482)
    fun toScript(s: String) = mathAlpha(s, 0x1D49C, 0x1D4B6,
        upperOverride = mapOf(
            'B' to 0x212C, 'E' to 0x2130, 'F' to 0x2131, 'H' to 0x210B,
            'I' to 0x2110, 'L' to 0x2112, 'M' to 0x2133, 'R' to 0x211B
        ),
        lowerOverride = mapOf('e' to 0x212F, 'g' to 0x210A, 'o' to 0x2134))
    fun toBoldScript(s: String) = mathAlpha(s, 0x1D4D0, 0x1D4EA)
    fun toFraktur(s: String) = mathAlpha(s, 0x1D504, 0x1D51E,
        upperOverride = mapOf(
            'C' to 0x212D, 'H' to 0x210C, 'I' to 0x2111, 'R' to 0x211C, 'Z' to 0x2128
        ))
    fun toBoldFraktur(s: String) = mathAlpha(s, 0x1D56C, 0x1D586)
    fun toDoubleStruck(s: String) = mathAlpha(s, 0x1D538, 0x1D552, 0x1D7D8,
        upperOverride = mapOf(
            'C' to 0x2102, 'H' to 0x210D, 'N' to 0x2115, 'P' to 0x2119,
            'Q' to 0x211A, 'R' to 0x211D, 'Z' to 0x2124
        ))
    fun toSans(s: String) = mathAlpha(s, 0x1D5A0, 0x1D5BA, 0x1D7E2)
    fun toSansBold(s: String) = mathAlpha(s, 0x1D5D4, 0x1D5EE, 0x1D7EC)
    fun toSansItalic(s: String) = mathAlpha(s, 0x1D608, 0x1D622)
    fun toSansBoldItalic(s: String) = mathAlpha(s, 0x1D63C, 0x1D656)
    fun toMonospace(s: String) = mathAlpha(s, 0x1D670, 0x1D68A, 0x1D7F6)

    fun toCircled(s: String): String {
        val sb = StringBuilder()
        s.forEach { c ->
            when (c) {
                in 'A'..'Z' -> sb.appendCodePoint(0x24B6 + (c - 'A'))
                in 'a'..'z' -> sb.appendCodePoint(0x24D0 + (c - 'a'))
                '0' -> sb.appendCodePoint(0x24EA)
                in '1'..'9' -> sb.appendCodePoint(0x2460 + (c - '1'))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun toCircledNeg(s: String): String {
        // Negative circled latin caps: U+1F150..1F169 (A..Z); digits 0..9 use
        // the dingbat circled set U+2776..277F (1..9, no 0). Add fallback for
        // lowercase by uppercasing it first so the user gets visible glyphs.
        val sb = StringBuilder()
        s.forEach { rawC ->
            val c = if (rawC in 'a'..'z') (rawC.code - 32).toChar() else rawC
            when (c) {
                in 'A'..'Z' -> sb.appendCodePoint(0x1F150 + (c - 'A'))
                '0' -> sb.appendCodePoint(0x24FF)
                in '1'..'9' -> sb.appendCodePoint(0x2776 + (c - '1'))
                else -> sb.append(rawC)
            }
        }
        return sb.toString()
    }

    fun toSquared(s: String): String {
        val sb = StringBuilder()
        s.forEach { rawC ->
            val c = if (rawC in 'a'..'z') (rawC.code - 32).toChar() else rawC
            when (c) {
                in 'A'..'Z' -> sb.appendCodePoint(0x1F130 + (c - 'A'))
                else -> sb.append(rawC)
            }
        }
        return sb.toString()
    }

    fun toSquaredNeg(s: String): String {
        val sb = StringBuilder()
        s.forEach { rawC ->
            val c = if (rawC in 'a'..'z') (rawC.code - 32).toChar() else rawC
            when (c) {
                in 'A'..'Z' -> sb.appendCodePoint(0x1F170 + (c - 'A'))
                else -> sb.append(rawC)
            }
        }
        return sb.toString()
    }

    fun toFullwidth(s: String): String {
        val sb = StringBuilder()
        s.forEach { c ->
            when (c) {
                ' ' -> sb.appendCodePoint(0x3000)               // ideographic space
                in '!'..'~' -> sb.appendCodePoint(0xFF00 + (c.code - 0x20))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun toSmallCaps(s: String): String {
        val sb = StringBuilder()
        s.forEach { c ->
            val mapped = SMALL_CAPS_MAP[c.lowercaseChar()]
            if (mapped != null && (c.isLowerCase() || c.isUpperCase())) {
                if (c.isUpperCase()) sb.append(c) else sb.append(mapped)
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    fun toUpsideDown(s: String): String {
        val sb = StringBuilder()
        // iterate over reversed string so reading order stays right-to-left
        s.reversed().forEach { c ->
            sb.append(UPSIDE_DOWN_MAP[c] ?: c)
        }
        return sb.toString()
    }

    fun toMath(s: String): String {
        val sb = StringBuilder(s.length)
        s.forEach { c ->
            sb.append(
                when (c.uppercaseChar()) {
                    'E' -> '3'
                    'T' -> '7'
                    'I' -> '1'
                    'O' -> '0'
                    'P' -> '9'
                    'A' -> '4'
                    'S' -> '5'
                    'G' -> '6'
                    'B' -> '8'
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    fun combine(s: String, mark: Char): String {
        val sb = StringBuilder(s.length * 2)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            sb.appendCodePoint(cp)
            // Add combining mark after each base char (skip whitespace & combining)
            if (!Character.isWhitespace(cp) && Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) {
                sb.append(mark)
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private val SMALL_CAPS_MAP: Map<Char, Char> = mapOf(
        'a' to 'ᴀ', 'b' to 'ʙ', 'c' to 'ᴄ', 'd' to 'ᴅ', 'e' to 'ᴇ',
        'f' to 'ꜰ', 'g' to 'ɢ', 'h' to 'ʜ', 'i' to 'ɪ', 'j' to 'ᴊ',
        'k' to 'ᴋ', 'l' to 'ʟ', 'm' to 'ᴍ', 'n' to 'ɴ', 'o' to 'ᴏ',
        'p' to 'ᴘ', 'q' to 'ǫ', 'r' to 'ʀ', 's' to 'ꜱ', 't' to 'ᴛ',
        'u' to 'ᴜ', 'v' to 'ᴠ', 'w' to 'ᴡ', 'x' to 'x', 'y' to 'ʏ', 'z' to 'ᴢ'
    )

    private val UPSIDE_DOWN_MAP: Map<Char, Char> = mapOf(
        'a' to 'ɐ','b' to 'q','c' to 'ɔ','d' to 'p','e' to 'ǝ',
        'f' to 'ɟ','g' to 'ƃ','h' to 'ɥ','i' to 'ᴉ','j' to 'ɾ',
        'k' to 'ʞ','l' to 'l','m' to 'ɯ','n' to 'u','o' to 'o',
        'p' to 'd','q' to 'b','r' to 'ɹ','s' to 's','t' to 'ʇ',
        'u' to 'n','v' to 'ʌ','w' to 'ʍ','x' to 'x','y' to 'ʎ','z' to 'z',
        'A' to '∀','B' to 'ꓭ','C' to 'Ɔ','D' to 'ꓷ','E' to 'Ǝ',
        'F' to 'Ⅎ','G' to '⅁','H' to 'H','I' to 'I','J' to 'ſ',
        'K' to 'ꓘ','L' to 'Ꞁ','M' to 'W','N' to 'N','O' to 'O',
        'P' to 'Ԁ','Q' to 'Ò','R' to 'ꓤ','S' to 'S','T' to 'Ʇ',
        'U' to '∩','V' to 'Λ','W' to 'M','X' to 'X','Y' to '⅄','Z' to 'Z',
        '0' to '0','1' to 'Ɩ','2' to 'ᄅ','3' to 'Ɛ','4' to 'ㄣ',
        '5' to 'ϛ','6' to '9','7' to 'ㄥ','8' to '8','9' to '6',
        '.' to '˙',',' to '\'','?' to '¿','!' to '¡','"' to ',',
        '\'' to ',','(' to ')',')' to '(','[' to ']',']' to '[',
        '{' to '}','}' to '{','<' to '>','>' to '<','&' to '⅋',
        '_' to '‾',';' to '؛'
    )

    /** Reverse map for normalize(): every styled glyph → ASCII char. */
    private val REVERSE_MAP: Map<Int, Char> by lazy {
        val m = HashMap<Int, Char>()
        // Math alphanumeric blocks
        addAlphaBlock(m, 0x1D400, 0x1D41A, 0x1D7CE)            // bold
        addAlphaBlock(m, 0x1D434, 0x1D44E, -1)                 // italic
        m[0x210E] = 'h'                                        // italic h
        addAlphaBlock(m, 0x1D468, 0x1D482, -1)                 // bold italic
        addAlphaBlock(m, 0x1D49C, 0x1D4B6, -1)                 // script
        listOf(0x212C to 'B', 0x2130 to 'E', 0x2131 to 'F', 0x210B to 'H',
               0x2110 to 'I', 0x2112 to 'L', 0x2133 to 'M', 0x211B to 'R',
               0x212F to 'e', 0x210A to 'g', 0x2134 to 'o').forEach { (k,v) -> m[k] = v }
        addAlphaBlock(m, 0x1D4D0, 0x1D4EA, -1)                 // bold script
        addAlphaBlock(m, 0x1D504, 0x1D51E, -1)                 // fraktur
        listOf(0x212D to 'C', 0x210C to 'H', 0x2111 to 'I', 0x211C to 'R', 0x2128 to 'Z')
            .forEach { (k,v) -> m[k] = v }
        addAlphaBlock(m, 0x1D56C, 0x1D586, -1)                 // bold fraktur
        addAlphaBlock(m, 0x1D538, 0x1D552, 0x1D7D8)            // double struck
        listOf(0x2102 to 'C', 0x210D to 'H', 0x2115 to 'N', 0x2119 to 'P',
               0x211A to 'Q', 0x211D to 'R', 0x2124 to 'Z').forEach { (k,v) -> m[k] = v }
        addAlphaBlock(m, 0x1D5A0, 0x1D5BA, 0x1D7E2)            // sans
        addAlphaBlock(m, 0x1D5D4, 0x1D5EE, 0x1D7EC)            // sans bold
        addAlphaBlock(m, 0x1D608, 0x1D622, -1)                 // sans italic
        addAlphaBlock(m, 0x1D63C, 0x1D656, -1)                 // sans bi
        addAlphaBlock(m, 0x1D670, 0x1D68A, 0x1D7F6)            // mono
        // Circled / squared / fullwidth — partial reverse, useful for word matching.
        for (i in 0..25) { m[0x24B6 + i] = ('A' + i); m[0x24D0 + i] = ('a' + i) }
        for (i in 1..9) { m[0x2460 + i - 1] = ('0' + i) }
        m[0x24EA] = '0'
        for (i in 0..25) { m[0x1F150 + i] = ('A' + i); m[0x1F130 + i] = ('A' + i); m[0x1F170 + i] = ('A' + i) }
        m[0x24FF] = '0'
        for (i in 1..9) { m[0x2776 + i - 1] = ('0' + i) }
        // Fullwidth
        for (i in 0x21..0x7E) m[0xFF00 + (i - 0x20)] = i.toChar()
        m[0x3000] = ' '
        // Small caps
        SMALL_CAPS_MAP.forEach { (ascii, glyph) -> m[glyph.code] = ascii }
        m
    }

    private fun addAlphaBlock(m: HashMap<Int, Char>, upperStart: Int, lowerStart: Int, digitStart: Int) {
        for (i in 0..25) {
            m[upperStart + i] = ('A' + i)
            m[lowerStart + i] = ('a' + i)
        }
        if (digitStart >= 0) for (i in 0..9) m[digitStart + i] = ('0' + i)
    }
}
