package moe.ryuu.kyokouauth.bungee

import net.md_5.bungee.api.connection.PendingConnection
import net.md_5.bungee.api.connection.ProxiedPlayer
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Transport only. A returned byte array is untrusted; the shared Gate must redeem its ticket. */
internal class BungeeRoleTransfer(private val timeoutMillis: Long = 3_000) : AutoCloseable {
    private val pending = IdentityHashMap<PendingConnection, CompletableFuture<ByteArray?>>()
    private var closed = false

    init { require(timeoutMillis in 1..30_000) }

    /** Call while the original native PreLogin intent is still held. */
    fun retrieve(connection: PendingConnection): CompletableFuture<ByteArray?> {
        val result = CompletableFuture<ByteArray?>()
        synchronized(pending) {
            if (closed || pending.size >= 4096 || pending.containsKey(connection)) return failed("Role cookie bridge closed or already pending")
            if (!connection.isConnected || connection.version !in 767..777) return failed("Unsupported or closed role connection")
            pending[connection] = result
        }
        result.whenComplete { _, _ -> synchronized(pending) { if (pending[connection] === result) pending.remove(connection) } }
        try {
            synchronized(pending) {
                if (closed || result.isDone) return result
                val native = connection.retrieveCookie(COOKIE_KEY)
                result.whenComplete { _, error -> if (error != null) native.cancel(false) }
                native.whenComplete { bytes, error ->
                    synchronized(pending) {
                        if (result.isDone) return@whenComplete
                        when {
                            closed || !connection.isConnected -> result.completeExceptionally(IllegalStateException("Role connection closed"))
                            error != null -> result.completeExceptionally(error)
                            bytes != null && bytes.size > MAX_COOKIE_BYTES -> result.completeExceptionally(IllegalArgumentException("Role cookie exceeds limit"))
                            else -> result.complete(bytes?.copyOf())
                        }
                    }
                }
                result.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            }
        } catch (error: Exception) {
            result.completeExceptionally(error)
        }
        return result
    }

    /** A successful future means only that native store/transfer calls were submitted in order. */
    fun transfer(player: ProxiedPlayer, host: String, port: Int, cookie: ByteArray?): CompletableFuture<Unit> {
        return try {
            synchronized(pending) {
                check(!closed && player.isConnected) { "Role connection closed" }
                require(player.pendingConnection.version in 767..777) { "Unsupported role transfer protocol" }
                require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it.isISOControl() } && port in 1..65535)
                require(cookie == null || cookie.size in 1..MAX_COOKIE_BYTES) { "Invalid role cookie size" }
                if (cookie != null) player.storeCookie(COOKIE_KEY, cookie.copyOf())
                player.transfer(host, port)
            }
            CompletableFuture.completedFuture(Unit)
        } catch (error: Exception) { CompletableFuture.failedFuture(error) }
    }

    fun cancel(connection: PendingConnection) {
        synchronized(pending) { pending[connection]?.completeExceptionally(IllegalStateException("Role connection closed")) }
    }

    override fun close() {
        synchronized(pending) {
            if (closed) return
            closed = true
            pending.values.toList().forEach { it.completeExceptionally(IllegalStateException("Role cookie bridge closed")) }
            pending.clear()
        }
    }

    private fun <T> failed(message: String): CompletableFuture<T> = CompletableFuture.failedFuture(IllegalStateException(message))

    companion object {
        const val COOKIE_KEY = "kyokouauth:role_transfer"
        const val MAX_COOKIE_BYTES = 512
    }
}
