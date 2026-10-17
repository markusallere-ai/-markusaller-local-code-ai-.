package ai

/** Deterministic dialogue replies and narrowly scoped references to the previous turn. */
internal object ConversationResponder {
    private const val MAX_SMALLTALK_WORDS = 8
    private val smalltalkPatterns = listOf(
        Regex("^(wie geht es dir|wie gehts dir|wie geht s dir|wie geht es|wie gehts)( heute)?$"),
        Regex("^(was kannst du|was kannst du denn|was machst du|wer bist du|was bist du)( eigentlich)?$"),
        Regex("^(bist du eine ki|bist du eine kuenstliche intelligenz|bist du echt)$"),
        Regex("^(danke|vielen dank|danke dir|das hilft|super danke)( dir| schoen)?$"),
        Regex("^(tschues|tschuess|auf wiedersehen|bis bald|ciao|bye)$"),
        Regex("^(mir geht es (gut|prima|schlecht)|mir gehts (gut|prima|schlecht))( danke)?$")
    )
    private val greetingPattern =
        Regex("^(hallo|hi|hey|servus|moin|na|guten morgen|guten tag|guten abend)( zusammen)?$")
    private val greetingQuestionPattern =
        Regex("^(hallo|hi|hey|servus|moin|na) wie geht( es dir|s dir)( heute)?$")
    private val repeatPattern =
        Regex("^(wiederhole das|wiederhol das|was hast du gerade gesagt|was war deine letzte antwort|nochmal bitte)$")
    private val topicPattern =
        Regex("^(worueber haben wir gesprochen|was war das thema|worum ging es gerade)$")
    private val expandPattern =
        Regex("^(erzaehl mehr|erzaehl mehr dazu|erklaer das genauer|erklaere das genauer|was meinst du damit)$")

    fun isSmalltalk(normalizedPrompt: String): Boolean {
        val normalized = normalize(normalizedPrompt)
        return normalized.split(" ").size <= MAX_SMALLTALK_WORDS &&
            (greetingPattern.matches(normalized) || greetingQuestionPattern.matches(normalized) ||
                smalltalkPatterns.any { it.matches(normalized) } ||
                repeatPattern.matches(normalized) || topicPattern.matches(normalized) ||
                expandPattern.matches(normalized))
    }

    fun reply(prompt: String): String {
        val normalized = normalize(prompt)
        return when {
            greetingPattern.matches(normalized) ->
                "Hallo! Ich bin Markusaller, ein lokaler Kotlin-Code-Assistent. Was möchtest du besprechen?"
            isHowAreYou(normalized) ->
                "Danke der Nachfrage! Ich habe keine Gefühle, bin aber bereit zu helfen. Wie geht es dir?"
            isCapabilityQuestion(normalized) ->
                "Ich bin Markusaller, ein lokaler, regelbasierter Assistent. Ich kann Kotlin-Beispiele " +
                    "erstellen, unterstützte Code-Themen erklären und einige Berechnungen durchführen. " +
                    "Ich bin kein frei formulierendes Sprachmodell und habe keinen Webzugriff."
            isThanks(normalized) ->
                "Gern! Wenn du möchtest, können wir mit einer weiteren Frage weitermachen."
            isFarewell(normalized) ->
                "Bis bald! Du kannst jederzeit mit einer neuen Frage weitermachen."
            normalized.startsWith("mir geht") ->
                "Danke, dass du es erzählst. Wobei kann ich dir helfen?"
            else -> "Hallo! Ich bin ein lokaler Kotlin-Code-Assistent. Was möchtest du besprechen?"
        }
    }

    fun answerFollowUp(
        prompt: String,
        previousPrompt: String?,
        previousResponse: String?
    ): String? {
        val normalized = normalize(prompt)
        return when {
            !repeatPattern.matches(normalized) && !topicPattern.matches(normalized) &&
                !expandPattern.matches(normalized) -> null
            previousPrompt == null || previousResponse == null ->
                "Wir haben gerade erst begonnen. Frag mich zum Beispiel nach Kotlin-Code, " +
                    "einer Code-Erklärung oder einer unterstützten Berechnung."
            repeatPattern.matches(normalized) -> "Meine letzte Antwort war:\n$previousResponse"
            topicPattern.matches(normalized) ->
                "Zuletzt ging es um „$previousPrompt“.\nMeine Antwort dazu war:\n$previousResponse"
            else ->
                "Du beziehst dich auf „$previousPrompt“. Meine letzte Antwort war:\n$previousResponse\n" +
                    "Ich kann nur in meinen unterstützten Themen darauf aufbauen; stelle gern eine konkrete Folgefrage."
        }
    }

    fun contextualizeTemperatureFollowUp(prompt: String, previousPrompt: String?): String? {
        val previousTarget = previousPrompt?.let {
            Regex("\\b(?:in|zu|nach)\\s+(?:grad\\s+)?(fahrenheit|celsius)\\b")
                .find(normalize(it))?.groupValues?.get(1)
        }
        val value = Regex("^(?:und\\s+)?([+-]?\\d+(?:[.,]\\d+)?)\\s*(?:grad|°[fc])?$")
            .matchEntire(normalize(prompt))?.groupValues?.get(1)
        return if (previousTarget == null || value == null) null
        else "Was sind $value Grad in $previousTarget?"
    }

    private fun isHowAreYou(prompt: String): Boolean =
        prompt.startsWith("wie geht") || greetingQuestionPattern.matches(prompt)

    private fun isCapabilityQuestion(prompt: String): Boolean =
        prompt.startsWith("was kannst") || prompt.startsWith("was machst") ||
            prompt.startsWith("wer bist") || prompt.startsWith("was bist") || prompt.startsWith("bist du")

    private fun isThanks(prompt: String): Boolean =
        prompt.startsWith("danke") || prompt.startsWith("vielen dank") ||
            prompt.startsWith("das hilft") || prompt.startsWith("super danke")

    private fun isFarewell(prompt: String): Boolean =
        prompt.startsWith("tsch") || prompt.startsWith("auf wiedersehen") ||
            prompt.startsWith("bis bald") || prompt == "ciao" || prompt == "bye"

    private fun normalize(value: String): String = TextFold.fold(value)
        .replace(Regex("[^\\p{L}\\p{N}°+.,\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
