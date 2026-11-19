package moe.ryuu.kyokouauth.core.identity

import java.util.UUID

/**
 * The account that a player is currently acting as.
 *
 * Two UUID namespaces; the XUID namespace supports more than one trusted proof source:
 *  - [Premium]   proven by a Mojang handshake, carries the real Mojang UUID and skin.
 *  - [Microsoft] proven by a device code or connection-verified Floodgate, carries an Xbox XUID.
 *
 * Microsoft authentication uses the XUID; the server profile may be a separately owned legacy UUID.
 * Unmapped identities keep the original XUID-derived profile by default.
 */
sealed class Identity {

    abstract val name: GameName

    /** The UUID this identity occupies on the server. */
    abstract val uuid: UUID

    data class Premium(
        override val name: GameName,
        val mojangUuid: UUID,
    ) : Identity() {
        override val uuid: UUID get() = mojangUuid
    }

    data class Microsoft(
        override val name: GameName,
        val xuid: Xuid,
        val gamertag: String,
        val profileUuid: UUID = ofXuid(xuid),
    ) : Identity() {
        init {
            // XUID zero was historically accepted with the default UUID; preserve that behaviour.
            require(profileUuid == ofXuid(xuid) || profileUuid != UUID(0L, 0L)) {
                "a mapped profile UUID must not be zero"
            }
        }

        override val uuid: UUID get() = profileUuid
    }

    companion object {
        /**
         * Floodgate's rule, adopted verbatim so that one Microsoft account resolves to one and the
         * same UUID whether it connects through Java or through the Bedrock bridge.
         */
        fun ofXuid(xuid: Xuid): UUID = UUID(0L, xuid.value)
    }
}
