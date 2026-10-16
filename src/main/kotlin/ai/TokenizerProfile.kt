package ai

import java.nio.file.Files
import java.nio.file.Path

internal object TokenizerTrainingDefaults {
    const val SKIPGRAM_EPOCHS = 12
    const val SKIPGRAM_WINDOW_SIZE = 2
    const val MAX_MERGE_RULES = 5000
}

/** Owns persisted tokenizer state and the corpus used to build it. */
internal class TokenizerProfile(
    private val config: AssistantConfig,
    private val tokenizer: BpeTokenizer,
    private val defaultCorpus: List<String>
) {
    private val tokenizerFile = config.tokenizerFile()
    private val corpusFile = config.tokenizerCorpusFile()
    private val fingerprints = TokenizerFingerprints(config, tokenizer)
    private var activeCorpus = defaultCorpus

    fun initialize() {
        val tokenizerLoaded = loadOrTrainTokenizer()
        val corpus = trainingCorpus()
        if (!tokenizerLoaded || fingerprints.embeddingStateNeedsRetraining(corpus)) {
            tokenizer.resetEmbeddings()
            tokenizer.trainSkipGramEmbeddings(
                corpus,
                epochs = TokenizerTrainingDefaults.SKIPGRAM_EPOCHS,
                windowSize = TokenizerTrainingDefaults.SKIPGRAM_WINDOW_SIZE
            )
            tokenizer.save(tokenizerFile)
            saveEmbeddingFingerprint(
                corpus,
                TokenizerTrainingDefaults.SKIPGRAM_EPOCHS,
                TokenizerTrainingDefaults.SKIPGRAM_WINDOW_SIZE
            )
        }
    }

    fun trainingCorpus(): List<String> = activeCorpus.ifEmpty { defaultCorpus }

    fun saveEmbeddingFingerprint(corpus: List<String>, epochs: Int, windowSize: Int) =
        fingerprints.saveEmbeddingFingerprint(corpus, epochs, windowSize)

    private fun loadOrTrainTokenizer(): Boolean {
        val corpus = if (Files.exists(corpusFile)) {
            loadTrainingCorpus(corpusFile)
        } else {
            defaultCorpus.map(TextFold::fold)
        }
        activeCorpus = corpus

        val fingerprint = fingerprints.tokenizerFingerprint(corpus)
        val tokenizerIsCurrent = Files.exists(tokenizerFile) &&
            Files.exists(config.tokenizerFingerprintFile()) &&
            Files.readString(config.tokenizerFingerprintFile()).trim() == fingerprint
        if (tokenizerIsCurrent) {
            try {
                tokenizer.load(tokenizerFile)
                return true
            } catch (exception: kotlinx.serialization.SerializationException) {
                FileIo.logWarning(
                    "Tokenizer-Datei konnte nicht geladen werden, Retraining wird gestartet: $tokenizerFile",
                    exception
                )
            } catch (exception: IllegalArgumentException) {
                FileIo.logWarning(
                    "Tokenizer-Datei konnte nicht geladen werden, Retraining wird gestartet: $tokenizerFile",
                    exception
                )
            }
        }

        tokenizer.train(corpus, TokenizerTrainingDefaults.MAX_MERGE_RULES)
        tokenizer.save(tokenizerFile)
        FileIo.writeAtomically(config.tokenizerFingerprintFile(), fingerprints.tokenizerFingerprint(corpus))
        return false
    }

    private fun loadTrainingCorpus(path: Path): List<String> =
        Files.readAllLines(path)
            .map { TextFold.fold(it.trim()) }
            .filter { it.isNotBlank() }

}
