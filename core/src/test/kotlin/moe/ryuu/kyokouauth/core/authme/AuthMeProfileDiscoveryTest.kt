package moe.ryuu.kyokouauth.core.authme

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.test.*

class AuthMeProfileDiscoveryTest {
    @TempDir lateinit var root: Path
    private val old = UUID.fromString("529a5f64-5822-330d-a962-8e02fa0e0716")

    @Test
    fun `trusted cache and actual playerdata identify historical UUID without deriving it from the name`() {
        playerData(old)
        Files.writeString(root.resolve("usercache.json"), """[{"name":"ArchivedAlice","uuid":"$old"}]""")
        val before = Files.readAllBytes(root.resolve("world/playerdata/$old.dat"))
        val result = AuthMeProfileDiscovery.discover(root)
        assertEquals(listOf(HistoricalProfile("ArchivedAlice", old)), result.profiles)
        assertEquals(0, result.invalidFiles)
        assertContentEquals(before, Files.readAllBytes(root.resolve("world/playerdata/$old.dat")))
    }

    @Test
    fun `Bukkit saved name works without a surviving user cache`() {
        playerData(old, "ArchivedAlice")
        assertEquals(listOf(HistoricalProfile("ArchivedAlice", old)), AuthMeProfileDiscovery.discover(root).profiles)
    }

    @Test
    fun `cache without actual playerdata and bare filenames never synthesize a profile`() {
        Files.writeString(root.resolve("usercache.json"), """[{"name":"ArchivedAlice","uuid":"$old"}]""")
        assertTrue(AuthMeProfileDiscovery.discover(root).profiles.isEmpty())
        playerData(old)
        Files.delete(root.resolve("usercache.json"))
        assertTrue(AuthMeProfileDiscovery.discover(root).profiles.isEmpty())
    }

    @Test
    fun `renamed or malformed player files cannot establish a cached UUID`() {
        playerData(old, "ArchivedAlice", UUID.fromString("f66564fa-f35f-3ec0-8f67-f28f4ee58a44"))
        Files.writeString(root.resolve("usercache.json"), """[{"name":"ArchivedAlice","uuid":"$old"}]""")
        val scan = AuthMeProfileDiscovery.discover(root)
        assertTrue(scan.profiles.isEmpty())
        assertEquals(1, scan.invalidFiles)
    }

    @Test
    fun `ambiguous historical names are preserved for the planner to exclude both`() {
        playerData(old, "ArchivedAlice")
        val other = UUID.fromString("f66564fa-f35f-3ec0-8f67-f28f4ee58a44")
        playerData(other, "archivedalice")
        assertEquals(setOf(old, other), AuthMeProfileDiscovery.discover(root).profiles.map { it.profileUuid }.toSet())
    }

    @Test
    fun `scan limits and malformed cache fail without leaking source content`() {
        playerData(old, "ArchivedAlice")
        playerData(UUID.fromString("f66564fa-f35f-3ec0-8f67-f28f4ee58a44"), "Other")
        val limit = assertFailsWith<IllegalStateException> { AuthMeProfileDiscovery.discover(root, 1) }
        assertNull(limit.cause)
        Files.writeString(root.resolve("usercache.json"), """[{"name":{"PRIVATE_SENTINEL":[]}}]""")
        val malformed = assertFailsWith<IllegalStateException> { AuthMeProfileDiscovery.discover(root) }
        assertFalse(malformed.toString().contains("PRIVATE_SENTINEL"))
        assertNull(malformed.cause)
    }

    private fun playerData(uuid: UUID, name: String? = null, embeddedUuid: UUID = uuid) {
        val directory = Files.createDirectories(root.resolve("world/playerdata"))
        DataOutputStream(GZIPOutputStream(Files.newOutputStream(directory.resolve("$uuid.dat")))).use { out ->
            out.writeByte(10); out.writeUTF("")
            out.writeByte(11); out.writeUTF("UUID"); out.writeInt(4)
            out.writeInt((embeddedUuid.mostSignificantBits shr 32).toInt())
            out.writeInt(embeddedUuid.mostSignificantBits.toInt())
            out.writeInt((embeddedUuid.leastSignificantBits shr 32).toInt())
            out.writeInt(embeddedUuid.leastSignificantBits.toInt())
            if (name != null) {
                out.writeByte(10); out.writeUTF("bukkit")
                out.writeByte(8); out.writeUTF("lastKnownName"); out.writeUTF(name)
                out.writeByte(0)
            }
            out.writeByte(0)
        }
    }
}
