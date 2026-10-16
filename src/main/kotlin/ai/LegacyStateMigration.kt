package ai

import java.nio.file.Files
import java.nio.file.Path

/** Kopiert bekannte Laufzeitdateien nach ausdrücklicher Entscheidung einmalig ins Nutzerprofil. */
class LegacyStateMigration(
    private val legacyDirectory: Path,
    private val storageDirectory: Path
) {
    private val markerFile = storageDirectory.resolve("legacy-state-migration.decided")

    /** Liefert nur vorhandene, bekannte Quelldateien, deren Ziel im Profil noch nicht existiert. */
    fun filesToMigrate(): List<Pair<Path, Path>> {
        if (legacyDirectory.toAbsolutePath().normalize() == storageDirectory.toAbsolutePath().normalize()) {
            return emptyList()
        }
        return LEGACY_STATE_FILES.map { relativePath ->
            legacyDirectory.resolve(relativePath) to storageDirectory.resolve(relativePath.fileName)
        }.filter { (source, target) ->
            Files.isRegularFile(source) && Files.notExists(target)
        }
    }

    /** Prüft den Marker, der sowohl Zustimmung als auch Ablehnung dauerhaft festhält. */
    fun hasDecision(): Boolean = Files.exists(markerFile)

    /** Führt die bestätigte Kopie aus oder speichert die Ablehnung, sodass später nicht ungefragt migriert wird. */
    fun decideToMigrate(confirmed: Boolean): List<Path> {
        if (hasDecision()) return emptyList()
        val files = filesToMigrate()
        Files.createDirectories(storageDirectory)
        val copied = if (confirmed) {
            files.map { (source, target) ->
                Files.copy(source, target)
                target
            }
        } else {
            emptyList()
        }
        FileIo.writeAtomically(markerFile, if (confirmed) "migrated" else "declined")
        return copied
    }

    companion object {
        private val LEGACY_STATE_FILES = listOf(
            Path.of("tokenizer_vocab.txt"),
            Path.of("tokenizer_corpus.txt"),
            Path.of("tokenizer_vocab.hash"),
            Path.of("tokenizer_embeddings.hash"),
            Path.of("knowledge.jsonl"),
            Path.of("training_examples.jsonl"),
            Path.of("build", "brain_state.json"),
            Path.of("build", "feedforward_model.json")
        )
    }
}
