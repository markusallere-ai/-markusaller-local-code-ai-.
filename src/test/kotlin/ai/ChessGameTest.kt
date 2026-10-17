package ai

import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Board
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChessGameTest {
    @Test
    fun `recognizes German and English chess requests without matching explanations`() {
        assertTrue(ChessRequest.matches("Lass eine Runde Schach spielen"))
        assertTrue(ChessRequest.matches("Let's play chess"))
        assertTrue(ChessRequest.matches("Can we play a game of chess?"))
        assertFalse(ChessRequest.matches("Erkläre mir die Schachregeln"))
    }

    @Test
    fun `starts with user as white and rejects illegal moves`() {
        val game = ChessGame()

        assertTrue(game.whiteToMove)
        assertEquals(Piece.WHITE_KING, game.pieceAt("e1"))
        assertFalse(game.playWhiteMove("e2", "e5"))
        assertTrue(game.playWhiteMove("e2", "e4"))
        assertFalse(game.whiteToMove)
    }

    @Test
    fun `computer replies with a legal move after a white move`() {
        val game = ChessGame()
        assertTrue(game.playWhiteMove("e2", "e4"))

        val computerMove = game.playComputerMove()

        assertNotNull(computerMove)
        assertEquals("e7e5", computerMove)
        assertTrue(computerMove in game.moves)
        assertTrue(game.whiteToMove)
    }

    @Test
    fun `selected opening guides the computer response`() {
        val openings = ChessOpening.load()
        val sicilian = openings.first { it.name.contains("Sizilian") }
        val game = ChessGame(openings)
        game.selectOpening(sicilian)

        assertTrue(game.playWhiteMove("e2", "e4"))
        assertEquals("c7c5", game.playComputerMove())
        assertEquals(sicilian, game.currentOpening())
    }

    @Test
    fun `computer chooses a legal move when the position leaves the opening book`() {
        val game = ChessGame()
        assertTrue(game.playWhiteMove("a2", "a3"))
        val expectedLegalMoves = Board().apply {
            doMove(legalMoves().first { it.toString() == "a2a3" })
        }.legalMoves().map { it.toString() }

        val computerMove = game.playComputerMove()

        assertNotNull(computerMove)
        assertTrue(computerMove in expectedLegalMoves)
    }

    @Test
    fun `opening bank is populated with legal lines`() {
        val openings = ChessOpening.load()

        assertTrue(openings.size >= 8)
        assertTrue(openings.any { it.name.contains("Sizilian") })
        assertTrue(openings.any { it.name.contains("London") })
        assertTrue(openings.any { it.name.contains("Damengambit") })
    }

    @Test
    fun `board uses standard square colors and visible white piece colors`() {
        assertFalse(ChessBoardPalette.isLightSquare('a', 1))
        assertTrue(ChessBoardPalette.isLightSquare('h', 1))
        assertEquals(
            java.awt.Color(30, 41, 59),
            ChessBoardPalette.pieceColor(Piece.WHITE_PAWN, lightSquare = true)
        )
        assertEquals(
            java.awt.Color(248, 250, 252),
            ChessBoardPalette.pieceColor(Piece.WHITE_PAWN, lightSquare = false)
        )
    }

    @Test
    fun `game snapshots are saved after moves without duplicate game records`() {
        val directory = Files.createTempDirectory("chess-game-store")
        val store = ChessGameStore(directory.resolve("chess_games.jsonl"))
        val game = ChessGame()
        assertTrue(game.playWhiteMove("e2", "e4"))
        store.save(game.snapshot())
        assertNotNull(game.playComputerMove())
        store.save(game.snapshot())

        val saved = store.load()

        assertEquals(1, saved.size)
        assertEquals(game.moves, saved.single().moves)
        assertEquals("*", saved.single().result)
    }

    @Test
    fun `resignation ends the game and stores the winner`() {
        val game = ChessGame()

        assertEquals("0-1", game.finishByResignation(Side.WHITE))
        assertTrue(game.isGameOver())
        assertEquals("Aufgegeben – Markusaller gewinnt.", game.snapshot().status)
        assertEquals("0-1", game.snapshot().result)
    }

    @Test
    fun `stockfish UCI centipawn evaluations are parsed`() {
        assertEquals(
            34,
            StockfishUci.parseEvaluationScore("info depth 14 score cp 34 nodes 1000 pv e2e4")
        )
        assertEquals(
            -125,
            StockfishUci.parseEvaluationScore("info depth 14 score cp -125 nodes 1000 pv e2e4")
        )
        assertEquals(null, StockfishUci.parseEvaluationScore("bestmove e2e4"))
    }

    @Test
    fun `stockfish mate scores retain mate distance and side`() {
        assertEquals(
            99_997,
            StockfishUci.parseEvaluationScore("info depth 14 score mate 3 nodes 1000 pv e2e4")
        )
        assertEquals(
            -99_995,
            StockfishUci.parseEvaluationScore("info depth 14 score mate -5 nodes 1000 pv e2e4")
        )
    }

    @Test
    fun `stockfish UCI process accepts position searches and closes cleanly`() {
        val javaName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", javaName)
        val fixtureClasses = Path.of(
            FakeStockfishEngine::class.java.protectionDomain.codeSource.location.toURI()
        ).toString()
        val command = listOf(
            javaExecutable.toString(),
            "-cp",
            fixtureClasses,
            FakeStockfishEngine::class.java.name
        )

        StockfishUci(command).use { engine ->
            val bestLine = engine.evaluate(Board().fen)
            val forcedLine = engine.evaluate(Board().fen, "d2d4")

            assertEquals(25, bestLine.centipawns)
            assertEquals("e2e4", bestLine.bestMove)
            assertEquals(25, forcedLine.centipawns)
            assertEquals("d2d4", forcedLine.bestMove)
        }
    }

    @Test
    fun `initial chess position has the standard twenty legal moves`() {
        assertEquals(20, Board().legalMoves().size)
        assertEquals(Side.WHITE, Board().sideToMove)
    }
}
