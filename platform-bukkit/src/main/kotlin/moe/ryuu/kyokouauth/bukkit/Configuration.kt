package moe.ryuu.kyokouauth.bukkit

import moe.ryuu.kyokouauth.core.auth.MojangSession
import moe.ryuu.kyokouauth.core.storage.DatabaseDescriptor
import moe.ryuu.kyokouauth.core.storage.Dialect
import org.bukkit.configuration.file.FileConfiguration
import java.io.File

/**
 * Everything an operator can change, read once at startup.
 *
 * The shape mirrors `config.yml` deliberately: a flat read means a typo in one section cannot
 * silently disable authentication elsewhere.
 */
class Configuration(
    private val root: FileConfiguration,
    private val dataFolder: File,
    /**
     * The server's root folder, taken from `Server#getWorldContainer`.
     *
     * Walking up from `dataFolder` looked equivalent and is not: on this runtime the parent chain
     * came back null and the libraries path silently fell into the plugin's own folder. The server
     * already knows where its root is, so it is handed over rather than rederived.
     */
    serverRoot: File,
) {

    val database: DatabaseDescriptor = run {
        val section = root.getConfigurationSection("database")
            ?: error("config.yml is missing the `database` section")
        val dialect = Dialect.of(section.getString("type"))
            ?: error("Unknown database type '${section.getString("type")}'. Valid: ${Dialect.entries.joinToString()}")
        DatabaseDescriptor(
            dialect = dialect,
            host = section.getString("host") ?: "127.0.0.1",
            port = section.getInt("port", 0).takeIf { it > 0 } ?: defaultPort(dialect),
            database = resolvePath(dialect, section.getString("database") ?: "kyokouauth"),
            username = section.getString("username") ?: "",
            password = section.getString("password") ?: "",
        )
    }

    val poolSize: Int = root.getInt("database.pool-size", 4)

    /** Where a missing JDBC driver is fetched from. Also usable to point at a mirror. */
    val driverRepository: String =
        root.getString("database.driver-repository", "https://repo1.maven.org/maven2")
            ?: "https://repo1.maven.org/maven2"

    /** When false, a driver must already sit in [librariesFolder]. */
    val autoDownloadDriver: Boolean = root.getBoolean("database.auto-download-driver", true)

    /**
     * The server's own `libraries/` folder - the same place Paper puts its dependencies, in the same
     * Maven layout.
     */
    val librariesFolder: File = File(serverRoot.absoluteFile, "libraries").normalize()

    /** How long a verified login stays trusted on the same address. `0` disables session reuse. */
    val sessionHours: Long = root.getLong("session.hours", 12L)

    /** How long the player has to finish the Microsoft sign-in before the code expires. */
    val verificationTimeoutSeconds: Int = root.getInt("verification.timeout-seconds", 300)

    /** How often the server asks Microsoft whether the player has finished. */
    val pollIntervalSeconds: Int = root.getInt("verification.poll-interval-seconds", 5)

    val limbo: LimboSettings = LimboSettings(
        teleportEnabled = root.getBoolean("limbo.teleport-to-spawn", false),
        titleEnabled = root.getBoolean("limbo.show-title", true),
        bossBarEnabled = root.getBoolean("limbo.show-bossbar", true),
        blindfold = root.getBoolean("limbo.blind", false),
    )

    /** A player who is already bound may not be renamed by the client. Re-grant the right name. */
    val enforceBoundName: Boolean = root.getBoolean("enforce-bound-name", true)

    /**
     * Where the handshake asks Mojang to confirm a session.
     *
     * Only the player name and a hash ever travel in this request, so pointing it at a mirror is not
     * a credential exposure - but it *is* what decides whether a login is accepted, so a wrong value
     * is as dangerous as a wrong database address.
     */
    val sessionServerUrl: String =
        root.getString("premium.session-server-url", MojangSession.DEFAULT_SESSION_SERVER)
            ?: MojangSession.DEFAULT_SESSION_SERVER

    /**
     * How many Mojang name lookups may be sent per [directoryBudgetWindowMinutes].
     *
     * Mojang allows roughly 600 per ten minutes per address. Staying at or under that means we stop
     * before the wall instead of reacting to a 429 that has already been spent. `0` disables the cap.
     */
    val directoryBudget: Int = root.getInt("premium.directory-limit", 600)

    val directoryBudgetWindowMinutes: Int = root.getInt("premium.directory-limit-window-minutes", 10)

    /** Refuse to start when the server is behind a proxy, because the handshake cannot work there. */
    val requireDirectConnections: Boolean = root.getBoolean("safety.require-direct-connections", true)

    val locale: String = root.getString("messages.locale", "zh_CN") ?: "zh_CN"

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

    data class LimboSettings(
        val teleportEnabled: Boolean,
        val titleEnabled: Boolean,
        val bossBarEnabled: Boolean,
        val blindfold: Boolean,
    )
}
