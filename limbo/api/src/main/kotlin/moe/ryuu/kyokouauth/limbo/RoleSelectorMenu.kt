package moe.ryuu.kyokouauth.limbo

import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import java.util.UUID

/** Private, exact presentation sentinels; never identity/permission evidence or a skull skin hash. */
enum class RoleAvatarIcon(val modelData: Int) {
    MICROSOFT(0x4B5901),
    PREMIUM(0x4B5902),
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
    val avatarIcon: RoleAvatarIcon? = null,
)

/** Two independent server-owned options. A refresh must use a new [generation]. */
data class RoleSelectorMenu(
    val generation: UUID,
    val title: TextComponent,
    val microsoft: RoleSelectorItem,
    val premium: RoleSelectorItem,
)
