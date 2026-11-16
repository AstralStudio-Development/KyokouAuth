package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.proxy.GameProfile
import net.md_5.bungee.api.connection.PendingConnection
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class BungeeProfileBridgeTest {
    private val loader = javaClass.classLoader

    /** The actual fixed 2111 implementation, no fake names standing in for reflective signatures. */
    private fun handler(name: String, uuid: UUID): PendingConnection {
        val type = Class.forName("net.md_5.bungee.connection.InitialHandler", true, loader)
        // The real constructor requires a running transport singleton. Allocate the actual class
        // without opening sockets; the public phase-checked setters below still execute native code.
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val pending = unsafeType.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as PendingConnection
        type.getDeclaredField("name").apply { isAccessible = true }.set(pending, name)
        val state = type.getDeclaredField("thisState").apply { isAccessible = true }
        state.set(pending, state.type.enumConstants.single { (it as Enum<*>).name == "USERNAME" })
        pending.isOnlineMode = false
        pending.uniqueId = uuid
        return pending
    }

    @Test fun `official2111 offline null profile uses only verified narrow bridge`() {
        val bridge = BungeeProfileBridge(loader)
        val uuid = UUID(0L, 2535412345678901L)
        val pending = handler("OracleLocal", uuid)
        val getter = pending.javaClass.getMethod("getLoginProfile")
        assertNull(getter.invoke(pending))
        val profile = GameProfile(uuid, pending.name, listOf(GameProfile.Property("textures", "skin-data", "skin-signature")))
        bridge.apply(pending, profile)
        assertNotNull(getter.invoke(pending))
        assertEquals(profile, bridge.read(pending))
        assertEquals(uuid, pending.uniqueId)
        assertNull(pending.javaClass.getMethod("getRewriteId").invoke(pending), "Bridge must never reflect rewriteId")
    }

    @Test fun `existing LoginResult identity object and signed textures are preserved`() {
        val bridge = BungeeProfileBridge(loader)
        val pending = handler("OracleLocal", UUID.randomUUID())
        val original = bridge.read(pending).withProperties(listOf(GameProfile.Property("textures", "skin", "signature")))
        bridge.apply(pending, original)
        val getter = pending.javaClass.getMethod("getLoginProfile")
        val actualResult = getter.invoke(pending)
        pending.isOnlineMode = true
        bridge.apply(pending, original.withProperties(original.properties + GameProfile.Property("ticket-test", "token", "")))
        assertSame(actualResult, getter.invoke(pending))
        assertEquals(original.properties.first(), bridge.read(pending).properties.first())
        assertEquals(pending.uniqueId, bridge.read(pending).id)
        assertFails { bridge.apply(pending, original.withId(UUID.randomUUID())) }
    }

    @Test fun `bridge does not try UUID setters during native FINISHING phase`() {
        val bridge = BungeeProfileBridge(loader)
        val pending = handler("OracleLocal", UUID.randomUUID())
        val profile = bridge.read(pending)
        bridge.apply(pending, profile)
        val state = pending.javaClass.getDeclaredField("thisState").apply { isAccessible = true }
        state.set(pending, state.type.enumConstants.single { (it as Enum<*>).name == "FINISHING" })
        assertFails { pending.uniqueId = UUID.randomUUID() }
        assertFails { pending.isOnlineMode = true }
        bridge.apply(pending, profile.withProperties(listOf(GameProfile.Property("ticket-test", "token", ""))))
        assertEquals(profile.id, pending.uniqueId)
    }

    @Test fun `unverified implementation and missing implementation fail closed`() {
        val bridge = BungeeProfileBridge(loader)
        assertFails { bridge.read(PendingFixture().pending) }
        val incompatible = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "net.md_5.bungee.connection.InitialHandler") throw ClassNotFoundException("injected incompatibility")
                return super.loadClass(name, resolve)
            }
        }
        assertFails { BungeeProfileBridge(incompatible) }
    }
}
