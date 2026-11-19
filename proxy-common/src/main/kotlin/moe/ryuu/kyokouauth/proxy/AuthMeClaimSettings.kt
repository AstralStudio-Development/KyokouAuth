package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.authme.AuthMeSourceConfig
import moe.ryuu.kyokouauth.core.storage.Dialect
import java.io.File
import java.nio.file.Path
import java.util.Locale

/** A separate legacy source; no web listener, password store or general command-dispatch capability. */
class AuthMeClaimSettings(
    val source: AuthMeSourceConfig,
    val batch: String,
    val configFile: Path? = null,
    val serverFolder: Path? = null,
) {
    val automaticDiscovery: Boolean get() = configFile != null && serverFolder != null
    override fun toString() = "AuthMeClaimSettings[REDACTED]"

    companion object {
        fun parse(values: Map<String, String>, folder: File, migrationEnabled: Boolean): AuthMeClaimSettings? {
            val prefix = "migration.authme."
            fun value(key: String, default: String = "") = values[prefix + key] ?: default
            fun invalid(key: String): Nothing = throw IllegalArgumentException("Invalid configuration '${prefix + key}'")
            fun bool(key: String, default: Boolean = false) = when (value(key, default.toString()).trim().lowercase(Locale.ROOT)) {
                "true", "yes", "on" -> true
                "false", "no", "off" -> false
                else -> invalid(key)
            }
            // Disabling new migration never prevents existing profile owners from logging in.
            if (!migrationEnabled || !bool("enabled")) return null
            require(bool("source-frozen")) { "AuthMe private claims require an explicitly frozen/cutover source batch" }
            fun path(raw: String): Path {
                val file = File(raw)
                return (if (file.isAbsolute) file else File(folder, raw)).absoluteFile.normalize().toPath()
            }
            val explicitServerFolder = value("server-folder").takeIf { it.isNotBlank() }?.let(::path)
            val configFile = value("config-file").takeIf { it.isNotBlank() }?.let(::path)
                ?: explicitServerFolder?.resolve("plugins/AuthMe/config.yml")
            val serverFolder = explicitServerFolder ?: configFile?.takeIf {
                it.parent?.fileName?.toString().equals("AuthMe", true) &&
                    it.parent?.parent?.fileName?.toString().equals("plugins", true)
            }?.parent?.parent?.parent
            val rawBatch = value("batch").ifBlank { if (configFile != null) "authme" else "" }
            val batch = rawBatch.takeIf { it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl) }
                ?: invalid("batch")
            val overrideUser = value("username").takeIf { it.isNotBlank() }
            val autoSource = configFile?.let { moe.ryuu.kyokouauth.core.authme.AuthMeConfigReader.read(it,
                databaseUsername = overrideUser,
                databasePassword = if (overrideUser != null) value("password") else null) }
            require(autoSource == null || autoSource.dialect == Dialect.SQLITE || overrideUser != null) {
                "Remote AuthMe discovery requires explicit SELECT-only source username/password overrides"
            }
            val dialect = autoSource?.dialect ?: Dialect.of(value("type", "sqlite"))
                ?.takeIf { it in setOf(Dialect.SQLITE, Dialect.MYSQL, Dialect.MARIADB) } ?: invalid("type")
            val location = autoSource?.location ?: if (dialect == Dialect.SQLITE) {
                val raw = value("sqlite-file", "migration/authme.db")
                if (raw.isBlank()) invalid("sqlite-file")
                val file = File(raw)
                (if (file.isAbsolute) file else File(folder, raw)).absoluteFile.normalize().path
            } else value("jdbc-url").takeIf { it.isNotBlank() && it.startsWith(dialect.jdbcPrefix + "//") }
                ?: invalid("jdbc-url")
            require(autoSource != null || value("hash", "SHA256").equals("SHA256", true)) {
                "Only the AuthMe salted SHA256 format is supported"
            }
            val source = autoSource ?: AuthMeSourceConfig(dialect, location,
                value("username"), value("password"), value("table", "authme"), value("name-column", "username"),
                value("password-column", "password"), value("totp-column", "totp"), value("password-charset", "UTF-8"))
            return AuthMeClaimSettings(source, batch, configFile, serverFolder)
        }
    }
}
