package ai

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dialog
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.IOException
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants
import javax.swing.border.EmptyBorder

@Suppress("MagicNumber", "TooGenericExceptionCaught", "TooManyFunctions")
internal class ResearchNotesDialog(
    private val store: ResearchNotesStore,
    private val saveAsKnowledge: (String) -> Unit
) {
    internal val inputArea = JTextArea().apply {
        font = Font("Segoe UI", Font.PLAIN, 14)
        lineWrap = true
        wrapStyleWord = true
        background = Color(15, 23, 42)
        foreground = Color(226, 232, 240)
        caretColor = Color(248, 250, 252)
        selectionColor = Color(56, 189, 248)
        selectedTextColor = Color(15, 23, 42)
        border = EmptyBorder(10, 10, 10, 10)
    }
    internal val savedNotesArea = JTextArea().apply {
        font = Font("Consolas", Font.PLAIN, 13)
        lineWrap = true
        wrapStyleWord = true
        isEditable = false
        background = Color(15, 23, 42)
        foreground = Color(226, 232, 240)
        caretColor = Color(248, 250, 252)
        border = EmptyBorder(10, 10, 10, 10)
    }
    internal val summaryArea = JTextArea().apply {
        font = Font("Segoe UI", Font.PLAIN, 14)
        lineWrap = true
        wrapStyleWord = true
        background = Color(15, 23, 42)
        foreground = Color(226, 232, 240)
        caretColor = Color(248, 250, 252)
        selectionColor = Color(56, 189, 248)
        selectedTextColor = Color(15, 23, 42)
        border = EmptyBorder(10, 10, 10, 10)
    }
    internal val statusLabel = JLabel("Eingefügte Texte werden nur in einer lokalen Sammeldatei gespeichert.")
    private var dialog: JDialog? = null
    private var tabs: JTabbedPane? = null
    private var generatedSummary: String? = null

    fun show(owner: Window?, showSavedNotes: Boolean = false) {
        val existingDialog = dialog
        if (existingDialog != null && existingDialog.isDisplayable) {
            if (showSavedNotes) tabs?.selectedIndex = 1
            existingDialog.toFront()
            existingDialog.requestFocus()
            return
        }

        dialog = JDialog(owner, "Informationen sammeln", Dialog.ModalityType.MODELESS).apply {
            defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
            contentPane = createContent()
            addWindowListener(object : WindowAdapter() {
                override fun windowClosed(event: WindowEvent?) {
                    dialog = null
                }
            })
            setSize(760, 560)
            setLocationRelativeTo(owner)
            isVisible = true
        }
        if (showSavedNotes) tabs?.selectedIndex = 1
    }

    internal fun createContent(): JPanel = JPanel(BorderLayout(0, 12)).apply {
        background = Color(10, 14, 26)
        border = EmptyBorder(18, 18, 18, 18)
        add(createTabs(), BorderLayout.CENTER)
        add(createCaptureFooter(), BorderLayout.SOUTH)
    }

    private fun createTabs(): JTabbedPane = JTabbedPane().apply {
        addTab("Neue Infos sammeln", createCapturePanel())
        addTab("Gespeicherte Infos ansehen", createSavedPanel())
        this@ResearchNotesDialog.tabs = this
        addChangeListener { event ->
            if ((event.source as JTabbedPane).selectedIndex == 1) refreshSavedNotes()
        }
    }

    private fun createCapturePanel(): JPanel = JPanel(BorderLayout(0, 12)).apply {
        isOpaque = false
        val description = JLabel(
            "<html>Kopiere Informationen aus dem Browser hier hinein. " +
                "Speichern hängt den Text an eine eigene lokale Datei an; " +
                "es wird keine Chatnachricht gesendet und keine Suche gestartet.</html>"
        ).apply {
            foreground = Color(203, 213, 225)
            font = Font("Segoe UI", Font.PLAIN, 13)
        }
        add(description, BorderLayout.NORTH)
        add(createTextScroll(inputArea), BorderLayout.CENTER)
    }

    private fun createSavedPanel(): JPanel = JPanel(BorderLayout(0, 12)).apply {
        isOpaque = false
        add(createSavedPathLabel(), BorderLayout.NORTH)
        add(createReviewSplit(), BorderLayout.CENTER)
        add(createReviewActions(), BorderLayout.SOUTH)
    }

    private fun createSavedPathLabel(): JLabel =
        JLabel(store.filePath.toAbsolutePath().normalize().toString()).apply {
            foreground = Color(148, 163, 184)
            font = Font("Segoe UI", Font.PLAIN, 12)
        }

    private fun createReviewSplit(): javax.swing.JSplitPane =
        javax.swing.JSplitPane(
            javax.swing.JSplitPane.VERTICAL_SPLIT,
            createLabeledTextPanel("Gespeicherte Originaltexte", savedNotesArea),
            createLabeledTextPanel(
                "Kurzzusammenfassung – vor dem Speichern prüfen und bearbeiten",
                summaryArea
            )
        ).apply {
            resizeWeight = 0.68
            dividerLocation = 320
        }

    private fun createLabeledTextPanel(title: String, area: JTextArea): JPanel =
        JPanel(BorderLayout(0, 6)).apply {
            isOpaque = false
            add(JLabel(title).apply {
                foreground = Color(203, 213, 225)
            }, BorderLayout.NORTH)
            add(createTextScroll(area), BorderLayout.CENTER)
        }

    private fun createReviewActions(): JPanel =
        JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(JButton("Aktualisieren").apply {
                addActionListener { refreshSavedNotes() }
            })
            add(JButton("Kurzvorschlag erstellen").apply {
                addActionListener { prepareSummary() }
            })
            add(JButton("Als Wissen speichern").apply {
                addActionListener { saveSummaryAsKnowledge() }
            })
        }

    private fun createCaptureFooter(): JPanel {
        val saveButton = JButton("Lokal speichern").apply {
            font = Font("Segoe UI", Font.BOLD, 13)
            addActionListener { saveCurrentNote() }
        }
        statusLabel.foreground = Color(148, 163, 184)
        statusLabel.font = Font("Segoe UI", Font.PLAIN, 12)
        return JPanel(BorderLayout(12, 0)).apply {
            isOpaque = false
            add(statusLabel, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                add(saveButton)
            }, BorderLayout.EAST)
        }
    }

    private fun createTextScroll(area: JTextArea): JScrollPane =
        JScrollPane(area).apply {
            border = javax.swing.BorderFactory.createLineBorder(Color(58, 66, 86), 1)
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            viewport.background = area.background
        }

    private fun refreshSavedNotes() {
        try {
            val notes = store.readAll()
            savedNotesArea.text = notes.ifBlank { "Noch keine Informationen gespeichert." }
            if ((summaryArea.text.isBlank() || summaryArea.text == generatedSummary) && notes.isNotBlank()) {
                setSuggestedSummary(ResearchNotesSummarizer.suggest(notes))
            }
            statusLabel.text = if (notes.isBlank()) "Die Sammeldatei ist noch leer."
            else "Gespeicherte Informationen geladen."
        } catch (exception: IOException) {
            showReadError(exception)
        } catch (exception: SecurityException) {
            showReadError(exception)
        }
    }

    private fun prepareSummary() {
        try {
            val notes = store.readAll()
            if (notes.isBlank()) {
                statusLabel.text = "Es gibt noch keine gespeicherten Informationen."
                return
            }
            setSuggestedSummary(ResearchNotesSummarizer.suggest(notes))
            statusLabel.text = "Extraktiver Vorschlag erstellt – bitte vor dem Speichern prüfen."
        } catch (exception: IOException) {
            showReadError(exception)
        } catch (exception: SecurityException) {
            showReadError(exception)
        }
    }

    private fun setSuggestedSummary(summary: String) {
        summaryArea.text = summary
        generatedSummary = summary
    }

    private fun saveSummaryAsKnowledge() {
        val summary = summaryArea.text.trim()
        if (summary.isEmpty()) {
            statusLabel.text = "Erstelle oder schreibe zuerst eine Kurzzusammenfassung."
            return
        }
        try {
            saveAsKnowledge(summary)
            statusLabel.text = "Kurzzusammenfassung als abrufbares Wissen gespeichert."
        } catch (exception: Exception) {
            FileIo.logWarning("Recherche-Kurzzusammenfassung konnte nicht gespeichert werden.", exception)
            JOptionPane.showMessageDialog(
                dialog,
                exception.message ?: "Das Wissen konnte nicht gespeichert werden.",
                "Speicherfehler",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }

    private fun showReadError(exception: Exception) {
        FileIo.logWarning("Recherche-Notizen konnten nicht gelesen werden.", exception)
        JOptionPane.showMessageDialog(
            dialog,
            exception.message ?: "Die gespeicherten Informationen konnten nicht gelesen werden.",
            "Lesefehler",
            JOptionPane.ERROR_MESSAGE
        )
    }

    private fun saveCurrentNote() {
        try {
            store.append(inputArea.text)
            savedNotesArea.text = store.readAll()
            inputArea.text = ""
            if (summaryArea.text == generatedSummary) {
                summaryArea.text = ""
                generatedSummary = null
            }
            statusLabel.text = "Gespeichert: ${store.filePath.toAbsolutePath().normalize()}"
        } catch (exception: Exception) {
            FileIo.logWarning("Recherche-Notiz konnte nicht gespeichert werden.", exception)
            JOptionPane.showMessageDialog(
                dialog,
                exception.message ?: "Die Notiz konnte nicht gespeichert werden.",
                "Speicherfehler",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }
}
