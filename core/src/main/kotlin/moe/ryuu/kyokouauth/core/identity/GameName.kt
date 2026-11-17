package moe.ryuu.kyokouauth.core.identity

import java.util.Locale

/**
 * A Minecraft login name.
 *
 * Names are compared case-insensitively: Minecraft accounts are unique regardless of case, so
 * `Notch` and `notch` must never be able to become two separate accounts.
 *
 * Instances are only obtainable through [parse], so an invalid name cannot exist.
 */
class GameName private constructor(val value: String) {

    val key: String = value.lowercase(Locale.ROOT)

    override fun equals(other: Any?): Boolean = other is GameName && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        /** Mirrors the vanilla username rule: 3-16 characters of `[A-Za-z0-9_]`. */
        private val VALID = Regex("^[A-Za-z0-9_]{3,16}$")

        fun parse(raw: String): GameName? = if (VALID.matches(raw)) GameName(raw) else null

        fun parseOrThrow(raw: String): GameName =
            parse(raw) ?: throw IllegalArgumentException("not a valid Minecraft name: '$raw'")

        /**
         * An isolated display name already produced by a connection-verified Floodgate provider.
         * It is never a Java name reservation: the Gate does not persist a binding or Java session.
         * This factory validates shape, not authentication; callers still need the native proof.
         */
        fun forBedrock(raw: String): GameName {
            require(raw.length in 1..16 && raw.none { it.isISOControl() || it.isWhitespace() } && !VALID.matches(raw)) {
                "Invalid isolated Bedrock display name"
            }
            return GameName(raw)
        }
    }
}
