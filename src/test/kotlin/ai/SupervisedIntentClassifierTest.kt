package ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Checks confidence and margin boundaries of the sparse TF-IDF intent model. */
class SupervisedIntentClassifierTest {
    @Test
    fun exactSingleClassPromptIsAcceptedAtInclusiveThresholdBoundaries() {
        val classifier = SupervisedIntentClassifier(
            listOf(IntentEvaluationCase("Erstelle eine Klasse Person", Intent.GenerateClass))
        )

        val prediction = classifier.predict(
            "Erstelle eine Klasse Person",
            similarityThreshold = 1.0,
            marginThreshold = 1.0
        )

        assertNotNull(prediction)
        assertEquals(Intent.GenerateClass, prediction.intent)
        assertEquals(1.0, prediction.similarity ?: -1.0, 1e-12)
        assertEquals(1.0, prediction.margin ?: -1.0, 1e-12)
    }

    @Test
    fun similarityThresholdAboveObservedConfidenceRejectsButPreservesCandidate() {
        val classifier = singleClassClassifier()

        val prediction = classifier.predict(
            "Erstelle eine Klasse Person",
            similarityThreshold = 1.00001,
            marginThreshold = 0.0
        )

        assertNotNull(prediction)
        assertEquals(Intent.Unknown, prediction.intent)
        assertEquals(Intent.GenerateClass, prediction.topCandidate)
    }

    @Test
    fun marginThresholdAboveObservedMarginRejectsThePrediction() {
        val classifier = singleClassClassifier()

        val prediction = classifier.predict(
            "Erstelle eine Klasse Person",
            similarityThreshold = 0.0,
            marginThreshold = 1.00001
        )

        assertNotNull(prediction)
        assertEquals(Intent.Unknown, prediction.intent)
        assertEquals(1.0, prediction.margin ?: -1.0, 1e-12)
    }

    @Test
    fun equalCentroidsAreRejectedWhenTheRequiredMarginIsPositive() {
        val samePrompt = "quartz nebula"
        val classifier = SupervisedIntentClassifier(
            listOf(
                IntentEvaluationCase(samePrompt, Intent.GenerateClass),
                IntentEvaluationCase(samePrompt, Intent.GenerateFunction)
            )
        )

        val prediction = classifier.predict(samePrompt, similarityThreshold = 0.0, marginThreshold = 0.01)

        assertNotNull(prediction)
        assertEquals(Intent.Unknown, prediction.intent)
        assertEquals(0.0, prediction.margin)
    }

    @Test
    fun punctuationOnlyInputHasNoPredictiveFeatures() {
        assertNull(singleClassClassifier().predict("!!!", similarityThreshold = 0.0, marginThreshold = 0.0))
    }

    @Test
    fun leaveOneOutProducesOneDiagnosticResultForEveryTrainingExample() {
        val examples = listOf(
            IntentEvaluationCase("Erstelle eine Klasse Person", Intent.GenerateClass),
            IntentEvaluationCase("Lege eine Klasse Fahrzeug an", Intent.GenerateClass),
            IntentEvaluationCase("Schreibe eine Funktion addiere", Intent.GenerateFunction),
            IntentEvaluationCase("Programmiere eine Funktion sortiere", Intent.GenerateFunction)
        )
        val results = SupervisedIntentClassifier(examples).leaveOneOut(
            similarityThreshold = 0.0,
            marginThreshold = 0.0
        )

        assertEquals(examples.size, results.size)
        assertEquals(examples.map { it.prompt }, results.map { it.prompt })
        assertTrue(results.all { it.prediction.method == "supervised-tfidf" })
    }

    private fun singleClassClassifier() = SupervisedIntentClassifier(
        listOf(IntentEvaluationCase("Erstelle eine Klasse Person", Intent.GenerateClass))
    )
}
