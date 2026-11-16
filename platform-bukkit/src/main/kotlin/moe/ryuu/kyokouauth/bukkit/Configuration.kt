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

    /** How long a verified login stays trusted. `0` disables session reuse. */
    val sessionHours: Long = root.getLong("session.hours", 12L)

    /**
     * Whether a session only counts from the address that earned it.
     *
     * On by default: a session that follows the player to a new address is one an attacker can
     * inherit. Turn it off when every player shares one address behind NAT or a tunnel, where the
     * check stops distinguishing anyone and only forces repeated verification.
     */
    val sessionBindsAddress: Boolean = root.getBoolean("session.bind-address", true)

    /** How long the player has to finish the Microsoft sign-in before the code expires. */
    val verificationTimeoutSeconds: Int = root.getInt("verification.timeout-seconds", 300)

    /** How often the server asks Microsoft whether the player has finished. */
    val pollIntervalSeconds: Int = root.getInt("verification.poll-interval-seconds", 5)

    val limbo: LimboSettings = LimboSettings(
        teleportEnabled = root.getBoolean("limbo.teleport-to-spawn", false),
        titleEnabled = root.getBoolean("limbo.show-title", true),
        bossBarEnabled = root.getBoolean("limbo.show-bossbar", true),
        blindfold = root.getBoolean("limbo.blind", false),
        hidePlayers = root.getBoolean("limbo.hide-players", true),
        spawn = root.getConfigurationSection("limbo.spawn")?.let { section ->
            section.getString("world")?.takeIf { it.isNotBlank() }?.let { world ->
                SpawnPoint(
                    world = world,
                    x = section.getDouble("x", 0.5),
                    y = section.getDouble("y", 64.0),
                    z = section.getDouble("z", 0.5),
                    yaw = section.getDouble("yaw", 0.0).toFloat(),
                    pitch = section.getDouble("pitch", 0.0).toFloat(),
                )
            }
        },
    )

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

    /**
     * How many login attempts one address may make per minute.
     *
     * Guards the Mojang lookup budget rather than the account: a client that reconnects in a loop
     * can spend the whole 600-per-ten-minutes allowance, and the budget fails closed, which locks
     * every other player out for the rest of the window. `0` disables the cap.
     */
    val connectionsPerMinute: Int = root.getInt("safety.connections-per-minute", 10)

    val locale: String = root.getString("messages.locale", "zh_CN") ?: "zh_CN"

    /**
     * Where a player is sent once they may play, or empty to leave them on this server.
     *
     * This is a proxy action: the plugin message it sends is read by BungeeCord or Velocity, and on
     * a standalone server nothing receives it. It therefore only does anything when the server is a
     * proxy backend, which is also the case where `safety.require-direct-connections` has to be off.
     */
    val postLoginServer: String = root.getString("post-login.server", "")?.trim() ?: ""

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
        val hidePlayers: Boolean,
        val spawn: SpawnPoint?,
    )

    /**
     * A fixed place a held player is put, written by `/kyokouauth setspawn`.
     *
     * The world is stored by name rather than as a reference: `config.yml` outlives any particular
     * `World` object, and a world may not be loaded yet when this is parsed. Resolution therefore
     * happens at use time, and a missing world falls back to the server's own spawn.
     */
    data class SpawnPoint(
        val world: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
    ) {
        fun toLocation(): org.bukkit.Location? =
            org.bukkit.Bukkit.getWorld(world)?.let { org.bukkit.Location(it, x, y, z, yaw, pitch) }
    }
}
