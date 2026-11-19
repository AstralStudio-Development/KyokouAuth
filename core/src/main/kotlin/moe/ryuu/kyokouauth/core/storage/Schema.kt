package moe.ryuu.kyokouauth.core.storage

/** One step of the schema history. Applied once and never edited after it has shipped. */
internal class Migration(val version: Int, val statements: (Dialect) -> List<String>)

/**
 * The schema, expressed in a single portable form.
 *
 * Rules that would otherwise live only in Kotlin are enforced here as well, because two plugins
 * writing to the same table - or one plugin and an impatient admin - must not be able to create the
 * states the domain forbids. In particular:
 *
 *  - `kyokouauth_name.name_key` is the primary key, so a name cannot be held twice.
 *  - `kyokouauth_name.player_uuid` is unique, so one identity cannot hold two names.
 *  - `kyokouauth_identity (kind, subject)` is unique, so one XUID or Mojang UUID maps to one row.
 */
internal object Schema {

    const val IDENTITY = "kyokouauth_identity"
    const val NAME = "kyokouauth_name"
    const val SESSION = "kyokouauth_session"
    const val META = "kyokouauth_meta"
    const val BEDROCK_LINK = "kyokouauth_bedrock_link"
    const val ACCOUNT_LINK = "kyokouauth_account_link"
    const val LEGACY_PROFILE = "kyokouauth_legacy_profile"
    const val PROFILE_OWNER = "kyokouauth_profile_owner"
    const val CLAIM_GRANT = "kyokouauth_claim_grant"
    const val PROFILE_CLAIM_AUDIT = "kyokouauth_profile_claim_audit"

    val migrations: List<Migration> = listOf(
        Migration(1) { dialect ->
            listOf(
                dialect.createTableStatement(
                    META,
                    listOf(
                        "meta_key" to dialect.text(64),
                        "meta_value" to dialect.text(255),
                    ),
                    listOf("PRIMARY KEY (meta_key)"),
                ),
                dialect.createTableStatement(
                    IDENTITY,
                    listOf(
                        "player_uuid" to dialect.text(36),
                        "kind" to dialect.text(16),
                        "subject" to dialect.text(64),
                        "gamertag" to dialect.text(64),
                        "created_at" to dialect.bigint,
                        "last_seen_at" to dialect.bigint,
                    ),
                    listOf(
                        "PRIMARY KEY (player_uuid)",
                        "CONSTRAINT uq_${IDENTITY}_subject UNIQUE (kind, subject)",
                    ),
                ),
                dialect.createTableStatement(
                    NAME,
                    listOf(
                        "name_key" to dialect.text(16),
                        "display_name" to dialect.text(16),
                        "player_uuid" to dialect.text(36),
                        "claimed_at" to dialect.bigint,
                    ),
                    listOf(
                        "PRIMARY KEY (name_key)",
                        "CONSTRAINT uq_${NAME}_player UNIQUE (player_uuid)",
                    ),
                ),
                dialect.createTableStatement(
                    SESSION,
                    listOf(
                        "player_uuid" to dialect.text(36),
                        "address" to dialect.text(45),
                        "issued_at" to dialect.bigint,
                        "expires_at" to dialect.bigint,
                    ),
                    listOf("PRIMARY KEY (player_uuid)"),
                ),
            )
        },

        // One Bedrock connection may act as one Java identity.
        //
        // This exists because Floodgate's global linking cannot answer the question we need to ask.
        // Its `/v2/link/bedrock/{xuid}` endpoint returns an empty body even when
        // `/v2/link/java/{uuid}` reports the link, so Floodgate never resolves a Bedrock join to a
        // Java account. Verified against the live API on 2026-11-16; see ADR-0013.
        //
        // `bedrock_uuid` is the primary key because one Bedrock account is one person. `java_uuid`
        // is deliberately not unique: a second Xbox account is not by itself evidence that a
        // different person owns it.
        Migration(2) { dialect ->
            listOf(
                dialect.createTableStatement(
                    BEDROCK_LINK,
                    listOf(
                        "bedrock_uuid" to dialect.text(36),
                        "java_uuid" to dialect.text(36),
                        "java_name" to dialect.text(16),
                        "linked_at" to dialect.bigint,
                    ),
                    listOf("PRIMARY KEY (bedrock_uuid)"),
                ),
            )
        },
        // Current link evidence is separate from the historical v2 table. Neither table is a
        // credential; no historical row is imported, and a Java UUID may be linked to several XUIDs
        // without granting those XUID roles to one another.
        Migration(3) { dialect ->
            listOf(
                dialect.createTableStatement(
                    ACCOUNT_LINK,
                    listOf(
                        "xuid" to "${dialect.text(20)} NOT NULL",
                        "java_uuid" to "${dialect.text(36)} NOT NULL",
                        "source" to "${dialect.text(64)} NOT NULL",
                        "scope" to "${dialect.text(64)} NOT NULL",
                        "verified_at" to "${dialect.bigint} NOT NULL",
                    ),
                    listOf("PRIMARY KEY (xuid)"),
                ),
            )
        },
        // Historical AuthMe offline metadata and exclusive Java Microsoft (XUID) profile ownership.
        Migration(4) { dialect ->
            listOf(
                dialect.createTableStatement(
                    LEGACY_PROFILE,
                    listOf(
                        "id" to "${dialect.text(36)} NOT NULL",
                        "batch" to "${dialect.text(128)} NOT NULL",
                        "legacy_name" to "${dialect.text(16)} NOT NULL",
                        "name_key" to "${dialect.text(16)} NOT NULL",
                        "profile_uuid" to "${dialect.text(36)} NOT NULL",
                        "status" to "${dialect.text(16)} NOT NULL",
                        "source" to "${dialect.text(16)} NOT NULL",
                        "claimed_xuid" to dialect.text(20),
                    ),
                    listOf(
                        "PRIMARY KEY (id)",
                        "CONSTRAINT uq_${LEGACY_PROFILE}_uuid UNIQUE (profile_uuid)",
                        "CONSTRAINT uq_${LEGACY_PROFILE}_name UNIQUE (name_key)",
                        "CHECK (source = 'authme')",
                        "CHECK ((status = 'AVAILABLE' AND claimed_xuid IS NULL) OR " +
                            "(status = 'CLAIMED' AND claimed_xuid IS NOT NULL))",
                    ),
                ),
                dialect.createTableStatement(
                    PROFILE_OWNER,
                    listOf(
                        "profile_uuid" to "${dialect.text(36)} NOT NULL",
                        "kind" to "${dialect.text(16)} NOT NULL",
                        "subject" to "${dialect.text(64)} NOT NULL",
                        "version" to "${dialect.bigint} NOT NULL",
                    ),
                    listOf(
                        "PRIMARY KEY (profile_uuid)",
                        "CONSTRAINT uq_${PROFILE_OWNER}_subject UNIQUE (kind, subject)",
                        "CHECK (kind = 'XUID')",
                        "CHECK (version > 0)",
                    ),
                ),
                dialect.createTableStatement(
                    CLAIM_GRANT,
                    listOf(
                        "token_hash" to "${dialect.text(64)} NOT NULL",
                        "record_id" to "${dialect.text(36)} NOT NULL",
                        "session_id" to "${dialect.text(36)} NOT NULL",
                        "login_name" to "${dialect.text(16)} NOT NULL",
                        "expires_at" to "${dialect.bigint} NOT NULL",
                        "consumed" to "${dialect.bigint} NOT NULL",
                    ),
                    listOf(
                        "PRIMARY KEY (token_hash)",
                        "FOREIGN KEY (record_id) REFERENCES $LEGACY_PROFILE (id)",
                        "CHECK (consumed IN (0, 1))",
                    ),
                ),
                dialect.createTableStatement(
                    PROFILE_CLAIM_AUDIT,
                    listOf(
                        "id" to "${dialect.text(36)} NOT NULL",
                        "record_id" to "${dialect.text(36)} NOT NULL",
                        "profile_uuid" to "${dialect.text(36)} NOT NULL",
                        "kind" to "${dialect.text(16)} NOT NULL",
                        "subject" to "${dialect.text(64)} NOT NULL",
                        "owner_version" to "${dialect.bigint} NOT NULL",
                        "session_id" to "${dialect.text(36)} NOT NULL",
                        "login_name" to "${dialect.text(16)} NOT NULL",
                        "event_type" to "${dialect.text(32)} NOT NULL",
                        "created_at" to "${dialect.bigint} NOT NULL",
                    ),
                    listOf("PRIMARY KEY (id)"),
                ),
            )
        },
        Migration(5) { dialect ->
            listOf("ALTER TABLE $CLAIM_GRANT ADD COLUMN proof_method ${dialect.text(24)} NOT NULL DEFAULT 'MANUAL'")
        },
    )

    val version: Int get() = migrations.maxOf { it.version }
}
