package ai

import java.awt.Desktop
import java.io.File
import javax.swing.JFrame
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * Öffnet den Diagnose-Snapshot im Standardprogramm oder in einer
 * aktualisierten Swing-Ansicht.
 */
object BrainStateViewer {
    /** Zeigt den JSON-Snapshot an und aktualisiert den Text periodisch, solange das Fenster offen ist. */
    fun openLiveView(path: File) {
        SwingUtilities.invokeLater {
            val frame = JFrame("Brain State Live View")
            frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            frame.setSize(900, 600)
            frame.setLocationRelativeTo(null)

            val area = JTextArea()
            area.isEditable = false
            area.font = java.awt.Font("Consolas", java.awt.Font.PLAIN, 13)
            area.text = path.readText()

            val scrollPane = JScrollPane(area)
            frame.contentPane.add(scrollPane)

            frame.isVisible = true

            Thread {
                while (frame.isDisplayable) {
                    try {
                        val text = path.readText()
                        SwingUtilities.invokeLater { area.text = text }
                        Thread.sleep(1000)
                    } catch (_: Exception) {
                        Thread.sleep(1000)
                    }
                }
            }.start()
        }
    }

    /** Öffnet den Snapshot mit dem Betriebssystemprogramm, sofern Desktop-Integration verfügbar ist. */
    fun openJsonFile(path: File) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(path)
            }
        } catch (_: Exception) {
            // no-op
        }
    }
}
