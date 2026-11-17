package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.UUID

/** Reflection belongs here, not in the shared authorization state machine. No SDK is bundled. */
class FloodgateRuntime(private val enabled: Boolean, private val allowLinked: Boolean = false,
                       private val provider: () -> FloodgateProvider?) {
    private val authority = if (enabled) checkNotNull(provider()) {
        "bedrock.enabled requires a proxy Floodgate installation"
    } else null
    private val access = authority?.let { original ->
        check(original.version.substringBefore('-').substringBefore(' ') == "2.2.5") {
            "bedrock.enabled requires a supported proxy Floodgate version"
        }
        try { Access(original, allowLinked) } catch (failure: Exception) {
            throw IllegalStateException("bedrock.enabled requires initialized compatible proxy Floodgate (${failure.javaClass.simpleName})")
        }
    }

    fun probe(connection: FloodgateConnection): BedrockLogin {
            return try {
                if (connection.attribute(KICK) != null) return rejected(BedrockFailure.PROVIDER_DENIED)
                val player = connection.attribute(PLAYER) ?: return BedrockLogin.Java
                if (!enabled) return rejected(BedrockFailure.DISABLED)
                val reader = access ?: return rejected(BedrockFailure.UNAVAILABLE)
                if (!providerCurrent() || !connection.active()) return rejected(BedrockFailure.STALE)
                val snapshot = reader.read(player, allowLinked)
                if (snapshot.linked && !allowLinked) return rejected(BedrockFailure.LINKED)
                val xuid = snapshot.xuid.toLongOrNull()?.takeIf { it > 0L }
                    ?: return rejected(BedrockFailure.INVALID_IDENTITY)
                if (snapshot.linked) {
                    if (snapshot.linkedUuid != snapshot.uuid || snapshot.linkedName != snapshot.name ||
                        snapshot.linkedBedrockId != Identity.ofXuid(Xuid(xuid))) return rejected(BedrockFailure.INVALID_IDENTITY)
                } else {
                    if (snapshot.uuid != Identity.ofXuid(Xuid(xuid))) return rejected(BedrockFailure.INVALID_IDENTITY)
                    val prefixIsolated = snapshot.prefix.isNotEmpty() && snapshot.prefix.any {
                        it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '_'
                    }
                    if (!prefixIsolated || !snapshot.name.startsWith(snapshot.prefix) ||
                        snapshot.name.length <= snapshot.prefix.length || runCatching { GameName.forBedrock(snapshot.name) }.isFailure) {
                        return rejected(BedrockFailure.UNSAFE_NAME)
                    }
                }
                if (reader.player(snapshot.uuid) !== player) return rejected(BedrockFailure.STALE)
                val current = {
                    providerCurrent() && connection.active() && connection.attribute(KICK) == null &&
                        connection.attribute(PLAYER) === player && reader.read(player, allowLinked) == snapshot &&
                        reader.player(snapshot.uuid) === player
                }
                val proof = if (snapshot.linked) BedrockProof.createLinked(Xuid(xuid), snapshot.uuid,
                    snapshot.name, snapshot.gamertag, connection.token, current)
                else BedrockProof.create(Xuid(xuid), snapshot.uuid, snapshot.name, snapshot.gamertag,
                    snapshot.prefix, connection.token, current)
                if (proof.isCurrent()) BedrockLogin.Verified(proof) else rejected(BedrockFailure.STALE)
            } catch (_: Exception) {
                rejected(BedrockFailure.INVALID_IDENTITY)
            }
        }

    private fun providerCurrent(): Boolean {
        val original = authority ?: return false
        val installed = provider() ?: return false
        return installed.instance === original.instance && installed.classLoader === original.classLoader &&
            installed.version == original.version && original.current() && installed.current() && access?.isCurrent() == true
    }

    private data class Snapshot(val xuid: String, val uuid: UUID, val name: String, val gamertag: String,
                                val prefix: String, val linked: Boolean, val linkedUuid: UUID?,
                                val linkedName: String?, val linkedBedrockId: UUID?)

    private class Access(provider: FloodgateProvider, allowLinked: Boolean) {
        private val apiClass = provider.classLoader.loadClass("org.geysermc.floodgate.api.FloodgateApi")
        private val playerClass = provider.classLoader.loadClass("org.geysermc.floodgate.api.player.FloodgatePlayer")
        private val singleton = apiClass.getMethod("getInstance").also {
            check(Modifier.isStatic(it.modifiers) && it.returnType == apiClass)
        }
        private val api = checkNotNull(singleton.invoke(null)) { "Floodgate is not initialized" }
        private val prefix = method(apiClass, "getPlayerPrefix", String::class.java)
        private val lookup = apiClass.getMethod("getPlayer", UUID::class.java).also { check(it.returnType == playerClass) }
        private val xuid = method(playerClass, "getXuid", String::class.java)
        private val uuid = method(playerClass, "getCorrectUniqueId", UUID::class.java)
        private val name = method(playerClass, "getCorrectUsername", String::class.java)
        private val gamertag = method(playerClass, "getUsername", String::class.java)
        private val linked = method(playerClass, "isLinked", Boolean::class.javaPrimitiveType!!)
        private val link = playerClass.getMethod("getLinkedPlayer").also { check(!it.returnType.isPrimitive) }
        private val linkedType = if (allowLinked) provider.classLoader.loadClass("org.geysermc.floodgate.util.LinkedPlayer") else null
        private val linkedUuid = linkedType?.let { method(it, "getJavaUniqueId", UUID::class.java) }
        private val linkedName = linkedType?.let { method(it, "getJavaUsername", String::class.java) }
        private val linkedBedrockId = linkedType?.let { method(it, "getBedrockId", UUID::class.java) }

        init {
            check(provider.classLoader.loadClass("org.geysermc.floodgate.api.ProxyFloodgateApi").isInstance(api)) {
                "Floodgate must run on this proxy"
            }
        }

        fun isCurrent(): Boolean = singleton.invoke(null) === api
        fun player(id: UUID): Any? = lookup.invoke(api, id)
        fun read(player: Any, linkedDetails: Boolean = false): Snapshot {
                    check(playerClass.isInstance(player)) { "Unexpected Floodgate player type" }
                    val linkedPlayer = link.invoke(player)
                    val isLinked = linked.invoke(player) as Boolean || linkedPlayer != null
                    var javaUuid: UUID? = null
                    var javaName: String? = null
                    var bedrockId: UUID? = null
                    if (linkedDetails && linkedPlayer != null) {
                check(checkNotNull(linkedType).isInstance(linkedPlayer)) { "Unexpected linked player type" }
                javaUuid = checkNotNull(linkedUuid).invoke(linkedPlayer) as UUID
                javaName = checkNotNull(linkedName).invoke(linkedPlayer) as String
                bedrockId = checkNotNull(linkedBedrockId).invoke(linkedPlayer) as UUID
                    }
                    return Snapshot(xuid.invoke(player) as String, uuid.invoke(player) as UUID,
                        name.invoke(player) as String, gamertag.invoke(player) as String, prefix.invoke(api) as String,
                        isLinked, javaUuid, javaName, bedrockId)
                }

        private fun method(owner: Class<*>, name: String, result: Class<*>): Method = owner.getMethod(name).also {
            check(it.returnType == result && Modifier.isPublic(it.modifiers))
        }
    }

    private fun rejected(reason: BedrockFailure) = BedrockLogin.Rejected(reason)
    private companion object {
        const val PLAYER = "floodgate-player"
        const val KICK = "floodgate-kick-message"
    }
}
