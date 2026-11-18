package moe.ryuu.kyokouauth.bungee

import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.*

class BungeePlatformTest {
    @Test fun `role properties update only the exact live native identity and selected expected profile`() {
        val profiles = TestProfiles()
        val platform = BungeePlatform(TestProxy())
        platform.installProfiles(profiles)
        val fixture = PendingFixture()
        profiles.add(fixture)
        val player = platform.pending(fixture.pending)
        val original = fixture.profile
        val property = moe.ryuu.kyokouauth.proxy.GameProfile.Property("native-extra", "keep", "signed")
        profiles.apply(fixture.pending, original.withProperties(original.properties + property))
        val selected = original.withProperties(listOf(
            moe.ryuu.kyokouauth.proxy.GameProfile.Property("textures", "selected", "role-signature"),
            moe.ryuu.kyokouauth.proxy.GameProfile.Property("kyokouauth:connection-id", "current-marker", ""),
            moe.ryuu.kyokouauth.proxy.GameProfile.Property("untrusted-extra", "ignore", "")))
        assertFails { player.applyRoleProfile(selected).join() }
        player.bind(fixture.player)
        player.loggedIn = true
        player.applyRoleProfile(selected).join()
        assertEquals(original.id, fixture.uuid)
        assertEquals(original.name, fixture.name)
        assertTrue(fixture.setterOrder.isEmpty(), "Existing Player UUID/name must never be rewritten")
        val expected = listOf(property) + selected.properties.take(2)
        assertEquals(expected, profiles.read(fixture.pending).properties)
        assertEquals(expected, assertNotNull(player.currentRoleProfile()).properties)
        for (wrong in listOf(selected.copy(id = java.util.UUID.randomUUID()), selected.copy(name = "oraclelocal"))) {
            assertFails { player.applyRoleProfile(wrong).join() }
        }
        fixture.connected = false
        assertFails { player.applyRoleProfile(selected).join() }
        fixture.connected = true
        platform.remove(player)
        assertFails { player.applyRoleProfile(selected).join() }
        assertEquals(expected, profiles.read(fixture.pending).properties)
    }

    @Test fun `private engines never enter public proxy directory but retain exact internal handles`() {
        val proxy = TestProxy()
        val platform = BungeePlatform(proxy)
        val backend = proxy.constructServerInfo("backend", InetSocketAddress("127.0.0.1", 29001), "backend", false)
        proxy.registered[backend.name] = backend

        for (protocol in 767..777) {
            val name = "kyokouauth-limbo-$protocol"
            platform.registerEngine(protocol, InetSocketAddress("127.0.0.1", 30000 + protocol))
            val common = platform.getServer(name).orElseThrow()
            val native = assertNotNull(platform.nativeServer(common))

            assertNull(proxy.getServerInfo(name), "Native /server lookup must not expose $name")
            assertFalse(proxy.servers.containsKey(name), "Tab/GetServers must not enumerate $name")
            assertFalse(proxy.servers.values.any { it === native })
            assertSame(common, platform.getServer(name).orElseThrow())
            assertSame(common, platform.getServer(name.uppercase(java.util.Locale.ROOT)).orElseThrow())
            assertSame(common, platform.commonServer(native))
            assertEquals(protocol, platform.engineProtocol(common))
        }

        assertEquals(setOf("backend"), proxy.servers.keys)
        assertSame(backend, platform.nativeServer(platform.getServer("backend").orElseThrow()))
    }

    @Test fun `same name and address cannot impersonate exact private handles`() {
        val proxy = TestProxy()
        val platform = BungeePlatform(proxy)
        val name = "kyokouauth-limbo-777"
        val address = InetSocketAddress("127.0.0.1", 30777)
        platform.registerEngine(777, address)
        val common = platform.getServer(name).orElseThrow()
        val native = assertNotNull(platform.nativeServer(common))
        val forged = proxy.constructServerInfo(name, address, "same endpoint, different object", true)

        assertNotSame(native, forged)
        assertNull(platform.commonServer(forged))
        assertNull(platform.nativeServer(moe.ryuu.kyokouauth.proxy.RegisteredServer(name)))
        assertSame(common, platform.commonServer(native))
    }

    @Test fun `public same-name collisions fail closed without becoming private or being removed`() {
        for (uppercase in listOf(false, true)) {
            val proxy = TestProxy()
            val platform = BungeePlatform(proxy)
            platform.registerEngine(777, InetSocketAddress("127.0.0.1", 30777))
            val common = platform.getServer("kyokouauth-limbo-777").orElseThrow()
            val native = assertNotNull(platform.nativeServer(common))
            val name = if (uppercase) "KYOKOUAUTH-LIMBO-777" else "kyokouauth-limbo-777"
            val conflicting = proxy.constructServerInfo(name, InetSocketAddress("127.0.0.1", 9), "public conflict", false)
            proxy.registered[name] = conflicting

            assertTrue(platform.getServer(name).isEmpty)
            assertNull(platform.nativeServer(common))
            assertNull(platform.commonServer(native))
            assertNull(platform.commonServer(conflicting))
            platform.unregisterEngines()
            assertSame(conflicting, proxy.getServerInfo(name), "Cleanup must not delete another registration")
            assertNull(platform.commonServer(conflicting))
        }
    }

    @Test fun `pre-existing public name blocks private registration without mutating public directory`() {
        val proxy = TestProxy()
        val platform = BungeePlatform(proxy)
        val name = "kyokouauth-limbo-777"
        val conflicting = proxy.constructServerInfo(name, InetSocketAddress("127.0.0.1", 9), "public conflict", false)
        proxy.registered[name] = conflicting

        assertFails { platform.registerEngine(777, InetSocketAddress("127.0.0.1", 30777)) }
        assertSame(conflicting, proxy.getServerInfo(name))
        assertTrue(platform.getServer(name).isEmpty)
        assertNull(platform.commonServer(conflicting))
    }

    @Test fun `unregistered private handles stay expired even when publicly republished or replaced`() {
        val proxy = TestProxy()
        val platform = BungeePlatform(proxy)
        val name = "kyokouauth-limbo-777"
        platform.registerEngine(777, InetSocketAddress("127.0.0.1", 30777))
        val previous = platform.getServer(name).orElseThrow()
        val oldNative = assertNotNull(platform.nativeServer(previous))
        assertFails { platform.registerEngine(777, InetSocketAddress("127.0.0.1", 31777)) }
        assertSame(previous, platform.getServer(name).orElseThrow())

        platform.unregisterEngines()
        assertTrue(platform.getServer(name).isEmpty)
        assertNull(platform.nativeServer(previous))
        assertNull(platform.commonServer(oldNative))
        assertNull(platform.engineProtocol(previous))
        proxy.registered[name] = oldNative
        assertNull(platform.commonServer(oldNative), "A retired private object must not be reclassified as a public backend")
        assertTrue(platform.getServer(name).isEmpty)
        proxy.registered.remove(name)

        platform.registerEngine(777, InetSocketAddress("127.0.0.1", 31777))
        val replacement = platform.getServer(name).orElseThrow()
        assertNotSame(previous, replacement)
        assertNotSame(oldNative, platform.nativeServer(replacement))
        assertNull(platform.nativeServer(previous))
        assertNull(platform.commonServer(oldNative))
        assertNull(proxy.getServerInfo(name))
    }

    @Test fun `connection request uses the exact private native target without publishing it`() {
        val proxy = TestProxy()
        val platform = BungeePlatform(proxy)
        val fixture = PendingFixture()
        val player = platform.pending(fixture.pending)
        player.bind(fixture.player)
        platform.registerEngine(777, InetSocketAddress("127.0.0.1", 30777))
        val common = platform.getServer("kyokouauth-limbo-777").orElseThrow()
        val native = assertNotNull(platform.nativeServer(common))

        val result = player.createConnectionRequest(common).connect().join()
        assertEquals(moe.ryuu.kyokouauth.proxy.ConnectionRequestBuilder.Status.SUCCESS, result.status)
        assertEquals(1, fixture.connects.size)
        assertSame(native, fixture.connects.single())
        assertTrue(proxy.servers.isEmpty())
    }
}
