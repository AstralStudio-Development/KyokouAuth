package moe.ryuu.kyokouauth.velocity

import com.velocitypowered.api.event.Continuation
import com.velocitypowered.api.event.EventTask
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Bounded native event lifetimes, not authentication. Only the supplied bounded auth-IO submitter
 * may run compute; the JDK deadline thread only refuses/closes/resumes a native connection.
 */
internal class VelocityRoleEventTasks(
    private val capacity: Int,
    private val submit: (() -> Unit) -> Unit,
    private val timeoutMillis: Long = 35_000,
    private val deadline: (Long) -> CompletableFuture<Unit> = {
        CompletableFuture<Unit>().orTimeout(it, TimeUnit.MILLISECONDS)
    },
) : AutoCloseable {
    private val lock = Any()
    private val pending = HashMap<UUID, Slot<*>>()
    private var closed = false

    init { require(capacity > 0 && timeoutMillis in 1..35_000) }

    fun <S, T> task(
        sessionId: UUID,
        active: () -> Boolean,
        watchClosed: (() -> Unit) -> AutoCloseable,
        source: () -> CompletableFuture<S>,
        compute: (S) -> T,
        apply: (T) -> Unit,
        refuse: () -> Unit,
    ): EventTask = EventTask.withContinuation { continuation ->
        val slot = Slot(sessionId, active, apply, refuse, continuation)
        val previous: Slot<*>?
        val accepted = synchronized(lock) {
            previous = pending[sessionId]
            if (closed || previous != null || pending.size >= capacity) false
            else { pending[sessionId] = slot; true }
        }
        if (!accepted) {
            // An overlapping stage cannot let an older callback reopen this physical connection.
            previous?.fail()
            slot.fail()
            return@withContinuation
        }
        try {
            val timer = deadline(timeoutMillis)
            slot.track(AutoCloseable { timer.cancel(false) })
            timer.whenComplete { _, _ -> if (!timer.isCancelled) slot.fail() }
            if (!slot.mayRun()) { slot.fail(); return@withContinuation }
            slot.track(watchClosed { slot.fail() })
            if (!slot.mayRun()) { slot.fail(); return@withContinuation }
            val input = source()
            slot.track(AutoCloseable { input.cancel(false) })
            input.whenComplete { value, failure ->
                if (failure != null || !slot.mayRun()) slot.fail()
                else try {
                    submit {
                        if (!slot.mayRun()) { slot.fail(); return@submit }
                        try {
                            // Keep the deadline armed across all HTTP/storage/common computation.
                            val result = compute(value)
                            slot.succeed(result)
                        } catch (_: Exception) { slot.fail() }
                        catch (_: LinkageError) { slot.fail() }
                    }
                } catch (_: Exception) { slot.fail() }
                catch (_: LinkageError) { slot.fail() }
            }
        } catch (_: Exception) { slot.fail() }
        catch (_: LinkageError) { slot.fail() }
    }

    fun cancel(sessionId: UUID) { synchronized(lock) { pending[sessionId] }?.fail() }
    fun pendingCount(): Int = synchronized(lock) { pending.size }

    override fun close() {
        val slots = synchronized(lock) {
            if (closed) return
            closed = true
            pending.values.toList()
        }
        slots.forEach { it.fail() }
    }

    private inner class Slot<T>(
        val sessionId: UUID,
        val active: () -> Boolean,
        val apply: (T) -> Unit,
        val refuse: () -> Unit,
        val continuation: Continuation,
    ) {
        private var done = false
        private val resources = ArrayList<AutoCloseable>(3)

        fun mayRun(): Boolean = synchronized(lock) { !done && !closed && runCatching(active).getOrDefault(false) }

        fun track(resource: AutoCloseable) {
            val release = synchronized(lock) { if (done) true else { resources += resource; false } }
            if (release) runCatching { resource.close() }
        }

        fun fail() = finish(null)
        fun succeed(result: T) = finish { apply(result) }

        private fun finish(success: (() -> Unit)?) {
            val cleanup = synchronized(lock) {
                if (done) return
                val allowed = success != null && !closed && runCatching(active).getOrDefault(false)
                done = true
                if (pending[sessionId] === this) pending.remove(sessionId)
                // The winning completion owns only a short native result write, never common IO.
                if (allowed) {
                    if (runCatching { success!!.invoke() }.isFailure) runCatching(refuse)
                } else runCatching(refuse)
                resources.toList().also { resources.clear() }
            }
            // Remove channel listeners and cancel source/deadline before releasing native login.
            try { cleanup.forEach { resource -> runCatching { resource.close() } } }
            finally { continuation.resume() }
        }
    }
}
