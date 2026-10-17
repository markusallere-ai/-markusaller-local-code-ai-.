package ai

import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.AbstractButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel

/** Opt-in control for opening Google searches in the user's default browser. */
@Suppress("MagicNumber")
internal class WebSearchControls {
    val modeToggle = JCheckBox("Google-Suche im Browser").apply {
        isOpaque = false
        foreground = Color(251, 191, 36)
        toolTipText = "Sendet deine Anfrage an Google und öffnet die Treffer im Standardbrowser."
    }
    val notice = JLabel(
        "<html>Suchtext geht an Google. Für denselben Comet-Tab zuerst die " +
            "Markusaller-Erweiterung laden.</html>"
    )
        .apply {
            foreground = Color(148, 163, 184)
            font = Font("Segoe UI", Font.PLAIN, 12)
        }
    val panel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
        isOpaque = false
        add(modeToggle)
        add(notice)
    }
    val controls: List<AbstractButton> = listOf(modeToggle)
}
