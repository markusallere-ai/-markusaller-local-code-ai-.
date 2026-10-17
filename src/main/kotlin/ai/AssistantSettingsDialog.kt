package ai

import java.awt.BorderLayout
import java.awt.Dialog
import java.awt.Window
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

@Suppress("MagicNumber")
internal class AssistantSettingsDialog(
    private val webSearchControls: WebSearchControls,
    private val experimentalChatControls: ExperimentalChatControls
) {
    fun show(owner: Window?) {
        val dialog = JDialog(owner, "Einstellungen", Dialog.ModalityType.MODELESS).apply {
            defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
            contentPane = createContent()
            pack()
            setLocationRelativeTo(owner)
        }
        dialog.isVisible = true
    }

    internal fun createContent(): JPanel = JPanel(BorderLayout(0, 16)).apply {
        border = EmptyBorder(20, 20, 20, 20)
        add(webSearchControls.panel, BorderLayout.NORTH)
        add(experimentalChatControls.panel, BorderLayout.CENTER)
    }
}
