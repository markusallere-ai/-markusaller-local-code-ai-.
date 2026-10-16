package ai

import java.nio.file.Files

/** Calculates and validates fingerprints for persisted tokenizer and embedding data. */
internal class TokenizerFingerprints(
    private val config: AssistantConfig,
    private val tokenizer: BpeTokenizer
) {
    private data class EmbeddingState(
        val epochs: Int,
        val windowSize: Int,
        val fingerprint: String
    )

    fun tokenizerFingerprint(corpus: List<String>): String =
        sha256(
            listOf(
                "tokenizer-algorithm-v=2",
                "max-merge-rules=${TokenizerTrainingDefaults.MAX_MERGE_RULES}",
                normalizedCorpus(corpus)
            )
        )

    fun embeddingStateNeedsRetraining(corpus: List<String>): Boolean {
        val path = config.embeddingFingerprintFile()
        return !Files.exists(path) ||
            readEmbeddingState(Files.readString(path).trim())?.let { saved ->
                saved.fingerprint == embeddingFingerprint(corpus, saved.epochs, saved.windowSize)
            } != true
    }

    fun saveEmbeddingFingerprint(corpus: List<String>, epochs: Int, windowSize: Int) {
        val fingerprint = embeddingFingerprint(corpus, epochs, windowSize)
        FileIo.writeAtomically(
            config.embeddingFingerprintFile(),
            "$FINGERPRINT_FORMAT|$epochs|$windowSize|$fingerprint"
        )
    }

    private fun embeddingFingerprint(corpus: List<String>, epochs: Int, windowSize: Int): String =
        sha256(
            listOf(
                "skipgram-algorithm-v=3",
                "skipgram-seed=7",
                "skipgram-negative-samples=3",
                "embedding-dimension=${tokenizer.embeddingDimension()}",
                "epochs=$epochs",
                "window-size=$windowSize",
                tokenizerFingerprint(corpus),
                normalizedCorpus(corpus)
            )
        )

    private fun normalizedCorpus(corpus: List<String>): String =
        corpus.joinToString("\n") { TextFold.fold(it.trim()) }

    private fun sha256(parts: List<String>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("\n---\n").toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun readEmbeddingState(value: String): EmbeddingState? {
        val parts = value.split('|')
        val state = when {
            parts.size == FINGERPRINT_PART_COUNT && parts[0] == FINGERPRINT_FORMAT ->
                parseVersionedState(parts)
            value.matches(EMBEDDING_HASH_PATTERN) -> EmbeddingState(
                TokenizerTrainingDefaults.SKIPGRAM_EPOCHS,
                TokenizerTrainingDefaults.SKIPGRAM_WINDOW_SIZE,
                value
            )
            else -> null
        }
        if (state == null) {
            FileIo.logWarning("Embedding-Fingerprint ist ungültig und wird neu aufgebaut.")
        }
        return state
    }

    private fun parseVersionedState(parts: List<String>): EmbeddingState? {
        val parameters = listOf(parts[1], parts[2])
            .map { it.toIntOrNull()?.takeIf { value -> value >= 0 } }
        val epochs = parameters.getOrNull(0)
        val windowSize = parameters.getOrNull(1)
        return if (epochs != null && windowSize != null) {
            EmbeddingState(epochs, windowSize, parts[FINGERPRINT_VALUE_INDEX])
        } else {
            null
        }
    }

    private companion object {
        const val DEFAULT_SKIPGRAM_EPOCHS = 12
        const val DEFAULT_SKIPGRAM_WINDOW_SIZE = 2
        const val FINGERPRINT_FORMAT = "v1"
        const val FINGERPRINT_PART_COUNT = 4
        const val FINGERPRINT_VALUE_INDEX = 3
        const val HASH_LENGTH = 64
        val EMBEDDING_HASH_PATTERN = Regex("[0-9a-f]{$HASH_LENGTH}")
    }
}
