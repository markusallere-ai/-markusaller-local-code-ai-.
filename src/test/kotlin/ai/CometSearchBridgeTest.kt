package ai

import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CometSearchBridgeTest {
    @Test
    fun bridgePageContainsTheValidatedGoogleTargetAndLocalFallback() {
        CometSearchBridge().use { bridge ->
            val target = URI("https://www.google.com/search?q=plant+cells")
            val bridgeUri = bridge.bridgeUri(target)
            val connection = bridgeUri.toURL().openConnection() as HttpURLConnection

            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

            assertEquals("127.0.0.1", bridgeUri.host)
            assertEquals(200, connection.responseCode)
            assertTrue(html.contains("https://www.google.com/search?q=plant+cells"))
            assertTrue(html.contains("Google-Suche öffnen"))
        }
    }

    @Test
    fun bridgeRejectsNonGoogleAndNonSearchUris() {
        CometSearchBridge().use { bridge ->
            assertFailsWith<IllegalArgumentException> {
                bridge.bridgeUri(URI("https://example.com/search?q=anything"))
            }
            assertFailsWith<IllegalArgumentException> {
                bridge.bridgeUri(URI("https://www.google.com/?q=anything"))
            }
        }
    }
}
