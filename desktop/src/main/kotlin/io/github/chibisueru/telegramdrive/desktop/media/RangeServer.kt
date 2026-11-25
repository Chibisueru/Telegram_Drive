package io.github.chibisueru.telegramdrive.desktop.media

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** A file the server can hand out: its size and type, and how to read bytes from a position. */
interface RangeSource {
    val size: Long
    val contentType: String
    /** Called once a response starts at [start] (e.g. to point the download there). */
    fun onStart(start: Long) {}
    /** Fills [buf] with up to [max] bytes from [pos], blocking until they're available. */
    fun read(pos: Long, buf: ByteArray, max: Int): Int
    fun close() {}
}

/**
 * A minimal HTTP server on 127.0.0.1 for the built-in players: GET/HEAD with byte ranges, one
 * thread per connection, the connection closed after each response. Players (FFmpeg) open a new
 * connection for every seek, so each request simply starts reading at its own position.
 */
class RangeServer(private val resolve: (path: String) -> RangeSource?) {
    private val socket = ServerSocket(0, 32, InetAddress.getLoopbackAddress())
    val port: Int get() = socket.localPort

    init {
        thread(isDaemon = true, name = "range-server") {
            while (!socket.isClosed) {
                val s = runCatching { socket.accept() }.getOrNull() ?: continue
                thread(isDaemon = true, name = "range-conn") { s.use { runCatching { serve(it) } } }
            }
        }
    }

    fun close() = runCatching { socket.close() }

    private fun serve(s: Socket) {
        s.soTimeout = 30_000
        val input = BufferedInputStream(s.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: return
        val path = parts.getOrNull(1) ?: return
        val out = s.getOutputStream()
        if (method != "GET" && method != "HEAD") return respond(out, 405, "Method Not Allowed", emptyMap())
        val source = runCatching { resolve(path) }.getOrNull() ?: return respond(out, 404, "Not Found", emptyMap())
        try {
            val size = source.size
            val range = headers["range"]?.let { Regex("""bytes=(\d*)-(\d*)""").find(it) }
            val start = range?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val end = (range?.groupValues?.get(2)?.toLongOrNull() ?: (size - 1)).coerceAtMost(size - 1)
            if (start >= size || end < start) {
                // e.g. a player probing exactly at the end of the file.
                return respond(out, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$size", "Accept-Ranges" to "bytes"))
            }
            val partial = range != null
            val hdrs = buildMap {
                put("Accept-Ranges", "bytes")
                put("Content-Type", source.contentType)
                put("Content-Length", (end - start + 1).toString())
                if (partial) put("Content-Range", "bytes $start-$end/$size")
            }
            respond(out, if (partial) 206 else 200, if (partial) "Partial Content" else "OK", hdrs)
            if (method == "HEAD") return
            source.onStart(start)
            val buf = ByteArray(256 * 1024)
            var pos = start
            while (pos <= end) {
                val n = source.read(pos, buf, minOf(buf.size.toLong(), end - pos + 1).toInt())
                if (n <= 0) break
                out.write(buf, 0, n) // throws once the player has closed the connection (e.g. after a seek)
                pos += n
            }
            out.flush()
        } catch (_: IOException) {
            // The player went away or seeked elsewhere.
        } finally {
            source.close()
        }
    }

    private fun respond(out: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\nConnection: close\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        if ("Content-Length" !in headers) sb.append("Content-Length: 0\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }
}
