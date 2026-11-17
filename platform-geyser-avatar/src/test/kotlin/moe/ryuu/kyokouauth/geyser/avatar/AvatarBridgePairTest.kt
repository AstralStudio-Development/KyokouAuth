package moe.ryuu.kyokouauth.geyser.avatar

import moe.ryuu.kyokouauth.core.avatar.AvatarHttp
import moe.ryuu.kyokouauth.core.avatar.AvatarLimits
import moe.ryuu.kyokouauth.proxy.avatar.RoleAvatarService
import moe.ryuu.kyokouauth.proxy.avatar.RoleAvatarStatusClient
import org.geysermc.geyser.api.pack.option.PriorityOption
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/**
 * Both HTTP bridges, image normalization, content validation and local pack ZIP/cache are real.
 * Only GeyserConnection/event and ResourcePack metadata use the existing package-local fixtures;
 * this test is not a running Geyser client, Xbox proof, resource download or phone render ACK.
 */
class AvatarBridgePairTest {
    @TempDir lateinit var directory: Path

    companion object {
        @JvmStatic @BeforeAll
        fun nativeValues() { NativeGeyserFixture.install() }
    }

    private fun ports(): Pair<Int, Int> {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        return ServerSocket(0, 1, loopback).use { first ->
            ServerSocket(0, 1, loopback).use { second -> first.localPort to second.localPort }
        }
    }

    private fun isolated(name: String): Path {
        val temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        assertTrue(temporary.toString().contains(".narrafork"), "Root Gradle must supply the ignored workspace test directory")
        assertTrue(directory.toAbsolutePath().normalize().startsWith(temporary))
        return directory.resolve(name)
    }

    private fun controlledJavaSkin(): ByteArray {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 64) for (x in 0 until 64) image.setRGB(x, y, 0xffb221ee.toInt())
        return ByteArrayOutputStream().also { assertTrue(ImageIO.write(image, "PNG", it)) }.toByteArray()
    }

    private fun textureValue(): String = Base64.getEncoder().encodeToString(
        """{"textures":{"SKIN":{"url":"https://textures.minecraft.net/texture/${"a".repeat(64)}"}}}""".toByteArray(),
    )

    private fun snapshotResponse(proxyPort: Int, xuid: Long) = AvatarHttp.get(
        URI("http://127.0.0.1:$proxyPort/v1/avatars?xuid=$xuid"), AvatarLimits.SNAPSHOT_WIRE_BYTES, 1_000,
    )

    private fun packEntries(pack: AvatarPackCache.Pack, root: Path): Map<String, ByteArray> {
        assertTrue(pack.path().toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))
        assertTrue(pack.path().fileName.toString().matches(Regex("avatar-(?:base-)?[0-9a-f]{64}\\.mcpack")))
        assertTrue(Files.size(pack.path()) in 1L..32_768L)
        return AvatarTestData.entries(Files.readAllBytes(pack.path())).also { entries ->
            val expected = setOf("manifest.json", "textures/kyokouauth/role_u.png", "textures/kyokouauth/role_j.png") +
                if (pack.base()) setOf("textures/item_texture.json") else emptySet()
            assertEquals(expected, entries.keys, "Only the two private image paths and owned pack metadata may be written")
            assertTrue(entries.getValue("manifest.json").toString(Charsets.UTF_8).contains("\"uuid\":\"${pack.uuid()}\""))
        }
    }

    private fun assertPortClosed(port: Int) {
        assertThrows(IOException::class.java) {
            Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), 200) }
        }
    }

    @Test
    fun `prepared real proxy snapshot traverses native pack runtime and real offered status bridge`() {
        val (proxyPort, statusPort) = ports()
        val root = isolated("ready")
        val downloads = AtomicInteger()
        val skin = controlledJavaSkin()
        val service = RoleAvatarService(proxyPort, downloader = { location ->
            assertEquals(URI("https://textures.minecraft.net/texture/${"a".repeat(64)}"), location)
            downloads.incrementAndGet()
            skin.copyOf()
        }) // The U image deliberately comes from the portable production default Steve supplier.
        val written = ConcurrentHashMap<UUID, AvatarPackCache.Pack>()
        val config = AvatarConfig(true, URI("http://127.0.0.1:$proxyPort/v1/avatars"), statusPort, 250, 4, 16)
        val connection = AvatarRuntimeTest.connection("1") // A strong-subject fixture supplied by the test, not proved by GET.
        service.use {
            service.start()
            assertEquals(202, snapshotResponse(proxyPort, 1L).status)
            assertEquals(0, downloads.get(), "A GET MISS must not prepare or initiate an external image request")
            val prepared = service.prepare(1L, null, textureValue()).get(3, TimeUnit.SECONDS)
            assertTrue(prepared.microsoftDefault)
            assertFalse(prepared.premiumDefault)
            assertEquals(1, downloads.get())
            val wire = snapshotResponse(proxyPort, 1L)
            assertEquals(200, wire.status)
            val json = wire.body.toString(Charsets.UTF_8)
            assertTrue(json.contains("\"microsoft_default\":true") && json.contains("\"premium_default\":false"))
            listOf("\"name\"", "\"xuid\"", "cookie", "token", "authorization").forEach { assertFalse(json.contains(it)) }

            // The source is the exact production default implementation, not a snapshot stub.
            val runtime = AvatarRuntime(config, root, LoopbackSnapshotClient(config.proxyEndpoint()), AvatarRuntime.PackFactory { pack ->
                written[pack.uuid()] = pack
                AvatarTestData.pack(pack)
            })
            try {
                AvatarStatusServer(statusPort, runtime.offers(), runtime.executor()).use { statusServer ->
                    statusServer.start()
                    val status = RoleAvatarStatusClient(URI("http://127.0.0.1:$statusPort/v1/packs/status"))
                    assertNull(status.status(1L), "Prepared images alone must not pretend a native session was offered a pack")
                    try {
                        val event = AvatarRuntimeTest.Event(connection)
                        runtime.onLoad(event)
                        event.returned = true
                        assertEquals(2, event.packs.size)
                        assertEquals(listOf(PriorityOption.NORMAL), event.options(event.packs[0].uuid()).toList())
                        assertEquals(listOf(PriorityOption.HIGHEST), event.options(event.packs[1].uuid()).toList())
                        val base = written.getValue(event.packs[0].uuid())
                        val overlay = written.getValue(event.packs[1].uuid())
                        assertTrue(base.base())
                        assertFalse(overlay.base())
                        assertNotEquals(base.uuid(), overlay.uuid())
                        packEntries(base, root)
                        val entries = packEntries(overlay, root)
                        assertArrayEquals(prepared.microsoftPng, entries.getValue("textures/kyokouauth/role_u.png"))
                        assertArrayEquals(prepared.premiumPng, entries.getValue("textures/kyokouauth/role_j.png"))
                        val microsoft = ImageIO.read(entries.getValue("textures/kyokouauth/role_u.png").inputStream())
                        val premium = ImageIO.read(entries.getValue("textures/kyokouauth/role_j.png").inputStream())
                        assertEquals(32, microsoft.width)
                        assertEquals(32, microsoft.height)
                        assertEquals(32, premium.width)
                        assertEquals(32, premium.height)
                        assertEquals(0, microsoft.getRGB(0, 0))
                        assertEquals(0, premium.getRGB(0, 0))
                        assertEquals(0xffb221ee.toInt(), premium.getRGB(8, 19))
                        assertNotEquals(microsoft.getRGB(8, 19), premium.getRGB(8, 19))
                        val offered = status.status(1L)
                        assertNotNull(offered)
                        assertFalse(offered!!.fallback)
                        assertEquals(prepared.fingerprint, offered.fingerprint)
                        assertEquals(4, offered.nonce.version())
                        assertEquals(overlay.fingerprint(), offered.fingerprint)
                        assertEquals(202, snapshotResponse(proxyPort, 2L).status)
                        assertNull(status.status(2L))
                        assertNull(service.snapshot(2L), "Public XUID query must not create a role or prepared presentation")
                        assertEquals(1, downloads.get(), "Native fetch and status GETs must only read the prepared snapshot")
                    } finally { runtime.disconnect(connection) }
                    assertNull(status.status(1L), "Exact-session cleanup must remove offered status")
                }
            } finally {
                runtime.close()
                assertTrue(runtime.executor().awaitTermination(1, TimeUnit.SECONDS))
            }
        }
        assertNull(service.snapshot(1L))
        assertTrue(written.values.all { Files.notExists(it.path()) }, "Close must release both owned immutable package files")
        assertPortClosed(proxyPort)
        assertPortClosed(statusPort)
    }

    @Test
    fun `real proxy miss or bridge closure remains explicit fallback through the real status client`() {
        for (bridgeClosed in listOf(false, true)) {
            val (proxyPort, statusPort) = ports()
            val root = isolated("fallback-$bridgeClosed")
            val downloads = AtomicInteger()
            val service = RoleAvatarService(proxyPort, downloader = { downloads.incrementAndGet(); controlledJavaSkin() })
            val written = ConcurrentHashMap<UUID, AvatarPackCache.Pack>()
            val config = AvatarConfig(true, URI("http://127.0.0.1:$proxyPort/v1/avatars"), statusPort, 250, 4, 16)
            val connection = AvatarRuntimeTest.connection("1")
            service.use {
                service.start()
                if (bridgeClosed) service.close() else assertEquals(202, snapshotResponse(proxyPort, 1L).status)
                val runtime = AvatarRuntime(config, root, LoopbackSnapshotClient(config.proxyEndpoint()), AvatarRuntime.PackFactory { pack ->
                    written[pack.uuid()] = pack
                    AvatarTestData.pack(pack)
                })
                try {
                    AvatarStatusServer(statusPort, runtime.offers(), runtime.executor()).use { statusServer ->
                        statusServer.start()
                        val status = RoleAvatarStatusClient(URI("http://127.0.0.1:$statusPort/v1/packs/status"))
                        try {
                            val event = AvatarRuntimeTest.Event(connection)
                            runtime.onLoad(event)
                            event.returned = true
                            assertEquals(1, event.packs.size)
                            val generic = written.getValue(event.packs.single().uuid())
                            assertTrue(generic.base())
                            packEntries(generic, root)
                            val offered = status.status(1L)
                            assertNotNull(offered)
                            assertTrue(offered!!.fallback)
                            assertNull(offered.fingerprint)
                            assertEquals(4, offered.nonce.version())
                            assertNull(service.snapshot(1L))
                            assertEquals(0, downloads.get(), "Neither a MISS nor an unavailable bridge can manufacture or prepare a role")
                        } finally { runtime.disconnect(connection) }
                        assertNull(status.status(1L))
                    }
                } finally {
                    runtime.close()
                    assertTrue(runtime.executor().awaitTermination(1, TimeUnit.SECONDS))
                }
            }
            assertTrue(written.values.all { Files.notExists(it.path()) })
            assertPortClosed(proxyPort)
            assertPortClosed(statusPort)
        }
    }
}
