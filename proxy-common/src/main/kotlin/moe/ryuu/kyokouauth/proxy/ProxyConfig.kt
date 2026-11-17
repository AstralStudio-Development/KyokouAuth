package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.config.YamlDocument
import moe.ryuu.kyokouauth.core.http.Http
import moe.ryuu.kyokouauth.core.storage.DatabaseDescriptor
import moe.ryuu.kyokouauth.core.storage.Dialect
import moe.ryuu.kyokouauth.limbo.LimboSettings
import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/** Missing keys retain defaults; explicitly malformed settings never silently disable protection. */
class ProxyConfig(private val values: Map<String, String>, private val dataFolder: File) {
    val database: DatabaseDescriptor = run {
        val dialect = Dialect.of(text("database.type", "sqlite")) ?: invalid("database.type", "a supported database type")
        val port = integer("database.port", 0, 0..65535).takeUnless { it == 0 } ?: when (dialect) {
            Dialect.MYSQL, Dialect.MARIADB -> 3306
            Dialect.POSTGRESQL -> 5432
            Dialect.H2 -> 9092
            Dialect.SQLITE -> 0
        }
        val name = text("database.database", "kyokouauth")
        requireSetting(name.isNotBlank(), "database.database", "a non-empty database name or path")
        val path = if (dialect == Dialect.SQLITE && !File(name).isAbsolute) File(dataFolder, name).absolutePath else name
        DatabaseDescriptor(dialect = dialect, host = text("database.host", "127.0.0.1").also {
            requireSetting(dialect.embedded || it.isNotBlank(), "database.host", "a non-empty host")
        }, port = port, database = path, username = text("database.username", ""), password = text("database.password", ""))
    }
    val poolSize = integer("database.pool-size", 4, 1..64)
    val driverRepository = text("database.driver-repository", "https://repo1.maven.org/maven2").also {
        val uri = runCatching { URI(it) }.getOrNull()
        requireSetting(uri != null && uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
            !uri.host.isNullOrEmpty() && uri.userInfo == null && uri.query == null && uri.fragment == null,
            "database.driver-repository", "an HTTP(S) repository URL without credentials, query or fragment")
    }
    val autoDownloadDriver = boolean("database.auto-download-driver", true)
    val librariesFolder = File(dataFolder, "libraries")
    val sessionHours = long("session.hours", 12L, 0L..876_000L)
    val sessionBindsAddress = boolean("session.bind-address", true)
    val verificationTimeoutSeconds = integer("verification.timeout-seconds", 300, 1..Int.MAX_VALUE)
    val pollIntervalSeconds = long("verification.poll-interval-seconds", 5L, 1L..3600L)
    val autoRenameMicrosoft = boolean("verification.auto-rename", true)
    val blockedCommands: Set<String> = run {
        val key = "verification.blocked-commands"
        requireSetting(values.keys.none { it.startsWith("$key.") }, key, "a list of command roots")
        val raw = values[key] ?: return@run setOf("server")
        val entries = raw.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        requireSetting(entries.size <= 256, key, "at most 256 command roots")
        entries.map { entry ->
            val token = entry.trimStart('/')
            requireSetting(entry.length <= 128 && entry.none { it.isWhitespace() || it.isISOControl() || it in "{}[]" } &&
                ('*' !in token || token == "*"), key, "literal command roots without arguments, or *")
            val root = CommandNames.root(entry)
            requireSetting(root.isNotEmpty(), key, "non-empty command roots")
            root
        }.toSet()
    }
    val connectionsPerMinute = integer("safety.connections-per-minute", 10, 0..1_000_000)
    val pendingLoginLimit = integer("safety.pending-login-limit", 4096, 1..65536)
    val ioWorkers = integer("safety.io-workers", 4, 1..32)
    val ioQueueCapacity = integer("safety.io-queue-capacity", 256, 1..8192)
    val directoryBudget = integer("premium.directory-limit", 600, 0..1_000_000)
    val directoryBudgetWindowMinutes = integer("premium.directory-limit-window-minutes", 10, 1..1440)
    val directoryNetwork: Http.Route = when (text("premium.directory-network", "inherit").trim().lowercase(Locale.ROOT)) {
        "inherit" -> Http.Route.INHERIT
        "direct" -> Http.Route.DIRECT
        else -> invalid("premium.directory-network", "inherit or direct")
    }
    val sessionHandshakeLimit = integer("premium.session-handshakes-per-minute", 60, 0..1_000_000)
    val sessionRetrySeconds = long("premium.session-retry-seconds", 5L, 1L..60L)
    val sessionFailureBackoffSeconds = long("premium.session-failure-backoff-seconds", 30L, 1L..3600L)
    val sessionFailureMaxBackoffSeconds = long("premium.session-failure-max-backoff-seconds", 120L, sessionFailureBackoffSeconds..3600L)
    val sessionHandshakeTimeoutSeconds = long("premium.session-timeout-seconds", 30L, 5L..120L)
    val locale = text("messages.locale", "zh_CN").also {
        requireSetting(it.matches(Regex("[A-Za-z0-9_-]{1,32}")), "messages.locale", "a locale name without path separators")
    }
    val postLoginServer = text("post-login.server", "").trim().also {
        requireSetting(it.length <= 256 && it.none(Char::isISOControl), "post-login.server", "a valid server name")
    }
    val limbo = LimboSettings(
        motd = text("limbo.motd", "KyokouAuth"),
        viewDistance = integer("limbo.view-distance", 2, 2..8),
        biomeId = integer("limbo.biome-id", 41, 0..Int.MAX_VALUE),
        biomeKey = text("limbo.biome", "").trim().takeIf { it.isNotEmpty() }?.also {
            requireSetting(it.matches(Regex("[a-z0-9_.-]+:[a-z0-9_/.-]+")), "limbo.biome", "a namespaced resource key")
        },
        spawn = LimboSettings.Spawn(
            coordinate("limbo.spawn.x", 0.5, -28_999_999.0..28_999_999.0),
            coordinate("limbo.spawn.y", 64.0, -64.0..319.999999),
            coordinate("limbo.spawn.z", 0.5, -28_999_999.0..28_999_999.0),
            coordinate("limbo.spawn.yaw", 0.0, -360_000.0..360_000.0).toFloat(),
            coordinate("limbo.spawn.pitch", 0.0, -90.0..90.0).toFloat()),
        keepAliveSeconds = long("limbo.keep-alive-seconds", 10L, 1L..300L),
        keepAliveTimeoutSeconds = long("limbo.keep-alive-timeout-seconds", 30L, 1L..600L),
        joinTimeoutSeconds = long("limbo.join-timeout-seconds", 30L, 1L..600L),
    )

    private fun text(key: String, default: String) = values[key] ?: default
    private fun integer(key: String, default: Int, range: IntRange): Int {
        val raw = values[key] ?: return default
        val value = raw.trim().toIntOrNull() ?: invalid(key, "an integer in $range")
        requireSetting(value in range, key, "an integer in $range")
        return value
    }
    private fun long(key: String, default: Long, range: LongRange): Long {
        val raw = values[key] ?: return default
        val value = raw.trim().toLongOrNull() ?: invalid(key, "an integer in $range")
        requireSetting(value in range, key, "an integer in $range")
        return value
    }
    private fun boolean(key: String, default: Boolean): Boolean = when (values[key]?.trim()?.lowercase(Locale.ROOT)) {
        null -> default
        "true", "yes", "on" -> true
        "false", "no", "off" -> false
        else -> invalid(key, "true or false")
    }
    private fun coordinate(key: String, default: Double, range: ClosedFloatingPointRange<Double>): Double {
        val raw = values[key] ?: return default
        val value = raw.trim().toDoubleOrNull() ?: invalid(key, "a finite coordinate")
        requireSetting(value.isFinite() && value in range, key, "a finite coordinate within the supported world")
        return value
    }
    private fun requireSetting(valid: Boolean, key: String, expected: String) { if (!valid) invalid(key, expected) }
    private fun invalid(key: String, expected: String): Nothing = throw IllegalArgumentException("Invalid configuration '$key': expected $expected")

    companion object {
        fun load(dataFolder: File): ProxyConfig {
            check(dataFolder.isDirectory || dataFolder.mkdirs()) { "Cannot create the authentication data directory" }
            val file = File(dataFolder, "config.yml")
            if (!file.exists()) {
                val temporary = Files.createTempFile(dataFolder.toPath(), "config-", ".part")
                try {
                    val stream = ProxyConfig::class.java.classLoader.getResourceAsStream("config.yml")
                        ?: error("Missing bundled authentication configuration")
                    stream.use { input -> Files.newOutputStream(temporary).use(input::copyTo) }
                    try { Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE) }
                    catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, file.toPath()) }
                } finally { Files.deleteIfExists(temporary) }
            }
            check(file.isFile) { "Authentication configuration must be a regular file" }
            return ProxyConfig(YamlDocument.load(file), dataFolder)
        }
    }
}