package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import java.util.UUID

/** Presentation is derived after authentication, not a credential supplied by a client. */
data class RoleOption(val identity: Identity, val existing: BoundIdentity?, val skin: GameProfile.Property?) {
    val reference: RoleReference get() = RoleReference(
        if (identity is Identity.Premium) BoundIdentity.Kind.MOJANG else BoundIdentity.Kind.XUID,
        identity.uuid, identity.name.value)
}

data class RoleOptions(val roles: List<RoleOption>, val linkedJavaUuid: UUID?) {
    init {
        require(roles.size in 1..2 && roles.map { it.identity.uuid }.distinct().size == roles.size)
        require(roles.count { it.identity is Identity.Premium } <= 1 && roles.count { it.identity is Identity.Microsoft } <= 1)
        require(roles.all { it.skin == null || it.skin.name == "textures" })
    }
}
