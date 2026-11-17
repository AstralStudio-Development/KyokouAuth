package moe.ryuu.kyokouauth.core.storage

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.jar.JarFile
import java.util.zip.CRC32
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Where the JDBC driver for a dialect comes from, and why it is not in the jar.
 *
 * Bundling all five drivers costs about 20 MB - `sqlite-jdbc` alone is 13 MB because it carries
 * native binaries for every platform - which would be most of the plugin's download for a database
 * the server may never use. They live outside the jar instead.
 *
 * Every pinned version is one this project's test matrix has actually run against. They are not
 * the newest releases on purpose: a driver is the one dependency whose behaviour cannot be faked,
 * so bumping one means re-running the embedded dialect suite and a real server for each backend.
 */
object DriverCatalog {

    class Coordinates(
        val group: String,
        val artifact: String,
        val version: String,
    ) {
        /** Path of the jar inside a Maven repository, which is also its layout under `libraries/`. */
        val repositoryPath: String
            get() = "${group.replace('.', '/')}/$artifact/$version/$artifact-$version.jar"

        override fun toString(): String = "$group:$artifact:$version"
    }

    fun of(dialect: Dialect): Coordinates = when (dialect) {
        Dialect.SQLITE -> Coordinates("org.xerial", "sqlite-jdbc", "3.45.3.0")
        Dialect.H2 -> Coordinates("com.h2database", "h2", "1.4.200")
        Dialect.MYSQL -> Coordinates("com.mysql", "mysql-connector-j", "8.4.0")
        Dialect.MARIADB -> Coordinates("org.mariadb.jdbc", "mariadb-java-client", "3.3.3")
        Dialect.POSTGRESQL -> Coordinates("org.postgresql", "postgresql", "42.7.4")
    }

}

/**
 * Prepares only the selected backend in the server's Maven-layout `libraries/` folder.
 * Platform drivers and manually installed jars take priority; existing files are never replaced.
 * The owner must close its database pool before closing this library's own jar loaders.
 */
class DriverLibrary(
    private val librariesFolder: File,
    private val repository: String,
    private val allowDownload: Boolean,
    private val notice: (String) -> Unit,
) : AutoCloseable {
    private val lifecycle = ReentrantReadWriteLock(true)
    private val loaders = ConcurrentHashMap<Dialect, URLClassLoader>()
    private var closed = false

    class DriverUnavailable(message: String) : Exception(message)

    /** Fail startup immediately when the selected driver cannot be prepared. */
    fun prepare(dialect: Dialect) {
        classLoader(dialect)
    }

    /** Null means the platform already provides the driver; that loader is never owned here. */
    fun classLoader(dialect: Dialect): ClassLoader? = lifecycle.read {
        check(!closed) { "DriverLibrary is closed" }
        synchronized(coordinateLocks.getValue(dialect)) {
            val driverName = Database.driverClass(dialect)
            try {
                Class.forName(driverName, false, DriverLibrary::class.java.classLoader)
                return@read null
            } catch (_: ClassNotFoundException) {
                // Only an absent driver justifies downloading one; linkage failures must surface.
            }
            loaders[dialect]?.let { return@read it }

            val coordinates = DriverCatalog.of(dialect)
            val jar = File(librariesFolder, coordinates.repositoryPath).toPath()
            if (!Files.exists(jar)) {
                if (!allowDownload) {
                    throw DriverUnavailable(
                        "The ${dialect.displayName} driver is missing from $jar and automatic " +
                            "download is disabled. Place ${jar.fileName} there.",
                    )
                }
                download(dialect, coordinates, jar)
            }
            validateJar(jar, driverName)
            URLClassLoader(arrayOf(jar.toUri().toURL()), DriverLibrary::class.java.classLoader).also {
                loaders[dialect] = it
            }
        }
    }

    override fun close() = lifecycle.write {
        if (closed) return@write
        closed = true
        var failure: Throwable? = null
        loaders.values.forEach { loader ->
            try {
                loader.close()
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
        }
        loaders.clear()
        failure?.let { throw it }
        Unit
    }

    private fun download(dialect: Dialect, coordinates: DriverCatalog.Coordinates, target: Path) {
        // Do not log repository URLs or transport exception messages: either can contain credentials.
        notice("下载 $coordinates")
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2)
        var temporary: Path? = null
        var primary: Throwable? = null
        try {
            Files.createDirectories(target.parent)
            temporary = Files.createTempFile(target.parent, "${target.fileName}.", ".part")
            val connection = connection(repositoryUrl(coordinates.repositoryPath), deadline)
            try {
                requireSuccess(connection)
                if (connection.contentLengthLong > MAX_DOWNLOAD_BYTES) {
                    throw DriverUnavailable("Driver download exceeds the $MAX_DOWNLOAD_BYTES byte limit for $coordinates")
                }
                connection.inputStream.use { input ->
                    Files.newOutputStream(temporary).use { output ->
                        copyBounded(input, output, MAX_DOWNLOAD_BYTES, deadline)
                    }
                }
            } finally {
                connection.disconnect()
            }
            validateJar(temporary, Database.driverClass(dialect), deadline)
            verifyChecksum(coordinates, temporary, deadline)
            checkDeadline(deadline)

            try {
                // ATOMIC_MOVE may overwrite an existing target on some providers, even without
                // REPLACE_EXISTING. A same-directory hard link atomically publishes complete bytes
                // and fails if an operator installed a jar meanwhile. Never fall back to copying.
                Files.createLink(target, temporary)
            } catch (_: FileAlreadyExistsException) {
                validateJar(target, Database.driverClass(dialect), deadline)
            }
        } catch (failure: Throwable) {
            val safe = if (failure is Exception && failure !is DriverUnavailable) {
                DriverUnavailable(
                    "无法准备 $coordinates 驱动（${failure.javaClass.simpleName}）。" +
                        "请将 ${target.fileName} 放到 ${target.parent}，或检查 database.driver-repository。",
                )
            } else failure
            primary = safe
            throw safe
        } finally {
            temporary?.let { part ->
                try {
                    Files.deleteIfExists(part)
                } catch (cleanup: Exception) {
                    if (primary != null) primary.addSuppressed(cleanup)
                    else throw DriverUnavailable("Cannot remove temporary driver file ${part.fileName}")
                }
            }
        }
    }

    private fun repositoryUrl(path: String): URL {
        val base = URI(repository)
        require(base.scheme.equals("https", true) || base.scheme.equals("http", true)) {
            "Driver repository must use HTTP or HTTPS"
        }
        return URI(base.scheme, base.userInfo, base.host, base.port, base.path.orEmpty().trimEnd('/') + "/" + path, base.query, null).toURL()
    }

    private fun connection(url: URL, deadline: Long): HttpURLConnection {
        checkDeadline(deadline)
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceIn(1L, SOCKET_TIMEOUT_MS.toLong()).toInt()
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = remaining
            readTimeout = remaining
        }
    }

    private fun requireSuccess(connection: HttpURLConnection) {
        val status = connection.responseCode
        if (status !in 200..299) throw DriverUnavailable("Driver repository returned HTTP $status")
    }

    /** Mirror sidecars provide integrity, not a substitute for HTTPS or a trusted repository. */
    private fun verifyChecksum(coordinates: DriverCatalog.Coordinates, jar: Path, deadline: Long) {
        for ((algorithm, extension, length) in CHECKSUMS) {
            val connection = connection(repositoryUrl(coordinates.repositoryPath + extension), deadline)
            val checksum = try {
                if (connection.responseCode in setOf(404, 410)) continue
                requireSuccess(connection)
                val output = ByteArrayOutputStream()
                connection.inputStream.use { copyBounded(it, output, 512, deadline) }
                output.toString(Charsets.US_ASCII).trim().takeWhile { !it.isWhitespace() }
            } finally {
                connection.disconnect()
            }
            if (checksum.length != length || checksum.any { it.digitToIntOrNull(16) == null }) {
                throw DriverUnavailable("Invalid $algorithm checksum for $coordinates")
            }
            val digest = MessageDigest.getInstance(algorithm)
            Files.newInputStream(jar).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    checkDeadline(deadline)
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(checksum, true)) throw DriverUnavailable("$algorithm checksum mismatch for $coordinates")
            return
        }
        // Some legitimate Maven mirrors do not publish SHA-256/SHA-512 sidecars. Do not pin an
        // invented hash or require weaker SHA-1; structure and driver-class validation still apply.
    }

    private fun validateJar(path: Path, driverName: String, deadline: Long = Long.MAX_VALUE) {
        try {
            JarFile(path.toFile(), true).use { jar ->
                val driver = jar.getJarEntry(driverName.replace('.', '/') + ".class")
                    ?: throw DriverUnavailable("Driver jar ${path.fileName} does not contain $driverName")
                if (driver.isDirectory || !jar.getInputStream(driver).use { it.readNBytes(4) }.contentEquals(CLASS_MAGIC)) {
                    throw DriverUnavailable("Driver jar ${path.fileName} contains an invalid driver class")
                }
                val names = HashSet<String>()
                val entries = jar.entries()
                val buffer = ByteArray(8192)
                var expanded = 0L
                while (entries.hasMoreElements()) {
                    checkDeadline(deadline)
                    val entry = entries.nextElement()
                    if (!names.add(entry.name) || names.size > 50_000) {
                        throw DriverUnavailable("Driver jar ${path.fileName} has invalid or excessive entries")
                    }
                    if (entry.isDirectory) continue
                    val crc = CRC32()
                    jar.getInputStream(entry).use { input ->
                        while (true) {
                            checkDeadline(deadline)
                            val count = input.read(buffer)
                            if (count < 0) break
                            expanded += count
                            if (expanded > MAX_EXPANDED_BYTES) throw DriverUnavailable("Driver jar exceeds the expanded byte limit")
                            crc.update(buffer, 0, count)
                        }
                    }
                    if (crc.value != entry.crc) throw DriverUnavailable("Driver jar ${path.fileName} has an invalid CRC")
                }
            }
        } catch (failure: Exception) {
            if (failure is DriverUnavailable) throw failure
            throw DriverUnavailable("Invalid driver jar ${path.fileName} (${failure.javaClass.simpleName})")
        }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, limit: Long, deadline: Long) {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            checkDeadline(deadline)
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw DriverUnavailable("Driver download exceeds the $limit byte limit")
            output.write(buffer, 0, count)
        }
        checkDeadline(deadline)
    }

    private fun checkDeadline(deadline: Long) {
        if (Thread.currentThread().isInterrupted) throw DriverUnavailable("Driver preparation was interrupted")
        if (deadline != Long.MAX_VALUE && System.nanoTime() - deadline >= 0) {
            throw DriverUnavailable("Driver preparation exceeded its two minute deadline")
        }
    }

    companion object {
        // The catalog has one pinned coordinate per dialect; share five locks across library
        // instances without retaining an unbounded set of server-directory paths.
        private val coordinateLocks = Dialect.entries.associateWith { Any() }

        // sqlite-jdbc is roughly 13 MiB; leave room without accepting unbounded mirror responses.
        private const val MAX_DOWNLOAD_BYTES = 32L * 1024L * 1024L
        private const val MAX_EXPANDED_BYTES = 128L * 1024L * 1024L
        private const val SOCKET_TIMEOUT_MS = 15_000
        private val CLASS_MAGIC = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
        private val CHECKSUMS = listOf(Triple("SHA-256", ".sha256", 64), Triple("SHA-512", ".sha512", 128))
    }
}
