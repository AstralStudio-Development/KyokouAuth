package moe.ryuu.kyokouauth.bungee

import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.limbo.PrivateTicketAuthority
import moe.ryuu.kyokouauth.proxy.BedrockProof
import moe.ryuu.kyokouauth.proxy.GameProfile
import net.md_5.bungee.netty.ChannelWrapper
import java.lang.reflect.Array as ReflectArray
import java.net.InetSocketAddress
import java.net.SocketAddress
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

    private fun skinOnly(pending: PendingConnection, id: String? = null, name: String? = null,
                         properties: List<GameProfile.Property> = listOf(GameProfile.Property("textures", "skin", "signed"))): Any {
        val property = Class.forName("net.md_5.bungee.protocol.data.Property", false, loader)
        val values = ReflectArray.newInstance(property, properties.size)
        val constructor = property.getConstructor(String::class.java, String::class.java, String::class.java)
        properties.forEachIndexed { index, item -> ReflectArray.set(values, index, constructor.newInstance(item.name, item.value, item.signature)) }
        val result = Class.forName("net.md_5.bungee.connection.LoginResult", false, loader)
            .getConstructor(String::class.java, String::class.java, values.javaClass).newInstance(id, name, values)
        pending.javaClass.getDeclaredField("loginProfile").apply { isAccessible = true }.set(pending, result)
        return result
    }

    private fun proof(pending: PendingConnection, source: Any = Any(), current: () -> Boolean = { true }): BedrockProof =
        BedrockProof.create(Xuid(2535412345678901L), UUID(0L, 2535412345678901L), pending.name, "Controlled", ".", source, current)

    @Test fun `official Floodgate skin-only LoginResult has a proof-bound semantic view without changing native identity fields`() {
        val pending = handler(".Controlled", UUID(0L, 2535412345678901L))
        val native = skinOnly(pending)
        var current = true
        val proof = proof(pending) { current }
        val bridge = BungeeProfileBridge(loader, bedrock = { if (it === pending) proof else null })
        val original = GameProfile(proof.uniqueId, proof.username, listOf(GameProfile.Property("textures", "skin", "signed")))
        assertEquals(original, bridge.read(pending))
        val updated = original.withProperties(original.properties + GameProfile.Property("ticket-test", "controlled", ""))
        bridge.apply(pending, updated)
        assertEquals(updated, bridge.read(pending))
        assertSame(native, pending.javaClass.getMethod("getLoginProfile").invoke(pending))
        assertNull(native.javaClass.getMethod("getId").invoke(native))
        assertNull(native.javaClass.getMethod("getName").invoke(native))
        assertEquals(proof.uniqueId, pending.uniqueId)
        assertEquals(proof.username, pending.name)
        assertNull(pending.javaClass.getMethod("getRewriteId").invoke(pending))
        current = false
        assertFails { bridge.read(pending) }
        assertFails { bridge.apply(pending, updated) }
    }

    @Test fun `skin-only is forbidden for Java missing proof changed native identity and partial null containers`() {
        val pending = handler(".Controlled", UUID(0L, 2535412345678901L))
        val proof = proof(pending)
        val bridge = BungeeProfileBridge(loader, bedrock = { proof })
        skinOnly(pending)
        assertFails { BungeeProfileBridge(loader).read(pending) }
        assertFails { BungeeProfileBridge(loader).apply(pending, GameProfile(proof.uniqueId, proof.username, emptyList())) }
        val absent = BungeeProfileBridge(loader, bedrock = { null })
        assertFails { absent.read(pending) }
        pending.isOnlineMode = true
        assertFails { bridge.read(pending) }
        pending.isOnlineMode = false
        pending.uniqueId = UUID.randomUUID()
        assertFails { bridge.read(pending) }
        pending.uniqueId = proof.uniqueId
        for ((id, name) in listOf(null to proof.username, proof.uniqueId.toString() to null)) {
            skinOnly(pending, id, name)
            assertFails { bridge.read(pending) }
            assertFails { bridge.apply(pending, GameProfile(proof.uniqueId, proof.username, emptyList())) }
        }
        val wrong = UUID.randomUUID()
        skinOnly(pending, wrong.toString(), ".Other")
        assertEquals(wrong, bridge.read(pending).id, "Wrong non-null fields must not fall back to the trusted identity")
        assertFails { bridge.apply(pending, GameProfile(proof.uniqueId, proof.username, emptyList())) }
    }

    @Test fun `real backend ticket stripping accepts current Bedrock skin-only profile and preserves its null fields and textures`() {
        val pending = handler(".Controlled", UUID(0L, 2535412345678901L))
        val channel = object : EmbeddedChannel(object : ChannelInboundHandlerAdapter() {}) {
            override fun remoteAddress0(): SocketAddress = InetSocketAddress("127.0.0.1", 43123)
        }
        val authority = PrivateTicketAuthority()
        try {
            val wrapper = ChannelWrapper(checkNotNull(channel.pipeline().firstContext()))
            pending.javaClass.getDeclaredField("ch").apply { isAccessible = true }.set(pending, wrapper)
            val proof = proof(pending, channel) { channel.isActive && pending.isConnected }
            val bridge = BungeeProfileBridge(loader, bedrock = { if (it === pending) proof else null })
            val native = skinOnly(pending, properties = listOf(GameProfile.Property("textures", "skin", "signed"),
                GameProfile.Property(authority.propertyKey, "controlled-ticket", ""),
                GameProfile.Property("kyokouauth:connection-id", "controlled-session", "")))
            val proxy = TestProxy()
            val platform = BungeePlatform(proxy)
            val player = platform.pending(pending)
            val backend = proxy.constructServerInfo("backend", InetSocketAddress("127.0.0.1", 29001), "test", false)
            proxy.registered["backend"] = backend
            BungeeTickets(platform, bridge, authority).prepare(player, checkNotNull(platform.commonServer(backend)))
            assertEquals(listOf(GameProfile.Property("textures", "skin", "signed")), bridge.read(pending).properties)
            assertSame(native, pending.javaClass.getMethod("getLoginProfile").invoke(pending))
            assertNull(native.javaClass.getMethod("getId").invoke(native))
            assertNull(native.javaClass.getMethod("getName").invoke(native))
            assertEquals(proof.uniqueId, pending.uniqueId)
        } finally { authority.close(); channel.finishAndReleaseAll() }
    }

    @Test fun `prepared Bedrock role rebuilds old container then interprets FG skin-only through expected identity and current proof`() {
        val channel = EmbeddedChannel(ChannelInboundHandlerAdapter())
        try {
            val pending = handler(".Controlled", UUID(0L, 2535412345678901L))
            pending.javaClass.getDeclaredField("ch").apply { isAccessible = true }
                .set(pending, ChannelWrapper(checkNotNull(channel.pipeline().firstContext())))
            var current = true
            val proof = proof(pending) { current }
            val original = skinOnly(pending, proof.uniqueId.toString(), proof.username)
            val selected = GameProfile(UUID.randomUUID(), "SelectedRole", listOf(
                GameProfile.Property("textures", "selected-skin", "selected-signature"),
                GameProfile.Property("kyokouauth:connection-id", "this-channel", "")))
            val bridge = BungeeProfileBridge(loader, bedrock = { proof }, role = { selected })
            BungeeRoleLoginBridge(loader).prepare(pending, selected.id, selected.name, proof)
            bridge.prepareRole(pending, selected)
            assertEquals(selected, bridge.read(pending))
            assertNotSame(original, pending.javaClass.getMethod("getLoginProfile").invoke(pending))
            assertEquals(proof.uniqueId.toString(), original.javaClass.getMethod("getId").invoke(original))
            assertEquals(".Controlled", proof.username)
            assertEquals(UUID(0L, 2535412345678901L), proof.uniqueId)
            val native = skinOnly(pending)
            assertEquals(selected.id, bridge.read(pending).id)
            assertEquals(selected.name, bridge.read(pending).name)
            bridge.apply(pending, selected)
            assertSame(native, pending.javaClass.getMethod("getLoginProfile").invoke(pending))
            assertNull(native.javaClass.getMethod("getId").invoke(native))
            assertNull(native.javaClass.getMethod("getName").invoke(native))
            assertEquals(selected, bridge.read(pending))
            assertFails { BungeeProfileBridge(loader, bedrock = { proof }).read(pending) }
            current = false
            assertFails { bridge.read(pending) }
        } finally { channel.finishAndReleaseAll() }
    }

    @Test fun `role binding alone cannot legalize Java skin-only profiles and role replacement is prelogin only`() {
        val channel = EmbeddedChannel(ChannelInboundHandlerAdapter())
        try {
            val pending = handler("Temporary", UUID(0, 1))
            pending.javaClass.getDeclaredField("ch").apply { isAccessible = true }
                .set(pending, ChannelWrapper(checkNotNull(channel.pipeline().firstContext())))
            val selected = GameProfile(UUID(0, 2), "SelectedRole", emptyList())
            val bridge = BungeeProfileBridge(loader, role = { selected })
            BungeeRoleLoginBridge(loader).prepare(pending, selected.id, selected.name)
            bridge.prepareRole(pending, selected)
            assertEquals(selected, bridge.read(pending))
            skinOnly(pending)
            assertFails { bridge.read(pending) }
            val state = pending.javaClass.getDeclaredField("thisState").apply { isAccessible = true }
            state.set(pending, state.type.enumConstants.single { (it as Enum<*>).name == "FINISHING" })
            val before = pending.javaClass.getMethod("getLoginProfile").invoke(pending)
            assertFails { bridge.prepareRole(pending, selected) }
            assertSame(before, pending.javaClass.getMethod("getLoginProfile").invoke(pending))
        } finally { channel.finishAndReleaseAll() }
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
