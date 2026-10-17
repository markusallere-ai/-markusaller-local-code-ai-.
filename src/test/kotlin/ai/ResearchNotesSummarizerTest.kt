package ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResearchNotesSummarizerTest {
    @Test
    fun researchNotesCommandRecognizesTheRequestedGermanPhrase() {
        assertTrue(
            ResearchNotesRequest.matches(
                "Geh die Infos durch und speichere sie so kurz wie möglich, damit die KI lernen kann."
            )
        )
        assertFalse(ResearchNotesRequest.matches("Erkläre mir Fotosynthese."))
    }

    @Test
    fun suggestKeepsOneConciseSentencePerTimestampedNoteAndRemovesDuplicates() {
        val notes = """
            ----- 2026-10-17T22:00:00 -----
            # Fotosynthese
            Die Fotosynthese nutzt Licht, Wasser und Kohlenstoffdioxid.
            Pflanzen geben dabei Sauerstoff ab.

            ----- 2026-10-17T23:00:00 -----
            Chlorophyll nimmt Lichtenergie auf.
            Die Energie wird chemisch gespeichert.

            ----- 2026-10-18T00:00:00 -----
            Die Fotosynthese nutzt Licht, Wasser und Kohlenstoffdioxid.
        """.trimIndent()

        assertEquals(
            listOf(
                "Die Fotosynthese nutzt Licht, Wasser und Kohlenstoffdioxid.",
                "Chlorophyll nimmt Lichtenergie auf."
            ).joinToString(System.lineSeparator()),
            ResearchNotesSummarizer.suggest(notes)
        )
    }

    @Test
    fun suggestReturnsEmptyForNotesWithoutConciseSentences() {
        assertEquals("", ResearchNotesSummarizer.suggest("  \n----- 2026-10-17T22:00:00 -----\n# Titel"))
    }
}
