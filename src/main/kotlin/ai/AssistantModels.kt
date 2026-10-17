package ai

import java.nio.file.Path

/** Geschlossene Menge der Gesprächs- und Code-Aufgabentypen, die der Router unterscheiden kann. */
sealed class Intent {
    data object GenerateClass : Intent()
    data object GenerateDataClass : Intent()
    data object GenerateFunction : Intent()
    data object GenerateTest : Intent()
    data object Explain : Intent()
    data object Tokenize : Intent()
    data object Smalltalk : Intent()
    data object Unknown : Intent()
}

/** Speicherorte und Annahmeschwellen; Grenzwerte werden beim Erzeugen auf gültige Werte geprüft. */
data class AssistantConfig(
    val storagePath: Path = defaultAssistantStoragePath(),
    val autoInitialize: Boolean = true,
    val writeBrainStateOnInit: Boolean = false,
    val intentSimilarityThreshold: Double = 0.05,
    val intentMarginThreshold: Double = 0.04,
    val knowledgeSimilarityThreshold: Double = 0.82,
    val knowledgeMarginThreshold: Double = 0.05,
    val knowledgeMinimumTokenOverlap: Double = 0.25,
    val knowledgeSingleMatchSimilarityThreshold: Double = 0.90
) {
    init {
        require(intentSimilarityThreshold in -1.0..1.0)
        require(intentMarginThreshold in 0.0..2.0)
        require(knowledgeSimilarityThreshold in -1.0..1.0)
        require(knowledgeMarginThreshold in 0.0..2.0)
        require(knowledgeMinimumTokenOverlap in 0.0..1.0)
        require(knowledgeSingleMatchSimilarityThreshold in -1.0..1.0)
    }

    /** Pfad des serialisierten Tokenizers im Profilverzeichnis. */
    fun tokenizerFile(): Path = storagePath.resolve("tokenizer_vocab.txt")
    /** Korpusdatei, aus der bei Vorhandensein das Vokabular gelernt wird. */
    fun tokenizerCorpusFile(): Path = storagePath.resolve("tokenizer_corpus.txt")
    /** Pfad für den auf Anfrage erzeugten Diagnose-Snapshot. */
    fun brainStateFile(): Path = storagePath.resolve("brain_state.json")
    /** Pfad des optional gespeicherten Feedforward-Modells. */
    fun feedforwardModelFile(): Path = storagePath.resolve("feedforward_model.json")
    /** Pfad des separat trainierten experimentellen Transformer-Chatmodells. */
    fun transformerChatModelFile(): Path = storagePath.resolve("transformer_chat_model.json")
    /** Pfad der dauerhaft gespeicherten Prompt-Antwort-Beispiele. */
    fun knowledgeFile(): Path = storagePath.resolve("knowledge.jsonl")
    /** Pfad der gelabelten Intent-Trainingsbeispiele. */
    fun trainingExamplesFile(): Path = storagePath.resolve("training_examples.jsonl")
    /** Pfad gespeicherter Schachpartien. */
    fun chessGamesFile(): Path = storagePath.resolve("chess_games.jsonl")
    /** Fingerprint zur Erkennung eines veralteten Tokenizer-Zustands. */
    fun tokenizerFingerprintFile(): Path = storagePath.resolve("tokenizer_vocab.hash")
    /** Fingerprint zur Erkennung veralteter Skip-Gram-Embeddings. */
    fun embeddingFingerprintFile(): Path = storagePath.resolve("tokenizer_embeddings.hash")
}

/** Standardprofil des Betriebssystembenutzers, unabhängig vom Arbeitsverzeichnis. */
private fun defaultAssistantStoragePath(): Path =
    Path.of(System.getProperty("user.home"), ".markusaller")

/** Ein gespeicherter Wissenseintrag samt Ähnlichkeitswert des aktuellen Abrufs. */
data class KnowledgeMatch(
    val prompt: String,
    val answer: String,
    val tags: List<String>,
    val similarity: Double
)

/** Antwort und Zustandssignale, die die GUI für den nächsten Gesprächsschritt benötigt. */
data class ConversationTurnResult(
    val response: String,
    val learnedKnowledge: Boolean = false,
    val awaitingClarification: Boolean = false,
    val awaitingConfirmation: Boolean = false
)

/** Token, ID und Vektor in positionsgleicher Reihenfolge für Diagnose und Anzeige. */
data class TokenEmbeddingResult(
    val tokens: List<String>,
    val ids: List<Int>,
    val embeddings: List<List<Double>>
)

/**
 * Laufzeitmodell mit Einbettungen und zwei linearen Schichten.
 * Es generiert experimentelle Fortsetzungen und ist nicht an den Prompt-Router angeschlossen.
 */
data class FeedforwardLanguageModel(
    val vocabulary: List<String>,
    val contextSize: Int,
    val hiddenSize: Int,
    val embeddingDim: Int = 32,
    val embeddings: Array<DoubleArray>,
    val w1: Array<DoubleArray>,
    val b1: DoubleArray,
    val w2: Array<DoubleArray>,
    val b2: DoubleArray,
    val lossHistory: MutableList<Double> = mutableListOf()
) {
    init {
        require(contextSize > 0) { "contextSize must be positive" }
        require(hiddenSize > 0) { "hiddenSize must be positive" }
        require(embeddingDim > 0) { "embeddingDim must be positive" }
        if (vocabulary.isEmpty()) {
            require(
                embeddings.isEmpty() && w1.isEmpty() && b1.isEmpty() &&
                    w2.isEmpty() && b2.isEmpty()
            ) { "An empty vocabulary cannot contain model parameters." }
        } else {
            require(vocabulary.distinct().size == vocabulary.size) { "Vocabulary entries must be unique." }
            require(embeddings.size == vocabulary.size && embeddings.all { it.size == embeddingDim }) {
                "Embedding matrix shape does not match vocabulary and embedding dimensions."
            }
            require(
                w1.size.toLong() == contextSize.toLong() * embeddingDim &&
                    w1.all { it.size == hiddenSize }
            ) { "First layer shape does not match context and hidden dimensions." }
            require(b1.size == hiddenSize) { "First layer bias shape does not match hidden dimension." }
            require(w2.size == hiddenSize && w2.all { it.size == vocabulary.size }) {
                "Second layer shape does not match hidden and vocabulary dimensions."
            }
            require(b2.size == vocabulary.size) { "Second layer bias shape does not match vocabulary size." }
        }
    }

    private val vocabularyIndex: Map<String, Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        vocabulary.withIndex().associate { it.value to it.index }
    }

    /** Berechnet aus dem letzten Kontextfenster Logits und gibt das wahrscheinlichste Token zurück. */
    fun predictNextToken(context: List<String>): String {
        if (vocabulary.isEmpty()) return ""
        val normalizedContext = context.map(TextFold::fold).takeLast(contextSize)
        val input = DoubleArray(contextSize * embeddingDim)

        for (tokenIndex in normalizedContext.indices) {
            val tokenId = vocabularyIndex[normalizedContext[tokenIndex]] ?: -1
            val vector = if (tokenId >= 0) embeddings[tokenId] else DoubleArray(embeddingDim)
            val offset = tokenIndex * embeddingDim
            for (dim in vector.indices) {
                input[offset + dim] = vector[dim]
            }
        }

        val hidden = DoubleArray(hiddenSize)
        for (hiddenIndex in 0 until hiddenSize) {
            var sum = b1[hiddenIndex]
            for (inputIndex in input.indices) {
                sum += input[inputIndex] * w1[inputIndex][hiddenIndex]
            }
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

        val probabilities = softmax(logits)
        val bestIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
        return vocabulary[bestIndex]
    }

    /** Wandelt Logits numerisch stabil in eine Wahrscheinlichkeitsverteilung um. */
    private fun softmax(values: DoubleArray): DoubleArray {
        val max = values.maxOrNull() ?: 0.0
        val shifted = values.map { kotlin.math.exp(it - max) }.toDoubleArray()
        val total = shifted.sum()
        return shifted.map { if (total == 0.0) 0.0 else it / total }.toDoubleArray()
    }

    /** Vergleicht Array-Inhalte statt Referenzen, damit Modell-Roundtrips in Tests prüfbar sind. */
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is FeedforwardLanguageModel &&
                vocabulary == other.vocabulary &&
                contextSize == other.contextSize &&
                hiddenSize == other.hiddenSize &&
                embeddingDim == other.embeddingDim &&
                embeddings.contentDeepEquals(other.embeddings) &&
                w1.contentDeepEquals(other.w1) &&
                b1.contentEquals(other.b1) &&
                w2.contentDeepEquals(other.w2) &&
                b2.contentEquals(other.b2) &&
                lossHistory == other.lossHistory
            )

    /** Berechnet einen Hash aus denselben Modellfeldern wie [equals]. */
    override fun hashCode(): Int {
        var result = vocabulary.hashCode()
        result = 31 * result + contextSize
        result = 31 * result + hiddenSize
        result = 31 * result + embeddingDim
        result = 31 * result + embeddings.contentDeepHashCode()
        result = 31 * result + w1.contentDeepHashCode()
        result = 31 * result + b1.contentHashCode()
        result = 31 * result + w2.contentDeepHashCode()
        result = 31 * result + b2.contentHashCode()
        result = 31 * result + lossHistory.hashCode()
        return result
    }
}

/** Diagnoseergebnis eines Tokenizer-Inputs inklusive Anzahl unbekannter IDs. */
data class TokenizerStressCase(
    val input: String,
    val tokens: List<String>,
    val ids: List<Int>,
    val unknownTokenCount: Int
)
