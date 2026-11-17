package moe.ryuu.kyokouauth.core.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YamlDocumentTest {
    @TempDir lateinit var directory: Path

    @Test fun `file and byte readers preserve UTF-8 and flattened values`() {
        val yaml = """
            login:
              message: 验证通过
              enabled: true
              aliases:
                - first
                - second
            absent: null
        """.trimIndent()
        val expected = mapOf(
            "login.message" to "验证通过",
            "login.enabled" to "true",
            "login.aliases" to "first\nsecond",
        )
        val file = Files.writeString(directory.resolve("messages.yml"), yaml)
        assertEquals(expected, YamlDocument.load(file.toFile()))
        assertEquals(expected, YamlDocument.load(yaml.toByteArray(Charsets.UTF_8)))
        // In particular, Windows rejects deleting this file if File.reader() was left open.
        Files.delete(file)
        assertFalse(Files.exists(file))
    }

    @Test fun `owned file reader is released when YAML parsing fails`() {
        val yaml = "login: [unterminated"
        val file = Files.writeString(directory.resolve("invalid.yml"), yaml)
        assertFails { YamlDocument.load(file.toFile()) }
        assertFails { YamlDocument.load(yaml.toByteArray(Charsets.UTF_8)) }
        Files.delete(file)
        assertFalse(Files.exists(file))
    }

    @Test fun `SnakeYaml reads ahead but never owns the supplied input stream`() {
        val stream = TrackingStream("login:\n  message: 验证通过\n".toByteArray(Charsets.UTF_8))
        stream.use {
            assertEquals(mapOf("login.message" to "验证通过"), YamlDocument.load(it))
            assertTrue(it.readCalls > 0)
            assertFalse(it.closed)
            assertEquals(-1, it.read())
        }
        assertTrue(stream.closed)
    }

    @Test fun `supplied input stream remains caller owned after parsing failure`() {
        val stream = TrackingStream("login: [unterminated".toByteArray(Charsets.UTF_8))
        stream.use {
            assertFails { YamlDocument.load(it) }
            assertTrue(it.readCalls > 0)
            assertFalse(it.closed)
        }
        assertTrue(stream.closed)
    }

    @Test fun `empty supplied stream remains caller owned`() {
        TrackingStream(byteArrayOf()).use {
            assertEquals(emptyMap(), YamlDocument.load(it))
            assertTrue(it.readCalls > 0)
            assertFalse(it.closed)
        }
    }

    private class TrackingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        var readCalls = 0

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            readCalls++
            return super.read(bytes, offset, length)
        }

        override fun close() {
            closed = true
            super.close()
        }
    }
}
