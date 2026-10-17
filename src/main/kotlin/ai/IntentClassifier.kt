package ai

/** Liefert neben dem Intent auch Herkunft und Kennzahlen, damit Klassifikationsentscheidungen prüfbar bleiben. */
data class IntentPrediction(
    val intent: Intent,
    val confidence: Double,
    val method: String,
    val similarity: Double? = null,
    val margin: Double? = null,
    val topCandidate: Intent? = null
)

/** Converts user wording into the stable public [Intent] API. */
class IntentClassifier(
    private val supervisedClassifier: (String) -> IntentPrediction? = { null }
) {
    /** Liefert nur die Kategorie, wenn Aufrufer keine Diagnosedaten benötigen. */
    fun classify(prompt: String): Intent = classifyWithDetails(prompt).intent

    /** Wendet zuerst eindeutige Regeln, danach den überwachten Klassifikator und zuletzt Unknown an. */
    fun classifyWithDetails(prompt: String): IntentPrediction {
        val normalized = normalize(prompt)
        val requestsCreation = CREATION_PATTERN.containsMatchIn(normalized)
        if (ConversationResponder.isSmalltalk(normalized) &&
            !hasTaskIntentWords(normalized, requestsCreation)
        ) {
            return rulePrediction(Intent.Smalltalk)
        }
        classifyByRule(normalized, requestsCreation)?.let { return it }
        if (BARE_NUMERIC_FOLLOW_UP_PATTERN.matches(normalized)) {
            return IntentPrediction(Intent.Unknown, 0.0, "numeric-context-required")
        }
        val supervised = supervisedClassifier(normalized)
        if (
            supervised?.intent == Intent.GenerateFunction &&
            TextPatterns.containsTemperatureTerm(normalized) &&
            !TextPatterns.isTemperatureConversionRequest(normalized)
        ) {
            return IntentPrediction(Intent.Unknown, 0.0, "temperature-action-required")
        }
        return supervised ?: IntentPrediction(Intent.Unknown, 0.0, "none")
    }

    /** Behandelt explizite Wortmuster vor dem statistischen Modell, damit klare Aufgaben stabil bleiben. */
    @Suppress("ComplexMethod")
    private fun classifyByRule(normalized: String, requestsCreation: Boolean): IntentPrediction? {
        val intent = when {
            TextPatterns.containsWord(normalized, TOKEN_WORDS) -> Intent.Tokenize
            !requestsCreation && asksForExplanation(normalized) -> Intent.Explain
            TextPatterns.containsWord(normalized, TEST_WORDS) -> Intent.GenerateTest
            TextPatterns.containsWord(normalized, DATA_CLASS_WORDS) || normalized.contains("data class") ->
                Intent.GenerateDataClass
            TextPatterns.containsWord(normalized, CLASS_WORDS) -> Intent.GenerateClass
            TextPatterns.containsWord(normalized, FUNCTION_WORDS) ||
                TextPatterns.containsWord(normalized, MAX_WORDS) ||
                TextPatterns.containsWord(normalized, MIN_WORDS) ||
                TextPatterns.containsWord(normalized, DIFFERENCE_WORDS) ||
                TextPatterns.isNumericTemperatureQuestion(normalized) ||
                TextPatterns.isTemperatureConversionRequest(normalized) -> Intent.GenerateFunction
            TextPatterns.containsWord(normalized, MODEL_WORDS) &&
                TextPatterns.containsWord(normalized, DATA_FIELD_WORDS) -> Intent.GenerateDataClass
            else -> return null
        }
        return rulePrediction(intent)
    }

    /** Prüft, ob trotz Begrüßung ein Wort für eine konkrete Aufgabe enthalten ist. */
    private fun hasTaskIntentWords(normalized: String, requestsCreation: Boolean): Boolean =
        requestsCreation ||
            asksForExplanation(normalized) ||
            TextPatterns.isTemperatureConversionRequest(normalized) ||
        listOf(
            TOKEN_WORDS,
            TEST_WORDS,
            DATA_CLASS_WORDS,
            CLASS_WORDS,
            FUNCTION_WORDS,
            MAX_WORDS,
            MIN_WORDS,
            DIFFERENCE_WORDS
        )
            .any { TextPatterns.containsWord(normalized, it) }

    /** Erkennt Erklärungsfragen nur anhand der unterstützten expliziten Muster und Code-Themen. */
    private fun asksForExplanation(normalized: String): Boolean =
        EXPLANATION_PATTERN.containsMatchIn(normalized) ||
            (QUESTION_PREFIXES.any(normalized::startsWith) &&
                CODE_EXPLANATION_TOPIC_PATTERN.containsMatchIn(normalized)) ||
            EXPLICIT_EXPLANATION_QUESTION_PATTERN.containsMatchIn(normalized)

    /** Markiert eine eindeutige Regelentscheidung mit voller Regel-Konfidenz. */
    private fun rulePrediction(intent: Intent) = IntentPrediction(intent, 1.0, "rule", topCandidate = intent)

    /** Vereinheitlicht Umlaute, Großschreibung, Satzzeichen und Whitespace vor Regelprüfungen. */
    private fun normalize(value: String) = TextFold.fold(value)
        .replace(TextPatterns.PUNCTUATION, " ")
        .replace(TextPatterns.WHITESPACE, " ").trim()

    companion object {
        private val QUESTION_PREFIXES =
            listOf("was ", "wie ", "warum ", "wieso ", "weshalb ", "wodurch ", "welche ursache")
        private val TOKEN_WORDS = listOf("token", "tokens", "tokenisiere", "tokenizer", "tokenize", "vokabular")
        private val TEST_WORDS =
            listOf("test", "testen", "pruefe", "pruefen", "prüfe", "prüfen", "kontrolliere", "validiere", "verifiziere")
        private val DATA_CLASS_WORDS = listOf("datenklasse", "data-class")
        private val CLASS_WORDS = listOf("klasse", "class")
        private val FUNCTION_WORDS = listOf("funktion", "function", "fun")
        private val MAX_WORDS = listOf(
            "maximum", "maximal", "groesste", "groessten", "groesster", "groesstes",
            "groest", "largest", "max", "maximums"
        )
        private val MIN_WORDS = listOf(
            "minimum", "minimal", "kleinste", "kleinsten", "kleinster", "kleinstes",
            "smallest", "min", "minimums"
        )
        private val DIFFERENCE_WORDS = listOf(
            "differenz", "difference", "delta", "subtraktion", "subtrahiere", "subtract"
        )
        private val MODEL_WORDS = listOf("modell")
        private val DATA_FIELD_WORDS = listOf("name", "email", "feld", "felder")
        private val EXPLANATION_PATTERN = Regex("\\b(erklaer\\w*|erlaeuter\\w*|beschreib\\w*|versteh\\w*)\\b")
        private val EXPLICIT_EXPLANATION_QUESTION_PATTERN = Regex("\\b(was steckt hinter|was bedeutet)\\b")
        private val BARE_NUMERIC_FOLLOW_UP_PATTERN =
            Regex("^(?:und\\s+)?[+-]?\\d+(?:[.,]\\d+)?(?:\\s*(?:grad|°[fc]))?$")
        private val CODE_EXPLANATION_TOPIC_PATTERN =
            Regex(
                "\\b(data class|kotlin|code|klasse|datenklasse|methode|funktion|" +
                    "fehlermeld\\w*|vererbung|variable|datentyp|token|nullable)\\b"
            )
        private val CREATION_PATTERN = Regex(
            "\\b(erstelle?\\w*|schreib\\w*|lege\\w*\\s+.*\\ban|erzeuge?\\w*|" +
                "implementier\\w*|baue?\\w*|generier\\w*|entwickel\\w*|entwirf)\\b"
        )
    }
}
