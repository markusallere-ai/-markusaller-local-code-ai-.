package ai

internal data class ResolvedPrompt(
    val response: String,
    val intent: Intent,
    val usedKnowledge: Boolean
)

internal object ConversationRules {
    private val unhelpfulClarifications = setOf(
        "ja", "nein", "ne", "ok", "okay", "aha", "hae", "hm", "hmm",
        "keine ahnung", "weiss nicht", "weiss ich nicht", "keine idee",
        "verstehe ich nicht", "was"
    )
    private val affirmativeConfirmations =
        setOf("ja", "j", "yes", "jo", "ja bitte", "ja gerne", "speichern", "richtig")
    private val negativeConfirmations =
        setOf("nein", "ne", "no", "nein danke", "nicht speichern", "falsch")

    fun isExplicitCodeRequest(prediction: IntentPrediction): Boolean =
        prediction.method == "rule" &&
            prediction.intent in setOf(
                Intent.GenerateClass,
                Intent.GenerateDataClass,
                Intent.GenerateFunction,
                Intent.GenerateTest,
                Intent.Tokenize
            )

    fun isCancellation(prompt: String): Boolean =
        prompt.equals("abbrechen", ignoreCase = true) || prompt.equals("/cancel", ignoreCase = true)

    fun isUnhelpfulClarification(value: String): Boolean =
        normalizeClarification(value) in unhelpfulClarifications

    fun isConfirmation(value: String, affirmative: Boolean): Boolean {
        val answers = if (affirmative) affirmativeConfirmations else negativeConfirmations
        return normalizeClarification(value) in answers
    }

    fun confirmationResult(prompt: String, answer: String) =
        ConversationTurnResult(
            "Ich habe verstanden: \"$prompt\" bedeutet \"$answer\". Soll ich das lokal speichern? " +
                "Antworte mit \"ja\" oder \"nein\".",
            awaitingConfirmation = true
        )

    private fun normalizeClarification(value: String): String = TextFold.fold(value)
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

/**
 * Owns prompt resolution entry points and the state machine for clarification
 * and knowledge confirmation. Generation and persistence stay injected.
 */
internal class ConversationCoordinator(
    private val classify: (String) -> IntentPrediction,
    private val resolve: (String) -> ResolvedPrompt,
    private val storeKnowledge: (String, String, List<String>) -> Unit
) {
    private val lock = Any()
    private val state = ConversationState()

    fun handlePrompt(prompt: String): String {
        val normalized = prompt.trim()
        if (normalized.isBlank()) {
            return "Bitte gib eine konkrete Kotlin-Anfrage ein. Zum Beispiel: " +
                "'Erstelle eine Datenklasse für Nutzer mit Name und Alter.'"
        }
        return resolve(normalized).response
    }

    fun handleConversationTurn(prompt: String): ConversationTurnResult = synchronized(lock) {
        val normalized = prompt.trim()
        if (normalized.isBlank()) {
            return@synchronized ConversationTurnResult("Bitte schreibe eine Nachricht.")
        }

        handlePendingKnowledge(normalized)?.let { return@synchronized it }
        handlePendingPrompt(normalized)?.let { return@synchronized it }

        ConversationResponder.answerFollowUp(
            normalized,
            state.lastTopicPrompt,
            state.lastTopicResponse
        )?.let { response ->
            state.rememberTurn(normalized, response, isSmalltalk = true)
            return@synchronized ConversationTurnResult(response)
        }

        val contextualPrompt =
            ConversationResponder.contextualizeTemperatureFollowUp(normalized, state.lastTopicPrompt)
        val resolved = resolve(contextualPrompt ?: normalized)
        if (resolved.intent == Intent.Unknown && !resolved.usedKnowledge) {
            state.askForClarification(normalized)
            return@synchronized ConversationTurnResult(
                "Das kenne ich noch nicht: \"$normalized\". Was bedeutet das? Erklär es mir kurz; " +
                    "ich frage danach, ob ich deine Erklärung lokal speichern soll. " +
                    "Schreibe \"abbrechen\", wenn du keine Erklärung hinzufügen möchtest.",
                awaitingClarification = true
            )
        }
        state.rememberTurn(
            contextualPrompt ?: normalized,
            resolved.response,
            resolved.intent == Intent.Smalltalk
        )
        ConversationTurnResult(resolved.response)
    }

    fun cancelPendingClarification() = synchronized(lock) {
        state.clear()
    }

    fun resetConversation() = synchronized(lock) {
        state.clearConversation()
    }

    private fun handlePendingKnowledge(prompt: String): ConversationTurnResult? {
        val proposed = state.pendingKnowledge ?: return null
        return when {
            ConversationRules.isConfirmation(prompt, affirmative = true) -> {
                storeKnowledge(proposed.first, proposed.second, listOf("conversation-clarification"))
                state.clear()
                ConversationTurnResult(
                    "Gespeichert: \"${proposed.first}\" bedeutet \"${proposed.second}\".",
                    learnedKnowledge = true
                )
            }
            ConversationRules.isConfirmation(prompt, affirmative = false) -> {
                state.clear()
                ConversationTurnResult("Okay, ich speichere diese Erklärung nicht.")
            }
            ConversationRules.isCancellation(prompt) -> {
                state.clear()
                ConversationTurnResult("Okay, ich verwerfe den vorgeschlagenen Wissenseintrag.")
            }
            else -> handlePendingKnowledgeResponse(prompt, proposed)
        }
    }

    private fun handlePendingKnowledgeResponse(
        prompt: String,
        proposed: Pair<String, String>
    ): ConversationTurnResult? {
        val prediction = classify(prompt)
        return when {
            isIndependentTurn(prediction) -> {
                state.clearPendingKnowledge()
                null
            }
            ConversationRules.isUnhelpfulClarification(prompt) -> ConversationTurnResult(
                "Antworte bitte mit \"ja\" zum Speichern, \"nein\" zum Verwerfen " +
                    "oder \"abbrechen\" zum Beenden.",
                awaitingConfirmation = true
            )
            else -> {
                state.proposeKnowledge(proposed.first, prompt)
                ConversationRules.confirmationResult(proposed.first, prompt)
            }
        }
    }

    private fun handlePendingPrompt(prompt: String): ConversationTurnResult? {
        val originalPrompt = state.pendingPrompt
        return when {
            originalPrompt == null -> null
            ConversationRules.isCancellation(prompt) -> {
                state.clearPendingPrompt()
                ConversationTurnResult("Okay, ich verwerfe die offene Rückfrage.")
            }
            else -> handlePendingPromptResponse(prompt, originalPrompt)
        }
    }

    private fun handlePendingPromptResponse(
        prompt: String,
        originalPrompt: String
    ): ConversationTurnResult? {
        val prediction = classify(prompt)
        return when {
            isIndependentTurn(prediction) -> {
                state.clearPendingPrompt()
                null
            }
            ConversationRules.isUnhelpfulClarification(prompt) -> ConversationTurnResult(
                "Das reicht noch nicht als Erklärung. " +
                    "Beschreibe bitte kurz, was \"$originalPrompt\" bedeutet; " +
                    "mit \"abbrechen\" kannst du die Rückfrage verwerfen.",
                awaitingClarification = true
            )
            else -> {
                state.proposeKnowledge(originalPrompt, prompt)
                ConversationRules.confirmationResult(originalPrompt, prompt)
            }
        }
    }

    private fun isIndependentTurn(prediction: IntentPrediction): Boolean =
        ConversationRules.isExplicitCodeRequest(prediction) || prediction.intent == Intent.Smalltalk
}
