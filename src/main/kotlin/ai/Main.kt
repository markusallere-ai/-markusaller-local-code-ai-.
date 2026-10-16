package ai

import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.border.EmptyBorder

/** Startet Swing auf dem Event-Dispatch-Thread, wie es die UI-Thread-Regeln verlangen. */
fun main() {
    SwingUtilities.invokeLater { MarkusallerWindow().show() }
}

/** Baut und steuert die lokale Swing-Oberfläche für Chat, Verlauf und Wissensverwaltung. */
class MarkusallerWindow {
    private val assistant by lazy {
        CodeAssistant(AssistantConfig(writeBrainStateOnInit = false))
    }
    private lateinit var promptArea: JTextArea
    private lateinit var outputArea: JTextArea
    private lateinit var statusLabel: JLabel
    private lateinit var historyModel: DefaultListModel<String>
    private lateinit var historyList: JList<String>
    private lateinit var sendButton: JButton
    private lateinit var loadingTimer: Timer
    private var loadingStep = 0
    private var isProcessing = false

    private val historyStore = ConversationHistoryStore()
    private val historyEntries = mutableListOf<HistoryEntry>()
    private val controlsDuringConversation = mutableListOf<AbstractButton>()

    /** Fragt eine einmalige Legacy-Migration ab und zeigt anschließend das Hauptfenster. */
    fun show() {
        resolveLegacyStateMigration()
        val frame = JFrame("Markusaller")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.contentPane = createContent()
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                historyStore.save(historyEntries)
            }
        })
        frame.pack()
        frame.setExtendedState(JFrame.MAXIMIZED_BOTH)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        loadHistory()
        updateHistoryView()
        runBackgroundAction(
            runningMessage = "Lokale Daten werden geladen...",
            successMessage = "Bereit",
            task = { assistant.ensureInitialized() },
            onSuccess = {}
        )
    }

    /** Übernimmt bekannte Projektdateien nur nach expliziter Zustimmung des Nutzers. */
    private fun resolveLegacyStateMigration() {
        val migration = LegacyStateMigration(
            Path.of(".").toAbsolutePath().normalize(),
            AssistantConfig().storagePath
        )
        val files = migration.filesToMigrate()
        if (migration.hasDecision() || files.isEmpty()) return

        val fileNames = files.joinToString(System.lineSeparator()) { (source, _) ->
            source.fileName.toString()
        }
        val confirmed = JOptionPane.showConfirmDialog(
            null,
            "Alte Laufzeitdateien im aktuellen Projektordner gefunden:\n\n" +
                "$fileNames\n\nIn den lokalen Speicher ~/.markusaller kopieren?",
            "Alte lokale Daten übernehmen?",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        ) == JOptionPane.YES_OPTION
        val copied = migration.decideToMigrate(confirmed)
        if (confirmed) {
            JOptionPane.showMessageDialog(
                null,
                "${copied.size} Datei(en) wurden nach ~/.markusaller kopiert."
            )
        }
    }

    /** Erstellt den kompletten Fensterinhalt und registriert die UI-Aktionen. */
    private fun createContent(): JComponent {
        promptArea = JTextArea("Schreibe eine Nachricht oder Kotlin-Anfrage.")
        promptArea.font = Font("Segoe UI", Font.PLAIN, 14)
        promptArea.lineWrap = true
        promptArea.wrapStyleWord = true
        promptArea.border = BorderFactory.createEmptyBorder(14, 14, 14, 14)
        promptArea.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent?) {
                if (promptArea.text == "Schreibe eine Nachricht oder Kotlin-Anfrage.") {
                    promptArea.text = ""
                }
            }
            override fun focusLost(e: FocusEvent?) {
                if (promptArea.text.isBlank()) {
                    promptArea.text = "Schreibe eine Nachricht oder Kotlin-Anfrage."
                }
            }
        })
        styleTextArea(promptArea)

        outputArea = JTextArea()
        outputArea.font = Font("Consolas", Font.PLAIN, 14)
        outputArea.isEditable = false
        outputArea.lineWrap = true
        outputArea.wrapStyleWord = true
        styleTextArea(outputArea)
        outputArea.background = Color(10, 14, 26)

        statusLabel = JLabel("Bereit")
        statusLabel.font = Font("Segoe UI", Font.PLAIN, 12)
        statusLabel.foreground = Color(148, 163, 184)

        val titleLabel = JLabel("Markusaller")
        titleLabel.font = Font("Segoe UI", Font.BOLD, 32)
        titleLabel.foreground = Color(248, 250, 252)

        val subtitleLabel = JLabel("Lokaler Gesprächs- und Kotlin-Code-Assistent")
        subtitleLabel.font = Font("Segoe UI", Font.PLAIN, 14)
        subtitleLabel.foreground = Color(148, 163, 184)

        val headerPanel = JPanel()
        headerPanel.layout = BoxLayout(headerPanel, BoxLayout.Y_AXIS)
        headerPanel.isOpaque = false
        headerPanel.add(titleLabel)
        headerPanel.add(Box.createVerticalStrut(6))
        headerPanel.add(subtitleLabel)

        sendButton = styledButton("Senden", Color(56, 189, 248), Color(15, 23, 42))
        sendButton.addActionListener { generateCode() }
        addButtonHover(sendButton)
        controlsDuringConversation.add(sendButton)

        val resetButton = styledButton("Zurücksetzen", Color(71, 85, 105), Color(226, 232, 240))
        resetButton.addActionListener {
            assistant.cancelPendingClarification()
            promptArea.text = ""
            outputArea.text = ""
            statusLabel.text = "Bereit"
        }
        addButtonHover(resetButton)
        controlsDuringConversation.add(resetButton)

        val historyClearButton = styledButton("Verlauf löschen", Color(71, 85, 105), Color(226, 232, 240))
        historyClearButton.addActionListener {
            historyEntries.clear()
            updateHistoryView()
            historyStore.save(historyEntries)
            statusLabel.text = "Verlauf gelöscht"
        }
        addButtonHover(historyClearButton)
        controlsDuringConversation.add(historyClearButton)

        val brainButton = styledButton("Brain JSON", Color(56, 189, 248), Color(15, 23, 42))
        brainButton.addActionListener {
            runBackgroundAction(
                runningMessage = "Brain-State wird erstellt...",
                successMessage = "Brain-State geöffnet",
                task = {
                    val path = assistant.brainStateFilePath()
                    assistant.writeBrainStateSnapshot(path)
                },
                onSuccess = { path ->
                    BrainStateViewer.openJsonFile(path.toFile())
                    BrainStateViewer.openLiveView(path.toFile())
                }
            )
        }
        addButtonHover(brainButton)
        controlsDuringConversation.add(brainButton)

        val evaluationButton = styledButton("Self-Evaluation", Color(56, 189, 248), Color(15, 23, 42))
        evaluationButton.addActionListener {
            runBackgroundAction(
                runningMessage = "Evaluation läuft...",
                successMessage = "Evaluation abgeschlossen",
                task = { assistant.formatEvaluation() },
                onSuccess = { outputArea.text = it }
            )
        }
        addButtonHover(evaluationButton)
        controlsDuringConversation.add(evaluationButton)

        val knowledgeButton = styledButton("Wissen", Color(71, 85, 105), Color(226, 232, 240))
        knowledgeButton.addActionListener { manageKnowledge() }
        addButtonHover(knowledgeButton)
        controlsDuringConversation.add(knowledgeButton)

        historyModel = DefaultListModel()
        historyList = JList(historyModel).apply {
            cellRenderer = HistoryRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellHeight = 52
            background = Color(0, 0, 0, 0)
            isOpaque = false
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 1) {
                        val index = locationToIndex(e.point)
                        if (index >= 0 && index < historyEntries.takeLast(40).size) {
                            val entry = historyEntries.takeLast(40)[index]
                            if (entry.role == "Prompt") {
                                promptArea.text = entry.text
                            } else {
                                outputArea.text = entry.text
                            }
                            statusLabel.text = "Verlauf geladen"
                        }
                    }
                }
            })
        }

        val historyScroll = JScrollPane(historyList).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            viewport.background = Color(10, 14, 26)
            background = Color(10, 14, 26)
        }

        val sidebar = RoundedPanel().apply {
            layout = BorderLayout(0, 16)
            preferredSize = Dimension(360, 0)
            border = EmptyBorder(22, 22, 22, 22)
        }
        val sidebarTitle = JLabel("Chat-Verlauf")
        sidebarTitle.font = Font("Segoe UI", Font.BOLD, 16)
        sidebarTitle.foreground = Color(248, 250, 252)
        val sidebarHeader = JPanel(BorderLayout())
        sidebarHeader.isOpaque = false
        sidebarHeader.add(sidebarTitle, BorderLayout.WEST)

        val sidebarActions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(historyClearButton)
            add(knowledgeButton)
            add(brainButton)
            add(evaluationButton)
        }
        sidebarHeader.add(sidebarActions, BorderLayout.EAST)
        sidebar.add(sidebarHeader, BorderLayout.NORTH)
        sidebar.add(historyScroll, BorderLayout.CENTER)

        val outputCard = RoundedPanel().apply {
            layout = BorderLayout(0, 18)
            border = EmptyBorder(24, 24, 24, 24)
        }
        val outputTitle = JLabel("Konversation")
        outputTitle.font = Font("Segoe UI", Font.BOLD, 22)
        outputTitle.foreground = Color(248, 250, 252)
        outputCard.add(outputTitle, BorderLayout.NORTH)

        val outputScroll = JScrollPane(outputArea).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            viewport.background = Color(10, 14, 26)
            background = Color(10, 14, 26)
        }
        outputCard.add(outputScroll, BorderLayout.CENTER)

        val inputPanel = RoundedPanel().apply {
            layout = BorderLayout(0, 16)
            border = EmptyBorder(20, 20, 20, 20)
            preferredSize = Dimension(0, 260)
        }
        val inputLabel = JLabel("Eingabe")
        inputLabel.font = Font("Segoe UI", Font.BOLD, 14)
        inputLabel.foreground = Color(226, 232, 240)
        inputPanel.add(inputLabel, BorderLayout.NORTH)

        val promptScroll = JScrollPane(promptArea).apply {
            border = BorderFactory.createLineBorder(Color(58, 66, 86), 1)
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            viewport.background = Color(15, 23, 42)
        }
        inputPanel.add(promptScroll, BorderLayout.CENTER)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 12, 0)).apply {
            isOpaque = false
            add(resetButton)
            add(sendButton)
        }
        inputPanel.add(buttonPanel, BorderLayout.SOUTH)

        val conversationPanel = JPanel(BorderLayout(0, 16)).apply {
            isOpaque = false
            add(outputCard, BorderLayout.CENTER)
            add(inputPanel, BorderLayout.SOUTH)
        }

        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, conversationPanel).apply {
            isOpaque = false
            dividerSize = 2
            dividerLocation = 360
            resizeWeight = 0.25
            background = Color(10, 14, 26)
            foreground = Color(10, 14, 26)
            border = BorderFactory.createEmptyBorder()
        }

        val contentPanel = JPanel(BorderLayout(0, 18)).apply {
            isOpaque = false
            border = EmptyBorder(16, 16, 16, 16)
            add(headerPanel, BorderLayout.NORTH)
            add(splitPane, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }

        return GradientPanel().apply {
            layout = BorderLayout()
            border = EmptyBorder(16, 16, 16, 16)
            add(contentPanel, BorderLayout.CENTER)
        }
    }

    /** Erzeugt eine Schaltfläche mit gemeinsamem Schrift-, Farb- und Fokusstil. */
    private fun styledButton(text: String, bg: Color, fg: Color): JButton {
        return JButton(text).apply {
            font = Font("Segoe UI", Font.BOLD, 14)
            background = bg
            foreground = fg
            isOpaque = true
            isFocusPainted = false
            border = BorderFactory.createEmptyBorder(12, 20, 12, 20)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
    }

    /** Wechselt die Hintergrundfarbe beim Überfahren und stellt sie beim Verlassen zurück. */
    private fun addButtonHover(button: JButton) {
        val normal = button.background
        val hover = normal.brighter().brighter()
        button.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) {
                button.background = hover
            }
            override fun mouseExited(e: MouseEvent?) {
                button.background = normal
            }
        })
    }

    /** Vereinheitlicht Farben und Caret-Stil der Texteingabe und -ausgabe. */
    private fun styleTextArea(area: JTextArea) {
        area.background = Color(15, 23, 42)
        area.foreground = Color(226, 232, 240)
        area.caretColor = Color(248, 250, 252)
        area.selectionColor = Color(56, 189, 248)
        area.selectedTextColor = Color(15, 23, 42)
        area.border = null
    }

    /** Sperrt Eingaben und verarbeitet den Turn im Hintergrund, damit Swing responsiv bleibt. */
    private fun generateCode() {
        if (isProcessing) return
        val prompt = promptArea.text.trim()
        if (prompt.isEmpty() || prompt == "Schreibe eine Nachricht oder Kotlin-Anfrage.") {
            statusLabel.text = "Bitte erst eine Anfrage eingeben."
            return
        }
        isProcessing = true
        setConversationProcessing(true)
        startLoadingAnimation()
        // Die Assistant-Arbeit läuft außerhalb des UI-Threads; done() wird wieder auf dem EDT ausgeführt.
        object : SwingWorker<ConversationTurnResult, Unit>() {
            override fun doInBackground(): ConversationTurnResult {
                return assistant.handleConversationTurn(prompt)
            }

            override fun done() {
                stopLoadingAnimation()
                try {
                    val turn = get()
                    val newEntries = appendConversation(prompt, turn.response)
                    statusLabel.text = when {
                        turn.learnedKnowledge -> "Erklärung lokal gespeichert"
                        turn.awaitingConfirmation -> "Speichern bestätigen oder verwerfen"
                        turn.awaitingClarification -> "Rückfrage offen"
                        else -> "Fertig."
                    }
                    persistHistoryAsync(newEntries, ::finishProcessing)
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                    statusLabel.text = "Verarbeitung wurde unterbrochen."
                    finishProcessing()
                } catch (exception: java.util.concurrent.ExecutionException) {
                    val cause = exception.cause ?: exception
                    FileIo.logWarning("Gesprächsschritt konnte nicht abgeschlossen werden.", cause)
                    statusLabel.text = "Fehler: ${cause.message ?: cause.javaClass.simpleName}"
                    finishProcessing()
                }
            }
        }.execute()
    }

    /** Zeigt den Turn sofort an und begrenzt den UI-Verlauf passend zum Dateispeicher. */
    private fun appendConversation(prompt: String, response: String): List<HistoryEntry> {
        if (outputArea.text.isNotBlank()) {
            outputArea.append("\n\n")
        }
        outputArea.append("Du: $prompt\n\nMarkusaller: $response")
        outputArea.caretPosition = outputArea.document.length
        val newEntries = listOf(
            HistoryEntry("Prompt", currentTimestamp(), prompt),
            HistoryEntry("Antwort", currentTimestamp(), response)
        )
        historyEntries.addAll(newEntries)
        if (historyEntries.size > ConversationHistoryStore.MAX_ENTRIES) {
            val excessCount = historyEntries.size - ConversationHistoryStore.COMPACTED_ENTRIES
            historyEntries.subList(0, excessCount).clear()
        }
        updateHistoryView()
        return newEntries
    }

    /** Schreibt Verlaufseinträge im Hintergrund und ruft den Abschluss auch bei Fehlern auf. */
    private fun persistHistoryAsync(entries: List<HistoryEntry>, onComplete: () -> Unit) {
        object : SwingWorker<Unit, Unit>() {
            override fun doInBackground() {
                historyStore.append(entries)
            }

            override fun done() {
                try {
                    get()
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                    statusLabel.text = "Verlauf konnte nicht gespeichert werden: unterbrochen."
                } catch (exception: java.util.concurrent.ExecutionException) {
                    val cause = exception.cause ?: exception
                    FileIo.logWarning("Gesprächsverlauf konnte nicht gespeichert werden.", cause)
                    statusLabel.text = "Verlauf konnte nicht gespeichert werden: ${cause.message}"
                } finally {
                    onComplete()
                }
            }
        }.execute()
    }

    /** Gibt nach erfolgreichem oder fehlgeschlagenem Turn alle Interaktionskontrollen wieder frei. */
    private fun finishProcessing() {
        isProcessing = false
        setConversationProcessing(false)
    }

    /** Führt Aktionen außerhalb des Event-Dispatch-Threads aus und zeigt Fehler im Statusfeld an. */
    private fun <T> runBackgroundAction(
        runningMessage: String,
        successMessage: String,
        task: () -> T,
        onSuccess: (T) -> Unit
    ) {
        if (isProcessing) return
        isProcessing = true
        setConversationProcessing(true)
        statusLabel.text = runningMessage
        object : SwingWorker<T, Unit>() {
            override fun doInBackground(): T = task()

            override fun done() {
                try {
                    onSuccess(get())
                    statusLabel.text = successMessage
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                    statusLabel.text = "Verarbeitung wurde unterbrochen."
                } catch (exception: java.util.concurrent.ExecutionException) {
                    val cause = exception.cause ?: exception
                    FileIo.logWarning("Hintergrundaktion konnte nicht abgeschlossen werden.", cause)
                    statusLabel.text = "Fehler: ${cause.message ?: cause.javaClass.simpleName}"
                } finally {
                    finishProcessing()
                }
            }
        }.execute()
    }

    /** Verhindert überlappende Gesprächs-Turns, während einer verarbeitet oder gespeichert wird. */
    private fun setConversationProcessing(processing: Boolean) {
        controlsDuringConversation.forEach { it.isEnabled = !processing }
        promptArea.isEnabled = !processing
    }

    /** Zeigt gespeichertes Wissen an und löscht den ausgewählten Eintrag nur nach Bestätigung. */
    private fun manageKnowledge() {
        val entries = assistant.listKnowledge()
        if (entries.isEmpty()) {
            JOptionPane.showMessageDialog(null, "Es sind noch keine Wissenseinträge gespeichert.")
            return
        }
        val model = DefaultListModel<String>()
        entries.forEach { entry ->
            model.addElement("${entry.prompt}  |  ${entry.answer}")
        }
        val list = JList(model).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 12
        }
        val pane = JScrollPane(list).apply { preferredSize = Dimension(600, 320) }
        val choice = JOptionPane.showConfirmDialog(
            null,
            pane,
            "Lokales Wissen – Eintrag zum Löschen auswählen",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        )
        val selectedIndex = list.selectedIndex
        if (choice == JOptionPane.OK_OPTION && selectedIndex >= 0) {
            val entry = entries[selectedIndex]
            val confirm = JOptionPane.showConfirmDialog(
                null,
                "Diesen Eintrag dauerhaft löschen?\n\n${entry.prompt}",
                "Wissen löschen",
                JOptionPane.YES_NO_OPTION
            )
            if (confirm == JOptionPane.YES_OPTION && assistant.deleteKnowledge(entry.prompt)) {
                statusLabel.text = "Wissenseintrag gelöscht"
            }
        }
    }

    /** Startet einen Swing-Timer, der während Hintergrundarbeit den Status animiert. */
    private fun startLoadingAnimation() {
        loadingStep = 0
        loadingTimer = Timer(300) {
            loadingStep = (loadingStep + 1) % 4
            statusLabel.text = "Generiere${".".repeat(loadingStep)}"
        }
        loadingTimer.start()
    }

    /** Stoppt den Timer, sobald die Hintergrundarbeit abgeschlossen ist. */
    private fun stopLoadingAnimation() {
        if (::loadingTimer.isInitialized && loadingTimer.isRunning) {
            loadingTimer.stop()
        }
    }

    /** Lädt den begrenzten Verlauf beim Start in den UI-Zustand. */
    private fun loadHistory() {
        historyEntries.addAll(historyStore.load())
    }

    /** Formatiert die lokale Uhrzeit für die Anzeige im Verlauf. */
    private fun currentTimestamp(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    /** Erzeugt Vorschauzeilen für die letzten 40 Einträge und scrollt zum neuesten. */
    private fun updateHistoryView() {
        historyModel.clear()
        historyEntries.takeLast(40).forEach { entry ->
            val preview = entry.text.trim().split(Regex("\\s+"), 4).take(3).joinToString(" ")
            val truncated = entry.text.trim().split(Regex("\\s+")).size > 3
            val display = "<html><div style='font-family:Segoe UI; padding:0; margin:0;'>" +
                "<span style='color:rgba(148,163,184,0.75); font-size:11px;'>" +
                "${escapeHtmlText(entry.timestamp)}</span> " +
                "<span style='color:rgba(56,189,248,0.95); font-weight:600;'>" +
                "${escapeHtmlText(entry.role)}</span> " +
                "<span style='color:rgba(203,213,225,0.85);'>" +
                "${escapeHtmlText(preview.trimEnd())}${if (truncated) "..." else ""}</span>" +
                "</div></html>"
            historyModel.addElement(display)
        }
        if (historyModel.size() > 0) {
            historyList.ensureIndexIsVisible(historyModel.size() - 1)
        }
    }
}

internal fun escapeHtmlText(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

/** Rendert Verlaufseinträge im dunklen UI-Stil mit sichtbarer Auswahl. */
private class HistoryRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
        label.isOpaque = true
        label.background = if (isSelected) Color(56, 189, 248, 90) else Color(15, 23, 42, 40)
        label.foreground = Color(226, 232, 240)
        label.border = EmptyBorder(8, 10, 8, 10)
        label.font = Font("Segoe UI", Font.PLAIN, 13)
        return label
    }
}

/** Zeichnet den Hintergrundverlauf und dezente dekorative Kreise. */
private class GradientPanel : JPanel() {
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val gradient = GradientPaint(0f, 0f, Color(10, 16, 34), width.toFloat(), height.toFloat(), Color(18, 24, 48))
        g2.paint = gradient
        g2.fillRect(0, 0, width, height)
        g2.color = Color(255, 255, 255, 20)
        g2.fillOval(width - 260, -120, 340, 340)
        g2.fillOval(-160, height - 260, 340, 340)
        g2.dispose()
    }
}

/** Zeichnet eine transparente Panel-Fläche mit abgerundeter Kante. */
private class RoundedPanel : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = Color(15, 23, 42, 225)
        g2.fillRoundRect(0, 0, width, height, 28, 28)
        g2.color = Color(255, 255, 255, 18)
        g2.stroke = BasicStroke(1.5f)
        g2.drawRoundRect(0, 0, width - 1, height - 1, 28, 28)
        g2.dispose()
        super.paintComponent(g)
    }
}
