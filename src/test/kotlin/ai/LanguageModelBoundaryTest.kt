package ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Checks empty-data, invalid-parameter, and output-limit behavior of the experimental model. */
class LanguageModelBoundaryTest {
    private fun trainer(): LanguageModelTrainer {
        val tokenizer = BpeTokenizer()
        tokenizer.train(listOf("alpha beta alpha beta"), maxMergeRules = 100)
        return LanguageModelTrainer(tokenizer)
    }

    @Test
    fun trainingRejectsNonpositiveArchitectureAndNegativeEpochCount() {
        val trainer = trainer()

        assertFailsWith<IllegalArgumentException> {
            trainer.train(listOf("alpha beta"), contextSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            trainer.train(listOf("alpha beta"), hiddenSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            trainer.train(listOf("alpha beta"), epochs = -1)
        }
    }

    @Test
    fun trainingRejectsNonpositiveOrNonfiniteLearningRate() {
        val trainer = trainer()

        listOf(0.0, -0.1, Double.NaN, Double.POSITIVE_INFINITY).forEach { learningRate ->
            assertFailsWith<IllegalArgumentException> {
                trainer.train(listOf("alpha beta alpha"), learningRate = learningRate)
            }
        }
    }

    @Test
    fun emptyTrainingCorpusReturnsAnEmptyPredictableModel() {
        val model = trainer().train(emptyList())

        assertTrue(model.vocabulary.isEmpty())
        assertTrue(model.lossHistory.isEmpty())
        assertEquals("", model.predictNextToken(listOf("unknown")))
    }

    @Test
    fun corpusWithoutEnoughContextTokensDoesNotCreateTrainingExamples() {
        val model = trainer().train(
            corpus = listOf("alpha beta"),
            contextSize = 2,
            hiddenSize = 4,
            epochs = 5
        )

        assertTrue(model.vocabulary.isEmpty())
        assertTrue(model.lossHistory.isEmpty())
    }

    @Test
    fun zeroEpochsKeepsLearnedVocabularyWithoutReportingTrainingLoss() {
        val model = trainer().train(
            corpus = listOf("alpha beta alpha beta"),
            contextSize = 1,
            hiddenSize = 4,
            epochs = 0
        )

        assertTrue(model.vocabulary.isNotEmpty())
        assertTrue(model.lossHistory.isEmpty())
        assertTrue(model.predictNextToken(listOf("alpha")).isNotBlank())
    }

    @Test
    fun negativeGenerationLimitDoesNotAddTokensAfterTheSeed() {
        val trainer = trainer()
        val model = trainer.train(emptyList())

        assertEquals("alpha beta", trainer.generate(model, "alpha beta", maxTokens = -1))
    }

    @Test
    fun repeatedContextTokensAccumulateTheirEmbeddingGradients() {
        val trainer = trainer()
        val corpus = listOf("alpha alpha beta")
        val before = trainer.train(corpus, contextSize = 2, hiddenSize = 2, epochs = 0)
        val after = trainer.train(corpus, contextSize = 2, hiddenSize = 2, epochs = 1, learningRate = 0.01)
        val alphaId = before.vocabulary.indexOf("alpha")
        val original = before.embeddings[alphaId][0]
        val epsilon = 1e-6

        fun loss(embeddingValue: Double): Double {
            val input = DoubleArray(before.contextSize * before.embeddingDim) {
                before.embeddings[alphaId][it % before.embeddingDim]
            }.apply {
                this[0] = embeddingValue
                this[before.embeddingDim] = embeddingValue
            }
            val hidden = DoubleArray(before.hiddenSize) { hiddenIndex ->
                kotlin.math.tanh(
                    before.b1[hiddenIndex] +
                        input.indices.sumOf { input[it] * before.w1[it][hiddenIndex] }
                )
            }
            val logits = DoubleArray(before.vocabulary.size) { outputIndex ->
                before.b2[outputIndex] +
                    hidden.indices.sumOf { hidden[it] * before.w2[it][outputIndex] }
            }
            val maxLogit = logits.maxOrNull() ?: 0.0
            val logNormalizer = maxLogit + kotlin.math.ln(logits.sumOf { kotlin.math.exp(it - maxLogit) })
            val targetId = before.vocabulary.indexOf("beta")
            return logNormalizer - logits[targetId]
        }

        val expected = original - 0.01 * (loss(original + epsilon) - loss(original - epsilon)) / (2 * epsilon)

        assertEquals(expected, after.embeddings[alphaId][0], absoluteTolerance = 1e-7)
    }
}
