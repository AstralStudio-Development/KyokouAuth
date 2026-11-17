package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.auth.DeviceCodeFlow
import moe.ryuu.kyokouauth.core.auth.XboxAuthException
import moe.ryuu.kyokouauth.core.auth.XboxLive
import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.limbo.LimboPlayer
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One device-code attempt, not an authorization registry. The gate owns the connection lock and
 * decides whether this exact attempt is current; only the gate can bind, grant or release a player.
 * All timer, prompt and cancellation state is serialized on that same lock. Network calls are not.
 */
internal class VerificationSession(
    val player: LimboPlayer,
    val locale: String?,
    private val lock: Any,
    private val config: ProxyConfig,
    private val messages: MessageCatalogue,
    private val services: LoginGate.Services,
    private val logger: ProxyLog,
    private val clock: () -> Long,
    private val current: (VerificationSession) -> Boolean,
    private val ownsTitle: (VerificationSession) -> Boolean,
    private val failed: (VerificationSession, String, Array<out Pair<String, String>>) -> Unit,
    private val verified: (VerificationSession, XboxLive.XstsToken) -> Unit,
) {
    private var finished = false
    private var intervalMillis = config.pollIntervalSeconds * 1000L
    private var poll: ScheduledFuture<*>? = null
    private var timeout: ScheduledFuture<*>? = null
    private var titleShown = false
    private val busy = AtomicBoolean(false)
    private var deadlineMillis = Long.MAX_VALUE
    private var deviceCodeMillis = 0L
    private var pollRequests = 0
    private var pollHttpMillis = 0L
    private var pollWaitMillis = 0L
    private var pollQueuedMillis = 0L

    /** Called under the gate's connection lock, including from the gate's authorization callback. */
    fun active(): Boolean = !finished && current(this)

    /** Arm before queueing IO, so worker saturation cannot keep a held connection alive forever. */
    fun arm() {
        val timeoutMillis = config.verificationTimeoutSeconds * 1000L
        deadlineMillis = clock() + timeoutMillis
        timeout = services.schedule(timeoutMillis) { fail("verification.expired") }
    }

    fun enqueueStart() {
        try {
            arm()
            services.submit { start() }
        } catch (_: Exception) {
            fail("verification.start-failed")
        }
    }

    /** False also expires a still-current attempt; late work must not begin more external IO. */
    fun beforeDeadline(): Boolean {
        if (!active()) return false
        if (clock() < deadlineMillis) return true
        fail("verification.expired")
        return false
    }

    /** Blocking startup also serves the existing management restart call, already on an IO worker. */
    fun start() {
        synchronized(lock) {
            if (!beforeDeadline()) return
            try {
                player.sendMessage(text("verification.starting"))
            } catch (_: Exception) {
                fail("verification.start-failed")
                return
            }
        }
        val startedAt = clock()
        val challenge = try {
            services.begin()
        } catch (failure: DeviceCodeFlow.DeviceCodeException) {
            logger.warn("Microsoft sign-in initialization for {} failed: code={}, httpStatus={}, elapsedMs={}",
                player.name, failure.code.name, failure.httpStatus, clock() - startedAt)
            val message = when (failure.code) {
                DeviceCodeFlow.FailureCode.TIMEOUT, DeviceCodeFlow.FailureCode.TRANSPORT,
                DeviceCodeFlow.FailureCode.UPSTREAM_UNAVAILABLE -> "verification.start-unavailable"
                DeviceCodeFlow.FailureCode.RATE_LIMITED -> "verification.start-busy"
                else -> "verification.start-failed"
            }
            fail(message)
            return
        } catch (failure: Exception) {
            logger.warn("Microsoft sign-in initialization for {} failed: type={}", player.name, failure.javaClass.simpleName)
            fail("verification.start-failed")
            return
        }
        val receivedAt = clock()
        deviceCodeMillis = elapsed(startedAt, receivedAt)
        synchronized(lock) {
            if (!beforeDeadline()) return
            intervalMillis = maxOf(config.pollIntervalSeconds, challenge.intervalSeconds.coerceAtLeast(1).toLong()) * 1000L
            try {
                showPrompt(challenge)
                schedulePoll(challenge, receivedAt)
            } catch (_: Exception) {
                fail("verification.start-failed")
            }
        }
    }

    private fun showPrompt(challenge: DeviceCodeFlow.Challenge) {
        val remainingMillis = (deadlineMillis - clock()).coerceAtLeast(0L)
        val stayTicks = (remainingMillis / 50L + if (remainingMillis % 50L == 0L) 0L else 1L)
            .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        // Only the ready challenge gets a title. Polling never restarts its animation.
        titleShown = true
        player.showTitle(text("limbo.title"), text("limbo.subtitle"), stay = stayTicks)
        player.sendMessage(text("verification.prompt",
            "url" to challenge.verificationUriComplete,
            "code" to challenge.userCode,
            "minutes" to (config.verificationTimeoutSeconds / 60).coerceAtLeast(1).toString()))
    }

    private fun schedulePoll(challenge: DeviceCodeFlow.Challenge, receivedAt: Long) = synchronized(lock) {
        if (!beforeDeadline()) return@synchronized
        // RFC 8628 and Microsoft's pending response require a full interval AFTER the response.
        // Only local work since receiving it (prompt, logging, lock wait) consumes that interval;
        // never deduct the HTTP request's RTT. A worker queue may make us later, never earlier.
        val delayMillis = (intervalMillis - elapsed(receivedAt)).coerceAtLeast(0L)
        try {
            poll = services.schedule(delayMillis) {
                synchronized(lock) {
                    if (!beforeDeadline()) return@schedule
                    val queuedAt = clock()
                    try {
                        services.submit { poll(challenge, receivedAt, queuedAt) }
                    } catch (_: Exception) {
                        fail("verification.retry")
                    }
                }
            }
        } catch (_: Exception) {
            fail("verification.retry")
        }
    }

    private fun poll(challenge: DeviceCodeFlow.Challenge, previousResponseAt: Long, queuedAt: Long) {
        synchronized(lock) {
            if (!beforeDeadline() || !busy.compareAndSet(false, true)) return
            pollRequests++
            pollWaitMillis += elapsed(previousResponseAt)
            pollQueuedMillis += elapsed(queuedAt)
        }
        var retryFrom: Long? = null
        val requestStartedAt = clock()
        var receivedAt = requestStartedAt
        try {
            val result = try {
                try {
                    services.poll(challenge)
                } finally {
                    receivedAt = clock()
                    pollHttpMillis += elapsed(requestStartedAt, receivedAt)
                }
            } catch (failure: DeviceCodeFlow.DeviceCodeException) {
                synchronized(lock) {
                    if (!beforeDeadline()) return
                    if (failure.code == DeviceCodeFlow.FailureCode.RATE_LIMITED) {
                        intervalMillis = maxOf(intervalMillis, failure.retryAfterMillis ?: 60_000L)
                    } else {
                        failure.retryAfterMillis?.let { intervalMillis = maxOf(intervalMillis, it) }
                    }
                    retryFrom = receivedAt
                    logger.warn("Microsoft sign-in poll for {} failed: code={}, httpStatus={}",
                        player.name, failure.code.name, failure.httpStatus)
                }
                return
            } catch (failure: Exception) {
                synchronized(lock) {
                    if (!beforeDeadline()) return
                    retryFrom = receivedAt
                    logger.warn("Microsoft sign-in poll for {} failed: type={}", player.name, failure.javaClass.simpleName)
                }
                return
            }
            synchronized(lock) {
                if (!beforeDeadline()) return
                when (result) {
                    is DeviceCodeFlow.PollResult.Pending -> retryFrom = receivedAt
                    is DeviceCodeFlow.PollResult.SlowDown -> {
                        // RFC 8628 specifies five more seconds on EVERY slow_down. Keep the
                        // current upstream/Retry-After floor, without adding exponential idle time.
                        intervalMillis = if (intervalMillis > Long.MAX_VALUE - 5_000L) Long.MAX_VALUE else intervalMillis + 5_000L
                        retryFrom = receivedAt
                    }
                    is DeviceCodeFlow.PollResult.Expired -> fail("verification.expired")
                    is DeviceCodeFlow.PollResult.Declined -> fail("verification.declined")
                    is DeviceCodeFlow.PollResult.Authorized -> Unit
                }
            }
            if (result is DeviceCodeFlow.PollResult.Authorized) complete(result.accessToken)
        } finally {
            synchronized(lock) {
                // Local work can consume the whole interval. Release busy BEFORE scheduling a
                // zero-delay retry, so its worker cannot run early, see busy, and lose the chain.
                busy.set(false)
                retryFrom?.let { schedulePoll(challenge, it) }
            }
        }
    }

    private fun complete(accessToken: String) {
        synchronized(lock) {
            if (!beforeDeadline()) return
            try {
                // This is progress, NOT authorization. Only a real successful token poll reaches
                // here, and the gate still owns all binding, deadline and admission decisions.
                player.sendMessage(text("verification.confirming"))
            } catch (_: Exception) {
                fail("verification.retry")
                return
            }
            if (!beforeDeadline()) return
        }
        val exchangeStartedAt = clock()
        var xboxMillis = 0L
        var completionMillis = -1L
        try {
            val xsts = try {
                try {
                    services.exchange(accessToken)
                } finally {
                    xboxMillis = elapsed(exchangeStartedAt)
                }
            } catch (failure: XboxAuthException) {
                fail("verification.xbox-failed", "reason" to failure.message)
                return
            } catch (_: Exception) {
                logger.warn("Xbox token exchange failed for {}", player.name)
                fail("verification.retry")
                return
            }
            synchronized(lock) { if (!beforeDeadline()) return }
            // Binding may perform IO. The callback rechecks this attempt before and after binding,
            // and keeps only the final session grant/authorization under the connection lock.
            val completionStartedAt = clock()
            try {
                verified(this, xsts)
            } finally {
                completionMillis = elapsed(completionStartedAt)
            }
        } finally {
            // One per token-bearing attempt, not one line per pending tick. Only safe stage names,
            // counts and durations: never identity, token, device code, URL or response content.
            logger.info("Microsoft verification timing: deviceCodeMs={} pollRequests={} pollHttpMs={} pollWaitMs={} pollQueuedMs={} xboxMs={} completionMs={}",
                deviceCodeMillis, pollRequests, pollHttpMillis, pollWaitMillis, pollQueuedMillis, xboxMillis, completionMillis)
        }
    }

    private fun elapsed(startedAt: Long, completedAt: Long = clock()): Long =
        (completedAt - startedAt).coerceAtLeast(0L)

    private fun fail(key: String, vararg placeholders: Pair<String, String>) = synchronized(lock) {
        if (active()) failed(this, key, placeholders)
    }

    /** Idempotent cancellation never touches another attempt's title, even on the same connection. */
    fun cancel() {
        if (finished) return
        finished = true
        poll?.cancel(false)
        timeout?.cancel(false)
        if (titleShown && ownsTitle(this)) {
            try {
                player.clearTitle()
            } catch (failure: Exception) {
                logger.warn("Could not clear verification title for {}: type={}", player.name, failure.javaClass.simpleName)
            }
        }
    }

    private fun text(key: String, vararg placeholders: Pair<String, String>) =
        messages.parsed(key, locale, *placeholders).toLimbo()
}
