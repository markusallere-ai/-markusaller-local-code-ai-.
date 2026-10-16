package ai

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises persistence validation, malformed records, replacement, and boundary conditions. */
class PersistenceBoundaryTest {
    @Test
    fun knowledgeStoreRejectsBlankPromptsAndAnswersWithoutCreatingEntries() {
        val directory = Files.createTempDirectory("knowledge-validation")
        val store = KnowledgeStore(
            AssistantConfig(directory),
            embed = { listOf(1.0) },
            similarity = { _, _ -> 1.0 }
        )

        assertFailsWith<IllegalArgumentException> { store.learn(" ", "answer") }
        assertFailsWith<IllegalArgumentException> { store.learn("prompt", " \n ") }
        assertEquals(0, store.count())
        assertFalse(Files.exists(directory.resolve("knowledge.jsonl")))
    }

    @Test
    fun knowledgeStoreTrimsPromptsTagsAndReplacesCaseInsensitiveDuplicates() {
        val store = knowledgeStore()
        store.learn(" quartz ", "old answer", listOf(" project ", "", "project"))
        store.learn("QUARTZ", "new answer", listOf(" corrected "))

        val listed = store.list().single()
        assertEquals("QUARTZ", listed.prompt)
        assertEquals("new answer", listed.answer)
        assertEquals(listOf("corrected"), listed.tags)
        assertEquals(1, store.count())
    }

    @Test
    fun knowledgeStoreUsesTextFoldForDuplicateAndExactPromptMatching() {
        val store = knowledgeStore()
        store.learn("für", "old answer")
        store.learn("fuer", "new answer")

        assertEquals(1, store.count())
        assertEquals("new answer", store.recall("FÜR", 1).single().answer)
    }

    @Test
    fun knowledgeRecallReturnsNoResultsForBlankQueriesOrNonpositiveLimits() {
        var embeddingCalls = 0
        val store = KnowledgeStore(
            AssistantConfig(Files.createTempDirectory("knowledge-empty-recall")),
            embed = { embeddingCalls++; listOf(1.0) },
            similarity = { _, _ -> 1.0 }
        )
        store.learn("valid prompt", "answer")
        embeddingCalls = 0

        assertTrue(store.recall("  ").isEmpty())
        assertTrue(store.recall("query", 0).isEmpty())
        assertTrue(store.recall("query", -1).isEmpty())
        assertEquals(0, embeddingCalls)
    }

    @Test
    fun knowledgeRecallUsesExactPromptMatchEvenWhenEmbeddingScoresDiffer() {
        val store = KnowledgeStore(
            AssistantConfig(Files.createTempDirectory("knowledge-exact-recall")),
            embed = { listOf(0.0, 1.0) },
            similarity = { _, _ -> -1.0 }
        )
        store.learn("  Exact Prompt ", "exact answer")
        store.learn("other prompt", "other answer")

        val exact = store.recall("EXACT PROMPT", 1).single()
        assertEquals("exact answer", exact.answer)
        assertEquals(1.0, exact.similarity)
    }

    @Test
    fun knowledgeStoreSkipsMalformedJsonLinesAndKeepsValidRecords() {
        val directory = Files.createTempDirectory("knowledge-corrupt-line")
        Files.writeString(
            directory.resolve("knowledge.jsonl"),
            """{"prompt":"valid prompt","answer":"valid answer"}""" + "\n" +
                "{ malformed json }\n"
        )
        val store = knowledgeStore(directory)

        assertEquals(1, store.count())
        assertEquals("valid answer", store.list().single().answer)
    }

    @Test
    fun knowledgeDeleteRejectsBlankInputAndDoesNotDeletePartialMatches() {
        val store = knowledgeStore()
        store.learn("person profile", "answer")

        assertFailsWith<IllegalArgumentException> { store.delete(" ") }
        assertFalse(store.delete("person"))
        assertEquals(1, store.count())
        assertTrue(store.delete("PERSON PROFILE"))
        assertEquals(0, store.count())
    }

    @Test
    fun trainingExampleStoreValidatesAndNormalizesAddedExamples() {
        val path = Files.createTempDirectory("training-validation").resolve("examples.jsonl")
        val store = TrainingExampleStore(path)
        val timestamp = Instant.parse("2026-01-02T03:04:05Z")

        assertFailsWith<IllegalArgumentException> {
            store.add(TrainingExample(" ", Intent.Explain, "answer"))
        }
        assertFailsWith<IllegalArgumentException> {
            store.add(TrainingExample("prompt", Intent.Explain, ""))
        }

        store.add(TrainingExample(" prompt ", Intent.Explain, "answer", listOf(" docs ", "", "docs"), timestamp))

        val saved = store.all().single()
        assertEquals("prompt", saved.prompt)
        assertEquals(listOf("docs"), saved.tags)
        assertEquals(timestamp, saved.timestamp)
    }

    @Test
    fun trainingExampleStoreReplacesPromptWithoutGrowingDuplicateRows() {
        val path = Files.createTempDirectory("training-replacement").resolve("examples.jsonl")
        val store = TrainingExampleStore(path)
        store.add(TrainingExample("same prompt", Intent.Explain, "old answer"))
        store.add(TrainingExample(" SAME PROMPT ", Intent.GenerateFunction, "new answer"))

        assertEquals(1, store.count())
        assertEquals(Intent.GenerateFunction, store.all().single().expectedIntent)
        assertEquals("new answer", store.all().single().expectedAnswer)
    }

    @Test
    fun trainingExampleStoreUsesTextFoldForDuplicatePrompts() {
        val path = Files.createTempDirectory("training-folded-duplicate").resolve("examples.jsonl")
        val store = TrainingExampleStore(path)
        store.add(TrainingExample("für", Intent.Explain, "first"))
        store.add(TrainingExample("fuer", Intent.GenerateFunction, "last"))

        assertEquals(1, store.count())
        assertEquals(Intent.GenerateFunction, store.all().single().expectedIntent)
    }

    @Test
    fun trainingExampleBatchWritesOneFinalValuePerRepeatedPrompt() {
        val path = Files.createTempDirectory("training-batch").resolve("examples.jsonl")
        val store = TrainingExampleStore(path)

        store.addAll(
            listOf(
                TrainingExample("shared prompt", Intent.Explain, "first"),
                TrainingExample("other prompt", Intent.Tokenize, "second"),
                TrainingExample("SHARED PROMPT", Intent.GenerateClass, "final")
            )
        )

        assertEquals(2, store.count())
        assertEquals("final", store.all().single { it.prompt == "SHARED PROMPT" }.expectedAnswer)
        assertEquals(listOf("other prompt", "SHARED PROMPT"), store.all().map(TrainingExample::prompt))
    }

    @Test
    fun emptyTrainingBatchDoesNotCreateAFile() {
        val path = Files.createTempDirectory("training-empty-batch").resolve("examples.jsonl")

        TrainingExampleStore(path).addAll(emptyList())

        assertFalse(Files.exists(path))
    }

    @Test
    fun invalidTrainingBatchDoesNotPartiallyPersistEarlierExamples() {
        val path = Files.createTempDirectory("training-batch-atomic").resolve("examples.jsonl")
        val store = TrainingExampleStore(path)
        store.add(TrainingExample("existing", Intent.Explain, "existing answer"))
        val before = Files.readString(path)

        assertFailsWith<IllegalArgumentException> {
            store.addAll(
                listOf(
                    TrainingExample("new", Intent.Explain, "valid"),
                    TrainingExample("invalid", Intent.Explain, " ")
                )
            )
        }

        assertEquals(before, Files.readString(path))
        assertEquals(listOf("existing"), store.all().map(TrainingExample::prompt))
    }

    @Test
    fun trainingExampleStoreSkipsCorruptedRowsButLoadsFollowingRecords() {
        val path = Files.createTempDirectory("training-corrupt-row").resolve("examples.jsonl")
        val store = TrainingExampleStore(path)
        store.add(TrainingExample("valid", Intent.Tokenize, "tokenized"))
        val validLine = Files.readString(path)
        Files.writeString(path, "{ broken }\n$validLine")

        assertEquals(listOf("valid"), store.all().map(TrainingExample::prompt))
    }

    @Test
    fun trainingExampleStoreMapsFutureIntentNamesToUnknown() {
        val path = Files.createTempDirectory("training-future-intent").resolve("examples.jsonl")
        val store = TrainingExampleStore(path)
        store.add(TrainingExample("future example", Intent.Explain, "answer"))
        Files.writeString(path, Files.readString(path).replace("\"Explain\"", "\"FutureIntent\""))

        assertEquals(Intent.Unknown, store.all().single().expectedIntent)
    }

    @Test
    fun trainingExampleStoreSkipsInvalidTimestampAndContinuesLoading() {
        val path = Files.createTempDirectory("training-invalid-time").resolve("examples.jsonl")
        val store = TrainingExampleStore(path)
        store.add(TrainingExample("valid example", Intent.Explain, "answer"))
        val valid = Files.readString(path)
        val invalid = valid.replace(Regex("202\\d-[^\"]+"), "not-an-iso-date")
        Files.writeString(path, "$invalid\n$valid")

        assertEquals(listOf("valid example"), store.all().map(TrainingExample::prompt))
    }

    @Test
    fun historySaveKeepsOnlyNewestAllowedEntries() {
        val store = ConversationHistoryStore(Files.createTempDirectory("history-save-limit"))
        val entries = (0..ConversationHistoryStore.MAX_ENTRIES).map { index ->
            HistoryEntry("Prompt", index.toString(), "entry $index")
        }

        store.save(entries)
        val loaded = store.load()

        assertEquals(ConversationHistoryStore.MAX_ENTRIES, loaded.size)
        assertEquals("entry 1", loaded.first().text)
        assertEquals("entry 10000", loaded.last().text)
    }

    @Test
    fun historyReaderSkipsInvalidJsonLinesWithoutDroppingLaterMessages() {
        val directory = Files.createTempDirectory("history-invalid-json")
        val store = ConversationHistoryStore(directory)
        val first = HistoryEntry("Prompt", "first", "first")
        val last = HistoryEntry("Antwort", "last", "last")
        store.save(listOf(first, last))
        val path = directory.resolve("history.jsonl")
        val validLines = Files.readAllLines(path)
        Files.writeString(path, validLines.first() + "\n{invalid}\n" + validLines.last() + "\n")

        assertEquals(listOf(first, last), store.load())
    }

    @Test
    fun historyAppendRepairsMissingFinalLineSeparator() {
        val directory = Files.createTempDirectory("history-missing-final-newline")
        val store = ConversationHistoryStore(directory)
        val first = HistoryEntry("Prompt", "first", "first")
        val second = HistoryEntry("Antwort", "second", "second")
        store.save(listOf(first))
        val path = directory.resolve("history.jsonl")
        Files.writeString(path, Files.readString(path).trimEnd('\n', '\r'))

        store.append(listOf(second))

        assertEquals(listOf(first, second), store.load())
    }

    @Test
    fun legacyHistoryKeepsDelimiterTextInsideMessageBody() {
        val directory = Files.createTempDirectory("history-legacy-delimiter")
        Files.writeString(directory.resolve("history.txt"), "Prompt||time||left||right\n")

        assertEquals(
            listOf(HistoryEntry("Prompt", "time", "left||right")),
            ConversationHistoryStore(directory).load()
        )
    }

    @Test
    fun emptyHistoryAppendDoesNotCreateAFile() {
        val directory = Files.createTempDirectory("history-empty-append")

        ConversationHistoryStore(directory).append(emptyList())

        assertFalse(Files.exists(directory.resolve("history.jsonl")))
    }

    @Test
    fun migrationDoesNotReplaceAnExistingTargetAndDoesNotCopyFromSameDirectory() {
        val directory = Files.createTempDirectory("migration-existing")
        val source = directory.resolve("knowledge.jsonl")
        Files.writeString(source, "source")
        val targetDirectory = Files.createTempDirectory("migration-target")
        val target = targetDirectory.resolve("knowledge.jsonl")
        Files.writeString(target, "existing target")

        assertTrue(LegacyStateMigration(directory, targetDirectory).filesToMigrate().isEmpty())
        assertTrue(LegacyStateMigration(directory, directory).filesToMigrate().isEmpty())
        assertEquals("existing target", Files.readString(target))
    }

    private fun knowledgeStore(directory: java.nio.file.Path = Files.createTempDirectory("knowledge-store")) =
        KnowledgeStore(
            AssistantConfig(directory),
            embed = { listOf(it.length.toDouble(), 1.0) },
            similarity = { left, right ->
                if (left.size != right.size) 0.0 else left.indices.sumOf { index -> left[index] * right[index] }
            }
        )
}
