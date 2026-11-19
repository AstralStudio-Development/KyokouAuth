package moe.ryuu.kyokouauth.core.authme

import moe.ryuu.kyokouauth.core.storage.Dialect
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Imports only a legacy source descriptor, never modifies the configuration or opens its database. */
object AuthMeConfigReader {
    fun read(
        configFile: Path,
        databaseUsername: String? = null,
        databasePassword: String? = null,
    ): AuthMeSourceConfig {
        try {
            require(Files.isRegularFile(configFile, NOFOLLOW_LINKS))
            val options = LoaderOptions().apply {
                isAllowDuplicateKeys = false
                maxAliasesForCollections = 0
                nestingDepthLimit = 20
                codePointLimit = MAX_CONFIG_BYTES
            }
            val bytes = Files.newInputStream(configFile, NOFOLLOW_LINKS).use { it.readNBytes(MAX_CONFIG_BYTES + 1) }
            require(bytes.size <= MAX_CONFIG_BYTES)
            val root = Yaml(SafeConstructor(options)).load<Any?>(bytes.toString(Charsets.UTF_8)) as? Map<*, *>
                ?: error("Invalid configuration")
            val source = root.section("DataSource")
            val security = root.section("settings").section("security")
            val backend = source["backend"] as? String ?: error("Missing backend")
            val dialect = when (backend.uppercase(java.util.Locale.ROOT)) {
                "SQLITE" -> Dialect.SQLITE
                "MYSQL" -> Dialect.MYSQL
                "MARIADB" -> Dialect.MARIADB
                else -> error("Unsupported backend")
            }
            require(security.string("passwordHash", "SHA256") == "SHA256")
            val database = source.string("mySQLDatabase", "authme")
            require(DATABASE_NAME.matches(database))
            val location = if (dialect == Dialect.SQLITE) {
                configFile.toAbsolutePath().normalize().parent.resolve("$database.db").toString()
            } else {
                val host = source.string("mySQLHost", "127.0.0.1")
                val port = when (val value = if (source.containsKey("mySQLPort")) source["mySQLPort"] else 3306) {
                    is Int -> value
                    is String -> value.toInt()
                    else -> error("Invalid port")
                }
                require(host.length in 1..253 && port in 1..65535)
                val protocol = if (dialect == Dialect.MYSQL) "mysql" else "mariadb"
                val endpoint = URI(protocol, null, host, port, "/$database", null, null)
                require(endpoint.host != null && endpoint.rawUserInfo == null && endpoint.rawQuery == null)
                val tls = if (dialect == Dialect.MYSQL) "VERIFY_IDENTITY" else "verify-full"
                "jdbc:$endpoint?sslMode=$tls&connectTimeout=5000&socketTimeout=5000&allowMultiQueries=false&autoReconnect=false"
            }
            return AuthMeSourceConfig(
                dialect = dialect,
                location = location,
                username = databaseUsername ?: source.string("mySQLUsername", "authme"),
                password = databasePassword ?: source.string("mySQLPassword", "12345"),
                table = source.string("mySQLTablename", "authme"),
                nameColumn = source.string("mySQLColumnName", "username"),
                passwordColumn = source.string("mySQLColumnPassword", "password"),
                totpColumn = source.string("mySQLtotpKey", "totp"),
                charsetName = security.string("passwordCharset", "UTF-8"),
            )
        } catch (_: Exception) {
            // YAML marks, file paths and source values may contain secrets. Do not retain a cause.
            throw IllegalArgumentException("Invalid or unsupported AuthMe configuration")
        }
    }

    private fun Map<*, *>.section(key: String): Map<*, *> = when (val value = this[key]) {
        null -> emptyMap<Any, Any>()
        is Map<*, *> -> value
        else -> error("Invalid section")
    }

    private fun Map<*, *>.string(key: String, default: String): String = when {
        !containsKey(key) -> default
        this[key] is String -> this[key] as String
        else -> error("Invalid scalar")
    }

    private const val MAX_CONFIG_BYTES = 1_048_576
    private val DATABASE_NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_-]{0,63}")
}
