package ai

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for prompt parsing, generated snippets, intent routing,
 * knowledge lookup, and the feedforward model's observable persistence behavior.
 */
class CodeAssistantBugFixTest {
    /** Creates an assistant with isolated temporary storage so tests cannot read the user's profile. */
    private fun isolatedAssistant() =
        CodeAssistant(AssistantConfig(Files.createTempDirectory("assistant-test-state")))

    @Test
    fun answerQuestionExplainsDataClassesInsteadOfGeneratingCode() {
        val assistant = isolatedAssistant()
        val response = assistant.handlePrompt("Was ist eine kotlin datenklasse?")

        assertTrue(response.startsWith("In Kotlin erstellt man eine Datenklasse"))
        assertFalse(response.trimStart().startsWith("data class"))
    }

    @Test
    fun explanationRulesCoverTopicsRecognizedByTheIntentClassifier() {
        val assistant = isolatedAssistant()
        val cases = listOf(
            "Wie funktioniert die Vererbung hier?" to "Vererbung ermöglicht",
            "Was bedeutet eine Variable?" to "Eine Variable bindet",
            "Was ist ein Datentyp?" to "Ein Datentyp legt fest",
            "Erklaere den Unterschied zwischen Liste und Menge" to "Mengen enthalten",
            "Was bedeutet diese Fehlermeldung?" to "Die genaue Ursache hängt",
            "Warum ist Kotlin statisch typisiert?" to "Kotlin ist statisch typisiert"
        )

        cases.forEach { (prompt, expectedContent) ->
            val response = assistant.handlePrompt(prompt)

            assertTrue(response.contains(expectedContent), "$prompt produced: $response")
            assertFalse(response.contains("Formuliere eine konkrete Anfrage"), "$prompt produced fallback: $response")
        }
    }

    @Test
    fun specializedExamplesAreAvailableThroughConversationRouting() {
        val cases = listOf(
            "Berechne pi" to "fun calculatePi",
            "Sortiere Zahlen absteigend" to "numbers.sortedDescending()",
            "Fibonacci berechnen" to "fun fibonacci",
            "Erstelle eine Klasse Pi" to "class Pi("
        )

        cases.forEach { (prompt, expectedContent) ->
            val result = isolatedAssistant().handleConversationTurn(prompt)

            assertTrue(result.response.contains(expectedContent), "$prompt produced: ${result.response}")
            assertFalse(result.awaitingClarification, "$prompt unexpectedly asked for clarification")
        }
    }

    @Test
    fun naturalTemperatureQuestionReturnsTheCalculatedValueWithoutClarification() {
        val assistant = isolatedAssistant()

        assertEquals(Intent.GenerateFunction, assistant.classifyIntent("Was sind 20 Grad in Fahrenheit?"))
        val result = assistant.handleConversationTurn("Was sind 20 Grad in Fahrenheit?")

        assertTrue(result.response.contains("20 °C entsprechen 68 °F"), result.response)
        assertFalse(result.awaitingClarification)
    }

    @Test
    fun changedTemperatureValueDoesNotReuseFuzzyAnswerForEarlierValue() {
        val assistant = isolatedAssistant()
        assistant.storeKnowledge(
            "Was sind 20 Grad in Fahrenheit?",
            "20 °C entsprechen 68 °F."
        )

        val result = assistant.handleConversationTurn("was sind 25 grad in fahrenheit")

        assertTrue(result.response.contains("25 °C entsprechen 77 °F"), result.response)
        assertFalse(result.response.contains("20 °C entsprechen 68 °F"), result.response)
        assertFalse(result.awaitingClarification)
    }

    @Test
    fun numericTemperatureCalculationOverridesExactSavedAnswer() {
        val assistant = isolatedAssistant()
        assistant.storeKnowledge(
            "Was sind 20 Grad in Fahrenheit?",
            "GESPEICHERTE ERKLAERUNG MIT 68 GRAD"
        )

        val response = assistant.handlePrompt("Was sind 20 Grad in Fahrenheit?")

        assertEquals("20 °C entsprechen 68 °F.", response)
        assertFalse(response.contains("GESPEICHERTE ERKLAERUNG"), response)
    }

    @Test
    fun temperatureQuestionSupportsReverseDirectionAndDecimalComma() {
        val assistant = isolatedAssistant()

        val response = assistant.handlePrompt("Was sind 20,5 Fahrenheit in Celsius?")

        assertTrue(response.contains("20.5 °F entsprechen -6.39 °C"), response)
    }

    @Test
    fun explicitTemperatureFunctionRequestStillGeneratesKotlin() {
        val response = isolatedAssistant().handlePrompt(
            "Erstelle eine Funktion, die 20 Celsius in Fahrenheit umrechnet"
        )

        assertTrue(response.contains("fun celsiusToFahrenheit(celsius: Double): Double"), response)
        assertFalse(response.contains("20 °C entsprechen"), response)
    }

    @Test
    fun specializedExampleRoutingDoesNotOverrideClassNamesOrClassFields() {
        val assistant = isolatedAssistant()
        val cases = listOf(
            "Erstelle eine Klasse Ordner" to "class Ordner(",
            "Erstelle eine Datenklasse Bestellung mit sortierter Liste" to "data class Bestellung("
        )

        cases.forEach { (prompt, expectedCode) ->
            val response = assistant.handlePrompt(prompt)

            assertTrue(response.contains(expectedCode), "$prompt produced: $response")
            assertFalse(response.contains("sortedDescending"), "$prompt produced sorting code: $response")
        }
    }

    @Test
    fun circumferenceRequestWithPiDoesNotReturnPiDigitGenerator() {
        val response = isolatedAssistant().handlePrompt("Berechne den Umfang mit Pi")

        assertTrue(response.contains("fun berechneKreisumfang(radius: Double)"), response)
        assertTrue(response.contains("return 2 * PI * radius"), response)
        assertFalse(response.contains("fun calculatePi"), response)
    }

    @Test
    fun guessFieldTypeDoesNotTreatListeAsBoolean() {
        val assistant = isolatedAssistant()
        val response = assistant.handlePrompt("Erstelle eine datenklasse Container mit Liste und Name")

        assertTrue(response.contains("liste: String") || response.contains("Liste: String"))
        assertFalse(response.contains(": Boolean"))
    }

    @Test
    fun tokenizePrefixIsCaseInsensitive() {
        val assistant = isolatedAssistant()
        val response = assistant.handlePrompt("Tokenisiere diesen Text: Hallo Welt")
        val sentenceResponse = assistant.handlePrompt("Zerlege den Satz: Ein kleiner Roboter lernt")

        assertTrue(response.contains("Text: Hallo Welt\n\nTokenliste"))
        assertFalse(response.lowercase().contains("tokenisiere diesen"))
        assertTrue(sentenceResponse.contains("Text: Ein kleiner Roboter lernt\n\nTokenliste"))
    }

    @Test
    fun feedforwardModelRoundTripUsesJsonFormat() {
        val storageDir = Files.createTempDirectory("assistant-ff")
        val assistant = CodeAssistant(AssistantConfig(storageDir))
        val model = assistant.trainFeedforwardLanguageModel(
            listOf("klasse und objekt", "objekt und methode"),
            contextSize = 2,
            hiddenSize = 4,
            epochs = 20
        )
        val path = storageDir.resolve("model.json")
        assistant.saveFeedforwardLanguageModel(model, path)
        val loaded = assistant.loadFeedforwardLanguageModel(path)

        assertNotNull(loaded)
        assertEquals(model.vocabulary, loaded.vocabulary)
        assertEquals(model.contextSize, loaded.contextSize)
        assertTrue(model.embeddings.contentDeepEquals(loaded.embeddings))
        assertTrue(model.w1.contentDeepEquals(loaded.w1))
        assertTrue(model.b1.contentEquals(loaded.b1))
        assertTrue(model.w2.contentDeepEquals(loaded.w2))
        assertTrue(model.b2.contentEquals(loaded.b2))
        assertEquals(
            model.predictNextToken(listOf("klasse", "und")),
            loaded.predictNextToken(listOf("klasse", "und"))
        )
        val copiedArrays = model.copy(
            embeddings = model.embeddings.map { it.copyOf() }.toTypedArray(),
            w1 = model.w1.map { it.copyOf() }.toTypedArray(),
            b1 = model.b1.copyOf(),
            w2 = model.w2.map { it.copyOf() }.toTypedArray(),
            b2 = model.b2.copyOf()
        )
        assertEquals(model, copiedArrays)
        assertEquals(model.hashCode(), copiedArrays.hashCode())
    }

    @Test
    fun unknownFeedforwardContextUsesZeroVector() {
        val model = FeedforwardLanguageModel(
            vocabulary = listOf("known", "other"),
            contextSize = 1,
            hiddenSize = 1,
            embeddingDim = 1,
            embeddings = arrayOf(doubleArrayOf(1.0), doubleArrayOf(0.0)),
            w1 = arrayOf(doubleArrayOf(1.0)),
            b1 = doubleArrayOf(0.0),
            w2 = arrayOf(doubleArrayOf(1.0, -1.0)),
            b2 = doubleArrayOf(-1.0, 0.0)
        )

        assertEquals("other", model.predictNextToken(listOf("unknown")))
    }

    @Test
    fun feedforwardModelCopyRebuildsVocabularyIndex() {
        val model = FeedforwardLanguageModel(
            vocabulary = listOf("old", "other"),
            contextSize = 1,
            hiddenSize = 1,
            embeddingDim = 1,
            embeddings = arrayOf(doubleArrayOf(1.0), doubleArrayOf(0.0)),
            w1 = arrayOf(doubleArrayOf(1.0)),
            b1 = doubleArrayOf(0.0),
            w2 = arrayOf(doubleArrayOf(1.0, -1.0)),
            b2 = doubleArrayOf(-1.0, 0.0)
        )

        val renamedVocabulary = model.copy(vocabulary = listOf("new", "other"))

        assertEquals("new", renamedVocabulary.predictNextToken(listOf("new")))
    }

    @Test
    fun cosineSimilarityRejectsMismatchedDimensions() {
        val assistant = isolatedAssistant()
        val left = List(32) { 1.0 }
        val right = List(16) { 1.0 }
        val method = CodeAssistant::class.java.getDeclaredMethod(
            "cosineSimilarityVector",
            List::class.java,
            List::class.java
        )
        method.isAccessible = true
        val result = method.invoke(assistant, left, right) as Double

        assertEquals(0.0, result)
    }

    @Test
    fun classifyIntentUsesExplicitDataModelRule() {
        val storageDir = Files.createTempDirectory("assistant-intent")
        Files.writeString(
            storageDir.resolve("tokenizer_corpus.txt"),
            listOf(
                "erstelle eine datenklasse fuer benutzer",
                "datenklasse mit feldern name und email",
                "data class modell fuer daten"
            ).joinToString(System.lineSeparator())
        )
        val assistant = CodeAssistant(AssistantConfig(storageDir, intentSimilarityThreshold = 0.05))
        val prediction = assistant.classifyIntentWithDetails("Bitte ein Modell mit Name und Email")

        assertEquals(Intent.GenerateDataClass, prediction.intent)
        assertEquals("rule", prediction.method)
    }

    @Test
    fun learnedKnowledgeSurvivesRestartAndIsRecalled() {
        val storageDir = Files.createTempDirectory("assistant-knowledge")
        val config = AssistantConfig(storageDir)
        val first = CodeAssistant(config)
        first.storeKnowledge(
            "Wie soll unser Benutzer gespeichert werden?",
            "Verwende data class Benutzer(val name: String, val email: String)",
            listOf("projektregel")
        )

        val second = CodeAssistant(config)
        val matches = second.recallKnowledge("Wie soll unser Benutzer gespeichert werden?", 1)

        assertEquals(1, second.knowledgeEntryCount())
        assertEquals("projektregel", matches.single().tags.single())
        assertTrue(matches.single().similarity > 0.99)
        assertTrue(second.handlePrompt("Wie soll unser Benutzer gespeichert werden?").contains("data class Benutzer"))
    }

    @Test
    fun knowledgeRecallRecomputesStoredEmbeddingsAfterEmbeddingSpaceChanges() {
        val directory = Files.createTempDirectory("assistant-refresh-knowledge-embeddings")
        var embeddingVersion = 0
        val store = KnowledgeStore(
            AssistantConfig(directory),
            embed = {
                if (embeddingVersion == 0) listOf(1.0, 0.0) else listOf(0.0, 1.0)
            },
            similarity = { left, right -> left.indices.sumOf { left[it] * right[it] } }
        )

        store.learn("stored prompt", "stored answer")
        embeddingVersion = 1

        assertEquals(1.0, store.recall("different query", 1).single().similarity)
    }

    @Test
    fun learningReplacesExistingPromptInsteadOfDuplicatingIt() {
        val storageDir = Files.createTempDirectory("assistant-knowledge-update")
        val assistant = CodeAssistant(AssistantConfig(storageDir))

        assistant.storeKnowledge("Welche Farbe?", "Blau")
        assistant.storeKnowledge("Welche Farbe?", "Gruen")

        assertEquals(1, assistant.knowledgeEntryCount())
        assertEquals("Gruen", assistant.recallKnowledge("Welche Farbe?", 1).single().answer)
    }

    @Test
    fun ambiguousKnowledgeMatchesDoNotOverrideNormalGeneration() {
        val assistant = CodeAssistant(AssistantConfig(Files.createTempDirectory("assistant-ambiguous-knowledge")))
        assistant.storeKnowledge("Wie speichere ich einen Benutzer?", "STORED_USER_ANSWER")

        val response = assistant.handlePrompt("Schreibe eine Funktion, die die Kreiszahl berechnet")

        assertFalse(response.contains("STORED_USER_ANSWER"))
    }

    @Test
    fun singleHighConfidenceKnowledgeMatchCanAnswerParaphrasedPrompt() {
        val assistant = CodeAssistant(AssistantConfig(Files.createTempDirectory("assistant-single-knowledge")))
        assistant.storeKnowledge("Wie speichere ich einen Benutzer?", "STORED_USER_ANSWER")

        val matches = assistant.recallKnowledge("Wie kann ich einen Benutzer speichern?", 1)
        val response = assistant.handlePrompt("Wie kann ich einen Benutzer speichern?")

        assertTrue(matches.single().similarity >= 0.90, "Observed similarity: ${matches.single().similarity}")
        assertEquals("STORED_USER_ANSWER", response)
    }

    @Test
    fun fuzzyKnowledgeRequiresTheSingleMatchConfidenceThresholdEvenWithSeveralCandidates() {
        val config = AssistantConfig(
            storagePath = Files.createTempDirectory("assistant-fuzzy-confidence"),
            knowledgeSimilarityThreshold = 0.0,
            knowledgeMarginThreshold = 0.0,
            knowledgeMinimumTokenOverlap = 0.0,
            knowledgeSingleMatchSimilarityThreshold = 0.999999
        )
        val assistant = CodeAssistant(config)
        assistant.storeKnowledge("Wie funktioniert ein quartz widget alpha?", "FIRST_STORED_ANSWER")
        assistant.storeKnowledge("Wie funktioniert ein quartz widget beta?", "SECOND_STORED_ANSWER")
        val matches = assistant.recallKnowledge("Wie funktioniert ein quartz widget gamma?", 2)

        assertEquals(2, matches.size)
        assertTrue(matches.first().similarity < config.knowledgeSingleMatchSimilarityThreshold)
        assertFalse(
            assistant.handlePrompt("Wie funktioniert ein quartz widget gamma?") in
                setOf("FIRST_STORED_ANSWER", "SECOND_STORED_ANSWER")
        )
    }

    @Test
    fun intentPredictionExposesMethodSimilarityAndMargin() {
        val assistant = CodeAssistant(AssistantConfig(Files.createTempDirectory("assistant-prediction")))
        val prediction = assistant.classifyIntentWithDetails("Vollkommen unbekannte Formulierung")

        assertEquals("supervised-tfidf", prediction.method)
        assertNotNull(prediction.similarity)
        assertNotNull(prediction.margin)
        assertTrue(prediction.confidence.isFinite())
        assertTrue(prediction.margin.isFinite())
    }

    @Test
    fun labelledTrainingExamplesAreIncludedInSupervisedIntentModel() {
        val storageDir = Files.createTempDirectory("assistant-labelled-intent")
        val assistant = CodeAssistant(AssistantConfig(storageDir))
        assistant.addTrainingExample(
            TrainingExample(
                prompt = "quartz nebula",
                expectedIntent = Intent.GenerateFunction,
                expectedAnswer = "fun quartzNebula() = Unit"
            )
        )

        assertEquals(Intent.GenerateFunction, assistant.classifyIntent("quartz nebula"))
    }

    @Test
    fun questionPunctuationDoesNotOverrideExplicitCodeGeneration() {
        val assistant = isolatedAssistant()

        assertEquals(Intent.GenerateFunction, assistant.classifyIntent("Kannst du eine Funktion zum Sortieren schreiben?"))
        assertEquals(Intent.Tokenize, assistant.classifyIntent("Wie sieht das Vokabular für diesen Satz aus?"))
        assertEquals(Intent.Explain, assistant.classifyIntent("Was ist eine Kotlin-Datenklasse?"))
        assertEquals(Intent.GenerateTest, assistant.classifyIntent("Schreibe einen Test für leere Eingaben"))
    }

    @Test
    fun greetingUsesSmalltalkAndLongCodeRequestDoesNot() {
        val assistant = isolatedAssistant()

        assertEquals(Intent.Smalltalk, assistant.classifyIntent("Hallo"))
        assertEquals(Intent.Smalltalk, assistant.classifyIntent("Guten Morgen"))
        assertEquals(Intent.GenerateClass, assistant.classifyIntent("Hallo Klasse erstellen"))
        assertEquals(
            Intent.GenerateClass,
            assistant.classifyIntent("Hallo, kannst du bitte eine Klasse Person erstellen?")
        )
        assertTrue(assistant.handlePrompt("Hallo").contains("lokaler Kotlin-Code-Assistent"))
    }

    @Test
    fun unknownPromptGetsHonestFallbackInsteadOfGeneratedStub() {
        val assistant = isolatedAssistant()

        val response = assistant.handlePrompt("asdkfj qwertz")

        assertEquals(Intent.Unknown, assistant.classifyIntent("asdkfj qwertz"))
        assertTrue(response.contains("nicht verstanden"))
        assertFalse(response.contains("fun main"))
    }
}
