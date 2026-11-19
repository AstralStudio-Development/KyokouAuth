package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.authme.AuthMeLegacyVerifier
import moe.ryuu.kyokouauth.core.authme.AuthMeProfileDiscovery
import moe.ryuu.kyokouauth.core.authme.AuthMeSourceSummary
import moe.ryuu.kyokouauth.core.identity.GameName
import moe.ryuu.kyokouauth.core.storage.LegacyBatchEntry
import moe.ryuu.kyokouauth.core.storage.LegacyBatchStatus
import moe.ryuu.kyokouauth.core.storage.LegacyProfileCandidate
import moe.ryuu.kyokouauth.core.storage.ProfileClaimRepository
import java.util.UUID

/** Public administrator workflow: scan without writing, inspect issues, explicitly confirm once. */
class AuthMeMigrationService(
    private val settings: AuthMeClaimSettings,
    private val verifier: AuthMeLegacyVerifier,
    private val claims: ProfileClaimRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var pending: AuthMeMigrationPreview? = null

    @Synchronized
    fun preview(stillCurrent: () -> Boolean = { true }): AuthMeMigrationPreview {
        pending = null
        val report = plan(stillCurrent)
        val preview = AuthMeMigrationPreview(UUID.randomUUID().toString(), nowMillis() + 300_000, report)
        pending = preview
        return preview
    }

    @Synchronized
    fun report(): AuthMeMigrationPreview? = pending?.takeIf { nowMillis() < it.expiresAt }

    @Synchronized
    fun confirm(token: String, transactionSize: Int = 100_000, stillCurrent: () -> Boolean = { true },
                progress: (Int) -> Unit = {}): AuthMeAutoMigrationReport {
        require(transactionSize in 1..100_000)
        check(stillCurrent()) { "Migration cancelled" }
        val preview = checkNotNull(pending) { "Run an AuthMe migration scan first" }
        check(token == preview.token && nowMillis() < preview.expiresAt) { "Invalid or expired migration confirmation" }
        pending = null
        val current = plan(stillCurrent)
        check(current == preview.report) { "Migration source or target changed; scan again before confirming" }
        val ready = preview.report.entries.filter { it.status == LegacyBatchStatus.READY }.map { it.candidate }
        var completed = 0
        val imported = ready.chunked(transactionSize).flatMap { chunk ->
            check(stillCurrent()) { "Migration cancelled" }
            claims.importLegacyBatch(settings.batch, chunk).also {
                completed += chunk.size
                progress(completed)
            }
        }
        check(stillCurrent()) { "Migration cancelled" }
        val byCandidate = imported.associateBy { it.candidate }
        return current.copy(entries = current.entries.map { byCandidate[it.candidate] ?: it })
    }

    private fun plan(stillCurrent: () -> Boolean): AuthMeAutoMigrationReport {
        check(stillCurrent()) { "Migration cancelled" }
        check(settings.automaticDiscovery) { "Automatic AuthMe discovery requires config-file and server-folder" }
        val accounts = verifier.inspectAccounts()
        val historical = AuthMeProfileDiscovery.discover(checkNotNull(settings.serverFolder), current = stillCurrent)
        val byName = historical.profiles.groupBy { GameName.parseOrThrow(it.name).key }
        val namesByUuid = historical.profiles.groupBy { it.profileUuid }
            .mapValues { (_, entries) -> entries.map { GameName.parseOrThrow(it.name).key }.toSet() }
        val candidates = ArrayList<LegacyProfileCandidate>()
        val issues = ArrayList<AuthMeMigrationIssue>()
        accounts.forEach { account ->
            check(stillCurrent()) { "Migration cancelled" }
            val name = account.name
            if (account.issue != null || name == null) {
                issues += AuthMeMigrationIssue(name?.takeIf { GameName.parse(it) != null }, account.issue?.name ?: "INVALID_NAME")
                return@forEach
            }
            val matches = byName[GameName.parseOrThrow(name).key].orEmpty().distinctBy { it.profileUuid }
            if (matches.isEmpty()) {
                issues += AuthMeMigrationIssue(name, "HISTORY_NOT_FOUND")
            } else if (matches.size != 1 || namesByUuid[matches.single().profileUuid].orEmpty().size != 1) {
                issues += AuthMeMigrationIssue(name, "AMBIGUOUS_HISTORY")
            } else {
                // Keep the trusted historical spelling; it is never fed to an offline UUID calculator.
                candidates += LegacyProfileCandidate(matches.single().name, matches.single().profileUuid)
            }
        }
        val entries = candidates.chunked(256).flatMap { chunk ->
            check(stillCurrent()) { "Migration cancelled" }
            claims.preflightLegacyBatch(settings.batch, chunk)
        }.sortedBy { GameName.parseOrThrow(it.candidate.name).key }
        entries.filter { it.status != LegacyBatchStatus.READY && it.status != LegacyBatchStatus.ALREADY_REGISTERED }
            .forEach { issues += AuthMeMigrationIssue(it.candidate.name, it.status.name) }
        return AuthMeAutoMigrationReport(accounts.size, candidates.size, historical.invalidFiles, entries,
            issues.sortedWith(compareBy({ it.name ?: "" }, { it.reason })))
    }
}

data class AuthMeMigrationIssue(val name: String?, val reason: String)

data class AuthMeMigrationPreview(val token: String, val expiresAt: Long, val report: AuthMeAutoMigrationReport,
                                val source: AuthMeSourceSummary? = null)

data class AuthMeAutoMigrationReport(
    val sourceAccounts: Int,
    val matchedProfiles: Int,
    val invalidHistoricalFiles: Int,
    val entries: List<LegacyBatchEntry>,
    val issues: List<AuthMeMigrationIssue>,
) {
    val ready: Int get() = entries.count { it.status == LegacyBatchStatus.READY }
    val imported: Int get() = entries.count { it.status == LegacyBatchStatus.IMPORTED }
    val alreadyRegistered: Int get() = entries.count { it.status == LegacyBatchStatus.ALREADY_REGISTERED }
}
