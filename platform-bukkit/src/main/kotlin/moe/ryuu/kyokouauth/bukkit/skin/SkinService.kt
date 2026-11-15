package moe.ryuu.kyokouauth.bukkit.skin

import com.destroystokyo.paper.profile.PlayerProfile
import com.destroystokyo.paper.profile.ProfileProperty
import moe.ryuu.kyokouauth.core.skin.BedrockSkinSource
import moe.ryuu.kyokouauth.core.skin.MojangSkinSource
import moe.ryuu.kyokouauth.core.skin.OperatorSkinSource
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * Decides which skin a player gets, and applies it to the profile used for their connection.
 *
 * Three sources, tried in order, because each covers a gap the previous one leaves:
 *
 *  1. **The player's own Mojang profile.** A premium player already has a skin and it arrives with
 *     Mojang's signature, so clients can verify it rather than merely render it.
 *  2. **Their Bedrock skin.** A Microsoft player has no Java skin but may have played on Bedrock;
 *     Geyser's registry maps their XUID to the texture their Bedrock client uses.
 *  3. **The operator's map.** A player with neither has no skin anywhere. `skins.yml` lets the
 *     operator name a texture URL for them.
 *
 * SkinsRestorer is deliberately not in this list. It manages the `textures` property itself and
 * fights anything else that writes to it, so when it is installed this class steps aside entirely.
 * Two plugins writing one profile property is worse than no skin at all.
 */
class SkinService(
    private val plugin: Plugin,
    private val operatorSkins: OperatorSkinSource,
    private val logger: (String) -> Unit,
) {

    /** True when SkinsRestorer owns skins here, in which case this class does nothing at all. */
    val skinsRestorerPresent: Boolean =
        plugin.server.pluginManager.getPlugin("SkinsRestorer")?.isEnabled == true

    /**
     * Applies the best available skin to [profile].
     *
     * Runs on the async pre-login thread - the only moment a skin can still be changed, because the
     * profile is frozen once `Login Success` is sent.
     *
     * @param uuid the identity UUID, used to look up a premium skin.
     * @param xuid the Xbox id, used to look up a Bedrock skin. Null for premium players.
     */
    fun apply(profile: PlayerProfile, uuid: UUID, nameKey: String, xuid: Long?) {
        if (skinsRestorerPresent) return

        if (xuid == null) {
            MojangSkinSource.lookup(uuid)?.let { skin ->
                profile.setProperty(ProfileProperty("textures", skin.value, skin.signature))
                return
            }
        }

        if (xuid != null) {
            when (val result = BedrockSkinSource.lookup(xuid)) {
                is BedrockSkinSource.Result.Found -> {
                    profile.setProperty(ProfileProperty("textures", result.skin.value, result.skin.signature))
                    return
                }

                is BedrockSkinSource.Result.Failed ->
                    // Not fatal: the player joins with a default skin rather than being refused.
                    logger("Bedrock skin lookup failed for xuid $xuid: ${result.reason}")

                BedrockSkinSource.Result.None -> Unit
            }
        }

        operatorSkins.lookup(nameKey)?.let { url ->
            // Unsigned: producing a signature needs Mojang's private key, and a server can only ever
            // pass one along. Clients render unsigned skins; they are simply not verifiable.
            val payload = """{"textures":{"SKIN":{"url":"$url"}}}"""
            val value = java.util.Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
            profile.setProperty(ProfileProperty("textures", value))
        }
    }
}
