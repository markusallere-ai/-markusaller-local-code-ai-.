package ai

import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Focused regression coverage for persisted and trained tokenizer embeddings. */
class EmbeddingRegressionTest {
    @Test
    fun embeddingStatePersistsAcrossRestarts() {
        val storageDir = Files.createTempDirectory("assistant-state")
        val first = CodeAssistant(AssistantConfig(storageDir))
        val token = first.tokenizeWithEmbeddings("kotlin").tokens.firstOrNull() ?: "kotlin"
        first.setEmbeddingForToken(token, List(32) { index -> if (index == 0) 1.0 else 0.0 })

        val second = CodeAssistant(AssistantConfig(storageDir))

        assertEquals(1.0, second.embeddingForToken(token).first(), 0.0001)
    }

    @Test
    fun skipGramEmbeddingStatePersistsAcrossRestarts() {
        val storageDir = Files.createTempDirectory("assistant-trained-state")
        val config = AssistantConfig(storageDir)
        val firstSimilarity = CodeAssistant(config).cosineSimilarity("funktion", "methode")

        val restoredSimilarity = CodeAssistant(config).cosineSimilarity("funktion", "methode")

        assertTrue(firstSimilarity.isFinite() && firstSimilarity != 0.0)
        assertEquals(firstSimilarity, restoredSimilarity, 1e-12)
    }

    @Test
    fun staleEmbeddingFingerprintRebuildsEmbeddingStateDeterministically() {
        val storageDir = Files.createTempDirectory("assistant-stale-embedding")
        val config = AssistantConfig(storageDir)
        val expectedSimilarity = CodeAssistant(config).cosineSimilarity("funktion", "methode")
        Files.delete(config.embeddingFingerprintFile())

        val rebuiltSimilarity = CodeAssistant(config).cosineSimilarity("funktion", "methode")

        assertEquals(expectedSimilarity, rebuiltSimilarity, 1e-12)
        assertTrue(Files.exists(config.embeddingFingerprintFile()))
    }

    @Test
    fun corruptTokenizerStateForcesEmbeddingRetraining() {
        val storageDir = Files.createTempDirectory("assistant-corrupt-tokenizer")
        val config = AssistantConfig(storageDir)
        val expectedSimilarity = CodeAssistant(config).cosineSimilarity("funktion", "methode")
        Files.writeString(config.tokenizerFile(), "not valid tokenizer state")

        val rebuiltSimilarity = CodeAssistant(config).cosineSimilarity("funktion", "methode")

        assertEquals(expectedSimilarity, rebuiltSimilarity, 1e-12)
    }

    @Test
    fun skipGramDoesNotCreatePositiveContextsAcrossSentenceBoundaries() {
        fun trainedSimilarity(corpus: List<String>): Double {
            val tokenizer = BpeTokenizer()
            tokenizer.train(corpus, maxMergeRules = 5000)
            tokenizer.trainSkipGramEmbeddings(corpus, epochs = 80, windowSize = 1)
            val alpha = tokenizer.embeddingForId(tokenizer.tokenId("alpha"))
            val beta = tokenizer.embeddingForId(tokenizer.tokenId("beta"))
            val dot = alpha.indices.sumOf { alpha[it] * beta[it] }
            val normAlpha = kotlin.math.sqrt(alpha.sumOf { it * it })
            val normBeta = kotlin.math.sqrt(beta.sumOf { it * it })
            return dot / (normAlpha * normBeta)
        }

        val separateSentences = trainedSimilarity(listOf("alpha alpha", "beta beta"))
        val joinedSentence = trainedSimilarity(listOf("alpha alpha beta beta"))

        assertTrue(joinedSentence > separateSentences, "$joinedSentence should exceed $separateSentences")
    }

    @Test
    fun tokenizerLoadsEmbeddingsByIdEvenWhenVocabularyRowsAreUnordered() {
        val tokenizer = BpeTokenizer()
        val unknownVector = List(32) { index -> if (index == 1) 1.0 else 0.0 }
        val tokenVector = List(32) { index -> if (index == 2) 1.0 else 0.0 }
        val state = TokenizerStateDto(
            vocabulary = listOf(
                EmbeddingRowDto("item", 1, tokenVector),
                EmbeddingRowDto("<unk>", 0, unknownVector)
            )
        )
        val path = Files.createTempFile("unordered-tokenizer", ".json")
        Files.writeString(path, JsonCodec.json.encodeToString(TokenizerStateDto.serializer(), state))

        tokenizer.load(path)

        assertEquals(unknownVector, tokenizer.embeddingForId(0))
        assertEquals(tokenVector, tokenizer.embeddingForId(tokenizer.tokenId("item")))
    }

    @Test
    fun tokenizerNormalizesLoadedTokensWithTheSameGermanFoldingAsInputs() {
        val tokenizer = BpeTokenizer()
        val vector = List(32) { index -> if (index == 3) 1.0 else 0.0 }
        val state = TokenizerStateDto(
            vocabulary = listOf(
                EmbeddingRowDto("<unk>", 0, List(32) { 0.0 }),
                EmbeddingRowDto("ä", 1, vector)
            )
        )
        val path = Files.createTempFile("folded-tokenizer", ".json")
        Files.writeString(path, JsonCodec.json.encodeToString(TokenizerStateDto.serializer(), state))

        tokenizer.load(path)

        assertEquals(1, tokenizer.tokenId("ä"))
        assertEquals(vector, tokenizer.embeddingForId(1))
    }

    @Test
    fun tokenizerRejectsInvalidStateWithoutDiscardingCurrentVocabulary() {
        val tokenizer = BpeTokenizer()
        tokenizer.train(listOf("stable token stable token"), maxMergeRules = 20)
        val existingTokens = tokenizer.vocabularySnapshot()
        val invalidState = TokenizerStateDto(
            vocabulary = listOf(
                EmbeddingRowDto("<unk>", 0, List(32) { 0.0 }),
                EmbeddingRowDto("broken", 2, List(32) { 0.0 })
            )
        )
        val path = Files.createTempFile("invalid-tokenizer", ".json")
        Files.writeString(path, JsonCodec.json.encodeToString(TokenizerStateDto.serializer(), invalidState))

        assertFailsWith<IllegalArgumentException> { tokenizer.load(path) }
        assertEquals(existingTokens, tokenizer.vocabularySnapshot())
    }

    @Test
    fun embeddingMatrixReturnsAnIsolatedSnapshot() {
        val tokenizer = BpeTokenizer()
        tokenizer.train(listOf("stable token stable token"), maxMergeRules = 20)
        val original = tokenizer.embeddingForId(0)

        tokenizer.embeddingMatrix()[0].fill(99.0)

        assertEquals(original, tokenizer.embeddingForId(0))
    }

    @Test
    fun skipGramTrainingUpdatesKnownEmbeddingScores() {
        val storageDir = Files.createTempDirectory("assistant-skipgram")
        Files.writeString(
            storageDir.resolve("tokenizer_corpus.txt"),
            listOf(
                "funktion und methode arbeiten zusammen",
                "klasse und objekt bilden eine struktur",
                "ein auto faehrt auf einer strasse",
                "methode ist eine spezielle funktion",
                "objekt ist eine instanz einer klasse",
                "funktion und methode sind eng verbunden",
                "klasse und object sind verwandt"
            ).joinToString(System.lineSeparator())
        )
        val assistant = CodeAssistant(AssistantConfig(storageDir))
        val before = assistant.cosineSimilarity("funktion", "methode")

        assistant.trainEmbeddingsWithSkipGram(epochs = 40, windowSize = 2)
        val after = assistant.cosineSimilarity("funktion", "methode")

        assertTrue(before.isFinite())
        assertTrue(after.isFinite())
        assertTrue(kotlin.math.abs(after - before) > 1e-6)
        assertTrue(after > 0.25)
    }

    @Test
    fun skipGramWithNoEpochsLeavesEmbeddingsUnchangedAndReportsTokenCount() {
        val tokenizer = BpeTokenizer()
        tokenizer.train(listOf("alpha beta"), maxMergeRules = 100)
        val before = tokenizer.embeddingMatrix().map(DoubleArray::toList)

        val processed = tokenizer.trainSkipGramEmbeddings(
            listOf("alpha beta"),
            epochs = 0,
            windowSize = 1
        )

        assertEquals(tokenizer.tokenize("alpha beta").size, processed)
        assertEquals(before, tokenizer.embeddingMatrix().map(DoubleArray::toList))
    }

    @Test
    fun zeroSkipGramWindowDoesNotNormalizeOrOtherwiseChangeEmbeddings() {
        val tokenizer = BpeTokenizer()
        tokenizer.train(listOf("alpha beta alpha beta"), maxMergeRules = 100)
        val before = tokenizer.embeddingMatrix().map(DoubleArray::toList)

        val processed = tokenizer.trainSkipGramEmbeddings(
            listOf("alpha beta"),
            epochs = 5,
            windowSize = 0
        )

        assertEquals(tokenizer.tokenize("alpha beta").size, processed)
        assertEquals(before, tokenizer.embeddingMatrix().map(DoubleArray::toList))
    }
}
