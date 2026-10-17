package ai

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import javax.swing.JPanel

class GoogleSearchTest {
    @Test
    fun searchEncodesGermanAndReservedCharactersAndOpensOnlyGoogleSearchUris() {
        var opened: URI? = null
        val search = GoogleSearch { opened = it }

        val uri = search.open("  Kotlin & Java: Grüße  ")

        assertEquals(uri, opened)
        assertEquals("https", uri.scheme)
        assertEquals("www.google.com", uri.host)
        assertTrue(uri.rawQuery.orEmpty().contains("Kotlin+%26+Java%3A+Gr%C3%BC%C3%9Fe"))
    }

    @Test
    fun searchRejectsEmptyAndOverlongQueriesWithoutOpeningBrowser() {
        var openCount = 0
        val search = GoogleSearch { openCount++ }

        assertFailsWith<IllegalArgumentException> { search.open(" \n ") }
        assertFailsWith<IllegalArgumentException> { search.open("a".repeat(513)) }
        assertEquals(0, openCount)
    }

    @Test
    fun webSearchIsOptInAndExplicitlyDisclosesExternalQueryTransfer() {
        val controls = WebSearchControls()

        assertFalse(controls.modeToggle.isSelected)
        assertTrue(controls.notice.text.contains("Google"))
        assertTrue(controls.notice.text.contains("Comet"))
    }

    @Test
    fun settingsDialogContainsBothModePanels() {
        val webSearch = WebSearchControls()
        val transformer = ExperimentalChatControls {}
        val content = AssistantSettingsDialog(webSearch, transformer).createContent()

        assertEquals(2, content.componentCount)
        assertTrue(content.components.contains(webSearch.panel))
        assertTrue(content.components.contains(transformer.panel))
        assertTrue(content.components.all { it is JPanel })
    }
}
