package ai

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal class ChessGameStore(
    private val path: Path = AssistantConfig().chessGamesFile()
) {
    @Synchronized
    fun save(game: SavedChessGame) {
        val games = load().filterNot { it.id == game.id } + game
        val content = games.joinToString(
            separator = System.lineSeparator(),
            postfix = System.lineSeparator(),
            transform = ::encode
        )
        FileIo.writeAtomically(path, content)
    }

    @Synchronized
    fun load(): List<SavedChessGame> {
        if (Files.notExists(path)) return emptyList()
        return Files.readAllLines(path).mapNotNull(::decode)
    }

    private fun encode(game: SavedChessGame): String =
        JsonCodec.compactJson.encodeToString(
            ChessGameDto.serializer(),
            ChessGameDto(game.id, game.timestamp, game.result, game.moves, game.status)
        )

    private fun decode(line: String): SavedChessGame? {
        if (line.isBlank()) return null
        return try {
            val value = JsonCodec.compactJson.decodeFromString(ChessGameDto.serializer(), line)
            SavedChessGame(value.id, value.timestamp, value.result, value.moves, value.status)
        } catch (exception: SerializationException) {
            FileIo.logWarning("Gespeichertes Schachspiel konnte nicht gelesen werden.", exception)
            null
        }
    }
}
