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
    private val defaultCorpus: List<String>,
    private val defaultTokenizerCorpus: List<String> = defaultCorpus,
    private val defaultEmbeddingCorpus: List<String> = defaultTokenizerCorpus
) {
    private val tokenizerFile = config.tokenizerFile()
    private val corpusFile = config.tokenizerCorpusFile()
    private val fingerprints = TokenizerFingerprints(config, tokenizer)
    private var activeCorpus = defaultCorpus

    fun initialize() {
        val tokenizerLoaded = loadOrTrainTokenizer()
        val embeddingCorpus = if (Files.exists(corpusFile)) {
            trainingCorpus()
        } else {
            defaultEmbeddingCorpus.map(TextFold::fold)
        }
        if (!tokenizerLoaded || fingerprints.embeddingStateNeedsRetraining(embeddingCorpus)) {
            tokenizer.resetEmbeddings()
            tokenizer.trainSkipGramEmbeddings(
                embeddingCorpus,
                epochs = TokenizerTrainingDefaults.SKIPGRAM_EPOCHS,
                windowSize = TokenizerTrainingDefaults.SKIPGRAM_WINDOW_SIZE
            )
            tokenizer.save(tokenizerFile)
            saveEmbeddingFingerprint(
                embeddingCorpus,
                TokenizerTrainingDefaults.SKIPGRAM_EPOCHS,
                TokenizerTrainingDefaults.SKIPGRAM_WINDOW_SIZE
            )
        }
    }

    fun trainingCorpus(): List<String> = activeCorpus.ifEmpty { defaultCorpus }

    fun saveEmbeddingFingerprint(corpus: List<String>, epochs: Int, windowSize: Int) =
        fingerprints.saveEmbeddingFingerprint(corpus, epochs, windowSize)

    private fun loadOrTrainTokenizer(): Boolean {
        val usingCustomCorpus = Files.exists(corpusFile)
        val corpus = if (usingCustomCorpus) {
            loadTrainingCorpus(corpusFile)
        } else {
            defaultCorpus.map(TextFold::fold)
        }
        activeCorpus = corpus
        val tokenizerCorpus = if (usingCustomCorpus) {
            corpus
        } else {
            defaultTokenizerCorpus.map(TextFold::fold)
        }

        val fingerprint = fingerprints.tokenizerFingerprint(tokenizerCorpus)
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

        tokenizer.train(tokenizerCorpus, TokenizerTrainingDefaults.MAX_MERGE_RULES)
        tokenizer.save(tokenizerFile)
        FileIo.writeAtomically(config.tokenizerFingerprintFile(), fingerprints.tokenizerFingerprint(tokenizerCorpus))
        return false
    }

    private fun loadTrainingCorpus(path: Path): List<String> =
        Files.readAllLines(path)
            .map { TextFold.fold(it.trim()) }
            .filter { it.isNotBlank() }

}
