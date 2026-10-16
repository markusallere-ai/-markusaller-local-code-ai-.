package ai

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlinx.serialization.encodeToString

/**
 * Public facade for prompt routing, knowledge access, tokenization, and local evaluation.
 */
class CodeAssistant(private val config: AssistantConfig = AssistantConfig()) {
    private val conversationCoordinator by lazy {
        ConversationCoordinator(
            classify = ::classifyIntentWithDetails,
            resolve = ::resolvePrompt,
            storeKnowledge = ::storeKnowledge
        )
    }

    /** Beantwortet einen Prompt ohne Rückfrage-Schleife, aber über denselben Resolver wie die GUI. */
    fun handlePrompt(prompt: String): String {
        ensureInitialized()
        return conversationCoordinator.handlePrompt(prompt)
    }

    /** Verarbeitet einen Gesprächsschritt und verlangt Bestätigung, bevor eine Erklärung gespeichert wird. */
    fun handleConversationTurn(prompt: String): ConversationTurnResult {
        ensureInitialized()
        return conversationCoordinator.handleConversationTurn(prompt)
    }

    /** Priorisiert exaktes Wissen, danach zulässige Fuzzy-Treffer und zuletzt den Generator. */
    private fun resolvePrompt(prompt: String): ResolvedPrompt {
        val exactAnswer = knowledgeStore.findExact(prompt)?.answer
        return when {
            exactAnswer != null -> ResolvedPrompt(exactAnswer, Intent.Unknown, usedKnowledge = true)
            else -> {
                val prediction = classifyIntentWithDetails(prompt)
                val specializedExample = codeGenerator.generateSpecializedExample(prompt, prediction.intent)
                val fuzzyAnswer = if (
                    specializedExample == null &&
                    prediction.intent != Intent.Smalltalk &&
                    !ConversationRules.isExplicitCodeRequest(prediction)
                ) {
                    findFuzzyKnownAnswer(prompt)
                } else {
                    null
                }
                val response = specializedExample ?: fuzzyAnswer ?: codeGenerator.generate(prompt, prediction.intent)
                val effectiveIntent = if (
                    specializedExample != null && prediction.intent == Intent.Unknown
                ) {
                    Intent.GenerateFunction
                } else {
                    prediction.intent
                }
                ResolvedPrompt(response, effectiveIntent, fuzzyAnswer != null)
            }
        }
    }

    /** Verwirft beide möglichen offenen Gesprächszustände atomar. */
    fun cancelPendingClarification() = conversationCoordinator.cancelPendingClarification()

    @Deprecated(
        message = "This stores knowledge without training model weights; use storeKnowledge instead.",
        replaceWith = ReplaceWith("storeKnowledge(prompt, answer, tags)")
    )
    fun learn(prompt: String, answer: String, tags: List<String> = emptyList()) {
        storeKnowledge(prompt, answer, tags)
    }

    /** Speichert ein wiederverwendbares Beispiel; dabei werden keine Modellgewichte trainiert. */
    fun storeKnowledge(prompt: String, answer: String, tags: List<String> = emptyList()) {
        ensureInitialized()
        require(prompt.isNotBlank()) { "Der Lernprompt darf nicht leer sein." }
        require(answer.isNotBlank()) { "Die gelernte Antwort darf nicht leer sein." }

        knowledgeStore.learn(prompt, answer, tags)
    }

    /** Speichert eine korrigierte Antwort unter dem ursprünglichen Prompt als exakten Treffer. */
    fun learnFromCorrection(prompt: String, correctedAnswer: String, tags: List<String> = listOf("correction")) {
        storeKnowledge(prompt, correctedAnswer, tags)
    }

    /** Liefert die ähnlichsten Wissenseinträge zur Anzeige oder Diagnose. */
    fun recallKnowledge(prompt: String, limit: Int = 3): List<KnowledgeMatch> {
        ensureInitialized()
        return knowledgeStore.recall(prompt, limit)
    }

    /** Zählt die dauerhaft gespeicherten Prompt-Antwort-Beispiele. */
    fun knowledgeEntryCount(): Int {
        ensureInitialized()
        return knowledgeStore.count()
    }

    /** Gibt gespeicherte Einträge zur Verwaltung zurück. */
    fun listKnowledge(): List<KnowledgeMatch> {
        ensureInitialized()
        return knowledgeStore.list()
    }

    /** Löscht den Eintrag mit dem angegebenen Prompt exakt. */
    fun deleteKnowledge(prompt: String): Boolean {
        ensureInitialized()
        return knowledgeStore.delete(prompt)
    }

    /** Akzeptiert unscharfes Wissen nur bei ausreichender Ähnlichkeit, Überlappung und Eindeutigkeit. */
    private fun findFuzzyKnownAnswer(prompt: String): String? {
        val matches = knowledgeStore.recall(prompt, 3)
        val best = matches.firstOrNull() ?: return null
        val second = matches.getOrNull(1)
        val matchIsClear = best.similarity >= config.knowledgeSingleMatchSimilarityThreshold &&
            (second == null || best.similarity - second.similarity >= config.knowledgeMarginThreshold)

        val tokenOverlap = lexicalTokenOverlap(prompt, best.prompt)
        return best.answer.takeIf {
            best.similarity >= config.knowledgeSimilarityThreshold &&
                    tokenOverlap >= config.knowledgeMinimumTokenOverlap &&
                    matchIsClear
        }
    }

    /** Speichert ein Intent-Label getrennt vom abrufbaren Prompt-Antwort-Wissen. */
    fun addTrainingExample(example: TrainingExample) {
        ensureInitialized()
        trainingExampleStore.add(example)
        supervisedIntentClassifier = null
    }

    /** Liefert die gespeicherten gelabelten Beispiele. */
    fun trainingExamples(): List<TrainingExample> {
        ensureInitialized()
        return trainingExampleStore.all()
    }

    /** Zählt die gelabelten Intent-Trainingsbeispiele. */
    fun trainingExampleCount(): Int {
        ensureInitialized()
        return trainingExampleStore.count()
    }

    /** Führt die feste lokale Evaluationssuite aus und gibt Rohmesswerte zurück. */
    fun evaluate(): EvaluationReport {
        ensureInitialized()
        return evaluationComponent.evaluate()
    }

    /** Führt dieselbe Evaluation mit benutzerdefinierten Intent- und Embedding-Fällen aus. */
    fun evaluate(
        intentCases: List<IntentEvaluationCase>,
        embeddingPairs: List<EmbeddingEvaluationPair>,
        feedforwardCorpus: List<String> = EvaluationComponent.DEFAULT_FEEDFORWARD_CORPUS
    ): EvaluationReport {
        ensureInitialized()
        return evaluationComponent.evaluate(intentCases, embeddingPairs, feedforwardCorpus)
    }

    /** Kompatibler Alias für [evaluate]. */
    fun runEvaluation(): EvaluationReport = evaluate()

    /** Formatiert Messwerte und Einzelfall-Diagnosen als lesbaren Bericht. */
    fun formatEvaluation(report: EvaluationReport = evaluate()): String = buildString {
        appendLine("Self-Evaluation")
        appendLine("Code-Quelle: ${CodeAssistant::class.java.protectionDomain?.codeSource?.location?.toExternalForm() ?: "unbekannt"}")
        appendLine("Tokenizer-Fingerprint: ${readFingerprint(config.tokenizerFingerprintFile())}")
        appendLine("Embedding-Fingerprint: ${readFingerprint(config.embeddingFingerprintFile())}")
        appendLine("Intent-Trainingsgenauigkeit: ${"%.2f".format(report.trainingIntentAccuracy)}")
        appendLine(
            "Leave-one-out-Selective-Accuracy: ${"%.2f".format(report.leaveOneOutIntentAccuracy)} " +
                "(Coverage ${"%.2f".format(report.leaveOneOutCoverage)})"
        )
        appendLine("Validierungs-Accuracy: ${"%.2f".format(report.validationIntentAccuracy)}")
        appendLine("Trainingsfälle:")
        report.intentResults.forEach { appendIntentResult(it) }
        appendLine("Leave-one-out-Fälle:")
        report.leaveOneOutIntentResults.forEach { appendIntentResult(it) }
        appendLine("Validierungs-/Held-out-Fälle:")
        report.generalizationIntentResults.forEach { appendIntentResult(it) }
        appendLine("Validierungs-Confusion-Matrix: ${report.validationConfusionMatrix}")
        appendLine(
            "Out-of-Domain-False-Acceptance-Rate: " +
                "${"%.2f".format(report.outOfDomainFalseAcceptanceRate)} " +
                "(${report.outOfDomainIntentResults.count { it.actual != Intent.Unknown }}/" +
                "${report.outOfDomainIntentResults.size} als Code-Intent akzeptiert)"
        )
        appendLine("Out-of-Domain-Fälle:")
        report.outOfDomainIntentResults.forEach { appendIntentResult(it) }
        appendLine(
            "Embedding-Trennung: ${"%.4f".format(report.embeddingSeparation)} " +
                "(verwandt ${report.validRelatedEmbeddingCount}/${report.embeddingResults.count { it.related }}, " +
                "unverwandt ${report.validUnrelatedEmbeddingCount}/${report.embeddingResults.count { !it.related }})"
        )
        appendLine("Embedding-Wortpaare:")
        report.embeddingResults.forEach { result ->
            appendLine(
                "${result.left} <-> ${result.right} | " +
                    "erwartet=${if (result.related) "verwandt" else "unverwandt"} | " +
                    "cosine=${if (result.similarity.isFinite()) "%.4f".format(result.similarity) else "OOV"}"
            )
        }
        appendLine("Feedforward-Trainingsloss Anfang: ${report.initialLoss ?: "n/a"}")
        appendLine("Feedforward-Trainingsloss Ende: ${report.finalLoss ?: "n/a"}")
        appendLine("Trainingsloss-Verbesserung Ende: ${report.finalLossImprovement ?: "n/a"}")
        appendLine("Beste beobachtete Trainingsloss-Verbesserung: ${report.bestLossImprovement ?: "n/a"}")
    }

    /** Liest einen Fingerprint, oder macht sein Fehlen im Evaluationsbericht sichtbar. */
    private fun readFingerprint(path: Path): String =
        if (Files.exists(path)) {
            val value = Files.readString(path).trim()
            if (path == config.embeddingFingerprintFile()) value.substringAfterLast('|') else value
        } else {
            "fehlt"
        }

    /** Formatiert eine Klassifikation samt Kennzahlen für die zeilenweise Evaluationsausgabe. */
    private fun StringBuilder.appendIntentResult(result: IntentEvaluationResult) {
        val prediction = result.prediction
        appendLine(
            "Prompt=\"${result.prompt}\" | erwartet=${result.expected} | erkannt=${prediction.intent} | " +
                "confidence=${"%.3f".format(prediction.confidence)} | " +
                "similarity=${prediction.similarity?.let { "%.3f".format(it) } ?: "n/a"} | " +
                "margin=${prediction.margin?.let { "%.3f".format(it) } ?: "n/a"} | " +
                "topKandidat=${prediction.topCandidate ?: "n/a"} | methode=${prediction.method}"
        )
    }

    /** Liefert den konfigurierten Zielpfad des Diagnose-Snapshots. */
    fun brainStateFilePath(): Path = config.brainStateFile()

    /** Trainiert das experimentelle Feedforward-Modell; normale Chat-Antworten verwenden es nicht. */
    fun trainFeedforwardLanguageModel(
        corpus: List<String>,
        contextSize: Int = 2,
        hiddenSize: Int = 32,
        epochs: Int = 200,
        learningRate: Double = 0.05
    ): FeedforwardLanguageModel {
        ensureInitialized()
        return languageModelTrainer.train(corpus, contextSize, hiddenSize, epochs, learningRate)
    }

    /**
     * Trainiert das lokale Feedforward-Modell und erzeugt eine deterministische
     * Tokenfortsetzung. Der experimentelle Pfad wird nicht von [handlePrompt] verwendet.
     */
    fun generateWithLanguageModel(
        corpus: List<String>,
        seed: String,
        maxTokens: Int,
        contextSize: Int = 2,
        hiddenSize: Int = 32,
        epochs: Int = 200
    ): String {
        ensureInitialized()
        val model = trainFeedforwardLanguageModel(corpus, contextSize, hiddenSize, epochs)
        return languageModelTrainer.generate(model, seed, maxTokens)
    }

    /** Speichert Modellarchitektur, Vokabular und Gewichte am angegebenen Pfad. */
    fun saveFeedforwardLanguageModel(
        model: FeedforwardLanguageModel,
        path: Path = config.feedforwardModelFile()
    ) = languageModelTrainer.save(model, path)

    /** Lädt Modellparameter; bei fehlender oder ungültiger Datei wird null zurückgegeben. */
    fun loadFeedforwardLanguageModel(
        path: Path = config.feedforwardModelFile()
    ): FeedforwardLanguageModel? = languageModelTrainer.load(path)

    /** Erzeugt den Anfragevektor durch Tokenisierung und Mittelung der bekannten Embeddings. */
    private fun queryEmbedding(text: String): List<Double> {
        val tokens = tokenizer.tokenize(text).filter { it.isNotBlank() }
        return averageEmbedding(tokens)
    }

    /** Berechnet den Jaccard-Anteil gemeinsamer Wörter als Zusatzfilter für Fuzzy-Treffer. */
    private fun lexicalTokenOverlap(left: String, right: String): Double {
        val leftTokens = WORD_PATTERN.findAll(TextFold.fold(left)).map { it.value }.toSet()
        val rightTokens = WORD_PATTERN.findAll(TextFold.fold(right)).map { it.value }.toSet()
        val union = leftTokens union rightTokens
        return if (union.isEmpty()) 0.0 else (leftTokens intersect rightTokens).size.toDouble() / union.size
    }


    /** Klassifiziert einen Prompt und gibt nur das Intent-Label zurück. */
    fun classifyIntent(prompt: String): Intent = classifyIntentWithDetails(prompt).intent

    /** Klassifiziert einen Prompt samt Herkunft und Konfidenzdiagnostik. */
    fun classifyIntentWithDetails(prompt: String): IntentPrediction =
        intentClassifier.classifyWithDetails(prompt)

    /** Initialisiert bei Bedarf den Klassifikator und wendet konfigurierte Annahmeschwellen an. */
    private fun classifyIntentBySupervisedModel(prompt: String): IntentPrediction? {
        if (!initialized) ensureInitialized()
        return supervisedClassifier().predict(
            prompt,
            config.intentSimilarityThreshold,
            config.intentMarginThreshold
        )
    }

    /** Mittelt nichtleere Tokenvektoren; ohne nutzbare Token entsteht ein Nullvektor. */
    private fun averageEmbedding(tokens: List<String>): List<Double> {
        val dim = tokenizer.embeddingDimension()
        val vectors = tokens.mapNotNull { token ->
            val tokenId = tokenizer.tokenId(token)
            val vector = tokenizer.embeddingForId(tokenId)
            vector.takeIf { it.isNotEmpty() && it.any { value -> value != 0.0 } }
        }
        if (vectors.isEmpty()) return List(dim) { 0.0 }
        return (0 until dim).map { dimIndex ->
            vectors.sumOf { it.getOrElse(dimIndex) { 0.0 } } / vectors.size
        }
    }

    /** Berechnet Cosine-Similarity und meldet inkompatible Vektordimensionen im Log. */
    private fun cosineSimilarityVector(left: List<Double>, right: List<Double>): Double {
        if (left.size != right.size) {
            FileIo.logWarning("Embedding-Dimensionen stimmen nicht überein: ${left.size} vs ${right.size}")
            return 0.0
        }
        val dot = left.indices.sumOf { left[it] * right[it] }
        val leftNorm = kotlin.math.sqrt(left.sumOf { it * it })
        val rightNorm = kotlin.math.sqrt(right.sumOf { it * it })
        return if (leftNorm == 0.0 || rightNorm == 0.0) 0.0 else dot / (leftNorm * rightNorm)
    }

    private val tokenizer = BpeTokenizer()
    private val tokenizerProfile = TokenizerProfile(config, tokenizer, AssistantTrainingCorpus.sentences)
    @Volatile private var initialized = false
    private val initLock = Any()
    private val knowledgeStore by lazy {
        KnowledgeStore(config, ::queryEmbedding, ::cosineSimilarityVector)
    }
    private val trainingExampleStore by lazy { TrainingExampleStore(config.trainingExamplesFile()) }
    @Volatile private var supervisedIntentClassifier: SupervisedIntentClassifier? = null
    /** Erstellt den Klassifikator einmalig aus festen und gespeicherten gelabelten Beispielen. */
    private fun supervisedClassifier(): SupervisedIntentClassifier {
        supervisedIntentClassifier?.let { return it }
        synchronized(initLock) {
            supervisedIntentClassifier?.let { return it }
            val storedExamples = trainingExampleStore.all()
                .filter { it.expectedIntent != Intent.Unknown }
                .map { IntentEvaluationCase(it.prompt, it.expectedIntent) }
            return SupervisedIntentClassifier(EvaluationComponent.TRAINING_INTENT_CASES + storedExamples)
                .also { supervisedIntentClassifier = it }
        }
    }
    private val intentClassifier by lazy {
        IntentClassifier(::classifyIntentBySupervisedModel)
    }
    private val codeGenerator by lazy {
        CodeGenerator(tokenizer)
    }
    private val languageModelTrainer by lazy {
        LanguageModelTrainer(tokenizer)
    }
    private val evaluationComponent by lazy {
        EvaluationComponent(
            classify = ::classifyIntentWithDetails,
            similarity = { left, right -> cosineSimilarityIfKnown(left, right) ?: Double.NaN },
            train = { corpus -> trainFeedforwardLanguageModel(corpus) },
            leaveOneOut = {
                SupervisedIntentClassifier(EvaluationComponent.TRAINING_INTENT_CASES).leaveOneOut(
                    config.intentSimilarityThreshold,
                    config.intentMarginThreshold
                )
            }
        )
    }

    init {
        if (config.autoInitialize) {
            ensureInitialized()
        }
    }

    /** Lädt oder trainiert Tokenizer und Embeddings anhand ihrer Fingerprints genau einmal. */
    fun ensureInitialized() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            tokenizerProfile.initialize()
            initialized = true
            if (config.writeBrainStateOnInit) {
                writeBrainStateSnapshot(config.brainStateFile())
            }
        }
    }

    /** Liefert Token, IDs und Embeddings positionsgleich für Diagnose und Anzeige. */
    fun tokenizeWithEmbeddings(text: String): TokenEmbeddingResult {
        ensureInitialized()
        val tokens = tokenizer.tokenize(text)
        val ids = tokenizer.encode(text)
        val embeddings = ids.map(tokenizer::embeddingForId)
        return TokenEmbeddingResult(tokens, ids, embeddings)
    }

    /** Liefert exakte Tokenvektoren oder mittelt bekannte Subtoken eines unbekannten Worts. */
    fun embeddingForToken(token: String): List<Double> {
        ensureInitialized()
        val id = tokenizer.tokenId(token)
        if (id != tokenizer.unknownId()) return tokenizer.embeddingForId(id)

        val subTokens = tokenizer.tokenize(token).filter { it != tokenizer.unknownToken() }
        if (subTokens.isEmpty()) return tokenizer.embeddingForId(id)
        return averageEmbedding(subTokens)
    }

    /** Ersetzt einen Vektor im Tokenizer und persistiert den aktualisierten Zustand. */
    fun setEmbeddingForToken(token: String, vector: List<Double>) {
        ensureInitialized()
        tokenizer.setEmbeddingForToken(token, vector)
        tokenizer.save(config.tokenizerFile())
    }

    /** Tokenisiert mehrere Inputs und zählt je Input unbekannte Vokabular-IDs. */
    fun runTokenizerStressTest(inputs: List<String>): List<TokenizerStressCase> {
        ensureInitialized()
        return inputs.map { input ->
            val result = tokenizeWithEmbeddings(input)
            TokenizerStressCase(
                input = input,
                tokens = result.tokens,
                ids = result.ids,
                unknownTokenCount = result.ids.count { it == tokenizer.unknownId() }
            )
        }
    }

    /** Schreibt eine begrenzte Diagnoseansicht von Vokabular, Vektoren und Beispielähnlichkeiten. */
    fun writeBrainStateSnapshot(path: Path): Path {
        ensureInitialized()
        val samples = listOf("funktion" to "methode", "funktion" to "auto", "klasse" to "objekt", "lernen" to "lernen")
        val snapshot = BrainStateSnapshotDto(
            timestamp = LocalDateTime.now().toString(),
            vocabSize = tokenizer.vocabSize(),
            unknownId = tokenizer.unknownId(),
            embeddingShape = listOf(tokenizer.embeddingMatrix().size, tokenizer.embeddingDimension()),
            tokenizerInfo = listOf(
                "byte-pair encoding subword vocabulary",
                "embeddings stored in a lookup matrix",
                "unknown tokens fall back to the <unk> entry"
            ),
            vocabularySample = tokenizer.vocabularySnapshot().take(20).map { (token, id) -> BrainStateTokenDto(id, token) },
            embeddingPreview = tokenizer.embeddingMatrix().take(6).mapIndexed { index, vector ->
                BrainStateEmbeddingPreviewDto(index, JsonCodec.sanitizeVector(vector.take(6).toList()))
            },
            cosineSimilaritySamples = samples.map { (left, right) ->
                BrainStateSimilaritySampleDto(left, right, JsonCodec.sanitizeDouble(cosineSimilarity(left, right)))
            }
        )
        FileIo.writeAtomically(path, JsonCodec.json.encodeToString(snapshot))
        return path
    }

    /** Berechnet die Wortähnlichkeit; bei unbekannten Wörtern wird 0.0 zurückgegeben. */
    fun cosineSimilarity(left: String, right: String): Double {
        ensureInitialized()
        return cosineSimilarityIfKnown(left, right) ?: 0.0
    }

    /** Liefert null statt eines irreführenden Scores, wenn mindestens ein Wort OOV ist. */
    private fun cosineSimilarityIfKnown(left: String, right: String): Double? {
        if (tokenizer.tokenId(left) == tokenizer.unknownId() ||
            tokenizer.tokenId(right) == tokenizer.unknownId()
        ) return null
        return cosineSimilarityVector(embeddingForToken(left), embeddingForToken(right))
    }

    /** Wählt reproduzierbare Bigramm-/Trigramm-Fortsetzungen aus dem Korpus, keine freie Sprache. */
    fun generateTextContinuation(seed: String, maxTokens: Int = 6): String {
        ensureInitialized()
        val corpusSentences = tokenizerProfile.trainingCorpus().map { sentence ->
            WORD_PATTERN.findAll(TextFold.fold(sentence)).map { it.value }.toList()
        }.filter { it.isNotEmpty() }
        val baseSeed = TextFold.fold(seed).trim().ifBlank { "klasse" }
        val prefix = WORD_PATTERN.findAll(baseSeed).map { it.value }.toList().takeLast(2).ifEmpty { listOf(baseSeed) }
        if (corpusSentences.isEmpty()) return prefix.joinToString(" ")

        val bigrams = mutableMapOf<String, MutableMap<String, Int>>()
        val trigrams = mutableMapOf<List<String>, MutableMap<String, Int>>()
        for (sentence in corpusSentences) {
            // N-Gramme werden pro Satz aufgebaut, damit kein Kontext über Satzgrenzen hinweg entsteht.
            for (index in 0 until sentence.size - 1) {
                val left = sentence[index]
                val right = sentence[index + 1]
                val followingWords = bigrams.getOrPut(left) { mutableMapOf() }
                followingWords[right] = (followingWords[right] ?: 0) + 1
            }
            for (index in 0 until sentence.size - 2) {
                val context = sentence.subList(index, index + 2).toList()
                val next = sentence[index + 2]
                val followingWords = trigrams.getOrPut(context) { mutableMapOf() }
                followingWords[next] = (followingWords[next] ?: 0) + 1
            }
        }

        val output = prefix.toMutableList()
        var context = prefix
        repeat(maxTokens.coerceAtLeast(0)) {
            // Ein vorhandenes Trigramm hat Vorrang; erst ohne Treffer wird auf das Bigramm zurückgegriffen.
            val candidates = if (context.size >= 2) {
                trigrams[context]?.takeIf { it.isNotEmpty() } ?: bigrams[context.last()].orEmpty()
            } else {
                bigrams[context.last()].orEmpty()
            }
            val nextWord = candidates.asSequence()
                .map { (word, count) -> word to count }
                .sortedByDescending { it.second }
                .map { it.first }
                .firstOrNull()
                ?: return output.joinToString(" ")

            output.add(nextWord)
            context = output.takeLast(2)
        }
        return output.joinToString(" ")
    }

    /** Trainiert und speichert Skip-Gram-Vektoren samt Fingerprint für spätere Konsistenzprüfungen. */
    fun trainEmbeddingsWithSkipGram(epochs: Int = 3, windowSize: Int = 2): Int {
        require(epochs >= 0) { "epochs darf nicht negativ sein." }
        require(windowSize >= 0) { "windowSize darf nicht negativ sein." }
        ensureInitialized()
        val corpus = tokenizerProfile.trainingCorpus()
        val trainedTokens = tokenizer.trainSkipGramEmbeddings(corpus, epochs, windowSize)
        tokenizer.save(config.tokenizerFile())
        tokenizerProfile.saveEmbeddingFingerprint(corpus, epochs, windowSize)
        return trainedTokens
    }

    companion object {
        private val WORD_PATTERN = Regex("[\\p{L}\\p{N}]+")
    }

}
