package moe.ryuu.kyokouauth.bungee

import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import net.md_5.bungee.api.connection.PendingConnection
import net.md_5.bungee.netty.ChannelWrapper
import net.md_5.bungee.protocol.packet.LoginRequest
import net.md_5.bungee.protocol.data.PlayerPublicKey
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class BungeeRoleLoginBridgeTest {
    private val loader = javaClass.classLoader

    private fun handler(channel: EmbeddedChannel): PendingConnection {
        val type = Class.forName("net.md_5.bungee.connection.InitialHandler", true, loader)
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val pending = unsafeType.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as PendingConnection
        type.getDeclaredField("ch").apply { isAccessible = true }
            .set(pending, ChannelWrapper(checkNotNull(channel.pipeline().firstContext())))
        type.getDeclaredField("name").apply { isAccessible = true }.set(pending, "Temporary")
        state(pending, "USERNAME")
        pending.isOnlineMode = false
        pending.uniqueId = UUID(0, 1)
        return pending
    }

    private fun state(pending: PendingConnection, name: String) {
        val field = pending.javaClass.getDeclaredField("thisState").apply { isAccessible = true }
        field.set(pending, field.type.enumConstants.single { (it as Enum<*>).name == name })
    }

    @Test fun `provisional alias is applied only in offline prelogin and cannot be used as a role name`() {
        val channel = EmbeddedChannel(ChannelInboundHandlerAdapter())
        try {
            val pending = handler(channel)
            val id = BungeeProfileBridge.offlineId("Temporary")
            val alias = "KA\$" + id.toString().replace("-", "").take(13)
            val bridge = BungeeRoleLoginBridge(loader)
            assertFails { bridge.prepare(pending, id, alias) }
            assertFails { bridge.prepareVerification(pending, UUID.randomUUID(), alias) }
            bridge.prepareVerification(pending, id, alias)
            assertEquals(alias, pending.name)
            assertEquals(id, pending.uniqueId)
            state(pending, "FINISHING")
            assertFails { bridge.prepareVerification(pending, id, alias) }
        } finally { channel.finishAndReleaseAll() }
    }

    @Test fun `provisional isolation refuses an existing signed login key without mutation`() {
        val channel = EmbeddedChannel(ChannelInboundHandlerAdapter())
        try {
            val pending = handler(channel)
            val id = BungeeProfileBridge.offlineId("Temporary")
            val request = LoginRequest("Temporary", PlayerPublicKey(123L, byteArrayOf(1), byteArrayOf(2)), id)
            pending.javaClass.getDeclaredField("loginRequest").apply { isAccessible = true }.set(pending, request)
            assertFails { BungeeRoleLoginBridge(loader).prepareVerification(pending, id,
                "KA\$" + id.toString().replace("-", "").take(13)) }
            assertEquals("Temporary", pending.name)
            assertEquals(UUID(0, 1), pending.uniqueId)
        } finally { channel.finishAndReleaseAll() }
    }

    @Test fun `role is real pending login name and UUID before player exists`() {
        val channel = EmbeddedChannel(ChannelInboundHandlerAdapter())
        try {
            val pending = handler(channel)
            val id = UUID(0, 2535412345678901L)
            BungeeRoleLoginBridge(loader).prepare(pending, id, "RealRole")
            assertEquals("RealRole", pending.name)
            assertEquals(id, pending.uniqueId)
            assertNull(pending.javaClass.getMethod("getRewriteId").invoke(pending))
        } finally { channel.finishAndReleaseAll() }
    }

    @Test fun `online finishing and closed connections cannot be rewritten`() {
        for (case in listOf("online", "FINISHING", "closed")) {
            val channel = EmbeddedChannel(ChannelInboundHandlerAdapter())
            try {
                val pending = handler(channel)
                when (case) {
                    "online" -> pending.isOnlineMode = true
                    "FINISHING" -> state(pending, case)
                    "closed" -> {
                        (pending.javaClass.getDeclaredField("ch").apply { isAccessible = true }.get(pending) as ChannelWrapper).markClosed()
                        channel.close()
                    }
                }
                assertFails { BungeeRoleLoginBridge(loader).prepare(pending, UUID(0, 2), "RealRole") }
                assertEquals("Temporary", pending.name)
                assertEquals(UUID(0, 1), pending.uniqueId)
            } finally { channel.finishAndReleaseAll() }
        }
    }

    @Test fun `a native player already constructed cannot be renamed even with a stale USERNAME state`() {
        val channel = EmbeddedChannel(ChannelInboundHandlerAdapter())
        try {
            val pending = handler(channel)
            val unsafeType = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val user = unsafeType.getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, Class.forName("net.md_5.bungee.UserConnection", true, loader))
            pending.javaClass.getDeclaredField("userCon").apply { isAccessible = true }.set(pending, user)
            assertFails { BungeeRoleLoginBridge(loader).prepare(pending, UUID(0, 2), "RealRole") }
            assertEquals("Temporary", pending.name)
            assertEquals(UUID(0, 1), pending.uniqueId)
        } finally { channel.finishAndReleaseAll() }
    }

    @Test fun `different role cannot inherit an old signed login key`() {
        val channel = EmbeddedChannel(ChannelInboundHandlerAdapter())
        try {
            val pending = handler(channel)
            val request = LoginRequest("Temporary", PlayerPublicKey(123L, byteArrayOf(1), byteArrayOf(2)), UUID(0, 1))
            pending.javaClass.getDeclaredField("loginRequest").apply { isAccessible = true }.set(pending, request)
            val bridge = BungeeRoleLoginBridge(loader)
            assertFails { bridge.prepare(pending, UUID(0, 2), "RealRole") }
            assertEquals("Temporary", pending.name)
            assertEquals(UUID(0, 1), pending.uniqueId)
            bridge.prepare(pending, UUID(0, 1), "Temporary")
            assertSame(request.publicKey, (pending.javaClass.getMethod("getLoginRequest").invoke(pending) as LoginRequest).publicKey)
        } finally { channel.finishAndReleaseAll() }
    }

    @Test fun `a verified native nonJava Bedrock name is preserved without generally accepting arbitrary prefixes`() {
        val channel = EmbeddedChannel(ChannelInboundHandlerAdapter())
        try {
            val pending = handler(channel)
            val proof = moe.ryuu.kyokouauth.proxy.BedrockProof.create(
                moe.ryuu.kyokouauth.core.identity.Xuid(2535412345678901L), UUID(0L, 2535412345678901L),
                "!Controlled", "Controlled", "!", channel) { true }
            val bridge = BungeeRoleLoginBridge(loader)
            assertFails { bridge.prepare(pending, proof.uniqueId, proof.username) }
            bridge.prepare(pending, proof.uniqueId, proof.username, proof)
            assertEquals(proof.uniqueId, pending.uniqueId)
            assertEquals("!Controlled", pending.name)
            assertFails { bridge.prepare(pending, proof.uniqueId, "!Other", proof) }
        } finally { channel.finishAndReleaseAll() }
    }

    @Test fun `invalid role name cannot partially change native identity`() {
        val channel = EmbeddedChannel(ChannelInboundHandlerAdapter())
        try {
            val pending = handler(channel)
            val bridge = BungeeRoleLoginBridge(loader)
            for (name in listOf("", "x".repeat(17), "bad\u0000name", "has space")) {
                assertFails { bridge.prepare(pending, UUID(0, 2), name) }
                assertEquals("Temporary", pending.name)
                assertEquals(UUID(0, 1), pending.uniqueId)
            }
        } finally { channel.finishAndReleaseAll() }
    }
}
