package moe.ryuu.kyokouauth.core.identity

/**
 * An Xbox account identifier. 64-bit, assigned by Microsoft, stable across renames.
 *
 * This is the only credential KyokouAuth ever extracts from the Microsoft device code flow.
 * Tokens are discarded immediately after the XUID is read; nothing token-shaped is persisted.
 */
@JvmInline
value class Xuid(val value: Long) : Comparable<Xuid> {

    override fun compareTo(other: Xuid): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()
}
