package ai

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers tokenizer and embedding behavior, including persistence, deterministic retraining,
 * sentence boundaries, and bounded language-model continuations.
 */
class CodeAssistantTokenizerTest {
    /** Creates a fresh assistant profile for each test instance. */
    private fun isolatedAssistant() =
        CodeAssistant(AssistantConfig(Files.createTempDirectory("assistant-tokenizer-test")))

    private val assistant = isolatedAssistant()

    @Test
    fun supplementalCorpusIsCuratedAndCoversMultipleProgrammingLanguages() {
        val corpus = SupplementalTrainingCorpus.sentences

        assertTrue(corpus.size >= 50)
        assertTrue(corpus.all(String::isNotBlank))
        assertEquals(corpus.size, corpus.map(TextFold::fold).distinct().size)
        assertTrue(corpus.any { "Kotlin" in it && "Java" in it })
        assertTrue(corpus.any { "C#" in it })
        assertTrue(corpus.any { "C++" in it })
        assertTrue(corpus.any { "Python" in it })
        assertTrue(corpus.any { "JavaScript" in it })
        assertTrue(corpus.size >= 120)
        assertTrue(corpus.any { "Folgefrage" in it && "Gespräch" in it })
        assertTrue(corpus.any { "Gesprächskontext" in it })
    }

    @Test
    fun legacyProjectCorpusHasNoEmptyOrFoldedDuplicateSentences() {
        val corpus = Files.readAllLines(Path.of("tokenizer_corpus.txt")).map(String::trim)

        assertTrue(corpus.isNotEmpty())
        assertTrue(corpus.all(String::isNotEmpty))
        assertEquals(corpus.size, corpus.map(TextFold::fold).distinct().size)
    }

    @Test
    fun classifyIntentRecognizesCodeGenerationRequests() {
        val intent = assistant.classifyIntent("Erstelle eine Klasse Person mit Name und Alter")

        assertEquals(Intent.GenerateClass, intent)
    }

    @Test
    fun sortingFunctionUsesRequestedOrder() {
        val generator = CodeGenerator(BpeTokenizer())
        val cases = listOf(
            "Sortiere Zahlen" to "numbers.sorted()",
            "Sortiere Zahlen aufsteigend" to "numbers.sorted()",
            "Sortiere Zahlen absteigend" to "numbers.sortedDescending()",
            "Sortiere Zahlen descending" to "numbers.sortedDescending()",
            "Sortiere Zahlen: größten zuerst" to "numbers.sortedDescending()"
        )

        cases.forEach { (prompt, expectedCall) ->
            val generated = generator.generate(prompt, Intent.Unknown)

            assertTrue(generated.contains("return $expectedCall"), generated)
        }
    }

    @Test
    fun generatedTestUsesConstructorArgumentsForClasses() {
        val response = assistant.handlePrompt("Erstelle einen Test für die Klasse Person mit Name und Alter")

        assertTrue(response.contains("val item = Person("))
        assertTrue(response.contains("assertEquals(\"Example\", item.name)"))
        assertTrue(response.contains("assertEquals(0, item.age)"))
    }

    @Test
    fun tokenizerFoldsCaseAndGermanDiacriticsConsistently() {
        assertEquals(assistant.tokenizeWithEmbeddings("Planung").tokens, assistant.tokenizeWithEmbeddings("planung").tokens)
        assertEquals(assistant.tokenizeWithEmbeddings("größer").tokens, assistant.tokenizeWithEmbeddings("groesser").tokens)
    }

    @Test
    fun tokenizerKeepsSupplementaryUnicodeCharactersIntact() {
        val tokenizer = BpeTokenizer()
        val input = "🚀👩‍💻"
        tokenizer.train(listOf(input), maxMergeRules = 0)

        assertEquals(input, tokenizer.tokenize(input).joinToString(""))
        assertEquals(listOf("🚀", "👩", "\u200d", "💻"), tokenizer.tokenize(input))
    }

    @Test
    fun tokenizeWithEmbeddingsReturnsAlignedTokensIdsAndVectors() {
        val result = assistant.tokenizeWithEmbeddings("Hallo 123 😄 english")

        assertTrue(result.tokens.isNotEmpty())
        assertEquals(result.ids.size, result.tokens.size)
        assertEquals(result.embeddings.size, result.ids.size)
        assertTrue(result.embeddings.all { it.size == 32 })
        assertTrue(result.embeddings.any { it.isNotEmpty() })
    }

    @Test
    fun tokenizerRejectsEmbeddingLookupWithMismatchedTokenAndId() {
        val tokenizer = BpeTokenizer()
        tokenizer.train(listOf("alpha beta"), maxMergeRules = 5000)
        val alphaToken = tokenizer.tokenize("alpha").first()
        val betaToken = tokenizer.tokenize("beta").first()

        assertFailsWith<IllegalArgumentException> {
            tokenizer.embeddingForToken(alphaToken, tokenizer.tokenId(betaToken))
        }
    }

    @Test
    fun settingKnownTokenEmbeddingKeepsFixedDimension() {
        val tokenizer = BpeTokenizer()
        tokenizer.train(listOf("known known known"), maxMergeRules = 100)
        val (token, id) = tokenizer.vocabularySnapshot().first { it.second > 0 }

        tokenizer.setEmbeddingForToken(token, listOf(0.25, -0.5))

        assertEquals(32, tokenizer.embeddingForId(id).size)
        assertEquals(0.25, tokenizer.embeddingForId(id)[0])
        assertEquals(-0.5, tokenizer.embeddingForId(id)[1])
        assertTrue(tokenizer.embeddingForId(id).drop(2).all { it == 0.0 })
    }

    @Test
    fun settingUnknownTokenEmbeddingDoesNotMutateUnknownVector() {
        val tokenizer = BpeTokenizer()
        tokenizer.train(listOf("known known known"), maxMergeRules = 100)
        val unknownEmbedding = tokenizer.embeddingForId(tokenizer.unknownId())

        assertFailsWith<IllegalArgumentException> {
            tokenizer.setEmbeddingForToken("not-in-vocabulary", listOf(1.0))
        }

        assertEquals(unknownEmbedding, tokenizer.embeddingForId(tokenizer.unknownId()))
    }

    @Test
    fun updatingEmbeddingRejectsInvalidIdAndNonFiniteValues() {
        val tokenizer = BpeTokenizer()
        tokenizer.train(listOf("known known known"), maxMergeRules = 100)
        val initial = tokenizer.embeddingForId(0).toDoubleArray()

        assertFailsWith<IllegalArgumentException> {
            tokenizer.updateEmbedding(-1, doubleArrayOf(1.0))
        }
        assertFailsWith<IllegalArgumentException> {
            tokenizer.updateEmbedding(0, doubleArrayOf(Double.NaN))
        }

        assertEquals(initial.toList(), tokenizer.embeddingForId(0))
    }

    @Test
    fun defaultAssistantInitializationDoesNotWriteBrainSnapshot() {
        val storage = Files.createTempDirectory("assistant-no-brain-snapshot")
        val config = AssistantConfig(storage)

        CodeAssistant(config)

        assertFalse(Files.exists(config.brainStateFile()))
    }

    @Test
    fun tokenizerStressTestHandlesUnknownWordsNumbersAndEmoji() {
        val report = assistant.runTokenizerStressTest(
            listOf(
                "mxyzptlk",
                "12345",
                "🚀",
                "Hello world",
                "Kotlin ❤️",
                "München 2026"
            )
        )

        assertEquals(6, report.size)
        assertTrue(report.all { it.tokens.isNotEmpty() || it.input.isBlank() })
        assertTrue(report.all { it.ids.size == it.tokens.size })
        assertTrue(report.any { it.unknownTokenCount > 0 })
    }

    @Test
    fun skipGramRejectsNegativeTrainingParameters() {
        val tokenizer = BpeTokenizer()
        tokenizer.train(listOf("alpha beta alpha beta"), maxMergeRules = 100)

        assertFailsWith<IllegalArgumentException> {
            tokenizer.trainSkipGramEmbeddings(listOf("alpha beta"), epochs = -1, windowSize = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            tokenizer.trainSkipGramEmbeddings(listOf("alpha beta"), epochs = 1, windowSize = -1)
        }
    }

    @Test
    fun generateTextContinuationProducesContextualWords() {
        val assistantWithCorpus = isolatedAssistant()
        val continuation = assistantWithCorpus.generateTextContinuation("eine klasse", 5)

        assertTrue(continuation.isNotBlank())
        assertTrue(continuation.split(Regex("\\s+")).any { it.isNotBlank() })
    }

    @Test
    fun textContinuationRespectsZeroTokenLimit() {
        assertEquals("klasse", assistant.generateTextContinuation("klasse", maxTokens = 0))
    }

    @Test
    fun continuationPrefersMatchingTrigramOverMoreFrequentUnrelatedBigram() {
        val storageDir = Files.createTempDirectory("assistant-trigram")
        Files.writeString(
            storageDir.resolve("tokenizer_corpus.txt"),
            (List(2) { "alpha beta correct" } + List(10) { "gamma beta distractor" })
                .joinToString(System.lineSeparator())
        )
        val assistantWithCorpus = CodeAssistant(AssistantConfig(storageDir))

        assertEquals("alpha beta correct", assistantWithCorpus.generateTextContinuation("alpha beta", 1))
    }

    @Test
    fun defaultAssistantStorageIsOutsideTheProjectWorkingDirectory() {
        val config = AssistantConfig()
        val expectedDirectory = Path.of(System.getProperty("user.home"), ".markusaller")

        assertEquals(expectedDirectory, config.storagePath)
        assertTrue(
            listOf(
                config.tokenizerFile(),
                config.tokenizerCorpusFile(),
                config.brainStateFile(),
                config.feedforwardModelFile(),
                config.knowledgeFile(),
                config.trainingExamplesFile(),
                config.tokenizerFingerprintFile(),
                config.embeddingFingerprintFile()
            ).all { it.parent == expectedDirectory }
        )
    }

    /** Eine direkte API-Nutzung mit deaktiviertem Auto-Init darf vorhandenen Tokenizerzustand nicht leeren. */
    @Test
    fun deferredInitializationPreservesAnExistingTokenizerWhenSettingAnEmbedding() {
        val directory = Files.createTempDirectory("assistant-deferred-initialization")
        val initialized = CodeAssistant(AssistantConfig(storagePath = directory))
        initialized.tokenizeWithEmbeddings("klasse")

        val deferred = CodeAssistant(
            AssistantConfig(storagePath = directory, autoInitialize = false)
        )
        deferred.setEmbeddingForToken("klasse", List(32) { index -> if (index == 0) 1.0 else 0.0 })

        val restarted = CodeAssistant(AssistantConfig(storagePath = directory))
        assertTrue(restarted.tokenizeWithEmbeddings("klasse").tokens.contains("klasse"))
    }

    @Test
    fun feedforwardLanguageModelLearnsTinyCorpus() {
        val trainingCorpus = listOf(
            "klasse und objekt",
            "klasse und funktion",
            "objekt und methode",
            "funktion und methode",
            "methode und klasse"
        )

        val assistant = isolatedAssistant()
        val model = assistant.trainFeedforwardLanguageModel(trainingCorpus, contextSize = 2, hiddenSize = 8, epochs = 200, learningRate = 0.05)

        assertTrue(model.lossHistory.isNotEmpty())
        assertTrue(model.lossHistory.last() < model.lossHistory.first())
        assertTrue(model.predictNextToken(listOf("klasse", "und")).isNotBlank())
    }

    @Test
    fun languageModelGeneratorProducesBoundedDeterministicContinuation() {
        val assistant = isolatedAssistant()
        val corpus = listOf(
            "klasse und objekt",
            "klasse und funktion",
            "objekt und methode",
            "funktion und methode",
            "methode und klasse"
        )

        val first = assistant.generateWithLanguageModel(
            corpus = corpus,
            seed = "klasse und",
            maxTokens = 4,
            contextSize = 2,
            hiddenSize = 8,
            epochs = 100
        )
        val second = assistant.generateWithLanguageModel(
            corpus = corpus,
            seed = "klasse und",
            maxTokens = 4,
            contextSize = 2,
            hiddenSize = 8,
            epochs = 100
        )

        assertTrue(first.isNotBlank())
        assertEquals(first, second)
        val seedTokenCount = assistant.tokenizeWithEmbeddings("klasse und").tokens.size
        val outputTokenCount = assistant.tokenizeWithEmbeddings(first).tokens.size
        assertTrue(outputTokenCount <= seedTokenCount + 4)
    }

    @Test
    fun brainStateSnapshotIsWrittenToProjectFile() {
        val path = Files.createTempDirectory("assistant-brain-state").resolve("brain_state_test.txt")
        Files.deleteIfExists(path)

        val writtenPath = assistant.writeBrainStateSnapshot(path)

        assertEquals(path, writtenPath)
        assertTrue(Files.exists(path))
        val content = Files.readString(path)
        assertTrue(content.contains("\"vocabSize\""))
        assertTrue(content.contains("\"unknownId\""))
        assertTrue(content.contains("\"embeddingShape\""))
    }
}
