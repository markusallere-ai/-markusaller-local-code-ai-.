package ai

import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.FlowLayout
import java.awt.GridLayout
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.JOptionPane
import javax.swing.JFileChooser
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.border.EmptyBorder
import javax.swing.filechooser.FileNameExtensionFilter

@Suppress("MagicNumber", "TooManyFunctions")
internal class ChessGameDialog(owner: JFrame?) : JDialog(owner, "Schach gegen Markusaller", true) {
    private val game = ChessGame()
    private val gameStore = ChessGameStore()
    private val boardButtons = mutableMapOf<String, JButton>()
    private val statusLabel = JLabel()
    private val moveListModel = DefaultListModel<String>()
    private val openingDetail = JTextArea()
    private val openings = ChessOpening.load()
    private val selectedSquares = mutableSetOf<String>()
    private var selectedSquare: String? = null
    private val analyzeButton = JButton("Spiel analysieren")

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        minimumSize = Dimension(900, 650)
        contentPane.add(buildContent())
        pack()
        refreshBoard()
        setLocationRelativeTo(owner)
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(event: java.awt.event.WindowEvent?) {
                persistGame()
            }
        })
    }

    private fun buildContent(): JPanel {
        return JPanel(BorderLayout(18, 0)).apply {
            background = Color(15, 23, 42)
            border = EmptyBorder(18, 18, 18, 18)
            val boardContainer = JPanel(BorderLayout(0, 10)).apply {
                background = Color(15, 23, 42)
                add(createBoardGrid(), BorderLayout.CENTER)
                add(createAnalyzeButton(), BorderLayout.SOUTH)
            }
            add(boardContainer, BorderLayout.CENTER)
            add(createSidePanel(), BorderLayout.EAST)
        }
    }

    private fun createAnalyzeButton(): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            background = Color(15, 23, 42)
            analyzeButton.isVisible = false
            analyzeButton.isEnabled = false
            analyzeButton.addActionListener { showAnalysis() }
            add(analyzeButton)
        }

    private fun createBoardGrid(): JPanel {
        val grid = JPanel(GridLayout(8, 8)).apply {
            preferredSize = Dimension(560, 560)
            border = BorderFactory.createLineBorder(Color(203, 213, 225), 2)
        }
        for (rank in 8 downTo 1) {
            for (file in 'a'..'h') {
                val square = "$file$rank"
                val button = createSquareButton(square)
                boardButtons[square] = button
                grid.add(button)
            }
        }
        return grid
    }

    private fun createSquareButton(square: String) = JButton().apply {
        font = Font("Segoe UI Symbol", Font.PLAIN, 38)
        isFocusPainted = false
        margin = java.awt.Insets(0, 0, 0, 0)
        toolTipText = square
        addActionListener { handleSquareClick(square) }
    }

    private fun createSidePanel(): JPanel {
        val sidePanel = JPanel(BorderLayout(0, 10)).apply {
            background = Color(15, 23, 42)
            preferredSize = Dimension(300, 0)
        }
        statusLabel.foreground = Color(248, 250, 252)
        statusLabel.font = Font("Segoe UI", Font.BOLD, 16)
        val topLabels = JPanel(GridLayout(1, 1)).apply {
            background = sidePanel.background
            add(statusLabel)
        }

        val panels = createGamePanels(sidePanel.background)
        sidePanel.add(topLabels, BorderLayout.NORTH)
        sidePanel.add(panels, BorderLayout.CENTER)

        val newGameButton = JButton("Neues Spiel").apply {
            addActionListener {
                dispose()
                ChessGameDialog(owner as? JFrame).isVisible = true
            }
        }
        val resignButton = JButton("Aufgeben").apply {
            addActionListener {
                game.finishByResignation(Side.WHITE)
                refreshBoard()
                persistGame()
            }
        }
        val buttons = JPanel(GridLayout(1, 2, 8, 0)).apply {
            background = sidePanel.background
            add(newGameButton)
            add(resignButton)
        }
        sidePanel.add(buttons, BorderLayout.SOUTH)
        return sidePanel
    }

    private fun createGamePanels(background: Color): JPanel {
        val moveList = JList(moveListModel).apply {
            this.background = Color(30, 41, 59)
            foreground = Color(226, 232, 240)
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            border = EmptyBorder(6, 8, 6, 8)
        }
        val openingListModel = DefaultListModel<ChessOpening>().apply {
            openings.forEach(::addElement)
        }
        val openingList = JList(openingListModel).apply {
            this.background = Color(30, 41, 59)
            foreground = Color(226, 232, 240)
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 7
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: javax.swing.JList<*>,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): java.awt.Component {
                    val label = super.getListCellRendererComponent(
                        list,
                        (value as? ChessOpening)?.name ?: value,
                        index,
                        isSelected,
                        cellHasFocus
                    ) as JLabel
                    label.border = EmptyBorder(5, 8, 5, 8)
                    return label
                }
            }
            addListSelectionListener { event ->
                if (!event.valueIsAdjusting) {
                    selectedValue?.let { opening ->
                        game.selectOpening(opening)
                        openingDetail.text = "${opening.description}\n\n${opening.notation}"
                    }
                }
            }
        }
        openingDetail.apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            this.background = Color(30, 41, 59)
            foreground = Color(203, 213, 225)
            font = Font("Segoe UI", Font.PLAIN, 13)
            border = EmptyBorder(8, 8, 8, 8)
        }
        return JPanel(GridLayout(3, 1, 0, 10)).apply {
            this.background = background
            add(section("Züge", JScrollPane(moveList)))
            add(section("Eröffnungsbank", JScrollPane(openingList)))
            add(section("Eröffnungsdetails", JScrollPane(openingDetail)))
        }
    }

    private fun section(title: String, component: java.awt.Component): JPanel =
        JPanel(BorderLayout(0, 5)).apply {
            background = Color(15, 23, 42)
            add(JLabel(title, SwingConstants.LEFT).apply {
                foreground = Color(148, 163, 184)
                font = Font("Segoe UI", Font.BOLD, 12)
            }, BorderLayout.NORTH)
            add(component, BorderLayout.CENTER)
        }

    private fun handleSquareClick(square: String) {
        if (game.isGameOver() || !game.whiteToMove) return
        val currentSelection = selectedSquare
        if (currentSelection != null && square in selectedSquares) {
            val promotion = choosePromotion(currentSelection, square)
            if (promotion != null && game.playWhiteMove(currentSelection, square, promotion)) {
                selectedSquare = null
                selectedSquares.clear()
                if (!game.isGameOver()) game.playComputerMove()
                refreshBoard()
                persistGame()
                return
            }
        }

        if (game.pieceAt(square).name.startsWith("WHITE_")) {
            selectedSquare = square
            selectedSquares.clear()
            selectedSquares.addAll(game.legalDestinations(square))
        } else {
            selectedSquare = null
            selectedSquares.clear()
        }
        refreshBoard()
    }

    private fun choosePromotion(from: String, to: String): Piece? {
        val options = game.promotionOptions(from, to)
        return if (options.isEmpty()) {
            Piece.WHITE_QUEEN
        } else {
            val labels = options.map { it.name.removePrefix("WHITE_") }.toTypedArray()
            val selected = javax.swing.JOptionPane.showInputDialog(
                this,
                "Zu welcher Figur soll der Bauer werden?",
                "Umwandlung",
                javax.swing.JOptionPane.QUESTION_MESSAGE,
                null,
                labels,
                labels.first()
            ) as? String
            options.getOrNull(labels.indexOf(selected))
        }
    }

    private fun refreshBoard() {
        val gameOver = game.isGameOver()
        for (rank in 8 downTo 1) {
            for (file in 'a'..'h') {
                val square = "$file$rank"
                val button = boardButtons.getValue(square)
                val piece = game.pieceAt(square)
                button.text = PIECE_GLYPHS.getValue(piece)
                val isLightSquare = ChessBoardPalette.isLightSquare(file, rank)
                button.background = when {
                    square == selectedSquare -> Color(250, 204, 21)
                    square in selectedSquares -> Color(74, 124, 89)
                    isLightSquare -> Color(238, 238, 210)
                    else -> Color(118, 150, 86)
                }
                button.foreground = ChessBoardPalette.pieceColor(
                    piece,
                    isLightSquare || square == selectedSquare || square in selectedSquares
                )
                button.isEnabled = !gameOver && game.whiteToMove
            }
        }
        statusLabel.text = game.status()
        analyzeButton.isVisible = gameOver
        analyzeButton.isEnabled = gameOver && game.moves.isNotEmpty()
        moveListModel.clear()
        game.moves.chunked(2).forEachIndexed { index, pair ->
            moveListModel.addElement("${index + 1}. ${pair.joinToString("  ")}")
        }
    }

    private fun persistGame() {
        if (game.moves.isEmpty() && !game.isGameOver()) return
        try {
            gameStore.save(game.snapshot())
        } catch (exception: java.io.IOException) {
            FileIo.logWarning("Schachpartie konnte nicht gespeichert werden.", exception)
            statusLabel.text = "Speicherfehler: Schachpartie konnte nicht gespeichert werden."
        }

    }

    private fun showAnalysis() {
        val enginePath = StockfishEngineLocator.find() ?: chooseStockfishEngine() ?: return
        analyzeWithStockfish(enginePath)
    }

    private fun chooseStockfishEngine(): Path? {
        val instructions = """
            Für eine echte Analyse wird Stockfish benötigt.
            Lade Stockfish von https://stockfishchess.org/download/ herunter.
            Wähle anschließend die Engine-Datei aus (unter Windows meist stockfish-windows-x86-64-avx2.exe).
            Der Pfad wird lokal für spätere Analysen gespeichert.
        """.trimIndent()
        val choice = JOptionPane.showConfirmDialog(
            this,
            instructions,
            "Stockfish benötigt",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.INFORMATION_MESSAGE
        )
        return if (choice == JOptionPane.OK_OPTION) {
            val chooser = JFileChooser().apply {
                dialogTitle = "Stockfish-Engine auswählen"
                isAcceptAllFileFilterUsed = true
                addChoosableFileFilter(FileNameExtensionFilter("Windows-Engine (*.exe)", "exe"))
            }
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                val selected = chooser.selectedFile.toPath()
                try {
                    StockfishEngineLocator.remember(selected)
                    selected
                } catch (exception: IllegalArgumentException) {
                    JOptionPane.showMessageDialog(
                        this,
                        exception.message ?: "Die ausgewählte Datei ist keine ausführbare Stockfish-Engine.",
                        "Ungültige Engine-Datei",
                        JOptionPane.ERROR_MESSAGE
                    )
                    null
                }
            } else {
                null
            }
        } else {
            null
        }
    }

    private fun analyzeWithStockfish(enginePath: Path) {
        analyzeButton.isEnabled = false
        statusLabel.text = "Stockfish startet …"
        val worker = object : SwingWorker<ChessGameAnalysis, Unit>() {
            override fun doInBackground(): ChessGameAnalysis =
                game.analyze(StockfishGameAnalyzer(), enginePath) { completed, total ->
                    setProgress(if (total == 0) 100 else completed * 100 / total)
                }

            override fun done() {
                try {
                    ChessAnalysisDialog(this@ChessGameDialog, get()).isVisible = true
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                    statusLabel.text = "Stockfish-Analyse wurde unterbrochen."
                } catch (exception: java.util.concurrent.ExecutionException) {
                    val cause = exception.cause ?: exception
                    FileIo.logWarning("Stockfish-Analyse konnte nicht abgeschlossen werden.", cause)
                    JOptionPane.showMessageDialog(
                        this@ChessGameDialog,
                        "Stockfish-Analyse fehlgeschlagen: ${cause.message ?: cause.javaClass.simpleName}",
                        "Analysefehler",
                        JOptionPane.ERROR_MESSAGE
                    )
                    statusLabel.text = "Stockfish-Analyse fehlgeschlagen."
                } finally {
                    analyzeButton.isEnabled = game.isGameOver() && game.moves.isNotEmpty()
                }
            }
        }
        worker.addPropertyChangeListener { event ->
            if (event.propertyName == "progress") {
                statusLabel.text = "Stockfish analysiert: ${event.newValue}%"
            }
        }
        worker.execute()
    }

    companion object {
        private val PIECE_GLYPHS = mapOf(
            Piece.WHITE_KING to "♔",
            Piece.WHITE_QUEEN to "♕",
            Piece.WHITE_ROOK to "♖",
            Piece.WHITE_BISHOP to "♗",
            Piece.WHITE_KNIGHT to "♘",
            Piece.WHITE_PAWN to "♙",
            Piece.BLACK_KING to "♚",
            Piece.BLACK_QUEEN to "♛",
            Piece.BLACK_ROOK to "♜",
            Piece.BLACK_BISHOP to "♝",
            Piece.BLACK_KNIGHT to "♞",
            Piece.BLACK_PAWN to "♟",
            Piece.NONE to ""
        )

        fun open(owner: JFrame? = null) {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater { open(owner) }
                return
            }
            ChessGameDialog(owner).isVisible = true
        }
    }

}

@Suppress("MagicNumber")
internal object ChessBoardPalette {
    fun isLightSquare(file: Char, rank: Int): Boolean = (file.code + rank) % 2 != 0

    fun pieceColor(piece: Piece, lightSquare: Boolean): Color = when {
        piece == Piece.NONE -> Color.BLACK
        piece.name.startsWith("WHITE_") && lightSquare -> Color(30, 41, 59)
        piece.name.startsWith("WHITE_") -> Color(248, 250, 252)
        lightSquare -> Color(15, 23, 42)
        else -> Color(0, 0, 0)
    }
}
