package moe.ryuu.kyokouauth.velocity

import com.velocitypowered.api.event.Continuation
import com.velocitypowered.api.event.EventTask
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import kotlin.test.*

class VelocityRoleEventTasksTest {
    private class Fixture(capacity: Int = 8) {
        val queue = ArrayDeque<() -> Unit>()
        val deadlines = mutableListOf<CompletableFuture<Unit>>()
        val tasks = VelocityRoleEventTasks(capacity, { queue.addLast(it) }, deadline = { delay ->
            assertEquals(35_000L, delay)
            CompletableFuture<Unit>().also { deadlines += it }
        })
    }

    private class Request(val fixture: Fixture, val id: UUID = UUID.randomUUID()) {
        var active = true
        var resumes = 0
        var refuses = 0
        var computes = 0
        var applies = 0
        var watched = 0
        var released = 0
        var closeNative: (() -> Unit)? = null
        val source = CompletableFuture<Int>()
        val continuation = object : Continuation {
            override fun resume() { resumes++ }
            override fun resumeWithException(error: Throwable): Unit = throw AssertionError("Failure must deny and resume, not strand native login", error)
        }
        fun task(compute: (Int) -> Int = { computes++; it }): EventTask = fixture.tasks.task(
            id, active = { active },
            watchClosed = { action -> watched++; closeNative = action; AutoCloseable { released++ } },
            source = { source }, compute = compute,
            apply = { applies++ }, refuse = { refuses++; active = false },
        )
        fun start(compute: (Int) -> Int = { computes++; it }) { task(compute).execute(continuation) }
    }

    @Test fun `dropped queued auth work still denies closes and resumes once by bounded deadline`() {
        val fixture = Fixture()
        val request = Request(fixture)
        request.source.complete(7)
        request.start()
        assertEquals(1, fixture.queue.size)
        assertEquals(0, request.resumes)
        val dropped = fixture.queue.removeFirst()
        fixture.deadlines.single().completeExceptionally(TimeoutException("controlled timeout"))
        assertEquals(1, request.refuses)
        assertEquals(1, request.resumes)
        assertFalse(request.active)
        assertEquals(0, fixture.tasks.pendingCount())
        assertEquals(1, request.released)
        dropped()
        request.closeNative?.invoke()
        fixture.tasks.close()
        assertEquals(0, request.computes)
        assertEquals(0, request.applies)
        assertEquals(1, request.resumes)
    }

    @Test fun `deadline remains armed during common computation and late result cannot apply allowed`() {
        val fixture = Fixture()
        val request = Request(fixture)
        request.source.complete(7)
        request.start { value ->
            assertFalse(fixture.deadlines.single().isDone, "HTTP/common work must run before completion cancels its timer")
            request.computes++
            fixture.deadlines.single().completeExceptionally(TimeoutException("during IO"))
            assertEquals(1, request.resumes)
            value
        }
        fixture.queue.removeFirst()()
        assertEquals(1, request.computes)
        assertEquals(0, request.applies)
        assertEquals(1, request.refuses)
        assertEquals(1, request.resumes)
        fixture.tasks.close()
    }

    @Test fun `deactivation completes source wait and discarded queue without callbacks and late work stays denied`() {
        for (queued in listOf(false, true)) {
            val fixture = Fixture()
            val request = Request(fixture)
            if (queued) request.source.complete(3)
            request.start()
            val late = fixture.queue.removeFirstOrNull()
            fixture.tasks.close()
            fixture.tasks.close()
            assertEquals(1, request.refuses)
            assertEquals(1, request.resumes)
            assertEquals(1, request.released)
            assertTrue(fixture.deadlines.single().isCancelled)
            if (!queued) assertTrue(request.source.isCancelled)
            request.source.complete(9)
            late?.invoke()
            assertEquals(0, request.computes)
            assertEquals(0, request.applies)
            assertEquals(0, fixture.tasks.pendingCount())
        }
    }

    @Test fun `exact connection close or disconnect cancellation resumes only its own native task`() {
        for (channelClose in listOf(false, true)) {
            val fixture = Fixture()
            val first = Request(fixture)
            val second = Request(fixture)
            first.start(); second.start()
            if (channelClose) first.closeNative?.invoke() else fixture.tasks.cancel(first.id)
            assertEquals(1, first.resumes)
            assertEquals(1, first.refuses)
            assertEquals(0, second.resumes)
            second.source.complete(5)
            fixture.queue.removeFirst()()
            assertEquals(1, second.applies)
            assertEquals(1, second.resumes)
            assertEquals(0, second.refuses)
            assertEquals(0, fixture.tasks.pendingCount())
            fixture.tasks.close()
        }
    }

    @Test fun `successful result applies once only after bounded worker runs and cleans timer and watcher before resume`() {
        val fixture = Fixture()
        val request = Request(fixture)
        request.start()
        request.source.complete(2)
        assertEquals(0, request.computes)
        assertEquals(0, request.applies)
        assertEquals(0, request.resumes)
        fixture.queue.removeFirst()()
        assertEquals(1, request.computes)
        assertEquals(1, request.applies)
        assertEquals(1, request.resumes)
        assertEquals(0, request.refuses)
        assertEquals(1, request.released)
        assertTrue(fixture.deadlines.single().isCancelled)
        assertEquals(0, fixture.tasks.pendingCount())
        request.closeNative?.invoke()
        assertEquals(1, request.resumes)
        fixture.tasks.close()
    }

    @Test fun `source failures cancellation compute failures and submission rejection never become allowed`() {
        for (failure in listOf("source", "cancel", "compute", "submit")) {
            val fixture = Fixture()
            val request = Request(fixture)
            if (failure == "submit") {
                val tasks = VelocityRoleEventTasks(1, { throw IllegalStateException("queue rejected") },
                    deadline = { CompletableFuture() })
                tasks.task(request.id, { true }, { AutoCloseable {} },
                    { CompletableFuture.completedFuture(1) }, { it }, { request.applies++ }, { request.refuses++ })
                    .execute(request.continuation)
                assertEquals(1, request.refuses)
                assertEquals(1, request.resumes)
                assertEquals(0, request.applies)
                tasks.close()
                fixture.tasks.close()
                continue
            }
            request.start { if (failure == "compute") error("controlled common failure") else it }
            when (failure) {
                "source" -> request.source.completeExceptionally(IllegalStateException("native failure"))
                "cancel" -> request.source.cancel(false)
                else -> { request.source.complete(1); fixture.queue.removeFirst()() }
            }
            assertEquals(1, request.refuses)
            assertEquals(1, request.resumes)
            assertEquals(0, request.applies)
            fixture.tasks.close()
        }
    }

    @Test fun `capacity and duplicate session claims fail closed without unbounded queued work`() {
        val fixture = Fixture(capacity = 1)
        val first = Request(fixture)
        val overflow = Request(fixture)
        first.start(); overflow.start()
        assertEquals(1, fixture.tasks.pendingCount())
        assertEquals(1, overflow.refuses)
        assertEquals(0, overflow.watched)
        val duplicate = Request(fixture, first.id)
        duplicate.start()
        assertEquals(1, first.refuses)
        assertEquals(1, duplicate.refuses)
        assertEquals(0, fixture.tasks.pendingCount())
        assertTrue(fixture.queue.isEmpty())
        fixture.tasks.close()
    }
}
