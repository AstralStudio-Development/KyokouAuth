package moe.ryuu.kyokouauth.core.authme

import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.storage.Database
import moe.ryuu.kyokouauth.core.storage.Dialect
import java.net.URI
import java.nio.CharBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Driver
import java.util.Properties
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads only bounded legacy fields; never creates a pool, changes a source schema or issues proof.
 * SQLite must be an administrator-supplied, immutable, consistent snapshot, not a live database.
 * Server sources additionally require a dedicated SELECT-only principal with trusted TLS roots.
 * The caller owns driverLoader and must close it only after in-flight verifications have finished.
 */
class AuthMeLegacyVerifier(
    private val config: AuthMeSourceConfig,
    private val driverLoader: ClassLoader? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val permits = Semaphore(4)
    private val sqlite = config.dialect == Dialect.SQLITE
    private val projection = buildString {
        // Project bounded strings before JDBC can materialize arbitrary legacy column contents.
        append("SELECT CASE WHEN INSTR(`${config.nameColumn}`, CHAR(0)) = 0 ")
        append("THEN SUBSTR(`${config.nameColumn}`, 1, 17) ELSE NULL END, ")
        append("CASE WHEN INSTR(`${config.passwordColumn}`, CHAR(0)) = 0 ")
        append("THEN SUBSTR(`${config.passwordColumn}`, 1, 257) ELSE NULL END, ")
        // SQLite LENGTH(TEXT) stops at NUL; a NUL-prefixed TOTP must not look disabled.
        append("CASE WHEN `${config.totpColumn}` IS NULL THEN 0 ")
        append("WHEN LENGTH(`${config.totpColumn}`) = 0 AND INSTR(`${config.totpColumn}`, CHAR(0)) = 0 ")
        append("THEN 0 ELSE 1 END ")
        append("FROM `${config.table}`")
    }
    private val select = "$projection WHERE LOWER(`${config.nameColumn}`) = ? LIMIT 2"

    /** Takes ownership of password and erases it on every path, including overload and close. */
    fun verify(name: GameName, password: CharArray): AuthMeVerification {
        var acquired = false
        try {
            if (closed.get()) return AuthMeVerification.UNAVAILABLE
            if (GameName.parse(name.value) == null || password.size !in 1..MAX_PASSWORD_CHARS) {
                return AuthMeVerification.REJECTED
            }
            acquired = permits.tryAcquire()
            if (!acquired || closed.get()) return AuthMeVerification.UNAVAILABLE
            val result = verifySource(name.key, password)
            return if (closed.get()) AuthMeVerification.UNAVAILABLE else result
        } catch (_: Exception) {
            // Never attach a JDBC/reflection exception as a cause or log its message.
            return AuthMeVerification.UNAVAILABLE
        } catch (_: LinkageError) {
            return AuthMeVerification.UNAVAILABLE
        } finally {
            password.fill('\u0000')
            if (acquired) permits.release()
        }
    }

    /** Reports only migration candidates and issues; this is never proof of ownership or a UUID. */
    fun inspectAccounts(maxAccounts: Int = 100_000): List<AuthMeAccount> {
        require(maxAccounts in 1..MAX_ACCOUNTS) { "Invalid AuthMe account limit" }
        var acquired = false
        try {
            check(!closed.get())
            acquired = permits.tryAcquire()
            check(acquired && !closed.get())
            val result = withSource { connection ->
                checkSchema(connection)
                readAccounts(connection, maxAccounts)
            }
            check(!closed.get())
            return result
        } catch (_: Exception) {
            throw IllegalStateException("Unable to inspect AuthMe source")
        } catch (_: LinkageError) {
            throw IllegalStateException("Unable to inspect AuthMe source")
        } finally {
            if (acquired) permits.release()
        }
    }

    /** Exports only bounded verification material, never identity/UUID evidence or source credentials. */
    fun exportSnapshot(
        destination: Path,
        snapshotDriverLoader: ClassLoader? = null,
        maxAccounts: Int = 100_000,
        current: () -> Boolean = { true },
        progress: (Int) -> Unit = {},
    ): Int {
        require(maxAccounts in 1..MAX_ACCOUNTS) { "Invalid AuthMe account limit" }
        var acquired = false
        var created = false
        var completed = false
        val target = destination.normalize()
        try {
            checkCurrent(current)
            acquired = permits.tryAcquire()
            check(acquired)
            check(destination.isAbsolute)
            val parent = target.parent
            check(parent != null && Files.isDirectory(parent, NOFOLLOW_LINKS))
            check(!Files.isSymbolicLink(parent))
            check(!Files.exists(target, NOFOLLOW_LINKS))
            check(SIDECAR_SUFFIXES.none { target.fileName.toString().endsWith(it) })
            check(SIDECAR_SUFFIXES.none { Files.exists(target.resolveSibling(target.fileName.toString() + it), NOFOLLOW_LINKS) })
            val count = withSource(streaming = true) { source ->
                checkCurrent(current)
                checkSchema(source)
                // Connecting to a legacy server may take time. Recheck before owning any files.
                check(Files.isDirectory(parent, NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent))
                check(SIDECAR_SUFFIXES.none { Files.exists(target.resolveSibling(target.fileName.toString() + it), NOFOLLOW_LINKS) })
                if (Files.getFileStore(parent).supportsFileAttributeView("posix")) {
                    Files.createFile(target, PosixFilePermissions.asFileAttribute(setOf(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    )))
                } else {
                    // Windows inherits the administrator-selected parent directory's ACL.
                    Files.createFile(target)
                }
                created = true
                writeSnapshot(source, target, snapshotDriverLoader, maxAccounts, current, progress)
            }
            checkCurrent(current)
            completed = true
            return count
        } catch (_: Exception) {
            throw IllegalStateException("Unable to export AuthMe snapshot")
        } catch (_: LinkageError) {
            throw IllegalStateException("Unable to export AuthMe snapshot")
        } finally {
            if (created && !completed) {
                // These names were absent before our exclusive CREATE_NEW; never clean another export.
                for (suffix in SIDECAR_SUFFIXES + "") {
                    try {
                        Files.deleteIfExists(target.resolveSibling(target.fileName.toString() + suffix))
                    } catch (_: Exception) {
                        // Keep cleanup failures and their potentially sensitive paths out of errors/logs.
                    }
                }
            }
            if (acquired) permits.release()
        }
    }

    private fun checkCurrent(current: () -> Boolean) {
        check(!closed.get() && current())
    }

    private fun writeSnapshot(
        source: Connection,
        target: Path,
        snapshotDriverLoader: ClassLoader?,
        maxAccounts: Int,
        current: () -> Boolean,
        progress: (Int) -> Unit,
    ): Int {
        val loader = snapshotDriverLoader ?: AuthMeLegacyVerifier::class.java.classLoader
        val driver = Class.forName(Database.driverClass(Dialect.SQLITE), true, loader)
            .getDeclaredConstructor().newInstance() as Driver
        val properties = Properties().apply {
            setProperty("open_mode", "66") // SQLITE_OPEN_READWRITE | SQLITE_OPEN_URI, never CREATE.
            setProperty("busy_timeout", "5000")
        }
        val url = "jdbc:sqlite:${target.toUri().toASCIIString()}?mode=rw"
        val connection = driver.connect(url, properties) ?: error("Snapshot unavailable")
        connection.use { snapshot ->
            snapshot.autoCommit = false
            try {
                snapshot.createStatement().use { statement ->
                    statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                    statement.execute("CREATE TABLE authme (username TEXT, password TEXT, totp TEXT)")
                }
                var count = 0
                snapshot.prepareStatement("INSERT INTO authme (username, password, totp) VALUES (?, ?, ?)").use { insert ->
                    insert.queryTimeout = QUERY_TIMEOUT_SECONDS
                    source.prepareStatement("$projection LIMIT ${maxAccounts + 1}").use { statement ->
                        statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                        statement.maxRows = maxAccounts + 1
                        if (!sqlite) statement.fetchSize = SNAPSHOT_BATCH_SIZE
                        statement.executeQuery().use { rows ->
                            while (rows.next()) {
                                checkCurrent(current)
                                check(count < maxAccounts)
                                val name = rows.getString(1)?.let(GameName::parse)?.value
                                val hash = rows.getString(2)
                                val hasTotp = rows.getInt(3) != 0 || rows.wasNull()
                                insert.setString(1, name)
                                insert.setString(2, hash?.takeIf { !hasTotp && SHA_FORMAT.matches(it) })
                                insert.setString(3, if (hasTotp) "required" else "")
                                insert.addBatch()
                                count++
                                if (count % SNAPSHOT_BATCH_SIZE == 0) {
                                    insert.executeBatch()
                                    insert.clearBatch()
                                    progress(count)
                                }
                            }
                        }
                    }
                    if (count % SNAPSHOT_BATCH_SIZE != 0) insert.executeBatch()
                }
                snapshot.createStatement().use { statement ->
                    statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                    statement.execute("CREATE INDEX authme_username_lower ON authme (LOWER(username))")
                }
                if (count == 0 || count % SNAPSHOT_BATCH_SIZE != 0) progress(count)
                checkCurrent(current)
                snapshot.commit()
                return count
            } finally {
                // Rollback is harmless after commit and mandatory after any failed/cancelled batch.
                snapshot.rollback()
            }
        }
    }

    private fun verifySource(key: String, password: CharArray): AuthMeVerification =
        withSource { readAccount(it, key, password) }

    private fun <T> withSource(streaming: Boolean = false, read: (Connection) -> T): T {
        val properties = Properties()
        val url = if (sqlite) sqliteUrl(properties) else serverUrl(properties)
        if (streaming && config.dialect == Dialect.MYSQL) properties.setProperty("useCursorFetch", "true")
        val loader = driverLoader ?: AuthMeLegacyVerifier::class.java.classLoader
        val driver = Class.forName(Database.driverClass(config.dialect), true, loader)
            .getDeclaredConstructor().newInstance() as Driver
        // Explicit Driver.connect, not DriverManager or Database: neither registration nor migration.
        val connection = driver.connect(url, properties) ?: error("AuthMe source unavailable")
        connection.use {
            try {
                configureReadOnly(connection)
                return read(connection)
            } finally {
                if (!sqlite) connection.rollback()
            }
        }
    }

    private fun sqliteUrl(properties: Properties): String {
        val path = Path.of(config.location)
        require(path.isAbsolute && Files.isRegularFile(path, NOFOLLOW_LINKS))
        require(!Files.isSymbolicLink(path))
        require(listOf("-wal", "-shm", "-journal").none { path.fileName.toString().endsWith(it) })
        require(listOf("-wal", "-shm", "-journal").none {
            // A broken symlink is a sidecar too. Never delete or create one.
            Files.exists(path.resolveSibling(path.fileName.toString() + it), NOFOLLOW_LINKS)
        })
        properties.setProperty("open_mode", "65") // SQLITE_OPEN_READONLY | SQLITE_OPEN_URI, never CREATE.
        properties.setProperty("busy_timeout", "5000")
        // toUri escapes ?, # and percent characters in actual filenames, preventing URI injection.
        return "jdbc:sqlite:${path.toUri().toASCIIString()}?mode=ro&immutable=1"
    }

    private fun serverUrl(properties: Properties): String {
        val prefix = if (config.dialect == Dialect.MYSQL) "jdbc:mysql://" else "jdbc:mariadb://"
        require(config.location.startsWith(prefix))
        val uri = URI(config.location.removePrefix("jdbc:"))
        require(uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null)
        require(uri.port == -1 || uri.port in 1..65535)
        require(DATABASE_PATH.matches(uri.rawPath.orEmpty()))
        val options = mutableMapOf<String, String>()
        val rawQuery = uri.rawQuery ?: error("Missing source TLS policy")
        for (pair in rawQuery.split('&')) {
            require(URL_OPTION.matches(pair))
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=')
            require(options.put(key, value) == null)
            require(key in URL_OPTIONS)
        }
        val tls = if (config.dialect == Dialect.MYSQL) "VERIFY_IDENTITY" else "verify-full"
        require(options["sslMode"] == tls)
        for (option in listOf("connectTimeout", "socketTimeout")) {
            options[option]?.let { value ->
                require(value.toIntOrNull()?.let { it in 1..MAX_TIMEOUT_MS } == true)
            }
        }
        for (option in listOf("allowMultiQueries", "autoReconnect")) {
            options[option]?.let { require(it == "false") }
        }
        properties.setProperty("sslMode", tls)
        properties.setProperty("user", config.username)
        properties.setProperty("password", config.password)
        properties.setProperty("connectTimeout", "5000")
        properties.setProperty("socketTimeout", "5000")
        properties.setProperty("allowMultiQueries", "false")
        properties.setProperty("autoReconnect", "false")
        properties.setProperty("useServerPrepStmts", "true")
        if (config.dialect == Dialect.MYSQL) {
            properties.setProperty("readOnlyPropagatesToServer", "true")
            properties.setProperty("allowLoadLocalInfile", "false")
            properties.setProperty("allowUrlInLocalInfile", "false")
            properties.setProperty("allowPublicKeyRetrieval", "false")
            properties.setProperty("paranoid", "true")
        } else {
            properties.setProperty("allowLocalInfile", "false")
        }
        return config.location
    }

    private fun configureReadOnly(connection: Connection) {
        if (sqlite) {
            // Xerial cannot change a connection's readOnly flag after opening it. open_mode and URI
            // established it before connect; query_only is an additional connection-local guard.
            check(connection.isReadOnly)
            connection.createStatement().use { statement ->
                statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                statement.execute("PRAGMA query_only=ON")
                statement.executeQuery("PRAGMA query_only").use { rows ->
                    check(rows.next() && rows.getInt(1) == 1)
                }
            }
        } else {
            connection.isReadOnly = true
            check(connection.isReadOnly)
            connection.createStatement().use { statement ->
                statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                // A driver timeout alone does not bound work left running on the server.
                statement.execute(if (config.dialect == Dialect.MYSQL) {
                    "SET SESSION max_execution_time=5000"
                } else {
                    "SET SESSION max_statement_time=5"
                })
            }
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                statement.execute("START TRANSACTION READ ONLY")
            }
        }
    }

    private fun checkSchema(connection: Connection) {
        val columns = listOf(config.nameColumn, config.passwordColumn, config.totpColumn)
        check(columns.map { it.lowercase(java.util.Locale.ROOT) }.toSet().size == columns.size)
        // Resolve exactly the configured identifiers, even for an empty source. Never guess a
        // similarly named column, silently omit TOTP, or read arbitrary account values as metadata.
        val sql = "SELECT ${columns.joinToString(", ") { "`$it`" }} FROM `${config.table}` WHERE 1=0"
        connection.prepareStatement(sql).use { statement ->
            statement.queryTimeout = QUERY_TIMEOUT_SECONDS
            statement.maxRows = 1
            statement.executeQuery().use { rows ->
                val metadata = rows.metaData
                check(metadata.columnCount == columns.size)
                columns.forEachIndexed { index, name ->
                    check(name.equals(metadata.getColumnName(index + 1), ignoreCase = true))
                }
                check(!rows.next())
            }
        }
    }

    private fun readAccounts(connection: Connection, maxAccounts: Int): List<AuthMeAccount> {
        val accounts = ArrayList<AuthMeAccount>()
        val seenNames = HashMap<String, Int>()
        connection.prepareStatement("$projection LIMIT ${maxAccounts + 1}").use { statement ->
            statement.queryTimeout = QUERY_TIMEOUT_SECONDS
            statement.maxRows = maxAccounts + 1
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    // Reject the entire scan rather than handing out an apparently complete prefix.
                    check(accounts.size < maxAccounts)
                    val name = rows.getString(1)
                    val hash = rows.getString(2)
                    val hasTotp = rows.getInt(3) != 0 || rows.wasNull()
                    val gameName = name?.let(GameName::parse)
                    var issue = when {
                        gameName == null -> AuthMeAccountIssue.INVALID_NAME
                        hasTotp -> AuthMeAccountIssue.TOTP_REQUIRED
                        hash == null || !SHA_FORMAT.matches(hash) -> AuthMeAccountIssue.UNSUPPORTED_HASH
                        else -> null
                    }
                    if (gameName != null) {
                        val previous = seenNames.putIfAbsent(gameName.key, accounts.size)
                        if (previous != null) {
                            accounts[previous] = accounts[previous].copy(issue = AuthMeAccountIssue.DUPLICATE_NAME)
                            issue = AuthMeAccountIssue.DUPLICATE_NAME
                        }
                    }
                    // Never return raw invalid names (control characters or truncated source data).
                    accounts.add(AuthMeAccount(gameName?.value, issue))
                }
            }
        }
        return accounts
    }

    private fun readAccount(connection: Connection, key: String, password: CharArray): AuthMeVerification {
        return connection.prepareStatement(select).use { statement ->
            statement.queryTimeout = QUERY_TIMEOUT_SECONDS
            statement.maxRows = 2
            statement.setString(1, key)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return AuthMeVerification.REJECTED
                val actualName = rows.getString(1)
                val hash = rows.getString(2)
                val hasTotp = rows.getInt(3) != 0 || rows.wasNull()
                // Database collations may ignore whitespace/accents. Recheck exact GameName semantics.
                if (actualName == null || GameName.parse(actualName)?.key != key || rows.next()) {
                    return AuthMeVerification.UNSUPPORTED
                }
                if (hasTotp || hash == null || !SHA_FORMAT.matches(hash)) {
                    return AuthMeVerification.UNSUPPORTED
                }
                if (matches(password, hash)) AuthMeVerification.VERIFIED else AuthMeVerification.REJECTED
            }
        }
    }

    private fun matches(password: CharArray, storedHash: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val inner = hash(digest, password)
        val material = CharArray(80)
        val expected = ByteArray(32)
        var actual: ByteArray? = null
        try {
            for (index in inner.indices) {
                val value = inner[index].toInt() and 0xff
                material[index * 2] = HEX[value ushr 4]
                material[index * 2 + 1] = HEX[value and 15]
            }
            // Strict format is $SHA$ + 16 lowercase hexadecimal salt + $ + 64 hex digest.
            storedHash.toCharArray(material, 64, 5, 21)
            for (index in expected.indices) {
                expected[index] = ((storedHash[22 + index * 2].digitToInt(16) shl 4) or
                    storedHash[23 + index * 2].digitToInt(16)).toByte()
            }
            actual = hash(digest, material)
            return MessageDigest.isEqual(expected, actual)
        } finally {
            inner.fill(0)
            material.fill('\u0000')
            expected.fill(0)
            actual?.fill(0)
        }
    }

    private fun hash(digest: MessageDigest, characters: CharArray): ByteArray {
        // Charset.encode mirrors String.getBytes replacement semantics without an immutable password.
        val bytes = config.charset.encode(CharBuffer.wrap(characters))
        try {
            digest.update(bytes)
            return digest.digest()
        } finally {
            if (bytes.hasArray()) bytes.array().fill(0)
        }
    }

    /** Already-running bounded reads finish cleanup, but can no longer return VERIFIED. */
    override fun close() {
        closed.set(true)
    }

    override fun toString(): String = "AuthMeLegacyVerifier(source=<redacted>)"

    companion object {
        private const val SNAPSHOT_BATCH_SIZE = 256
        private val SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")
        private const val MAX_ACCOUNTS = 100_000
        private const val MAX_PASSWORD_CHARS = 1024
        private const val QUERY_TIMEOUT_SECONDS = 5
        private const val MAX_TIMEOUT_MS = 10_000
        private const val HEX = "0123456789abcdef"
        private val SHA_FORMAT = Regex("\\\$SHA\\\$[0-9a-f]{16}\\\$[0-9a-f]{64}")
        private val DATABASE_PATH = Regex("/[A-Za-z0-9_][A-Za-z0-9_-]{0,63}")
        private val URL_OPTION = Regex("[A-Za-z][A-Za-z0-9]*=[A-Za-z0-9_-]+")
        private val URL_OPTIONS = setOf("sslMode", "connectTimeout", "socketTimeout", "allowMultiQueries", "autoReconnect")
    }
}

/** A validated source name (null if invalid); a null issue means only a verification candidate. */
data class AuthMeAccount(val name: String?, val issue: AuthMeAccountIssue?)

enum class AuthMeAccountIssue {
    INVALID_NAME,
    UNSUPPORTED_HASH,
    TOTP_REQUIRED,
    DUPLICATE_NAME,
}
