package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.config.YamlDefaults
import moe.ryuu.kyokouauth.core.config.YamlDocument
import moe.ryuu.kyokouauth.core.http.Http
import moe.ryuu.kyokouauth.core.storage.DatabaseDescriptor
import moe.ryuu.kyokouauth.core.storage.Dialect
import moe.ryuu.kyokouauth.core.text.RichText
import moe.ryuu.kyokouauth.limbo.LimboSettings
import moe.ryuu.kyokouauth.limbo.Schematic
import java.io.File
import java.net.URI
import java.nio.file.Files
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
    val bedrockEnabled = boolean("bedrock.enabled", false)
    val rolesEnabled = boolean("role-selection.enabled", false)
    private val configuredProfileClaims = boolean("migration.enabled", false)
    @Volatile private var snapshotProfileClaims: Boolean? = null
    val profileClaimsEnabled: Boolean get() = snapshotProfileClaims ?: configuredProfileClaims
    internal fun snapshotClaimsEnabled(enabled: Boolean) { snapshotProfileClaims = enabled }
    val migrationFolder = File(dataFolder, "migration").toPath().toAbsolutePath().normalize()
    val authMeClaims = AuthMeClaimSettings.parse(values, dataFolder, configuredProfileClaims)
    val roleSelectionTimeoutSeconds = long("role-selection.timeout-seconds", 60L, 5L..600L)
    // The old TTL key remains a migration alias; it no longer enables a transfer or cookie flow.
    val roleHintTtlSeconds = if ("role-selection.hint-ttl-seconds" in values)
        long("role-selection.hint-ttl-seconds", 30L, 5L..120L)
    else long("role-selection.transfer-ttl-seconds", 30L, 5L..120L)
    internal val hasRetiredRoleSettings = values.keys.any {
        it.startsWith("role-selection.avatars.") || it == "role-selection.reconnect-host" ||
            it == "role-selection.reconnect-port"
    }
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
        requireSetting(VALID_LOCALE.matches(it), "messages.locale", "a locale name without path separators")
    }
    val postLoginServer = text("post-login.server", "").trim().also {
        requireSetting(it.length <= 256 && it.none(Char::isISOControl), "post-login.server", "a valid server name")
    }
    val limbo = LimboSettings(
        sidebar = sidebar(),
        motd = text("limbo.motd", "KyokouAuth"),
        viewDistance = integer("limbo.view-distance", 2, 2..8),
        biomeId = integer("limbo.biome-id", 41, 0..Int.MAX_VALUE),
        biomeKey = text("limbo.biome", "").trim().takeIf { it.isNotEmpty() }?.also {
            requireSetting(VALID_BIOME_KEY.matches(it), "limbo.biome", "a namespaced resource key")
        },
        spawn = LimboSettings.Spawn(
            coordinate("limbo.spawn.x", 0.5, -28_999_999.0..28_999_999.0),
            coordinate("limbo.spawn.y", 64.0, -64.0..319.999999),
            coordinate("limbo.spawn.z", 0.5, -28_999_999.0..28_999_999.0),
            coordinate("limbo.spawn.yaw", 0.0, -360_000.0..360_000.0).toFloat(),
            coordinate("limbo.spawn.pitch", 0.0, -90.0..90.0).toFloat()),
        map = loadLimboMap(),
        motdComponent = moe.ryuu.kyokouauth.core.text.RichText.parse(text("limbo.motd", "KyokouAuth")).toLimbo(),
        keepAliveSeconds = long("limbo.keep-alive-seconds", 10L, 1L..300L),
        keepAliveTimeoutSeconds = long("limbo.keep-alive-timeout-seconds", 30L, 1L..600L),
        joinTimeoutSeconds = long("limbo.join-timeout-seconds", 30L, 1L..600L),
    )

    private fun sidebar(): LimboSettings.Sidebar? {
        val prefix = "limbo.sidebar"
        if (!boolean("$prefix.enabled", false)) return null

        fun component(key: String, raw: String): RichText.Parsed {
            requireSetting(raw.length <= 512, key, "at most 512 characters")
            return RichText.parse(raw).also { parsed ->
                requireSetting(parsed.spans.none { span -> span.text.any(Char::isISOControl) }, key, "single-line text")
            }
        }

        val title = component("$prefix.title", text("$prefix.title", "KyokouAuth"))
        requireSetting(title.plainText.isNotBlank(), "$prefix.title", "a non-empty title")
        val key = "$prefix.lines"
        requireSetting(values.keys.none { it.startsWith("$key.") }, key, "a list of text lines")
        val maxLines = LimboSettings.Sidebar.MAX_LINES
        val lines = text(key, "").takeIf { it.isNotEmpty() }?.split('\n', limit = maxLines + 1).orEmpty()
        requireSetting(lines.size <= maxLines, key, "at most $maxLines lines")
        return LimboSettings.Sidebar(title.toLimbo(), lines.map { component(key, it).toLimbo() })
    }

    private fun loadLimboMap(): LimboSettings.MapLayout? {
        val key = "limbo.map.file"
        val file = text(key, "").trim()
        if (file.isEmpty()) return null
        requireSetting(Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}\\.schem", RegexOption.IGNORE_CASE).matches(file), key,
            "a .schem filename in the plugin data directory")
        val path = dataFolder.toPath().resolve(file)
        val noFollow = java.nio.file.LinkOption.NOFOLLOW_LINKS
        requireSetting(Files.isRegularFile(path, noFollow) && Files.size(path) <= 16L * 1024 * 1024, key,
            "a regular .schem file no larger than 16 MiB")
        val schematic = try { Files.newInputStream(path, noFollow).use(Schematic::read) }
        catch (failure: Exception) { throw IllegalArgumentException("Invalid configuration '$key': cannot read Sponge schematic v2/v3", failure) }
        return LimboSettings.MapLayout(schematic, integer("limbo.map.x", 0, -28_999_999..28_999_999),
            integer("limbo.map.y", 63, -64..319), integer("limbo.map.z", 0, -28_999_999..28_999_999),
            boolean("limbo.map.unknown-blocks-as-air", false))
    }

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
        private val VALID_LOCALE = Regex("[A-Za-z0-9_-]{1,32}")
        private val VALID_BIOME_KEY = Regex("[a-z0-9_.-]+:[a-z0-9_/.-]+")

        fun load(dataFolder: File): ProxyConfig {
            check(dataFolder.isDirectory || dataFolder.mkdirs()) { "Cannot create the authentication data directory" }
            val file = File(dataFolder, "config.yml")
            val locale = Locale.getDefault()
            val simplifiedChinese = locale.language == "zh" && when (locale.script) {
                "Hans" -> true
                "" -> locale.country !in setOf("TW", "HK", "MO")
                else -> false
            }
            // Locale affects only the first file's comments, never an existing file or the default values.
            val template = if (!file.exists() && simplifiedChinese) "config.zh_CN.yml" else "config.yml"
            val defaults = ProxyConfig::class.java.classLoader.getResourceAsStream(template)?.use { it.readBytes() }
                ?: error("Missing bundled authentication configuration: $template")
            YamlDefaults.sync(file.toPath(), defaults, mapOf(
                // Backfilling these canonical keys with the template default would override a legacy setting.
                "limbo.biome" to "limbo.biome-id",
                "role-selection.hint-ttl-seconds" to "role-selection.transfer-ttl-seconds",
            ))
            check(file.isFile) { "Authentication configuration must be a regular file" }
            return ProxyConfig(YamlDocument.load(file), dataFolder)
        }
    }
}