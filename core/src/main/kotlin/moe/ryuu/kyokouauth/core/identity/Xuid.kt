package moe.ryuu.kyokouauth.core.identity

/**
 * An Xbox account identifier. 64-bit, assigned by Microsoft, stable across renames.
 *
 * Obtained from the Microsoft device-code exchange or a current connection-verified Floodgate proof.
 * Tokens are discarded immediately after the XUID is read; nothing token-shaped is persisted.
 */
@JvmInline
value class Xuid(val value: Long) : Comparable<Xuid> {

    override fun compareTo(other: Xuid): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()
}
