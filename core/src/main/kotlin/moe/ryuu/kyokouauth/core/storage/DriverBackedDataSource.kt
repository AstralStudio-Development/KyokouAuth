package moe.ryuu.kyokouauth.core.storage

import java.io.PrintWriter
import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.Properties
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * A `DataSource` over a single already-instantiated JDBC [Driver].
 *
 * HikariCP has two ways to reach a driver: name one in `driverClassName`, which it resolves with
 * `Class.forName` on its own classloader, or be handed a `DataSource`. The drivers are downloaded at
 * runtime into a separate classloader that HikariCP knows nothing about, so the second is the only
 * option. It costs this small adapter and removes a whole class of "driver not found" failures.
 */
internal class DriverBackedDataSource(
    private val driver: Driver,
    private val url: String,
    descriptor: DatabaseDescriptor,
) : DataSource {

    private val properties = Properties().apply {
        if (descriptor.username.isNotEmpty()) setProperty("user", descriptor.username)
        if (descriptor.password.isNotEmpty()) setProperty("password", descriptor.password)
        descriptor.properties.forEach { (key, value) -> setProperty(key, value) }
    }

    @Throws(SQLException::class)
    override fun getConnection(): Connection =
        driver.connect(url, properties)
            ?: throw SQLException("Driver ${driver.javaClass.name} does not accept $url")

    @Throws(SQLException::class)
    override fun getConnection(username: String?, password: String?): Connection {
        val credentials = Properties().apply {
            putAll(properties)
            if (username != null) setProperty("user", username)
            if (password != null) setProperty("password", password)
        }
        return driver.connect(url, credentials)
            ?: throw SQLException("Driver ${driver.javaClass.name} does not accept $url")
    }

    override fun getLogWriter(): PrintWriter? = null

    override fun setLogWriter(out: PrintWriter?) = Unit

    override fun setLoginTimeout(seconds: Int) = Unit

    override fun getLoginTimeout(): Int = 0

    override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> unwrap(iface: Class<T>): T =
        if (iface.isInstance(this)) this as T else throw SQLException("Not a wrapper for ${iface.name}")

    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}
