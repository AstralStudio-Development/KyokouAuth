package moe.ryuu.kyokouauth.limbo.session

import io.netty.buffer.Unpooled
import moe.ryuu.kyokouauth.limbo.*
import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import org.geysermc.mcprotocollib.network.Session
import org.geysermc.mcprotocollib.network.event.session.*
import org.geysermc.mcprotocollib.network.packet.Packet
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec
import org.geysermc.mcprotocollib.protocol.data.ProtocolState
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundCustomQueryPacket
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundCustomQueryAnswerPacket
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BooleanSupplier
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

/** Library socket tests are lifecycle evidence only; the official oracle supplies independent compatibility checks. */
class EngineContractTest {
    private val protocol = MinecraftCodec.CODEC.protocolVersion
    private val secret = "engine-contract-test-only".toByteArray()
    private val connectionId = UUID.randomUUID()
    private val uuid = UUID.randomUUID()
    private val name = "ContractPlayer"

    @Test fun `fixed codec full registry and biome keys are version specific`() {
        assertEquals(System.getProperty("expected.protocol").toInt(), protocol)
        val registry = RegistrySnapshot.load()
        assertTrue(registry.registries.isNotEmpty())
        assertTrue("minecraft:plains" in registry.biomeKeys)
        assertEquals(registry.plainsId, registry.biomeId("minecraft:plains"))
        assertFails { registry.biomeId("minecraft:nonexistent") }
        assertFails { RegistrySnapshot.decode(byteArrayOf(0x80.toByte())) }
    }

    @Test fun `world and rich message serialize with this engine codec`() {
        val registry = RegistrySnapshot.load()
        val world = LimboWorld(LimboSettings(biomeKey = "minecraft:plains"), registry)
        val packets = world.joinPackets(7)
        assertEquals(25, packets.count { it.javaClass.simpleName == "ClientboundLevelChunkWithLightPacket" })
        packets.forEach { packet ->
            val id = GeneratedCodec.CODEC.getCodec(ProtocolState.GAME).getClientboundId(packet.javaClass)
            assertTrue(id >= 0)
            val buffer = Unpooled.buffer()
            try { serialize(packet, buffer); assertTrue(buffer.isReadable || packet.javaClass.simpleName == "ClientboundChunkBatchStartPacket") } finally { buffer.release() }
        }
        val message = TextComponent("验证 Unicode", color = "#22d3ee", bold = true, children = listOf(
            TextComponent("完整 URL", underlined = true, click = TextComponent.Click.OpenUrl("https://example.invalid/test?a=1&b=2"))))
        val packet = ClientboundSystemChatPacket(message.toLibrary(), false)
        val buffer = Unpooled.buffer()
        try {
            serialize(packet, buffer)
            val codec = GeneratedCodec.CODEC.getCodec(ProtocolState.GAME)
            val decoded = Wire.packet(codec, codec.getClientboundId(packet.javaClass), buffer) as ClientboundSystemChatPacket
            assertFalse(buffer.isReadable)
            assertNotNull(decoded.content.children().last().clickEvent())
        } finally { buffer.release() }
    }

    @Test fun `private ticket parser rejects missing duplicate replay and wrong target`() {
        PrivateTicketAuthority().use { authority ->
            val identity = TicketIdentity(connectionId, uuid, name, "127.0.0.1", protocol)
            fun host(token: String, duplicate: Boolean = false): String {
                val property = "{\"name\":\"${authority.propertyKey}\",\"value\":\"$token\"}"
                return "localhost\u0000127.0.0.1\u0000${uuid.toString().replace("-", "")}\u0000["+property+(if(duplicate) ","+property else "")+"]"
            }
            val token = authority.issue(identity, BooleanSupplier { true })
            assertFails { ForwardedIdentity.redeem(host(token, true), authority, name, protocol) }
            val accepted = ForwardedIdentity.redeem(host(token), authority, name, protocol)
            assertEquals(connectionId, accepted.connectionId)
            assertFails { ForwardedIdentity.redeem(host(token), authority, name, protocol) }
            assertFails { ForwardedIdentity.redeem("localhost", authority, name, protocol) }
        }
    }

    @Test fun `library client requires trusted forwarding then loading and survives heartbeats`() =
        connection(ForwardingMode.VelocityModern(secret))

    @Test fun `private ticket library socket never requests modern forwarding`() {
        PrivateTicketAuthority().use { connection(ForwardingMode.PrivateTicket(it)) }
    }

    @Test fun `role selector uses three rows reopens closes and accepts only one server microsoft option`() =
        connection(ForwardingMode.VelocityModern(secret), false)

    @Test fun `role selector accepts premium right click without trusting predicted head`() =
        connection(ForwardingMode.VelocityModern(secret), true)

    private fun connection(mode: ForwardingMode, selectPremium: Boolean? = null) {
        val joined = CountDownLatch(1)
        val left = CountDownLatch(1)
        val chat = CountDownLatch(1)
        val joins = AtomicInteger()
        val selected = CountDownLatch(1)
        val selections = AtomicInteger()
        val generation = UUID.randomUUID()
        val menu = RoleSelectorMenu(generation, TextComponent("选择角色"),
            RoleSelectorItem("server-microsoft", UUID(0, 42), "MicrosoftName", "texture-value", "texture-signature",
                TextComponent("微软角色"), listOf(TextComponent("独立资料"))),
            RoleSelectorItem("server-premium", UUID(1, 2), "PremiumName", null, null,
                TextComponent("正版角色"), emptyList()))
        val selectorStep = AtomicInteger()
        val fullInventorySyncs = AtomicInteger()
        val firstWindow = AtomicInteger()
        val failure = AtomicReference<String>()
        val tasks = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
        val settings = LimboSettings(biomeKey = "minecraft:plains", keepAliveSeconds = 1, keepAliveTimeoutSeconds = 3)
        val engine = EngineFactory().create(EngineContext(settings, object : LimboListener {
            override fun onJoin(player: LimboPlayer) {
                assertEquals(connectionId, player.connectionId)
                joins.incrementAndGet()
                player.sendMessage(TextComponent("Ready"))
                if (selectPremium != null) player.openRoleSelector(menu)
                joined.countDown()
            }
            override fun onRoleSelect(player: LimboPlayer, generation: UUID, optionId: String) {
                if (generation != menu.generation || optionId != if (selectPremium == true) "server-premium" else "server-microsoft") {
                    failure.set("Unexpected server-owned selection callback")
                }
                selections.incrementAndGet()
                selected.countDown()
            }
            override fun onLeave(player: LimboPlayer) { left.countDown() }
        }, LimboLogger { message, error -> if (message.contains("rejected") || error != null) failure.set(message+":"+error) },
            InetSocketAddress("127.0.0.1", 0), mode, tasks))
        var client: Session? = null
        try {
            client = client(engine.start())
            val ticketMode = mode as? ForwardingMode.PrivateTicket
            val ticket = ticketMode?.authority?.issue(TicketIdentity(connectionId, uuid, name, "127.0.0.1", protocol), BooleanSupplier { true })
            client.addListener(object : SessionAdapter() {
                override fun packetSending(event: PacketSendingEvent) {
                    val packet = event.javaClass.getMethod("getPacket").invoke(event) as Packet
                    if (ticket != null && packet.javaClass.simpleName == "ClientIntentionPacket") {
                        val host = "localhost\u0000127.0.0.1\u0000${uuid.toString().replace("-", "")}\u0000[{\"name\":\"${ticketMode!!.authority.propertyKey}\",\"value\":\"$ticket\"}]"
                        event.setPacket(packet.javaClass.getMethod("withHostname", String::class.java).invoke(packet, host) as Packet)
                    }
                }
                override fun packetReceived(session: Session, packet: Packet) {
                    try {
                        when {
                            packet is ClientboundCustomQueryPacket -> {
                                check(ticket == null) { "Private ticket mode must never negotiate modern forwarding" }
                                session.send(ServerboundCustomQueryAnswerPacket(packet.messageId, payload()))
                            }
                            packet.javaClass.simpleName == "ClientboundChunkBatchFinishedPacket" ->
                                session.send(construct("packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket", 8f) as Packet)
                            packet.javaClass.simpleName == "ClientboundPlayerPositionPacket" -> {
                                val id = (packet.javaClass.methods.first { it.name in setOf("getId", "getTeleportId") && it.parameterCount == 0 }.invoke(packet) as Int)
                                val type = Class.forName(PREFIX+"packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket")
                                val accept = type.constructors.firstOrNull { it.parameterCount == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType }
                                    ?.newInstance(id) ?: construct("packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket", id, 0.5, 64.0, 0.5, 0f, 0f)
                                session.send(accept as Packet)
                                if (ProtocolPackets.hasPlayerLoaded) {
                                    val loaded = Class.forName(PREFIX+"packet.ingame.serverbound.ServerboundPlayerLoadedPacket").getField("INSTANCE").get(null) as Packet
                                    session.send(loaded)
                                } else {
                                    val args = if (protocol == 767) arrayOf<Any>(false, 0.5, 64.0, 0.5, 0f, 0f)
                                        else arrayOf<Any>(false, false, 0.5, 64.0, 0.5, 0f, 0f)
                                    session.send(construct("packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket", *args) as Packet)
                                }
                            }
                            packet is ClientboundSystemChatPacket -> chat.countDown()
                            selectPremium != null && packet.javaClass.simpleName == "ClientboundOpenScreenPacket" -> {
                                val type = packet.javaClass.getMethod("getType").invoke(packet) as Enum<*>
                                assertEquals("GENERIC_9X3", type.name)
                            }
                            selectPremium != null && packet.javaClass.simpleName == "ClientboundContainerSetContentPacket" -> {
                                val window = packet.javaClass.getMethod("getContainerId").invoke(packet) as Int
                                val state = packet.javaClass.getMethod("getStateId").invoke(packet) as Int
                                val items = packet.javaClass.getMethod("getItems").invoke(packet) as Array<*>
                                if (window == 0) {
                                    fullInventorySyncs.incrementAndGet()
                                    assertEquals(46, items.size, "Full player inventory includes armor and offhand")
                                    assertTrue(items.all { it == null })
                                    assertNull(packet.javaClass.getMethod("getCarriedItem").invoke(packet))
                                    return
                                }
                                assertEquals(63, items.size, "27 menu + 36 authoritative player slots")
                                assertEquals(listOf(12, 14), items.indices.filter { items[it] != null })
                                assertNull(packet.javaClass.getMethod("getCarriedItem").invoke(packet))
                                val slot = if (selectPremium) 14 else 12
                                when (selectorStep.getAndIncrement()) {
                                    0 -> {
                                        firstWindow.set(window)
                                        session.send(construct("packet.ingame.serverbound.inventory.ServerboundContainerClosePacket", window) as Packet)
                                        session.send(selectorClick(window, state, slot, "CLICK_ITEM", "RIGHT_CLICK", null))
                                    }
                                    1 -> {
                                        assertNotEquals(firstWindow.get(), window, "Close must allocate a fresh non-reused window")
                                        assertEquals(0, selections.get())
                                        session.send(selectorClick(firstWindow.get(), state, slot, "CLICK_ITEM", "LEFT_CLICK", null))
                                        session.send(selectorClick(window, state + 99, if (slot == 12) 14 else 12, "CLICK_ITEM", "LEFT_CLICK", null))
                                    }
                                    2 -> {
                                        assertEquals(0, selections.get())
                                        val otherSlot = if (slot == 12) 14 else 12
                                        session.send(selectorClick(window, state, otherSlot, "SHIFT_CLICK_ITEM", "LEFT_CLICK", null))
                                        session.send(selectorClick(window, state, otherSlot, "MOVE_TO_HOTBAR_SLOT", "SLOT_1", null))
                                        session.send(selectorClick(window, state, otherSlot, "MOVE_TO_HOTBAR_SLOT", "OFF_HAND", null))
                                        session.send(selectorClick(window, state, otherSlot, "SPREAD_ITEM", "LEFT_MOUSE_ADD_SLOT", null))
                                        session.send(selectorClick(window, state, 13, "CLICK_ITEM", "LEFT_CLICK", null))
                                    }
                                    3 -> {
                                        assertEquals(0, selections.get(), "Rejected prediction burst must be corrected before selection")
                                        val prediction = if (protocol < 770) items[slot] else {
                                            val hashed = Class.forName(PREFIX + "data.game.item.HashedStack")
                                            hashed.constructors.single().newInstance(123, 1, emptyMap<Any, Any>(), emptySet<Any>())
                                        }
                                        session.send(selectorClick(window, state, slot, "CLICK_ITEM", "RIGHT_CLICK", prediction))
                                        session.send(selectorClick(window, state, slot, "CLICK_ITEM", "LEFT_CLICK", prediction))
                                    }
                                }
                            }
                        }
                    } catch (error: Throwable) { failure.set(error.toString()) }
                }
                override fun disconnected(event: DisconnectedEvent) { if (joins.get() == 0) failure.compareAndSet(null, "Client disconnected: ${event.reason}") }
            })
            client.javaClass.getMethod("connect", Boolean::class.javaPrimitiveType).invoke(client, true)
            assertTrue(joined.await(10, TimeUnit.SECONDS), "READY protocol=$protocol failure=${failure.get()}")
            assertTrue(chat.await(3, TimeUnit.SECONDS))
            assertEquals(1, joins.get())
            if (selectPremium != null) {
                assertTrue(selected.await(5, TimeUnit.SECONDS), "Selector protocol=$protocol failure=${failure.get()}")
                assertTrue(selectorStep.get() >= 4, "A close/old-window/wrong-state packet selected before correction")
                assertTrue(fullInventorySyncs.get() >= 4, "Initial/reopen/rejection-burst corrections must clear armor and offhand too")
                tasks.submit {}.get(2, TimeUnit.SECONDS)
                assertEquals(1, selections.get(), "Duplicate/current/old-window clicks must not select twice")
                assertNull(failure.get())
            }
            // A scheduled assertion spans more than two heartbeat periods, without injecting any render evidence.
            val alive = tasks.schedule(Callable { client.isConnected }, 2500, TimeUnit.MILLISECONDS)
            assertTrue(alive.get(4, TimeUnit.SECONDS), failure.get())
            client.disconnect("test complete")
            assertTrue(left.await(3, TimeUnit.SECONDS))
        } finally {
            client?.channel?.close()?.syncUninterruptibly()
            engine.close()
            tasks.submit {}.get(5, TimeUnit.SECONDS)
            tasks.shutdownNow()
        }
    }

    @Test fun `incomplete library transport expires and closes cleanly`() {
        val tasks = ScheduledThreadPoolExecutor(1)
        val engine = EngineFactory().create(EngineContext(LimboSettings(biomeKey="minecraft:plains", joinTimeoutSeconds=1),
            LimboListener.None, LimboLogger { _, _ -> }, InetSocketAddress("127.0.0.1",0), ForwardingMode.VelocityModern(secret), tasks))
        try {
            Socket().use { socket -> socket.connect(engine.start()); socket.soTimeout=4000; socket.getOutputStream().write(0x80); assertEquals(-1,socket.getInputStream().read()) }
        } finally { engine.close(); tasks.submit {}.get(5,TimeUnit.SECONDS); tasks.shutdownNow() }
    }

    private fun client(address: InetSocketAddress): Session {
        val minecraft = MinecraftProtocol(name)
        if (protocol <= 768) {
            val type = Class.forName("org.geysermc.mcprotocollib.network.tcp.TcpClientSession")
            return type.constructors.first { it.parameterCount == 3 }.newInstance(address.address.hostAddress, address.port, minecraft) as Session
        }
        val type = Class.forName("org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory")
        val factory = type.getMethod("factory").invoke(null)
        type.getMethod("setRemoteSocketAddress", java.net.SocketAddress::class.java).invoke(factory,address)
        type.getMethod("setProtocol", MinecraftProtocol::class.java).invoke(factory,minecraft)
        type.getMethod("setPacketHandlerExecutor", Executor::class.java).invoke(factory,Executor { it.run() })
        return type.getMethod("create").invoke(factory) as Session
    }

    private fun serialize(packet: Packet, buffer: io.netty.buffer.ByteBuf) {
        val method = packet.javaClass.methods.first { it.name == "serialize" && it.parameterCount in 1..2 }
        if(method.parameterCount == 1) method.invoke(packet,buffer)
        else {
            val supplier = GeneratedCodec.CODEC.javaClass.getMethod("getHelperFactory").invoke(GeneratedCodec.CODEC) as java.util.function.Supplier<*>
            method.invoke(packet,buffer,supplier.get())
        }
    }

    private fun construct(name: String, vararg args: Any?): Any {
        val primitive = mapOf(java.lang.Integer.TYPE to Int::class.javaObjectType, java.lang.Float.TYPE to Float::class.javaObjectType,
            java.lang.Double.TYPE to Double::class.javaObjectType, java.lang.Boolean.TYPE to Boolean::class.javaObjectType)
        val constructor = Class.forName(PREFIX+name).constructors.first { it.parameterCount == args.size && it.parameterTypes.zip(args.toList()).all { (type,value) ->
            value == null && !type.isPrimitive || value != null && (primitive[type] ?: type).isInstance(value)
        } }
        return constructor.newInstance(*args)
    }

    private fun selectorClick(window: Int, state: Int, slot: Int, action: String, button: String, prediction: Any?): Packet {
        val actionType = Class.forName(PREFIX + "data.game.inventory.ContainerActionType").enumConstants
            .single { (it as Enum<*>).name == action }
        val parameterType = when (action) {
            "SHIFT_CLICK_ITEM" -> "ShiftClickItemAction"
            "MOVE_TO_HOTBAR_SLOT" -> "MoveToHotbarAction"
            "SPREAD_ITEM" -> "SpreadItemAction"
            else -> "ClickItemAction"
        }
        val parameter = Class.forName(PREFIX + "data.game.inventory.$parameterType").enumConstants
            .single { (it as Enum<*>).name == button }
        return construct("packet.ingame.serverbound.inventory.ServerboundContainerClickPacket", window, state, slot,
            actionType, parameter, prediction, mapOf(slot to null)) as Packet
    }

    private fun payload(): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        fun variable(value: Int) { var n=value; do { val b=n and 127; n=n ushr 7; out.writeByte(b or if(n==0) 0 else 128) } while(n!=0) }
        fun string(value: String) { val data=value.toByteArray(); variable(data.size); out.write(data) }
        variable(1); string("127.0.0.1"); out.writeLong(uuid.mostSignificantBits); out.writeLong(uuid.leastSignificantBits)
        string(name); variable(1); string(ForwardedIdentity.CONNECTION_PROPERTY); string(connectionId.toString()); out.writeBoolean(false)
        val body=bytes.toByteArray()
        return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret,"HmacSHA256")) }.doFinal(body)+body
    }

    companion object { private const val PREFIX="org.geysermc.mcprotocollib.protocol." }
}
