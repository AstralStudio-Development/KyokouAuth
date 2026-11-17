package moe.ryuu.kyokouauth.proxy

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

/** Bounded blocking work and lightweight timers; failure and shutdown never become successful nulls. */
internal class AuthTasks(
    private val logger: ProxyLog,
    workers: Int = 4,
    queueCapacity: Int = 256,
    private val shutdownWaitMillis: Long = 5_000L,
) : AutoCloseable {
    private val closing = AtomicBoolean(false)
    private val work = ConcurrentHashMap.newKeySet<Task<*>>()
    private val io = ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity), threads("KyokouAuth-IO"), ThreadPoolExecutor.AbortPolicy())
    private val timers = ScheduledThreadPoolExecutor(1, threads("KyokouAuth-Scheduler")).apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
        continueExistingPeriodicTasksAfterShutdownPolicy = false
    }

    data class Snapshot(val queued: Int, val active: Int, val scheduled: Int, val tracked: Int, val closed: Boolean)
    fun snapshot() = Snapshot(io.queue.size, io.activeCount, timers.queue.size, work.size, closing.get())

    private inner class Task<T>(val completion: CompletableFuture<T>, action: Callable<T>) : FutureTask<T>(action) {
        override fun done() {
            work.remove(this)
            io.remove(this)
            try { completion.complete(get()) }
            catch (_: CancellationException) { completion.cancel(false) }
            catch (failure: ExecutionException) { completion.completeExceptionally(failure.cause ?: failure) }
            catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                completion.completeExceptionally(failure)
            }
        }
        fun reject(failure: RejectedExecutionException) {
            completion.completeExceptionally(failure)
            cancel(false)
        }
    }

    private fun ensureOpen() { if (closing.get()) throw RejectedExecutionException("Authentication work is closed") }
    private fun <T> enqueue(action: () -> T, onRejection: (RejectedExecutionException) -> Unit): CompletableFuture<T> {
        val completion = CompletableFuture<T>()
        val task = Task(completion, Callable(action))
        completion.whenComplete { _, _ -> if (completion.isCancelled) task.cancel(true) }
        work.add(task)
        try { ensureOpen(); io.execute(task) }
        catch (failure: RejectedExecutionException) { task.reject(failure); onRejection(failure) }
        return completion
    }

    /** Completion contract: execution and admission failures complete exceptionally. */
    fun <T> submit(action: () -> T): CompletableFuture<T> = enqueue(action) { }

    /** Admission contract: overload/close is synchronous, so event intent cleanup cannot be lost. */
    fun execute(action: () -> Unit) {
        enqueue(action) { throw it }.whenComplete { _, failure ->
            if (failure != null && failure !is CancellationException) logger.error("Login task failed", failure)
        }
    }

    fun schedule(delayMillis: Long, action: () -> Unit): ScheduledFuture<*> {
        ensureOpen()
        require(delayMillis >= 0)
        return timers.schedule({ runTimer(action) }, delayMillis, TimeUnit.MILLISECONDS)
    }

    fun scheduleAtFixedRate(periodMillis: Long, action: () -> Unit): ScheduledFuture<*> {
        ensureOpen()
        require(periodMillis > 0)
        return timers.scheduleAtFixedRate({ runTimer(action) }, periodMillis, periodMillis, TimeUnit.MILLISECONDS)
    }

    private fun runTimer(action: () -> Unit) {
        if (!closing.get()) try { action() } catch (failure: Exception) { logger.error("Scheduled login task failed", failure) }
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        timers.shutdownNow()
        io.shutdownNow().forEach { (it as? FutureTask<*>)?.cancel(false) }
        work.toList().forEach { it.cancel(true) }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownWaitMillis)
        try {
            val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
            if (!io.awaitTermination(remaining, TimeUnit.NANOSECONDS)) logger.warn("Authentication workers did not stop within the shutdown deadline")
            timers.awaitTermination((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun threads(prefix: String): ThreadFactory {
        val sequence = AtomicInteger()
        return ThreadFactory { runnable -> Thread(runnable, "$prefix-${sequence.incrementAndGet()}").apply { isDaemon = true } }
    }
}
