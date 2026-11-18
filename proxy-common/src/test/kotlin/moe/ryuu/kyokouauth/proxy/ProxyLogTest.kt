package moe.ryuu.kyokouauth.proxy

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ProxyLogTest {
    private data class Entry(val level: ProxyLog.Level, val message: String, val error: Throwable?)
    private val entries = mutableListOf<Entry>()
    private val log = ProxyLog { level, message, error -> entries += Entry(level, message, error) }

    @Test
    fun `unformatted messages preserve template identity and empty messages`() {
        val template = charArrayOf('{', '}', ' ', 'x').concatToString()
        log.info(template)
        log.warn("")
        assertSame(template, entries.first().message)
        assertEquals("", entries.last().message)
        entries.forEach { assertNull(it.error) }
    }

    @Test
    fun `adjacent placeholders and argument braces are not reparsed`() {
        log.info("{}{}{} tail", "{}", "next", null)
        assertEquals("{}nextnull tail", entries.single().message)
    }

    @Test
    fun `formatting replaces placeholders in order and preserves literal replacement text`() {
        log.info("{} logged in with {} and {}", "Steve", "\$1\\{}", null)
        val entry = entries.single()
        assertEquals(ProxyLog.Level.INFO, entry.level)
        assertEquals("Steve logged in with \$1\\{} and null", entry.message)
        assertNull(entry.error)
    }

    @Test
    fun `missing placeholders remain visible and excess arguments do not change the message`() {
        log.warn("{} still waiting for {}", "Steve")
        log.info("No placeholders", "unused", 17)
        assertEquals(listOf("Steve still waiting for {}", "No placeholders"), entries.map { it.message })
        assertEquals(listOf(ProxyLog.Level.WARN, ProxyLog.Level.INFO), entries.map { it.level })
        entries.forEach { assertNull(it.error) }
    }

    @Test
    fun `a trailing exception retains its identity cause and unfilled placeholder`() {
        val cause = IllegalArgumentException("bad token")
        val failure = IllegalStateException("exchange failed", cause)
        log.error("Could not verify {}: {}", "Steve", failure)
        val entry = entries.single()
        assertEquals(ProxyLog.Level.ERROR, entry.level)
        assertEquals("Could not verify Steve: {}", entry.message)
        assertSame(failure, entry.error)
        assertSame(cause, entry.error?.cause)
    }

    @Test
    fun `an exception without formatting values is still passed separately at every level`() {
        val failure = java.io.IOException("closed channel")
        log.info("Info failed", failure)
        log.warn("Warning failed", failure)
        log.error("Error failed", failure)
        assertEquals(listOf(ProxyLog.Level.INFO, ProxyLog.Level.WARN, ProxyLog.Level.ERROR), entries.map { it.level })
        assertEquals(listOf("Info failed", "Warning failed", "Error failed"), entries.map { it.message })
        entries.forEach { assertSame(failure, it.error) }
    }

    @Test
    fun `an exception in a non trailing position is a formatting value not the logged failure`() {
        val value = IllegalStateException("upstream broke")
        log.warn("Failure {} returned status {}", value, 503)
        val entry = entries.single()
        assertEquals("Failure java.lang.IllegalStateException: upstream broke returned status 503", entry.message)
        assertNull(entry.error)
    }
}
