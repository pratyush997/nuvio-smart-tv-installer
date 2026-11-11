// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * A small WebSocket client (RFC 6455): text messages only, enough for the TV's remote control
 * channel. `wss://` accepts any certificate, because Samsung TVs use a self-signed one; it is
 * only meant for TVs on the local network.
 */
class WebSocket private constructor(private val socket: Socket) : AutoCloseable {
    private val input: InputStream = socket.getInputStream().buffered()
    private val output: OutputStream = socket.getOutputStream()

    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private val random = SecureRandom()

        fun acceptKey(key: String): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)))

        fun connect(url: String, timeoutMs: Int = 5000): WebSocket {
            val uri = URI(url)
            val secure = uri.scheme.equals("wss", ignoreCase = true)
            val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
            val plain = Socket()
            plain.connect(InetSocketAddress(uri.host, port), timeoutMs)
            plain.soTimeout = timeoutMs
            val socket = if (secure) {
                val context = SSLContext.getInstance("TLS")
                context.init(null, arrayOf(TrustLocalTv), random)
                (context.socketFactory.createSocket(plain, uri.host, port, true) as SSLSocket).also { it.startHandshake() }
            } else plain
            try {
                handshake(socket, uri)
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
            return WebSocket(socket)
        }

        private fun handshake(socket: Socket, uri: URI) {
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
            val path = (uri.rawPath.ifEmpty { "/" }) + (uri.rawQuery?.let { "?$it" } ?: "")
            val host = if (uri.port > 0) "${uri.host}:${uri.port}" else uri.host
            val request = "GET $path HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
            socket.getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII)); flush() }
            val head = readHead(socket.getInputStream())
            val status = head.lineSequence().first()
            if (!status.contains(" 101")) throw IOException("The TV refused the remote control connection ($status).")
            val accept = head.lineSequence().drop(1).map { it.split(":", limit = 2) }
                .firstOrNull { it.size == 2 && it[0].trim().equals("Sec-WebSocket-Accept", ignoreCase = true) }?.get(1)?.trim()
            if (accept != acceptKey(key)) throw IOException("The TV's remote control answer was not a valid WebSocket handshake.")
        }

        /** Reads up to the blank line ending the HTTP headers, without reading past it. */
        private fun readHead(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException("The TV closed the connection during the handshake.")
                bytes.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) break
                if (bytes.size() > 16 * 1024) throw IOException("The TV's handshake answer is too long.")
            }
            return bytes.toString(Charsets.ISO_8859_1.name())
        }

        /** Encodes one client frame: FIN set, masked, as RFC 6455 requires from clients. */
        fun encodeFrame(opcode: Int, payload: ByteArray, mask: ByteArray = ByteArray(4).also(random::nextBytes)): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(0x80 or opcode)
            when {
                payload.size < 126 -> out.write(0x80 or payload.size)
                payload.size <= 0xFFFF -> { out.write(0x80 or 126); out.write(payload.size shr 8); out.write(payload.size and 0xFF) }
                else -> { out.write(0x80 or 127); for (i in 7 downTo 0) out.write(((payload.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
            }
            out.write(mask)
            out.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
            return out.toByteArray()
        }

        /** Reads one frame. Returns the opcode and the (unmasked) payload. */
        fun readFrame(input: InputStream): Pair<Int, ByteArray> {
            fun next(): Int = input.read().also { if (it < 0) throw EOFException("The TV closed the remote control connection.") }
            val first = next()
            val second = next()
            var length = (second and 0x7F).toLong()
            if (length == 126L) length = ((next() shl 8) or next()).toLong()
            else if (length == 127L) { length = 0; repeat(8) { length = (length shl 8) or next().toLong() } }
            if (length > 1_000_000) throw IOException("The TV sent a message that is too large.")
            val mask = if (second and 0x80 != 0) ByteArray(4) { next().toByte() } else null
            val payload = ByteArray(length.toInt())
            var read = 0
            while (read < payload.size) {
                val n = input.read(payload, read, payload.size - read)
                if (n < 0) throw EOFException("The TV closed the remote control connection.")
                read += n
            }
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            return (first and 0x0F) to payload
        }
    }

    @Synchronized
    fun send(text: String) {
        output.write(encodeFrame(0x1, text.toByteArray(Charsets.UTF_8)))
        output.flush()
    }

    /** The next text message, or null when none arrives within [timeoutMs]. Answers pings. */
    fun receive(timeoutMs: Int): String? {
        socket.soTimeout = timeoutMs
        while (true) {
            val (opcode, payload) = try {
                readFrame(input)
            } catch (_: SocketTimeoutException) {
                return null
            }
            // The TV's messages are small and never fragmented.
            when (opcode) {
                0x1 -> return String(payload, Charsets.UTF_8)
                0x8 -> throw EOFException("The TV closed the remote control connection.")
                0x9 -> synchronized(this) { output.write(encodeFrame(0xA, payload)); output.flush() }
                else -> continue
            }
        }
    }

    override fun close() {
        runCatching { synchronized(this) { output.write(encodeFrame(0x8, ByteArray(0))); output.flush() } }
        runCatching { socket.close() }
    }

    /** Samsung TVs present a self-signed certificate on the local network. */
    private object TrustLocalTv : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
