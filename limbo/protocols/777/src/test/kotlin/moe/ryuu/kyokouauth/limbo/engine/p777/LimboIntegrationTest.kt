package moe.ryuu.kyokouauth.limbo.session

import moe.ryuu.kyokouauth.limbo.*
import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import org.geysermc.mcprotocollib.network.Session
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory
import org.geysermc.mcprotocollib.network.packet.Packet
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundCustomQueryPacket
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundCustomQueryAnswerPacket
import org.junit.jupiter.api.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

private fun testEngine(settings: LimboSettings, listener: LimboListener = LimboListener.None,
    logger: LimboLogger = LimboLogger.Default, forwardingSecret: ByteArray): LimboEngine {
    val tasks = java.util.concurrent.ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    val engine = EngineFactory().create(EngineContext(settings.copy(biomeKey = "minecraft:plains"), listener, logger,
        java.net.InetSocketAddress("127.0.0.1", 0), ForwardingMode.VelocityModern(forwardingSecret), tasks))
    return object : LimboEngine {
        override fun start() = engine.start()
        override fun connectionCount() = engine.connectionCount()
        override fun close() { engine.close(); tasks.submit {}.get(5, TimeUnit.SECONDS); tasks.shutdownNow() }
    }
}

/** Library-client integration verifies transport/lifecycle, not original-client rendering. */
class LimboIntegrationTest {
    @Test fun `signed proxy connection must finish loading before the listener sees it`() {
        val joins = AtomicInteger()
        val leaves = CountDownLatch(1)
        val batch = CountDownLatch(1)
        val teleport = CountDownLatch(1)
        val chat = CountDownLatch(1)
        val server = testEngine(LimboSettings(keepAliveSeconds = 1, keepAliveTimeoutSeconds = 3),
            object : LimboListener {
                override fun onJoin(player: LimboPlayer) {
                    assertEquals(SignedTestProfile.connectionId, player.connectionId)
                    joins.incrementAndGet()
                    player.sendMessage(TextComponent("Oracle link", click = TextComponent.Click.OpenUrl("https://example.invalid/limbo-oracle")))
                }
                override fun onLeave(player: LimboPlayer) { leaves.countDown() }
            }, LimboLogger { _, _ -> }, forwardingSecret = SignedTestProfile.secret)
        val address = server.start()
        val client = ClientNetworkSessionFactory.factory().setRemoteSocketAddress(address)
            .setProtocol(MinecraftProtocol("OraclePlayer")).create()
        client.addListener(object : SessionAdapter() {
            override fun packetReceived(session: Session, packet: Packet) {
                when (packet) {
                    is ClientboundCustomQueryPacket -> session.send(ServerboundCustomQueryAnswerPacket(packet.messageId, SignedTestProfile.payload()))
                    is ClientboundChunkBatchFinishedPacket -> { session.send(ServerboundChunkBatchReceivedPacket(8f)); batch.countDown() }
                    is ClientboundPlayerPositionPacket -> { session.send(ServerboundAcceptTeleportationPacket(packet.id, packet.position.x, packet.position.y, packet.position.z, packet.yRot, packet.xRot)); teleport.countDown() }
                    is ClientboundSystemChatPacket -> { assertNotNull(packet.content.clickEvent()); chat.countDown() }
                }
            }
        })
        try {
            client.connect(true)
            assertTrue(batch.await(8, TimeUnit.SECONDS), "configuration and full chunk batch received")
            assertTrue(teleport.await(3, TimeUnit.SECONDS))
            assertEquals(0, joins.get(), "packet receipt alone is not renderer readiness")
            client.send(ServerboundPlayerLoadedPacket.INSTANCE)
            assertTrue(chat.await(3, TimeUnit.SECONDS), "structured clickable message sent after ready")
            client.send(ServerboundPlayerLoadedPacket.INSTANCE)
            assertEquals(1, joins.get())
            client.disconnect("test finished")
            assertTrue(leaves.await(3, TimeUnit.SECONDS))
        } finally {
            client.channel.close().syncUninterruptibly()
            server.close()
        }
    }

    @Test fun `unsigned direct clients are refused before configuration`() {
        val joined = AtomicInteger()
        val server = testEngine(LimboSettings(), object : LimboListener {
            override fun onJoin(player: LimboPlayer) { joined.incrementAndGet() }
            override fun onLeave(player: LimboPlayer) = Unit
        }, LimboLogger { _, _ -> }, forwardingSecret = SignedTestProfile.secret)
        val client = ClientNetworkSessionFactory.factory().setRemoteSocketAddress(server.start())
            .setProtocol(MinecraftProtocol("OraclePlayer")).create()
        val closed = CountDownLatch(1)
        client.addListener(object : SessionAdapter() {
            override fun packetReceived(session: Session, packet: Packet) {
                if (packet is ClientboundCustomQueryPacket) session.send(ServerboundCustomQueryAnswerPacket(packet.messageId, byteArrayOf(1)))
            }
            override fun disconnected(event: org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent) { closed.countDown() }
        })
        try {
            client.connect(true)
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertEquals(0, joined.get())
        } finally { client.channel.close().syncUninterruptibly(); server.close() }
    }

    @Test fun `incomplete handshake expires and server releases the half connection`() {
        testEngine(LimboSettings(joinTimeoutSeconds = 1), forwardingSecret = SignedTestProfile.secret).use { server ->
            Socket().use { socket ->
                socket.connect(server.start())
                socket.soTimeout = 4000
                socket.getOutputStream().write(byteArrayOf(0x80.toByte()))
                assertEquals(-1, socket.getInputStream().read())
            }
        }
    }
}
