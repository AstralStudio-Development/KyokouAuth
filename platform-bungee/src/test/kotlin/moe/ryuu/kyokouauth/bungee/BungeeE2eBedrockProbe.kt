package moe.ryuu.kyokouauth.bungee

import io.netty.channel.Channel
import io.netty.util.AttributeKey
import net.md_5.bungee.api.connection.PendingConnection
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.LoginEvent
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.event.PreLoginEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.event.EventHandler
import net.md_5.bungee.event.EventPriority
import java.util.IdentityHashMap
import java.util.UUID

/**
 * Test-only observer of the official decrypt/listener chain. It never manufactures a player or a
 * positive proof. The isolated driver must send a real encrypted handshake using its own new key.
 */
internal class BungeeE2eBedrockProbe(
    private val plugin: Plugin,
    private val floodgate: BungeeFloodgate,
    private val profiles: ProfileAccess,
    scenario: String,
    private val authenticated: (ProxiedPlayer) -> Boolean,
    private val javaWrites: () -> Int,
) : Listener, AutoCloseable {
    private data class Observation(val channel: Channel, val player: Any, val uuid: UUID, val name: String)
    private val channels = BungeeFloodgateChannels(javaClass.classLoader)
    private val observations = IdentityHashMap<PendingConnection, Observation>()
    private val playerKey = AttributeKey.valueOf<Any>("floodgate-player")
    private val kickKey = AttributeKey.valueOf<Any>("floodgate-kick-message")
    private val tamper = System.getProperty("kyokou.e2e.bedrockTamper", when (scenario) {
        "bedrock-profile-uuid" -> "profile-uuid-at-login"
        "bedrock-profile-name" -> "profile-name-at-login"
        "bedrock-provider-stale" -> "provider-close-at-login"
        else -> "none"
    })

    init {
        require(tamper in setOf("none", "remove-player-at-login", "kick-at-login", "provider-close-at-login",
            "profile-uuid-at-login", "profile-name-at-login"))
    }

    @EventHandler(priority = EventPriority.LOW)
    fun afterFloodgate(event: PreLoginEvent) {
        try {
            val channel = channels.pending(event.connection)
            val player = channel.attr(playerKey).get()
            val kick = channel.attr(kickKey).get()
            plugin.logger.info("BUNGEE_E2E_FLOODGATE_NATIVE phase=PreLogin player=${player != null} kick=${kick != null} cancelled=${event.isCancelled}")
            if (event.isCancelled || player == null || kick != null) return
            check(!event.connection.isOnlineMode) { "Official Floodgate did not select offline mode in USERNAME" }
            val observation = Observation(channel, player, checkNotNull(event.connection.uniqueId), event.connection.name)
            synchronized(observations) {
                check(observations.size < 64 && observations.put(event.connection, observation) == null)
            }
        } catch (failure: Exception) {
            if (!event.isCancelled) { event.isCancelled = true; event.reason = unavailable().toBungee() }
            failed("PreLogin", failure, event.connection)
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    fun beforeAdmission(event: LoginEvent) {
        if (event.isCancelled) return
        try {
            val original = checkNotNull(synchronized(observations) { observations[event.connection] })
            check(current(event.connection, original)) { "Native source changed after official Floodgate PreLogin" }
            check(event.connection.uniqueId == original.uuid && event.connection.name == original.name && !event.connection.isOnlineMode)
            val profile = profiles.read(event.connection)
            check(profile.id == original.uuid && profile.name == original.name)
            plugin.logger.info("BUNGEE_E2E_FLOODGATE_NATIVE phase=Login sourceFresh=true profileConsistent=true")
            // Deliberately destructive negative-only hooks, after real decryption established source.
            when (tamper) {
                "remove-player-at-login" -> original.channel.attr(playerKey).set(null)
                "kick-at-login" -> original.channel.attr(kickKey).set("controlled isolated rejection")
                "provider-close-at-login" -> floodgate.close()
                "profile-uuid-at-login" -> tamperProfile(event.connection, "id", "11111111111111111111111111111111")
                "profile-name-at-login" -> tamperProfile(event.connection, "name", ".TamperedProfile")
            }
            if (tamper != "none") plugin.logger.info("BUNGEE_E2E_FLOODGATE_TAMPER $tamper")
        } catch (failure: Exception) {
            if (!event.isCancelled) { event.isCancelled = true; event.reason = unavailable().toBungee() }
            failed("Login", failure, event.connection)
        }
    }

    // Bungee's bus sorts the signed-byte priority; 65 runs strictly after native HIGHEST (64).
    // Same-priority listener registration order is deliberately not an authentication guarantee.
    @EventHandler(priority = 65)
    fun afterAdmission(event: PostLoginEvent) {
        val pending = event.player.pendingConnection
        try {
            val original = checkNotNull(synchronized(observations) { observations.remove(pending) })
            check(current(pending, original) && channels.native(event.player) === original.channel)
            check(event.player.uniqueId == original.uuid && event.player.name == original.name)
            val nativeProfile = pending.javaClass.getMethod("getLoginProfile").invoke(pending)
            val idPresent = nativeProfile?.let { it.javaClass.getMethod("getId").invoke(it) != null } == true
            val namePresent = nativeProfile?.let { it.javaClass.getMethod("getName").invoke(it) != null } == true
            plugin.logger.info("BUNGEE_E2E_FLOODGATE_NATIVE phase=PostLogin profilePresent=${nativeProfile != null} idPresent=$idPresent namePresent=$namePresent")
            val profile = profiles.read(pending)
            check(profile.id == original.uuid && profile.name == original.name)
            check(authenticated(event.player) && javaWrites() == 0) { "Shared Gate did not authenticate without Java writes" }
            plugin.logger.info("BUNGEE_E2E_FLOODGATE_NATIVE phase=PostLogin sourceFresh=true profileConsistent=true")
            plugin.logger.info("BUNGEE_E2E_BEDROCK_AUTHENTICATED uuid=${event.player.uniqueId} name=${event.player.name} javaWrites=${javaWrites()}")
        } catch (failure: Exception) { failed("PostLogin", failure, pending) }
    }

    @EventHandler
    fun disconnected(event: PlayerDisconnectEvent) { synchronized(observations) { observations.remove(event.player.pendingConnection) } }

    /** Negative fixture only: corrupt the actual native LoginResult, never a successful proof. */
    private fun tamperProfile(pending: PendingConnection, fieldName: String, value: String) {
        profiles.apply(pending, profiles.read(pending)) // Materialize an absent offline LoginResult first.
        val profile = checkNotNull(pending.javaClass.getMethod("getLoginProfile").invoke(pending))
        profile.javaClass.getDeclaredField(fieldName).apply {
            check(trySetAccessible())
            set(profile, value)
        }
    }

    private fun current(pending: PendingConnection, original: Observation): Boolean =
        channels.pending(pending) === original.channel && original.channel.isActive &&
            original.channel.attr(playerKey).get() === original.player && original.channel.attr(kickKey).get() == null

    private fun failed(phase: String, failure: Exception, pending: PendingConnection) {
        synchronized(observations) { observations.remove(pending) }
        pending.disconnect(unavailable().toBungee())
        val location = failure.stackTrace.firstOrNull { it.className.startsWith("moe.ryuu.kyokouauth.") }
        plugin.logger.warning("BUNGEE_E2E_FLOODGATE_NATIVE_FAILED phase=$phase type=${failure.javaClass.simpleName} location=$location")
        plugin.logger.warning("BUNGEE_E2E_BEDROCK_FORBIDDEN phase=$phase")
    }

    override fun close() { synchronized(observations) { observations.clear() } }
}
