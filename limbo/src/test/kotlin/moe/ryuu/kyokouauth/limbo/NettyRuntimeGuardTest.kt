package moe.ryuu.kyokouauth.limbo

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NettyRuntimeGuardTest {
    @Test
    fun `missing provided transport is an explicit closed admission error`() {
        val missing = object : ClassLoader(null) {
            override fun loadClass(name: String): Class<*> = throw ClassNotFoundException(name)
        }
        val failure = assertFailsWith<IllegalStateException> { NettyRuntimeGuard.check(missing) }
        assertTrue(failure.message!!.contains("authentication remains closed"))
        assertTrue(failure.cause is ClassNotFoundException)
    }

    @Test
    fun `failed transport linkage opens no engines and cleanup remains idempotent`() {
        // This module deliberately has no platform Netty on its test runtime classpath.
        val server = LimboServer(settings = LimboSettings(), forwardingSecret = byteArrayOf(1, 2, 3))
        try {
            val failure = assertFailsWith<IllegalStateException> { server.startAll() }
            assertTrue(failure.message!!.contains("authentication remains closed"))
            assertEquals(0, server.connectionCount())
        } finally {
            server.close()
            server.close()
        }
    }
}
