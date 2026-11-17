package moe.ryuu.kyokouauth.limbo.session

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import moe.ryuu.kyokouauth.limbo.RoleSelectorItem
import moe.ryuu.kyokouauth.limbo.RoleAvatarIcon
import moe.ryuu.kyokouauth.limbo.RoleSelectorMenu
import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket
import org.geysermc.mcprotocollib.auth.GameProfile
import org.geysermc.mcprotocollib.network.packet.Packet
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec
import org.geysermc.mcprotocollib.protocol.data.ProtocolState
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.function.Supplier
import kotlin.test.*

/** Wire seam: fixed upstream factories/IDs, authoritative menu payloads, and hostile typed decode budgets. */
class RoleSelectorProtocolTest {
    private val protocol = GeneratedCodec.PROTOCOL
    private val menu = RoleSelectorMenu(UUID(4, 5), TextComponent("选择角色"),
        RoleSelectorItem("ms", UUID(0, 42), "MicrosoftName", "test-texture", "test-signature", TextComponent("微软角色"), listOf(TextComponent("独立资料"))),
        RoleSelectorItem("java", UUID(1, 2), "PremiumName", null, null, TextComponent("正版角色"), emptyList()))
    private val componentOwner = "org.geysermc.mcprotocollib.protocol.data.game.item.component." +
        if (protocol <= 768) "DataComponentType" else "DataComponentTypes"
    private fun component(name: String) = Class.forName(componentOwner).getField(name).get(null) as DataComponentType<*>

    @Test fun `official inventory codecs preserve title exact slots profile texture signature and independent names`() {
        RoleSelectorPackets.verify()
        val before = RoleSelectorPackets.lookupCounts
        val open = RoleSelectorPackets.open(7, menu)
        assertEquals("GENERIC_9X3", open.type.name)
        val inventory = RoleSelectorPackets.playerInventory(1)
        assertEquals(0, inventory.containerId)
        assertEquals(46, inventory.items.size)
        assertTrue(inventory.items.all { it == null })
        assertNull(inventory.carriedItem)
        val packets = listOf(open, inventory, RoleSelectorPackets.content(7, 1, menu), RoleSelectorPackets.close(7))
        for (packet in packets) {
            val buffer = Unpooled.buffer()
            try {
                encode(packet, buffer)
                val full = MinecraftCodec.CODEC.getCodec(ProtocolState.GAME)
                val decoded = Wire.packet(full, full.getClientboundId(packet.javaClass), buffer)
                assertSame<Any>(packet.javaClass, decoded.javaClass)
                assertFalse(buffer.isReadable)
            } finally { buffer.release() }
        }
        val content = RoleSelectorPackets.content(7, 1, menu)
        assertEquals(63, content.items.size)
        assertEquals(listOf(12, 14), content.items.indices.filter { content.items[it] != null })
        assertNull(content.carriedItem)
        for ((slot, expected) in listOf(12 to menu.microsoft, 14 to menu.premium)) {
            val item = requireNotNull(content.items[slot])
            assertEquals(1, item.amount)
            val patch = item.javaClass.methods.single { it.name in setOf("getDataComponents", "getDataComponentsPatch") }.invoke(item)
            var profile = patch.javaClass.getMethod("get", DataComponentType::class.java).invoke(patch, component("PROFILE"))
            if (protocol >= 773) profile = profile.javaClass.getMethod("getProfile").invoke(profile)
            profile as GameProfile
            assertEquals(expected.profileUuid, profile.id)
            assertEquals(expected.profileName, profile.name)
            assertEquals(expected.textureValue, profile.properties.firstOrNull()?.value)
            assertEquals(expected.textureSignature, profile.properties.firstOrNull()?.signature)
        }
        repeat(20) { RoleSelectorPackets.content(7, 1, menu) }
        assertEquals(before, RoleSelectorPackets.lookupCounts, "Warmed menu construction must not perform more reflective lookups")
    }

    @Test fun `decoded selector item roots are nonitalic with explicit base colors and preserve template italic children`() {
        val styled = menu.copy(microsoft = menu.microsoft.copy(
            name = TextComponent("Item name", children = listOf(TextComponent("Explicit italic", italic = true))),
            lore = listOf(TextComponent("Lore line", children = listOf(TextComponent("Lore italic", italic = true)))),
        ))
        val packet = RoleSelectorPackets.content(7, 1, styled)
        val buffer = Unpooled.buffer()
        try {
            encode(packet, buffer)
            val codec = MinecraftCodec.CODEC.getCodec(ProtocolState.GAME)
            val decoded = Wire.packet(codec, codec.getClientboundId(packet.javaClass), buffer) as ClientboundContainerSetContentPacket
            val head = requireNotNull(decoded.items[12])
            val patch = head.javaClass.methods.single { it.name in setOf("getDataComponents", "getDataComponentsPatch") }.invoke(head)
            val get = patch.javaClass.getMethod("get", DataComponentType::class.java)
            val name = get.invoke(patch, component("CUSTOM_NAME")) as Component
            val lore = (get.invoke(patch, component("LORE")) as List<*>).single() as Component
            assertEquals(TextDecoration.State.FALSE, name.decoration(TextDecoration.ITALIC))
            assertEquals(NamedTextColor.WHITE, name.color())
            assertEquals(TextDecoration.State.FALSE, lore.decoration(TextDecoration.ITALIC))
            assertEquals(NamedTextColor.GRAY, lore.color())
            fun nodes(value: Component): List<Component> = listOf(value) + value.children().flatMap { nodes(it) }
            for ((root, text) in listOf(name to "Explicit italic", lore to "Lore italic")) {
                val explicit = nodes(root).filterIsInstance<net.kyori.adventure.text.TextComponent>().single { it.content() == text }
                assertEquals(TextDecoration.State.TRUE, explicit.decoration(TextDecoration.ITALIC))
            }
        } finally { buffer.release() }
    }

    @Test fun `decoded avatar markers are exact and opt in without replacing real profiles`() {
        fun decoded(value: RoleSelectorMenu): ClientboundContainerSetContentPacket {
            val packet = RoleSelectorPackets.content(7, 1, value)
            val buffer = Unpooled.buffer()
            try {
                encode(packet, buffer)
                val codec = MinecraftCodec.CODEC.getCodec(ProtocolState.GAME)
                return Wire.packet(codec, codec.getClientboundId(packet.javaClass), buffer) as ClientboundContainerSetContentPacket
            } finally { buffer.release() }
        }
        fun value(head: ItemStack, name: String): Any? {
            val type = Class.forName(componentOwner).getField(name).get(null) ?: return null
            val patch = head.javaClass.methods.single { it.name in setOf("getDataComponents", "getDataComponentsPatch") }.invoke(head)
            return patch.javaClass.getMethod("get", DataComponentType::class.java).invoke(patch, type)
        }
        val java = decoded(menu)
        val marked = menu.copy(microsoft = menu.microsoft.copy(avatarIcon = RoleAvatarIcon.MICROSOFT),
            premium = menu.premium.copy(avatarIcon = RoleAvatarIcon.PREMIUM))
        val bedrock = decoded(marked)
        for ((slot, icon, expected) in listOf(Triple(12, RoleAvatarIcon.MICROSOFT, menu.microsoft), Triple(14, RoleAvatarIcon.PREMIUM, menu.premium))) {
            val unmarked = requireNotNull(java.items[slot])
            val head = requireNotNull(bedrock.items[slot])
            assertNull(value(unmarked, "CUSTOM_MODEL_DATA"), "Java default must not acquire a private avatar marker")
            val cmd = assertNotNull(value(head, "CUSTOM_MODEL_DATA"), "Opt-in avatar marker was not serialized")
            assertEquals(unmarked.id, head.id)
            assertEquals(1, head.amount)
            if (protocol <= 768) assertEquals(icon.modelData, assertIs<Int>(cmd))
            else {
                val floats = cmd.javaClass.getMethod("floats").invoke(cmd) as List<*>
                assertEquals(1, floats.size)
                assertEquals(icon.modelData.toFloat(), assertIs<Float>(floats.single()))
                for (field in listOf("flags", "strings", "colors")) {
                    assertTrue((cmd.javaClass.getMethod(field).invoke(cmd) as List<*>).isEmpty())
                }
            }
            var profile = assertNotNull(value(head, "PROFILE"))
            if (protocol >= 773) profile = profile.javaClass.getMethod("getProfile").invoke(profile)
            profile as GameProfile
            assertEquals(expected.profileUuid, profile.id)
            assertEquals(expected.profileName, profile.name)
            assertEquals(expected.textureValue, profile.properties.firstOrNull()?.value)
            assertEquals(expected.textureSignature, profile.properties.firstOrNull()?.signature)
        }
    }

    @Test fun `typed click rejects changed-slot preallocation and oversized frame before decoding`() {
        val packet = click(null, (0..64).associateWith { null })
        val buffer = Unpooled.buffer()
        try {
            encode(packet, buffer)
            val failure = assertFailsWith<IllegalArgumentException> { receive(packet, buffer) }
            assertContains(failure.message.orEmpty(), "changedSlots")
            buffer.clear().writeZero(65537)
            val frame = assertFailsWith<IllegalArgumentException> { receive(packet, buffer) }
            assertContains(frame.message.orEmpty(), "frame")
            assertEquals(0, buffer.readerIndex(), "Frame budget must be checked before parsing")
        } finally { buffer.release() }
    }

    @Test fun `legacy typed predictions reject component count unknown type huge nbt allocation and deep nbt`() {
        if (protocol >= 770) return // These versions carry bounded component hashes instead of item/NBT values.
        val stack = ItemStack(1, 1, null)
        val packet = click(stack, emptyMap())
        fun hostile(payload: (ByteBuf) -> Unit): Throwable {
            val bytes = Unpooled.buffer()
            try {
                encode(packet, bytes)
                // Replace only the two empty patch counters emitted by the official serializer.
                bytes.writerIndex(bytes.writerIndex() - 2)
                payload(bytes)
                return assertFails { receive(packet, bytes) }
            } finally { bytes.release() }
        }
        assertContains(hostile { variable(it, 5); variable(it, 0) }.message.orEmpty(), "components")
        assertContains(hostile { variable(it, 1); variable(it, 0); variable(it, 0) }.message.orEmpty(), "Unsupported role selector component")
        val customName = component("CUSTOM_NAME").id
        // Byte-array tag length is deliberately absurd, with no body. Built-in LimitedDataInput must reject before allocation.
        val huge = hostile { variable(it, 1); variable(it, 0); variable(it, customName); it.writeByte(7).writeInt(Int.MAX_VALUE) }
        assertFalse(huge is OutOfMemoryError)
        assertFalse(huge is IndexOutOfBoundsException, "NBT allocation budget must fail before a giant array/body read")
        val deep = hostile { bytes ->
            variable(bytes, 1); variable(bytes, 0); variable(bytes, customName)
            bytes.writeByte(10)
            repeat(20) { bytes.writeByte(10).writeShort(1).writeByte('x'.code) }
            repeat(21) { bytes.writeByte(0) }
        }
        assertContains(deep.message.orEmpty(), "deeply nested")
    }

    @Test fun `hashed predictions reject component collection budget without treating predictions as authorization`() {
        if (protocol < 770) return
        val type = Class.forName("org.geysermc.mcprotocollib.protocol.data.game.item.HashedStack")
        val hash = type.constructors.single().newInstance(123, 1, emptyMap<Any, Any>(), emptySet<Any>())
        val packet = click(hash, emptyMap())
        val buffer = Unpooled.buffer()
        try {
            encode(packet, buffer)
            // Hash maps were both empty: overwrite the final two counters with an oversized added count.
            buffer.writerIndex(buffer.writerIndex() - 2)
            variable(buffer, 5); variable(buffer, 0)
            val failure = assertFailsWith<IllegalArgumentException> { receive(packet, buffer) }
            assertContains(failure.message.orEmpty(), "hashedComponent")
        } finally { buffer.release() }
    }

    private fun click(carried: Any?, changed: Map<Int, Any?>): Packet {
        val type = Class.forName("org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket")
        val constructor = type.constructors.single { it.parameterCount == 7 && it.parameterTypes.last() == Map::class.java }
        return constructor.newInstance(7, 1, 12, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK, carried, changed) as Packet
    }
    private fun helper(): Any? = if (protocol <= 768)
        (GeneratedCodec.CODEC.javaClass.getMethod("getHelperFactory").invoke(GeneratedCodec.CODEC) as Supplier<*>).get() else null
    private fun encode(packet: Packet, buffer: ByteBuf) {
        val method = packet.javaClass.methods.single { it.name == "serialize" }
        if (protocol <= 768) method.invoke(packet, buffer, helper()) else method.invoke(packet, buffer)
    }
    private fun receive(packet: Packet, buffer: ByteBuf): Packet {
        val registry = GeneratedCodec.CODEC.getCodec(ProtocolState.GAME)
        val id = MinecraftCodec.CODEC.getCodec(ProtocolState.GAME).getServerboundId(packet.javaClass)
        val method = registry.javaClass.methods.single { it.name == "createServerboundPacket" }
        return try {
            if (protocol <= 768) method.invoke(registry, id, buffer, helper()) as Packet else method.invoke(registry, id, buffer) as Packet
        } catch (failure: InvocationTargetException) { throw failure.targetException }
    }
    private fun variable(buffer: ByteBuf, value: Int) {
        if (protocol <= 768) helper()!!.javaClass.getMethod("writeVarInt", ByteBuf::class.java, Int::class.java).invoke(helper(), buffer, value)
        else Class.forName("org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes")
            .getMethod("writeVarInt", ByteBuf::class.java, Int::class.java).invoke(null, buffer, value)
    }
}
