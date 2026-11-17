package moe.ryuu.kyokouauth.proxy

import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuthTasksTest {
    private val quiet = ProxyLog { _, _, _ -> }

    @Test
    fun `future failures retain their cause and never masquerade as successful null`() {
        AuthTasks(quiet, workers = 1, queueCapacity = 2).use { tasks ->
            assertEquals(17, tasks.submit { 17 }.get(3, TimeUnit.SECONDS))
            val cause = IOException("controlled failure")
            val failure = assertFailsWith<ExecutionException> { tasks.submit<Int> { throw cause }.get(3, TimeUnit.SECONDS) }
            assertSame(cause, failure.cause)
            assertNull(tasks.submit<String?> { null }.get(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `saturation is bounded and rejects admission without running on the caller`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        AuthTasks(quiet, workers = 1, queueCapacity = 1).use { tasks ->
            try {
                val first = tasks.submit { started.countDown(); release.await(); 1 }
                assertTrue(started.await(3, TimeUnit.SECONDS))
                val queued = tasks.submit { 2 }
                val failure = assertFailsWith<ExecutionException> { tasks.submit { 3 }.get(3, TimeUnit.SECONDS) }
                assertTrue(failure.cause is RejectedExecutionException)
                val ran = AtomicBoolean(false)
                assertFailsWith<RejectedExecutionException> { tasks.execute { ran.set(true) } }
                assertTrue(!ran.get())
                assertEquals(1, tasks.snapshot().queued)
                release.countDown()
                assertEquals(1, first.get(3, TimeUnit.SECONDS))
                assertEquals(2, queued.get(3, TimeUnit.SECONDS))
            } finally { release.countDown() }
        }
    }

    @Test
    fun `cancelled queued work immediately releases capacity`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        AuthTasks(quiet, workers = 1, queueCapacity = 1).use { tasks ->
            try {
                tasks.submit { started.countDown(); release.await() }
                assertTrue(started.await(3, TimeUnit.SECONDS))
                val queued = tasks.submit { error("cancelled work ran") }
                assertTrue(queued.cancel(false))
                assertEquals(0, tasks.snapshot().queued)
                val replacement = tasks.submit { 9 }
                release.countDown()
                assertEquals(9, replacement.get(3, TimeUnit.SECONDS))
            } finally { release.countDown() }
        }
    }

    @Test
    fun `cancelled deadlines are removed from the timer queue`() {
        AuthTasks(quiet).use { tasks ->
            val future = tasks.schedule(300_000) { error("cancelled timer ran") }
            assertEquals(1, tasks.snapshot().scheduled)
            future.cancel(false)
            assertEquals(0, tasks.snapshot().scheduled)
        }
    }

    @Test
    fun `close cancels outstanding futures and rejects subsequent work idempotently`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val tasks = AuthTasks(quiet, workers = 1, queueCapacity = 1)
        try {
            val active = tasks.submit { started.countDown(); release.await() }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val queued = tasks.submit { error("shutdown queue ran") }
            tasks.close()
            tasks.close()
            assertTrue(active.isDone && queued.isDone)
            assertEquals(0, tasks.snapshot().tracked)
            assertEquals(0, tasks.snapshot().scheduled)
            assertEquals(0, tasks.snapshot().queued)
            assertFailsWith<RejectedExecutionException> { tasks.execute { } }
            assertFailsWith<RejectedExecutionException> { tasks.schedule(1) { } }
            val failed = assertFailsWith<ExecutionException> { tasks.submit { 4 }.get(3, TimeUnit.SECONDS) }
            assertTrue(failed.cause is RejectedExecutionException)
        } finally { release.countDown(); tasks.close() }
    }

    @Test
    fun `close preserves a caller interruption`() {
        val preserved = AtomicBoolean(false)
        val finished = CountDownLatch(1)
        val thread = Thread {
            val tasks = AuthTasks(quiet)
            Thread.currentThread().interrupt()
            tasks.close()
            preserved.set(Thread.currentThread().isInterrupted)
            Thread.interrupted()
            finished.countDown()
        }
        thread.start()
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertTrue(preserved.get())
        thread.join(1000)
    }
}
