package ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers evaluation input requirements and aggregation of incomplete metrics. */
class EvaluationBoundaryTest {
    @Test
    fun evaluationRejectsAnEmptyIntentCaseSet() {
        assertFailsWith<IllegalArgumentException> {
            component().evaluate(intentCases = emptyList(), embeddingPairs = validPairs())
        }
    }

    @Test
    fun evaluationRejectsAnEmptyEmbeddingPairSet() {
        assertFailsWith<IllegalArgumentException> {
            component().evaluate(
                intentCases = listOf(IntentEvaluationCase("prompt", Intent.Unknown)),
                embeddingPairs = emptyList()
            )
        }
    }

    @Test
    fun evaluationRequiresAtLeastThreeUsableRelatedPairs() {
        val pairs = listOf(
            EmbeddingEvaluationPair("a", "b", true),
            EmbeddingEvaluationPair("c", "d", false),
            EmbeddingEvaluationPair("e", "f", false),
            EmbeddingEvaluationPair("g", "h", false)
        )

        assertFailsWith<IllegalArgumentException> {
            component().evaluate(
                intentCases = listOf(IntentEvaluationCase("prompt", Intent.Unknown)),
                embeddingPairs = pairs
            )
        }
    }

    @Test
    fun evaluationRequiresAtLeastThreeUsableUnrelatedPairs() {
        val pairs = listOf(
            EmbeddingEvaluationPair("a", "b", true),
            EmbeddingEvaluationPair("c", "d", true),
            EmbeddingEvaluationPair("e", "f", true),
            EmbeddingEvaluationPair("g", "h", false)
        )

        assertFailsWith<IllegalArgumentException> {
            component().evaluate(
                intentCases = listOf(IntentEvaluationCase("prompt", Intent.Unknown)),
                embeddingPairs = pairs
            )
        }
    }

    @Test
    fun nonfiniteEmbeddingValuesAreExcludedFromMeansButCountedAsExcluded() {
        val pairs = validPairs()
        val scores = listOf(0.8, 0.6, Double.NaN, 0.4, 0.2, 0.1, Double.POSITIVE_INFINITY, 0.3)
        val report = component(similarity = { left, right ->
            val pairIndex = pairs.indexOfFirst { it.left == left && it.right == right }
            scores[pairIndex]
        }).evaluate(
            intentCases = listOf(IntentEvaluationCase("prompt", Intent.Unknown)),
            embeddingPairs = pairs,
            feedforwardCorpus = emptyList()
        )

        assertEquals(3, report.validRelatedEmbeddingCount)
        assertEquals(3, report.validUnrelatedEmbeddingCount)
        assertEquals(1, report.excludedRelatedEmbeddingCount)
        assertEquals(1, report.excludedUnrelatedEmbeddingCount)
        assertEquals(0.6, report.embeddingRelatedMean, 1e-12)
        assertEquals(0.2, report.embeddingUnrelatedMean, 1e-12)
        assertEquals(0.4, report.embeddingSeparation, 1e-12)
    }

    @Test
    fun emptyFeedforwardCorpusReportsAbsentLossWithoutCallingTrainer() {
        val report = component(
            train = { error("Trainer must not run for an empty corpus") }
        ).evaluate(
            intentCases = listOf(IntentEvaluationCase("prompt", Intent.Unknown)),
            embeddingPairs = validPairs(),
            feedforwardCorpus = emptyList()
        )

        assertNull(report.initialLoss)
        assertNull(report.finalLoss)
        assertFalse(report.feedforwardLossDecreased)
        assertEquals(emptyList(), report.lossHistory)
    }

    @Test
    fun reportDistinguishesFinalLossImprovementFromBestLossImprovement() {
        val report = component(
            train = { emptyModel(listOf(2.0, 0.5, 1.0)) }
        ).evaluate(
            intentCases = listOf(IntentEvaluationCase("prompt", Intent.Unknown)),
            embeddingPairs = validPairs()
        )

        assertEquals(1.0, report.finalLossImprovement)
        assertEquals(1.5, report.bestLossImprovement)
        assertTrue(report.feedforwardLossDecreased)
    }

    private fun component(
        similarity: (String, String) -> Double = { _, _ -> 0.5 },
        train: (List<String>) -> FeedforwardLanguageModel = { emptyModel(listOf(1.0, 0.5)) }
    ) = EvaluationComponent(
        classify = { IntentPrediction(Intent.Unknown, 0.0, "test") },
        similarity = similarity,
        train = train
    )

    private fun validPairs() = listOf(
        EmbeddingEvaluationPair("related-1", "related-2", true),
        EmbeddingEvaluationPair("related-3", "related-4", true),
        EmbeddingEvaluationPair("related-5", "related-6", true),
        EmbeddingEvaluationPair("related-7", "related-8", true),
        EmbeddingEvaluationPair("unrelated-1", "unrelated-2", false),
        EmbeddingEvaluationPair("unrelated-3", "unrelated-4", false),
        EmbeddingEvaluationPair("unrelated-5", "unrelated-6", false),
        EmbeddingEvaluationPair("unrelated-7", "unrelated-8", false)
    )

    private fun emptyModel(lossHistory: List<Double>) = FeedforwardLanguageModel(
        vocabulary = emptyList(),
        contextSize = 1,
        hiddenSize = 1,
        embeddings = emptyArray(),
        w1 = emptyArray(),
        b1 = DoubleArray(0),
        w2 = emptyArray(),
        b2 = DoubleArray(0),
        lossHistory = lossHistory.toMutableList()
    )
}
