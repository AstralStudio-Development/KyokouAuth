package moe.ryuu.kyokouauth.core.storage

/**
 * The five backends share one schema. Everything that differs between them is confined here so
 * that no repository ever has to branch on the backend.
 */
enum class Dialect(
    val displayName: String,
    /** JDBC subprotocol, used to build the default connection string. */
    val jdbcPrefix: String,
    /** Column type for a 64-bit integer. Identical everywhere, kept explicit for clarity. */
    val bigint: String = "BIGINT",
    /** Column type for bounded text. */
    val text: (Int) -> String = { "VARCHAR($it)" },
) {
    SQLITE("SQLite", "jdbc:sqlite:", text = { "TEXT" }),
    H2("H2", "jdbc:h2:", text = { "VARCHAR($it)" }),
    MYSQL("MySQL", "jdbc:mysql:", text = { "VARCHAR($it)" }),
    MARIADB("MariaDB", "jdbc:mariadb:", text = { "VARCHAR($it)" }),
    POSTGRESQL("PostgreSQL", "jdbc:postgresql:", text = { "VARCHAR($it)" });

    /** SQLite and H2 default to a single writer; the server pools behave normally. */
    val embedded: Boolean get() = this == SQLITE || this == H2

    /**
     * `CREATE TABLE IF NOT EXISTS` is supported by all five, which is why schema setup never needs
     * to query the catalogue first.
     */
    fun createTableStatement(table: String, columns: List<Pair<String, String>>, constraints: List<String>): String {
        val body = buildList {
            columns.forEach { (name, type) -> add("$name $type") }
            addAll(constraints)
        }.joinToString(", ")
        return "CREATE TABLE IF NOT EXISTS $table ($body)"
    }

    /**
     * An index needs a name and `IF NOT EXISTS` support. PostgreSQL has the latter since 9.5, MySQL
     * never had it — MySQL is handled by declaring its unique keys inline instead of as indexes.
     */
    fun supportsCreateIndexIfNotExists(): Boolean = this != MYSQL && this != MARIADB

    companion object {
        fun of(name: String?): Dialect? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** Where the connection comes from. Kept separate so the driver can be loaded before it is used. */
data class DatabaseDescriptor(
    val dialect: Dialect,
    val host: String = "127.0.0.1",
    val port: Int = defaultPort(dialect),
    val database: String = "kyokouauth",
    val username: String = "",
    val password: String = "",
    val properties: Map<String, String> = emptyMap(),
) {
    fun jdbcUrl(): String = when (dialect) {
        Dialect.SQLITE -> "jdbc:sqlite:$database"
        // H2 treats a leading `./` as "relative to the working directory". Prepending it to an
        // absolute path produces `./C:/...`, which H2 rejects outright rather than normalising.
        Dialect.H2 -> "jdbc:h2:" + (if (java.io.File(database).isAbsolute) database else "./$database") +
            ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
        Dialect.MYSQL -> "jdbc:mysql://$host:$port/$database?useSSL=false&characterEncoding=utf8&serverTimezone=UTC"
        Dialect.MARIADB -> "jdbc:mariadb://$host:$port/$database?useSsl=false"
        Dialect.POSTGRESQL -> "jdbc:postgresql://$host:$port/$database"
    }

    companion object {
        private fun defaultPort(dialect: Dialect): Int = when (dialect) {
            Dialect.MYSQL, Dialect.MARIADB -> 3306
            Dialect.POSTGRESQL -> 5432
            Dialect.H2 -> 9092
            Dialect.SQLITE -> 0
        }
    }
}
