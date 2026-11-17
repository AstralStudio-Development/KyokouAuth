package moe.ryuu.kyokouauth.core.auth

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.identity.Identity
import moe.ryuu.kyokouauth.core.identity.Xuid
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import moe.ryuu.kyokouauth.core.storage.IdentityStore
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RoleResolverTest {
    private val xuid = Xuid(2533274790395904L)
    private val javaUuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
    private fun name(raw: String) = GameName.parseOrThrow(raw)

    @Test
    fun `current linked proof offers separate Microsoft and first visit Java roles without claiming either`() {
        val microsoft = BoundIdentity.of(Identity.Microsoft(name("OfflineRole"), xuid, "OldTag"), 100L)
        val store = ReadOnlyStore(mutableListOf(microsoft))
        val ready = assertIs<RoleResolution.Ready>(
            RoleResolver(store).resolve(xuid, "Changed Xbox Tag", AccountLinkResult.Linked(LinkedJavaProfile(javaUuid, name("Notch")))),
        )
        assertEquals(2, ready.roles.size)
        assertEquals(Identity.Microsoft(name("OfflineRole"), xuid, "Changed Xbox Tag"), ready.roles[0].identity)
        assertEquals(microsoft, ready.roles[0].existing)
        assertEquals(Identity.Premium(name("Notch"), javaUuid), ready.roles[1].identity)
        assertNull(ready.roles[1].existing)
        assertEquals(listOf(microsoft), store.records)
    }

    @Test
    fun `current not linked proof grants only the authenticated XUID role`() {
        val first = BoundIdentity.of(Identity.Microsoft(name("FirstRole"), xuid, "First"), 1L)
        val other = BoundIdentity.of(Identity.Microsoft(name("OtherRole"), Xuid(2002L), "Other"), 1L)
        val java = BoundIdentity.of(Identity.Premium(name("Notch"), javaUuid), 1L)
        val ready = assertIs<RoleResolution.Ready>(RoleResolver(ReadOnlyStore(mutableListOf(first, other, java))).resolve(xuid, "First", AccountLinkResult.NotLinked))
        assertEquals(listOf(first.uuid), ready.roles.map { it.identity.uuid })
    }

    @Test
    fun `linked first visit offers only Java and never manufactures a Microsoft role from its name`() {
        val store = ReadOnlyStore(mutableListOf())
        val ready = assertIs<RoleResolution.Ready>(RoleResolver(store).resolve(xuid, "Some Xbox Tag", AccountLinkResult.Linked(LinkedJavaProfile(javaUuid, name("Notch"))), ".SomeTag"))
        assertEquals(listOf(RoleCandidate(Identity.Premium(name("Notch"), javaUuid), null)), ready.roles)
        assertEquals(emptyList(), store.records)
    }

    @Test
    fun `new unlinked Bedrock must supply an isolated non Java fallback`() {
        val resolver = RoleResolver(ReadOnlyStore(mutableListOf()))
        assertIs<RoleResolution.Failed>(resolver.resolve(xuid, "Notch", AccountLinkResult.NotLinked))
        assertIs<RoleResolution.Failed>(resolver.resolve(xuid, "Notch", AccountLinkResult.NotLinked, "Notch"))
        assertIs<RoleResolution.Failed>(resolver.resolve(xuid, "Notch", AccountLinkResult.NotLinked, ".bad name"))
        val ready = assertIs<RoleResolution.Ready>(resolver.resolve(xuid, "Some Tag", AccountLinkResult.NotLinked, ".Some_Tag"))
        assertEquals(Identity.Microsoft(GameName.forBedrock(".Some_Tag"), xuid, "Some Tag"), ready.roles.single().identity)
        assertNull(ready.roles.single().existing)
    }

    @Test
    fun `unavailable never falls back to bound roles or isolated display names`() {
        val microsoft = BoundIdentity.of(Identity.Microsoft(name("OfflineRole"), xuid, "Tag"), 1L)
        val store = object : IdentityStore by ReadOnlyStore(mutableListOf(microsoft)) {
            override fun bySubject(kind: BoundIdentity.Kind, subject: String): BoundIdentity? = error("must not consult historical identity evidence")
        }
        assertIs<RoleResolution.Failed>(RoleResolver(store).resolve(xuid, "Tag", AccountLinkResult.Unavailable("timeout"), ".Tag"))
    }

    @Test
    fun `Microsoft rename and changed gamertag reread the same UUID without moving its local name`() {
        val original = BoundIdentity.of(Identity.Microsoft(name("Before"), xuid, "Tag"), 1L)
        val store = ReadOnlyStore(mutableListOf(original))
        val resolver = RoleResolver(store)
        val before = assertIs<RoleResolution.Ready>(resolver.resolve(xuid, "NewTag", AccountLinkResult.NotLinked)).roles.single()
        store.records[0] = original.copy(name = name("After"), lastSeenAt = 2L)
        val after = assertIs<RoleResolution.Ready>(resolver.resolve(xuid, "NewTag", AccountLinkResult.NotLinked, ".Wrong")).roles.single()
        assertEquals(before.identity.uuid, after.identity.uuid)
        assertEquals(name("After"), after.identity.name)
    }

    @Test
    fun `Java rename is resolved by UUID rather than stale global link name`() {
        val java = BoundIdentity.of(Identity.Premium(name("Before"), javaUuid), 1L)
        val store = ReadOnlyStore(mutableListOf(java))
        val resolver = RoleResolver(store)
        val proof = AccountLinkResult.Linked(LinkedJavaProfile(javaUuid, name("Before")))
        assertEquals(name("Before"), assertIs<RoleResolution.Ready>(resolver.resolve(xuid, "Tag", proof)).roles.single().identity.name)
        store.records[0] = java.copy(name = name("After"), lastSeenAt = 2L)
        val after = assertIs<RoleResolution.Ready>(resolver.resolve(xuid, "Tag", proof)).roles.single()
        assertEquals(javaUuid, after.identity.uuid)
        assertEquals(name("After"), after.identity.name)
    }

    @Test
    fun `revoked or changed current link cannot keep the previous Java candidate`() {
        val java = BoundIdentity.of(Identity.Premium(name("Notch"), javaUuid), 1L)
        val microsoft = BoundIdentity.of(Identity.Microsoft(name("OfflineRole"), xuid, "Tag"), 1L)
        val resolver = RoleResolver(ReadOnlyStore(mutableListOf(java, microsoft)))
        assertEquals(2, assertIs<RoleResolution.Ready>(resolver.resolve(xuid, "Tag", AccountLinkResult.Linked(LinkedJavaProfile(javaUuid, name("Notch"))))).roles.size)
        assertEquals(listOf(microsoft.uuid), assertIs<RoleResolution.Ready>(resolver.resolve(xuid, "Tag", AccountLinkResult.NotLinked)).roles.map { it.identity.uuid })
        val changed = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6")
        assertEquals(listOf(microsoft.uuid, changed), assertIs<RoleResolution.Ready>(resolver.resolve(xuid, "Tag", AccountLinkResult.Linked(LinkedJavaProfile(changed, name("NewJava"))))).roles.map { it.identity.uuid })
    }

    @Test
    fun `two authenticated XUIDs linked to one Java profile never gain the other Microsoft role`() {
        val otherXuid = Xuid(2002L)
        val first = BoundIdentity.of(Identity.Microsoft(name("FirstRole"), xuid, "Tag1"), 1L)
        val second = BoundIdentity.of(Identity.Microsoft(name("OtherRole"), otherXuid, "Tag2"), 1L)
        val resolver = RoleResolver(ReadOnlyStore(mutableListOf(first, second)))
        val proof = AccountLinkResult.Linked(LinkedJavaProfile(javaUuid, name("Notch")))
        assertEquals(listOf(first.uuid, javaUuid), assertIs<RoleResolution.Ready>(resolver.resolve(xuid, "Tag1", proof)).roles.map { it.identity.uuid })
        assertEquals(listOf(second.uuid, javaUuid), assertIs<RoleResolution.Ready>(resolver.resolve(otherXuid, "Tag2", proof)).roles.map { it.identity.uuid })
    }

    @Test
    fun `another local role owning linked Java name refuses the entire resolution`() {
        val microsoft = BoundIdentity.of(Identity.Microsoft(name("Notch"), xuid, "Tag"), 1L)
        assertIs<RoleResolution.Failed>(RoleResolver(ReadOnlyStore(mutableListOf(microsoft))).resolve(xuid, "Tag", AccountLinkResult.Linked(LinkedJavaProfile(javaUuid, name("Notch")))))
        val other = BoundIdentity.of(Identity.Premium(name("Notch"), UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6")), 1L)
        assertIs<RoleResolution.Failed>(RoleResolver(ReadOnlyStore(mutableListOf(other))).resolve(xuid, "Tag", AccountLinkResult.Linked(LinkedJavaProfile(javaUuid, name("Notch")))))
    }

    @Test
    fun `conflicting XUID subject UUID kind and missing name rows are refused`() {
        val valid = BoundIdentity.of(Identity.Microsoft(name("OfflineRole"), xuid, "Tag"), 1L)
        val invalid = listOf(
            valid.copy(uuid = UUID(0L, 2002L)),
            valid.copy(subject = "2002"),
            valid.copy(kind = BoundIdentity.Kind.MOJANG),
        )
        invalid.forEach { record ->
            assertIs<RoleResolution.Failed>(RoleResolver(ReadOnlyStore(mutableListOf(record))).resolve(xuid, "Tag", AccountLinkResult.NotLinked))
        }
        val missingName = object : IdentityStore by ReadOnlyStore(mutableListOf(valid)) {
            override fun byName(name: GameName): BoundIdentity? = null
        }
        assertIs<RoleResolution.Failed>(RoleResolver(missingName).resolve(xuid, "Tag", AccountLinkResult.NotLinked))
    }

    @Test
    fun `conflicting Java subject kind and invalid profile namespaces are refused`() {
        val valid = BoundIdentity.of(Identity.Premium(name("Notch"), javaUuid), 1L)
        listOf(valid.copy(subject = "wrong"), valid.copy(kind = BoundIdentity.Kind.XUID)).forEach { record ->
            assertIs<RoleResolution.Failed>(RoleResolver(ReadOnlyStore(mutableListOf(record))).resolve(xuid, "Tag", AccountLinkResult.Linked(LinkedJavaProfile(javaUuid, name("Notch")))))
        }
        val resolver = RoleResolver(ReadOnlyStore(mutableListOf()))
        listOf(UUID(0L, xuid.value), UUID(0L, 0L), UUID.fromString("069a79f4-44e9-1726-a5be-fca90e38aaf5")).forEach { invalid ->
            assertIs<RoleResolution.Failed>(resolver.resolve(xuid, "Tag", AccountLinkResult.Linked(LinkedJavaProfile(invalid, name("Notch")))))
        }
        assertIs<RoleResolution.Failed>(resolver.resolve(xuid, "Tag", AccountLinkResult.Linked(LinkedJavaProfile(javaUuid, GameName.forBedrock(".Notch")))))
        assertIs<RoleResolution.Failed>(resolver.resolve(Xuid(0L), "Tag", AccountLinkResult.NotLinked, ".Tag"))
    }

    @Test
    fun `database errors never become permission to use a fallback role`() {
        val store = object : IdentityStore by ReadOnlyStore(mutableListOf()) {
            override fun bySubject(kind: BoundIdentity.Kind, subject: String): BoundIdentity? = throw IllegalStateException("database unavailable")
        }
        assertIs<RoleResolution.Failed>(RoleResolver(store).resolve(xuid, "Tag", AccountLinkResult.NotLinked, ".Tag"))
    }

    private class ReadOnlyStore(val records: MutableList<BoundIdentity>) : IdentityStore {
        override fun byName(name: GameName) = records.firstOrNull { it.name.key == name.key }
        override fun byUuid(uuid: UUID) = records.firstOrNull { it.uuid == uuid }
        override fun bySubject(kind: BoundIdentity.Kind, subject: String) = records.firstOrNull { it.kind == kind && it.subject == subject }
        override fun cached(uuid: UUID) = error("access control must not use cached records")
        override fun all() = error("role resolution must not enumerate other subjects")
        override fun claim(identity: Identity, now: Long) = error("role resolution must not bind roles")
        override fun claimRenamingMicrosoft(identity: Identity.Microsoft, now: Long) = error("role resolution must not rename roles")
        override fun touch(uuid: UUID, now: Long) = error("role resolution must not mutate roles")
        override fun unbind(uuid: UUID) = error("role resolution must not unbind roles")
        override fun rename(uuid: UUID, newName: GameName, now: Long) = error("role resolution must not rename roles")
    }
}
