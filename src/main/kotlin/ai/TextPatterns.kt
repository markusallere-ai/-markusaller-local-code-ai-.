package ai

/** Gemeinsame Regex-Regeln für Prompt-Normalisierung, Feldextraktion und sichere Kotlin-Bezeichner. */
internal object TextPatterns {
    val PUNCTUATION = Regex("[\\p{Punct}]")
    val WHITESPACE = Regex("\\s+")
    val WORD_BOUNDARY_INT = Regex("\\bint\\b", RegexOption.IGNORE_CASE)
    val WORD_BOUNDARY_ZAHL = Regex("\\b(zahl|long)\\b", RegexOption.IGNORE_CASE)
    val WORD_BOUNDARY_STRING = Regex("\\b(string|text|name)\\b", RegexOption.IGNORE_CASE)
    val WORD_BOUNDARY_MIT = Regex("\\bmit\\b", RegexOption.IGNORE_CASE)
    val PROPERTY_SPLIT = Regex("\\s*(?:,|\\b(?:und|sowie)\\b|;)\\s*", RegexOption.IGNORE_CASE)
    val ARTICLE_WORDS = Regex("\\b(der|die|das|eine|einen|einem|einer|den|dem)\\b", RegexOption.IGNORE_CASE)
    val FIELD_NAME = Regex("^([\\p{L}][_\\p{L}\\p{N}]*)")
    val EMAIL_FIELD = Regex("\\b(e[-\\s]?mail|emailadresse)\\b", RegexOption.IGNORE_CASE)
    val NON_ALNUM = Regex("[^\\p{L}\\p{N}_]")
    val TOKENIZE_PREFIX =
        Regex("^\\s*(tokenisiere|tokenizer|tokenize|zerlege|teile|spalte)\\s*", RegexOption.IGNORE_CASE)
    val TOKENIZE_TEXT_PREFIX =
        Regex(
            "^\\s*(?:(?:(?:diesen|diese|dieser|dieses|den|die|der|dem)\\s+)?text\\b\\s*:?\\s*|" +
                "(?:(?:den|diesen|diese|dieser|dieses|der|dem)\\s+)?" +
                "(?:satz|absatz|eingabe|nachricht)\\b\\s*:\\s*)",
            RegexOption.IGNORE_CASE
        )
    val PI_PRECISION = Regex("\\b(\\d{1,4})\\s*(stellen|digits|dezimal|decimal)?\\b", RegexOption.IGNORE_CASE)

    private val KOTLIN_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
        "if", "in", "interface", "is", "null", "object", "package", "return",
        "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
        "var", "when", "while"
    )

    /** Prüft ganze Wörter statt beliebiger Teilzeichenfolgen, damit ähnliche Begriffe nicht versehentlich matchen. */
    fun containsWord(text: String, words: List<String>): Boolean =
        words.any { word -> Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) }

    /** Verhindert leere Namen und ergänzt bei Kotlin-Schlüsselwörtern ein neutrales Suffix. */
    fun sanitizeKotlinIdentifier(name: String): String {
        val cleaned = name.trim()
        if (cleaned.isBlank()) return "Example"
        return if (cleaned.lowercase() in KOTLIN_KEYWORDS) "${cleaned}Item" else cleaned
    }
}
