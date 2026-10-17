package ai

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TinyTransformerChatTest {
    @Test
    fun experimentalModeIsOffByDefaultAndTrainingControlInvokesTrainingAction() {
        var trainingRequested = false
        val controls = ExperimentalChatControls { trainingRequested = true }

        assertFalse(controls.modeToggle.isSelected)
        controls.trainButton.doClick()

        assertTrue(trainingRequested)
    }

    @Test
    fun tinyTransformerTrainsAndGeneratesFromLocalDialogueExamples() {
        val examples = List(8) { "hallo" to "hallo" }
        val model = TinyTransformerChatTrainer().train(
            examples,
            contextSize = 8,
            embeddingSize = 8,
            epochs = 40,
            learningRate = 0.05
        )
        val answer = model.generate(listOf(ChatMessage("user", "hallo")), maxTokens = 8)

        assertEquals(40, model.lossHistory.size)
        assertTrue(model.lossHistory.all(Double::isFinite))
        assertTrue(model.lossHistory.last() < model.lossHistory.first())
        assertTrue(answer.isNotBlank())
    }

    @Test
    @Suppress("LongMethod")
    fun gradientsAcrossParameterGroupsMatchCentralFiniteDifferences() {
        val trainer = TinyTransformerChatTrainer()
        val model = trainer.train(
            listOf("hallo" to "ja", "wie geht es" to "gut"),
            contextSize = 8,
            embeddingSize = 4,
            epochs = 1
        )
        val tokenIds = listOf(
            model.vocabulary.indexOf(TinyTransformerChatModel.USER_MARKER),
            model.vocabulary.indexOf("hallo"),
            model.vocabulary.indexOf(TinyTransformerChatModel.ASSISTANT_MARKER)
        )
        val targetId = model.vocabulary.indexOf("ja")
        assertTrue(tokenIds.all { it >= 0 } && targetId >= 0)

        val forward = model.forwardPass(tokenIds)
        val gradients = trainer.calculateGradients(model, tokenIds, forward, targetId)
        val epsilon = 1e-5

        fun assertGradient(label: String, analytic: Double, read: () -> Double, write: (Double) -> Unit) {
            val original = read()
            val numerical = try {
                write(original + epsilon)
                val lossAbove = model.lossForTarget(tokenIds, targetId)
                write(original - epsilon)
                val lossBelow = model.lossForTarget(tokenIds, targetId)
                (lossAbove - lossBelow) / (2.0 * epsilon)
            } finally {
                write(original)
            }
            assertTrue(
                kotlin.math.abs(analytic - numerical) < 1e-7,
                "$label gradient $analytic differs from central difference $numerical"
            )
        }

        val dimension = 2
        val tokenId = model.vocabulary.indexOf("hallo")
        val row = 1
        assertGradient(
            "token embedding",
            gradients.tokenEmbeddings.getValue(tokenId)[dimension],
            { model.tokenEmbeddings[tokenId][dimension] },
            { model.tokenEmbeddings[tokenId][dimension] = it }
        )
        assertGradient(
            "position embedding",
            gradients.positionEmbeddings[row][dimension],
            { model.positionEmbeddings[row][dimension] },
            { model.positionEmbeddings[row][dimension] = it }
        )
        assertGradient(
            "query weights",
            gradients.queryWeights[row][dimension],
            { model.queryWeights[row][dimension] },
            { model.queryWeights[row][dimension] = it }
        )
        assertGradient(
            "key weights",
            gradients.keyWeights[row][dimension],
            { model.keyWeights[row][dimension] },
            { model.keyWeights[row][dimension] = it }
        )
        assertGradient(
            "value weights",
            gradients.valueWeights[row][dimension],
            { model.valueWeights[row][dimension] },
            { model.valueWeights[row][dimension] = it }
        )
        assertGradient(
            "attention output weights",
            gradients.attentionOutputWeights[row][dimension],
            { model.attentionOutputWeights[row][dimension] },
            { model.attentionOutputWeights[row][dimension] = it }
        )
        assertGradient(
            "feedforward input weights",
            gradients.feedForwardInputWeights[row][dimension],
            { model.feedForwardInputWeights[row][dimension] },
            { model.feedForwardInputWeights[row][dimension] = it }
        )
        assertGradient(
            "feedforward input bias",
            gradients.feedForwardInputBias[dimension],
            { model.feedForwardInputBias[dimension] },
            { model.feedForwardInputBias[dimension] = it }
        )
        assertGradient(
            "feedforward output weights",
            gradients.feedForwardOutputWeights[row][dimension],
            { model.feedForwardOutputWeights[row][dimension] },
            { model.feedForwardOutputWeights[row][dimension] = it }
        )
        assertGradient(
            "feedforward output bias",
            gradients.feedForwardOutputBias[dimension],
            { model.feedForwardOutputBias[dimension] },
            { model.feedForwardOutputBias[dimension] = it }
        )
        assertGradient(
            "output weights",
            gradients.outputWeights[targetId][dimension],
            { model.outputWeights[targetId][dimension] },
            { model.outputWeights[targetId][dimension] = it }
        )
        assertGradient(
            "output bias",
            gradients.outputBias[targetId],
            { model.outputBias[targetId] },
            { model.outputBias[targetId] = it }
        )
    }

    @Test
    fun transformerModelPersistsAndProducesTheSameGreedyContinuation() {
        val trainer = TinyTransformerChatTrainer()
        val model = trainer.train(
            listOf("hallo" to "guten tag", "danke" to "gern geschehen"),
            contextSize = 8,
            embeddingSize = 8,
            epochs = 2
        )
        val path = Files.createTempDirectory("tiny-transformer").resolve("chat.json")
        trainer.save(model, path)
        val loaded = trainer.load(path)

        assertNotNull(loaded)
        assertEquals(
            model.generate(listOf(ChatMessage("user", "hallo")), 6),
            loaded.generate(listOf(ChatMessage("user", "hallo")), 6)
        )
    }

    @Test
    fun trainingRejectsInvalidHyperparameters() {
        val trainer = TinyTransformerChatTrainer()

        assertFailsWith<IllegalArgumentException> {
            trainer.train(listOf("hallo" to "hi"), epochs = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            trainer.train(listOf("hallo" to "hi"), embeddingSize = 2)
        }
    }

    @Test
    fun experimentalAssistantRequiresExplicitTrainingAndLabelsItsOutput() {
        val config = AssistantConfig(Files.createTempDirectory("assistant-transformer-chat"))
        val assistant = CodeAssistant(config)

        assertFailsWith<IllegalStateException> {
            assistant.experimentalChatResponse("Hallo")
        }
        assertTrue(assistant.trainExperimentalChatModel().contains("trainiert und gespeichert"))

        val response = CodeAssistant(config).experimentalChatResponse("Hallo")

        assertTrue(response.startsWith("[EXPERIMENTELLER TRANSFORMER"))
        assertTrue(response.isNotBlank())
    }
}
