package ai

import com.github.bhlangonijr.chesslib.Side
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.border.EmptyBorder

@Suppress("MagicNumber")
internal class ChessAnalysisDialog(
    owner: JDialog,
    private val analysis: ChessGameAnalysis
) : JDialog(owner, "Partieanalyse (lokale Schätzung)", true) {
    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        contentPane = createContent()
        minimumSize = Dimension(720, 560)
        pack()
        setLocationRelativeTo(owner)
    }

    private fun createContent(): JPanel {
        val root = JPanel(BorderLayout(0, 14)).apply {
            background = BACKGROUND
            border = EmptyBorder(18, 18, 18, 18)
        }
        val summary = JPanel(BorderLayout(12, 0)).apply {
            background = BACKGROUND
            add(createPlayerCard("Weiß (du)", analysis.white), BorderLayout.WEST)
            add(createPlayerCard("Schwarz (Markusaller)", analysis.black), BorderLayout.EAST)
        }
        root.add(summary, BorderLayout.NORTH)
        val details = JTextArea(buildMoveReport()).apply {
            isEditable = false
            font = Font("Consolas", Font.PLAIN, 13)
            background = Color(30, 41, 59)
            foreground = Color(226, 232, 240)
            border = EmptyBorder(12, 12, 12, 12)
            caretPosition = 0
        }
        root.add(JScrollPane(details), BorderLayout.CENTER)
        root.add(
            JLabel(
                "<html>Stellungsbewertungen stammen von Stockfish. Genauigkeit, Zugkategorien " +
                    "und Genauigkeit sind abgeleitete Schätzwerte, keine offiziellen " +
                    "Chess.com-Wertungen. Eine Elo-Zahl lässt sich aus einer einzelnen Partie " +
                    "nicht seriös bestimmen.</html>"
            ).apply {
                foreground = Color(148, 163, 184)
                border = EmptyBorder(2, 0, 0, 0)
            },
            BorderLayout.SOUTH
        )
        return root
    }

    private fun createPlayerCard(title: String, player: ChessPlayerAnalysis): JPanel =
        JPanel(BorderLayout(0, 8)).apply {
            background = Color(30, 41, 59)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(71, 85, 105)),
                EmptyBorder(12, 14, 12, 14)
            )
            add(JLabel(title).apply {
                foreground = Color(56, 189, 248)
                font = Font("Segoe UI", Font.BOLD, 16)
            }, BorderLayout.NORTH)
            add(JLabel(
                "<html><b>Genauigkeit:</b> ${player.accuracy}%<br>" +
                    "<b>Ø Bewertungsverlust:</b> ${player.averageCentipawnLoss} cp</html>"
            ).apply {
                foreground = Color(248, 250, 252)
                font = Font("Segoe UI", Font.PLAIN, 14)
            }, BorderLayout.CENTER)
            add(JLabel(formatCounts(player)).apply {
                foreground = Color(203, 213, 225)
                font = Font("Segoe UI", Font.PLAIN, 12)
            }, BorderLayout.SOUTH)
        }

    private fun formatCounts(player: ChessPlayerAnalysis): String = buildString {
        append("<html>")
        DISPLAY_CATEGORIES.chunked(CATEGORIES_PER_ROW).forEachIndexed { index, row ->
            if (index > 0) append("<br>")
            append(row.joinToString("  ") { (category, label) ->
                "$label ${player.categoryCounts[category] ?: 0}"
            })
        }
        append("</html>")
    }

    private fun buildMoveReport(): String = buildString {
        appendLine("Zugbewertung durch Stockfish")
        appendLine()
        analysis.moves.forEach { move ->
            val side = if (move.side == Side.WHITE) "Weiß" else "Schwarz"
            appendLine(
                "${move.fullMove}. $side  ${move.coordinate.padEnd(6)} " +
                    "${CATEGORY_NAMES.getValue(move.category).padEnd(12)} " +
                    "Bewertungsverlust: ${move.centipawnLoss} cp"
            )
        }
        if (analysis.moves.isEmpty()) appendLine("Keine Züge zum Analysieren.")
    }

    companion object {
        private val BACKGROUND = Color(15, 23, 42)
        private val CATEGORY_NAMES = mapOf(
            ChessMoveCategory.BOOK to "Buch",
            ChessMoveCategory.BRILLIANT to "Brillant*",
            ChessMoveCategory.GREAT to "Großartig",
            ChessMoveCategory.BEST to "Bester Zug",
            ChessMoveCategory.EXCELLENT to "Excellent",
            ChessMoveCategory.GOOD to "Gut",
            ChessMoveCategory.INACCURACY to "Ungenau",
            ChessMoveCategory.MISTAKE to "Fehler",
            ChessMoveCategory.BLUNDER to "Patzer"
        )
        private val DISPLAY_CATEGORIES = listOf(
            ChessMoveCategory.BOOK to "Buch",
            ChessMoveCategory.BRILLIANT to "Brillant",
            ChessMoveCategory.GREAT to "Großartig",
            ChessMoveCategory.BEST to "Bester Zug",
            ChessMoveCategory.EXCELLENT to "Excellent",
            ChessMoveCategory.GOOD to "Gut",
            ChessMoveCategory.INACCURACY to "Ungenau",
            ChessMoveCategory.MISTAKE to "Fehler",
            ChessMoveCategory.BLUNDER to "Patzer"
        )
        private const val CATEGORIES_PER_ROW = 3
    }
}
