package moe.ryuu.kyokouauth.limbo

import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

/** Frozen SDK-free selector seam; old player/listener fakes remain source compatible. */
class RoleSelectorApiTest {
    @Test fun `menu carries only server options and legacy player fails closed`() {
        val microsoft = RoleSelectorItem("microsoft", UUID(0, 42), "MicrosoftName", null, null,
            TextComponent("微软角色"), listOf(TextComponent("Independent profile")))
        val premium = RoleSelectorItem("premium", UUID(1, 2), "PremiumName", "texture", "signature",
            TextComponent("正版角色"), emptyList())
        val menu = RoleSelectorMenu(UUID.randomUUID(), TextComponent("选择角色"), microsoft, premium)
        val player = LegacyPlayer()
        // A legacy engine must never silently pretend that the player was offered a choice.
        assertFailsWith<UnsupportedOperationException> { player.openRoleSelector(menu) }
        player.closeRoleSelector()
        LimboListener.None.onRoleSelect(player, menu.generation, premium.optionId)
        assertEquals("microsoft", menu.microsoft.optionId)
        assertEquals("signature", menu.premium.textureSignature)
        assertNull(menu.microsoft.avatarIcon)
        assertNull(menu.premium.avatarIcon)
        assertEquals(0x4B5901, RoleAvatarIcon.MICROSOFT.modelData)
        assertEquals(0x4B5902, RoleAvatarIcon.PREMIUM.modelData)
        assertEquals(2, RoleAvatarIcon.entries.size)
        RoleAvatarIcon.entries.forEach { icon ->
            assertTrue(icon.modelData in 1 until (1 shl 24))
            assertEquals(icon.modelData, icon.modelData.toFloat().toInt())
        }
        val oldJavaConstructor = RoleSelectorItem::class.java.getConstructor(String::class.java, UUID::class.java, String::class.java,
            String::class.java, String::class.java, TextComponent::class.java, List::class.java)
        assertNull(oldJavaConstructor.newInstance("java", UUID(1, 2), "PremiumName", null, null, TextComponent("Label"), emptyList<TextComponent>()).avatarIcon)
        val leaked = (RoleSelectorItem::class.java.declaredFields + RoleSelectorMenu::class.java.declaredFields)
            .map { it.type.name }.filter { it.startsWith("org.geysermc.") || it.startsWith("net.kyori.") }
        assertTrue(leaked.isEmpty(), "Public selector API leaked SDK types: $leaked")
    }

    private class LegacyPlayer : LimboPlayer {
        override val name = "LegacyPlayer"
        override val uuid = UUID(0, 1)
        override val connectionId = UUID(0, 2)
        override val address = "127.0.0.1"
        override fun sendMessage(message: TextComponent) = Unit
        override fun showTitle(title: TextComponent, subtitle: TextComponent?, fadeIn: Int, stay: Int, fadeOut: Int) = Unit
        override fun clearTitle() = Unit
        override fun disconnect(reason: TextComponent) = Unit
        override fun close() = Unit
    }
}
