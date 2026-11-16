package moe.ryuu.kyokouauth.bukkit.inject

import io.netty.channel.Channel
import moe.ryuu.kyokouauth.bukkit.KyokouAuthPlugin
import moe.ryuu.kyokouauth.core.auth.MojangDirectory
import moe.ryuu.kyokouauth.core.auth.MojangSession
import net.kyori.adventure.key.Key

/**
 * Installs the premium handshake on every incoming connection.
 *
 * Uses Paper's supported [io.papermc.paper.network.ChannelInitializeListenerHolder] rather than
 * reaching into `ServerConnectionListener` with reflection: the pipeline name the handler is
 * inserted after (`splitter`) is a protocol constant, while the class that builds the pipeline is
 * not.
 *
 * ## Verified profiles
 *
 * A successful handshake produces a [MojangSession.Profile] that must reach the login gate. It is
 * stashed here keyed by remote address and name, then consumed once by the pre-login listener. The
 * stash is deliberately single-use and short-lived: replaying a verified profile onto a later
 * connection would let a proven player's identity be reused by whoever holds that address next.
 */
class LoginInjector(private val plugin: KyokouAuthPlugin) {

    private var hook: ChannelHook.Handle? = null

    private val verified = java.util.concurrent.ConcurrentHashMap<String, StashedProfile>()

    class StashedProfile(
        val profile: MojangSession.Profile,
        val verifiedAt: Long,
    )

    private val keys = HandshakeKeys()

    /**
     * Whether the server expects the `shouldAuthenticate` flag on Encryption Request.
     *
     * Added in 1.20.5 (protocol 766). Sending it to an older client shifts every subsequent field
     * by one byte, so this has to be exact - hence a real version parse rather than a guess.
     *
     * Read from `Bukkit.getBukkitVersion()` rather than `Server#getMinecraftVersion`: the latter is
     * a Paper addition whose availability moves with the API line, while the Bukkit version string
     * is `<mc>-R0.1-SNAPSHOT` on every generation this plugin loads on.
     */
    private val supportsShouldAuthenticate: Boolean = run {
        val minecraft = plugin.server.bukkitVersion.substringBefore('-')
        val parts = minecraft.split(".").mapNotNull { it.toIntOrNull() }
        val major = parts.getOrNull(0) ?: 0
        val minor = parts.getOrNull(1) ?: 0
        val patch = parts.getOrNull(2) ?: 0

        when {
            // The 26.x year-based scheme is unambiguously newer than any 1.x release.
            major >= 26 -> true
            major > 1 -> true
            minor > 20 -> true
            minor == 20 -> patch >= 5
            else -> false
        }
    }

    fun install() {
        hook = ChannelHook.install("premium-handshake") { channel -> attach(channel) }
        if (hook == null) {
            plugin.consoleWarn("console.handshake-unavailable")
        } else {
            plugin.console("console.handshake-installed")
        }
    }

    fun uninstall() {
        hook?.remove()
        hook = null
    }

    private fun attach(channel: Channel) {
        // `splitter` is the frame decoder's pipeline name, stable across every supported version.
        if (channel.pipeline().get(NAME) != null) return
        if (channel.pipeline().get("splitter") == null) return

        channel.pipeline().addAfter(
            "splitter",
            NAME,
            PremiumHandshakeHandler(
                keys = keys,
                lookupName = ::lookupName,
                onVerified = { name, profile, address ->
                    verified[keyOf(name, address)] = StashedProfile(profile, System.currentTimeMillis())
                },
                supportsShouldAuthenticate = supportsShouldAuthenticate,
                sessionServerUrl = plugin.configuration.sessionServerUrl,
                renderKick = { reason ->
                    val key = when (reason) {
                        PremiumHandshakeHandler.KickReason.NOT_AUTHENTICATED -> "handshake.not-authenticated"
                        PremiumHandshakeHandler.KickReason.SESSION_SERVER_UNAVAILABLE -> "handshake.session-unavailable"
                        PremiumHandshakeHandler.KickReason.HANDSHAKE_FAILED -> "handshake.failed"
                        PremiumHandshakeHandler.KickReason.DIRECTORY_UNAVAILABLE -> "handshake.directory-unavailable"
                    }
                    plugin.messenger.legacy(plugin.messages.parsed(key))
                },
                logger = plugin.logger::info,
                ioExecutor = plugin.io,
            ),
        )
    }


    /**
     * The directory's answer for this name, in all three states.
     *
     * A Floodgate name is answered without asking Mojang. Those names carry Floodgate's prefix
     * (`.` by default), which no Mojang account can hold, so the directory can only ever say "no" -
     * but it would say it after an HTTP round trip, once per Bedrock join, against a rate limiter
     * that premium joins need. Short-circuiting keeps that budget for the logins that can spend it.
     *
     * The result is passed through rather than reduced to a boolean: `Failed` means the question was
     * never answered, and treating it as "not premium" skipped the handshake for real premium
     * players.
     */
    private fun lookupName(name: String): MojangDirectory.Lookup {
        val prefix = plugin.floodgate?.floodgateNamePrefix
        if (!prefix.isNullOrEmpty() && name.startsWith(prefix)) return MojangDirectory.Lookup.Free
        return MojangDirectory.lookup(name)
    }

    /**
     * Takes the verified profile for this connection, if a handshake produced one.
     *
     * Single-use: removing on read means a replay cannot inherit it, and the short TTL means an
     * abandoned verification does not linger.
     */
    fun consume(name: String, address: String): MojangSession.Profile? {
        val stash = verified.remove(keyOf(name, address)) ?: return null
        if (System.currentTimeMillis() - stash.verifiedAt > 30_000L) return null
        return stash.profile
    }

    private fun keyOf(name: String, address: String): String = name.lowercase() + "|" + address

    private companion object {
        const val NAME = "kyokouauth_handshake"
    }
}
