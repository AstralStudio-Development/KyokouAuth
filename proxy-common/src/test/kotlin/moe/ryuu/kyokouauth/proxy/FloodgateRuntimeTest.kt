package moe.ryuu.kyokouauth.proxy

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import java.util.UUID
import kotlin.test.*

@Isolated("Controlled provider singleton used to test the reflection seam")
class FloodgateRuntimeTest {
    @Test fun `disabled optional integration never loads an SDK but rejects marked connections`() {
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String): Class<*> = error("Disabled integration tried loading $name")
        }
        val runtime = FloodgateRuntime(false) { FloodgateProvider(Any(), "unknown", loader) { true } }
        val attributes = mutableMapOf<String, Any?>()
        val connection = FloodgateConnection(Any(), { true }, attributes::get)
        assertSame(BedrockLogin.Java, runtime.probe(connection))
        attributes["floodgate-player"] = Any()
        assertEquals(BedrockLogin.Rejected(BedrockFailure.DISABLED), runtime.probe(connection))
    }

    @Test fun `explicit enable requires a supported initialized proxy provider`() {
        assertFailsWith<IllegalStateException> { FloodgateRuntime(true) { null } }
        assertFailsWith<IllegalStateException> { Fixture(version = "3.0-unsupported") }
        FakeApi.live = null
        assertFailsWith<IllegalStateException> {
            FloodgateRuntime(true) { FloodgateProvider(Any(), "2.2.5", SdkLoader()) { true } }
        }
    }

    @Test fun `current channel player is verified and reread without a UUID based reverse lookup`() {
        val h = Fixture()
        val verified = assertIs<BedrockLogin.Verified>(h.runtime.probe(h.connection))
        assertTrue(verified.proof.isCurrent())
        assertEquals(UUID(0L, 123L), verified.proof.uniqueId)
        assertEquals(".Bedrock", verified.proof.username)
        assertTrue(verified.proof.matches(assertIs<BedrockLogin.Verified>(h.runtime.probe(h.connection)).proof))
        h.attributes.clear()
        assertSame(BedrockLogin.Java, h.runtime.probe(h.connection), "An API cache entry alone is never a channel proof")
        assertFalse(verified.proof.isCurrent())
    }

    @Test fun `provider kick linked identity invalid XUID and unsafe name never become Java fallbacks`() {
        val kick = Fixture()
        kick.attributes["floodgate-kick-message"] = "private-upstream-detail"
        val rejection = kick.runtime.probe(kick.connection)
        assertEquals(BedrockLogin.Rejected(BedrockFailure.PROVIDER_DENIED), rejection)
        assertFalse(rejection.toString().contains("private-upstream-detail"))
        val linked = Fixture()
        linked.player.linked = true
        assertEquals(BedrockLogin.Rejected(BedrockFailure.LINKED), linked.runtime.probe(linked.connection))
        for (value in listOf("0", "-1", "not-an-xuid")) {
            val h = Fixture()
            h.player.xuid = value
            assertIs<BedrockLogin.Rejected>(h.runtime.probe(h.connection))
        }
        val unsafe = Fixture()
        unsafe.api.prefix = "BE_"
        unsafe.player.correctUsername = "BE_Bedrock"
        assertEquals(BedrockLogin.Rejected(BedrockFailure.UNSAFE_NAME), unsafe.runtime.probe(unsafe.connection))
        val wrongUuid = Fixture()
        wrongUuid.player.correctUniqueId = UUID.randomUUID()
        assertIs<BedrockLogin.Rejected>(wrongUuid.runtime.probe(wrongUuid.connection))
    }

    @Test fun `replacement attributes provider generations and identity mutations invalidate existing proofs`() {
        for (failure in listOf("attribute", "kick", "provider", "singleton", "profile", "public-map", "closed")) {
            val h = Fixture()
            val proof = assertIs<BedrockLogin.Verified>(h.runtime.probe(h.connection)).proof
            when (failure) {
                "attribute" -> h.attributes["floodgate-player"] = FakePlayer()
                "kick" -> h.attributes["floodgate-kick-message"] = "rejected"
                "provider" -> h.plugin = Any()
                "singleton" -> FakeApi.live = FakeProxyApi()
                "profile" -> h.player.correctUsername = ".Changed"
                "public-map" -> h.api.players[h.player.correctUniqueId] = FakePlayer()
                "closed" -> h.active = false
            }
            assertFalse(proof.isCurrent(), failure)
        }
    }

    @Test fun `same provider and identity on a different channel do not match one physical proof`() {
        val h = Fixture()
        val first = assertIs<BedrockLogin.Verified>(h.runtime.probe(h.connection)).proof
        val second = assertIs<BedrockLogin.Verified>(h.runtime.probe(FloodgateConnection(Any(), { true }, h.attributes::get))).proof
        assertFalse(first.matches(second))
    }

    class FakePlayer {
        var xuid = "123"
        var correctUniqueId = UUID(0L, 123L)
        var correctUsername = ".Bedrock"
        var username = "Bedrock"
        var linked = false
        fun isLinked() = linked
        fun getLinkedPlayer(): Any? = if (linked) Any() else null
    }

    open class FakeApi {
        var prefix = "."
        val players = mutableMapOf<UUID, FakePlayer>()
        fun getPlayerPrefix() = prefix
        fun getPlayer(uuid: UUID): FakePlayer? = players[uuid]
        companion object {
            var live: FakeApi? = null
            @JvmStatic fun getInstance(): FakeApi? = live
        }
    }
    class FakeProxyApi : FakeApi()

    private class SdkLoader : ClassLoader(FloodgateRuntimeTest::class.java.classLoader) {
        override fun loadClass(name: String): Class<*> = when (name) {
            "org.geysermc.floodgate.api.FloodgateApi" -> FakeApi::class.java
            "org.geysermc.floodgate.api.ProxyFloodgateApi" -> FakeProxyApi::class.java
            "org.geysermc.floodgate.api.player.FloodgatePlayer" -> FakePlayer::class.java
            else -> super.loadClass(name)
        }
    }

    private class Fixture(version: String = "2.2.5-SNAPSHOT (b141-81b65cc)") {
        var plugin = Any()
        var active = true
        val api = FakeProxyApi()
        val player = FakePlayer()
        val attributes = mutableMapOf<String, Any?>("floodgate-player" to player)
        val loader = SdkLoader()
        val connection = FloodgateConnection(Any(), { active }, attributes::get)
        val runtime: FloodgateRuntime
        init {
            FakeApi.live = api
            api.players[player.correctUniqueId] = player
            runtime = FloodgateRuntime(true) { FloodgateProvider(plugin, version, loader) { true } }
        }
    }
}
