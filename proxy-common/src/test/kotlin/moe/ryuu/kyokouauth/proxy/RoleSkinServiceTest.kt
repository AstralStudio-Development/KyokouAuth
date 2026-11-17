package moe.ryuu.kyokouauth.proxy

import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RoleSkinServiceTest {
    interface Skin { val value: String; val signature: String }
    interface Storage { fun getSkinOfPlayer(uuid: UUID): Optional<Skin> }
    interface Api { fun getPlayerStorage(): Storage }
    object Holder {
        var api: Api? = null
        @JvmStatic fun get(): Api = checkNotNull(api)
    }
    class Loader : ClassLoader(RoleSkinServiceTest::class.java.classLoader) {
        override fun loadClass(name: String): Class<*> = when (name) {
            "net.skinsrestorer.api.SkinsRestorer" -> Api::class.java
            "net.skinsrestorer.api.SkinsRestorerProvider" -> Holder::class.java
            "net.skinsrestorer.api.storage.PlayerStorage" -> Storage::class.java
            "net.skinsrestorer.api.property.SkinProperty" -> Skin::class.java
            else -> super.loadClass(name)
        }
    }

    @Test
    fun `Microsoft role reads only its UUID assignment and an unset role keeps default skin`() {
        val uuid = UUID(0L, 7L)
        val calls = mutableListOf<UUID>()
        Holder.api = object : Api {
            override fun getPlayerStorage() = object : Storage {
                override fun getSkinOfPlayer(uuid: UUID): Optional<Skin> {
                    calls += uuid
                    return if (uuid == UUID(0L, 7L)) Optional.of(object : Skin {
                        override val value = "configured-uuid-skin"
                        override val signature = "configured-signature"
                    }) else Optional.empty()
                }
            }
        }
        val token = Any()
        val loader = Loader()
        RoleSkinService { RoleSkinProvider(token, loader) { true } }.use { skins ->
            assertEquals(GameProfile.Property("textures", "configured-uuid-skin", "configured-signature"), skins.microsoft(uuid))
            assertNull(skins.microsoft(UUID(0L, 8L)), "An unset role must not fall back to another player's skin")
            // A local alias rename does not alter the UUID used to fetch the role's assigned skin.
            assertEquals("configured-uuid-skin", skins.microsoft(uuid)?.value)
        }
        assertEquals(listOf(uuid, UUID(0L, 8L), uuid), calls)
    }

    @Test
    fun `missing optional plugin means default but stale or failing storage is not misreported as unset`() {
        RoleSkinService().use { assertNull(it.microsoft(UUID(0L, 7L))) }
        Holder.api = object : Api {
            override fun getPlayerStorage() = object : Storage {
                override fun getSkinOfPlayer(uuid: UUID): Optional<Skin> = error("private-storage-detail")
            }
        }
        val token = Any()
        val loader = Loader()
        var current = true
        val service = RoleSkinService { RoleSkinProvider(token, loader) { current } }
        val failure = assertFailsWith<IllegalStateException> { service.microsoft(UUID(0L, 7L)) }
        assertFalse(failure.message!!.contains("private-storage-detail"))
        assertNull(failure.cause)
        current = false
        assertFailsWith<IllegalStateException> { service.microsoft(UUID(0L, 7L)) }
        service.close()
        assertFailsWith<IllegalStateException> { service.microsoft(UUID(0L, 7L)) }
    }
}
