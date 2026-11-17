package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.limbo.PrivateTicketAuthority
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.*

class BungeeTicketsTest {
    private class Harness {
        val native = TestProxy()
        val platform = BungeePlatform(native)
        val profiles = TestProfiles()
        val authority = PrivateTicketAuthority()
        val tickets = BungeeTickets(platform, profiles, authority)
        val fixture = PendingFixture()
        val player = platform.pending(fixture.pending)
        val backend = native.constructServerInfo("backend", InetSocketAddress("127.0.0.1", 28001), "backend", false)
        init {
            profiles.add(fixture)
            player.loggedIn = true
            player.bind(fixture.player)
            platform.registerEngine(767, InetSocketAddress("127.0.0.1", 28767))
            platform.registerEngine(777, InetSocketAddress("127.0.0.1", 28777))
            native.registered[backend.name] = backend
        }
        val engine get() = platform.getServer("kyokouauth-limbo-777").orElseThrow()
        fun token() = profiles.read(fixture.pending).properties.single { it.name == authority.propertyKey }.value
        fun redeem(token: String, protocol: Int = 777) = authority.redeem(token, protocol, player.uniqueId,
            player.username, player.remoteAddress.address.hostAddress)
    }

    @Test fun `private credential is 256 bits single use exact identity and textures survive`() {
        val h = Harness()
        h.tickets.prepare(h.player, h.engine)
        val token = h.token()
        assertEquals(32, java.util.Base64.getUrlDecoder().decode(token).size)
        val identity = h.redeem(token)
        assertEquals(h.player.sessionId, identity.connectionId)
        assertEquals(h.player.uniqueId, identity.uuid)
        assertFails { h.redeem(token) }
        assertEquals("preserved", h.profiles.read(h.fixture.pending).properties.single { it.name == "textures" }.value)
        // Redemption does not make a second concurrent private connect legal.
        assertFails { h.tickets.prepare(h.player, h.engine) }
    }

    @Test fun `wrong engine consumes credential and cannot downgrade or replay`() {
        val h = Harness()
        h.tickets.prepare(h.player, h.engine)
        val token = h.token()
        assertFails { h.redeem(token, 767) }
        assertFails { h.redeem(token) }
        assertFails { h.tickets.prepare(h.player, h.platform.getServer("kyokouauth-limbo-767").orElseThrow()) }
    }

    @Test fun `real backend is stripped before forwarding and residual ticket revoked`() {
        val h = Harness()
        h.tickets.prepare(h.player, h.engine)
        val token = h.token()
        h.tickets.prepare(h.player, h.platform.commonServer(h.backend)!!)
        assertFalse(h.profiles.read(h.fixture.pending).properties.any { it.name == h.authority.propertyKey })
        assertNull(h.player.engineAttempt)
        assertFails { h.redeem(token) }
        assertEquals("preserved", h.profiles.read(h.fixture.pending).properties.single { it.name == "textures" }.value)
    }

    @Test fun `strip failure is surfaced so backend event must disconnect`() {
        val h = Harness()
        h.tickets.prepare(h.player, h.engine)
        val token = h.token()
        h.profiles.failApply = true
        assertFails { h.tickets.prepare(h.player, h.platform.commonServer(h.backend)!!) }
        assertFails { h.redeem(token) }
    }

    @Test fun `closed frontend and public same-name collision invalidate live private credential`() {
        for (close in listOf(true, false)) {
            val h = Harness()
            h.tickets.prepare(h.player, h.engine)
            val token = h.token()
            if (close) h.fixture.connected = false else {
                val name = h.engine.name
                h.native.registered[name] = h.native.constructServerInfo(name, InetSocketAddress("127.0.0.1", 9), "public conflict", false)
            }
            assertFails { h.redeem(token) }
        }
    }

    @Test fun `unregister and same-name re-registration cannot revive an issued private ticket`() {
        val h = Harness()
        val oldEngine = h.engine
        val oldNative = assertNotNull(h.platform.nativeServer(oldEngine))
        h.tickets.prepare(h.player, oldEngine)
        val token = h.token()

        h.platform.unregisterEngines()
        assertTrue(h.platform.getServer(oldEngine.name).isEmpty)
        assertNull(h.platform.nativeServer(oldEngine))
        assertNull(h.platform.commonServer(oldNative))
        h.platform.registerEngine(777, InetSocketAddress("127.0.0.1", 29777))
        assertNotSame(oldEngine, h.engine)
        assertNotSame(oldNative, h.platform.nativeServer(h.engine))
        assertFails { h.redeem(token) }
        assertFails { h.redeem(token) }
    }

    @Test fun `old connection revocation cannot revoke a same identity replacement`() {
        val h = Harness()
        val freshFixture = PendingFixture()
        h.profiles.add(freshFixture)
        val fresh = h.platform.pending(freshFixture.pending)
        fresh.loggedIn = true
        fresh.bind(freshFixture.player)
        h.tickets.prepare(fresh, h.engine)
        val token = h.profiles.read(freshFixture.pending).properties.single { it.name == h.authority.propertyKey }.value
        h.tickets.revoke(h.player)
        val identity = h.authority.redeem(token, 777, fresh.uniqueId, fresh.username, fresh.remoteAddress.address.hostAddress)
        assertEquals(fresh.sessionId, identity.connectionId)
        assertNotEquals(h.player.sessionId, fresh.sessionId)
        assertNotSame(h.player, fresh)
    }
}
