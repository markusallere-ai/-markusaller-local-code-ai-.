package ai

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private const val LEGACY_HISTORY_FIELD_COUNT = 3

/** Ein Verlaufseintrag; der Text bleibt als Feld erhalten und darf Zeilenumbrüche enthalten. */
data class HistoryEntry(val role: String, val timestamp: String, val text: String)

/** JSONL-Verlaufsspeicher mit Legacy-Leseweg, Append für normale Turns und begrenzter Aufbewahrung. */
class ConversationHistoryStore(
    private val directory: Path = Path.of(System.getProperty("user.home"), ".markusaller")
) {
    private val historyPath = directory.resolve("history.jsonl")
    private val legacyHistoryPath = directory.resolve("history.txt")
    private var entryCount: Int? = null

    /** Liest JSONL bevorzugt, fällt bei Bedarf auf das alte Trennzeichenformat zurück und begrenzt die Ausgabe. */
    @Synchronized
    fun load(): List<HistoryEntry> {
        val entries = when {
            Files.exists(historyPath) -> Files.readAllLines(historyPath).mapNotNull(::decodeEntry)
            Files.exists(legacyHistoryPath) -> loadLegacyHistory()
            else -> emptyList()
        }.takeLast(MAX_ENTRIES)
        entryCount = entries.size
        return entries
    }

    /** Ersetzt den Verlauf vollständig und behält nur die neuesten zulässigen Einträge. */
    @Synchronized
    fun save(entries: List<HistoryEntry>) {
        val retained = entries.takeLast(MAX_ENTRIES)
        writeEntries(retained)
        entryCount = retained.size
    }

    /** Hängt Einträge an; nur Migration oder Überschreiten des Limits erfordert eine Kompaktierung. */
    @Synchronized
    fun append(entries: List<HistoryEntry>) {
        when {
            entries.isEmpty() -> Unit
            Files.notExists(historyPath) && Files.exists(legacyHistoryPath) ->
                rewriteWithEntries(retainedAfterAppend(load() + entries))
            (entryCount ?: load().size) + entries.size > MAX_ENTRIES ->
                rewriteWithEntries(retainedAfterAppend(load() + entries))
            else -> appendToFile(entries)
        }
    }

    /** Serialisiert nur den neuen Batch und hängt dessen JSONL-Zeilen an die Datei an. */
    private fun appendToFile(entries: List<HistoryEntry>) {
        val existingCount = entryCount ?: 0
        Files.createDirectories(directory)
        if (Files.exists(historyPath) && Files.size(historyPath) > 0) {
            // Prüft nur das letzte Byte, damit auch alte oder abgebrochene
            // Schreibvorgänge sicher mit einer frischen Zeile fortgesetzt werden.
            val endsWithLineFeed = Files.newByteChannel(historyPath, StandardOpenOption.READ).use { channel ->
                val lastByte = java.nio.ByteBuffer.allocate(1)
                channel.position(channel.size() - 1)
                channel.read(lastByte)
                lastByte.array()[0] == '\n'.code.toByte()
            }
            if (!endsWithLineFeed) {
                Files.writeString(historyPath, System.lineSeparator(), StandardOpenOption.APPEND)
            }
        }
        val encoded = entries.joinToString(System.lineSeparator(), postfix = System.lineSeparator()) {
            encodeEntry(it)
        }
        Files.writeString(
            historyPath,
            encoded,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        )
        entryCount = existingCount + entries.size
    }

    /** Schreibt den kompakten Verlauf neu und synchronisiert den im Speicher gehaltenen Zähler. */
    private fun rewriteWithEntries(entries: List<HistoryEntry>) {
        writeEntries(entries)
        entryCount = entries.size
    }

    private fun retainedAfterAppend(entries: List<HistoryEntry>): List<HistoryEntry> =
        // Nach Überschreiten des Limits wird auf einen kleineren Bestand
        // kompaktiert, um häufige Komplettschreibvorgänge zu vermeiden.
        entries.takeLast(if (entries.size > MAX_ENTRIES) COMPACTED_ENTRIES else MAX_ENTRIES)

    /** Kodiert jeden Eintrag als genau eine JSON-Zeile und ersetzt die Datei atomar. */
    private fun writeEntries(entries: List<HistoryEntry>) {
        val content = if (entries.isEmpty()) {
            ""
        } else {
            entries.joinToString(
                separator = System.lineSeparator(),
                postfix = System.lineSeparator(),
                transform = ::encodeEntry
            )
        }
        FileIo.writeAtomically(historyPath, content)
    }

    /** Wandelt ein Laufzeitobjekt in die kompakte JSON-Darstellung einer einzelnen Zeile um. */
    private fun encodeEntry(entry: HistoryEntry): String =
        JsonCodec.compactJson.encodeToString(
            HistoryEntryDto.serializer(),
            HistoryEntryDto(entry.role, entry.timestamp, entry.text)
        )

    /** Parst eine JSONL-Zeile; ungültige JSON-Struktur wird protokolliert und verworfen. */
    private fun decodeEntry(line: String): HistoryEntry? {
        if (line.isBlank()) return null
        return try {
            val entry = JsonCodec.compactJson.decodeFromString(HistoryEntryDto.serializer(), line)
            HistoryEntry(entry.role, entry.timestamp, entry.text)
        } catch (exception: SerializationException) {
            FileIo.logWarning("Verlaufseintrag konnte nicht gelesen werden.", exception)
            null
        }
    }

    private fun loadLegacyHistory(): List<HistoryEntry> =
        Files.readAllLines(legacyHistoryPath).mapNotNull { line ->
            // Das dritte Feld nimmt auch alte Nachrichten vollständig auf, wenn sie "||" enthalten.
            val parts = line.split("||", limit = 3)
            if (parts.size == LEGACY_HISTORY_FIELD_COUNT) {
                HistoryEntry(parts[0], parts[1], parts[2])
            } else {
                null
            }
        }

    companion object {
        const val MAX_ENTRIES = 10_000
        const val COMPACTED_ENTRIES = 9_000
    }
}
