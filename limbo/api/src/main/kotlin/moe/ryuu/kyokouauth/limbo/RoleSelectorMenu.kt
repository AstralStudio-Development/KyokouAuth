package moe.ryuu.kyokouauth.limbo

import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import java.util.UUID

/** Server-owned vanilla presentation only; never identity or permission evidence. */
enum class RoleSelectorIcon {
    PLAYER_HEAD,
    NAME_TAG,
    DIAMOND,
}

/** Presentation only; neither the profile nor the texture is evidence of permission to use a role. */
data class RoleSelectorItem @JvmOverloads constructor(
    val optionId: String,
    val profileUuid: UUID,
    val profileName: String,
    val textureValue: String?,
    val textureSignature: String?,
    val name: TextComponent,
    val lore: List<TextComponent>,
    val icon: RoleSelectorIcon = RoleSelectorIcon.PLAYER_HEAD,
)

/** Two independent server-owned options. A refresh must use a new [generation]. */
data class RoleSelectorMenu(
    val generation: UUID,
    val title: TextComponent,
    val microsoft: RoleSelectorItem,
    val premium: RoleSelectorItem,
)
