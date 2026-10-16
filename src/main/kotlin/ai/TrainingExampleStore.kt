package ai

import java.nio.file.Files
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** A labelled example that can be used for training and repeatable evaluation. */
data class TrainingExample(
    val prompt: String,
    val expectedIntent: Intent,
    val expectedAnswer: String,
    val tags: List<String> = emptyList(),
    val timestamp: Instant = Instant.now()
)

/** Local JSONL persistence for labelled examples. No external model is involved. */
class TrainingExampleStore(private val path: java.nio.file.Path) {
    /** Fügt ein Beispiel hinzu oder ersetzt den Eintrag für denselben normalisierten Prompt. */
    fun add(example: TrainingExample) = addAll(listOf(example))

    /** Validiert und schreibt einen Batch mit nur einem Lese- und Schreibdurchlauf. */
    fun addAll(examples: Iterable<TrainingExample>) {
        val pending = examples.toList()
        if (pending.isEmpty()) return
        pending.forEach { example ->
            require(example.prompt.isNotBlank()) { "Der Trainingsprompt darf nicht leer sein." }
            require(example.expectedAnswer.isNotBlank()) { "Die erwartete Antwort darf nicht leer sein." }
        }

        val entries = load().toMutableList()
        pending.forEach { example ->
            val normalized = example.copy(
                prompt = example.prompt.trim(),
                tags = example.tags.map(String::trim).filter(String::isNotEmpty).distinct()
            )
            val promptKey = normalizedPromptKey(normalized.prompt)
            entries.removeAll { normalizedPromptKey(it.prompt) == promptKey }
            entries.add(normalized)
        }

        FileIo.writeAtomically(path, entries.joinToString(System.lineSeparator()) {
            JsonCodec.compactJson.encodeToString(it.toDto())
        })
    }

    /** Liefert sämtliche lesbaren Trainingsbeispiele in Speicherreihenfolge. */
    fun all(): List<TrainingExample> = load()

    /** Zählt gültige Einträge der Datei. */
    fun count(): Int = load().size

    /** Parst JSONL-Zeilen, protokolliert beschädigte Beispiele und überspringt sie. */
    private fun load(): List<TrainingExample> {
        if (!Files.exists(path)) return emptyList()
        return Files.readAllLines(path).mapNotNull { line ->
            if (line.isBlank()) null else try {
                JsonCodec.compactJson.decodeFromString(TrainingExampleDto.serializer(), line).toModel()
            } catch (e: Exception) {
                FileIo.logWarning("Trainingsbeispiel konnte nicht geladen werden.", e)
                null
            }
        }
    }
}

private fun normalizedPromptKey(prompt: String): String = TextFold.fold(prompt.trim())

@Serializable
private data class TrainingExampleDto(
    val prompt: String,
    val expectedIntent: String,
    val expectedAnswer: String,
    val tags: List<String>,
    val timestamp: String
) {
    /** Wandelt den gespeicherten Intent-Namen und ISO-Zeitstempel in das Laufzeitmodell um. */
    fun toModel(): TrainingExample = TrainingExample(
        prompt, intentFromName(expectedIntent), expectedAnswer, tags, Instant.parse(timestamp)
    )

    companion object {
        /** Konvertiert Enum und Zeitstempel des Laufzeitmodells in stabile JSON-Felder. */
        fun fromModel(value: TrainingExample) = TrainingExampleDto(
            value.prompt, intentName(value.expectedIntent), value.expectedAnswer,
            value.tags, value.timestamp.toString()
        )
    }
}

private fun TrainingExample.toDto() = TrainingExampleDto.fromModel(this)

/** Verwendet explizite Enum-Namen statt serialisierungsabhängiger Kotlin-Klassennamen. */
private fun intentName(intent: Intent): String = when (intent) {
    Intent.GenerateClass -> "GenerateClass"
    Intent.GenerateDataClass -> "GenerateDataClass"
    Intent.GenerateFunction -> "GenerateFunction"
    Intent.GenerateTest -> "GenerateTest"
    Intent.Explain -> "Explain"
    Intent.Tokenize -> "Tokenize"
    Intent.Smalltalk -> "Smalltalk"
    Intent.Unknown -> "Unknown"
}

/** Stellt gespeicherte Intent-Namen wieder her; unbekannte Werte werden sichtbar protokolliert. */
private fun intentFromName(name: String): Intent = when (name) {
    "GenerateClass" -> Intent.GenerateClass
    "GenerateDataClass" -> Intent.GenerateDataClass
    "GenerateFunction" -> Intent.GenerateFunction
    "GenerateTest" -> Intent.GenerateTest
    "Explain" -> Intent.Explain
    "Tokenize" -> Intent.Tokenize
    "Smalltalk" -> Intent.Smalltalk
    else -> {
        FileIo.logWarning("Unbekannter Intent in Trainingsbeispiel: '$name'.")
        Intent.Unknown
    }
}
