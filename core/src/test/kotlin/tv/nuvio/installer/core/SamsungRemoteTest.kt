// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SamsungRemoteTest {
    /** A fake TV remote channel: plain ws on a free local port. */
    private class FakeTv(private val reply: (path: String) -> String?) : AutoCloseable {
        val server = ServerSocket(0)
        val received = LinkedBlockingQueue<String>()
        val paths = LinkedBlockingQueue<String>()

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        client.use { socket ->
                            val input = socket.getInputStream()
                            val head = readHead(input)
                            val path = head.lineSequence().first().split(" ")[1]
                            paths += path
                            val key = head.lineSequence().first { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(":").trim()
                            val out = socket.getOutputStream()
                            out.write(
                                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                                    "Sec-WebSocket-Accept: ${WebSocket.acceptKey(key)}\r\n\r\n").toByteArray()
                            )
                            reply(path)?.let { sendServerText(out, it) }
                            runCatching {
                                while (true) {
                                    val (opcode, payload) = WebSocket.readFrame(input)
                                    if (opcode == 0x8) break
                                    if (opcode == 0x1) received += String(payload)
                                }
                            }
                        }
                    }
                }
            }
        }

        private fun readHead(input: InputStream): String {
            val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) sb.append(input.read().toChar())
            return sb.toString()
        }

        /** Server frames are not masked. */
        private fun sendServerText(out: OutputStream, text: String) {
            val bytes = text.toByteArray()
            out.write(0x81); out.write(bytes.size); out.write(bytes); out.flush()
        }

        override fun close() = server.close()
    }

    private fun connect(tv: FakeTv, token: String? = null, timeoutMs: Int = 3000) =
        SamsungRemote.connect("127.0.0.1", token = token, approvalTimeoutMs = timeoutMs, port = tv.server.localPort, secure = false)

    @Test
    fun firstConnectionGetsATokenAndSendsText() {
        FakeTv { """{"event":"ms.channel.connect","data":{"id":"x","token":"98765"}}""" }.use { tv ->
            connect(tv).use { remote ->
                assertEquals("98765", remote.token)
                remote.sendText("Breaking Bad")
                remote.pressKey("KEY_ENTER")
            }
            val path = tv.paths.poll(2, TimeUnit.SECONDS)!!
            val name = URLDecoder.decode(path.substringAfter("name=").substringBefore("&"), "UTF-8")
            assertEquals("TV Installer", String(Base64.getDecoder().decode(name)))
            assertTrue(path.startsWith("/api/v2/channels/samsung.remote.control?"))

            val text = Json.parseToJsonElement(tv.received.poll(2, TimeUnit.SECONDS)!!).jsonObject["params"]!!.jsonObject
            assertEquals("SendInputString", text["TypeOfRemote"]!!.jsonPrimitive.content)
            assertEquals("Breaking Bad", String(Base64.getDecoder().decode(text["Cmd"]!!.jsonPrimitive.content)))
            val key = Json.parseToJsonElement(tv.received.poll(2, TimeUnit.SECONDS)!!).jsonObject["params"]!!.jsonObject
            assertEquals("KEY_ENTER", key["DataOfCmd"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun savedTokenIsSentAndKept() {
        FakeTv { """{"event":"ms.channel.connect","data":{}}""" }.use { tv ->
            connect(tv, token = "111").use { assertEquals("111", it.token) }
            assertTrue(tv.paths.poll(2, TimeUnit.SECONDS)!!.endsWith("&token=111"))
        }
    }

    @Test
    fun deniedOnTheTv() {
        FakeTv { """{"event":"ms.channel.unauthorized"}""" }.use { tv ->
            assertFailsWith<RemoteDeniedException> { connect(tv) }
        }
    }

    @Test
    fun noAnswerOnTheTvTimesOut() {
        FakeTv { null }.use { tv ->
            assertFailsWith<RemoteDeniedException> { connect(tv, timeoutMs = 500) }
        }
    }

    @Test
    fun nothingListening() {
        val port = ServerSocket(0).use { it.localPort }
        val e = assertFailsWith<InstallerException> {
            SamsungRemote.connect("127.0.0.1", approvalTimeoutMs = 500, port = port, secure = false)
        }
        assertTrue(e.message!!.startsWith("Could not open the TV's remote control"))
    }

    @Test
    fun framesRoundTrip() {
        for (size in listOf(0, 5, 125, 126, 300, 70_000)) {
            val payload = ByteArray(size) { (it % 251).toByte() }
            val (opcode, decoded) = WebSocket.readFrame(ByteArrayInputStream(WebSocket.encodeFrame(0x1, payload)))
            assertEquals(0x1, opcode)
            assertTrue(payload.contentEquals(decoded), "size $size")
        }
    }

    @Test
    fun acceptKeyFromTheRfc() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocket.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
    }
}
