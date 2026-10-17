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
    private val TEMPERATURE_WORDS =
        listOf("celsius", "fahrenheit", "temperatur", "temperature")
    private val TEMPERATURE_CONVERSION_ACTIONS = listOf(
        "umrechnen", "umrechne", "umrechnet", "convert", "konvertiere", "konvertier",
        "wandle", "umwandeln", "rechne", "rechnen", "berechne"
    )
    private val NUMERIC_TEMPERATURE_DESTINATION =
        Regex("\\b(in|zu|nach|to)\\s+(?:grad\\s+)?(?:fahrenheit|celsius)\\b|°\\s*[fc]\\b")
    private val NUMERIC_TEMPERATURE_VALUE =
        Regex("(?<![\\p{L}\\p{N}_])[+-]?\\d+(?:[.,]\\d+)?(?![\\p{L}\\p{N}_])")

    private val KOTLIN_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
        "if", "in", "interface", "is", "null", "object", "package", "return",
        "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
        "var", "when", "while"
    )

    /** Prüft ganze Wörter statt beliebiger Teilzeichenfolgen, damit ähnliche Begriffe nicht versehentlich matchen. */
    fun containsWord(text: String, words: List<String>): Boolean =
        words.any { word -> Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) }

    /** Erfordert sowohl einen Temperaturbegriff als auch eine ausdrückliche Umrechnungsaktion. */
    fun isTemperatureConversionRequest(text: String): Boolean =
        containsTemperatureTerm(text) && containsWord(text, TEMPERATURE_CONVERSION_ACTIONS)

    fun containsTemperatureTerm(text: String): Boolean =
        containsWord(text, TEMPERATURE_WORDS)

    /** Recognizes a numeric temperature question with an explicit destination scale. */
    fun isNumericTemperatureQuestion(text: String): Boolean =
        containsTemperatureTerm(text) &&
            NUMERIC_TEMPERATURE_VALUE.containsMatchIn(text) &&
            NUMERIC_TEMPERATURE_DESTINATION.containsMatchIn(text)

    /** Verhindert leere Namen und ergänzt bei Kotlin-Schlüsselwörtern ein neutrales Suffix. */
    fun sanitizeKotlinIdentifier(name: String): String {
        val cleaned = name.trim()
        if (cleaned.isBlank()) return "Example"
        return if (cleaned.lowercase() in KOTLIN_KEYWORDS) "${cleaned}Item" else cleaned
    }
}
