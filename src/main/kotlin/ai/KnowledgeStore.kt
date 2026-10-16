package ai

import java.nio.file.Files
import kotlinx.serialization.encodeToString

/**
 * Dauerhafter JSONL-Speicher für gelernte Prompt-Antwort-Beispiele.
 * Gespeicherte Vektoren sind nur Metadaten: Beim Recall wird der Prompt neu
 * eingebettet, damit Änderungen am Vokabular keinen alten Vektorraum verwenden.
 */
class KnowledgeStore(
    private val config: AssistantConfig,
    private val embed: (String) -> List<Double>,
    private val similarity: (List<Double>, List<Double>) -> Double
) {
    /** Fügt eine Antwort hinzu oder ersetzt den gleichlautenden Prompt ohne Duplikat. */
    fun learn(prompt: String, answer: String, tags: List<String> = emptyList()) {
        require(prompt.isNotBlank()) { "Der Lernprompt darf nicht leer sein." }
        require(answer.isNotBlank()) { "Die gelernte Antwort darf nicht leer sein." }
        val entry = KnowledgeEntryDto(
            prompt.trim(), answer,
            tags.map(String::trim).filter(String::isNotEmpty).distinct(),
            embed(prompt)
        )
        val promptKey = normalizedPromptKey(entry.prompt)
        val entries = load().filterNot { normalizedPromptKey(it.prompt) == promptKey } + entry
        save(entries)
    }

    /** Sortiert gespeicherte Beispiele nach aktueller Embedding-Ähnlichkeit zur Anfrage. */
    fun recall(prompt: String, limit: Int = 3): List<KnowledgeMatch> {
        if (prompt.isBlank() || limit <= 0) return emptyList()
        val query = embed(prompt)
        return load().map { entry ->
            KnowledgeMatch(
                entry.prompt, entry.answer, entry.tags,
                if (normalizedPromptKey(entry.prompt) == normalizedPromptKey(prompt)) 1.0
                else similarity(query, embed(entry.prompt))
            )
        }.sortedByDescending { it.similarity }.take(limit)
    }

    /** Sucht ohne Fuzzy-Matching nach einem identischen Prompt, Groß-/Kleinschreibung wird ignoriert. */
    fun findExact(prompt: String): KnowledgeMatch? = load()
        .firstOrNull { normalizedPromptKey(it.prompt) == normalizedPromptKey(prompt) }
        ?.let { KnowledgeMatch(it.prompt, it.answer, it.tags, 1.0) }

    /** Gibt alle Einträge unabhängig von Ähnlichkeit in Speicherreihenfolge zurück. */
    fun list(): List<KnowledgeMatch> = load().map { entry ->
        KnowledgeMatch(entry.prompt, entry.answer, entry.tags, 1.0)
    }

    /** Entfernt gleichlautende Prompts und meldet, ob tatsächlich etwas gelöscht wurde. */
    fun delete(prompt: String): Boolean {
        require(prompt.isNotBlank()) { "Der Suchbegriff darf nicht leer sein." }
        val entries = load()
        val promptKey = normalizedPromptKey(prompt)
        val remaining = entries.filterNot { normalizedPromptKey(it.prompt) == promptKey }
        if (remaining.size == entries.size) return false
        save(remaining)
        return true
    }

    private fun normalizedPromptKey(prompt: String): String = TextFold.fold(prompt.trim())

    /** Zählt alle gültigen, gespeicherten Wissenseinträge. */
    fun count(): Int = load().size

    /** Liest gültige JSONL-Zeilen; beschädigte Zeilen werden protokolliert und übersprungen. */
    private fun load(): List<KnowledgeEntryDto> {
        val path = config.knowledgeFile()
        if (!Files.exists(path)) return emptyList()
        return Files.readAllLines(path).mapNotNull { line ->
            if (line.isBlank()) null else try {
                JsonCodec.compactJson.decodeFromString(KnowledgeEntryDto.serializer(), line)
            } catch (e: Exception) {
                FileIo.logWarning("Wissenseintrag konnte nicht geladen werden.", e)
                null
            }
        }
    }

    /** Ersetzt die Datei atomar durch die vollständige aktualisierte Eintragsliste. */
    private fun save(entries: List<KnowledgeEntryDto>) {
        FileIo.writeAtomically(config.knowledgeFile(), entries.joinToString(System.lineSeparator()) {
            JsonCodec.compactJson.encodeToString(KnowledgeEntryDto.serializer(), it)
        })
    }
}
