package ai

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Checks configuration boundaries and shared text/numeric normalization contracts. */
class ConfigurationAndNormalizationTest {
    @Test
    fun assistantConfigAcceptsAllDocumentedThresholdEndpoints() {
        val config = AssistantConfig(
            storagePath = Files.createTempDirectory("assistant-config-endpoints"),
            intentSimilarityThreshold = -1.0,
            intentMarginThreshold = 2.0,
            knowledgeSimilarityThreshold = 1.0,
            knowledgeMarginThreshold = 0.0,
            knowledgeMinimumTokenOverlap = 1.0,
            knowledgeSingleMatchSimilarityThreshold = -1.0
        )

        assertEquals(-1.0, config.intentSimilarityThreshold)
        assertEquals(2.0, config.intentMarginThreshold)
        assertEquals(1.0, config.knowledgeSimilarityThreshold)
        assertEquals(0.0, config.knowledgeMarginThreshold)
        assertEquals(1.0, config.knowledgeMinimumTokenOverlap)
        assertEquals(-1.0, config.knowledgeSingleMatchSimilarityThreshold)
    }

    @Test
    fun assistantConfigRejectsThresholdsOutsideTheirRanges() {
        val directory = Files.createTempDirectory("assistant-config-invalid")
        val invalidConfigurations = listOf<() -> AssistantConfig>(
            { AssistantConfig(directory, intentSimilarityThreshold = -1.01) },
            { AssistantConfig(directory, intentSimilarityThreshold = 1.01) },
            { AssistantConfig(directory, intentMarginThreshold = -0.01) },
            { AssistantConfig(directory, intentMarginThreshold = 2.01) },
            { AssistantConfig(directory, knowledgeSimilarityThreshold = -1.01) },
            { AssistantConfig(directory, knowledgeSimilarityThreshold = 1.01) },
            { AssistantConfig(directory, knowledgeMarginThreshold = 2.01) },
            { AssistantConfig(directory, knowledgeMinimumTokenOverlap = -0.01) },
            { AssistantConfig(directory, knowledgeMinimumTokenOverlap = 1.01) },
            { AssistantConfig(directory, knowledgeSingleMatchSimilarityThreshold = -1.01) },
            { AssistantConfig(directory, knowledgeSingleMatchSimilarityThreshold = 1.01) }
        )

        invalidConfigurations.forEach { create ->
            assertFailsWith<IllegalArgumentException> { create() }
        }
    }

    @Test
    fun assistantConfigRejectsNonfiniteThresholds() {
        val directory = Files.createTempDirectory("assistant-config-nonfinite")
        val invalidConfigurations = listOf<() -> AssistantConfig>(
            { AssistantConfig(directory, intentSimilarityThreshold = Double.NaN) },
            { AssistantConfig(directory, intentMarginThreshold = Double.POSITIVE_INFINITY) },
            { AssistantConfig(directory, knowledgeSimilarityThreshold = Double.NaN) },
            { AssistantConfig(directory, knowledgeMarginThreshold = Double.POSITIVE_INFINITY) },
            { AssistantConfig(directory, knowledgeMinimumTokenOverlap = Double.NaN) },
            { AssistantConfig(directory, knowledgeSingleMatchSimilarityThreshold = Double.NaN) }
        )

        invalidConfigurations.forEach { create ->
            assertFailsWith<IllegalArgumentException> { create() }
        }
    }

    @Test
    fun configuredStoragePathsResolveEveryRuntimeFileIntoTheProfile() {
        val directory = Files.createTempDirectory("assistant-config-paths")
        val config = AssistantConfig(directory)
        val paths = listOf(
            config.tokenizerFile(),
            config.tokenizerCorpusFile(),
            config.brainStateFile(),
            config.feedforwardModelFile(),
            config.knowledgeFile(),
            config.trainingExamplesFile(),
            config.tokenizerFingerprintFile(),
            config.embeddingFingerprintFile()
        )

        assertTrue(paths.all { it.parent == directory })
        assertEquals(8, paths.map { it.fileName }.distinct().size)
    }

    @Test
    fun textFoldNormalizesGermanCharactersAndCase() {
        assertEquals("strasse fuer oel", TextFold.fold("Straße für Öl"))
        assertEquals("groesser", TextFold.fold("GRÖSSER"))
    }

    @Test
    fun textFoldAppliesUnicodeCompatibilityNormalizationBeforeFolding() {
        assertEquals("groesser", TextFold.fold("ＧＲÖẞＥＲ"))
    }

    @Test
    fun kotlinIdentifierSanitizerProvidesFallbackAndProtectsReservedWords() {
        assertEquals("Example", TextPatterns.sanitizeKotlinIdentifier("  "))
        assertEquals("classItem", TextPatterns.sanitizeKotlinIdentifier("class"))
        assertEquals("regularName", TextPatterns.sanitizeKotlinIdentifier("regularName"))
    }

    @Test
    fun jsonNumberSanitizerLeavesFiniteNumbersAndReplacesNonFiniteValues() {
        assertEquals(1.25, JsonCodec.sanitizeDouble(1.25))
        assertEquals(0.0, JsonCodec.sanitizeDouble(Double.NaN))
        assertEquals(1e308, JsonCodec.sanitizeDouble(Double.POSITIVE_INFINITY))
        assertEquals(-1e308, JsonCodec.sanitizeDouble(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun jsonVectorSanitizerPreservesOrderAndProducesOnlyFiniteValues() {
        val sanitized = JsonCodec.sanitizeVector(
            listOf(0.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        )

        assertEquals(listOf(0.5, 0.0, 1e308, -1e308), sanitized)
        assertTrue(sanitized.all(Double::isFinite))
    }
}
