// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A Samsung sign-in. [expiresInSeconds] is how long Samsung says the token is valid, when it says. */
class SamsungAccess(val accessToken: String, val userId: String, val email: String, val expiresInSeconds: Long? = null)

/**
 * Receives the Samsung account OAuth result. Samsung redirects the browser to
 * http://localhost:4794/signin/callback (the redirect URI registered for this service ID),
 * so a tiny HTTP server listens on the loopback interface while the user signs in.
 */
class SamsungLoginServer(
    private val port: Int = 4794,
    /** Optional link shown on the success page to return to the app (e.g. a custom-scheme URL). */
    private val returnUrl: String? = null,
) : AutoCloseable {
    companion object {
        const val AUTH_URL = "https://account.samsung.com/mobile/account/check.do?serviceID=v285zxnl3h&actionID=StartOAuth2" +
            "&accessToken=Y&redirect_uri=http://localhost:4794/signin/callback"
        private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }
    }

    private val sockets = ArrayList<ServerSocket>()
    private val result = AtomicReference<SamsungAccess?>()
    private val done = CountDownLatch(1)

    /** Starts listening. Throws if the port is unavailable. */
    fun start() {
        val v4 = ServerSocket()
        v4.reuseAddress = true
        try {
            v4.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        } catch (e: Exception) {
            throw InstallerException("Unable to open the local Samsung login receiver on port $port: ${e.message}", e)
        }
        sockets += v4
        runCatching {
            val v6 = ServerSocket()
            v6.reuseAddress = true
            v6.bind(InetSocketAddress(InetAddress.getByName("::1"), port))
            sockets += v6
        }
        for (server in sockets) {
            Thread({ acceptLoop(server) }, "samsung-login-$port").apply { isDaemon = true }.start()
        }
    }

    /** Blocks until the Samsung login completes, or throws on timeout / cancellation. */
    fun await(timeoutMs: Long = 5 * 60_000): SamsungAccess {
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw InstallerException("Samsung login timed out. Start the installation again and finish the login within 5 minutes.")
        }
        return result.get() ?: throw InstallerException("Samsung login was cancelled.")
    }

    override fun close() {
        sockets.forEach { runCatching { it.close() } }
        done.countDown()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: SocketException) {
                return
            } catch (_: Exception) {
                continue
            }
            runCatching { handle(client) }
            runCatching { client.close() }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val target = parts.getOrNull(1).orEmpty()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (method == "POST" && length > 0) {
            val buffer = ByteArray(minOf(length, 1_000_000))
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            String(buffer, 0, read, Charsets.UTF_8)
        } else ""

        val access = extractAccess(target, body)
        val alreadyDone = result.get() != null
        if (access != null && !alreadyDone) result.set(access)
        val authorized = access != null || alreadyDone
        val html = page(authorized)
        val bytes = html.toByteArray(Charsets.UTF_8)
        val status = if (authorized || method != "POST") "200 OK" else "400 Bad Request"
        val out = client.getOutputStream()
        out.write(
            ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        out.write(bytes)
        out.flush()
        if (access != null) done.countDown()
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            if (b == '\n'.code) return buffer.toString("UTF-8").trimEnd('\r')
            buffer.write(b)
            if (buffer.size() > 64 * 1024) return buffer.toString("UTF-8")
        }
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }

    /** Accepts JSON or query-string encoded access info, in the query string or a form body. */
    internal fun extractAccess(target: String, body: String): SamsungAccess? {
        val query = parseQuery(target.substringAfter('?', ""))
        val form = runCatching { parseQuery(body) }.getOrDefault(emptyMap())
        val candidates = listOfNotNull(
            query["code"], query["accessInfo"], form["code"], form["accessInfo"], body.takeIf { it.trim().startsWith("{") }
        )
        for (raw in candidates) parseAccessInfo(raw)?.let { return it }
        return fromMap(query) ?: fromMap(form)
    }

    private fun parseAccessInfo(raw: String): SamsungAccess? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val map: Map<String, String> = runCatching {
            (lenientJson.parseToJsonElement(value) as JsonObject).mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() }
        }.getOrElse { runCatching { parseQuery(value) }.getOrDefault(emptyMap()) }
        return fromMap(map)
    }

    private fun fromMap(map: Map<String, String>): SamsungAccess? {
        val token = map["access_token"] ?: map["accessToken"] ?: return null
        val userId = map["userId"] ?: map["user_id"] ?: ""
        val email = map["inputEmailID"] ?: map["email"] ?: userId
        val expiresIn = (map["access_token_expires_in"] ?: map["expires_in"])?.trim()?.toLongOrNull()
        return SamsungAccess(token, userId, email, expiresIn)
    }

    private fun page(authorized: Boolean): String {
        val title = if (authorized) "Samsung authorization complete" else "Samsung authorization in progress"
        val text = if (authorized) "Return to the TV Installer app. Installation continues automatically."
        else "Complete the Samsung login. The Nuvio installer receives the authorization automatically."
        val button = if (authorized && returnUrl != null) {
            "<p><a href=\"$returnUrl\" style=\"display:inline-block;padding:14px 22px;border-radius:12px;background:#34d399;color:#000;text-decoration:none;font-weight:600\">Return to TV Installer</a></p>"
        } else ""
        return """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Nuvio Samsung Login</title><style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0d0d0d;color:#fff;
font-family:sans-serif}main{max-width:520px;padding:32px}h1{font-weight:500}p{color:#a8a8a8;line-height:1.6}</style></head>
<body><main><h1>$title</h1><p>$text</p>$button</main></body></html>"""
    }
}
