package ai

import kotlin.math.ln
import kotlin.math.sqrt

/** Sparse word/character TF-IDF centroids trained from labelled examples. */
internal class SupervisedIntentClassifier(
    private val examples: List<IntentEvaluationCase>
) {
    private val documentFrequency: Map<String, Int> = examples
        .map { features(it.prompt).keys }
        .flatten()
        .groupingBy { it }
        .eachCount()
    private val idf: Map<String, Double> = documentFrequency.mapValues { (_, frequency) ->
        ln((examples.size + 1.0) / (frequency + 1.0)) + 1.0
    }
    private val centroids: Map<Intent, Map<String, Double>> = examples
        .groupBy { it.expected }
        .mapValues { (_, intentExamples) ->
            val vectors = intentExamples.map { vectorize(it.prompt) }
            val combined = mutableMapOf<String, Double>()
            vectors.forEach { vector ->
                vector.forEach { (feature, value) -> combined[feature] = (combined[feature] ?: 0.0) + value }
            }
            normalize(combined.mapValues { (_, value) -> value / vectors.size })
        }

    /** Bewertet den ähnlichsten Intent-Zentroid und akzeptiert ihn nur über beiden Schwellen. */
    fun predict(
        prompt: String,
        similarityThreshold: Double,
        marginThreshold: Double
    ): IntentPrediction? {
        val vector = vectorize(prompt)
        if (vector.isEmpty()) return null
        val ranked = centroids.map { (intent, centroid) ->
            intent to cosine(vector, centroid)
        }.sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)
        val margin = if (second == null) best.second else best.second - second.second
        val accepted = best.second >= similarityThreshold && margin >= marginThreshold
        return IntentPrediction(
            intent = if (accepted) best.first else Intent.Unknown,
            confidence = best.second,
            method = "supervised-tfidf",
            similarity = best.second,
            margin = margin,
            topCandidate = best.first
        )
    }

    /** Klassifiziert jedes Beispiel erneut, wobei es selbst aus dem jeweiligen Trainingssatz entfernt wird. */
    fun leaveOneOut(
        similarityThreshold: Double,
        marginThreshold: Double
    ): List<IntentEvaluationResult> = examples.mapIndexed { index, example ->
        val training = examples.filterIndexed { candidateIndex, _ -> candidateIndex != index }
        val prediction = SupervisedIntentClassifier(training).predict(
            example.prompt,
            similarityThreshold,
            marginThreshold
        ) ?: IntentPrediction(Intent.Unknown, 0.0, "supervised-tfidf")
        IntentEvaluationResult(example.prompt, example.expected, prediction)
    }

    /** Kombiniert Wort-, Zeichen-N-Gramm- und Bigramm-Merkmale zu einem TF-IDF-Vektor. */
    private fun vectorize(text: String): Map<String, Double> {
        val raw = features(text)
        if (raw.isEmpty()) return emptyMap()
        return normalize(raw.mapValues { (feature, count) ->
            count * (idf[feature] ?: (ln(examples.size + 1.0) + 1.0))
        })
    }

    /** Erzeugt wortgebundene Zeichen-N-Gramme sowie Wort- und Nachbarwortmerkmale. */
    private fun features(text: String): Map<String, Double> {
        val words = WORD_PATTERN.findAll(TextFold.fold(text)).map { it.value }.toList()
        val features = mutableMapOf<String, Double>()
        words.forEach { word ->
            features["w:$word"] = (features["w:$word"] ?: 0.0) + 1.0
            val bounded = "^$word$"
            for (size in 3..5) {
                for (start in 0..bounded.length - size) {
                    val ngram = bounded.substring(start, start + size)
                    features["c$size:$ngram"] = (features["c$size:$ngram"] ?: 0.0) + 1.0
                }
            }
        }
        words.zipWithNext().forEach { (left, right) ->
            features["b:$left $right"] = (features["b:$left $right"] ?: 0.0) + 1.0
        }
        return features
    }

    /** Normiert Merkmale auf Einheitslänge, damit Cosine-Werte nicht von Textlänge abhängen. */
    private fun normalize(vector: Map<String, Double>): Map<String, Double> {
        val norm = sqrt(vector.values.sumOf { it * it })
        return if (norm == 0.0) emptyMap() else vector.mapValues { (_, value) -> value / norm }
    }

    /** Berechnet das Skalarprodukt normalisierter Sparse-Vektoren. */
    private fun cosine(left: Map<String, Double>, right: Map<String, Double>): Double =
        left.entries.sumOf { (feature, value) -> value * (right[feature] ?: 0.0) }

    companion object {
        private val WORD_PATTERN = Regex("[\\p{L}\\p{N}]+")
    }
}
