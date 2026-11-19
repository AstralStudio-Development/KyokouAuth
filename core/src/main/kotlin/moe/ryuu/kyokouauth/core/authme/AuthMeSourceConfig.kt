package moe.ryuu.kyokouauth.core.authme

import moe.ryuu.kyokouauth.core.storage.Dialect
import java.nio.charset.Charset

/** A separate, SELECT-only legacy source, never a KyokouAuth storage descriptor. */
class AuthMeSourceConfig(
    val dialect: Dialect,
    val location: String,
    val username: String = "",
    val password: String = "",
    val table: String = "authme",
    val nameColumn: String = "username",
    val passwordColumn: String = "password",
    val totpColumn: String = "totp",
    val charsetName: String = "UTF-8",
) {
    internal val charset: Charset

    init {
        require(dialect == Dialect.SQLITE || dialect == Dialect.MYSQL || dialect == Dialect.MARIADB) {
            "Unsupported AuthMe source dialect"
        }
        require(location.length in 1..4096 && location.none { it.isISOControl() }) {
            "Invalid AuthMe source location"
        }
        require(listOf(table, nameColumn, passwordColumn, totpColumn).all { IDENTIFIER.matches(it) }) {
            "Invalid AuthMe source identifier"
        }
        require(username.length <= 256 && password.length <= 4096) { "Invalid AuthMe source credentials" }
        charset = try {
            require(charsetName.length in 1..64)
            Charset.forName(charsetName)
        } catch (_: IllegalArgumentException) {
            // Do not retain an exception whose message embeds untrusted configuration.
            throw IllegalArgumentException("Invalid AuthMe source charset")
        }
    }

    // The location may contain a password even when it is an invalid/rejected JDBC URL.
    override fun toString(): String = "AuthMeSourceConfig(dialect=$dialect, source=<redacted>)"

    companion object {
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    }
}

/** Only VERIFIED is usable as an input to a separate, short-lived, connection-bound old proof. */
enum class AuthMeVerification {
    VERIFIED,
    REJECTED,
    UNSUPPORTED,
    UNAVAILABLE,
}
