package ai

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal class ResearchNotesStore(
    private val directory: Path = Path.of(System.getProperty("user.home"), ".markusaller"),
    private val now: () -> LocalDateTime = LocalDateTime::now
) {
    val filePath: Path
        get() = directory.resolve(FILE_NAME)

    fun readAll(): String =
        if (Files.exists(filePath)) Files.readString(filePath) else ""

    fun append(note: String) {
        val normalizedNote = note.trim()
        require(normalizedNote.isNotEmpty()) { "Füge zuerst Informationen in das Sammelfeld ein." }
        require(normalizedNote.length <= MAX_NOTE_LENGTH) {
            "Ein Sammeltext darf höchstens $MAX_NOTE_LENGTH Zeichen enthalten."
        }

        val entry = buildString {
            append("----- ")
            append(now().format(TIMESTAMP_FORMAT))
            append(" -----")
            append(System.lineSeparator())
            append(normalizedNote)
            append(System.lineSeparator())
            append(System.lineSeparator())
        }
        Files.createDirectories(directory)
        Files.writeString(
            filePath,
            entry,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        )
    }

    private companion object {
        const val FILE_NAME = "research_notes.txt"
        const val MAX_NOTE_LENGTH = 100_000
        val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    }
}
