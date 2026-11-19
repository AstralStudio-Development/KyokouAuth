package moe.ryuu.kyokouauth.core.authme

import moe.ryuu.kyokouauth.core.storage.Dialect
import java.net.URI
import java.nio.file.Path
import java.util.Locale

/**
 * Selects only the configured source; host classification never performs DNS or a connection.
 * A DNS alias remains unchanged for TLS and does not prove which physical machine it addresses.
 */
class AuthMeSourceSelection(
    val host: String? = null,
    val port: Int? = null,
    val localSourceConfirmed: Boolean = false,
) {
    fun select(source: AuthMeSourceConfig): AuthMeSelectedSource {
        try {
            if (source.dialect == Dialect.SQLITE) {
                require(host == null && port == null && !localSourceConfirmed)
                val path = Path.of(source.location)
                require(path.isAbsolute)
                val filename = path.fileName?.toString()
                require(!filename.isNullOrEmpty())
                return AuthMeSelectedSource(source, AuthMeSourceSummary(Dialect.SQLITE, null, null, filename))
            }
            require(source.dialect == Dialect.MYSQL || source.dialect == Dialect.MARIADB)
            port?.let { require(it in 1..65535) }
            val scheme = if (source.dialect == Dialect.MYSQL) "mysql" else "mariadb"
            require(source.location.startsWith("jdbc:$scheme://"))
            val uri = URI(source.location.removePrefix("jdbc:"))
            require(uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null)
            require(DATABASE_PATH.matches(uri.rawPath.orEmpty()))
            val query = uri.rawQuery ?: error("Missing TLS policy")
            checkOptions(source.dialect, query)
            val authority = uri.rawAuthority ?: error("Missing host")
            val sourceHost: String
            val portSuffix: String
            if (authority.startsWith("[")) {
                val end = authority.indexOf(']')
                require(end > 1)
                sourceHost = authority.substring(1, end)
                portSuffix = authority.substring(end + 1)
            } else {
                sourceHost = authority.substringBefore(':')
                portSuffix = authority.substring(sourceHost.length)
            }
            val configuredPort = if (portSuffix.isEmpty()) 3306 else {
                require(PORT_SUFFIX.matches(portSuffix))
                portSuffix.substring(1).toInt().also { require(it in 1..65535) }
            }
            val configuredHost = checkHost(sourceHost)
            val selectedHost = host?.let(::checkHost) ?: configuredHost
            val selectedPort = port ?: configuredPort
            if (selectedHost.local && !localSourceConfirmed) throw AuthMeLocalSourceConfirmationRequired()
            val selected = if (host == null && port == null) source else {
                val renderedHost = if (':' in selectedHost.value) "[${selectedHost.value}]" else selectedHost.value
                val renderedPort = port?.let { ":$it" } ?: portSuffix
                AuthMeSourceConfig(
                    dialect = source.dialect,
                    location = "jdbc:$scheme://$renderedHost$renderedPort${uri.rawPath}?$query",
                    username = source.username,
                    password = source.password,
                    table = source.table,
                    nameColumn = source.nameColumn,
                    passwordColumn = source.passwordColumn,
                    totpColumn = source.totpColumn,
                    charsetName = source.charsetName,
                )
            }
            return AuthMeSelectedSource(selected, AuthMeSourceSummary(
                source.dialect, selectedHost.value, selectedPort, uri.rawPath.substring(1),
            ))
        } catch (error: AuthMeLocalSourceConfirmationRequired) {
            throw error
        } catch (_: Exception) {
            // URI/parser errors may include complete URLs or attacker-supplied credentials.
            throw IllegalArgumentException("Invalid AuthMe source selection")
        }
    }

    override fun toString(): String = "AuthMeSourceSelection(overrides=<redacted>)"

    private fun checkOptions(dialect: Dialect, query: String) {
        val options = HashMap<String, String>()
        for (pair in query.split('&')) {
            require(URL_OPTION.matches(pair))
            val key = pair.substringBefore('=')
            require(key in URL_OPTIONS)
            require(options.put(key, pair.substringAfter('=')) == null)
        }
        require(options["sslMode"] == if (dialect == Dialect.MYSQL) "VERIFY_IDENTITY" else "verify-full")
        for (key in listOf("connectTimeout", "socketTimeout")) {
            options[key]?.let { require(it.toIntOrNull()?.let { timeout -> timeout in 1..10_000 } == true) }
        }
        for (key in listOf("allowMultiQueries", "autoReconnect")) {
            options[key]?.let { require(it == "false") }
        }
    }

    private fun checkHost(input: String): Host {
        require(input.length in 1..253 && input.none { it.isISOControl() || it.isWhitespace() })
        val value = if (input.startsWith("[")) {
            require(input.endsWith("]"))
            input.substring(1, input.lastIndex)
        } else input
        if (':' in value) {
            val words = ipv6(value)
            require(words.any { it != 0 })
            val mapped = words.take(5).all { it == 0 } && words[5] == 0xffff
            require(!mapped || words[6] != 0 || words[7] != 0)
            val loopback = words.take(7).all { it == 0 } && words[7] == 1
            return Host(value, loopback || (mapped && (words[6] ushr 8) == 127))
        }
        require(value == input && '[' !in input && ']' !in input)
        val dns = input.removeSuffix(".")
        val labels = dns.split('.')
        require(labels.all { DNS_LABEL.matches(it) })
        if (labels.all { NUMERIC_LABEL.matches(it) }) {
            require(input == dns)
            val ipv4 = ipv4(dns)
            require(ipv4.any { it != 0 })
            return Host(input, ipv4[0] == 127)
        }
        val normalized = dns.lowercase(Locale.ROOT)
        return Host(input, normalized == "localhost" || normalized.endsWith(".localhost"))
    }

    private fun ipv4(value: String): List<Int> {
        val parts = value.split('.')
        require(parts.size == 4)
        return parts.map { part ->
            require(DECIMAL_OCTET.matches(part))
            part.toInt().also { require(it in 0..255) }
        }
    }

    /** Parses literal digits only; never delegates a hostname to InetAddress or DNS. */
    private fun ipv6(input: String): List<Int> {
        require(IPV6_DIGITS.matches(input) && ':' in input && ":::" !in input)
        var value = input
        if ('.' in value) {
            val lastColon = value.lastIndexOf(':')
            val embedded = ipv4(value.substring(lastColon + 1))
            val high = (embedded[0] shl 8) or embedded[1]
            val low = (embedded[2] shl 8) or embedded[3]
            value = value.substring(0, lastColon + 1) + high.toString(16) + ":" + low.toString(16)
        }
        val compression = value.indexOf("::")
        val parts = if (compression < 0) {
            value.split(':').also { require(it.size == 8) }
        } else {
            require(compression == value.lastIndexOf("::"))
            val left = value.substring(0, compression).let { if (it.isEmpty()) emptyList() else it.split(':') }
            val right = value.substring(compression + 2).let { if (it.isEmpty()) emptyList() else it.split(':') }
            require(left.size + right.size < 8)
            left + List(8 - left.size - right.size) { "0" } + right
        }
        return parts.map {
            require(IPV6_WORD.matches(it))
            it.toInt(16)
        }
    }

    private data class Host(val value: String, val local: Boolean)

    companion object {
        private val DATABASE_PATH = Regex("/[A-Za-z0-9_][A-Za-z0-9_-]{0,63}")
        private val PORT_SUFFIX = Regex(":[0-9]{1,5}")
        private val DNS_LABEL = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
        private val NUMERIC_LABEL = Regex("(?:[0-9]+|0[xX][0-9a-fA-F]+)")
        private val DECIMAL_OCTET = Regex("(?:0|[1-9][0-9]{0,2})")
        private val IPV6_DIGITS = Regex("[0-9a-fA-F:.]+")
        private val IPV6_WORD = Regex("[0-9a-fA-F]{1,4}")
        private val URL_OPTION = Regex("[A-Za-z][A-Za-z0-9]*=[A-Za-z0-9_-]+")
        private val URL_OPTIONS = setOf("sslMode", "connectTimeout", "socketTimeout", "allowMultiQueries", "autoReconnect")
    }
}

class AuthMeSelectedSource(val source: AuthMeSourceConfig, val summary: AuthMeSourceSummary)

data class AuthMeSourceSummary(val dialect: Dialect, val host: String?, val port: Int?, val database: String)

class AuthMeLocalSourceConfirmationRequired : IllegalArgumentException("AuthMe local source requires explicit confirmation")
