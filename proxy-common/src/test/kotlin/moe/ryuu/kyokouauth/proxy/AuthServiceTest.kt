package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.core.text.RichText
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuthServiceTest {
    @TempDir lateinit var directory: Path
    private val quiet = ProxyLog { _, _, _ -> }
    private fun config() = ProxyConfig(mapOf("database.type" to "h2", "database.database" to directory.resolve("identities").toString(),
        "database.auto-download-driver" to "false"), directory.toFile())

    @Test
    fun `real service preserves task exceptions and is closed idempotently`() {
        val service = AuthService(config(), quiet)
        try {
            val expected = IOException("controlled task failure")
            val failure = assertFailsWith<ExecutionException> { service.submit<Int> { throw expected }.get(3, TimeUnit.SECONDS) }
            assertSame(expected, failure.cause)
            service.close()
            service.close()
            assertFailsWith<RejectedExecutionException> { service.execute { error("closed admission ran") } }
            val rejected = assertFailsWith<ExecutionException> { service.submit { 1 }.get(3, TimeUnit.SECONDS) }
            assertTrue(rejected.cause is RejectedExecutionException)
        } finally { service.close() }
    }

    @Test
    fun `a closed command queue reports busy rather than inventing an unknown identity`() {
        val config = config()
        val messages = MessageCatalogue("en_US", directory.resolve("messages").toFile()).also { it.load() }
        val server = object : ProxyServer {
            override fun getServer(name: String) = Optional.empty<RegisteredServer>()
            override fun getPlayer(name: String) = Optional.empty<Player>()
            override fun getPlayer(uuid: UUID) = Optional.empty<Player>()
            override val allPlayers = emptyList<Player>()
            override val configuration = object : ProxyConfiguration { override val attemptConnectionOrder = emptyList<String>() }
        }
        val replies = mutableListOf<RichText.Parsed>()
        val source = object : CommandSource {
            override val locale = "en_US"
            override fun hasPermission(permission: String) = true
            override fun sendMessage(message: RichText.Parsed) { replies += message }
        }
        AuthService(config, quiet).use { service ->
            val gate = LoginGate(server, config, messages, service, quiet)
            val command = KyokouAuthCommand(server, config, messages, service, gate) { messages.load() }
            service.close()
            command.execute(source, arrayOf("whois", "Controlled"))
            val response = replies.single().spans.joinToString("") { it.text }
            assertTrue(response.contains("busy"), response)
            assertTrue(!response.contains("No record named"), response)
        }
    }
}
