package ai

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.logging.Level
import java.util.logging.Logger

/** Gemeinsame Dateifunktionen, damit Speicherfehler protokolliert und Schreibvorgänge konsistent ersetzt werden. */
internal object FileIo {
    private val logger = Logger.getLogger("ai.CodeAssistant")

    /** Schreibt Warnungen über den Projektlogger; optionale Ursachen bleiben im Log nachvollziehbar. */
    fun logWarning(message: String, cause: Throwable? = null) {
        logger.log(Level.WARNING, message, cause)
    }

    /** Schreibt in eine eindeutige Nachbardatei und ersetzt das Ziel atomar, soweit das Dateisystem es unterstützt. */
    @Synchronized
    fun writeAtomically(path: Path, content: String) {
        val target = path.toAbsolutePath().normalize()
        val directory = target.parent ?: Path.of(".")
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, ".${target.fileName}.", ".tmp")
        try {
            Files.writeString(temp, content, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
