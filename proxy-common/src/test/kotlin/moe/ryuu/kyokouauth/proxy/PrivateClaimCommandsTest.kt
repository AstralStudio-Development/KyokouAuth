package moe.ryuu.kyokouauth.proxy

import org.junit.jupiter.api.Test
import kotlin.test.*

class PrivateClaimCommandsTest {
    @Test
    fun `aliases namespaces malformed arguments and control input never escape the private family`() {
        for (line in listOf("ka claim", "/KA CLAIM AUTHME", "/kyokouauth:ka claim authme OldSteve SECRET",
            "/ka claim status SECRET", "/ka claim authme OldSteve SECRET\nINJECTION", "/ka claim authme OldSteve " + "x".repeat(3000))) {
            assertTrue(PrivateClaimCommands.isSensitive(line))
            val input = assertNotNull(PrivateClaimCommands.parse(line))
            assertFalse(input.toString().contains("SECRET"))
            input.close()
            assertNull(input.takePassword())
        }
        for (line in listOf("/server lobby", "/ka status", "/kyokouauth whois Steve", "/ka claimant authme x y")) {
            assertFalse(PrivateClaimCommands.isSensitive(line))
            assertNull(PrivateClaimCommands.parse(line))
        }
    }

    @Test
    fun `status cancel and invalid syntax never retain a password buffer`() {
        for ((line, action) in listOf("/ka claim status" to PrivateClaimCommands.Action.STATUS,
            "/ka claim cancel" to PrivateClaimCommands.Action.CANCEL,
            "/ka claim status EXTRA" to PrivateClaimCommands.Action.USAGE,
            "/ka claim authme OldSteve" to PrivateClaimCommands.Action.USAGE,
            "/ka claim authme Invalid*Name secret" to PrivateClaimCommands.Action.USAGE)) {
            PrivateClaimCommands.parse(line)!!.use { input ->
                assertEquals(action, input.action)
                assertNull(input.takePassword())
            }
        }
    }

    @Test
    fun `private AuthMe input preserves password characters without exposing them in rendering`() {
        val line = "/kyokouauth claim authme OldSteve  Abc secret "
        assertTrue(PrivateClaimCommands.isSensitive(line))
        val input = PrivateClaimCommands.parse(line)!!
        assertEquals(PrivateClaimCommands.Action.AUTHME, input.action)
        assertEquals("OldSteve", input.legacyName?.value)
        assertFalse(input.toString().contains("Abc"))
        assertFalse(input.toString().contains("secret"))
        val password = input.takePassword()!!
        assertEquals(" Abc secret ", password.concatToString())
        assertNull(input.takePassword())
        input.close()
        password.fill('\u0000')
    }
}
