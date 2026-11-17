package moe.ryuu.kyokouauth.proxy

import java.util.Locale

/** Command roots only: arguments never participate in the pre-authentication restriction. */
object CommandNames {
    fun root(command: String): String = command.trimStart()
        .takeWhile { !it.isWhitespace() }
        .trimStart('/')
        .substringAfterLast(':')
        .trimStart('/')
        .lowercase(Locale.ROOT)
}
