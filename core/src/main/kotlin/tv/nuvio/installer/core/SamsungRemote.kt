// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/** The TV said no to the remote control connection (the user chose Deny, or it timed out). */
class RemoteDeniedException : InstallerException(
    "The TV did not allow this phone to control it. Try again and choose Allow on the TV. " +
        "If you chose Deny before, allow it on the TV under Settings › All Settings › Connection › External Device Manager › " +
        "Device Connection Manager › Device List."
)

/**
 * Types on a Samsung TV through its remote control channel (the same one the SmartThings app
 * uses): wss on port 8002. The first time, the TV asks whether to allow the phone and then hands
 * out a token; pass it back as [token] so the TV does not ask again.
 *
 * Text only arrives when the TV's on-screen keyboard is open (for example in Nuvio's search).
 */
class SamsungRemote private constructor(private val socket: WebSocket, val token: String?) : AutoCloseable {
    companion object {
        const val PORT = 8002
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun url(ip: String, name: String, token: String?, port: Int = PORT, secure: Boolean = true): String {
            val encodedName = Base64.getEncoder().encodeToString(name.toByteArray(Charsets.UTF_8))
            val query = "name=" + java.net.URLEncoder.encode(encodedName, "UTF-8") + (token?.let { "&token=$it" } ?: "")
            return "${if (secure) "wss" else "ws"}://${ip.trim()}:$port/api/v2/channels/samsung.remote.control?$query"
        }

        /**
         * Connects and waits until the TV accepts. Allow [approvalTimeoutMs] for the user to answer
         * the TV's prompt the first time.
         */
        fun connect(
            ip: String,
            name: String = "TV Installer",
            token: String? = null,
            approvalTimeoutMs: Int = 30_000,
            port: Int = PORT,
            secure: Boolean = true,
        ): SamsungRemote {
            val socket = try {
                WebSocket.connect(url(ip, name, token, port, secure))
            } catch (e: Exception) {
                throw InstallerException("Could not open the TV's remote control (port $port): ${e.message}", e)
            }
            try {
                val deadline = System.currentTimeMillis() + approvalTimeoutMs
                while (true) {
                    val left = (deadline - System.currentTimeMillis()).toInt()
                    if (left <= 0) throw RemoteDeniedException()
                    val message = socket.receive(left) ?: continue
                    val event = parse(message) ?: continue
                    when (event.event) {
                        "ms.channel.connect" -> return SamsungRemote(socket, event.token ?: token)
                        "ms.channel.unauthorized", "ms.channel.timeOut" -> throw RemoteDeniedException()
                    }
                }
            } catch (e: Exception) {
                socket.close()
                if (e is InstallerException) throw e
                throw InstallerException("The TV's remote control stopped answering: ${e.message}", e)
            }
        }

        class Event(val event: String, val token: String?)

        fun parse(message: String): Event? = runCatching {
            val obj = json.parseToJsonElement(message).jsonObject
            val event = obj["event"]?.jsonPrimitive?.content ?: return null
            val data = obj["data"] as? JsonObject
            Event(event, data?.get("token")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() })
        }.getOrNull()

        fun textMessage(text: String): String = control {
            put("Cmd", Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
            put("DataOfCmd", "base64")
            put("TypeOfRemote", "SendInputString")
        }

        fun endTextMessage(): String = control { put("TypeOfRemote", "SendInputEnd") }

        fun keyMessage(key: String): String = control {
            put("Cmd", "Click")
            put("DataOfCmd", key)
            put("Option", "false")
            put("TypeOfRemote", "SendRemoteKey")
        }

        private fun control(params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
            buildJsonObject {
                put("method", "ms.remote.control")
                put("params", buildJsonObject(params))
            }.toString()
    }

    /** Replaces the text in the TV's open keyboard field with [text]. */
    fun sendText(text: String) = socket.send(textMessage(text))

    /** Closes the TV's keyboard, as if Done was pressed. */
    fun endText() = socket.send(endTextMessage())

    /** Presses a remote key, such as KEY_ENTER, KEY_RETURN or KEY_LEFT. */
    fun pressKey(key: String) = socket.send(keyMessage(key))

    override fun close() = socket.close()
}
