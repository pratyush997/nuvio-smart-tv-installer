// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal client for Samsung's SDB daemon (an ADB-protocol derivative) that Tizen TVs expose
 * on port 26101 while Developer Mode is enabled. The TV only accepts connections from the
 * "Host IP" configured in Developer Mode, and it does not use ADB authentication.
 */
class SdbClient private constructor(private val socket: Socket) : Closeable {

    class ShellOptions(
        val timeoutMs: Long = 120_000,
        /** Return once no new output arrived for this long (ignored when [completeWhen] is set). */
        val idleAfterDataMs: Long = 500,
        /** Return an empty result when the command produced no output for this long (0 = wait). */
        val noOutputSuccessMs: Long = 0,
        /** Return as soon as the accumulated output matches. */
        val completeWhen: Regex? = null,
    )

    private sealed interface StreamEvent {
        class Data(val bytes: ByteArray) : StreamEvent
        data object Okay : StreamEvent
        class Closed(val error: Throwable?) : StreamEvent
    }

    private class Stream(val localId: Int) {
        @Volatile var remoteId = 0
        @Volatile var closed = false
        val opened = CountDownLatch(1)
        val events = LinkedBlockingQueue<StreamEvent>()
    }

    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
    private val output: OutputStream = socket.getOutputStream()
    private val writeLock = Any()
    private val streams = ConcurrentHashMap<Int, Stream>()
    private val nextLocalId = AtomicInteger(1)
    private val handshake = CountDownLatch(1)

    @Volatile private var failure: Throwable? = null
    @Volatile var remoteMaxData: Int = 4096
        private set
    @Volatile var banner: String = ""
        private set

    private val reader = Thread({ readLoop() }, "sdb-reader").apply { isDaemon = true }

    companion object {
        const val DEFAULT_PORT = 26101
        private const val A_SYNC = 0x434e5953
        private const val A_CNXN = 0x4e584e43
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257
        private const val A_AUTH = 0x48545541
        private const val PROTOCOL_VERSION = 0x01000000
        private const val HOST_MAX_DATA = 4096
        private const val SYNC_MAX_CHUNK = 64 * 1024

        /** Opens the TCP connection and completes the CNXN handshake. */
        fun connect(host: String, port: Int = DEFAULT_PORT, timeoutMs: Int = 6000): SdbClient {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            } catch (e: ConnectException) {
                socket.close()
                throw InstallerException(
                    "The TV refused the connection on $host:$port. Check that Developer Mode is on and the TV IP is correct.", e
                )
            } catch (e: SocketTimeoutException) {
                socket.close()
                throw InstallerException("Timed out connecting to $host:$port. Check the TV IP and that the phone and TV are on the same Wi-Fi.", e)
            } catch (e: IOException) {
                socket.close()
                throw InstallerException("Unable to reach $host:$port: ${e.message}", e)
            }
            val client = SdbClient(socket)
            client.reader.start()
            client.sendPacket(A_CNXN, PROTOCOL_VERSION, HOST_MAX_DATA, cString("host::"))
            if (!client.handshake.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
                client.close()
                throw InstallerException("Timed out waiting for the TV's SDB handshake.")
            }
            client.failure?.let { error ->
                client.close()
                throw describeConnectionFailure(error)
            }
            return client
        }

        private fun describeConnectionFailure(error: Throwable): InstallerException {
            if (error is InstallerException) return error
            val reset = error is SocketException && (error.message ?: "").contains("reset", ignoreCase = true)
            return if (reset || error is EOFException) {
                InstallerException(
                    "The TV closed the connection. In Developer Mode on the TV, \"Host IP\" must be set to this phone's IP. Then ${TvNetwork.POWER_CYCLE}.",
                    error
                )
            } else {
                InstallerException("Connection to the TV failed: ${error.message ?: error.javaClass.simpleName}", error)
            }
        }

        private fun cString(value: String): ByteArray = value.toByteArray(Charsets.UTF_8) + 0

        private fun le(vararg values: Int): ByteArray {
            val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { buffer.putInt(it) }
            return buffer.array()
        }

        private fun syncHeader(id: String, length: Int): ByteArray =
            id.toByteArray(Charsets.US_ASCII) + le(length)
    }

    // ---------------------------------------------------------------- protocol plumbing

    private fun sendPacket(command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        var checksum = 0
        for (b in data) checksum += (b.toInt() and 0xff)
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size).putInt(checksum).putInt(command.inv())
            .array()
        synchronized(writeLock) {
            output.write(header)
            if (data.isNotEmpty()) output.write(data)
            output.flush()
        }
    }

    private fun readLoop() {
        try {
            val header = ByteArray(24)
            while (true) {
                input.readFully(header)
                val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val command = h.int
                val arg0 = h.int
                val arg1 = h.int
                val length = h.int
                if (length < 0 || length > 16 * 1024 * 1024) throw IOException("Invalid SDB packet length $length")
                val data = ByteArray(length)
                if (length > 0) input.readFully(data)
                dispatch(command, arg0, arg1, data)
            }
        } catch (t: Throwable) {
            if (failure == null) failure = t
        } finally {
            handshake.countDown()
            for (stream in streams.values) {
                stream.closed = true
                stream.opened.countDown()
                stream.events.offer(StreamEvent.Closed(failure))
            }
        }
    }

    private fun dispatch(command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        when (command) {
            A_CNXN -> {
                remoteMaxData = if (arg1 > 0) arg1 else 4096
                banner = String(data, Charsets.UTF_8).trimEnd('\u0000')
                handshake.countDown()
            }
            A_AUTH -> {
                failure = InstallerException("The TV requested ADB authentication, which Samsung SDB does not use. Is this really a Samsung Tizen TV?")
                socket.close()
            }
            A_OKAY -> {
                val stream = streams[arg1] ?: return
                if (stream.remoteId == 0) {
                    stream.remoteId = arg0
                    stream.opened.countDown()
                } else {
                    stream.events.offer(StreamEvent.Okay)
                }
            }
            A_WRTE -> {
                val stream = streams[arg1]
                if (stream != null) {
                    if (stream.remoteId == 0) stream.remoteId = arg0
                    stream.events.offer(StreamEvent.Data(data))
                }
                runCatching { sendPacket(A_OKAY, arg1, arg0) }
            }
            A_CLSE -> {
                val stream = streams.remove(arg1) ?: return
                stream.closed = true
                stream.opened.countDown()
                stream.events.offer(StreamEvent.Closed(null))
            }
            A_SYNC -> Unit
        }
    }

    private fun openStream(destination: String, timeoutMs: Long = 10_000): Stream {
        failure?.let { throw describeConnectionFailure(it) }
        val stream = Stream(nextLocalId.getAndIncrement())
        streams[stream.localId] = stream
        sendPacket(A_OPEN, stream.localId, 0, cString(destination))
        if (!stream.opened.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            streams.remove(stream.localId)
            throw InstallerException("Timed out opening \"$destination\" on the TV.")
        }
        if (stream.remoteId == 0) {
            streams.remove(stream.localId)
            failure?.let { throw describeConnectionFailure(it) }
            throw InstallerException("The TV refused to open \"$destination\".")
        }
        return stream
    }

    private fun closeStream(stream: Stream) {
        streams.remove(stream.localId)
        if (!stream.closed && failure == null) {
            stream.closed = true
            runCatching { sendPacket(A_CLSE, stream.localId, stream.remoteId) }
        }
    }

    // ---------------------------------------------------------------- public API

    /** Runs `shell:<command>` and returns its output. */
    fun shell(command: String, options: ShellOptions = ShellOptions()): String {
        val stream = openStream("shell:$command")
        val out = StringBuilder()
        val start = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - start) / 1_000_000
        try {
            while (true) {
                val remaining = options.timeoutMs - elapsedMs()
                if (remaining <= 0) throw InstallerException("Timed out waiting for TV command: $command")
                val wait = when {
                    out.isEmpty() && options.noOutputSuccessMs > 0 -> minOf(remaining, options.noOutputSuccessMs - elapsedMs())
                    out.isNotEmpty() && options.completeWhen == null && options.idleAfterDataMs > 0 -> minOf(remaining, options.idleAfterDataMs)
                    else -> remaining
                }
                if (wait <= 0) return out.toString()
                val event = stream.events.poll(wait, TimeUnit.MILLISECONDS)
                when (event) {
                    null -> {
                        if (out.isEmpty() && options.noOutputSuccessMs > 0 && elapsedMs() >= options.noOutputSuccessMs) return ""
                        if (out.isNotEmpty() && options.completeWhen == null && options.idleAfterDataMs > 0) return out.toString()
                    }
                    is StreamEvent.Data -> {
                        out.append(String(event.bytes, Charsets.UTF_8))
                        if (options.completeWhen?.containsMatchIn(out) == true) return out.toString()
                    }
                    is StreamEvent.Closed -> {
                        if (event.error != null && out.isEmpty()) throw describeConnectionFailure(event.error)
                        return out.toString()
                    }
                    StreamEvent.Okay -> Unit
                }
            }
        } finally {
            closeStream(stream)
        }
    }

    /**
     * Uploads [data] to [remotePath] using the ADB sync protocol (SEND/DATA/DONE), waiting for the
     * TV to acknowledge every packet.
     */
    fun push(remotePath: String, data: ByteArray, mode: Int = 33261, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val stream = openStream("sync:")
        val pending = java.io.ByteArrayOutputStream()
        try {
            fun write(bytes: ByteArray) {
                sendPacket(A_WRTE, stream.localId, stream.remoteId, bytes)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (true) {
                    val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                    if (left <= 0) throw InstallerException("The TV stopped acknowledging the upload of $remotePath.")
                    when (val event = stream.events.poll(left, TimeUnit.MILLISECONDS)) {
                        StreamEvent.Okay -> return
                        is StreamEvent.Data -> {
                            pending.write(event.bytes)
                            failIfSyncError(pending.toByteArray(), remotePath)
                        }
                        is StreamEvent.Closed -> throw event.error?.let { describeConnectionFailure(it) }
                            ?: InstallerException("The TV closed the upload of $remotePath.")
                        null -> Unit
                    }
                }
            }

            val target = "$remotePath,$mode".toByteArray(Charsets.UTF_8)
            write(syncHeader("SEND", target.size) + target)

            val chunkSize = minOf(remoteMaxData, HOST_MAX_DATA, SYNC_MAX_CHUNK) - 8
            var offset = 0
            onProgress(0, data.size.toLong())
            while (offset < data.size) {
                val length = minOf(chunkSize, data.size - offset)
                val packet = ByteArray(8 + length)
                System.arraycopy(syncHeader("DATA", length), 0, packet, 0, 8)
                System.arraycopy(data, offset, packet, 8, length)
                write(packet)
                offset += length
                onProgress(offset.toLong(), data.size.toLong())
            }
            write(syncHeader("DONE", (System.currentTimeMillis() / 1000).toInt()))

            // Wait for the sync status ("OKAY" or "FAIL" + message).
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (pending.size() < 8) {
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0) throw InstallerException("The TV did not confirm the upload of $remotePath.")
                when (val event = stream.events.poll(left, TimeUnit.MILLISECONDS)) {
                    is StreamEvent.Data -> pending.write(event.bytes)
                    is StreamEvent.Closed -> if (pending.size() < 8) {
                        throw InstallerException("The TV closed the upload of $remotePath before confirming it.")
                    }
                    else -> Unit
                }
            }
            failIfSyncError(pending.toByteArray(), remotePath)
            val status = String(pending.toByteArray(), 0, 4, Charsets.US_ASCII)
            if (status != "OKAY") throw InstallerException("Unexpected upload status from the TV: $status")
            runCatching { sendPacket(A_WRTE, stream.localId, stream.remoteId, syncHeader("QUIT", 0)) }
        } finally {
            closeStream(stream)
        }
    }

    private fun failIfSyncError(bytes: ByteArray, remotePath: String) {
        if (bytes.size >= 8 && String(bytes, 0, 4, Charsets.US_ASCII) == "FAIL") {
            val length = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val message = String(bytes, 8, minOf(length, bytes.size - 8).coerceAtLeast(0), Charsets.UTF_8)
            throw InstallerException("The TV rejected the upload of $remotePath: $message")
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}
