package ai

import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResearchNotesStoreTest {
    @Test
    fun readAllReturnsEmptyForMissingFileAndReadsSavedNotes() {
        val directory = Files.createTempDirectory("research-notes-read")
        val store = ResearchNotesStore(directory)

        assertEquals("", store.readAll())
        store.append("Gespeicherte Information")
        assertTrue(store.readAll().contains("Gespeicherte Information"))
    }

    @Test
    fun appendKeepsEachPastedNoteSeparateAndTimestamped() {
        val directory = Files.createTempDirectory("research-notes-store")
        val store = ResearchNotesStore(directory) { LocalDateTime.parse("2026-10-17T22:00:00") }

        store.append("  Erste Quelle\nmit zwei Zeilen  ")
        store.append("Zweite Quelle")

        val content = Files.readString(store.filePath)
        assertEquals(2, Regex("----- 2026-10-17T22:00:00 -----").findAll(content).count())
        assertTrue(content.contains("Erste Quelle\nmit zwei Zeilen"))
        assertTrue(content.contains("Zweite Quelle"))
        assertTrue(content.indexOf("Erste Quelle") < content.indexOf("Zweite Quelle"))
    }

    @Test
    fun appendRejectsBlankAndOverlongNotesWithoutCreatingFile() {
        val directory = Files.createTempDirectory("research-notes-validation")
        val store = ResearchNotesStore(directory)

        assertFailsWith<IllegalArgumentException> { store.append(" \n ") }
        assertFailsWith<IllegalArgumentException> { store.append("a".repeat(100_001)) }
        assertTrue(Files.notExists(store.filePath))
    }
}
