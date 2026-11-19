package moe.ryuu.kyokouauth.proxy
import moe.ryuu.kyokouauth.core.auth.MojangDirectory
import moe.ryuu.kyokouauth.core.authme.AuthMeSourceSelection
import moe.ryuu.kyokouauth.core.authme.AuthMeSourceSummary
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.storage.BoundIdentity
import moe.ryuu.kyokouauth.core.storage.ClaimResult
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.core.text.RichText
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException

/**
 * The operator's view of the plugin: what is bound, who is trusted, and how to take it away.
 *
 * Every subcommand that touches the database does so on the IO pool and answers when the answer
 * exists. A proxy command must not block a Netty worker on a query, and unlike the login gate there
 * is no player waiting for the result - so nothing here needs to be synchronous.
 *
 * What is deliberately absent compared with the backend plugin: `setspawn`, because the limbo has a
 * fixed position in the config and no world to write; and the anti-cheat style commands that only
 * made sense when the plugin owned a server.
 */
class KyokouAuthCommand(
    private val server: ProxyServer,
    private val config: ProxyConfig,
    private val messages: MessageCatalogue,
    private val auth: AuthService,
    private val gate: LoginGate,
    private val reloadMessages: () -> Unit,
) {

    private val usages = listOf(
        "usage-whois",
        "usage-session",
        "usage-verify",
        "usage-unbind",
        "usage-rename",
        "usage-untrust",
        "usage-status",
        "usage-reload",
    )

    fun hasPermission(source: CommandSource): Boolean = source.hasPermission(PERMISSION)

    fun execute(source: CommandSource, args: Array<out String>) {
        // Player credentials are consumed before this public dispatcher. Never process/echo them here.
        if (args.firstOrNull()?.equals("claim", ignoreCase = true) == true) {
            tell(source, "migration.claim-private-only")
            return
        }
        if (!hasPermission(source)) return

        if (args.isEmpty()) {
            usage(source)
            return
        }

        when (args[0].lowercase(Locale.ROOT)) {
            "whois" -> whois(source, args)
            "session" -> session(source, args)
            "verify" -> verify(source, args)
            "unbind" -> unbind(source, args)
            "rename" -> rename(source, args)
            "untrust" -> untrust(source, args)
            "status" -> status(source)
            "reload" -> reload(source)
            "migration" -> migration(source, args)
            else -> tell(source, "command.unknown-subcommand", "name" to args[0])
        }
    }

    fun suggest(args: Array<out String>): List<String> {
        val typed = args.lastOrNull() ?: ""

        if (args.size <= 1) {
            return NAMES.filter { it.startsWith(typed, ignoreCase = true) }
        }

        // Only `/kyokouauth verify` names a player; the rest take a name that is not in the proxy's
        // player list by definition (an offline player's binding).
        return when (args[0].lowercase(Locale.ROOT)) {
            "verify" -> server.allPlayers.map { it.username }.filter { it.startsWith(typed, ignoreCase = true) }
            else -> emptyList()
        }
    }

    private fun migration(source: CommandSource, args: Array<out String>) {
        if (!source.isConsole) { tell(source, "migration.console-only"); return }
        val operation = args.getOrNull(1)?.lowercase(Locale.ROOT)
        if (!config.profileClaimsEnabled && operation !in setOf("scan", "confirm", "issues", "progress", "status", "cancel", "disable")) {
            tell(source, "migration.disabled"); return
        }
        when (operation) {
            "scan" -> {
                val selection = migrationScanOptions(args)
                if (selection == null) { tell(source, "migration.scan-usage"); return }
                val scan = auth.scanInboxLegacyImport(selection)
                if (!scan.isCompletedExceptionally) tell(source, "migration.scan-started")
                scan.migrationAnswer(source) { preview ->
                    migrationSource(source, preview.source)
                    migrationReport(source, preview.report)
                    tell(source, "migration.scan-confirm", "token" to preview.token)
                }
            }
            "confirm" -> {
                if (args.size != 3) { tell(source, "command.migration-usage"); return }
                auth.confirmInboxLegacyImport(args[2]).migrationAnswer(source) { report ->
                    migrationReport(source, report)
                    tell(source, "migration.batch-imported", "count" to report.imported.toString())
                }
            }
            "progress", "status" -> {
                if (args.size != 2) { tell(source, "command.migration-usage"); return }
                val status = auth.inboxLegacyImportStatus()
                if (status == null) tell(source, "migration.snapshot-unavailable")
                else {
                    val phase = component("migration.phase-${status.phase.name.lowercase(Locale.ROOT)}", null).spans.joinToString("") { it.text }
                    tell(source, "migration.scan-progress", "phase" to phase, "count" to status.processed.toString(),
                        "active" to status.active.toString())
                    migrationSource(source, status.source)
                    status.failure?.let { failure ->
                        val reason = component("migration.failure-${failure.name.lowercase(Locale.ROOT)}", null).spans.joinToString("") { it.text }
                        tell(source, "migration.last-failure", "reason" to reason)
                    }
                }
            }
            "cancel" -> {
                if (args.size != 2) { tell(source, "command.migration-usage"); return }
                tell(source, if (auth.cancelInboxLegacyImport()) "migration.scan-cancelling" else "migration.scan-not-cancellable")
            }
            "disable" -> {
                if (args.size != 2) { tell(source, "command.migration-usage"); return }
                auth.disableInboxLegacyImport().migrationAnswer(source) { tell(source, "migration.snapshot-disabled") }
            }
            "issues" -> {
                val page = args.getOrNull(2)?.toIntOrNull() ?: if (args.size == 2) 1 else 0
                if (args.size !in 2..3 || page !in 1..5000) { tell(source, "command.migration-usage"); return }
                auth.submit { auth.automaticLegacyImportReport() }.answer(source) { preview ->
                    if (preview == null) tell(source, "migration.scan-required")
                    else {
                        val issues = preview.report.issues
                        val pages = maxOf(1, (issues.size + 19) / 20)
                        tell(source, "migration.issues-page", "page" to page.toString(), "pages" to pages.toString(), "total" to issues.size.toString())
                        issues.drop((page - 1) * 20).take(20).forEach { issue ->
                            val reason = component("migration.issue-${issue.reason.lowercase(Locale.ROOT)}", null)
                                .spans.joinToString("") { it.text }
                            tell(source, "migration.issue-entry", "name" to (issue.name ?: "?"), "reason" to reason)
                        }
                    }
                }
            }
            "pending" -> {
                val requests = gate.pendingProfileClaims()
                if (requests.isEmpty()) tell(source, "migration.pending-empty")
                else requests.forEach { request -> tell(source, "migration.pending-entry",
                    "session" to request.sessionId.toString(), "name" to request.loginName.value, "stage" to request.stage.name) }
            }
            "check", "import" -> {
                if (args.size != 5) { tell(source, "command.migration-usage"); return }
                val profile = runCatching { java.util.UUID.fromString(args[4]) }.getOrNull()
                if (profile == null) { tell(source, "command.migration-usage"); return }
                if (args[1].equals("check", ignoreCase = true)) {
                    auth.submit { auth.preflightLegacyProfile(args[2], args[3], profile) }.answer(source) {
                        tell(source, "migration.preflight-ok")
                    }
                } else {
                    auth.submit { auth.importLegacyProfile(args[2], args[3], profile) }.answer(source) { record ->
                        tell(source, "migration.imported", "record" to record.id.toString())
                    }
                }
            }
            "approve" -> {
                if (args.size != 5 || args[4] != "verified") { tell(source, "command.migration-usage"); return }
                val session = runCatching { java.util.UUID.fromString(args[2]) }.getOrNull()
                val record = runCatching { java.util.UUID.fromString(args[3]) }.getOrNull()
                if (session == null || record == null) { tell(source, "command.migration-usage"); return }
                try { gate.approveProfileClaim(session, record).answer(source) { tell(source, "migration.approved") } }
                catch (_: Exception) { tell(source, "migration.operation-failed") }
            }
            else -> tell(source, "command.migration-usage")
        }
    }

    private fun migrationScanOptions(args: Array<out String>): AuthMeSourceSelection? {
        var host: String? = null
        var port: Int? = null
        var local = false
        var index = 2
        while (index < args.size) {
            when (args[index].lowercase(Locale.ROOT)) {
                "--local-source" -> {
                    if (local) return null
                    local = true
                }
                "--source-host" -> {
                    if (host != null || ++index >= args.size) return null
                    val value = args[index]
                    if (value.length !in 1..253 || value.startsWith("-") || value.any {
                            it.isWhitespace() || it.isISOControl() || it in "@/\\?#\"'"
                        }) return null
                    host = value
                }
                "--source-port" -> {
                    if (port != null || ++index >= args.size) return null
                    port = args[index].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                }
                else -> return null
            }
            index++
        }
        return AuthMeSourceSelection(host, port, local)
    }

    private fun migrationSource(source: CommandSource, summary: AuthMeSourceSummary?) {
        if (summary == null) return
        if (summary.host == null) tell(source, "migration.source-sqlite", "database" to summary.database)
        else tell(source, "migration.source-remote", "type" to summary.dialect.displayName,
            "host" to summary.host.orEmpty(), "port" to summary.port.toString(), "database" to summary.database)
    }

    private fun migrationReport(source: CommandSource, report: AuthMeAutoMigrationReport) {
        tell(source, "migration.scan-summary", "total" to report.sourceAccounts.toString(),
            "matched" to report.matchedProfiles.toString(), "ready" to report.ready.toString(),
            "existing" to report.alreadyRegistered.toString(), "issues" to report.issues.size.toString(),
            "invalid" to report.invalidHistoricalFiles.toString())
    }

    // --- subcommands -----------------------------------------------------------------------

    private fun usage(source: CommandSource) {
        tell(source, "command.usage-header")
        usages.forEach { tell(source, "command.$it") }
        if (source.isConsole) tell(source, "command.migration-usage")
    }

    private fun status(source: CommandSource) {
        val limit = config.directoryBudget
        val used = MojangDirectory.budgetUsage()

        tell(
            source,
            "command.status",
            "dialect" to config.database.dialect.displayName,
            "session" to config.sessionHours.toString(),
            "locale" to config.locale,
            "verifying" to gate.verifyingCount().toString(),
            "budget" to if (limit <= 0) "$used (unlimited)" else "$used/$limit",
            "cached" to auth.identities.size().toString(),
        )
    }

    /**
     * Reloads what can be reloaded.
     *
     * Message files, because they are read per delivery and an operator translates in place. The
     * database, the limbo bind and the pools are not: rebuilding them under live players would drop
     * every connection in the limbo, so those changes are a restart.
     */
    private fun reload(source: CommandSource) {
        reloadMessages()
        tell(source, "command.reloaded")
    }

    private fun whois(source: CommandSource, args: Array<out String>) {
        val name = args.getOrNull(1)?.let(GameName::parse)
        if (name == null) {
            tell(source, "command.usage-whois")
            return
        }

        auth.submit { auth.identities.byName(name) }.answer(source) { record ->
            if (record == null) {
                tell(source, "command.unknown-name", "name" to name.value)
                return@answer
            }

            tell(
                source,
                "command.whois",
                "name" to record.name.value,
                "uuid" to record.uuid.toString(),
                "kind" to when (record.kind) {
                    BoundIdentity.Kind.MOJANG -> "premium"
                    BoundIdentity.Kind.XUID -> "microsoft"
                },
                "subject" to record.subject,
                "gamertag" to (record.gamertag ?: "-"),
            )
        }
    }

    private fun session(source: CommandSource, args: Array<out String>) {
        val name = args.getOrNull(1)?.let(GameName::parse)
        if (name == null) {
            tell(source, "command.usage-session")
            return
        }

        auth.submit {
            val record = auth.identities.byName(name)
            record to record?.let { auth.sessions.find(it.uuid) }
        }.answer(source) { (record, session) ->
            if (record == null) {
                tell(source, "command.unknown-name", "name" to name.value)
                return@answer
            }

            if (session == null) {
                tell(source, "command.session-none", "name" to record.name.value)
                return@answer
            }

            val remainingMinutes = ((session.expiresAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
            tell(
                source,
                "command.session",
                "name" to record.name.value,
                "address" to session.address,
                "minutes" to remainingMinutes.toString(),
            )
        }
    }

    private fun verify(source: CommandSource, args: Array<out String>) {
        val requested = args.getOrNull(1)
        val target = requested?.let { name -> server.getPlayer(name).orElse(null) }
        if (target == null) {
            tell(source, "command.unknown-player", "name" to (requested ?: ""))
            return
        }

        // Asks Microsoft for a fresh code, so the answer comes from the IO pool like every other
        // network call here.
        auth.submit { gate.restartVerification(target) }.answer(source) { restarted ->
            if (restarted) {
                tell(source, "command.verify-resent", "name" to target.username)
            } else {
                tell(source, "command.not-verifying", "name" to target.username)
            }
        }
    }

    private fun unbind(source: CommandSource, args: Array<out String>) {
        val name = args.getOrNull(1)?.let(GameName::parse)
        if (name == null) {
            tell(source, "command.usage-unbind")
            return
        }

        auth.submit {
            val record = auth.identities.byName(name) ?: return@submit null
            auth.auth.revokeSession(record.uuid)
            auth.identities.unbind(record.uuid)
            record
        }.answer(source) { record ->
            if (record == null) {
                tell(source, "command.unknown-name", "name" to name.value)
            } else {
                tell(source, "command.unbound", "name" to record.name.value)
            }
        }
    }

    private fun untrust(source: CommandSource, args: Array<out String>) {
        val name = args.getOrNull(1)?.let(GameName::parse)
        if (name == null) {
            tell(source, "command.usage-untrust")
            return
        }

        auth.submit {
            val record = auth.identities.byName(name) ?: return@submit null
            auth.auth.revokeSession(record.uuid)
            record
        }.answer(source) { record ->
            if (record == null) {
                tell(source, "command.unknown-name", "name" to name.value)
            } else {
                tell(source, "command.untrusted", "name" to record.name.value)
            }
        }
    }

    private fun rename(source: CommandSource, args: Array<out String>) {
        val from = args.getOrNull(1)?.let(GameName::parse)
        val to = args.getOrNull(2)?.let(GameName::parse)
        if (from == null || to == null) {
            tell(source, "command.usage-rename")
            return
        }

        auth.submit {
            val record = auth.identities.byName(from) ?: return@submit null
            record to auth.identities.rename(record.uuid, to, System.currentTimeMillis())
        }.answer(source) { result ->
            if (result == null) {
                tell(source, "command.unknown-name", "name" to from.value)
                return@answer
            }

            val (record, outcome) = result
            when (outcome) {
                is ClaimResult.Bound -> {
                    tell(source, "command.renamed", "from" to from.value, "to" to to.value)

                    // An online player would keep a name the database no longer agrees with, so they
                    // are moved off to resolve it again.
                    server.getPlayer(record.uuid).ifPresent { player ->
                        player.disconnect(messages.parsed("login.identity-renamed", localeOf(player)))
                    }
                }

                is ClaimResult.NameTaken -> tell(
                    source,
                    "command.rename-taken",
                    "name" to to.value,
                    "owner" to outcome.by.name.value,
                )

                else -> tell(source, "command.internal-error")
            }
        }
    }

    private fun <T> CompletableFuture<T>.migrationAnswer(source: CommandSource, action: (T) -> Unit) {
        whenComplete { result, failure ->
            if (failure == null) {
                try { action(result) } catch (unexpected: Exception) {
                    auth.reportFailure(unexpected)
                    tell(source, "migration.snapshot-failed")
                }
            } else {
                var cause = failure
                while ((cause is CompletionException || cause is ExecutionException) && cause.cause != null) cause = cause.cause!!
                when (cause) {
                    is java.util.concurrent.CancellationException -> tell(source, "migration.scan-cancelled")
                    is RejectedExecutionException -> tell(source, "command.busy")
                    is AuthMeImportException -> {
                        if (cause.reason == AuthMeImportFailure.OPERATION_FAILED) auth.reportFailure(cause)
                        tell(source, "migration.failure-${cause.reason.name.lowercase(Locale.ROOT)}")
                    }
                    else -> { auth.reportFailure(cause); tell(source, "migration.snapshot-failed") }
                }
            }
        }
    }

    private fun <T> CompletableFuture<T>.answer(source: CommandSource, action: (T) -> Unit) {
        whenComplete { result, failure ->
            if (failure != null) {
                var cause = failure
                while ((cause is CompletionException || cause is ExecutionException) && cause.cause != null) cause = cause.cause!!
                tell(source, if (cause is RejectedExecutionException) "command.busy" else "command.internal-error")
            } else {
                try { action(result) } catch (unexpected: Exception) {
                    auth.reportFailure(unexpected)
                    tell(source, "command.internal-error")
                }
            }
        }
    }

    // --- plumbing --------------------------------------------------------------------------

    private fun tell(
        source: CommandSource,
        key: String,
        vararg placeholders: Pair<String, String>,
    ) {
        val locale = (source as? Player)?.let { localeOf(it) }
        source.sendMessage(component(key, locale, *placeholders))
    }

    private fun component(key: String, locale: String?, vararg placeholders: Pair<String, String>): RichText.Parsed =
        messages.parsed(key, locale, *placeholders)

    private fun localeOf(player: Player): String? = player.effectiveLocale.toString()

    companion object {
        const val NAME = "kyokouauth"

        /** The short form operators type most often; `/ka status` twice a day. */
        const val ALIAS = "ka"

        const val PERMISSION = "kyokouauth.admin"

        private val NAMES = listOf("whois", "session", "verify", "unbind", "rename", "untrust", "status", "reload", "migration")
    }
}