package moe.ryuu.kyokouauth.core.storage

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the JDBC driver for a dialect comes from, and why it is not in the jar.
 *
 * Bundling all five drivers costs about 20 MB - `sqlite-jdbc` alone is 13 MB because it carries
 * native binaries for every platform - which would be most of the plugin's download for a database
 * the server may never use. They live outside the jar instead.
 *
 * Every pinned version is the last one that still runs on **Java 8**, because the plugin's bytecode
 * floor is Java 8 (see ADR-0001) and a server on 1.12 is very likely on a Java 8 runtime. That is why
 * H2 is 1.4.200 rather than 2.x: 2.x requires Java 11.
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

    val all: List<Coordinates> get() = Dialect.entries.map(::of)
}

/**
 * Puts the JDBC drivers in the server's own `libraries/` folder, the same Maven layout Paper uses.
 *
 * ## Why the server's folder and not the plugin's
 *
 * `libraries/` is where every other dependency on this server already lives. A driver sitting there
 * is findable by a human, survives the plugin being replaced, and does not turn the plugin data
 * folder into a cache.
 *
 * ## Why everything is fetched at startup
 *
 * Downloading on first *use* means an operator who switches `database.type` months later waits for a
 * download in the middle of a restart, with no warning. Fetching all five once at startup costs the
 * same total bandwidth in the common case (only the ones missing are fetched) and never surprises
 * anyone later.
 *
 * A jar placed in `libraries/` by hand is indistinguishable from a downloaded one and is preferred:
 * nothing here overwrites an existing file, so an offline server only needs the jars.
 */
class DriverLibrary(
    private val librariesFolder: File,
    private val repository: String,
    private val allowDownload: Boolean,
    private val notice: (String) -> Unit,
) {

    private val loaders = ConcurrentHashMap<String, ClassLoader>()

    class DriverUnavailable(message: String) : Exception(message)

    /**
     * Makes sure every driver is present, downloading the missing ones.
     *
     * Failures are reported but do not throw: a server using SQLite should still start if the
     * PostgreSQL mirror is unreachable. The error resurfaces if that backend is actually selected.
     */
    fun prefetch() {
        if (!allowDownload) return

        Dialect.entries.forEach { dialect ->
            val coordinates = DriverCatalog.of(dialect)
            val target = file(coordinates)

            // Already usable without our help: Paper bundles sqlite and MySQL drivers in its own
            // libraries folder, and other plugins may shade one. Downloading a second copy would be
            // 15 MB spent on a jar nothing loads.
            if (runCatching { Class.forName(Database.driverClass(dialect)) }.isSuccess) {
                notice("${dialect.displayName} 驱动已由服务端提供，跳过下载")
                return@forEach
            }
            if (target.isFile) return@forEach

            runCatching { download(coordinates, target) }
                .onFailure { notice(it.message ?: "could not fetch $coordinates") }
        }
    }

    /**
     * @return a classloader that sees the driver, or null when it is already on the plugin's own
     *   classpath - the usual case when Paper's `plugin.yml` `libraries` or the server bundle
     *   already provides it.
     */
    fun classLoader(dialect: Dialect): ClassLoader? {
        val driverName = Database.driverClass(dialect)

        // Already visible? Paper bundles some drivers and any plugin may have shaded one.
        if (runCatching { Class.forName(driverName) }.isSuccess) return null

        val coordinates = DriverCatalog.of(dialect)
        loaders[coordinates.repositoryPath]?.let { return it }

        val jar = file(coordinates)
        if (!jar.isFile) {
            if (!allowDownload) {
                throw DriverUnavailable(
                    "The ${dialect.displayName} driver is missing from ${jar.absolutePath} and automatic " +
                        "download is disabled. Place ${jar.name} there.",
                )
            }
            download(coordinates, jar)
        }

        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), DriverLibrary::class.java.classLoader)
        loaders[coordinates.repositoryPath] = loader
        return loader
    }

    private fun file(coordinates: DriverCatalog.Coordinates): File =
        File(librariesFolder, coordinates.repositoryPath)

    private fun download(coordinates: DriverCatalog.Coordinates, target: File) {
        val url = repository.trimEnd('/') + "/" + coordinates.repositoryPath
        notice("下载 $coordinates <- $url")

        target.parentFile.mkdirs()

        // Written to a temporary name first: a half-written jar left behind by a crash would be
        // picked up as "already downloaded" on the next start and fail in a way nobody can diagnose.
        val temporary = File(target.parentFile, target.name + ".part")
        try {
            val connection = URL(url).openConnection()
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.getInputStream().use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }

            // Some mirrors answer 200 with an HTML error page, so the result is checked rather than
            // trusted.
            if (temporary.length() < 1024 || !looksLikeJar(temporary)) {
                throw DriverUnavailable("$url did not return a jar (a mirror serving HTML?)")
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        } catch (failure: Exception) {
            temporary.delete()
            throw DriverUnavailable(
                "无法从 $url 下载 ${coordinates.artifact} 驱动（${failure.message}）。" +
                    "请把 ${target.name} 放到 ${librariesFolder.absolutePath}，" +
                    "或把 database.driver-repository 换成可访问的镜像。",
            )
        }
    }

    /** A jar is a zip; the first two bytes decide it cheaply. */
    private fun looksLikeJar(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val header = ByteArray(2)
            stream.read(header) == 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)
}
