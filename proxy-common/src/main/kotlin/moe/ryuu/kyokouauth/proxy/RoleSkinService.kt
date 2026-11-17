package moe.ryuu.kyokouauth.proxy

import java.lang.reflect.Modifier
import java.util.Optional
import java.util.UUID

/** Optional installed plugin, never a player-controlled classloader or an authentication proof. */
class RoleSkinProvider(val instance: Any, val loader: ClassLoader, val current: () -> Boolean)

/** Only explicit UUID-bound SR skin assignments are read; there is no name/default lookup. */
class RoleSkinService(private val provider: () -> RoleSkinProvider? = { null }) : AutoCloseable {
    private class Access(val provider: RoleSkinProvider) {
        private val apiType = provider.loader.loadClass("net.skinsrestorer.api.SkinsRestorer")
        private val holder = provider.loader.loadClass("net.skinsrestorer.api.SkinsRestorerProvider")
        private val get = holder.getMethod("get").also { check(Modifier.isStatic(it.modifiers) && apiType.isAssignableFrom(it.returnType)) }
        val api = checkNotNull(get.invoke(null))
        private val storageType = provider.loader.loadClass("net.skinsrestorer.api.storage.PlayerStorage")
        private val storage = apiType.getMethod("getPlayerStorage").invoke(api).also { check(storageType.isInstance(it)) }
        private val lookup = storageType.getMethod("getSkinOfPlayer", UUID::class.java).also { check(it.returnType == Optional::class.java) }
        private val skinType = provider.loader.loadClass("net.skinsrestorer.api.property.SkinProperty")
        private val value = skinType.getMethod("getValue").also { check(it.returnType == String::class.java) }
        private val signature = skinType.getMethod("getSignature").also { check(it.returnType == String::class.java) }
        fun current() = provider.current() && get.invoke(null) === api
        fun read(uuid: UUID): GameProfile.Property? {
            val skin = (lookup.invoke(storage, uuid) as Optional<*>).orElse(null) ?: return null
            check(skinType.isInstance(skin))
            val encoded = value.invoke(skin) as String
            val signed = signature.invoke(skin) as String
            check(encoded.length in 1..8192 && signed.length <= 2048) { "Invalid configured skin property" }
            return GameProfile.Property("textures", encoded, signed)
        }
    }

    private var access: Access? = null
    private var closed = false

    @Synchronized fun microsoft(uuid: UUID): GameProfile.Property? {
        check(!closed) { "Role skin service is closed" }
        val installed = provider() ?: return null
        return try {
            val current = access?.takeIf { it.provider.instance === installed.instance && it.provider.loader === installed.loader }
                ?: Access(installed).also { access = it }
            check(current.current()) { "Skin provider changed" }
            current.read(uuid)
        } catch (_: Exception) {
            throw IllegalStateException("Configured skin storage is unavailable")
        } catch (_: LinkageError) {
            throw IllegalStateException("Configured skin API is incompatible")
        }
    }

    @Synchronized override fun close() { closed = true; access = null }
}
