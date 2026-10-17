package ai

import java.awt.Color
import java.awt.FlowLayout
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JPanel

/** UI controls for training and opting into the explicitly experimental decoder. */
@Suppress("MagicNumber")
internal class ExperimentalChatControls(onTrain: () -> Unit) {
    val modeToggle = JCheckBox("Experimenteller Transformer-Chat").apply {
        isOpaque = false
        foreground = Color(251, 191, 36)
        toolTipText = "Kleines lokal trainiertes Modell; Antworten können falsch oder unsinnig sein."
    }
    val trainButton = JButton("KI-Modell trainieren").apply {
        font = java.awt.Font("Segoe UI", java.awt.Font.BOLD, 14)
        background = Color(71, 85, 105)
        foreground = Color(226, 232, 240)
        isOpaque = true
        isFocusPainted = false
        border = javax.swing.BorderFactory.createEmptyBorder(12, 20, 12, 20)
        addActionListener { onTrain() }
    }
    val panel = JPanel(FlowLayout(FlowLayout.RIGHT, 12, 0)).apply {
        isOpaque = false
        add(modeToggle)
        add(trainButton)
    }
    val controls: List<AbstractButton> = listOf(modeToggle, trainButton)
}
