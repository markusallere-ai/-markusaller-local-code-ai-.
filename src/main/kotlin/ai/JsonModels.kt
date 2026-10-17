package ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Serialisierbare linke/rechte Symbole einer BPE-Merge-Regel. */
@Serializable
data class MergeRuleDto(val left: String, val right: String)

/** Ein Vokabulareintrag mit stabiler ID und gespeichertem Embedding. */
@Serializable
data class EmbeddingRowDto(val token: String, val id: Int, val embedding: List<Double>)

/** Persistierter Tokenizer-Zustand, einschließlich Formatversion und BPE-Regeln. */
@Serializable
data class TokenizerStateDto(
    val version: Int = 1,
    val embeddingDim: Int = 32,
    val mergeRules: List<MergeRuleDto> = emptyList(),
    val vocabulary: List<EmbeddingRowDto> = emptyList()
)

/** Ein einzelnes Token im diagnostischen Brain-Snapshot. */
@Serializable
data class BrainStateTokenDto(val id: Int, val token: String)

/** Begrenzte Vorschau eines Embedding-Zeilenvektors für die Diagnoseansicht. */
@Serializable
data class BrainStateEmbeddingPreviewDto(val row: Int, val values: List<Double>)

/** Cosine-Similarity-Beispiel, das im Brain-Snapshot angezeigt wird. */
@Serializable
data class BrainStateSimilaritySampleDto(val left: String, val right: String, val similarity: Double)

/** Kompakter, menschenlesbarer Zustandsschnappschuss des Tokenizers. */
@Serializable
data class BrainStateSnapshotDto(
    val timestamp: String,
    val vocabSize: Int,
    val unknownId: Int,
    val embeddingShape: List<Int>,
    val tokenizerInfo: List<String>,
    val vocabularySample: List<BrainStateTokenDto>,
    val embeddingPreview: List<BrainStateEmbeddingPreviewDto>,
    val cosineSimilaritySamples: List<BrainStateSimilaritySampleDto>
)

/** JSON-Transportmodell für Vokabular, Gewichte und Architekturparameter des Feedforward-Modells. */
@Serializable
data class FeedforwardModelDto(
    val vocabulary: List<String>,
    val contextSize: Int,
    val hiddenSize: Int,
    val embeddingDim: Int,
    val embeddings: List<List<Double>>,
    val w1: List<List<Double>>,
    val b1: List<Double>,
    val w2: List<List<Double>>,
    val b2: List<Double>
)

/** JSON-Zeile des dauerhaft gespeicherten Prompt-Antwort-Wissens. */
@Serializable
data class KnowledgeEntryDto(
    val prompt: String,
    val answer: String,
    val tags: List<String> = emptyList(),
    val embedding: List<Double> = emptyList()
)

/** JSON-Zeile des Verlaufs; Nachrichtentext ist unabhängig von Zeilenumbrüchen. */
@Serializable
data class HistoryEntryDto(
    val role: String,
    val timestamp: String,
    val text: String
)

/** A persisted snapshot of one chess game, updated after each move. */
@Serializable
data class ChessGameDto(
    val id: String,
    val timestamp: String,
    val result: String,
    val moves: List<String>,
    val status: String
)

/** Zentrale JSON-Konfigurationen und Bereinigung nicht endlicher Zahlenwerte. */
object JsonCodec {
    /** Lesbares JSON für Zustandsdateien, bei dem Standardwerte explizit erhalten bleiben. */
    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = false
    }
    /** Kompaktes JSON für JSONL-Dateien, in denen jedes Objekt eine eigene Zeile bildet. */
    val compactJson = Json {
        encodeDefaults = true
        allowSpecialFloatingPointValues = false
    }

    /** Ersetzt NaN und Unendlichkeiten durch endliche Werte, die der JSON-Codec schreiben kann. */
    fun sanitizeDouble(value: Double): Double = when {
        value.isNaN() -> 0.0
        value.isInfinite() -> if (value > 0) 1e308 else -1e308
        else -> value
    }

    /** Wendet die Zahlbereinigung auf jeden Eintrag eines Vektors an. */
    fun sanitizeVector(values: List<Double>): List<Double> = values.map { sanitizeDouble(it) }
}
