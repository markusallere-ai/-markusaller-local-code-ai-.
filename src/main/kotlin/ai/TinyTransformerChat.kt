package ai

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private const val SOFTMAX_EXPONENT_LIMIT = 60.0
private const val MINIMUM_LOSS_PROBABILITY = 1e-12

internal data class ChatMessage(val role: String, val text: String)

internal data class TinyTransformerForwardPass(
    val inputs: Array<DoubleArray>,
    val query: DoubleArray,
    val keys: Array<DoubleArray>,
    val values: Array<DoubleArray>,
    val attention: DoubleArray,
    val attentionContext: DoubleArray,
    val attentionResidual: DoubleArray,
    val feedForwardHidden: DoubleArray,
    val hidden: DoubleArray,
    val probabilities: DoubleArray
)

internal data class TinyTransformerOutputGradients(
    val logits: DoubleArray,
    val hidden: DoubleArray
)

internal data class TinyTransformerGradients(
    val tokenEmbeddings: Map<Int, DoubleArray>,
    val positionEmbeddings: Array<DoubleArray>,
    val queryWeights: Array<DoubleArray>,
    val keyWeights: Array<DoubleArray>,
    val valueWeights: Array<DoubleArray>,
    val attentionOutputWeights: Array<DoubleArray>,
    val feedForwardInputWeights: Array<DoubleArray>,
    val feedForwardInputBias: DoubleArray,
    val feedForwardOutputWeights: Array<DoubleArray>,
    val feedForwardOutputBias: DoubleArray,
    val outputWeights: Array<DoubleArray>,
    val outputBias: DoubleArray
)

/** A small decoder-only model with causal single-head self-attention. */
internal data class TinyTransformerChatModel(
    val vocabulary: List<String>,
    val contextSize: Int,
    val embeddingSize: Int,
    val tokenEmbeddings: Array<DoubleArray>,
    val positionEmbeddings: Array<DoubleArray>,
    val queryWeights: Array<DoubleArray>,
    val keyWeights: Array<DoubleArray>,
    val valueWeights: Array<DoubleArray>,
    val attentionOutputWeights: Array<DoubleArray>,
    val feedForwardInputWeights: Array<DoubleArray>,
    val feedForwardInputBias: DoubleArray,
    val feedForwardOutputWeights: Array<DoubleArray>,
    val feedForwardOutputBias: DoubleArray,
    val outputWeights: Array<DoubleArray>,
    val outputBias: DoubleArray,
    val lossHistory: List<Double>
) {
    init {
        require(vocabulary.isNotEmpty() && vocabulary.distinct().size == vocabulary.size)
        require(contextSize > 0 && embeddingSize > 0)
        require(tokenEmbeddings.size == vocabulary.size && tokenEmbeddings.all { it.size == embeddingSize })
        require(positionEmbeddings.size == contextSize && positionEmbeddings.all { it.size == embeddingSize })
        require(listOf(queryWeights, keyWeights, valueWeights, attentionOutputWeights).all(::isSquare))
        require(
            feedForwardInputWeights.size == embeddingSize &&
                feedForwardInputWeights.all { it.size == embeddingSize }
        )
        require(feedForwardInputBias.size == embeddingSize)
        require(
            feedForwardOutputWeights.size == embeddingSize &&
                feedForwardOutputWeights.all { it.size == embeddingSize }
        )
        require(feedForwardOutputBias.size == embeddingSize)
        require(outputWeights.size == vocabulary.size && outputWeights.all { it.size == embeddingSize })
        require(outputBias.size == vocabulary.size)
        require(
            tokenEmbeddings.all { row -> row.all(Double::isFinite) } &&
                positionEmbeddings.all { row -> row.all(Double::isFinite) } &&
                listOf(
                    queryWeights, keyWeights, valueWeights, attentionOutputWeights,
                    feedForwardInputWeights, feedForwardOutputWeights
                ).all { matrix -> matrix.all { row -> row.all(Double::isFinite) } } &&
                feedForwardInputBias.all(Double::isFinite) &&
                feedForwardOutputBias.all(Double::isFinite) &&
                outputWeights.all { row -> row.all(Double::isFinite) } &&
                outputBias.all(Double::isFinite) && lossHistory.all(Double::isFinite)
        ) { "Transformer parameters must be finite." }
    }

    private val vocabularyIndex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        vocabulary.withIndex().associate { it.value to it.index }
    }
    private val unknownIndex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        vocabularyIndex["<unbekannt>"] ?: 0
    }

    private fun isSquare(matrix: Array<DoubleArray>) =
        matrix.size == embeddingSize && matrix.all { it.size == embeddingSize }

    fun generate(messages: List<ChatMessage>, maxTokens: Int): String {
        val promptTokens = messages.flatMap { message ->
            listOf(if (message.role == "assistant") ASSISTANT_MARKER else USER_MARKER) +
                tokenizeWords(message.text)
        } + ASSISTANT_MARKER
        val context = promptTokens.takeLast(contextSize).map { vocabularyIndex[it] ?: unknownIndex }.toMutableList()
        val output = mutableListOf<String>()
        repeat(maxTokens.coerceAtLeast(0)) {
            val next = predict(context)
            if (next == END_MARKER) return formatGeneratedText(output)
            output += next
            context += vocabularyIndex[next] ?: unknownIndex
            while (context.size > contextSize) context.removeAt(0)
        }
        return formatGeneratedText(output)
    }

    private fun predict(tokenIds: List<Int>): String {
        val forward = forwardPass(tokenIds)
        return vocabulary[forward.probabilities.indices.maxByOrNull { forward.probabilities[it] } ?: 0]
    }

    internal fun forwardPass(tokenIds: List<Int>): TinyTransformerForwardPass {
        val ids = tokenIds.takeLast(contextSize)
        require(ids.isNotEmpty()) { "At least one context token is required." }
        val inputs = Array(ids.size) { position ->
            DoubleArray(embeddingSize) { dimension ->
                tokenEmbeddings[ids[position]][dimension] + positionEmbeddings[position][dimension]
            }
        }
        val scale = 1.0 / sqrt(embeddingSize.toDouble())
        val query = multiply(queryWeights, inputs.last())
        val keys = Array(inputs.size) { multiply(keyWeights, inputs[it]) }
        val values = Array(inputs.size) { multiply(valueWeights, inputs[it]) }
        val scores = DoubleArray(inputs.size) { position -> dot(query, keys[position]) * scale }
        val attention = softmax(scores)
        val attentionContext = DoubleArray(embeddingSize) { dimension ->
            inputs.indices.sumOf { position -> attention[position] * values[position][dimension] }
        }
        val attentionOutput = multiply(attentionOutputWeights, attentionContext)
        val attentionResidual = DoubleArray(embeddingSize) { dimension ->
            inputs.last()[dimension] + attentionOutput[dimension]
        }
        val feedForwardPreActivation = multiply(feedForwardInputWeights, attentionResidual)
        val feedForwardHidden = DoubleArray(embeddingSize) { output ->
            kotlin.math.tanh(feedForwardPreActivation[output] + feedForwardInputBias[output])
        }
        val feedForwardOutput = multiply(feedForwardOutputWeights, feedForwardHidden)
        val hidden = DoubleArray(embeddingSize) { dimension ->
            attentionResidual[dimension] +
                feedForwardOutput[dimension] + feedForwardOutputBias[dimension]
        }
        val logits = DoubleArray(vocabulary.size) { tokenId ->
            dot(hidden, outputWeights[tokenId]) + outputBias[tokenId]
        }
        return TinyTransformerForwardPass(
            inputs,
            query,
            keys,
            values,
            attention,
            attentionContext,
            attentionResidual,
            feedForwardHidden,
            hidden,
            softmax(logits)
        )
    }

    internal fun lossForTarget(tokenIds: List<Int>, targetId: Int): Double {
        require(targetId in vocabulary.indices) { "Target token is outside the vocabulary." }
        return -ln(forwardPass(tokenIds).probabilities[targetId].coerceAtLeast(MINIMUM_LOSS_PROBABILITY))
    }

    private fun multiply(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray =
        DoubleArray(matrix.size) { row -> dot(matrix[row], vector) }

    private fun dot(left: DoubleArray, right: DoubleArray): Double =
        left.indices.sumOf { left[it] * right[it] }

    private fun softmax(values: DoubleArray): DoubleArray {
        val maximum = values.maxOrNull() ?: 0.0
        val shifted = DoubleArray(values.size) {
            exp((values[it] - maximum).coerceIn(-SOFTMAX_EXPONENT_LIMIT, SOFTMAX_EXPONENT_LIMIT))
        }
        val total = shifted.sum()
        return if (total == 0.0) DoubleArray(values.size) else DoubleArray(values.size) { shifted[it] / total }
    }

    companion object {
        const val USER_MARKER = "<nutzer>"
        const val ASSISTANT_MARKER = "<assistent>"
        const val END_MARKER = "<ende>"

        @Suppress("MagicNumber")
        fun formatGeneratedText(words: List<String>): String {
            val text = words.joinToString(" ")
                .replace(Regex("\\s+([.,!?;:])"), "$1")
                .replace(Regex("([.!?])\\s*([\\p{L}])")) { match ->
                    "${match.groupValues[1]} ${match.groupValues[2].uppercase()}"
            }
            return text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}

/** Trains one causal-attention block with next-token cross-entropy and SGD. */
@Suppress("MagicNumber", "TooManyFunctions")
internal class TinyTransformerChatTrainer {
    @Suppress("LongMethod")
    fun train(
        examples: List<Pair<String, String>>,
        contextSize: Int = 24,
        embeddingSize: Int = 24,
        epochs: Int = 24,
        learningRate: Double = 0.04
    ): TinyTransformerChatModel {
        require(examples.isNotEmpty()) { "At least one dialogue example is required." }
        require(contextSize in 2..128) { "contextSize must be between 2 and 128." }
        require(embeddingSize in 4..128) { "embeddingSize must be between 4 and 128." }
        require(epochs in 1..500) { "epochs must be between 1 and 500." }
        require(learningRate.isFinite() && learningRate in 0.0001..0.5) {
            "learningRate must be finite and between 0.0001 and 0.5."
        }

        val sequences = examples.map { (user, assistant) ->
            listOf(TinyTransformerChatModel.USER_MARKER) + tokenizeWords(user) +
                TinyTransformerChatModel.ASSISTANT_MARKER + tokenizeWords(assistant) +
                TinyTransformerChatModel.END_MARKER
        }.filter { it.size >= 3 }
        require(sequences.isNotEmpty()) { "Dialogue examples must contain user and assistant text." }
        val vocabulary = (sequences.flatten() + "<unbekannt>").distinct().sorted()
        val index = vocabulary.withIndex().associate { it.value to it.index }
        val random = Random(41)
        val tokenEmbeddings = Array(vocabulary.size) {
            DoubleArray(embeddingSize) { (random.nextDouble() - 0.5) * 0.16 }
        }
        val positionEmbeddings = Array(contextSize) {
            DoubleArray(embeddingSize) { (random.nextDouble() - 0.5) * 0.08 }
        }
        fun randomMatrix() = Array(embeddingSize) {
            DoubleArray(embeddingSize) { (random.nextDouble() - 0.5) * 0.08 }
        }
        val queryWeights = randomMatrix()
        val keyWeights = randomMatrix()
        val valueWeights = randomMatrix()
        val attentionOutputWeights = randomMatrix()
        val feedForwardInputWeights = randomMatrix()
        val feedForwardInputBias = DoubleArray(embeddingSize)
        val feedForwardOutputWeights = randomMatrix()
        val feedForwardOutputBias = DoubleArray(embeddingSize)
        val outputWeights = Array(vocabulary.size) {
            DoubleArray(embeddingSize) { (random.nextDouble() - 0.5) * 0.12 }
        }
        val outputBias = DoubleArray(vocabulary.size)
        val examplesByTarget = sequences.flatMap { sequence ->
            (1 until sequence.size).map { targetPosition ->
                sequence.subList((targetPosition - contextSize).coerceAtLeast(0), targetPosition)
                    .map { index.getValue(it) } to index.getValue(sequence[targetPosition])
            }
        }.toMutableList()
        val randomizer = java.util.Random(41)
        val lossHistory = mutableListOf<Double>()
        val model = TinyTransformerChatModel(
            vocabulary,
            contextSize,
            embeddingSize,
            tokenEmbeddings,
            positionEmbeddings,
            queryWeights,
            keyWeights,
            valueWeights,
            attentionOutputWeights,
            feedForwardInputWeights,
            feedForwardInputBias,
            feedForwardOutputWeights,
            feedForwardOutputBias,
            outputWeights,
            outputBias,
            lossHistory
        )

        repeat(epochs) {
            Collections.shuffle(examplesByTarget, randomizer)
            var totalLoss = 0.0
            for ((tokenIds, targetId) in examplesByTarget) {
                val forward = model.forwardPass(tokenIds)
                totalLoss -= ln(forward.probabilities[targetId].coerceAtLeast(MINIMUM_LOSS_PROBABILITY))
                applyGradients(model, calculateGradients(model, tokenIds, forward, targetId), learningRate)
            }
            lossHistory += totalLoss / examplesByTarget.size
        }

        return model
    }

    internal fun calculateOutputGradients(
        model: TinyTransformerChatModel,
        forward: TinyTransformerForwardPass,
        targetId: Int
    ): TinyTransformerOutputGradients {
        require(targetId in model.vocabulary.indices) { "Target token is outside the vocabulary." }
        val gradientLogits = forward.probabilities.copyOf()
        gradientLogits[targetId] -= 1.0
        val gradientHidden = DoubleArray(model.embeddingSize) { dimension ->
            model.vocabulary.indices.sumOf { tokenId ->
                gradientLogits[tokenId] * model.outputWeights[tokenId][dimension]
            }
        }
        return TinyTransformerOutputGradients(gradientLogits, gradientHidden)
    }

    @Suppress("LongMethod")
    internal fun calculateGradients(
        model: TinyTransformerChatModel,
        tokenIds: List<Int>,
        forward: TinyTransformerForwardPass,
        targetId: Int
    ): TinyTransformerGradients {
        val output = calculateOutputGradients(model, forward, targetId)
        val dimension = model.embeddingSize
        val scale = 1.0 / sqrt(dimension.toDouble())
        val gradientFeedForwardHidden = transposeMultiply(model.feedForwardOutputWeights, output.hidden)
        val gradientFeedForwardPreActivation = DoubleArray(dimension) { index ->
            gradientFeedForwardHidden[index] *
                (1.0 - forward.feedForwardHidden[index] * forward.feedForwardHidden[index])
        }
        val gradientFeedForwardInput =
            transposeMultiply(model.feedForwardInputWeights, gradientFeedForwardPreActivation)
        val gradientAttentionResidual = DoubleArray(dimension) { index ->
            output.hidden[index] + gradientFeedForwardInput[index]
        }
        val gradientAttentionContext = transposeMultiply(model.attentionOutputWeights, gradientAttentionResidual)
        val gradientValues = Array(forward.inputs.size) { position ->
            DoubleArray(dimension) { index -> forward.attention[position] * gradientAttentionContext[index] }
        }
        val gradientAttention = DoubleArray(forward.inputs.size) { position ->
            dot(gradientAttentionContext, forward.values[position])
        }
        val weightedGradient = forward.attention.indices.sumOf {
            forward.attention[it] * gradientAttention[it]
        }
        val gradientQuery = DoubleArray(dimension)
        val gradientKeys = Array(forward.inputs.size) { DoubleArray(dimension) }
        for (position in forward.inputs.indices) {
            val gradientScore = forward.attention[position] *
                (gradientAttention[position] - weightedGradient) * scale
            for (index in 0 until dimension) {
                gradientQuery[index] += gradientScore * forward.keys[position][index]
                gradientKeys[position][index] = gradientScore * forward.query[index]
            }
        }

        val contextIds = tokenIds.takeLast(model.contextSize)
        val positionGradients = Array(forward.inputs.size) { position ->
            val gradientKeyInput = transposeMultiply(model.keyWeights, gradientKeys[position])
            val gradientValueInput = transposeMultiply(model.valueWeights, gradientValues[position])
            DoubleArray(dimension) { index ->
                gradientKeyInput[index] + gradientValueInput[index] +
                    if (position == forward.inputs.lastIndex) gradientAttentionResidual[index] else 0.0
            }
        }
        val queryInputGradient = transposeMultiply(model.queryWeights, gradientQuery)
        val tokenGradients = mutableMapOf<Int, DoubleArray>()
        for (position in forward.inputs.indices) {
            addInPlace(
                tokenGradients.getOrPut(contextIds[position]) { DoubleArray(dimension) },
                positionGradients[position]
            )
        }
        addInPlace(tokenGradients.getValue(contextIds.last()), queryInputGradient)
        val positionEmbeddingGradients = Array(model.contextSize) { DoubleArray(dimension) }
        positionGradients.forEachIndexed { position, gradient ->
            addInPlace(positionEmbeddingGradients[position], gradient)
        }
        addInPlace(positionEmbeddingGradients[forward.inputs.lastIndex], queryInputGradient)

        return TinyTransformerGradients(
            tokenGradients,
            positionEmbeddingGradients,
            outerProduct(gradientQuery, forward.inputs.last()),
            sumOuterProducts(gradientKeys, forward.inputs),
            sumOuterProducts(gradientValues, forward.inputs),
            outerProduct(gradientAttentionResidual, forward.attentionContext),
            outerProduct(gradientFeedForwardPreActivation, forward.attentionResidual),
            gradientFeedForwardPreActivation,
            outerProduct(output.hidden, forward.feedForwardHidden),
            output.hidden,
            outerProduct(output.logits, forward.hidden),
            output.logits
        )
    }

    private fun applyGradients(model: TinyTransformerChatModel, gradients: TinyTransformerGradients, rate: Double) {
        gradients.tokenEmbeddings.forEach { (tokenId, gradient) ->
            updateVector(model.tokenEmbeddings[tokenId], gradient, rate)
        }
        gradients.positionEmbeddings.forEachIndexed { position, gradient ->
            updateVector(model.positionEmbeddings[position], gradient, rate)
        }
        updateMatrix(model.queryWeights, gradients.queryWeights, rate)
        updateMatrix(model.keyWeights, gradients.keyWeights, rate)
        updateMatrix(model.valueWeights, gradients.valueWeights, rate)
        updateMatrix(model.attentionOutputWeights, gradients.attentionOutputWeights, rate)
        updateMatrix(model.feedForwardInputWeights, gradients.feedForwardInputWeights, rate)
        updateVector(model.feedForwardInputBias, gradients.feedForwardInputBias, rate)
        updateMatrix(model.feedForwardOutputWeights, gradients.feedForwardOutputWeights, rate)
        updateVector(model.feedForwardOutputBias, gradients.feedForwardOutputBias, rate)
        updateMatrix(model.outputWeights, gradients.outputWeights, rate)
        updateVector(model.outputBias, gradients.outputBias, rate)
    }

    private fun outerProduct(output: DoubleArray, input: DoubleArray): Array<DoubleArray> =
        Array(output.size) { row -> DoubleArray(input.size) { column -> output[row] * input[column] } }

    private fun sumOuterProducts(
        outputByPosition: Array<DoubleArray>,
        inputByPosition: Array<DoubleArray>
    ): Array<DoubleArray> =
        Array(outputByPosition.first().size) { row ->
            DoubleArray(inputByPosition.first().size) { column ->
                outputByPosition.indices.sumOf { position ->
                    outputByPosition[position][row] * inputByPosition[position][column]
                }
            }
        }

    private fun addInPlace(target: DoubleArray, addition: DoubleArray) {
        target.indices.forEach { target[it] += addition[it] }
    }

    private fun updateVector(parameters: DoubleArray, gradient: DoubleArray, rate: Double) {
        parameters.indices.forEach { parameters[it] -= rate * gradient[it] }
    }

    private fun updateMatrix(parameters: Array<DoubleArray>, gradient: Array<DoubleArray>, rate: Double) {
        parameters.indices.forEach { updateVector(parameters[it], gradient[it], rate) }
    }

    fun save(model: TinyTransformerChatModel, path: Path) {
        val dto = TinyTransformerChatModelDto(
            vocabulary = model.vocabulary,
            contextSize = model.contextSize,
            embeddingSize = model.embeddingSize,
            tokenEmbeddings = model.tokenEmbeddings.map { it.toList() },
            positionEmbeddings = model.positionEmbeddings.map { it.toList() },
            queryWeights = model.queryWeights.map { it.toList() },
            keyWeights = model.keyWeights.map { it.toList() },
            valueWeights = model.valueWeights.map { it.toList() },
            attentionOutputWeights = model.attentionOutputWeights.map { it.toList() },
            feedForwardInputWeights = model.feedForwardInputWeights.map { it.toList() },
            feedForwardInputBias = model.feedForwardInputBias.toList(),
            feedForwardOutputWeights = model.feedForwardOutputWeights.map { it.toList() },
            feedForwardOutputBias = model.feedForwardOutputBias.toList(),
            outputWeights = model.outputWeights.map { it.toList() },
            outputBias = model.outputBias.toList(),
            lossHistory = model.lossHistory
        )
        FileIo.writeAtomically(path, JsonCodec.json.encodeToString(dto))
    }

    fun load(path: Path): TinyTransformerChatModel? {
        if (Files.notExists(path)) return null
        return try {
            val dto = JsonCodec.json.decodeFromString<TinyTransformerChatModelDto>(Files.readString(path))
            TinyTransformerChatModel(
                dto.vocabulary,
                dto.contextSize,
                dto.embeddingSize,
                dto.tokenEmbeddings.map { it.toDoubleArray() }.toTypedArray(),
                dto.positionEmbeddings.map { it.toDoubleArray() }.toTypedArray(),
                dto.queryWeights.map { it.toDoubleArray() }.toTypedArray(),
                dto.keyWeights.map { it.toDoubleArray() }.toTypedArray(),
                dto.valueWeights.map { it.toDoubleArray() }.toTypedArray(),
                dto.attentionOutputWeights.map { it.toDoubleArray() }.toTypedArray(),
                dto.feedForwardInputWeights.map { it.toDoubleArray() }.toTypedArray(),
                dto.feedForwardInputBias.toDoubleArray(),
                dto.feedForwardOutputWeights.map { it.toDoubleArray() }.toTypedArray(),
                dto.feedForwardOutputBias.toDoubleArray(),
                dto.outputWeights.map { it.toDoubleArray() }.toTypedArray(),
                dto.outputBias.toDoubleArray(),
                dto.lossHistory
            )
        } catch (exception: kotlinx.serialization.SerializationException) {
            FileIo.logWarning("Experimentelles Transformer-Modell konnte nicht geladen werden: $path", exception)
            null
        } catch (exception: IllegalArgumentException) {
            FileIo.logWarning("Experimentelles Transformer-Modell ist ungültig: $path", exception)
            null
        }
    }

    private fun dot(left: DoubleArray, right: DoubleArray): Double =
        left.indices.sumOf { left[it] * right[it] }

    private fun multiply(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray =
        DoubleArray(matrix.size) { row -> dot(matrix[row], vector) }

    private fun transposeMultiply(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray =
        DoubleArray(matrix.firstOrNull()?.size ?: 0) { column ->
            matrix.indices.sumOf { row -> matrix[row][column] * vector[row] }
        }

    private fun updateOuterProduct(row: DoubleArray, input: DoubleArray, scale: Double) {
        for (column in row.indices) {
            row[column] -= scale * input[column]
        }
    }

    private fun updateOuterProduct(
        matrix: Array<DoubleArray>,
        input: DoubleArray,
        gradient: DoubleArray,
        learningRate: Double
    ) {
        for (row in matrix.indices) {
            updateOuterProduct(matrix[row], input, learningRate * gradient[row])
        }
    }

    private fun softmax(values: DoubleArray): DoubleArray {
        val maximum = values.maxOrNull() ?: 0.0
        val shifted = DoubleArray(values.size) {
            exp((values[it] - maximum).coerceIn(-SOFTMAX_EXPONENT_LIMIT, SOFTMAX_EXPONENT_LIMIT))
        }
        val total = shifted.sum()
        return if (total == 0.0) DoubleArray(values.size) else DoubleArray(values.size) { shifted[it] / total }
    }
}

@Serializable
private data class TinyTransformerChatModelDto(
    val vocabulary: List<String>,
    val contextSize: Int,
    val embeddingSize: Int,
    val tokenEmbeddings: List<List<Double>>,
    val positionEmbeddings: List<List<Double>>,
    val queryWeights: List<List<Double>>,
    val keyWeights: List<List<Double>>,
    val valueWeights: List<List<Double>>,
    val attentionOutputWeights: List<List<Double>>,
    val feedForwardInputWeights: List<List<Double>>,
    val feedForwardInputBias: List<Double>,
    val feedForwardOutputWeights: List<List<Double>>,
    val feedForwardOutputBias: List<Double>,
    val outputWeights: List<List<Double>>,
    val outputBias: List<Double>,
    val lossHistory: List<Double>
)

private fun tokenizeWords(text: String): List<String> =
    Regex("<[^>]+>|[\\p{L}\\p{N}]+|[^\\s\\p{L}\\p{N}]")
        .findAll(TextFold.fold(text))
        .map { it.value }
        .toList()

@Suppress("MaxLineLength")
internal object ExperimentalChatCorpus {
    val examples = listOf(
        "hallo" to "Hallo! Was möchtest du besprechen?",
        "hi" to "Hallo! Wie kann ich dir helfen?",
        "guten morgen" to "Guten Morgen! Was liegt heute an?",
        "wie geht es dir" to "Danke der Nachfrage. Ich bin bereit zu helfen. Wie geht es dir?",
        "mir geht es gut" to "Das freut mich. Wobei kann ich dich unterstützen?",
        "was kannst du" to "Ich kann einfache Kotlin-Beispiele erstellen und Code erklären.",
        "wer bist du" to "Ich bin Markusaller, ein lokaler experimenteller Assistent.",
        "bist du ein sprachmodell" to "Ich bin ein kleines selbst trainiertes Modell und kann Fehler machen.",
        "was ist kotlin" to "Kotlin ist eine Programmiersprache für Anwendungen und die Java Virtual Machine.",
        "was ist eine funktion" to "Eine Funktion nimmt Eingaben entgegen und kann ein Ergebnis zurückgeben.",
        "was ist eine datenklasse" to "Eine Datenklasse bündelt strukturierte Werte in einer Kotlin-Klasse.",
        "was ist ein tokenizer" to "Ein Tokenizer zerlegt Text in kleinere Einheiten, die ein Modell verarbeitet.",
        "was ist attention" to "Attention gewichtet frühere Token danach, wie relevant sie für den aktuellen Schritt sind.",
        "wie funktioniert ein transformer" to "Ein Transformer verarbeitet Token mit Attention und lernt Vorhersagen für nächste Token.",
        "was bedeutet training" to "Beim Training werden Modellgewichte anhand von Beispielen und Vorhersagefehlern angepasst.",
        "kannst du im internet suchen" to "Nein. Dieses Programm arbeitet lokal und hat keinen Webzugriff.",
        "bist du immer richtig" to "Nein. Das Modell ist klein und experimentell. Bitte überprüfe wichtige Antworten.",
        "danke" to "Gern! Stell einfach eine weitere Frage.",
        "danke dir" to "Gern geschehen. Wobei kann ich dir noch helfen?",
        "bis bald" to "Bis bald! Du kannst jederzeit wieder schreiben.",
        "was ist der unterschied zwischen liste und menge" to "Eine Liste bewahrt ihre Reihenfolge. Eine Menge enthält jedes Element höchstens einmal.",
        "was ist ein test" to "Ein Test prüft, ob ein Programm für ausgewählte Eingaben das erwartete Ergebnis liefert.",
        "wie lernt das programm" to "Es passt seine Gewichte anhand lokaler Dialogbeispiele an. Das ist kein großes vortrainiertes Modell.",
        "warum klingt deine antwort manchmal falsch" to "Meine Architektur und mein Trainingskorpus sind sehr klein. Dadurch kann ich unpassende Texte erzeugen.",
        "erkläre das genauer" to "Nenne mir bitte das Thema oder den Satz, den ich genauer erklären soll.",
        "ich möchte kotlin lernen" to "Gern. Wir können mit Variablen, Funktionen und kleinen Beispielen anfangen.",
        "was ist eine variable" to "Eine Variable verbindet einen Namen mit einem gespeicherten Wert.",
        "wie speichere ich daten" to "Das hängt von der Anwendung ab. In Kotlin kannst du Dateien oder eine Datenbank verwenden.",
        "kannst du code schreiben" to "Ich kann einfache Kotlin-Klassen, Datenklassen, Funktionen und Tests als Vorlagen erzeugen.",
        "was ist maschinelles lernen" to "Maschinelles Lernen passt Modellparameter anhand von Beispielen an.",
        "was ist ein neuronales netz" to "Ein neuronales Netz verarbeitet Zahlen durch verbundene, trainierbare Rechenschichten.",
        "was ist ein embedding" to "Ein Embedding stellt ein Token als Vektor aus Zahlen dar.",
        "was ist ein kontextfenster" to "Ein Kontextfenster begrenzt, wie viele vorherige Token das Modell gleichzeitig berücksichtigt.",
        "kannst du dich an unser gespräch erinnern" to "Ich kann den Kontext der aktuellen Sitzung nutzen, aber nach einem Neustart nicht automatisch daran anknüpfen.",
        "was bedeutet experimentell" to "Experimentell bedeutet, dass dieses Modell noch begrenzt und nicht zuverlässig genug für wichtige Entscheidungen ist.",
        "erzähle einen witz" to "Ich bin ein kleines lokales Modell und kenne nur wenige Dialogbeispiele. Ein guter Witz gehört noch nicht zu meinen Stärken.",
        "wie spät ist es" to "Ich habe keinen verlässlichen Zugriff auf die aktuelle Uhrzeit.",
        "was ist python" to "Python ist eine Programmiersprache, die häufig für Skripte und Datenverarbeitung eingesetzt wird.",
        "warum braucht ein modell viele daten" to "Viele vielfältige Beispiele helfen einem Modell, Muster zu lernen und auf neue Formulierungen zu übertragen.",
        "was kann ich als nächstes fragen" to "Frag mich zum Beispiel nach Kotlin, einer Funktion, einer Datenklasse oder dem Transformer-Training."
    )
}
