package com.ghosttype.ime

data class KeyDef(
    val label: String,
    val output: String = label,
    val widthWeight: Float = 1f,
    val type: KeyType = KeyType.CHAR,
    val longPress: String? = null
)

enum class KeyType {
    CHAR, SHIFT, BACKSPACE, ENTER, SPACE, SYMBOLS, LANGUAGE, NUMBERS,
    COMMA, PERIOD, AUTOTYPE, CLIPBOARD, EMOJI,
    // ===== Edit-context actions (issue #2) =====
    // Fired by the long-press menu on the backspace key. Each one maps to
    // an InputConnection.performContextMenuAction call in
    // GhostTypeIMEService.handleKey.
    SELECT_ALL, COPY, CUT, PASTE
}

data class KbLayout(val rows: List<List<KeyDef>>)

object KeyboardLayouts {

    private fun row(vararg labels: String, longs: Map<String, String> = emptyMap()): List<KeyDef> =
        labels.map { KeyDef(it, longPress = longs[it]) }

    private fun bottomRow(periodLabel: String = ".", commaLabel: String = ","): List<KeyDef> = listOf(
        KeyDef("?123", "symbols", widthWeight = 1.4f, type = KeyType.SYMBOLS),
        KeyDef("😀", "emoji", widthWeight = 1.0f, type = KeyType.EMOJI),
        KeyDef("🌐", "lang", widthWeight = 1.0f, type = KeyType.LANGUAGE),
        KeyDef(commaLabel, commaLabel, widthWeight = 1.0f, type = KeyType.COMMA),
        // Space key label is intentionally blank here — the visible text is
        // resolved at render time in KeyboardView from the XOR-encrypted
        // ObfConstants.SPACE_LABEL so it can't be edited inside the APK
        // without re-signing the keystore. Output stays a single space.
        KeyDef("", " ", widthWeight = 4.0f, type = KeyType.SPACE),
        KeyDef(periodLabel, periodLabel, widthWeight = 1.0f, type = KeyType.PERIOD),
        // longPress = "\n" → smart-Enter logic in GhostTypeIMEService treats
        // this as the "force newline" escape hatch (browser etc. otherwise
        // sends the IME action on a normal tap).
        KeyDef("⏎", "enter", widthWeight = 1.6f, type = KeyType.ENTER, longPress = "\n")
    )

    val ENGLISH_QWERTY = KbLayout(
        rows = listOf(
            listOf("1","2","3","4","5","6","7","8","9","0").map { KeyDef(it) },
            row("q","w","e","r","t","y","u","i","o","p", longs = mapOf("e" to "èéêë","u" to "ùúûü","i" to "ìíîï","o" to "òóôõö")),
            row("a","s","d","f","g","h","j","k","l", longs = mapOf("a" to "àáâãäå")),
            listOf(KeyDef("⇧", "shift", widthWeight = 1.5f, type = KeyType.SHIFT))
                + row("z","x","c","v","b","n","m", longs = mapOf("n" to "→"))
                + listOf(KeyDef("⌫", "back", widthWeight = 1.5f, type = KeyType.BACKSPACE)),
            bottomRow()
        )
    )

    val SYMBOLS = KbLayout(
        rows = listOf(
            row("1","2","3","4","5","6","7","8","9","0"),
            row("@","#","$","_","&","-","+","(",")","/"),
            listOf(KeyDef("=\\<", "more", widthWeight = 1.4f, type = KeyType.SYMBOLS))
                + row("*","\"","'",":",";","!","?")
                + listOf(KeyDef("⌫", "back", widthWeight = 1.4f, type = KeyType.BACKSPACE)),
            listOf(
                KeyDef("ABC", "abc", widthWeight = 1.4f, type = KeyType.SYMBOLS),
                KeyDef("😀", "emoji", widthWeight = 1.0f, type = KeyType.EMOJI),
                KeyDef("🌐", "lang", widthWeight = 1.0f, type = KeyType.LANGUAGE),
                KeyDef(",", ",", widthWeight = 1.0f, type = KeyType.COMMA),
                // Same XOR-encrypted branding as bottomRow() — see comment above.
                KeyDef("", " ", widthWeight = 4.0f, type = KeyType.SPACE),
                KeyDef(".", ".", widthWeight = 1.0f, type = KeyType.PERIOD),
                KeyDef("⏎", "enter", widthWeight = 1.6f, type = KeyType.ENTER)
            )
        )
    )

    val URDU_PHONETIC = KbLayout(
        rows = listOf(
            row("1","2","3","4","5","6","7","8","9","0"),
            row("ق","و","ع","ر","ت","ے","ء","ی","ہ","پ"),
            row("ا","س","د","ف","گ","ح","ج","ک","ل"),
            listOf(KeyDef("⇧", "shift", widthWeight = 1.5f, type = KeyType.SHIFT))
                + row("ز","خ","چ","و","ب","ن","م")
                + listOf(KeyDef("⌫", "back", widthWeight = 1.5f, type = KeyType.BACKSPACE)),
            bottomRow(periodLabel = "۔", commaLabel = "،")
        )
    )

    val ARABIC = KbLayout(
        rows = listOf(
            row("1","2","3","4","5","6","7","8","9","0"),
            row("ض","ص","ث","ق","ف","غ","ع","ه","خ","ح"),
            row("ش","س","ي","ب","ل","ا","ت","ن","م"),
            listOf(KeyDef("⇧", "shift", widthWeight = 1.5f, type = KeyType.SHIFT))
                + row("ئ","ء","ؤ","ر","لا","ى","ة")
                + listOf(KeyDef("⌫", "back", widthWeight = 1.5f, type = KeyType.BACKSPACE)),
            bottomRow(periodLabel = ".", commaLabel = "،")
        )
    )

    fun forLanguage(code: String): KbLayout = when (code) {
        "ur" -> URDU_PHONETIC
        "ar" -> ARABIC
        else -> ENGLISH_QWERTY
    }

    val LANGUAGE_CYCLE = listOf("en")
    fun nextLanguage(current: String): String = "en"
}
