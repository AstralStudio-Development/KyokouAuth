package moe.ryuu.kyokouauth.core.auth

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import moe.ryuu.kyokouauth.core.storage.IdentityStore
import java.util.UUID

/** Current, independently verified Java profile. Never reconstructed from a historical link row. */
data class LinkedJavaProfile(val uuid: UUID, val name: GameName)

sealed class AccountLinkResult {
    data object NotLinked : AccountLinkResult()
    data class Linked(val profile: LinkedJavaProfile) : AccountLinkResult()
    data class Unavailable(val reason: String) : AccountLinkResult()
}

data class RoleCandidate(val identity: Identity, val existing: BoundIdentity?)

sealed class RoleResolution {
    data class Ready(val roles: List<RoleCandidate>) : RoleResolution()
    data class Failed(val reason: String) : RoleResolution()
}

/** Read-only role resolution after this connection has proved [Xuid]; never a login credential. */
class RoleResolver(private val identities: IdentityStore) {
    fun resolve(
        xuid: Xuid,
        gamertag: String,
        link: AccountLinkResult,
        fallbackBedrockName: String? = null,
    ): RoleResolution {
        if (xuid.value <= 0) return RoleResolution.Failed("Invalid authenticated XUID")
        if (link is AccountLinkResult.Unavailable) return RoleResolution.Failed("Current account link is unavailable")
        return try {
            resolveCurrent(xuid, gamertag, link, fallbackBedrockName)
        } catch (_: Exception) {
            // JDBC failures or a record changing between uncached reads never become a role grant.
            RoleResolution.Failed("Role records are unavailable or inconsistent")
        }
    }

    private fun resolveCurrent(
        xuid: Xuid,
        gamertag: String,
        link: AccountLinkResult,
        fallbackBedrockName: String?,
    ): RoleResolution {
        val roles = mutableListOf<RoleCandidate>()
        val microsoftUuid = Identity.ofXuid(xuid)
        val microsoft = identities.bySubject(BoundIdentity.Kind.XUID, xuid.toString())
        val atMicrosoftUuid = identities.byUuid(microsoftUuid)
        if (microsoft != atMicrosoftUuid) return RoleResolution.Failed("Microsoft subject and UUID disagree")
        if (microsoft != null) {
            if (microsoft.uuid != microsoftUuid || microsoft.kind != BoundIdentity.Kind.XUID ||
                microsoft.subject != xuid.toString() || !consistent(microsoft)) {
                return RoleResolution.Failed("Microsoft role binding is inconsistent")
            }
            roles += RoleCandidate(Identity.Microsoft(microsoft.name, xuid, gamertag), microsoft)
        }

        if (link is AccountLinkResult.Linked) {
            val profile = link.profile
            if (profile.uuid.version() !in setOf(3, 4) || profile.uuid.variant() != 2 ||
                GameName.parse(profile.name.value) == null) {
                return RoleResolution.Failed("Invalid linked Java profile")
            }
            val java = identities.byUuid(profile.uuid)
            val bySubject = identities.bySubject(BoundIdentity.Kind.MOJANG, profile.uuid.toString())
            if (java != bySubject) return RoleResolution.Failed("Java subject and UUID disagree")
            if (java != null && (java.kind != BoundIdentity.Kind.MOJANG || java.subject != profile.uuid.toString() ||
                    java.uuid != profile.uuid || !consistent(java))) {
                return RoleResolution.Failed("Java role binding is inconsistent")
            }
            // A changed global profile must not overwrite a name held by a different local role.
            val claimedProfileName = identities.byName(profile.name)
            if (claimedProfileName != null && claimedProfileName.uuid != profile.uuid) {
                return RoleResolution.Failed("Linked Java name is held by another role")
            }
            val currentName = java?.name ?: profile.name
            roles += RoleCandidate(Identity.Premium(currentName, profile.uuid), java)
        } else if (microsoft == null && fallbackBedrockName != null) {
            // Explicit isolated Bedrock names only. Never manufacture an offline Java name from
            // the Xbox gamertag or a linked Java name, and never persist this display-only role.
            val name = try { GameName.forBedrock(fallbackBedrockName) } catch (_: IllegalArgumentException) {
                return RoleResolution.Failed("Invalid isolated Bedrock name")
            }
            if (identities.byName(name) != null) return RoleResolution.Failed("Bedrock display name is held by another role")
            roles += RoleCandidate(Identity.Microsoft(name, xuid, gamertag), null)
        }

        if (roles.isEmpty()) return RoleResolution.Failed("No available role")
        if (roles.map { it.identity.uuid }.distinct().size != roles.size ||
            roles.map { it.identity.name.key }.distinct().size != roles.size) {
            return RoleResolution.Failed("Role identities or names collide")
        }
        return RoleResolution.Ready(roles.toList())
    }

    private fun consistent(record: BoundIdentity): Boolean =
        GameName.parse(record.name.value) != null && identities.byName(record.name) == record
}
