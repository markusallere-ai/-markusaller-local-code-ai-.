package ai

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

internal data class StockfishEvaluation(val centipawns: Int, val bestMove: String?)

internal class StockfishGameAnalyzer {
    fun analyze(
        plies: List<ChessPly>,
        stockfishPath: Path,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<ChessMoveReview> {
        require(Files.isRegularFile(stockfishPath)) { "Stockfish-Datei nicht gefunden: $stockfishPath" }
        return StockfishUci(stockfishPath).use { engine ->
            plies.mapIndexed { index, ply ->
                onProgress(index + FIRST_PLY_INDEX, plies.size)
                analyzePly(engine, index, ply)
            }
        }
    }

    private fun analyzePly(engine: StockfishUci, index: Int, ply: ChessPly): ChessMoveReview {
        if (ply.book) {
            return ChessMoveReview(index / PLIES_PER_MOVE + FIRST_FULL_MOVE, ply.side, ply.coordinate,
                ChessMoveCategory.BOOK, NO_EVAL_LOSS)
        }

        val best = engine.evaluate(ply.fen)
        val played = engine.evaluate(ply.fen, ply.coordinate)
        val loss = (best.centipawns - played.centipawns).coerceAtLeast(NO_EVAL_LOSS)
        val category = classifyMove(ply, loss, best)
        return ChessMoveReview(
            index / PLIES_PER_MOVE + FIRST_FULL_MOVE,
            ply.side,
            ply.coordinate,
            category,
            loss
        )
    }

    private fun classifyMove(ply: ChessPly, loss: Int, best: StockfishEvaluation): ChessMoveCategory =
        when {
            loss <= GREAT_LOSS && best.bestMove == ply.coordinate && isSacrificingBestMove(ply) ->
                ChessMoveCategory.BRILLIANT
            loss <= GREAT_LOSS && best.bestMove == ply.coordinate -> ChessMoveCategory.GREAT
            loss <= BEST_LOSS -> ChessMoveCategory.BEST
            loss <= EXCELLENT_LOSS -> ChessMoveCategory.EXCELLENT
            loss <= GOOD_LOSS -> ChessMoveCategory.GOOD
            loss <= INACCURACY_LOSS -> ChessMoveCategory.INACCURACY
            loss <= MISTAKE_LOSS -> ChessMoveCategory.MISTAKE
            else -> ChessMoveCategory.BLUNDER
        }

    private fun isSacrificingBestMove(ply: ChessPly): Boolean {
        val board = Board().apply { loadFromFen(ply.fen) }
        val move = board.legalMoves().firstOrNull { it.toString() == ply.coordinate }
        val movedPiece = move?.let { board.getPiece(it.from) } ?: Piece.NONE
        return if (
            move == null || movedPiece == Piece.NONE || movedPiece.pieceType == PieceType.PAWN
        ) {
            false
        } else {
            board.doMove(move)
            try {
                board.legalMoves().any { reply ->
                    reply.to == move.to &&
                        board.getPiece(reply.to).pieceSide == ply.side &&
                        MATERIAL_VALUES.getValue(board.getPiece(reply.to).pieceType) >=
                        MATERIAL_VALUES.getValue(movedPiece.pieceType) - SACRIFICE_MARGIN
                }
            } finally {
                board.undoMove()
            }
        }
    }

    companion object {
        private const val FIRST_PLY_INDEX = 1
        private const val FIRST_FULL_MOVE = 1
        private const val PLIES_PER_MOVE = 2
        private const val NO_EVAL_LOSS = 0
        private const val GREAT_LOSS = 2
        private const val BEST_LOSS = 5
        private const val EXCELLENT_LOSS = 20
        private const val GOOD_LOSS = 50
        private const val INACCURACY_LOSS = 100
        private const val MISTAKE_LOSS = 200
        private const val SACRIFICE_MARGIN = 50
        private val MATERIAL_VALUES = mapOf(
            PieceType.PAWN to 100,
            PieceType.KNIGHT to 320,
            PieceType.BISHOP to 330,
            PieceType.ROOK to 500,
            PieceType.QUEEN to 900,
            PieceType.KING to 0,
            PieceType.NONE to 0
        )
    }
}

internal class StockfishUci private constructor(
    private val process: Process,
    private val output: LinkedBlockingQueue<String>
) : Closeable {
    private val writer = process.outputStream.bufferedWriter()

    constructor(executable: Path) : this(listOf(executable.toAbsolutePath().toString()))

    internal constructor(command: List<String>) : this(
        ProcessBuilder(command)
            .redirectErrorStream(true)
            .start(),
        LinkedBlockingQueue()
    ) {
        startOutputReader(process, output)
        try {
            send("uci")
            awaitLine("uciok")
            send("isready")
            awaitLine("readyok")
        } catch (exception: java.io.IOException) {
            process.destroyForcibly()
            throw exception
        } catch (exception: IllegalStateException) {
            process.destroyForcibly()
            throw exception
        } catch (exception: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw exception
        }
    }

    fun evaluate(fen: String, forcedMove: String? = null): StockfishEvaluation {
        send("position fen $fen")
        val command = if (forcedMove == null) {
            "go depth $ANALYSIS_DEPTH"
        } else {
            "go depth $ANALYSIS_DEPTH searchmoves $forcedMove"
        }
        send(command)
        var score: Int? = null
        var bestMove: String? = null
        while (bestMove == null) {
            val line = nextLine()
            parseEvaluationScore(line)?.let { score = it }
            if (line.startsWith("bestmove ")) {
                bestMove = line.split(WHITESPACE).getOrNull(BEST_MOVE_COLUMN)
                    ?.takeUnless { it == NO_MOVE }
            }
        }
        return StockfishEvaluation(
            score ?: error("Stockfish hat für die Position keinen auswertbaren Score geliefert."),
            bestMove
        )
    }

    override fun close() {
        if (process.isAlive) {
            send("quit")
            if (!process.waitFor(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy()
            }
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun send(command: String) {
        check(process.isAlive) { "Stockfish wurde unerwartet beendet." }
        writer.write(command)
        writer.newLine()
        writer.flush()
    }

    private fun awaitLine(expected: String) {
        while (true) {
            val line = nextLine()
            if (line == expected) return
        }
    }

    private fun nextLine(): String {
        val line = output.poll(ENGINE_RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            ?: error("Stockfish antwortet nicht innerhalb des Zeitlimits.")
        check(line != OUTPUT_EOF) { "Stockfish-Ausgabe wurde unerwartet geschlossen." }
        return line
    }

    companion object {
        private const val ANALYSIS_DEPTH = 14
        private const val MATE_SCORE = 100_000
        private const val SHUTDOWN_TIMEOUT_SECONDS = 2L
        private const val ENGINE_RESPONSE_TIMEOUT_SECONDS = 180L
        private const val BEST_MOVE_COLUMN = 1
        private const val NO_MOVE = "(none)"
        private const val OUTPUT_EOF = "\u0000"
        private val WHITESPACE = Regex("\\s+")
        private val SCORE_PATTERN = Regex("\\bscore\\s+(cp|mate)\\s+(-?\\d+)")

        internal fun parseEvaluationScore(line: String): Int? {
            val match = if (line.startsWith("info ")) SCORE_PATTERN.find(line) else null
            val value = match?.groupValues?.get(2)?.toIntOrNull()
            return when {
                match == null || value == null -> null
                match.groupValues[1] == "cp" -> value
                match.groupValues[1] == "mate" ->
                    if (value >= 0) MATE_SCORE - value else -MATE_SCORE - value
                else -> null
            }
        }

        private fun startOutputReader(process: Process, output: LinkedBlockingQueue<String>) {
            Thread({
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                        lines.forEach { output.put(it) }
                    }
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (exception: java.io.IOException) {
                    if (process.isAlive) FileIo.logWarning("Stockfish-Ausgabe konnte nicht gelesen werden.", exception)
                } finally {
                    output.offer(OUTPUT_EOF)
                }
            }, "stockfish-output-reader").apply {
                isDaemon = true
                start()
            }
        }
    }
}

internal object StockfishEngineLocator {
    private const val STOCKFISH_PATH_KEY = "stockfish.path"
    private val preferences = Preferences.userNodeForPackage(StockfishEngineLocator::class.java)

    fun find(): Path? {
        val configured = listOfNotNull(
            preferences.get(STOCKFISH_PATH_KEY, null),
            System.getProperty("markusaller.stockfish.path"),
            System.getenv("STOCKFISH_PATH")
        )
        val names = if (isWindows()) listOf("stockfish.exe", "stockfish") else listOf("stockfish")
        val fromPath = System.getenv("PATH").orEmpty()
            .split(System.getProperty("path.separator"))
            .flatMap { directory -> names.map { Path.of(directory).resolve(it) } }
        return (configured.map { Path.of(it) } + fromPath)
            .firstOrNull(::isUsable)
    }

    fun remember(path: Path) {
        require(isUsable(path)) { "Die ausgewählte Stockfish-Datei ist nicht ausführbar: $path" }
        preferences.put(STOCKFISH_PATH_KEY, path.toAbsolutePath().normalize().toString())
    }

    fun isUsable(path: Path): Boolean = Files.isRegularFile(path) && (isWindows() || Files.isExecutable(path))

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
