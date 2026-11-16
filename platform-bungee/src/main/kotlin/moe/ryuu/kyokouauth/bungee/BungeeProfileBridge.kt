package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.proxy.GameProfile
import net.md_5.bungee.api.connection.PendingConnection
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Modifier
import java.util.UUID

/**
 * Deliberately narrow compatibility boundary for official Bungee 2111.
 * UUIDs are always set with PendingConnection.setUniqueId while still in USERNAME. The sole field
 * write creates an absent offline LoginResult; existing profiles only use their public properties
 * setter. In particular neither InitialHandler.uniqueId nor rewriteId is reflected.
 */
internal class BungeeProfileBridge(loader: ClassLoader) : ProfileAccess {
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
        check(handler.`package`.implementationVersion?.endsWith("55e1b0b:2111") == true) {
            "KyokouAuth-Bungee requires the verified official Bungee 2111 implementation (55e1b0b)"
        }
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
        return if (profile == null) GameProfile(pending.uniqueId ?: offlineId(pending.name), pending.name, emptyList())
        else GameProfile(parseUuid(getId.invoke(profile) as String), getName.invoke(profile) as String, properties(profile))
    }

    override fun apply(pending: PendingConnection, profile: GameProfile) {
        actual(pending)
        check(pending.uniqueId == profile.id && pending.name == profile.name) { "Profile identity differs from actual connection" }
        val properties = ReflectArray.newInstance(property, profile.properties.size)
        profile.properties.forEachIndexed { index, item ->
            ReflectArray.set(properties, index, propertyConstructor.newInstance(item.name, item.value, item.signature.ifEmpty { null }))
        }
        val current = getter.invoke(pending)
        if (current == null) {
            check(!pending.isOnlineMode) { "Online login is missing its authenticated profile" }
            field.set(pending, resultConstructor.newInstance(profile.id.toString().replace("-", ""), profile.name, properties))
        } else {
            check(parseUuid(getId.invoke(current) as String) == profile.id && getName.invoke(current) == profile.name) {
                "Existing authenticated profile identity changed"
            }
            setProperties.invoke(current, properties)
        }
    }

    fun hasProperty(pending: PendingConnection, name: String): Boolean = read(pending).properties.any { it.name == name }

    private fun properties(profile: Any): List<GameProfile.Property> {
        val values = getProperties.invoke(profile) ?: return emptyList()
        return (0 until ReflectArray.getLength(values)).map { index ->
            val item = ReflectArray.get(values, index)
            GameProfile.Property(propertyName.invoke(item) as String, propertyValue.invoke(item) as String,
                propertySignature.invoke(item) as? String ?: "")
        }
    }

    companion object {
        internal fun offlineId(name: String): UUID = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
        private fun parseUuid(value: String): UUID = UUID.fromString(if (value.length == 32)
            value.replaceFirst(Regex("^(.{8})(.{4})(.{4})(.{4})(.{12})$"), "$1-$2-$3-$4-$5") else value)
    }
}
