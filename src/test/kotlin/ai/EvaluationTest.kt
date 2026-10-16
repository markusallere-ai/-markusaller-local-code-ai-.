package ai

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies evaluation metrics, reproducibility, and persistence of labelled training examples. */
class EvaluationTest {
    @Test
    fun fixedEvaluationHasMeasurableQualityAndLossTrend() {
        val assistant = CodeAssistant(AssistantConfig(Files.createTempDirectory("evaluation")))
        val report = assistant.evaluate()

        assertEquals(60, report.intentResults.size)
        assertEquals(60, report.leaveOneOutIntentResults.size)
        assertTrue(report.generalizationIntentResults.size == 12)
        assertEquals(8, report.outOfDomainIntentResults.size)
        assertTrue(report.outOfDomainFalseAcceptanceRate.isFinite())
        assertTrue(
            report.outOfDomainFalseAcceptanceRate <= 0.25,
            "Zu viele fachfremde Prompts wurden akzeptiert: ${report.outOfDomainIntentResults}"
        )
        assertTrue(EvaluationComponent.FINAL_TEST_INTENT_CASES.size == 12)
        assertTrue(report.trainingIntentAccuracy >= 0.90, report.intentResults.toString())
        assertTrue(report.leaveOneOutIntentAccuracy >= 0.55, report.leaveOneOutIntentResults.toString())
        assertTrue(report.leaveOneOutCoverage >= 0.45)
        assertTrue(report.validationIntentAccuracy >= 0.80, report.generalizationIntentResults.toString())
        assertTrue(
            report.generalizationIntentAccuracy.isFinite(),
            report.generalizationIntentResults.toString()
        )
        assertTrue(report.validationConfusionMatrix.isNotEmpty())
        assertTrue(report.intentResults.all { it.prediction.method.isNotBlank() })
        assertTrue(report.generalizationIntentResults.all { it.prediction.confidence.isFinite() })
        assertTrue(report.embeddingResults.isNotEmpty())
        assertTrue(report.lossHistory.size > 1)
        assertTrue(report.feedforwardLossDecreased, "${report.initialLoss} -> ${report.finalLoss}")
        assertTrue(
            report.validRelatedEmbeddingCount >= 6 && report.validUnrelatedEmbeddingCount >= 6,
            "Es werden mindestens sechs In-Vocabulary-Paare pro Gruppe verlangt: ${report.embeddingResults}"
        )
        assertTrue(report.embeddingSeparation.isFinite())
        assertTrue(report.finalLossImprovement?.isFinite() == true)
        assertTrue(report.bestLossImprovement?.isFinite() == true)
    }

    @Test
    fun currentModelBaselineKeepsFinalLossImproving() {
        val assistant = CodeAssistant(AssistantConfig(Files.createTempDirectory("evaluation-baseline")))
        val report = assistant.evaluate()

        assertTrue(
            report.finalLossImprovement != null && report.finalLossImprovement > 0.05,
            "Der End-Loss muss gegenüber dem Start-Loss messbar sinken: $report"
        )
    }

    @Test
    fun labelledTrainingExamplesPersistWithMetadata() {
        val directory = Files.createTempDirectory("training-examples")
        val config = AssistantConfig(directory)
        CodeAssistant(config).addTrainingExample(
            TrainingExample(
                "Erklaere die Klasse", Intent.Explain, "Die Klasse beschreibt Daten.",
                listOf("docs", "docs"), java.time.Instant.parse("2026-01-01T00:00:00Z")
            )
        )

        val restored = CodeAssistant(config).trainingExamples().single()
        assertEquals(Intent.Explain, restored.expectedIntent)
        assertEquals(listOf("docs"), restored.tags)
        assertEquals(java.time.Instant.parse("2026-01-01T00:00:00Z"), restored.timestamp)
    }

    @Test
    fun evaluationDoesNotMutateEmbeddingState() {
        val assistant = CodeAssistant(AssistantConfig(Files.createTempDirectory("evaluation-repeat")))

        val first = assistant.evaluate()
        val second = assistant.evaluate()

        assertEquals(first, second)
    }

    @Test
    fun evaluationReportPrintsPredictionDiagnostics() {
        val assistant = CodeAssistant(AssistantConfig(Files.createTempDirectory("evaluation-diagnostics")))
        val reportText = assistant.formatEvaluation()

        assertTrue(reportText.contains("erwartet="))
        assertTrue(reportText.contains("erkannt="))
        assertTrue(reportText.contains("similarity="))
        assertTrue(reportText.contains("topKandidat="))
        assertTrue(reportText.contains("methode="))
        assertTrue(reportText.contains("Code-Quelle:"))
        assertTrue(reportText.contains("Tokenizer-Fingerprint:"))
        assertTrue(reportText.contains("Embedding-Fingerprint:"))
        assertTrue(reportText.contains("Feedforward-Trainingsloss Anfang:"))
        assertTrue(reportText.contains("funktion <-> methode"))
        assertTrue(reportText.contains("Intent-Trainingsgenauigkeit:"))
        assertTrue(reportText.contains("Out-of-Domain-False-Acceptance-Rate:"))
        assertTrue(reportText.contains("asdkfj qwertz"))
    }

    @Test
    fun embeddingEvaluationExcludesOutOfVocabularyPairs() {
        val assistant = CodeAssistant(AssistantConfig(Files.createTempDirectory("embedding-oov")))
        val pairs = listOf(
            EmbeddingEvaluationPair("funktion", "methode", true),
            EmbeddingEvaluationPair("klasse", "objekt", true),
            EmbeddingEvaluationPair("datenklasse", "struktur", true),
            EmbeddingEvaluationPair("funktion", "sprache", false),
            EmbeddingEvaluationPair("klasse", "lernen", false),
            EmbeddingEvaluationPair("datenklasse", "sprache", false),
            EmbeddingEvaluationPair("notinthevocabulary1", "methode", true),
            EmbeddingEvaluationPair("funktion", "notinthevocabulary2", false)
        )

        val report = assistant.evaluate(
            listOf(IntentEvaluationCase("Erstelle eine Klasse Person", Intent.GenerateClass)),
            pairs
        )

        assertEquals(1, report.excludedRelatedEmbeddingCount)
        assertEquals(1, report.excludedUnrelatedEmbeddingCount)
        assertTrue(report.validRelatedEmbeddingCount >= 3)
        assertTrue(report.validUnrelatedEmbeddingCount >= 3)
    }

    @Test
    fun trainingPromptsAreBalancedAcrossAllIntents() {
        val counts = EvaluationComponent.TRAINING_INTENT_CASES.groupingBy { it.expected }.eachCount()

        assertEquals(
            setOf(
                Intent.GenerateClass,
                Intent.GenerateDataClass,
                Intent.GenerateFunction,
                Intent.GenerateTest,
                Intent.Explain,
                Intent.Tokenize
            ),
            counts.keys
        )
        assertTrue(counts.values.all { it == 10 }, counts.toString())
    }
}
