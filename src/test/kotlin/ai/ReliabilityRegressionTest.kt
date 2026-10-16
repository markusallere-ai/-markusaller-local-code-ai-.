package ai

import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for conversational state, explicit learning consent,
 * migration safety, current embeddings, and bounded JSONL history.
 */
class ReliabilityRegressionTest {
    /** Uses isolated storage and disables initialization-time snapshots for predictable tests. */
    private fun assistant() =
        CodeAssistant(AssistantConfig(Files.createTempDirectory("assistant-reliability")))

    @Test
    fun historyPreviewEscapesUntrustedHtmlText() {
        assertEquals(
            "&lt;img src=&quot;https://example.invalid/?a=1&amp;b=2&quot;&gt;",
            escapeHtmlText("<img src=\"https://example.invalid/?a=1&b=2\">")
        )
    }

    @Test
    fun concurrentAtomicWritesDoNotShareTemporaryFiles() {
        val directory = Files.createTempDirectory("atomic-write-concurrency")
        val target = directory.resolve("state.json")
        val values = (0 until 4).map { "writer-$it:" + "x".repeat(1024) }
        val executor = Executors.newFixedThreadPool(values.size)

        try {
            val writes = values.map { value ->
                executor.submit {
                    repeat(5) { FileIo.writeAtomically(target, value) }
                }
            }
            writes.forEach { it.get() }

            assertTrue(Files.readString(target) in values)
            Files.list(directory).use { files -> assertEquals(1L, files.count()) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun knowledgeRecallRecomputesStoredEmbeddingsAfterEmbeddingSpaceChanges() {
        val directory = Files.createTempDirectory("assistant-refresh-knowledge-embeddings")
        var embeddingVersion = 0
        val store = KnowledgeStore(
            AssistantConfig(directory),
            embed = {
                if (embeddingVersion == 0) listOf(1.0, 0.0) else listOf(0.0, 1.0, 0.0)
            },
            similarity = { left, right ->
                if (left.size != right.size) 0.0 else left.indices.sumOf { left[it] * right[it] }
            }
        )

        store.learn("stored prompt", "stored answer")
        embeddingVersion = 1

        assertEquals(1.0, store.recall("different query", 1).single().similarity)
    }

    @Test
    fun unknownPromptRequiresConfirmationAndCanBeRecalledAfterRestart() {
        val storageDir = Files.createTempDirectory("assistant-conversation-learning")
        val config = AssistantConfig(storageDir)
        val first = CodeAssistant(config)

        assertTrue(first.handleConversationTurn("quartz nebula").awaitingClarification)
        val proposal = first.handleConversationTurn("Ein Begriff fuer ein erfundenes Sternbild.")
        assertTrue(proposal.awaitingConfirmation)
        assertFalse(proposal.learnedKnowledge)
        assertEquals(0, first.knowledgeEntryCount())

        assertTrue(first.handleConversationTurn("ja").learnedKnowledge)
        val restarted = CodeAssistant(config)

        assertEquals(
            "Ein Begriff fuer ein erfundenes Sternbild.",
            restarted.handleConversationTurn("quartz nebula").response
        )
    }

    @Test
    fun unhelpfulClarificationDoesNotBecomeKnowledge() {
        val assistant = assistant()

        assertTrue(assistant.handleConversationTurn("quartz nebula").awaitingClarification)
        val unhelpful = assistant.handleConversationTurn("keine Ahnung")
        assertTrue(unhelpful.awaitingClarification)
        assertFalse(unhelpful.learnedKnowledge)
        assertEquals(0, assistant.knowledgeEntryCount())

        assertTrue(assistant.handleConversationTurn("Ein erfundenes Sternbild.").awaitingConfirmation)
        assertTrue(assistant.handleConversationTurn("ja").learnedKnowledge)
    }

    @Test
    fun decliningClarificationDoesNotPersistIt() {
        val assistant = assistant()
        assistant.handleConversationTurn("quartz nebula")
        assertTrue(assistant.handleConversationTurn("Ein erfundenes Sternbild.").awaitingConfirmation)

        assertFalse(assistant.handleConversationTurn("nein").learnedKnowledge)
        assertEquals(0, assistant.knowledgeEntryCount())
    }

    /** Übliche zustimmende/ablehnende Formulierungen dürfen nicht als neue Erklärung gespeichert werden. */
    @Test
    fun naturalAffirmationsConfirmKnowledgeAndNaturalDeclineDoesNotSave() {
        listOf("Ja, bitte!", "ja gerne", "jo").forEachIndexed { index, confirmation ->
            val assistant = assistant()
            val prompt = "unknown term $index"
            val explanation = "Eine lokale Erklärung $index."

            assertTrue(assistant.handleConversationTurn(prompt).awaitingClarification)
            assertTrue(assistant.handleConversationTurn(explanation).awaitingConfirmation)
            assertTrue(assistant.handleConversationTurn(confirmation).learnedKnowledge)
            assertEquals(explanation, assistant.handleConversationTurn(prompt).response)
        }

        val assistant = assistant()
        assertTrue(assistant.handleConversationTurn("unknown declined term").awaitingClarification)
        assertTrue(assistant.handleConversationTurn("Eine Erklärung.").awaitingConfirmation)
        assertFalse(assistant.handleConversationTurn("nein danke").learnedKnowledge)
        assertEquals(0, assistant.knowledgeEntryCount())
    }

    @Test
    fun recognizedNewRequestAndCancellationClearPendingClarification() {
        val assistant = assistant()
        assertTrue(assistant.handleConversationTurn("asdkfj qwertz").awaitingClarification)

        val nextTurn = assistant.handleConversationTurn("Erstelle eine Klasse Person")
        assertTrue(nextTurn.response.contains("class Person"))
        assertEquals(0, assistant.knowledgeEntryCount())

        assertTrue(assistant.handleConversationTurn("another unknown").awaitingClarification)
        assertTrue(assistant.handleConversationTurn("abbrechen").response.contains("verwerfe"))
    }

    @Test
    fun learnedKnowledgeCanBeListedAndDeleted() {
        val assistant = assistant()
        assistant.storeKnowledge("quartz nebula", "Ein erfundenes Sternbild.")

        assertEquals("quartz nebula", assistant.listKnowledge().single().prompt)
        assertTrue(assistant.deleteKnowledge("QUARTZ NEBULA"))
        assertTrue(assistant.listKnowledge().isEmpty())
        assertFalse(assistant.deleteKnowledge("quartz nebula"))
    }

    @Test
    fun exactCorrectionOverridesRecognizedCodeIntentInBothPromptApis() {
        val assistant = assistant()
        assistant.storeKnowledge("Erstelle eine Klasse Person", "CORRECTED_ANSWER")

        assertEquals("CORRECTED_ANSWER", assistant.handlePrompt("Erstelle eine Klasse Person"))
        assertEquals("CORRECTED_ANSWER", assistant.handleConversationTurn("Erstelle eine Klasse Person").response)
    }

    @Test
    fun knownSmalltalkUsesSameResponseFromBothPromptApis() {
        val assistant = assistant()

        assertEquals(assistant.handlePrompt("Hallo"), assistant.handleConversationTurn("Hallo").response)
    }

    @Test
    fun explanationStartingWithQuestionWordCanAnswerClarification() {
        val assistant = assistant()
        assertTrue(assistant.handleConversationTurn("unknown concept").awaitingClarification)

        val explanation = assistant.handleConversationTurn("Wie funktioniert das?")

        assertTrue(explanation.awaitingConfirmation)
        assertTrue(explanation.response.contains("unknown concept"))
    }

    @Test
    fun blankTurnsPreserveBothClarificationAndConfirmationStates() {
        val assistant = assistant()
        assertTrue(assistant.handleConversationTurn("unknown term").awaitingClarification)

        assertEquals("Bitte schreibe eine Nachricht.", assistant.handleConversationTurn(" ").response)
        assertTrue(assistant.handleConversationTurn("Eine kurze Erklärung.").awaitingConfirmation)
        assertEquals("Bitte schreibe eine Nachricht.", assistant.handleConversationTurn("\n").response)
        assertEquals(0, assistant.knowledgeEntryCount())

        assertTrue(assistant.handleConversationTurn("ja").learnedKnowledge)
        assertEquals("Eine kurze Erklärung.", assistant.handleConversationTurn("unknown term").response)
    }

    @Test
    fun directCancellationApiClearsPendingClarificationWithoutLearning() {
        val assistant = assistant()
        assertTrue(assistant.handleConversationTurn("unknown cancellation term").awaitingClarification)

        assistant.cancelPendingClarification()

        assertEquals(0, assistant.knowledgeEntryCount())
        assertTrue(assistant.handleConversationTurn("unknown cancellation term").awaitingClarification)
    }

    @Test
    fun smalltalkAbandonsOpenClarificationWithoutSavingIt() {
        val assistant = assistant()
        assertTrue(assistant.handleConversationTurn("unknown smalltalk term").awaitingClarification)

        val greeting = assistant.handleConversationTurn("Hallo")

        assertTrue(greeting.response.contains("lokaler Kotlin-Code-Assistent"))
        assertEquals(0, assistant.knowledgeEntryCount())
        assertTrue(assistant.handleConversationTurn("unknown smalltalk term").awaitingClarification)
    }

    @Test
    fun conversationHistoryRoundTripsMultilineMessagesAsJsonLines() {
        val directory = Files.createTempDirectory("assistant-history")
        val store = ConversationHistoryStore(directory)
        val entries = listOf(
            HistoryEntry("Prompt", "2026-10-16 10:00:00", "Erstelle eine Klasse"),
            HistoryEntry("Antwort", "2026-10-16 10:00:01", "data class Person(\n    val name: String\n)\n|| Ende")
        )

        store.save(entries)

        assertEquals(2, Files.readAllLines(directory.resolve("history.jsonl")).size)
        assertEquals(entries, store.load())
    }

    /** Ein vollständiges Rewrite muss einen Trenner hinterlassen, bevor spätere JSONL-Zeilen angehängt werden. */
    @Test
    fun appendingAfterSaveKeepsEachEntryOnItsOwnJsonLine() {
        val directory = Files.createTempDirectory("assistant-history-save-append")
        val store = ConversationHistoryStore(directory)
        val first = HistoryEntry("Prompt", "first", "first message")
        val second = HistoryEntry("Antwort", "second", "second message")
        val third = HistoryEntry("Prompt", "third", "third message")

        store.save(listOf(first, second))
        store.append(listOf(third))

        assertEquals(listOf(first, second, third), store.load())
    }

    @Test
    fun appendingMigratesLegacyHistoryInsteadOfDroppingIt() {
        val directory = Files.createTempDirectory("assistant-legacy-history")
        Files.writeString(
            directory.resolve("history.txt"),
            "Prompt||2026-10-16 10:00:00||legacy prompt\n"
        )
        val store = ConversationHistoryStore(directory)
        val newEntry = HistoryEntry("Antwort", "2026-10-16 10:00:01", "new answer")

        store.append(listOf(newEntry))

        assertEquals(
            listOf(
                HistoryEntry("Prompt", "2026-10-16 10:00:00", "legacy prompt"),
                newEntry
            ),
            store.load()
        )

        val followingEntry = HistoryEntry("Prompt", "2026-10-16 10:00:02", "following prompt")
        store.append(listOf(followingEntry))
        assertEquals(3, store.load().size)
    }

    @Test
    fun conversationHistoryIsCappedAndCompactedAfterReachingLimit() {
        val directory = Files.createTempDirectory("assistant-bounded-history")
        val store = ConversationHistoryStore(directory)
        val fullHistory = (0 until ConversationHistoryStore.MAX_ENTRIES).map { index ->
            HistoryEntry("Prompt", index.toString(), "entry $index")
        }
        store.save(fullHistory)

        val appended = HistoryEntry("Antwort", "new", "new entry")
        store.append(listOf(appended))

        val loaded = store.load()
        assertEquals(9_000, loaded.size)
        assertEquals("entry 9999", loaded[loaded.lastIndex - 1].text)
        assertEquals(appended, loaded.last())

        val following = HistoryEntry("Prompt", "following", "after compaction")
        store.append(listOf(following))
        assertEquals(following, store.load().last())
    }

    @Test
    fun legacyStateIsCopiedOnlyAfterExplicitConfirmation() {
        val legacyDirectory = Files.createTempDirectory("assistant-legacy-source")
        val storageDirectory = Files.createTempDirectory("assistant-legacy-target")
        val source = legacyDirectory.resolve("knowledge.jsonl")
        Files.writeString(source, "legacy knowledge")
        val migration = LegacyStateMigration(legacyDirectory, storageDirectory)

        assertEquals(1, migration.filesToMigrate().size)
        assertTrue(migration.decideToMigrate(confirmed = false).isEmpty())
        assertFalse(Files.exists(storageDirectory.resolve("knowledge.jsonl")))
        assertTrue(migration.hasDecision())
        assertTrue(migration.decideToMigrate(confirmed = true).isEmpty())

        val acceptedStorage = Files.createTempDirectory("assistant-legacy-accepted")
        val accepted = LegacyStateMigration(legacyDirectory, acceptedStorage)
        assertEquals(
            listOf(acceptedStorage.resolve("knowledge.jsonl")),
            accepted.decideToMigrate(confirmed = true)
        )
        assertEquals("legacy knowledge", Files.readString(acceptedStorage.resolve("knowledge.jsonl")))
    }
}
