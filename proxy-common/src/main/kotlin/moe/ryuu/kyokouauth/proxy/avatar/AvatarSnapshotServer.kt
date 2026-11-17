package moe.ryuu.kyokouauth.proxy.avatar

import moe.ryuu.kyokouauth.core.avatar.AvatarJson
import moe.ryuu.kyokouauth.core.avatar.AvatarLimits
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Tiny read-only HTTP/1 endpoint: bounds are enforced while reading, before header allocation. */
internal class AvatarSnapshotServer(private val port: Int, private val lookup: (Long) -> RoleAvatarSnapshot?) : AutoCloseable {
    private val listener = ServerSocket()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "role-avatar-http").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val deadlines = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "role-avatar-http-deadline").apply { isDaemon = true } }
    @Volatile private var closed = false

    fun start() {
        deadlines.removeOnCancelPolicy = true
        listener.reuseAddress = false
        try {
            listener.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), 8)
            Thread({ accept() }, "role-avatar-http-accept").apply { isDaemon = true }.start()
        } catch (failure: Exception) { close(); throw failure }
    }

    private fun accept() {
        while (!closed) {
            val socket = try { listener.accept() } catch (_: Exception) { return }
            if (closed) { socket.close(); return }
            socket.soTimeout = 750
            socket.sendBufferSize = 64 * 1024
            socket.tcpNoDelay = true
            sockets += socket
            val timeout = try { deadlines.schedule({ runCatching { socket.close() } }, 1_000, TimeUnit.MILLISECONDS) }
                catch (_: Exception) { sockets -= socket; socket.close(); continue }
            try {
                workers.execute {
                    try { handle(socket) } finally {
                        timeout.cancel(false)
                        sockets -= socket
                        runCatching { socket.close() }
                    }
                }
            } catch (_: Exception) {
                timeout.cancel(false)
                sockets -= socket
                runCatching { socket.close() }
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream(), 1024)
            val first = line(input, 512)
            var headerBytes = first.length + 2
            val parts = first.split(' ')
            if (parts.size != 3 || parts[2] !in setOf("HTTP/1.0", "HTTP/1.1")) return respond(socket, 400)
            val headers = mutableMapOf<String, String>()
            while (true) {
                val next = line(input, 512)
                headerBytes += next.length + 2
                if (headerBytes > 4096 || headers.size >= 16 && next.isNotEmpty()) return respond(socket, 431)
                if (next.isEmpty()) break
                val colon = next.indexOf(':')
                if (colon <= 0) return respond(socket, 400)
                val key = next.substring(0, colon).lowercase()
                val value = next.substring(colon + 1).trim()
                if (!key.matches(Regex("[a-z0-9-]{1,64}")) || headers.putIfAbsent(key, value) != null) return respond(socket, 400)
            }
            val expectedHost = "127.0.0.1:$port"
            if (parts[2] == "HTTP/1.1" && headers["host"] != expectedHost && !(port == 80 && headers["host"] == "127.0.0.1")) return respond(socket, 400)
            if (headers.containsKey("transfer-encoding") || headers["content-length"]?.let { it != "0" } == true) return respond(socket, 400)
            if (parts[0] != "GET") return respond(socket, 405)
            val match = Regex("/v1/avatars\\?xuid=([1-9][0-9]{0,18})").matchEntire(parts[1])
                ?: return respond(socket, if (parts[1].startsWith("/v1/avatars")) 400 else 404)
            val xuid = match.groupValues[1].toLongOrNull()?.takeIf { it > 0 } ?: return respond(socket, 400)
            val snapshot = lookup(xuid)
            if (snapshot == null) respond(socket, 202, AvatarJson.notReady()) else respond(socket, 200, AvatarJson.ready(snapshot))
        } catch (_: Exception) {
            // Slow/malformed/closed clients receive no data and trigger no prepare, IO or logging.
        }
    }

    private fun line(input: BufferedInputStream, maximum: Int): String {
        val bytes = ByteArrayOutputStream(128)
        while (true) {
            val next = input.read()
            if (next == 13) {
                if (input.read() != 10) throw IllegalArgumentException("Invalid HTTP framing")
                return bytes.toString(Charsets.US_ASCII)
            }
            if (next !in 32..126 || bytes.size() >= maximum) throw IllegalArgumentException("Invalid HTTP line")
            bytes.write(next)
        }
    }

    private fun respond(socket: Socket, status: Int, body: ByteArray = "{\"schema\":1,\"state\":\"invalid-request\"}".toByteArray()) {
        if (body.size > AvatarLimits.SNAPSHOT_WIRE_BYTES) throw IllegalArgumentException("Response budget")
        val reason = when (status) { 200 -> "OK"; 202 -> "Accepted"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 431 -> "Request Header Fields Too Large"; else -> "Bad Request" }
        val header = "HTTP/1.1 $status $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }

    override fun close() {
        closed = true
        runCatching { listener.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        workers.shutdownNow()
        deadlines.shutdownNow()
    }
}
