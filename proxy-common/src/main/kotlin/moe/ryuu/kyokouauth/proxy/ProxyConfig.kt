package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.config.YamlDocument
import moe.ryuu.kyokouauth.core.config.bool
import moe.ryuu.kyokouauth.core.config.int
import moe.ryuu.kyokouauth.core.config.long
import moe.ryuu.kyokouauth.core.config.string
import moe.ryuu.kyokouauth.core.storage.DatabaseDescriptor
import moe.ryuu.kyokouauth.core.storage.Dialect
import moe.ryuu.kyokouauth.limbo.LimboSettings
import java.io.File

/**
 * Everything an operator can change, read once at startup.
 *
 * A missing key is a default rather than a crash: an upgrade that adds a setting must not leave the
 * plugin refusing to start until someone hand-edits a file. A missing *file* is written out from the
 * jar, so the first start produces something to edit.
 */
class ProxyConfig(
    private val values: Map<String, String>,
    private val dataFolder: File,
) {

    val database: DatabaseDescriptor = run {
        val type = values.string("database.type", "sqlite")
        val dialect = Dialect.of(type)
            ?: error("Unknown database type '$type'. Valid: ${Dialect.entries.joinToString()}")

        DatabaseDescriptor(
            dialect = dialect,
            host = values.string("database.host", "127.0.0.1"),
            port = values.int("database.port", 0).takeIf { it > 0 } ?: defaultPort(dialect),
            database = resolvePath(dialect, values.string("database.database", "kyokouauth")),
            username = values.string("database.username", ""),
            password = values.string("database.password", ""),
        )
    }

    val poolSize: Int = values.int("database.pool-size", 4)

    /** Where a missing JDBC driver is fetched from. Also usable to point at a mirror. */
    val driverRepository: String = values.string("database.driver-repository", "https://repo1.maven.org/maven2")

    /** When false, a driver must already sit in [librariesFolder]. */
    val autoDownloadDriver: Boolean = values.bool("database.auto-download-driver", true)

    /**
     * Where fetched drivers are kept.
     *
     * The plugin's own folder, not the proxy's `libraries/`: a proxy has no such folder, and writing
     * into the proxy's installation directory is not this plugin's business.
     */
    val librariesFolder: File = File(dataFolder, "libraries")

    /** How long a verified login stays trusted. `0` disables session reuse. */
    val sessionHours: Long = values.long("session.hours", 12L)

    /**
     * Whether a session only counts from the address that earned it.
     *
     * On by default: a session that follows the player to a new address is one an attacker can
     * inherit. Turn it off when every player shares one address behind NAT or a tunnel, where the
     * check stops distinguishing anyone and only forces repeated verification.
     */
    val sessionBindsAddress: Boolean = values.bool("session.bind-address", true)

    /** How long the player has to finish the Microsoft sign-in before the code expires. */
    val verificationTimeoutSeconds: Int = values.int("verification.timeout-seconds", 300)

    /** How often the server asks Microsoft whether the player has finished. */
    val pollIntervalSeconds: Long = values.int("verification.poll-interval-seconds", 5).toLong()

    /** Opt-in: a freshly verified XUID may move its one existing name to the requested free name. */
    val autoRenameMicrosoft: Boolean = values.bool("verification.auto-rename", false)

    /**
     * How many login attempts one address may make per minute.
     *
     * Guards the Mojang lookup budget rather than the account: a client that reconnects in a loop can
     * spend the whole allowance, and the budget fails closed, which would lock everyone else out.
     * `0` disables the cap.
     */
    val connectionsPerMinute: Int = values.int("safety.connections-per-minute", 10)

    /**
     * How many Mojang name lookups may be sent per [directoryBudgetWindowMinutes].
     *
     * This is a local ceiling, not an upstream allowance: endpoint and shared-address limits may
     * differ. Cached answers cost nothing, while `0` disables only this local cap.
     */
    val directoryBudget: Int = values.int("premium.directory-limit", 600)

    val directoryBudgetWindowMinutes: Int = values.int("premium.directory-limit-window-minutes", 10)
    /** Isolated name-lookup routing; never changes Velocity's session or Microsoft requests. */
    val directoryNetwork: moe.ryuu.kyokouauth.core.http.Http.Route =
        when (values.string("premium.directory-network", "inherit").trim().lowercase(java.util.Locale.ROOT)) {
            "inherit" -> moe.ryuu.kyokouauth.core.http.Http.Route.INHERIT
            "direct" -> moe.ryuu.kyokouauth.core.http.Http.Route.DIRECT
            else -> error("premium.directory-network must be inherit or direct")
        }

    /** Local ceilings, not a claim about Mojang's external quota. Zero disables only this ceiling. */
    val sessionHandshakeLimit: Int = values.int("premium.session-handshakes-per-minute", 60).coerceAtLeast(0)
    val sessionRetrySeconds: Long = values.long("premium.session-retry-seconds", 5).coerceIn(1, 60)
    val sessionFailureBackoffSeconds: Long = values.long("premium.session-failure-backoff-seconds", 30).coerceIn(1, 3600)
    val sessionFailureMaxBackoffSeconds: Long = values.long("premium.session-failure-max-backoff-seconds", 120)
        .coerceIn(sessionFailureBackoffSeconds, 3600)
    val sessionHandshakeTimeoutSeconds: Long = values.long("premium.session-timeout-seconds", 30).coerceIn(5, 120)

    val locale: String = values.string("messages.locale", "zh_CN")

    /**
     * Where a player is sent once they may play. Empty means the proxy's own first choice.
     *
     * Read here so an operator can pin a lobby that is not first in `velocity.toml`'s attempt order.
     */
    val postLoginServer: String = values.string("post-login.server", "").trim()

    val limbo: LimboSettings = LimboSettings(
        motd = values.string("limbo.motd", "KyokouAuth"),
        viewDistance = values.int("limbo.view-distance", 2).coerceIn(2, 8),
        biomeId = values.int("limbo.biome-id", 41),
        biomeKey = values.string("limbo.biome", "").trim().takeIf { it.isNotEmpty() },
        spawn = LimboSettings.Spawn(
            x = values.string("limbo.spawn.x", "0.5").toDoubleOrNull() ?: 0.5,
            y = values.string("limbo.spawn.y", "64.0").toDoubleOrNull() ?: 64.0,
            z = values.string("limbo.spawn.z", "0.5").toDoubleOrNull() ?: 0.5,
            yaw = values.string("limbo.spawn.yaw", "0.0").toFloatOrNull() ?: 0f,
            pitch = values.string("limbo.spawn.pitch", "0.0").toFloatOrNull() ?: 0f,
        ),
        keepAliveSeconds = values.long("limbo.keep-alive-seconds", 10L).coerceAtLeast(1),
        keepAliveTimeoutSeconds = values.long("limbo.keep-alive-timeout-seconds", 30L).coerceAtLeast(5),
        joinTimeoutSeconds = values.long("limbo.join-timeout-seconds", 30L).coerceAtLeast(5),
    )

    private fun resolvePath(dialect: Dialect, raw: String): String {
        if (dialect != Dialect.SQLITE) return raw
        val file = File(raw)
        return if (file.isAbsolute) file.absolutePath else File(dataFolder, raw).absolutePath
    }

    private fun defaultPort(dialect: Dialect): Int = when (dialect) {
        Dialect.MYSQL, Dialect.MARIADB -> 3306
        Dialect.POSTGRESQL -> 5432
        Dialect.H2 -> 9092
        Dialect.SQLITE -> 0
    }

    companion object {

        /** Loads `config.yml`, writing the bundled copy first if there is none. */
        fun load(dataFolder: File): ProxyConfig {
            dataFolder.mkdirs()
            val file = File(dataFolder, "config.yml")

            if (!file.isFile) {
                ProxyConfig::class.java.classLoader.getResourceAsStream("config.yml")?.use { stream ->
                    runCatching { file.writeBytes(stream.readBytes()) }
                }
            }

            // A file that cannot be parsed is the operator's to fix, and the error names the file:
            // starting with silent defaults would hide a broken database address behind a kick nobody
            // can explain.
            return ProxyConfig(YamlDocument.load(file), dataFolder)
        }
    }
}