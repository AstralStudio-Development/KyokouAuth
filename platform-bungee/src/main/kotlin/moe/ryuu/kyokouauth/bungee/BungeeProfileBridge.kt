package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.proxy.BedrockProof
import moe.ryuu.kyokouauth.proxy.GameProfile
import net.md_5.bungee.api.connection.PendingConnection
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Modifier
import java.util.UUID

/**
 * Deliberately narrow compatibility boundary for the verified official Bungee builds.
 * UUIDs are always set with PendingConnection.setUniqueId while still in USERNAME. The sole field
 * write installs an offline LoginResult, including a deliberate rebuild for a Gate-selected role
 * before Player construction. Later profiles only use the public properties setter; neither
 * InitialHandler.uniqueId nor rewriteId is reflected.
 */
internal class BungeeProfileBridge(
    loader: ClassLoader,
    private val bedrock: (PendingConnection) -> BedrockProof? = { null },
    private val role: (PendingConnection) -> GameProfile? = { null },
) : ProfileAccess {
    private val handler = Class.forName("net.md_5.bungee.connection.InitialHandler", false, loader)
    private val result = Class.forName("net.md_5.bungee.connection.LoginResult", false, loader)
    private val property = Class.forName("net.md_5.bungee.protocol.data.Property", false, loader)
    private val propertyArray = ReflectArray.newInstance(property, 0).javaClass
    private val getter = handler.getMethod("getLoginProfile")
    private val field = handler.getDeclaredField("loginProfile")
    private val resultConstructor = result.getConstructor(String::class.java, String::class.java, propertyArray)
    private val propertyConstructor = property.getConstructor(String::class.java, String::class.java, String::class.java)
    private val getProperties = result.getMethod("getProperties")
    private val setProperties = result.getMethod("setProperties", propertyArray)
    private val getId = result.getMethod("getId")
    private val getName = result.getMethod("getName")
    private val propertyName = property.getMethod("getName")
    private val propertyValue = property.getMethod("getValue")
    private val propertySignature = property.getMethod("getSignature")

    init {
        BungeeRuntime.requireCompatible()
        check(PendingConnection::class.java.isAssignableFrom(handler)) { "Unsupported Bungee InitialHandler" }
        check(getter.returnType == result && field.type == result && Modifier.isPrivate(field.modifiers) &&
            !Modifier.isStatic(field.modifiers) && !Modifier.isFinal(field.modifiers)) { "Unsupported loginProfile signature" }
        check(getProperties.returnType == propertyArray && setProperties.returnType == Void.TYPE)
        check(listOf(getId, getName, propertyName, propertyValue, propertySignature).all { it.returnType == String::class.java })
        check(handler.getMethod("setUniqueId", UUID::class.java).returnType == Void.TYPE)
        check(handler.getMethod("setOnlineMode", Boolean::class.javaPrimitiveType).returnType == Void.TYPE)
        check(field.trySetAccessible()) { "Bungee loginProfile is inaccessible; admission stays closed" }
    }

    private fun actual(pending: PendingConnection) {
        check(pending.javaClass == handler) { "Only the verified official InitialHandler is supported" }
    }

    override fun read(pending: PendingConnection): GameProfile {
        actual(pending)
        val profile = getter.invoke(pending)
        if (profile == null) return GameProfile(pending.uniqueId ?: offlineId(pending.name), pending.name, emptyList())
        val identity = identity(pending, profile)
        return GameProfile(identity.first, identity.second, properties(profile))
    }

    override fun apply(pending: PendingConnection, profile: GameProfile) {
        actual(pending)
        check(pending.uniqueId == profile.id && pending.name == profile.name) { "Profile identity differs from actual connection" }
        val properties = nativeProperties(profile.properties)
        val current = getter.invoke(pending)
        if (current == null) {
            check(!pending.isOnlineMode) { "Online login is missing its authenticated profile" }
            field.set(pending, resultConstructor.newInstance(profile.id.toString().replace("-", ""), profile.name, properties))
        } else {
            val identity = identity(pending, current)
            check(identity.first == profile.id && identity.second == profile.name) {
                "Existing authenticated profile identity changed"
            }
            // Official Floodgate may retain null id/name after applying a skin. They are not
            // identity fields in that container: update only properties, never repair either one.
            setProperties.invoke(current, properties)
        }
    }

    // Loaded only by the explicitly enabled role branch, never by default Java admission.
    private val rolePhase by lazy {
        val state = handler.getDeclaredField("thisState")
        val user = handler.getDeclaredField("userCon")
        check(state.type.isEnum && state.type.name == "net.md_5.bungee.connection.InitialHandler\$State" &&
            user.type.name == "net.md_5.bungee.UserConnection")
        check(listOf(state, user).all { Modifier.isPrivate(it.modifiers) && !Modifier.isStatic(it.modifiers) && it.trySetAccessible() })
        state to user
    }

    /** Rebuild a server-prepared offline role container, never reuse another role's LoginResult. */
    fun prepareRole(pending: PendingConnection, profile: GameProfile) {
        actual(pending)
        val (state, user) = rolePhase
        check(pending.isConnected && !pending.isOnlineMode && user.get(pending) == null &&
            (state.get(pending) as Enum<*>).name == "USERNAME") { "Role profile may only be rebuilt in offline prelogin" }
        val expected = checkNotNull(role(pending)) { "Role profile requires the Gate-selected identity" }
        check(expected.id == profile.id && expected.name == profile.name &&
            pending.uniqueId == profile.id && pending.name == profile.name) { "Prepared role differs from native identity" }
        val properties = nativeProperties(profile.properties)
        field.set(pending, resultConstructor.newInstance(profile.id.toString().replace("-", ""), profile.name, properties))
    }

    private fun identity(pending: PendingConnection, profile: Any): Pair<UUID, String> {
        val id = getId.invoke(profile) as String?
        val name = getName.invoke(profile) as String?
        if (id != null && name != null) return parseUuid(id) to name
        check(id == null && name == null && !pending.isOnlineMode) { "Incomplete native profile identity" }
        // FG's official PostLogin skin applier creates LoginResult(null, null, properties).
        // Only a still-current proof admitted for this exact native player may interpret it.
        val proof = checkNotNull(bedrock(pending)) { "Skin-only profile requires current Bedrock proof" }
        val selected = role(pending)
        val expectedId = selected?.id ?: proof.uniqueId
        val expectedName = selected?.name ?: proof.username
        check(proof.isCurrent() && pending.uniqueId == expectedId && pending.name == expectedName) {
            "Skin-only profile differs from its proof-bound native or Gate-selected role identity"
        }
        return expectedId to expectedName
    }

    private fun nativeProperties(properties: List<GameProfile.Property>): Any =
        ReflectArray.newInstance(property, properties.size).also { values ->
            properties.forEachIndexed { index, item ->
                ReflectArray.set(values, index, propertyConstructor.newInstance(item.name, item.value, item.signature.ifEmpty { null }))
            }
        }

    private fun properties(profile: Any): List<GameProfile.Property> {
        val values = getProperties.invoke(profile) ?: return emptyList()
        return (0 until ReflectArray.getLength(values)).map { index ->
            val item = ReflectArray.get(values, index)
            GameProfile.Property(propertyName.invoke(item) as String, propertyValue.invoke(item) as String,
                propertySignature.invoke(item) as? String ?: "")
        }
    }

    companion object {
        private val UNDASHED_UUID = Regex("^(.{8})(.{4})(.{4})(.{4})(.{12})$")
        internal fun offlineId(name: String): UUID = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
        private fun parseUuid(value: String): UUID = UUID.fromString(if (value.length == 32)
            value.replaceFirst(UNDASHED_UUID, "$1-$2-$3-$4-$5") else value)
    }
}
