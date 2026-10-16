package ai

import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Verifies tokenizer snapshot schema checks and atomic rejection of invalid state. */
class TokenizerStateValidationTest {
    @Test
    fun tokenizerTrainingRejectsNegativeMergeRuleLimit() {
        assertFailsWith<IllegalArgumentException> {
            BpeTokenizer().train(listOf("alpha beta"), maxMergeRules = -1)
        }
    }

    @Test
    fun zeroMergeRuleLimitStillBuildsACharacterVocabulary() {
        val tokenizer = BpeTokenizer()

        tokenizer.train(listOf("alpha beta"), maxMergeRules = 0)

        assertEquals(5, tokenizer.tokenize("alpha").size)
        assertEquals(32, tokenizer.embeddingDimension())
        assertEquals(0, tokenizer.tokenId("not-in-vocabulary"))
    }

    @Test
    fun unsupportedSnapshotVersionIsRejectedWithoutChangingCurrentState() {
        val validRows = validVocabulary()
        assertInvalidSnapshot(TokenizerStateDto(version = 2, vocabulary = validRows))
    }

    @Test
    fun incompatibleEmbeddingDimensionIsRejectedWithoutChangingCurrentState() {
        val validRows = validVocabulary()
        assertInvalidSnapshot(TokenizerStateDto(embeddingDim = 16, vocabulary = validRows))
    }

    @Test
    fun noncontiguousVocabularyIdsAreRejectedWithoutChangingCurrentState() {
        val rows = listOf(
            EmbeddingRowDto("<unk>", 0, vector(0.0)),
            EmbeddingRowDto("token", 2, vector(0.5))
        )
        assertInvalidSnapshot(TokenizerStateDto(vocabulary = rows))
    }

    @Test
    fun snapshotMustContainUnknownTokenAtIdZero() {
        val rows = listOf(EmbeddingRowDto("token", 0, vector(0.5)))
        assertInvalidSnapshot(TokenizerStateDto(vocabulary = rows))
    }

    @Test
    fun duplicateTokensAfterUnicodeNormalizationAreRejected() {
        val rows = listOf(
            EmbeddingRowDto("<unk>", 0, vector(0.0)),
            EmbeddingRowDto("ä", 1, vector(0.5)),
            EmbeddingRowDto("ae", 2, vector(0.25))
        )
        assertInvalidSnapshot(TokenizerStateDto(vocabulary = rows))
    }

    @Test
    fun wrongEmbeddingVectorWidthIsRejectedWithoutChangingCurrentState() {
        val rows = listOf(
            EmbeddingRowDto("<unk>", 0, List(31) { 0.0 })
        )
        assertInvalidSnapshot(TokenizerStateDto(vocabulary = rows))
    }

    @Test
    fun emptyMergeRuleSymbolsAreRejectedWithoutChangingCurrentState() {
        assertInvalidSnapshot(
            TokenizerStateDto(
                mergeRules = listOf(MergeRuleDto("", "token")),
                vocabulary = validVocabulary()
            )
        )
    }

    @Test
    fun nonfiniteJsonEmbeddingValueIsRejectedWithoutChangingCurrentState() {
        val values = listOf("1e999") + List(31) { "0.0" }
        val json = """
            {"version":1,"embeddingDim":32,"mergeRules":[],"vocabulary":[
            {"token":"<unk>","id":0,"embedding":[${values.joinToString(",")}]}]}
        """.trimIndent()
        val tokenizer = BpeTokenizer()
        tokenizer.train(listOf("stable token stable token"), maxMergeRules = 20)
        val beforeVocabulary = tokenizer.vocabularySnapshot()
        val beforeEmbeddings = tokenizer.embeddingMatrix().map(DoubleArray::toList)
        val path = Files.createTempFile("nonfinite-tokenizer-snapshot", ".json")
        Files.writeString(path, json)

        assertFailsWith<SerializationException> { tokenizer.load(path) }
        assertEquals(beforeVocabulary, tokenizer.vocabularySnapshot())
        assertEquals(beforeEmbeddings, tokenizer.embeddingMatrix().map(DoubleArray::toList))
    }

    private fun assertInvalidSnapshot(snapshot: TokenizerStateDto) {
        val tokenizer = BpeTokenizer()
        tokenizer.train(listOf("stable token stable token"), maxMergeRules = 20)
        val beforeVocabulary = tokenizer.vocabularySnapshot()
        val beforeEmbeddings = tokenizer.embeddingMatrix().map(DoubleArray::toList)
        val path = Files.createTempFile("invalid-tokenizer-snapshot", ".json")
        Files.writeString(path, JsonCodec.json.encodeToString(snapshot))

        assertFailsWith<IllegalArgumentException> { tokenizer.load(path) }

        assertEquals(beforeVocabulary, tokenizer.vocabularySnapshot())
        assertEquals(beforeEmbeddings, tokenizer.embeddingMatrix().map(DoubleArray::toList))
    }

    private fun validVocabulary() = listOf(
        EmbeddingRowDto("<unk>", 0, vector(0.0)),
        EmbeddingRowDto("token", 1, vector(0.5))
    )

    private fun vector(value: Double) = List(32) { index -> if (index == 0) value else 0.0 }
}
