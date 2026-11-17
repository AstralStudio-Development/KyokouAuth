package moe.ryuu.kyokouauth.core.auth

import moe.ryuu.kyokouauth.core.http.Http
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.net.ConnectException
import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Talks to Microsoft for real.
 *
 * The device-code handshake is the one part of this project that cannot be reasoned about from the
 * code: the endpoint, the parameter names and the shape of the error bodies are external facts, and a
 * guess that compiles is still a guess. This test performs the smallest possible live exchange - it
 * asks for a code and never completes it - so a wrong request shape fails here rather than on a
 * player's first join.
 *
 * Explicit opt-in only: -Dkyokou.test.live=true. Ordinary builds never request real codes or profiles.
 */
@EnabledIfSystemProperty(named = "kyokou.test.live", matches = "true")
class DeviceCodeFlowLiveTest {

    /**
     * Runs a live exchange, converting transport failures into a skip.
     *
     * A timeout here says nothing about the code: the same request succeeds and fails run to run on
     * a flaky link. Only a *successful* round trip that produces the wrong shape is a real failure,
     * so anything that is not a transport error is rethrown and fails the build.
     */
    private fun <T> live(block: () -> T): T = try {
        block()
    } catch (failure: Exception) {
        val transport = generateSequence<Throwable>(failure) { it.cause }.any {
            it is UnknownHostException || it is ConnectException || it is java.net.SocketTimeoutException
        }
        if (!transport) throw failure
        Assumptions.abort<T>("no usable network: ${failure.message}")
    }

    @Test
    fun `a device code can be requested and has the shape the login flow needs`() {
        val challenge = live { DeviceCodeFlow.begin() }

        assertTrue(challenge.userCode.isNotBlank(), "user code was empty")
        assertTrue(challenge.deviceCode.isNotBlank(), "device code was empty")
        assertTrue(
            challenge.verificationUriComplete.contains(challenge.userCode),
            "the clickable URL must carry the code (value intentionally not logged)",
        )
        assertTrue(challenge.expiresInSeconds in 60..3600, "unexpected lifetime ${challenge.expiresInSeconds}")
        assertTrue(challenge.intervalSeconds in 1..60, "unexpected poll interval ${challenge.intervalSeconds}")
    }

    @Test
    fun `polling before the player has signed in reports pending, not an error`() {
        val challenge = live { DeviceCodeFlow.begin() }

        // Nobody has typed the code, so Microsoft's answer must be a normal "keep waiting" rather
        // than an exception - the poll loop treats the two very differently.
        val result = live { DeviceCodeFlow.poll(challenge) }
        assertTrue(
            result is DeviceCodeFlow.PollResult.Pending || result is DeviceCodeFlow.PollResult.SlowDown,
            "expected Pending, got $result",
        )
    }

    @Test
    fun `an unknown name is reported as free by the mojang directory`() {
        val name = "kyokouauth-nonexistent-" + System.nanoTime()

        when (val lookup = live { MojangDirectory.lookup(name) }) {
            is MojangDirectory.Lookup.Free -> Unit
            is MojangDirectory.Lookup.Failed -> Assumptions.abort("directory unavailable: ${lookup.reason}")
            is MojangDirectory.Lookup.Premium -> throw AssertionError("random name resolved to ${lookup.uuid}")
        }
    }

    @Test
    fun `a known premium name resolves to its real mojang uuid`() {
        when (val lookup = live { MojangDirectory.lookup("notch") }) {
            is MojangDirectory.Lookup.Premium -> assertEquals(
                "069a79f4-44e9-4726-a5be-fca90e38aaf5",
                lookup.uuid.toString(),
                "the Mojang directory must report the account's real UUID",
            )

            is MojangDirectory.Lookup.Failed -> Assumptions.abort("directory unavailable: ${lookup.reason}")
            MojangDirectory.Lookup.Free -> throw AssertionError("'notch' must be a premium name")
        }
    }
}
