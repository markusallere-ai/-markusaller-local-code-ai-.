package ai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

internal class CometSearchBridge : AutoCloseable {
    internal companion object {
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_OK = 200
        const val NO_RESPONSE_BODY = -1L
        val shared = CometSearchBridge()
    }

    @Volatile
    private var server: HttpServer? = null

    fun bridgeUri(searchUri: URI): URI {
        require(isGoogleSearch(searchUri)) { "Nur Google-Suchadressen dürfen an Comet übergeben werden." }
        val activeServer = server()
        val encodedTarget = URLEncoder.encode(searchUri.toString(), StandardCharsets.UTF_8)
        return URI("http://127.0.0.1:${activeServer.address.port}/search?target=$encodedTarget")
    }

    override fun close() {
        synchronized(this) {
            server?.stop(0)
            server = null
        }
    }

    private fun server(): HttpServer {
        server?.let { return it }
        return synchronized(this) {
            server ?: HttpServer.create(
                InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
                0
            ).apply {
                createContext("/search", ::serveSearchPage)
                executor = Executors.newCachedThreadPool { runnable ->
                    Thread(runnable, "markusaller-comet-search").apply { isDaemon = true }
                }
                start()
                server = this
            }
        }
    }

    private fun serveSearchPage(exchange: HttpExchange) {
        val target = exchange.requestURI.rawQuery
            ?.split('&')
            ?.firstOrNull { it.startsWith("target=") }
            ?.substringAfter('=')
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
            ?.let(::parseUri)
        if (target == null || !isGoogleSearch(target)) {
            exchange.sendResponseHeaders(HTTP_BAD_REQUEST, NO_RESPONSE_BODY)
            exchange.close()
            return
        }

        val targetUrl = escapeHtml(target.toString())
        val html = """
            <!doctype html>
            <html lang="de">
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Markusaller-Suche wird an Comet übergeben</title>
            <body style="font:16px Segoe UI,sans-serif;margin:3rem;max-width:48rem">
              <h1>Google-Suche</h1>
              <p>Wenn die Markusaller-Erweiterung in Comet aktiv ist, wird diese Suche
                 im gespeicherten Such-Tab geöffnet.</p>
              <p>Ohne Erweiterung kannst du die Suche direkt öffnen:</p>
              <p><a href="$targetUrl">Google-Suche öffnen</a></p>
            </body>
            </html>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.sendResponseHeaders(HTTP_OK, html.size.toLong())
        exchange.responseBody.use { it.write(html) }
    }

    private fun parseUri(value: String): URI? =
        try {
            URI(value)
        } catch (_: java.net.URISyntaxException) {
            null
        }

    private fun isGoogleSearch(uri: URI): Boolean =
        uri.scheme == "https" &&
            uri.host == "www.google.com" &&
            uri.path == "/search" &&
            !uri.getQueryParameter("q").isNullOrBlank()

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "&#39;")

    private fun URI.getQueryParameter(name: String): String? =
        rawQuery?.split('&')
            ?.firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
}
