package ai

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlinx.serialization.encodeToString

/** Covers file-backed persistence and JSON sanitization for the experimental model. */
class LanguageModelPersistenceTest {
    private val trainer = LanguageModelTrainer(BpeTokenizer())

    @Test
    fun loadingMissingModelFileReturnsNull() {
        val path = Files.createTempDirectory("missing-language-model").resolve("model.json")

        assertNull(trainer.load(path))
    }

    @Test
    fun loadingMalformedModelFileReturnsNull() {
        val path = Files.createTempFile("malformed-language-model", ".json")
        Files.writeString(path, "{ invalid json")

        assertNull(trainer.load(path))
    }

    @Test
    fun modelSaveSanitizesNonfiniteParametersIntoLoadableJson() {
        val path = Files.createTempDirectory("sanitized-language-model").resolve("model.json")
        val model = FeedforwardLanguageModel(
            vocabulary = listOf("token"),
            contextSize = 1,
            hiddenSize = 1,
            embeddingDim = 1,
            embeddings = arrayOf(doubleArrayOf(Double.NaN)),
            w1 = arrayOf(doubleArrayOf(Double.POSITIVE_INFINITY)),
            b1 = doubleArrayOf(Double.NEGATIVE_INFINITY),
            w2 = arrayOf(doubleArrayOf(Double.NaN)),
            b2 = doubleArrayOf(Double.POSITIVE_INFINITY)
        )

        trainer.save(model, path)
        val restored = assertNotNull(trainer.load(path))

        assertEquals(0.0, restored.embeddings[0][0])
        assertEquals(1e308, restored.w1[0][0])
        assertEquals(-1e308, restored.b1[0])
        assertEquals(0.0, restored.w2[0][0])
        assertEquals(1e308, restored.b2[0])
    }

    @Test
    fun modelPersistenceStoresWeightsButNotTheTrainingLossHistory() {
        val path = Files.createTempDirectory("model-loss-history").resolve("model.json")
        val model = trainer.train(
            corpus = listOf("alpha beta alpha beta"),
            contextSize = 1,
            hiddenSize = 2,
            epochs = 3
        )
        assertEquals(3, model.lossHistory.size)

        trainer.save(model, path)
        val restored = assertNotNull(trainer.load(path))

        assertEquals(model.vocabulary, restored.vocabulary)
        assertEquals(model.w1.map(DoubleArray::toList), restored.w1.map(DoubleArray::toList))
        assertEquals(emptyList(), restored.lossHistory)
    }

    @Test
    fun loadingModelWithMismatchedTensorShapesReturnsNull() {
        val path = Files.createTempFile("invalid-model-shape", ".json")
        val dto = FeedforwardModelDto(
            vocabulary = listOf("token"),
            contextSize = 1,
            hiddenSize = 1,
            embeddingDim = 1,
            embeddings = emptyList(),
            w1 = listOf(listOf(0.0)),
            b1 = listOf(0.0),
            w2 = listOf(listOf(0.0)),
            b2 = listOf(0.0)
        )
        Files.writeString(path, JsonCodec.json.encodeToString(dto))

        assertNull(trainer.load(path))
    }
}
