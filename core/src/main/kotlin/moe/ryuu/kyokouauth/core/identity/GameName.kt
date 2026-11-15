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
         * A Bedrock name as Floodgate presents it, prefix and all.
         *
         * Floodgate prepends a non-alphanumeric prefix (`.` by default) so a Bedrock name can never
         * collide with a Java one. [parse] rejects exactly that prefix, and that rejection is what
         * keeps such a name out of the name table - but it also fires at the login event, before
         * anything has asked Floodgate who this connection is. Without this entry point an unlinked
         * Bedrock player is kicked as an invalid name before the branch that admits them can run,
         * which is a deadlock: linking is done from inside the server.
         *
         * Callers must pass a name Floodgate itself produced. A value made here is never bound -
         * [moe.ryuu.kyokouauth.bukkit.listener.LoginListener] admits Bedrock connections without
         * touching the name table, and every `bind*` call site is on a Java path, whose names come
         * from [parse].
         */
        fun forBedrock(raw: String): GameName = GameName(raw)
    }
}
