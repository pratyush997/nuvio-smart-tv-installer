package tv.nuvio.installer.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal object Http {
    const val USER_AGENT = "Nuvio-TV-Installer-Android"

    private fun open(url: String, timeoutMs: Int): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }

    private fun HttpURLConnection.bodyOrThrow(what: String): ByteArray {
        val code = responseCode
        if (code !in 200..299) {
            val detail = runCatching { errorStream?.use { String(it.readBytes()) } }.getOrNull().orEmpty().take(500)
            throw InstallerException("$what failed: HTTP $code ${responseMessage.orEmpty()} $detail".trim())
        }
        return inputStream.use { it.readBytes() }
    }

    fun getText(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 30_000): String {
        val connection = open(url, timeoutMs)
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        try {
            return String(connection.bodyOrThrow("Request to $url"), Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private class RetryableException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** Downloads with up to 4 attempts; GitHub's download CDN occasionally answers 5xx. */
    fun download(url: String, target: File, label: String, events: InstallerEvents, headers: Map<String, String> = emptyMap()) {
        var lastError: Exception? = null
        for (attempt in 1..4) {
            try {
                downloadOnce(url, target, label, events, headers)
                return
            } catch (e: IOException) {
                lastError = e
                if (attempt < 4) {
                    events.info("Download interrupted (${e.message}); retrying (${attempt + 1}/4)...")
                    Thread.sleep(1500L * attempt)
                }
            }
        }
        val name = url.substringAfterLast('/')
        throw InstallerException("Couldn't download $name (${lastError?.message}). GitHub may be busy; try again in a minute.", lastError)
    }

    private fun downloadOnce(url: String, target: File, label: String, events: InstallerEvents, headers: Map<String, String>) {
        val connection = open(url, 60_000)
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        try {
            val code = connection.responseCode
            if (code in 500..599 || code == 429) throw RetryableException("HTTP $code")
            if (code !in 200..299) throw InstallerException("Download failed: HTTP $code for ${url.substringAfterLast('/')}")
            val total = connection.contentLengthLong
            target.parentFile?.mkdirs()
            val temp = File(target.path + ".part")
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (done - lastReport > 256 * 1024 || done == total) {
                            events.progress(label, done, total)
                            lastReport = done
                        }
                    }
                    events.progress(label, done, if (total > 0) total else done)
                }
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) throw IOException("Unable to move download into place: $target")
        } finally {
            connection.disconnect()
        }
    }

    class FilePart(val name: String, val fileName: String, val contentType: String, val data: ByteArray)

    fun postMultipart(url: String, fields: List<Pair<String, String>>, files: List<FilePart>, what: String): String {
        val boundary = "----NuvioBoundary" + UUID.randomUUID().toString().replace("-", "")
        val body = ByteArrayOutputStream()
        fun line(text: String) = body.write((text + "\r\n").toByteArray(Charsets.UTF_8))
        for ((name, value) in fields) {
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"$name\"")
            line("")
            line(value)
        }
        for (file in files) {
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"${file.name}\"; filename=\"${file.fileName}\"")
            line("Content-Type: ${file.contentType}")
            line("")
            body.write(file.data)
            line("")
        }
        line("--$boundary--")

        val connection = open(url, 60_000)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            val bytes = body.toByteArray()
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            return String(connection.bodyOrThrow(what), Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }
}

/** Downloads [url] to [target], reporting progress under [label]. */
fun downloadFile(url: String, target: File, label: String, events: InstallerEvents, headers: Map<String, String> = emptyMap()) =
    Http.download(url, target, label, events, headers)
