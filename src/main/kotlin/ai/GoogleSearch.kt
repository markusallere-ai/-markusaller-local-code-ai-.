package ai

import java.awt.Desktop
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal class GoogleSearch(
    private val openUri: (URI) -> Unit = ::openInBrowser
) {
    fun open(query: String): URI {
        val uri = searchUri(query)
        openUri(uri)
        return uri
    }

    internal fun searchUri(query: String): URI {
        val normalizedQuery = query.trim()
        require(normalizedQuery.isNotEmpty()) { "Bitte einen Suchbegriff eingeben." }
        require(normalizedQuery.length <= MAX_QUERY_LENGTH) {
            "Suchanfragen dürfen höchstens $MAX_QUERY_LENGTH Zeichen enthalten."
        }
        val encodedQuery = URLEncoder.encode(normalizedQuery, StandardCharsets.UTF_8)
        return URI("https://www.google.com/search?q=$encodedQuery")
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 512

        fun openInBrowser(uri: URI) {
            check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                "Auf diesem System ist kein Standardbrowser verfügbar."
            }
            Desktop.getDesktop().browse(CometSearchBridge.shared.bridgeUri(uri))
        }
    }
}
