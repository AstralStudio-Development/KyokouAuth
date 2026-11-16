package moe.ryuu.kyokouauth.bukkit.profile

import moe.ryuu.kyokouauth.bukkit.KyokouAuthPlugin
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import java.util.UUID

/**
 * Places a connection in the world under `UUID(0, xuid)` - the identity's own UUID.
 *
 * Also the only place a skin is applied, because a skin is a property of the profile and this is the
 * only moment the profile can still be changed: once `Login Success` is sent it is frozen.
 */
internal class PaperProfileApplier(private val plugin: KyokouAuthPlugin) : ProfileApplier {

    override fun apply(
        event: AsyncPlayerPreLoginEvent,
        uuid: UUID,
        name: GameName,
        record: BoundIdentity?,
        textures: Pair<String, String>?,
    ) {
        val profile = plugin.server.createProfile(uuid, name.value)

        // A bound record carries the XUID even for a player who has not verified *this* connection;
        // the name is already reserved to them, so their own skin is the right one either way.
        val xuid = record?.takeIf { it.kind == BoundIdentity.Kind.XUID }?.subject?.toLongOrNull()
        plugin.skins.apply(profile, uuid, name.key, xuid, textures)

        event.playerProfile = profile
    }
}
