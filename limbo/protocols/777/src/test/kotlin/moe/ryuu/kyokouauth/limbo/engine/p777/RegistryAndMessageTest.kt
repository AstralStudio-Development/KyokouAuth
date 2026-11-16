package moe.ryuu.kyokouauth.limbo.session

import io.netty.buffer.Unpooled
import moe.ryuu.kyokouauth.limbo.LimboSettings
import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket
import org.junit.jupiter.api.Test
import kotlin.test.*

class RegistryAndMessageTest {
    @Test fun `snapshot is complete and numeric ids match the sent registry order`() {
        val snapshot = RegistrySnapshot.load()
        assertEquals(32, snapshot.registries.size)
        assertEquals(432, snapshot.registries.sumOf { it.entries.size })
        assertEquals(0, snapshot.dimensionId)
        assertEquals(41, snapshot.plainsId)
        assertEquals(-64, snapshot.minY)
        assertEquals(384, snapshot.height)
    }
    @Test fun `truncated configuration is refused before listening`() {
        assertFails { RegistrySnapshot.decode(byteArrayOf(0x80.toByte())) }
        assertFails { RegistrySnapshot.decode(byteArrayOf(0x7f, 7, 0)) }
    }
    @Test fun `world uses full light masks and a bounded spawn-centred batch`() {
        val world = LimboWorld(LimboSettings(biomeKey = "minecraft:plains"), RegistrySnapshot.load())
        val packets = world.joinPackets(19)
        assertEquals(25, packets.count { it is ClientboundLevelChunkWithLightPacket })
        val chunk = world.chunk(0, 0) as ClientboundLevelChunkWithLightPacket
        assertEquals(26, chunk.lightData.skyYMask.cardinality())
        assertEquals(26, chunk.lightData.emptyBlockYMask.cardinality())
        assertEquals(26, chunk.lightData.skyUpdates.size)
        assertTrue(chunk.lightData.skyUpdates.all { it.size == 2048 && it.all { b -> b == 0xff.toByte() } })
        val buf = Unpooled.wrappedBuffer(chunk.chunkData)
        try {
            repeat(24) {
                val section = MinecraftTypes.readChunkSection(buf, 1, 67)
                assertEquals(0, section.blockCount)
                assertEquals(0, section.fluidCount)
                assertEquals(41, section.biomeData.get(0, 0, 0))
            }
            assertFalse(buf.isReadable)
        } finally { buf.release() }
    }
    @Test fun `structured messages retain Unicode empty roots and exact click semantics`() {
        val msg = TextComponent("", children = listOf(TextComponent("验证 🌸\n", color = "#22d3ee"),
            TextComponent("Open", underlined = true, click = TextComponent.Click.OpenUrl("https://example.invalid/limbo-oracle"))))
        val buf = Unpooled.buffer()
        try {
            ClientboundSystemChatPacket(msg.toLibrary(), false).serialize(buf)
            assertEquals(10, buf.getUnsignedByte(0).toInt())
            assertEquals(8, buf.getUnsignedByte(1).toInt(), "compound starts with a named field, not empty root-name bytes")
            val decoded = ClientboundSystemChatPacket(buf)
            val link = decoded.content.children()[1].clickEvent()
            assertEquals(ClickEvent.Action.OPEN_URL, link?.action())
            assertEquals("https://example.invalid/limbo-oracle", (link?.payload() as? ClickEvent.Payload.Text)?.value())
            assertFalse(buf.isReadable)
        } finally { buf.release() }
    }
}
