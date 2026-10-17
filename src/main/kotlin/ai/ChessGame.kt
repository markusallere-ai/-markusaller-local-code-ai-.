package ai

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.move.Move
import java.time.Instant
import java.nio.file.Path
import java.util.UUID

internal data class ChessPly(val fen: String, val coordinate: String, val side: Side, val book: Boolean)

internal enum class ChessMoveCategory {
    BOOK, BRILLIANT, GREAT, BEST, EXCELLENT, GOOD, INACCURACY, MISTAKE, BLUNDER
}

internal data class ChessMoveReview(
    val fullMove: Int,
    val side: Side,
    val coordinate: String,
    val category: ChessMoveCategory,
    val centipawnLoss: Int
)

internal data class ChessPlayerAnalysis(
    val accuracy: Int,
    val averageCentipawnLoss: Int,
    val categoryCounts: Map<ChessMoveCategory, Int>
)

internal data class ChessGameAnalysis(
    val white: ChessPlayerAnalysis,
    val black: ChessPlayerAnalysis,
    val moves: List<ChessMoveReview>
)

internal data class SavedChessGame(
    val id: String,
    val timestamp: String,
    val result: String,
    val moves: List<String>,
    val status: String
)

internal data class ChessOpening(
    val name: String,
    val description: String,
    val moves: List<String>,
    val notation: String
) {
    companion object {
        fun load(): List<ChessOpening> {
            val stream = ChessOpening::class.java.getResourceAsStream("/ai/chess-openings.tsv")
                ?: error("Die Schach-Eröffnungsdatei /ai/chess-openings.tsv fehlt.")
            return stream.bufferedReader().useLines { lines ->
                lines.filter(String::isNotBlank).mapIndexed { index, line ->
                    val columns = line.split('|')
                    require(columns.size == OPENING_COLUMN_COUNT) {
                        "Ungültiger Eintrag in chess-openings.tsv, " +
                            "Zeile ${index + FIRST_OPENING_LINE_NUMBER}."
                    }
                    val moves = columns[MOVES_COLUMN].trim().split(Regex("\\s+"))
                    require(moves.isNotEmpty() && moves.all(UCI_MOVE::matches)) {
                        "Ungültige Zugfolge in chess-openings.tsv, " +
                            "Zeile ${index + FIRST_OPENING_LINE_NUMBER}."
                    }
                    val opening = ChessOpening(
                        columns[NAME_COLUMN].trim(),
                        columns[DESCRIPTION_COLUMN].trim(),
                        moves,
                        columns[NOTATION_COLUMN].trim()
                    )
                    validateMoves(opening, index + FIRST_OPENING_LINE_NUMBER)
                    opening
                }.toList()
            }
        }

        private fun validateMoves(opening: ChessOpening, lineNumber: Int) {
            val board = Board()
            opening.moves.forEach { coordinate ->
                val move = board.legalMoves().firstOrNull { it.toString() == coordinate }
                    ?: error(
                        "Illegaler Eröffnungszug '$coordinate' in chess-openings.tsv, Zeile $lineNumber."
                    )
                check(board.doMove(move)) {
                    "Eröffnungszug '$coordinate' konnte in chess-openings.tsv, " +
                        "Zeile $lineNumber nicht ausgeführt werden."
                }
            }
        }

        private const val NAME_COLUMN = 0
        private const val DESCRIPTION_COLUMN = 1
        private const val MOVES_COLUMN = 2
        private const val NOTATION_COLUMN = 3
        private const val OPENING_COLUMN_COUNT = 4
        private const val FIRST_OPENING_LINE_NUMBER = 1
        private val UCI_MOVE = Regex("[a-h][1-8][a-h][1-8][qrbn]?")
    }
}

@Suppress("TooManyFunctions")
internal class ChessGame(
    private val openings: List<ChessOpening> = ChessOpening.load()
) {
    private val board = Board()
    private val playedMoves = mutableListOf<String>()
    private val plies = mutableListOf<ChessPly>()
    private val gameId = UUID.randomUUID().toString()
    private val startedAt = Instant.now().toString()
    private var preferredOpening: ChessOpening? = null
    private var savedResult: String? = null
    private var resignationResult: String? = null

    val moves: List<String>
        get() = playedMoves.toList()

    val isSaved: Boolean
        get() = savedResult != null

    val whiteToMove: Boolean
        get() = board.sideToMove == Side.WHITE

    fun pieceAt(square: String): Piece = board.getPiece(square.toChessSquare())

    fun selectOpening(opening: ChessOpening) {
        preferredOpening = opening
    }

    fun legalDestinations(from: String): Set<String> = board.legalMoves()
        .filter { it.from.toString().equals(from, ignoreCase = true) }
        .map { it.to.toString().lowercase() }
        .toSet()

    fun promotionOptions(from: String, to: String): List<Piece> = board.legalMoves()
        .filter {
            it.from.toString().equals(from, ignoreCase = true) &&
                it.to.toString().equals(to, ignoreCase = true) &&
                it.promotion != Piece.NONE
        }
        .map { it.promotion }
        .distinct()

    fun playWhiteMove(from: String, to: String, promotion: Piece = Piece.WHITE_QUEEN): Boolean {
        if (!whiteToMove || isGameOver()) return false
        val move = board.legalMoves().firstOrNull {
            it.from.toString().equals(from, ignoreCase = true) &&
                it.to.toString().equals(to, ignoreCase = true) &&
                (it.promotion == Piece.NONE || it.promotion == promotion)
        }
        return move?.let(::applyMove) ?: false
    }

    fun playComputerMove(): String? {
        if (whiteToMove || isGameOver()) return null
        val legalMoves = board.legalMoves()
        val move = legalMoves.firstOrNull()?.let { openingMove(legalMoves) ?: chooseSearchMove(legalMoves) }
        if (move != null) applyMove(move)
        return move?.toString()
    }

    fun finishByResignation(resigningSide: Side): String {
        val result = if (resigningSide == Side.WHITE) "0-1" else "1-0"
        resignationResult = result
        saveResult(result)
        return result
    }

    fun saveIfFinished(): SavedChessGame? {
        if (isGameOver()) {
            val result = when {
                board.isMated && board.sideToMove == Side.WHITE -> "0-1"
                board.isMated -> "1-0"
                else -> "1/2-1/2"
            }
            saveResult(result)
        }
        return savedResult?.let(::savedGame)
    }

    fun saveAsUnfinished(): SavedChessGame? {
        if (savedResult == null && playedMoves.isNotEmpty()) saveResult("*")
        return savedResult?.let(::savedGame)
    }

    fun snapshot(): SavedChessGame {
        saveIfFinished()
        return savedGame(savedResult ?: "*")
    }

    fun analyze(
        analyzer: StockfishGameAnalyzer,
        stockfishPath: Path,
        onProgress: (Int, Int) -> Unit
    ): ChessGameAnalysis {
        val reviews = analyzer.analyze(plies, stockfishPath, onProgress)
        return ChessGameAnalysis(
            analyzePlayer(reviews.filter { it.side == Side.WHITE }),
            analyzePlayer(reviews.filter { it.side == Side.BLACK }),
            reviews
        )
    }

    internal fun analysisPositions(): List<ChessPly> = plies.toList()

    fun status(): String = when {
        resignationResult == "0-1" -> "Aufgegeben – Markusaller gewinnt."
        resignationResult == "1-0" -> "Aufgegeben – du gewinnst."
        board.isMated -> "Matt – ${if (whiteToMove) "Schwarz" else "Weiß"} gewinnt."
        board.isDraw -> "Unentschieden."
        board.legalMoves().isEmpty() -> "Patt – unentschieden."
        board.isKingAttacked -> "Schach."
        else -> if (whiteToMove) "Du bist am Zug (Weiß)." else "Markusaller denkt …"
    }

    fun isGameOver(): Boolean =
        resignationResult != null || board.isMated || board.isDraw || board.legalMoves().isEmpty()

    fun currentOpening(): ChessOpening? {
        preferredOpening?.let { opening ->
            return opening.takeIf(::matchesCurrentLine)
        }
        return openings.firstOrNull(::matchesCurrentLine)
    }

    private fun matchesCurrentLine(opening: ChessOpening): Boolean =
        playedMoves.size <= opening.moves.size && playedMoves == opening.moves.take(playedMoves.size)

    private fun applyMove(move: Move): Boolean {
        val mover = board.sideToMove
        val fen = board.fen
        val bookMove = openingMove(board.legalMoves()) == move
        if (!board.doMove(move)) return false
        plies += ChessPly(fen, move.toString(), mover, bookMove)
        playedMoves += move.toString()
        saveIfFinished()
        return true
    }

    private fun search(position: Board, depth: Int, alphaStart: Int, beta: Int): Int {
        val terminalScore = when {
            position.isMated -> -CHECKMATE_SCORE
            position.isDraw -> 0
            else -> null
        }
        val legalMoves = position.legalMoves()
        return terminalScore ?: when {
            legalMoves.isEmpty() && position.isKingAttacked -> -CHECKMATE_SCORE
            legalMoves.isEmpty() -> 0
            depth == 0 -> evaluate(position)
            else -> searchMoves(position, legalMoves, depth, alphaStart, beta)
        }
    }

    private fun searchMoves(
        position: Board,
        legalMoves: List<Move>,
        depth: Int,
        alphaStart: Int,
        beta: Int
    ): Int {
        var alpha = alphaStart
        var bestScore = -INFINITY
        for (move in legalMoves) {
            position.doMove(move)
            val score = -search(position, depth - 1, -beta, -alpha)
            position.undoMove()
            bestScore = maxOf(bestScore, score)
            alpha = maxOf(alpha, score)
            if (alpha >= beta) break
        }
        return bestScore
    }

    private fun analyzePlayer(reviews: List<ChessMoveReview>): ChessPlayerAnalysis {
        val categoryCounts = ChessMoveCategory.entries.associateWith { category ->
            reviews.count { it.category == category }
        }
        val accuracy = if (reviews.isEmpty()) 0 else {
            (reviews.map { PERCENT_SCALE * Math.exp(-it.centipawnLoss / ACCURACY_SCALE) }.average())
                .toInt().coerceIn(MIN_ACCURACY, MAX_ACCURACY)
        }
        val averageCentipawnLoss = reviews
            .filter { it.category != ChessMoveCategory.BOOK }
            .map { it.centipawnLoss }
            .average()
            .takeIf(Double::isFinite)
            ?.toInt() ?: NO_EVAL_LOSS
        return ChessPlayerAnalysis(accuracy, averageCentipawnLoss, categoryCounts)
    }

    private fun evaluate(position: Board): Int {
        val material = Piece.values().filter { it != Piece.NONE }.sumOf { piece ->
            val value = MATERIAL_VALUES.getValue(piece.pieceType)
            val count = position.getPieceLocation(piece).size
            if (piece.pieceSide == position.sideToMove) value * count else -value * count
        }
        return material - if (position.isKingAttacked) CHECK_PENALTY else 0
    }

    private fun saveResult(result: String) {
        if (savedResult == null) savedResult = result
    }

    private fun savedGame(result: String) =
        SavedChessGame(gameId, startedAt, result, moves, status())

    private fun openingMove(legalMoves: List<Move>): Move? {
        val nextCoordinate = currentOpening()?.moves?.getOrNull(playedMoves.size) ?: return null
        return legalMoves.firstOrNull { it.toString() == nextCoordinate }
    }

    private fun chooseSearchMove(legalMoves: List<Move>): Move {
        var bestMove = legalMoves.first()
        var bestScore = Int.MIN_VALUE
        legalMoves.forEach { move ->
            board.doMove(move)
            val score = -negamax(SEARCH_DEPTH - 1, -INFINITY, INFINITY)
            board.undoMove()
            if (score > bestScore) {
                bestScore = score
                bestMove = move
            }
        }
        return bestMove
    }

    private fun negamax(depth: Int, alphaStart: Int, beta: Int): Int {
        val terminalScore = when {
            board.isMated -> -CHECKMATE_SCORE
            board.isDraw -> 0
            else -> null
        }
        val legalMoves = board.legalMoves()
        return terminalScore ?: when {
            legalMoves.isEmpty() && board.isKingAttacked -> -CHECKMATE_SCORE
            legalMoves.isEmpty() -> 0
            depth == 0 -> evaluatePosition()
            else -> searchMoves(legalMoves, depth, alphaStart, beta)
        }
    }

    private fun searchMoves(legalMoves: List<Move>, depth: Int, alphaStart: Int, beta: Int): Int {
        var alpha = alphaStart
        var bestScore = -INFINITY
        for (move in legalMoves) {
            board.doMove(move)
            val score = -negamax(depth - 1, -beta, -alpha)
            board.undoMove()
            if (score > bestScore) bestScore = score
            if (score > alpha) alpha = score
            if (alpha >= beta) break
        }
        return bestScore
    }

    private fun evaluatePosition(): Int {
        return evaluate(board)
    }

    private fun String.toChessSquare() = com.github.bhlangonijr.chesslib.Square.valueOf(uppercase())

    companion object {
        private const val SEARCH_DEPTH = 3
        private const val CHECKMATE_SCORE = 100_000
        private const val CHECK_PENALTY = 25
        private const val INFINITY = 1_000_000
        private const val ACCURACY_SCALE = 150.0
        private const val PERCENT_SCALE = 100.0
        private const val MIN_ACCURACY = 0
        private const val MAX_ACCURACY = 100
        private const val NO_EVAL_LOSS = 0
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

internal object ChessRequest {
    private val chessTerm = Regex("\\b(schach|schachpartie|schachspiel|chess)\\b")
    private val playTerm = Regex("\\b(spiel\\w*|runde|partie|play\\w*|game)\\b")

    fun matches(prompt: String): Boolean {
        val normalized = TextFold.fold(prompt)
        return chessTerm.containsMatchIn(normalized) && playTerm.containsMatchIn(normalized)
    }
}
