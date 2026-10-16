package ai

/** Hält genau einen offenen Gesprächsschritt: erst Erklärung anfragen, dann deren Speicherung bestätigen lassen. */
internal class ConversationState {
    var pendingPrompt: String? = null
        private set
    var pendingKnowledge: Pair<String, String>? = null
        private set

    /** Beginnt eine Wissensrückfrage und verwirft einen eventuell älteren Speichervorschlag. */
    fun askForClarification(prompt: String) {
        pendingKnowledge = null
        pendingPrompt = prompt
    }

    /** Ersetzt die offene Rückfrage durch den konkreten, noch unbestätigten Wissenseintrag. */
    fun proposeKnowledge(prompt: String, explanation: String) {
        pendingPrompt = null
        pendingKnowledge = prompt to explanation
    }

    /** Beendet nur den Klärungsschritt, ohne einen separaten Speichervorschlag anzutasten. */
    fun clearPendingPrompt() {
        pendingPrompt = null
    }

    /** Verwirft nur den noch nicht bestätigten Wissenseintrag. */
    fun clearPendingKnowledge() {
        pendingKnowledge = null
    }

    /** Setzt den gesamten Gesprächszustand zurück, etwa wenn der Nutzer abbricht. */
    fun clear() {
        pendingPrompt = null
        pendingKnowledge = null
    }
}
