package ai

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.random.Random
import kotlinx.serialization.encodeToString

/** Trains and persists the feed-forward language model. */
class LanguageModelTrainer(private val tokenizer: BpeTokenizer) {
    /** Trainiert ein Modell mit den angegebenen Architektur- und Optimierungsparametern. */
    fun train(
        corpus: List<String>,
        contextSize: Int = 2,
        hiddenSize: Int = 32,
        epochs: Int = 200,
        learningRate: Double = 0.05
    ): FeedforwardLanguageModel =
        trainFeedforwardLanguageModel(corpus, contextSize, hiddenSize, epochs, learningRate)

    /**
     * Generates a deterministic continuation. [maxTokens] is the maximum number
     * of tokens generated after the seed; greedy prediction keeps this path
     * reproducible without an external language model.
     */
    fun generate(
        model: FeedforwardLanguageModel,
        seed: String,
        maxTokens: Int
    ): String {
        val generatedLimit = maxTokens.coerceAtLeast(0)
        val seedTokens = tokenizer.tokenize(seed)
            .filter { it.isNotBlank() && it != tokenizer.unknownToken() }
            .map { it.lowercase() }
        val output = seedTokens.toMutableList()
        val context = output.toMutableList()

        for (index in 0 until generatedLimit) {
            val next = model.predictNextToken(context)
            if (next.isBlank() || next == tokenizer.unknownToken()) break
            output += next
            context += next
        }
        return output.joinToString(" ")
    }

    private fun trainFeedforwardLanguageModel(
        corpus: List<String>,
        contextSize: Int,
        hiddenSize: Int,
        epochs: Int,
        learningRate: Double
    ): FeedforwardLanguageModel {
        // Jeder Trainingsfall verwendet ein festes Kontextfenster und das darauffolgende Token als Ziel.
        require(contextSize > 0) { "contextSize must be positive" }
        require(hiddenSize > 0) { "hiddenSize must be positive" }
        require(epochs >= 0) { "epochs must not be negative" }
        require(learningRate.isFinite() && learningRate > 0.0) {
            "learningRate must be finite and positive"
        }
        val tokenizedCorpus = corpus
            .map { sentence -> tokenizer.tokenize(sentence).filter { it.isNotBlank() && it != tokenizer.unknownToken() }.map { it.lowercase() } }
            .filter { it.size >= contextSize + 1 }

        if (tokenizedCorpus.isEmpty()) {
            return FeedforwardLanguageModel(
                vocabulary = emptyList(),
                contextSize = contextSize,
                hiddenSize = hiddenSize,
                embeddings = emptyArray(),
                w1 = emptyArray(),
                b1 = DoubleArray(0),
                w2 = emptyArray(),
                b2 = DoubleArray(0),
                lossHistory = mutableListOf()
            )
        }

        val vocabulary = tokenizedCorpus.flatten().distinct().sorted()
        val vocabularyIndex = vocabulary.withIndex().associate { it.value to it.index }
        val embeddingDim = 32
        val inputSize = contextSize * embeddingDim

        val random = Random(7)
        val embeddings = Array(vocabulary.size) { index ->
            DoubleArray(embeddingDim) { dim -> (random.nextDouble() - 0.5) * 0.1 + if (index == 0) 0.01 else 0.0 }
        }
        val w1 = Array(inputSize) { DoubleArray(hiddenSize) { (random.nextDouble() - 0.5) * 0.1 } }
        val b1 = DoubleArray(hiddenSize) { (random.nextDouble() - 0.5) * 0.1 }
        val w2 = Array(hiddenSize) { DoubleArray(vocabulary.size) { (random.nextDouble() - 0.5) * 0.1 } }
        val b2 = DoubleArray(vocabulary.size) { (random.nextDouble() - 0.5) * 0.1 }
        val examples = mutableListOf<Pair<List<String>, String>>()
        for (sentence in tokenizedCorpus) {
            for (index in 0..sentence.size - contextSize - 1) {
                examples += sentence.subList(index, index + contextSize) to sentence[index + contextSize]
            }
        }

        val lossHistory = mutableListOf<Double>()
        val gradW2 = Array(hiddenSize) { DoubleArray(vocabulary.size) }
        val gradB2 = DoubleArray(vocabulary.size)
        val gradW1 = Array(inputSize) { DoubleArray(hiddenSize) }
        val gradB1 = DoubleArray(hiddenSize)
        val gradLogits = DoubleArray(vocabulary.size)
        val gradHidden = DoubleArray(hiddenSize)
        val shuffleRandom = java.util.Random(7)

        repeat(epochs) {
            Collections.shuffle(examples, shuffleRandom)
            var batchLoss = 0.0
            for ((context, target) in examples) {
                val targetId = vocabularyIndex[target] ?: continue
                val input = DoubleArray(inputSize)
                for (tokenOffset in context.indices) {
                    val token = context[tokenOffset]
                    val tokenId = vocabularyIndex[token] ?: 0
                    val vector = embeddings[tokenId]
                    val start = tokenOffset * embeddingDim
                    for (dim in vector.indices) {
                        input[start + dim] = vector[dim]
                    }
                }

                val hiddenPre = DoubleArray(hiddenSize)
                val hidden = DoubleArray(hiddenSize)
                for (hiddenIndex in 0 until hiddenSize) {
                    var sum = b1[hiddenIndex]
                    for (inputIndex in input.indices) {
                        sum += input[inputIndex] * w1[inputIndex][hiddenIndex]
                    }
                    hiddenPre[hiddenIndex] = sum
                    hidden[hiddenIndex] = kotlin.math.tanh(sum)
                }

                val logits = DoubleArray(vocabulary.size)
                for (outputIndex in logits.indices) {
                    var sum = b2[outputIndex]
                    for (hiddenIndex in 0 until hiddenSize) {
                        sum += hidden[hiddenIndex] * w2[hiddenIndex][outputIndex]
                    }
                    logits[outputIndex] = sum
                }

                // Softmax plus negative Log-Likelihood bildet die Kreuzentropie des nächsten Tokens.
                val probabilities = softmax(logits)
                val trueProbability = probabilities[targetId].coerceAtLeast(1e-12)
                val loss = -kotlin.math.ln(trueProbability)
                batchLoss += loss

                for (outputIndex in gradLogits.indices) {
                    gradLogits[outputIndex] = probabilities[outputIndex]
                }
                gradLogits[targetId] -= 1.0

                gradB2.fill(0.0)
                for (hiddenIndex in 0 until hiddenSize) {
                    for (outputIndex in 0 until vocabulary.size) {
                        gradW2[hiddenIndex][outputIndex] = gradLogits[outputIndex] * hidden[hiddenIndex]
                        gradB2[outputIndex] += gradLogits[outputIndex]
                    }
                }

                for (hiddenIndex in 0 until hiddenSize) {
                    var sum = 0.0
                    for (outputIndex in 0 until vocabulary.size) {
                        sum += w2[hiddenIndex][outputIndex] * gradLogits[outputIndex]
                    }
                    gradHidden[hiddenIndex] = sum * (1.0 - hidden[hiddenIndex] * hidden[hiddenIndex])
                }

                gradB1.fill(0.0)
                for (inputIndex in input.indices) {
                    for (hiddenIndex in 0 until hiddenSize) {
                        gradW1[inputIndex][hiddenIndex] = input[inputIndex] * gradHidden[hiddenIndex]
                        gradB1[hiddenIndex] += gradHidden[hiddenIndex]
                    }
                }

                // Backpropagiert den Fehler durch beide Dense-Schichten bis in die Kontext-Embeddings.
                val gradEmbeddings = mutableMapOf<Int, DoubleArray>()
                for (tokenOffset in context.indices) {
                    val token = context[tokenOffset]
                    val tokenId = vocabularyIndex[token] ?: continue
                    val offset = tokenOffset * embeddingDim
                    val gradVector = gradEmbeddings.getOrPut(tokenId) { DoubleArray(embeddingDim) }
                    for (dim in 0 until embeddingDim) {
                        var sum = 0.0
                        for (hiddenIndex in 0 until hiddenSize) {
                            sum += gradHidden[hiddenIndex] * w1[offset + dim][hiddenIndex]
                        }
                        gradVector[dim] += sum
                    }
                }

                for (hiddenIndex in 0 until hiddenSize) {
                    for (outputIndex in 0 until vocabulary.size) {
                        w2[hiddenIndex][outputIndex] -= learningRate * gradW2[hiddenIndex][outputIndex]
                    }
                }
                for (outputIndex in 0 until vocabulary.size) {
                    b2[outputIndex] -= learningRate * gradB2[outputIndex]
                }
                for (inputIndex in input.indices) {
                    for (hiddenIndex in 0 until hiddenSize) {
                        w1[inputIndex][hiddenIndex] -= learningRate * gradW1[inputIndex][hiddenIndex]
                    }
                }
                for (hiddenIndex in 0 until hiddenSize) {
                    b1[hiddenIndex] -= learningRate * gradB1[hiddenIndex]
                }
                for ((tokenId, gradVector) in gradEmbeddings) {
                    for (dim in 0 until embeddingDim) {
                        embeddings[tokenId][dim] -= learningRate * gradVector[dim]
                    }
                }
            }
            lossHistory += if (examples.isEmpty()) 0.0 else batchLoss / examples.size
        }

        return FeedforwardLanguageModel(
            vocabulary = vocabulary,
            contextSize = contextSize,
            hiddenSize = hiddenSize,
            embeddingDim = embeddingDim,
            embeddings = embeddings,
            w1 = w1,
            b1 = b1,
            w2 = w2,
            b2 = b2,
            lossHistory = lossHistory
        )
    }

    private fun softmax(values: DoubleArray): DoubleArray {
        // Das Abziehen des Maximums verhindert unnötigen numerischen Überlauf bei exp.
        val max = values.maxOrNull() ?: 0.0
        val shifted = values.map { kotlin.math.exp(it - max) }.toDoubleArray()
        val total = shifted.sum()
        return shifted.map { if (total == 0.0) 0.0 else it / total }.toDoubleArray()
    }

    /** Serialisiert Vokabular, Embeddings, Gewichte und Bias-Werte atomar in JSON. */
    fun save(model: FeedforwardLanguageModel, path: Path) {
        val dto = FeedforwardModelDto(
            vocabulary = model.vocabulary,
            contextSize = model.contextSize,
            hiddenSize = model.hiddenSize,
            embeddingDim = model.embeddingDim,
            embeddings = model.embeddings.map { row -> JsonCodec.sanitizeVector(row.toList()) },
            w1 = model.w1.map { row -> JsonCodec.sanitizeVector(row.toList()) },
            b1 = JsonCodec.sanitizeVector(model.b1.toList()),
            w2 = model.w2.map { row -> JsonCodec.sanitizeVector(row.toList()) },
            b2 = JsonCodec.sanitizeVector(model.b2.toList())
        )
        FileIo.writeAtomically(path, JsonCodec.json.encodeToString(dto))
    }

    /** Lädt ein Modell; fehlende oder ungültige Dateien werden protokolliert und als null gemeldet. */
    fun load(path: Path): FeedforwardLanguageModel? {
        if (!Files.exists(path)) return null
        return try {
            val dto = JsonCodec.json.decodeFromString(FeedforwardModelDto.serializer(), Files.readString(path))
            FeedforwardLanguageModel(
                vocabulary = dto.vocabulary,
                contextSize = dto.contextSize,
                hiddenSize = dto.hiddenSize,
                embeddingDim = dto.embeddingDim,
                embeddings = dto.embeddings.map { it.toDoubleArray() }.toTypedArray(),
                w1 = dto.w1.map { it.toDoubleArray() }.toTypedArray(),
                b1 = dto.b1.toDoubleArray(),
                w2 = dto.w2.map { it.toDoubleArray() }.toTypedArray(),
                b2 = dto.b2.toDoubleArray(),
                lossHistory = mutableListOf()
            )
        } catch (e: Exception) {
            FileIo.logWarning("Feedforward-Modell konnte nicht geladen werden: ${path}", e)
            null
        }
    }


}
