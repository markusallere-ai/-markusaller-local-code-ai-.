package ai

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.random.Random
import kotlinx.serialization.encodeToString

/**
 * Lernt ein BPE-Subwortvokabular und speichert zu jeder Token-ID einen festen Vektor.
 * Die Lern- und Ladeoperationen werden gegen gleichzeitige Tokenizer-Zugriffe gesperrt.
 */
class BpeTokenizer {
    private val endOfWord = "</w>"
    private val mergeRules = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    private val tokenToId = Collections.synchronizedMap(linkedMapOf<String, Int>())
    private val unkToken = "<unk>"
    @Volatile private var embeddings: Array<DoubleArray> = emptyArray()
    private val embeddingDim = 32
    private val tokenizerLock = Any()

    /** Liefert die feste Breite aller Embedding-Vektoren. */
    fun embeddingDimension(): Int = embeddingDim

    /** Lernt häufige Zeichenpaare und baut daraus geordnete Token-IDs. */
    fun train(corpus: List<String>, maxMergeRules: Int) = synchronized(tokenizerLock) {
        require(maxMergeRules >= 0) { "maxMergeRules must not be negative" }
        mergeRules.clear()
        val sourceWords = corpus.flatMap { splitText(TextFold.fold(it)) }.filter { it.isNotBlank() }
        val wordSequences = sourceWords
            .map { word -> symbolsFor(word) }
            .toMutableList()

        val pairCounts = countPairs(wordSequences)
        while (mergeRules.size < maxMergeRules) {
            val bestPair = pairCounts.maxByOrNull { it.value }?.key ?: break
            val bestCount = pairCounts[bestPair] ?: 0
            if (bestCount < 2) break
            mergeRules.add(bestPair)
            mergePairInSequences(wordSequences, bestPair, pairCounts)
        }
        buildVocabulary(wordSequences)
    }

    /** Zerlegt normalisierten Text per gelernter Merge-Regeln in Subwörter. */
    fun tokenize(text: String): List<String> = synchronized(tokenizerLock) {
        tokenizeTokens(text)
    }

    /** Wandelt dieselbe Tokenfolge in die zugehörigen Vokabular-IDs um. */
    fun encode(text: String): List<Int> = synchronized(tokenizerLock) {
        tokenizeTokens(text).map { tokenToId[it] ?: tokenToId[unkToken] ?: 0 }
    }

    /** Liefert Tokens und IDs gemeinsam, ohne dass zwischen den Abbildungen neu trainiert werden kann. */
    fun tokenizeWithIds(text: String): List<Pair<String, Int>> = synchronized(tokenizerLock) {
        tokenizeTokens(text).map { token -> token to (tokenToId[token] ?: tokenToId[unkToken] ?: 0) }
    }

    /** Die ID 0 ist der definierte Ersatz für unbekannte Token. */
    fun unknownId(): Int = synchronized(tokenizerLock) { tokenToId[unkToken] ?: 0 }

    /** Gibt das sichtbare Symbol zurück, das unbekannte Token repräsentiert. */
    fun unknownToken(): String = unkToken

    /** Liest den Vektor einer ID; ungültige IDs fallen auf den Unknown-Vektor zurück. */
    fun embeddingForId(id: Int): List<Double> = synchronized(tokenizerLock) {
        return getEmbedding(id).toList()
    }

    @Deprecated("Use embeddingForId(id); this overload validates that token and id correspond.")
    fun embeddingForToken(token: String, id: Int): List<Double> = synchronized(tokenizerLock) {
        require(tokenId(token) == id) { "Token '$token' does not correspond to vocabulary id $id." }
        getEmbedding(id).toList()
    }

    /**
     * Trainiert Vektoren aus lokalen Kontextfenstern; Sätze bleiben getrennt,
     * und deterministische Negativbeispiele machen den Lauf reproduzierbar.
     */
    fun trainSkipGramEmbeddings(corpus: List<String>, epochs: Int, windowSize: Int): Int = synchronized(tokenizerLock) {
        require(epochs >= 0) { "epochs must not be negative" }
        require(windowSize >= 0) { "windowSize must not be negative" }
        val sentences = corpus.map { sentence ->
            tokenize(sentence).filter { it.isNotBlank() && it != unknownToken() }
        }.filter { it.isNotEmpty() }
        if (sentences.isEmpty()) return 0
        val vocabularyIds = sentences.flatten().map(::tokenId).filter { it != unknownId() }.distinct()
        if (vocabularyIds.size < 2) return sentences.sumOf { it.size }
        if (epochs == 0 || windowSize == 0) return sentences.sumOf { it.size }
        val negativeRandom = Random(7)
        val negativeSamplesPerPositive = 3

        repeat(epochs) {
            for (sentence in sentences) {
                val ids = sentence.map(::tokenId)
                for (index in ids.indices) {
                    val centerId = ids[index]
                    if (centerId == unknownId()) continue
                    val centerVector = embeddings.getOrNull(centerId) ?: continue
                    val leftLimit = (index - windowSize).coerceAtLeast(0)
                    val rightLimit = (index + windowSize + 1).coerceAtMost(ids.size)
                    val positiveContextIds = (leftLimit until rightLimit)
                        .filter { it != index }
                        .map { ids[it] }
                        .filter { it != unknownId() && it != centerId }
                        .toSet()

                    for (neighborId in positiveContextIds) {
                        val neighborVector = embeddings.getOrNull(neighborId) ?: continue
                        updatePair(centerVector, neighborVector, label = 1.0, learningRate = 0.025)

                        repeat(negativeSamplesPerPositive) {
                            val negativeId = drawNegative(vocabularyIds, positiveContextIds, centerId, negativeRandom)
                                ?: return@repeat
                            val negativeVector = embeddings.getOrNull(negativeId) ?: return@repeat
                            updatePair(centerVector, negativeVector, label = 0.0, learningRate = 0.025)
                        }
                    }
                }
            }
            normalizeEmbeddings()
        }
        sentences.sumOf { it.size }
    }

    /** Wählt ein Token, das weder Zentrum noch positives Kontexttoken ist. */
    private fun drawNegative(
        vocabularyIds: List<Int>,
        positiveContextIds: Set<Int>,
        centerId: Int,
        random: Random
    ): Int? {
        repeat(16) {
            val candidate = vocabularyIds[random.nextInt(vocabularyIds.size)]
            if (candidate != centerId && candidate !in positiveContextIds) return candidate
        }
        return vocabularyIds.firstOrNull { it != centerId && it !in positiveContextIds }
    }

    /** Passt beide Vektoren mit einem sigmoidbasierten Skip-Gram-Gradienten an. */
    private fun updatePair(left: DoubleArray, right: DoubleArray, label: Double, learningRate: Double) {
        val probability = sigmoid(dot(left, right))
        val gradient = (label - probability) * learningRate
        for (dim in left.indices) {
            val oldLeft = left[dim]
            val oldRight = right[dim]
            left[dim] = oldLeft + gradient * oldRight
            right[dim] = oldRight + gradient * oldLeft
        }
    }

    /** Sucht die normalisierte Token-ID oder liefert die Unknown-ID zurück. */
    fun tokenId(token: String): Int = synchronized(tokenizerLock) {
        val lowered = TextFold.fold(token)
        tokenToId[lowered] ?: tokenToId[unkToken] ?: 0
    }

    /** Liefert eine tiefe Kopie, damit Aufrufer den internen Trainingszustand nicht verändern können. */
    fun embeddingMatrix(): Array<DoubleArray> = synchronized(tokenizerLock) {
        embeddings.map(DoubleArray::copyOf).toTypedArray()
    }

    /** Initialisiert alle Vektoren reproduzierbar neu, passend zum aktuellen Vokabular. */
    fun resetEmbeddings() = synchronized(tokenizerLock) {
        initializeEmbeddings()
    }

    /** Gibt eine vom Aufrufer unabhängige, nach ID geordnete Momentaufnahme zurück. */
    fun vocabularySnapshot(): List<Pair<String, Int>> = synchronized(tokenizerLock) {
        tokenToId.entries.sortedBy { it.value }.map { it.key to it.value }
    }

    /** Ersetzt den Tokenvektor, füllt kurze Eingaben mit Nullen auf und schneidet lange ab. */
    fun setEmbeddingForToken(token: String, vector: List<Double>) = synchronized(tokenizerLock) {
        val normalizedToken = TextFold.fold(token)
        val id = tokenToId[normalizedToken]
            ?: throw IllegalArgumentException("Token '$token' is not in the vocabulary.")
        require(vector.all(Double::isFinite)) { "Embedding values must be finite." }
        embeddings[id] = vector.toDoubleArray().copyOf(embeddingDim)
    }

    /** Ersetzt den Vektor einer gültigen ID und kopiert die Eingabe auf feste Dimension. */
    fun updateEmbedding(id: Int, vector: DoubleArray) = synchronized(tokenizerLock) {
        require(id in embeddings.indices) { "Embedding id $id is outside the vocabulary." }
        require(vector.all(Double::isFinite)) { "Embedding values must be finite." }
        embeddings[id] = vector.copyOf(embeddingDim)
    }

    /** Anzahl der Vokabulareinträge einschließlich des Unknown-Tokens. */
    fun vocabSize(): Int = synchronized(tokenizerLock) { tokenToId.size }

    /** Ordnet eindeutige Tokens nach Häufigkeit und Merge-Rang und weist stabile IDs zu. */
    private fun buildVocabulary(wordSequences: List<List<String>>) {
        tokenToId.clear()
        tokenToId[unkToken] = 0

        val frequency = wordSequences
            .flatMap { it }
            .map { it.removeSuffix(endOfWord) }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
        val mergeRank = mutableMapOf<String, Int>()
        mergeRules.forEachIndexed { index, pair ->
            mergeRank.putIfAbsent(pair.first, index)
            mergeRank.putIfAbsent(pair.second, index)
        }

        wordSequences
            .flatMap { it }
            .map { it.removeSuffix(endOfWord) }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(
            compareByDescending<String> { frequency[it] ?: 0 }
                .thenBy { mergeRank[it] ?: Int.MAX_VALUE }
                .thenBy { it }
        ).forEachIndexed { index, token ->
            if (token != unkToken) {
                tokenToId[token] = index + 1
            }
        }
        initializeEmbeddings()
    }

    /** Speichert Merge-Regeln, Vokabular und bereinigte Embeddings als JSON-Zustand. */
    fun save(path: Path) = synchronized(tokenizerLock) {
        val snapshot = TokenizerStateDto(
            embeddingDim = embeddingDim,
            mergeRules = mergeRules.map { MergeRuleDto(it.first, it.second) },
            vocabulary = tokenToId.entries.sortedBy { it.value }.map { (token, id) ->
                EmbeddingRowDto(token, id, JsonCodec.sanitizeVector(embeddings.getOrNull(id)?.toList() ?: emptyList()))
            }
        )
        FileIo.writeAtomically(path, JsonCodec.json.encodeToString(snapshot))
    }

    /** Lädt den vollständigen Zustand oder initialisiert bei fehlender Datei leere Embeddings. */
    fun load(path: Path): Unit = synchronized(tokenizerLock) {
        if (!Files.exists(path)) {
            mergeRules.clear()
            tokenToId.clear()
            tokenToId[unkToken] = 0
            initializeEmbeddings()
            return
        }

        val snapshot = JsonCodec.json.decodeFromString(TokenizerStateDto.serializer(), Files.readString(path))
        require(snapshot.version == 1) {
            "Unsupported tokenizer state version ${snapshot.version}."
        }
        require(snapshot.embeddingDim == embeddingDim) {
            "Tokenizer embedding dimension ${snapshot.embeddingDim} does not match $embeddingDim."
        }
        val normalizedVocabulary = snapshot.vocabulary.map { entry ->
            entry.copy(token = TextFold.fold(entry.token))
        }
        require(normalizedVocabulary.map { it.id }.sorted() == normalizedVocabulary.indices.toList()) {
            "Tokenizer vocabulary IDs must be unique, contiguous, and start at zero."
        }
        require(normalizedVocabulary.map { it.token }.distinct().size == normalizedVocabulary.size) {
            "Tokenizer vocabulary contains duplicate normalized tokens."
        }
        require(normalizedVocabulary.any { it.token == unkToken && it.id == 0 }) {
            "Tokenizer vocabulary must contain <unk> at ID 0."
        }
        require(normalizedVocabulary.all { entry ->
            entry.embedding.size == embeddingDim && entry.embedding.all(Double::isFinite)
        }) {
            "Tokenizer embeddings must have dimension $embeddingDim and finite values."
        }

        val newVocabulary = linkedMapOf(unkToken to 0)
        normalizedVocabulary.forEach { entry -> newVocabulary[entry.token] = entry.id }
        val newEmbeddings = Array(normalizedVocabulary.size.coerceAtLeast(1)) { index ->
            normalizedVocabulary.firstOrNull { it.id == index }?.embedding?.toDoubleArray()
                ?: randomVector(index)
        }

        val normalizedMergeRules = snapshot.mergeRules.map {
            TextFold.fold(it.left) to TextFold.fold(it.right)
        }
        require(normalizedMergeRules.all { (left, right) ->
            left.isNotBlank() && right.isNotBlank() && left != unkToken && right != unkToken
        }) {
            "Tokenizer merge rules must contain non-empty symbols other than <unk>."
        }

        mergeRules.clear()
        mergeRules.addAll(normalizedMergeRules)
        tokenToId.clear()
        tokenToId.putAll(newVocabulary)
        embeddings = newEmbeddings
    }

    /** Erstellt einen reproduzierbaren Vektor je ID, damit frische Zustände testbar bleiben. */
    private fun initializeEmbeddings() {
        embeddings = Array(tokenToId.size.coerceAtLeast(1)) { index ->
            randomVector(index)
        }
    }

    private fun randomVector(index: Int): DoubleArray {
        val vectorRandom = Random(7 + index)
        return DoubleArray(embeddingDim) { dim ->
            val noise = (vectorRandom.nextDouble() - 0.5) * 0.1
            if (index == 0) noise * 0.5 else noise
        }
    }

    private fun getEmbedding(id: Int): DoubleArray {
        return embeddings.getOrNull(id) ?: embeddings.getOrNull(0) ?: DoubleArray(embeddingDim)
    }

    /** Normiert alle Zeilen nach jeder Trainingsrunde, damit Cosine-Vergleiche sinnvoll bleiben. */
    private fun normalizeEmbeddings() {
        for (vector in embeddings) {
            normalizeInPlace(vector)
        }
    }

    /** Teilt einen Vektor durch seine euklidische Norm; Nullvektoren bleiben unverändert. */
    private fun normalizeInPlace(vector: DoubleArray) {
        val norm = kotlin.math.sqrt(vector.sumOf { it * it })
        if (norm > 0.0) {
            for (dim in vector.indices) {
                vector[dim] /= norm
            }
        }
    }

    /** Berechnet das Skalarprodukt gleich langer Vektoren. */
    private fun dot(left: DoubleArray, right: DoubleArray): Double {
        return left.indices.sumOf { left[it] * right[it] }
    }

    /** Komprimiert einen Logit in den Wertebereich 0 bis 1. */
    private fun sigmoid(value: Double): Double {
        return 1.0 / (1.0 + kotlin.math.exp(-value))
    }

    /** Wendet die Merge-Regeln in ihrer Lernreihenfolge auf ein Wort an. */
    private fun encodeWord(word: String): List<String> {
        val lowered = word.lowercase()
        var symbols = symbolsFor(lowered)
        for (mergeRule in mergeRules) {
            if (symbols.size < 2) break
            val firstMatch = (0 until symbols.lastIndex).firstOrNull { index ->
                symbols[index] == mergeRule.first && symbols[index + 1] == mergeRule.second
            } ?: continue
            val mergedSymbols = ArrayList<String>(symbols.size)
            mergedSymbols.addAll(symbols.subList(0, firstMatch))
            var i = firstMatch
            while (i < symbols.size) {
                if (i < symbols.lastIndex && symbols[i] == mergeRule.first && symbols[i + 1] == mergeRule.second) {
                    mergedSymbols.add(symbols[i] + symbols[i + 1])
                    i += 2
                } else {
                    mergedSymbols.add(symbols[i])
                    i += 1
                }
            }
            symbols = mergedSymbols
        }
        val merged = symbols.map { it.removeSuffix(endOfWord) }.filter { it.isNotEmpty() }
        return if (merged.isEmpty()) listOf(unkToken) else merged
    }

    private fun tokenizeTokens(text: String): List<String> =
        splitText(TextFold.fold(text)).flatMap(::encodeWord)

    /** Keeps supplementary Unicode characters intact instead of splitting their UTF-16 surrogate pairs. */
    private fun symbolsFor(word: String): MutableList<String> {
        val symbols = mutableListOf<String>()
        var offset = 0
        while (offset < word.length) {
            val codePoint = word.codePointAt(offset)
            symbols.add(String(Character.toChars(codePoint)))
            offset += Character.charCount(codePoint)
        }
        symbols.add(endOfWord)
        return symbols
    }

    /** Zählt alle benachbarten Symbole und dient dem BPE-Schritt zur Wahl des häufigsten Paars. */
    private fun countPairs(wordSequences: List<List<String>>): MutableMap<Pair<String, String>, Int> {
        val counts = mutableMapOf<Pair<String, String>, Int>()
        for (symbols in wordSequences) {
            for (i in 0 until symbols.lastIndex) {
                val pair = symbols[i] to symbols[i + 1]
                counts[pair] = counts.getOrDefault(pair, 0) + 1
            }
        }
        return counts
    }

    /** Führt ein ausgewähltes Paar überall zusammen und hält Nachbarzählungen inkrementell aktuell. */
    private fun mergePairInSequences(wordSequences: List<MutableList<String>>, pair: Pair<String, String>, pairCounts: MutableMap<Pair<String, String>, Int>) {
        for (symbols in wordSequences) {
            var i = 0
            while (i < symbols.lastIndex) {
                if (symbols[i] == pair.first && symbols[i + 1] == pair.second) {
                    updatePairCountsForMerge(symbols, i, pair, pairCounts)
                    i += 1
                } else {
                    i += 1
                }
            }
        }
    }

    /** Aktualisiert nur die Zählungen um die veränderte Stelle, statt den ganzen Korpus neu zu zählen. */
    private fun updatePairCountsForMerge(symbols: MutableList<String>, index: Int, pair: Pair<String, String>, pairCounts: MutableMap<Pair<String, String>, Int>) {
        val left = if (index > 0) symbols[index - 1] else null
        val right = if (index + 2 < symbols.size) symbols[index + 2] else null
        val merged = pair.first + pair.second

        fun decrement(pairToDecrement: Pair<String, String>) {
            val currentCount = checkNotNull(pairCounts[pairToDecrement]) {
                "BPE pair count is missing for $pairToDecrement."
            }
            check(currentCount > 0) { "BPE pair count must remain positive for $pairToDecrement." }
            if (currentCount == 1) {
                pairCounts.remove(pairToDecrement)
            } else {
                pairCounts[pairToDecrement] = currentCount - 1
            }
        }

        fun increment(pairToIncrement: Pair<String, String>) {
            pairCounts[pairToIncrement] = pairCounts.getOrDefault(pairToIncrement, 0) + 1
        }

        if (left != null) decrement(left to pair.first)
        decrement(pair.first to pair.second)
        if (right != null) decrement(pair.second to right)

        symbols[index] = merged
        symbols.removeAt(index + 1)

        if (left != null) increment(left to merged)
        if (right != null) increment(merged to right)
    }

    /** Trennt Buchstabenfolgen, Ziffernfolgen und Satzzeichen, lässt Whitespace als Trenner aus. */
    private fun splitText(text: String): List<String> {
        return Regex("\\p{L}+|\\p{N}+|[^\\s\\p{L}\\p{N}]+" ).findAll(text)
            .map { it.value }
            .toList()
    }
}
