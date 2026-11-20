package moe.ryuu.kyokouauth.bungee

import net.md_5.bungee.api.connection.PendingConnection
import moe.ryuu.kyokouauth.proxy.BedrockProof
import java.lang.reflect.Modifier
import java.util.UUID

/** The only name write is on the verified offline InitialHandler while its PreLogin intent is held. */
internal class BungeeRoleLoginBridge(loader: ClassLoader) {
    private val handler = Class.forName("net.md_5.bungee.connection.InitialHandler", false, loader)
    private val user = Class.forName("net.md_5.bungee.UserConnection", false, loader)
    private val name = handler.getDeclaredField("name")
    private val state = handler.getDeclaredField("thisState")
    private val userCon = handler.getDeclaredField("userCon")
    private val loginRequest = handler.getMethod("getLoginRequest")
    private val publicKey = loginRequest.returnType.getMethod("getPublicKey")

    init {
        BungeeRuntime.requireCompatible()
        check(PendingConnection::class.java.isAssignableFrom(handler))
        check(name.type == String::class.java && mutablePrivate(name.modifiers)) { "Unsupported InitialHandler name signature" }
        check(state.type.isEnum && state.type.name == "net.md_5.bungee.connection.InitialHandler\$State" && mutablePrivate(state.modifiers))
        check(userCon.type == user && mutablePrivate(userCon.modifiers))
        check(loginRequest.returnType.name == "net.md_5.bungee.protocol.packet.LoginRequest" &&
            publicKey.returnType.name == "net.md_5.bungee.protocol.data.PlayerPublicKey")
        check(handler.getMethod("setUniqueId", UUID::class.java).returnType == Void.TYPE)
        check(listOf(name, state, userCon).all { it.trySetAccessible() }) { "Bungee prelogin role fields are inaccessible" }
    }

    /** Does not grant authorization, change online-mode, touch rewriteId, or mutate a Player. */
    fun prepare(pending: PendingConnection, id: UUID, roleName: String, proof: BedrockProof? = null) {
        val nativeBedrockName = proof != null && proof.isCurrent() && proof.username == roleName && proof.uniqueId == id
        require(roleName.length in 1..16 && roleName.none { it.isWhitespace() || it.isISOControl() } &&
            (VALID_ROLE_NAME.matches(roleName) || nativeBedrockName)) { "Invalid native role name" }
        prepareIdentity(pending, id, roleName)
    }

    /** Only the gate's deterministic first-bind alias may use this otherwise non-Java namespace. */
    fun prepareVerification(pending: PendingConnection, id: UUID, alias: String) {
        require(id == BungeeProfileBridge.offlineId(pending.name) &&
            moe.ryuu.kyokouauth.limbo.VerificationProfileName.matches(id, alias)) { "Invalid provisional identity alias" }
        prepareIdentity(pending, id, alias)
    }

    private fun prepareIdentity(pending: PendingConnection, id: UUID, targetName: String) {
        check(pending.javaClass == handler) { "Only the compatible InitialHandler is supported" }
        check(pending.isConnected && !pending.isOnlineMode && userCon.get(pending) == null &&
            (state.get(pending) as Enum<*>).name == "USERNAME") { "Identity may only be prepared in offline prelogin" }
        if (pending.uniqueId != id || pending.name != targetName) {
            val request = loginRequest.invoke(pending)
            check(request == null || publicKey.invoke(request) == null) { "Cannot carry a different identity's signed login key" }
        }
        // The native setter performs its own USERNAME/offline assertions. Never write uniqueId.
        pending.uniqueId = id
        name.set(pending, targetName)
        check(pending.uniqueId == id && pending.name == targetName) { "Native identity did not settle" }
    }

    private fun mutablePrivate(modifiers: Int) = Modifier.isPrivate(modifiers) &&
        !Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers)

    private companion object {
        val VALID_ROLE_NAME = Regex("[A-Za-z0-9_]{1,16}")
    }
}
