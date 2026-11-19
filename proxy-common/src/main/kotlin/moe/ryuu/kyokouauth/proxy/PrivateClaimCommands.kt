package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.identity.GameName

/** Private packet ingress only. Never send this command family through the ordinary dispatcher. */
object PrivateClaimCommands {
    enum class Action { AUTHME, STATUS, CANCEL, USAGE }
    class Input internal constructor(val action: Action, val legacyName: GameName? = null,
                                     private var password: CharArray? = null) : AutoCloseable {
        /** Transfers buffer ownership; the consumer must erase it even if execution is rejected. */
        fun takePassword(): CharArray? = password.also { password = null }
        override fun close() { password?.fill('\u0000'); password = null }
        override fun toString() = "PrivateClaimInput[REDACTED]"
    }

    fun isSensitive(line: String): Boolean {
        val root = word(line, 0) ?: return false
        if (CommandNames.root(line.substring(root.first, root.second)) !in setOf("ka", "kyokouauth")) return false
        val family = word(line, root.second) ?: return false
        return line.regionMatches(family.first, "claim", 0, 5, ignoreCase = true) && family.second - family.first == 5
    }

    /** Returns null only for unrelated commands; malformed private input still stays private. */
    fun parse(line: String): Input? {
        if (!isSensitive(line)) return null
        if (line.length > 2048 || line.any(Char::isISOControl)) return Input(Action.USAGE)
        val root = word(line, 0)!!
        val family = word(line, root.second)!!
        val action = word(line, family.second) ?: return Input(Action.USAGE)
        val name = line.substring(action.first, action.second)
        if (name.equals("status", true) || name.equals("cancel", true)) {
            if (word(line, action.second) != null) return Input(Action.USAGE)
            return Input(if (name.equals("status", true)) Action.STATUS else Action.CANCEL)
        }
        if (!name.equals("authme", true)) return Input(Action.USAGE)
        val account = word(line, action.second) ?: return Input(Action.USAGE)
        val parsed = GameName.parse(line.substring(account.first, account.second)) ?: return Input(Action.USAGE)
        // Exactly one separator belongs to the syntax. Further leading/trailing spaces are password data.
        val start = account.second + 1
        if (start >= line.length || line.length - start > 1024) return Input(Action.USAGE)
        return Input(Action.AUTHME, parsed, line.toCharArray(start, line.length))
    }

    private fun word(line: String, offset: Int): Pair<Int, Int>? {
        var start = offset
        while (start < line.length && line[start].isWhitespace()) start++
        if (start == line.length) return null
        var end = start
        while (end < line.length && !line[end].isWhitespace()) end++
        return start to end
    }
}
