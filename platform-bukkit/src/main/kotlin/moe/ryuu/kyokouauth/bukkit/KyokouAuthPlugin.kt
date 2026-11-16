package moe.ryuu.kyokouauth.bukkit

import moe.ryuu.kyokouauth.bukkit.command.KyokouAuthCommand
import moe.ryuu.kyokouauth.bukkit.floodgate.FloodgateBridge
import moe.ryuu.kyokouauth.bukkit.floodgate.LinkExtension
import moe.ryuu.kyokouauth.bukkit.listener.LoginListener
import moe.ryuu.kyokouauth.bukkit.listener.LimboChatListener
import moe.ryuu.kyokouauth.bukkit.listener.LimboListener
import moe.ryuu.kyokouauth.bukkit.limbo.LimboManager
import moe.ryuu.kyokouauth.bukkit.verification.VerificationService
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService

class KyokouAuthPlugin : JavaPlugin() {

    /**
     * The schema version this build expects, read from the bundled `config.yml`.
     *
     * A constant here would be a second copy of the same number with nothing to keep the two in
     * step - and a stale one is silent: the operator's old file simply never gets migrated, so new
     * options exist in code and not on disk.
     */
    private val CONFIG_VERSION: Int by lazy {
        javaClass.classLoader.getResourceAsStream("config.yml")?.use { stream ->
            org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(InputStreamReader(stream, StandardCharsets.UTF_8))
                .getInt("config-version", 0)
        } ?: 0
    }

    lateinit var configuration: Configuration
        private set
    lateinit var messages: Messages
        private set

    /**
     * Replaces a config.yml written by an older version of the plugin.
     *
     * `saveDefaultConfig` never overwrites, so without this an existing server would keep the old
     * file forever - including its old comments, which is exactly how an operator ends up reading
     * documentation for settings that no longer exist.
     *
     * The old file is kept as `config.old.yml` rather than merged: a half-merged config is worse
     * than a clearly-backed-up one, and there are few enough settings to re-apply by hand.
     */
    /**
     * Brings an outdated `config.yml` up to the current schema without losing the operator's work.
     *
     * The template is the base and the operator's values are written into it, so comments stay and
     * a removed option disappears. No `.old` file is produced: nothing is discarded that the merge
     * did not already carry, and a second copy of a config is one more thing to clean up later.
     */
    private fun migrateConfigIfOutdated() {
        val file = File(dataFolder, "config.yml")
        if (!file.isFile) return

        val existing = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file)
        val version = existing.getInt("config-version", 0)
        if (version >= CONFIG_VERSION) return

        val template = javaClass.classLoader.getResourceAsStream("config.yml")?.use {
            String(it.readBytes(), StandardCharsets.UTF_8)
        } ?: return

        val merged = ConfigMerger.merge(template, existing)

        runCatching { file.writeText(merged, StandardCharsets.UTF_8) }
            .onSuccess {
                logger.info("config.yml updated from v$version to v$CONFIG_VERSION; values kept, comments refreshed")
            }
            .onFailure {
                logger.warning("Could not rewrite config.yml (${it.message}); the file is unchanged")
            }
    }


    lateinit var messenger: moe.ryuu.kyokouauth.bukkit.message.Messenger
        private set
    lateinit var core: KyokouAuthCore
        private set
    lateinit var limbo: LimboManager
        private set
    lateinit var verification: VerificationService
        private set

    lateinit var skins: moe.ryuu.kyokouauth.bukkit.skin.SkinService
        private set

    /**
     * The login-phase handshake, or null when a proxy owns the login.
     *
     * Null on a proxy backend rather than installed-and-idle: the handler answers a premium name by
     * sending an Encryption Request, and a proxy that sees one concludes the backend is in
     * online-mode and drops the connection with `Backend server is online-mode!`. The proxy has
     * already done whatever authentication is going to happen; this side must not start a second
     * one on the same connection.
     */
    var injector: moe.ryuu.kyokouauth.bukkit.inject.LoginInjector? = null
        private set

    lateinit var io: ExecutorService
        private set

    /**
     * Whether this server sits behind a proxy.
     *
     * Read once at startup: a proxy does not appear at runtime, and the answer is needed from a
     * thread that must not touch the filesystem.
     */
    private var behindProxy = false

    /**
     * Caps login attempts per address.
     *
     * Rebuilt on reload because the allowance is a configuration value; the counters it held are
     * dropped with it, which is the right direction for a limiter whose window is a minute.
     */
    lateinit var connectionLimiter: moe.ryuu.kyokouauth.core.auth.ConnectionLimiter
        private set

    /** Non-null only when Floodgate is installed and reachable. */
    var floodgate: moe.ryuu.kyokouauth.bukkit.floodgate.FloodgateBridge? = null
        private set

    /**
     * Registers a listener only when the event class it uses actually exists on this server.
     *
     * A failed registration aborts the whole plugin on some versions, which would turn one missing
     * off-hand event into "nobody can log in". Probing first keeps every restriction that *can* work.
     */
    private fun registerIfAvailable(className: String, description: String, register: () -> Unit) {
        val present = runCatching { Class.forName(className) }.isSuccess
        if (present) {
            runCatching(register).onFailure {
                consoleWarn("console.restriction-failed", "name" to description, "error" to (it.message ?: "?"))
            }
        } else {
            console("console.restriction-skipped", "name" to description)
        }
    }

    /** Sends a catalogue message to a command sender. */
    /**
     * Sends a catalogue message, in the recipient's own client language when a file exists for it.
     *
     * One method rather than a `CommandSender` overload plus a `Player` overload. Kotlin resolves
     * overloads by *static* type, and every command handler holds a `CommandSender` - so the player
     * overload was never selected and every command answered in the server's language regardless of
     * what the client had set. The locale is read here, where the runtime type is available.
     */
    fun tell(sender: org.bukkit.command.CommandSender, key: String, vararg placeholders: Pair<String, String>) {
        val locale = (sender as? org.bukkit.entity.Player)?.locale()?.toString()
        messenger.send(sender, messages.parsed(key, locale, *placeholders))
    }

    /**
     * A catalogue message as the `§`-coded String the login phase takes.
     *
     * `AsyncPlayerPreLoginEvent#disallow` and the Disconnect packet both take a plain String, and
     * the injected handshake handler sits on the Netty pipeline where a Component has no meaning.
     */
    fun kickText(key: String, vararg placeholders: Pair<String, String>): String =
        messenger.legacy(messages.parsed(key, *placeholders))

    /** Disconnects a player with a catalogue message, in their own client language when available. */
    fun kick(player: org.bukkit.entity.Player, key: String, vararg placeholders: Pair<String, String>) {
        player.kick(messenger.component(messages.parsed(key, player.locale().toString(), *placeholders)))
    }

    /**
     * Decides which UUID a connection is admitted under.
     *
     * A separate object because the Paper and legacy behaviours reference different Bukkit classes,
     * and the legacy server must not have to load the Paper one at all.
     */
    lateinit var profiles: moe.ryuu.kyokouauth.bukkit.profile.ProfileApplier
        private set

    /**
     * Re-reads config.yml and the message files. Storage is not re-opened: that needs a restart.
     */
    fun reloadSettings() {
        reloadConfig()
        configuration = Configuration(config, dataFolder, server.worldContainer)
        messages.load()
        connectionLimiter = moe.ryuu.kyokouauth.core.auth.ConnectionLimiter(configuration.connectionsPerMinute)
    }

    /**
     * Sends a player to another server once they are allowed to play.
     *
     * A proxy action, and a no-op when no target is configured. Failure is logged rather than
     * thrown: the player is already admitted and standing on this server, so a missing proxy
     * connection costs them a manual switch, not their session.
     */
    fun postLoginConnect(player: org.bukkit.entity.Player) {
        val target = configuration.postLoginServer
        if (target.isEmpty()) return

        try {
            val payload = java.io.ByteArrayOutputStream()
            java.io.DataOutputStream(payload).use {
                it.writeUTF("Connect")
                it.writeUTF(target)
            }
            player.sendPluginMessage(this, "BungeeCord", payload.toByteArray())
        } catch (failure: Throwable) {
            logger.warning("Could not send ${player.name} to '$target': ${failure.message}")
        }
    }

    /**
     * Asks the proxy to disconnect a player.
     *
     * The only lever a backend has over a connection it does not own. `/server` and the `Connect`
     * plugin message - the two ways a player can leave this server without permission - are both
     * answered by the proxy and never reach Bukkit, so neither can be cancelled here. What does
     * happen here is the departure itself: this server sees the player quit, and the proxy can
     * still be told to drop the whole connection before the new server is usable.
     *
     * The result is "you cannot go", not "the command did nothing". Refusing the command outright
     * requires the proxy to be the one saying no.
     */
    fun kickFromProxy(name: String, reason: String) {
        if (!behindProxy) return

        try {
            val payload = java.io.ByteArrayOutputStream()
            java.io.DataOutputStream(payload).use {
                it.writeUTF("KickPlayer")
                it.writeUTF(name)
                it.writeUTF(reason)
            }
            server.sendPluginMessage(this, "BungeeCord", payload.toByteArray())
        } catch (failure: Throwable) {
            logger.warning("Could not ask the proxy to disconnect $name: ${failure.message}")
        }
    }

    /**
     * Writes a fixed Limbo position and reloads.
     *
     * Stored in `config.yml` rather than a side file so that copying a server's plugin folder carries
     * the spawn with it, and so that the operator sees it next to the switch that enables it.
     */
    fun setLimboSpawn(location: org.bukkit.Location) {
        val world = location.world ?: return
        config.set("limbo.spawn.world", world.name)
        config.set("limbo.spawn.x", location.x)
        config.set("limbo.spawn.y", location.y)
        config.set("limbo.spawn.z", location.z)
        config.set("limbo.spawn.yaw", location.yaw.toDouble())
        config.set("limbo.spawn.pitch", location.pitch.toDouble())
        saveConfig()
        reloadSettings()
    }

    override fun onEnable() {
        migrateConfigIfOutdated()
        saveDefaultConfig()
        configuration = Configuration(config, dataFolder, server.worldContainer)
        messages = Messages(configuration.locale, dataFolder).also { it.load() }
        messenger = moe.ryuu.kyokouauth.bukkit.message.Messenger()
        connectionLimiter = moe.ryuu.kyokouauth.core.auth.ConnectionLimiter(configuration.connectionsPerMinute)

        // Must be configured before the first login can touch the directory.
        moe.ryuu.kyokouauth.core.auth.MojangDirectory.configureBudget(
            configuration.directoryBudget,
            configuration.directoryBudgetWindowMinutes,
        )

        behindProxy = isBehindProxy()

        if (configuration.requireDirectConnections && behindProxy) {
            severe("console.refuse-proxy")
            server.pluginManager.disablePlugin(this)
            return
        }

        // On a proxy backend, `/server` is answered by the proxy and never reaches Bukkit. That is
        // not a bypass in itself: a server switch is a fresh connection, so the target backend runs
        // this same pre-login gate and an unverified player is stopped there as well. The actual
        // hole is a backend that does not have this plugin installed, which is what this warns
        // about - the one case nothing on this side can cover.
        if (behindProxy) {
            consoleWarn("console.proxy-server-bypass")
        } else if (!configuration.requireDirectConnections) {
            // The operator turned the guard off, so they expect a proxy. Not finding one means the
            // detection looked in the wrong place or the proxy settings live in a file this server
            // does not have - worth saying out loud, because the plugin will now behave as if it
            // were a standalone server.
            consoleWarn(
                "console.proxy-not-detected",
                "cwd" to runCatching { java.io.File(".").canonicalPath }.getOrDefault("?"),
            )
        }

        io = moe.ryuu.kyokouauth.core.storage.Database.ioExecutor(configuration.poolSize)

        core = try {
            KyokouAuthCore.create(
                configuration = configuration,
                onMigrated = { version -> console("console.storage-migrated", "version" to version.toString()) },
                onNotice = { message -> logger.info(message) },
            )
        } catch (failure: Throwable) {
            severe("console.database-failed", "error" to (failure.message ?: failure.javaClass.simpleName))
            failure.printStackTrace()
            server.pluginManager.disablePlugin(this)
            return
        }

        limbo = LimboManager(this, messages) { configuration.limbo }
        floodgate = FloodgateBridge.create(
            plugin = this,
            onDetected = { version -> console("console.floodgate-detected", "version" to version) },
            onBroken = { error -> consoleWarn("console.floodgate-broken", "error" to error) },
        )
        if (floodgate != null) installLinkExtension()
        skins = moe.ryuu.kyokouauth.bukkit.skin.SkinService(
            this,
            moe.ryuu.kyokouauth.core.skin.OperatorSkinSource(loadOperatorSkins()),
        ) { message -> logger.fine(message) }
        if (skins.skinsRestorerPresent) console("console.skins-restorer") else console("console.skins-self")
        verification = VerificationService(
            plugin = this,
            core = core,
            messages = messages,
            limbo = limbo,
            pollIntervalSeconds = configuration.pollIntervalSeconds.toLong(),
            timeoutSeconds = configuration.verificationTimeoutSeconds,
        )

        // Chosen before any listener is registered: the implementation references PlayerProfile at
        // class level, and on an unsupported server the honest answer is to not start at all.
        val applier = moe.ryuu.kyokouauth.bukkit.profile.ProfileApplier.create(this)
        if (applier == null) {
            severe("console.refuse-version", "server" to "${server.name} ${server.version}")
            server.pluginManager.disablePlugin(this)
            return
        }
        profiles = applier

        server.pluginManager.registerEvents(LoginListener(this), this)
        server.pluginManager.registerEvents(LimboListener(this), this)

        // The chat event is the one class the limbo guards cannot assume is present, so it lives in
        // its own listener: a server missing it loses that restriction and nothing else.
        registerIfAvailable("io.papermc.paper.event.player.AsyncChatEvent", "Paper chat event") {
            server.pluginManager.registerEvents(LimboChatListener(this), this)
        }

        // Installed unconditionally, Floodgate or not.
        //
        // An earlier revision stood down whenever Floodgate was present, on the theory that Floodgate
        // sends its own Encryption Request and two handshakes would collide. Reading Floodgate's
        // injector proves otherwise: `SpigotInjector` only wraps the acceptor's channel list and adds
        // a per-connection handler, and the handler it adds (`SpigotDataHandler`, inserted with
        // `addBefore(packetHandler, ...)`) exists to decode the Bedrock payload that Geyser puts in
        // the handshake hostname. Everything else is `ResultType.NOT_FLOODGATE_DATA` and is passed
        // through untouched - no Encryption Request is ever sent to a Java client. `FloodgateCipher`
        // is AES over that hostname payload, not the login-phase cipher.
        //
        // So the two are orthogonal: Floodgate claims Bedrock connections, this claims Java premium
        // names, and a Bedrock connection never reaches us as a premium name anyway because
        // Floodgate's name prefix is rejected by `GameName`. Standing down did not avoid a conflict;
        // it removed the only door a premium name has.
        //
        // A proxy is the one case where standing down is right, and for the opposite reason: the
        // proxy owns the login, so a second handshake on the same connection is not merely
        // redundant - the Encryption Request it sends makes the proxy believe this backend is in
        // online-mode and drop the connection outright.
        if (!behindProxy) {
            injector = moe.ryuu.kyokouauth.bukkit.inject.LoginInjector(this)
            injector?.install()
        }

        KyokouAuthCommand(this).also { command ->
            getCommand("kyokouauth")?.let {
                it.setExecutor(command)
                it.tabCompleter = command
            }
        }

        console("console.enabled", "storage" to configuration.database.dialect.displayName)

        if (moe.ryuu.kyokouauth.bukkit.api.PlaceholderRegistrar.register(this)) {
            console("console.placeholderapi-ok")
        }

        selfCheck()
    }

    /**
     * Hands Floodgate the linking extension that resolves a Bedrock account to its Java identity.
     *
     * The extension travels inside this jar, so the operator never downloads, renames or places
     * anything. Two things still cannot be automated: Floodgate reads its linking folder only at
     * startup, so a first install needs one restart, and it only enables own linking from its own
     * config file, which this plugin will not rewrite behind the operator's back. Both are reported
     * rather than hidden - a silently inert feature looks identical to a broken one.
     */
    private fun installLinkExtension() {
        val floodgatePlugin = server.pluginManager.getPlugin("floodgate") ?: return

        when (val outcome = LinkExtension.install(floodgatePlugin)) {
            is LinkExtension.Outcome.Failed ->
                consoleWarn("console.link-extension-failed", "error" to outcome.reason)

            LinkExtension.Outcome.Installed -> console("console.link-extension-installed")

            LinkExtension.Outcome.AlreadyPresent -> Unit
        }

        LinkExtension.configurationProblem(floodgatePlugin)?.let { problem ->
            consoleWarn("console.link-extension-config", "problem" to problem)
        }
    }

    /** A catalogue message printed to the console, in whatever language the operator chose. */
    fun console(key: String, vararg placeholders: Pair<String, String>) {
        logger.info(messages.plain(key, *placeholders))
    }

    /** Same, at warning level. */
    fun consoleWarn(key: String, vararg placeholders: Pair<String, String>) {
        logger.warning(messages.plain(key, *placeholders))
    }

    /** Same, at severe level. */
    private fun severe(key: String, vararg placeholders: Pair<String, String>) {
        logger.severe(messages.plain(key, *placeholders))
    }

    /**
     * Verifies the message pipeline at startup instead of at a player's first join.
     *
     * The renderer walks Adventure types that differ between the 4.x line Paper bundles from 1.16.5
     * and the 5.x line current Paper ships. A mismatch there is invisible to the class loader - the
     * messages class only links when a message is first rendered, which is exactly when a player is
     * being kicked. Rendering one here turns that into a startup failure with a readable cause.
     */
    private fun selfCheck() {
        try {
            // Render one full message through the backend this server actually chose. This is the
            // only check that catches a backend/API mismatch on a specific server version, and it
            // catches it at startup rather than on the first player who tries to log in. Rendered,
            // not delivered: printing "click this link to sign in" to the console would read as an
            // instruction to the operator.
            messenger.probe(messages.parsed("verification.prompt", "url" to "https://example.com", "code" to "SELF-CHECK", "minutes" to "5"))
            messenger.legacy(messages.parsed("verification.prompt", "url" to "https://example.com", "code" to "SELF-CHECK", "minutes" to "5"))
            console("console.pipeline-ok", "locale" to configuration.locale)
        } catch (failure: Throwable) {
            severe("console.refuse-pipeline", "error" to (failure.javaClass.simpleName + ": " + (failure.message ?: "")))
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        injector?.uninstall()
        if (::limbo.isInitialized) limbo.releaseAll()
        if (::io.isInitialized) io.shutdownNow()
        if (::core.isInitialized) core.close()
    }

    /**
     * Detects a server that is the backend of a proxy.
     *
     * Both BungeeCord and Velocity forwarding make the server accept an identity the client did not
     * prove. On such a server the login handshake has already happened elsewhere, so this plugin's
     * entire premise is void.
     *
     * Only the *enabling* switches count. `proxies.bungee-cord.online-mode` is a separate question -
     * it describes the proxy's mode, defaults to true, and means nothing while forwarding is off.
     */
    private fun isBehindProxy(): Boolean {
        val spigot = java.io.File("spigot.yml")
        if (spigot.isFile) {
            val yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(spigot)
            if (yaml.getBoolean("settings.bungeecord", false)) return true
            if (yaml.getBoolean("settings.velocity-support.enabled", false)) return true
        }

        val paper = java.io.File("config/paper-global.yml")
        if (paper.isFile) {
            val yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(paper)
            if (yaml.getBoolean("proxies.velocity.enabled", false)) return true
        }
        return false
    }

    fun isProxyForwarding(): Boolean = isBehindProxy()

    /**
     * Reads `skins.yml`, the operator's name-to-texture map.
     *
     * Absent file means no map, which is the normal case: most servers either have premium and
     * Bedrock players (both covered automatically) or are content with default skins.
     */
    private fun loadOperatorSkins(): Map<String, String> {
        val file = java.io.File(dataFolder, "skins.yml")
        if (!file.isFile) return emptyMap()

        return try {
            val yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file)
            yaml.getKeys(false).mapNotNull { key ->
                yaml.getString(key)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    ?.let { key to it }
            }.toMap()
        } catch (failure: Throwable) {
            logger.warning("skins.yml could not be read: ${failure.message}")
            emptyMap()
        }
    }
}
