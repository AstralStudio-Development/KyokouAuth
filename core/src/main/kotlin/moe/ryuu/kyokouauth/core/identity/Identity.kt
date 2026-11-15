package moe.ryuu.kyokouauth.core.identity

import java.util.UUID

/**
 * The account that a player is currently acting as.
 *
 * Two shapes only, matching the two Auth Channels:
 *  - [Premium]   proven by a Mojang handshake, carries the real Mojang UUID and skin.
 *  - [Microsoft] proven by a device code, carries only an Xbox XUID.
 *
 * The identity of a Microsoft player is derived, never stored as a separate fact: see [uuid].
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
    ) : Identity() {

        override val uuid: UUID get() = ofXuid(xuid)
    }

    companion object {
        /**
         * Floodgate's rule, adopted verbatim so that one Microsoft account resolves to one and the
         * same UUID whether it connects through Java or through the Bedrock bridge.
         */
        fun ofXuid(xuid: Xuid): UUID = UUID(0L, xuid.value)
    }
}
