package moe.ryuu.kyokouauth.limbo.session

import moe.ryuu.kyokouauth.limbo.RoleSelectorIcon
import moe.ryuu.kyokouauth.limbo.RoleSelectorItem
import moe.ryuu.kyokouauth.limbo.RoleSelectorMenu
import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import org.geysermc.mcprotocollib.auth.GameProfile
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerClosePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket
import java.util.Properties

/** Fixed, narrowly typed official inventory factories. No client-provided item ever enters these methods. */
internal object RoleSelectorPackets {
    const val MICROSOFT_SLOT = 12
    const val PREMIUM_SLOT = 14
    const val MENU_SLOTS = 27
    const val TOTAL_SLOTS = MENU_SLOTS + 36
    // InventoryMenu window 0 includes crafting/armor and offhand (SHIELD_SLOT 45); oracle verifies this per version.
    const val PLAYER_INVENTORY_SLOTS = 46
    private val protocol = GeneratedCodec.PROTOCOL
    private val lookup = PacketLookup(protocol)
    private const val ROOT = "org.geysermc.mcprotocollib.protocol."
    private val componentsOwner = ROOT + "data.game.item.component." + if (protocol <= 768) "DataComponentType" else "DataComponentTypes"
    @Suppress("UNCHECKED_CAST")
    private fun component(name: String) = lookup.staticField(componentsOwner, name, DataComponentType::class.java) as DataComponentType<Any>
    private val profileComponent = component("PROFILE")
    private val nameComponent = component("CUSTOM_NAME")
    private val loreComponent = component("LORE")
    private val resolvable = if (protocol >= 773) lookup.constructor(ROOT + "data.game.entity.player.ResolvableProfile",
        Any::class.java, GameProfile::class.java) else null
    private val fixture = Properties().apply {
        val path = "/limbo/role-selector-$protocol.properties"
        RoleSelectorPackets::class.java.getResourceAsStream(path).use { load(requireNotNull(it) { "Missing official selector fixture $path" }) }
        require(getProperty("protocol").toInt() == protocol)
        require(getProperty("source") == "official-server-bootstrap-built-in-registries")
        for (key in listOf("server-sha256", "registry-server-sha256", "exporter-sha256")) {
            require(getProperty(key).matches(Regex("[a-f0-9]{64}"))) { "Invalid selector fixture provenance" }
        }
        require(getProperty("generic-9x3-id").toInt() == ContainerType.GENERIC_9X3.ordinal) { "Upstream menu registry differs from official fixture" }
    }
    private val itemIds = mapOf(
        RoleSelectorIcon.PLAYER_HEAD to fixture.getProperty("player-head-id").toInt(),
        RoleSelectorIcon.NAME_TAG to fixture.getProperty("name-tag-id").toInt(),
        RoleSelectorIcon.DIAMOND to fixture.getProperty("diamond-id").toInt(),
    ).also { ids -> require(ids.values.all { it > 0 } && ids.values.toSet().size == RoleSelectorIcon.entries.size) }
    internal val lookupCounts get() = lookup.counts()

    /** Eager at engine startup, including the legacy component owner and modern profile wrapper signatures. */
    fun verify() = Unit

    fun open(window: Int, menu: RoleSelectorMenu) = ClientboundOpenScreenPacket(window, ContainerType.GENERIC_9X3, menu.title.toLibrary())
    fun close(window: Int) = ClientboundContainerClosePacket(window)
    fun playerInventory(state: Int) = ClientboundContainerSetContentPacket(0, state, arrayOfNulls<ItemStack>(PLAYER_INVENTORY_SLOTS), null)

    fun content(window: Int, state: Int, menu: RoleSelectorMenu): ClientboundContainerSetContentPacket {
        // The menu is 27 slots; SetContent also includes all 36 player inventory slots. All are server-owned.
        val items = arrayOfNulls<ItemStack>(TOTAL_SLOTS)
        items[MICROSOFT_SLOT] = item(menu.microsoft)
        items[PREMIUM_SLOT] = item(menu.premium)
        return ClientboundContainerSetContentPacket(window, state, items, null)
    }

    private fun item(item: RoleSelectorItem): ItemStack {
        val patch = DataComponents(HashMap())
        if (item.icon == RoleSelectorIcon.PLAYER_HEAD) {
            val profile = GameProfile(item.profileUuid, item.profileName).apply {
                setProperties(item.textureValue?.let { listOf(GameProfile.Property("textures", it, item.textureSignature)) } ?: emptyList())
            }
            patch.put(profileComponent, resolvable?.newInstance(profile) ?: profile)
        }
        patch.put(nameComponent, itemText(item.name, "white").toLibrary())
        patch.put(loreComponent, item.lore.map { itemText(it, "gray").toLibrary() })
        return ItemStack(itemIds.getValue(item.icon), 1, patch)
    }

    /** Only item roots override vanilla defaults; explicit template styles remain intact in the child. */
    private fun itemText(template: TextComponent, baseColor: String) =
        TextComponent("", color = baseColor, italic = false, children = listOf(template))
}
