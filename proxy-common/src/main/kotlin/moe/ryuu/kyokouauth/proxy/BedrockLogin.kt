package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import java.util.UUID

/** No third-party types or client-supplied marker can cross the connection-proof seam. */
sealed interface BedrockLogin {
    data object Java : BedrockLogin
    data class Verified(val proof: BedrockProof) : BedrockLogin
    data class Rejected(val reason: BedrockFailure) : BedrockLogin
}

enum class BedrockFailure { DISABLED, UNAVAILABLE, PROVIDER_DENIED, LINKED, INVALID_IDENTITY, UNSAFE_NAME, STALE }

/** Native identity and XUID proof are separate from the server role selected after authentication. */
class BedrockProof private constructor(
    val identity: Identity,
    val xuid: Xuid,
    val gamertag: String,
    private val source: Any,
    private val current: () -> Boolean,
) {
    val uniqueId: UUID get() = identity.uuid
    val username: String get() = identity.name.value
    val linkedJavaUuid: UUID? get() = (identity as? Identity.Premium)?.mojangUuid

    fun isCurrent(): Boolean = runCatching(current).getOrDefault(false)
    fun matches(other: BedrockProof): Boolean = source === other.source && identity == other.identity &&
        xuid == other.xuid && gamertag == other.gamertag && isCurrent() && other.isCurrent()

    companion object {
        fun create(xuid: Xuid, uuid: UUID, username: String, gamertag: String, prefix: String,
                   source: Any, current: () -> Boolean): BedrockProof {
            validateXuid(xuid, gamertag)
            require(uuid == Identity.ofXuid(xuid)) { "Invalid Bedrock UUID" }
            require(prefix.isNotEmpty() && prefix.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '_' } &&
                username.startsWith(prefix) && username.length > prefix.length && GameName.parse(username) == null) { "Bedrock names require an isolated prefix" }
            return BedrockProof(Identity.Microsoft(GameName.forBedrock(username), xuid, gamertag), xuid, gamertag, source, current)
        }

        fun createLinked(xuid: Xuid, uuid: UUID, username: String, gamertag: String,
                         source: Any, current: () -> Boolean): BedrockProof {
            validateXuid(xuid, gamertag)
            require(uuid.version() in setOf(3, 4) && uuid.variant() == 2) { "Invalid linked Java UUID" }
            return BedrockProof(Identity.Premium(GameName.parseOrThrow(username), uuid), xuid, gamertag, source, current)
        }

        private fun validateXuid(xuid: Xuid, gamertag: String) {
            require(xuid.value > 0L && gamertag.isNotBlank() && gamertag.length <= 64 &&
                gamertag.none(Char::isISOControl)) { "Invalid Bedrock identity" }
        }
    }
}

/** Native adapters own channel discovery; only these bounded read operations reach reflection code. */
class FloodgateConnection(val token: Any, val active: () -> Boolean, val attribute: (String) -> Any?)

/** The provider generation must still be installed; descriptors/classloaders are never taken from clients. */
class FloodgateProvider(val instance: Any, val version: String, val classLoader: ClassLoader, val current: () -> Boolean)
