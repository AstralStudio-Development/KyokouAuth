package moe.ryuu.kyokouauth.core.storage

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.net.InetSocketAddress
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DriverLibraryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `only the selected driver is downloaded and concurrent callers share its loader`() {
        val jar = driverJar()
        repository(jar).use { repository ->
            isolatedLibrary(repository.url).use { library ->
                val start = CountDownLatch(1)
                val workers = Executors.newFixedThreadPool(8)
                try {
                    val results = (1..8).map {
                        workers.submit<ClassLoader?> {
                            assertTrue(start.await(5, TimeUnit.SECONDS))
                            library.classLoader()
                        }
                    }
                    start.countDown()
                    val first = assertNotNull(results.first().get(10, TimeUnit.SECONDS))
                    results.forEach { assertSame(first, it.get(10, TimeUnit.SECONDS)) }
                    library.prepare()
                    assertEquals(listOf("/${DriverCatalog.of(Dialect.H2).repositoryPath}"), repository.jarRequests())
                    assertNotNull(first.loadClass(Database.driverClass(Dialect.H2)))
                } finally {
                    workers.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `an offline manually installed jar is kept and its owned loader is closed`() {
        val jar = driverJar()
        val target = target()
        Files.createDirectories(target.parent)
        Files.write(target, jar)
        val library = isolatedLibrary("http://127.0.0.1:1", allowDownload = false)
        val loader = assertNotNull(library.classLoader()) as URLClassLoader
        assertNotNull(loader.getResource(PROBE))

        library.close()
        library.close()

        assertNull(loader.getResource(PROBE), "closing the library must release its jar loader")
        assertFailsWith<IllegalStateException> { library.classLoader() }
        assertFailsWith<IllegalStateException> { library.prepare() }
        assertContentEquals(jar, Files.readAllBytes(target))
    }

    @Test
    fun `the platform driver remains usable and no download is attempted`() {
        IsolatedLibrary(DriverLibrary(directory.toFile(), "http://127.0.0.1:1", false) {}, Dialect.H2).use { library ->
            library.prepare()
            assertNull(library.classLoader())
        }
        assertNotNull(Class.forName(Database.driverClass(Dialect.H2)))
        assertTrue(directory.toFile().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `offline mode refuses a missing driver without creating files`() {
        isolatedLibrary("http://127.0.0.1:1", allowDownload = false).use { library ->
            assertTrue(assertFails { library.classLoader() }.message.orEmpty().contains("disabled"))
        }
        assertTrue(directory.toFile().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a zip prefix is not enough and invalid downloads leave no published jar or parts`() {
        val invalid = ByteArray(2048).also { it[0] = 'P'.code.toByte(); it[1] = 'K'.code.toByte() }
        repository(invalid).use { repository ->
            isolatedLibrary(repository.url).use { library ->
                assertFails { library.prepare() }
            }
        }
        assertNoPublishedOrTemporaryFiles()
    }

    @Test
    fun `a valid jar without the expected driver class is rejected`() {
        repository(driverJar(includeDriver = false)).use { repository ->
            isolatedLibrary(repository.url).use { library ->
                assertFails { library.prepare() }
            }
        }
        assertNoPublishedOrTemporaryFiles()
    }

    @Test
    fun `an invalid manual jar is preserved without attempting a replacement download`() {
        val original = "operator's existing file".toByteArray()
        Files.createDirectories(target().parent)
        Files.write(target(), original)
        repository(driverJar()).use { repository ->
            isolatedLibrary(repository.url).use { library -> assertFails { library.prepare() } }
            assertTrue(repository.jarRequests().isEmpty())
        }
        assertContentEquals(original, Files.readAllBytes(target()))
    }

    @Test
    fun `a supplied sha256 is verified before publication`() {
        val jar = driverJar()
        repository(jar, checksum = "0".repeat(64)).use { repository ->
            isolatedLibrary(repository.url).use { library ->
                assertFails { library.prepare() }
            }
        }
        assertNoPublishedOrTemporaryFiles()

        val checksum = MessageDigest.getInstance("SHA-256").digest(jar).joinToString("") { "%02x".format(it) }
        repository(jar, checksum).use { repository ->
            isolatedLibrary(repository.url).use { library -> library.prepare() }
        }
        assertContentEquals(jar, Files.readAllBytes(target()))
    }

    @Test
    fun `a manual jar arriving during download is never overwritten`() {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val manual = driverJar(probe = "manually installed")
        repository(driverJar(), beforeJar = {
            started.countDown()
            assertTrue(finish.await(5, TimeUnit.SECONDS))
        }).use { repository ->
            isolatedLibrary(repository.url).use { library ->
                val worker = Executors.newSingleThreadExecutor()
                try {
                    val download = worker.submit { library.prepare() }
                    assertTrue(started.await(5, TimeUnit.SECONDS))
                    Files.createDirectories(target().parent)
                    Files.write(target(), manual)
                    finish.countDown()
                    download.get(10, TimeUnit.SECONDS)
                    assertContentEquals(manual, Files.readAllBytes(target()))
                } finally {
                    finish.countDown()
                    worker.shutdownNow()
                }
            }
        }
        assertTrue(Files.list(target().parent).use { files -> files.noneMatch { it.toString().endsWith(".part") } })
    }

    @Test
    fun `download notices and safe failures do not expose repository credentials`() {
        val notices = mutableListOf<String>()
        repository("not a jar".toByteArray()).use { repository ->
            val authenticated = repository.url.replace("http://", "http://operator:secret-password@") + "?token=secret-token"
            isolatedLibrary(authenticated, notices = notices).use { library ->
                val failure = assertFails { library.prepare() }
                (notices + failure.message.orEmpty()).forEach {
                    assertTrue(!it.contains("secret-password") && !it.contains("secret-token"), it)
                }
            }
        }
    }

    @Test
    fun `an oversized declared download is rejected before publication`() {
        repository(driverJar(), declaredLength = 64L * 1024L * 1024L).use { repository ->
            isolatedLibrary(repository.url).use { library ->
                val message = assertFails { library.prepare() }.message.orEmpty()
                assertTrue(message.contains("byte limit"), message)
            }
        }
        assertNoPublishedOrTemporaryFiles()
    }

    @Test
    fun `chunked downloads are bounded even when a valid jar has no declared length`() {
        val oversized = directory.resolve("oversized-source.jar")
        val buffer = ByteArray(1024 * 1024)
        val crc = CRC32().apply { repeat(33) { update(buffer) } }
        JarOutputStream(Files.newOutputStream(oversized)).use { jar ->
            val resource = Database.driverClass(Dialect.H2).replace('.', '/') + ".class"
            jar.putNextEntry(JarEntry(resource))
            javaClass.classLoader.getResourceAsStream(resource)!!.use { it.copyTo(jar) }
            jar.closeEntry()
            jar.putNextEntry(JarEntry("large-data.bin").apply {
                method = ZipEntry.STORED
                size = 33L * buffer.size
                compressedSize = size
                this.crc = crc.value
            })
            repeat(33) { jar.write(buffer) }
            jar.closeEntry()
        }
        repository(ByteArray(0), declaredLength = 0, streamedJar = oversized).use { repository ->
            isolatedLibrary(repository.url).use { library ->
                val message = assertFails { library.prepare() }.message.orEmpty()
                assertTrue(message.contains("byte limit"), message)
            }
        }
        assertNoPublishedOrTemporaryFiles()
    }

    @Test
    fun `close waits for an active prepare and releases its resulting loader`() {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        repository(driverJar(), beforeJar = {
            started.countDown()
            assertTrue(finish.await(5, TimeUnit.SECONDS))
        }).use { repository ->
            isolatedLibrary(repository.url).use { library ->
                val workers = Executors.newFixedThreadPool(2)
                try {
                    val prepared = workers.submit<ClassLoader?> { library.classLoader() }
                    assertTrue(started.await(5, TimeUnit.SECONDS))
                    val closing = workers.submit { library.close() }
                    assertFailsWith<TimeoutException> { closing.get(100, TimeUnit.MILLISECONDS) }
                    finish.countDown()
                    val loader = assertNotNull(prepared.get(10, TimeUnit.SECONDS))
                    closing.get(10, TimeUnit.SECONDS)
                    assertNull(loader.getResource(PROBE))
                    assertFailsWith<IllegalStateException> { library.prepare() }
                } finally {
                    finish.countDown()
                    workers.shutdownNow()
                }
            }
        }
    }

    private fun assertNoPublishedOrTemporaryFiles() {
        assertTrue(!Files.exists(target()))
        if (Files.exists(target().parent)) {
            assertTrue(Files.list(target().parent).use { files -> files.noneMatch { it.toString().endsWith(".part") } })
        }
    }

    private fun target(): Path = directory.resolve(DriverCatalog.of(Dialect.H2).repositoryPath)

    private fun isolatedLibrary(
        repository: String,
        allowDownload: Boolean = true,
        notices: MutableList<String> = mutableListOf(),
    ): IsolatedLibrary {
        val parent = javaClass.classLoader
        val isolated = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
                if (Dialect.entries.any { name == Database.driverClass(it) }) throw ClassNotFoundException(name)
                val loaded = findLoadedClass(name) ?: if (name.startsWith("moe.ryuu.kyokouauth.core.storage.")) {
                    val bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.use { it.readBytes() }
                    defineClass(name, bytes, 0, bytes.size)
                } else {
                    super.loadClass(name, false)
                }
                if (resolve) resolveClass(loaded)
                loaded
            }
        }
        val type = isolated.loadClass(DriverLibrary::class.java.name)
        val instance = type.constructors.single { it.parameterCount == 4 }.newInstance(
            directory.toFile(), repository, allowDownload, { message: String -> notices.add(message); Unit },
        )
        val dialect = isolated.loadClass(Dialect::class.java.name).enumConstants.first { it.toString() == "H2" }
        return IsolatedLibrary(instance, dialect)
    }

    private class IsolatedLibrary(private val instance: Any, private val dialect: Any) : AutoCloseable {
        fun prepare() { invoke("prepare", dialect) }
        fun classLoader(): ClassLoader? = invoke("classLoader", dialect) as ClassLoader?
        override fun close() { invoke("close") }

        private fun invoke(name: String, vararg arguments: Any): Any? = try {
            instance.javaClass.methods.single { it.name == name }.invoke(instance, *arguments)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    private fun driverJar(includeDriver: Boolean = true, probe: String = "downloaded"): ByteArray =
        ByteArrayOutputStream().use { output ->
            JarOutputStream(output).use { jar ->
                if (includeDriver) {
                    val resource = Database.driverClass(Dialect.H2).replace('.', '/') + ".class"
                    jar.putNextEntry(JarEntry(resource))
                    javaClass.classLoader.getResourceAsStream(resource)!!.use { it.copyTo(jar) }
                    jar.closeEntry()
                }
                jar.putNextEntry(JarEntry(PROBE))
                jar.write(probe.toByteArray())
                jar.closeEntry()
            }
            output.toByteArray()
        }

    private fun repository(
        jar: ByteArray,
        checksum: String? = null,
        beforeJar: () -> Unit = {},
        declaredLength: Long = jar.size.toLong(),
        streamedJar: Path? = null,
    ): Repository {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                val path = exchange.requestURI.path
                requests.add(path)
                if (path.endsWith(".jar")) {
                    beforeJar()
                    exchange.sendResponseHeaders(200, declaredLength)
                    runCatching {
                        if (streamedJar == null) {
                            exchange.responseBody.write(jar)
                            // Keep a declared oversized response open long enough for the client
                            // to inspect headers instead of racing an undersized-body reset.
                            var remaining = declaredLength - jar.size
                            val padding = ByteArray(8192)
                            while (remaining > 0) {
                                val count = minOf(remaining, padding.size.toLong()).toInt()
                                exchange.responseBody.write(padding, 0, count)
                                remaining -= count
                            }
                        } else Files.newInputStream(streamedJar).use { it.copyTo(exchange.responseBody) }
                    }
                } else if (path.endsWith(".sha256") && checksum != null) {
                    val bytes = checksum.toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                } else {
                    exchange.sendResponseHeaders(404, -1)
                }
            }
        }
        server.start()
        return Repository(server, requests)
    }

    private class Repository(private val server: HttpServer, private val requests: List<String>) : AutoCloseable {
        val url: String get() = "http://127.0.0.1:${server.address.port}"
        fun jarRequests(): List<String> = synchronized(requests) { requests.filter { it.endsWith(".jar") } }
        override fun close() { server.stop(0) }
    }

    companion object {
        private const val PROBE = "kyokou-driver-probe.txt"
    }
}
