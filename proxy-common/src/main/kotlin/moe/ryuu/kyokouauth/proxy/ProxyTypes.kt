package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.text.RichText
import java.net.InetSocketAddress
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Ports retain exact platform object identity; no native proxy or component types cross here. */
interface CommandSource {
    val locale: String? get() = null
    val isConsole: Boolean get() = false
    fun hasPermission(permission: String): Boolean
    fun sendMessage(message: RichText.Parsed)
}

interface InboundConnection {
    val sessionId: UUID
    val isActive: Boolean
    val remoteAddress: InetSocketAddress
    fun disconnect(message: RichText.Parsed)
}

interface Player : InboundConnection, CommandSource {
    val uniqueId: UUID
    val username: String
    val isOnlineMode: Boolean
    val effectiveLocale: Locale
    val protocolVersion: Int
    override val locale: String? get() = effectiveLocale.toString()
    fun createConnectionRequest(target: RegisteredServer): ConnectionRequestBuilder
    /** Update managed properties only; the existing native UUID/name must exactly match [profile]. */
    fun applyRoleProfile(profile: GameProfile): CompletableFuture<Unit> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Role profile updates are unavailable"))
}

/** Instances must be stable for the registration lifetime, not re-created for each event. */
class RegisteredServer(val name: String)

interface ProxyServer {
    fun getServer(name: String): Optional<RegisteredServer>
    fun getPlayer(name: String): Optional<Player>
    fun getPlayer(uuid: UUID): Optional<Player>
    val allPlayers: Collection<Player>
    val configuration: ProxyConfiguration
}

interface ProxyConfiguration { val attemptConnectionOrder: List<String> }

interface ConnectionRequestBuilder {
    enum class Status { SUCCESS, ALREADY_CONNECTED, FAILED }
    data class Result(val status: Status)
    fun connect(): CompletableFuture<Result>
}

data class GameProfile(val id: UUID, val name: String, val properties: List<Property>) {
    data class Property(val name: String, val value: String, val signature: String)
    fun withId(id: UUID) = copy(id = id)
    fun withProperties(properties: List<Property>) = copy(properties = properties)
}

class PreLoginEvent(val connection: InboundConnection, val username: String, val uniqueId: UUID?,
                    val bedrock: BedrockLogin = BedrockLogin.Java) {
    var result = PreLoginComponentResult.allowed()
    data class PreLoginComponentResult(val isAllowed: Boolean, val onlineMode: Boolean?, val reason: RichText.Parsed?) {
        companion object {
            fun allowed() = PreLoginComponentResult(true, null, null)
            fun denied(reason: RichText.Parsed) = PreLoginComponentResult(false, null, reason)
            fun forceOnlineMode() = PreLoginComponentResult(true, true, null)
            fun forceOfflineMode() = PreLoginComponentResult(true, false, null)
        }
    }
}

class GameProfileRequestEvent(val connection: InboundConnection, var gameProfile: GameProfile,
                              val bedrock: BedrockLogin = BedrockLogin.Java)

class ResultedEvent {
    data class ComponentResult(val isAllowed: Boolean, val reason: RichText.Parsed?) {
        companion object {
            fun allowed() = ComponentResult(true, null)
            fun denied(reason: RichText.Parsed) = ComponentResult(false, reason)
        }
    }
}

class LoginEvent(val player: Player, val bedrock: BedrockLogin = BedrockLogin.Java) { var result = ResultedEvent.ComponentResult.allowed() }

class PlayerChooseInitialServerEvent(val player: Player) {
    var initialServer: RegisteredServer? = null
        private set
    fun setInitialServer(server: RegisteredServer?) { initialServer = server }
}

class ServerPreConnectEvent(val player: Player, target: RegisteredServer?) {
    var result = ServerResult(Optional.ofNullable(target))
    data class ServerResult(val server: Optional<RegisteredServer>) {
        companion object { fun denied() = ServerResult(Optional.empty()) }
    }
}

class KickedFromServerEvent(val player: Player) {
    var result: DisconnectPlayer? = null
    data class DisconnectPlayer(val reason: RichText.Parsed) {
        companion object { fun create(reason: RichText.Parsed) = DisconnectPlayer(reason) }
    }
}

class DisconnectEvent(val player: Player)
