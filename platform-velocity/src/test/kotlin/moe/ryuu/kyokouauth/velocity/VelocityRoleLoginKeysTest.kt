package moe.ryuu.kyokouauth.velocity

import com.velocitypowered.api.network.ProtocolState
import com.velocitypowered.api.proxy.InboundConnection
import com.velocitypowered.api.proxy.LoginPhaseConnection
import com.velocitypowered.api.proxy.crypto.IdentifiedKey
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.test.*

class VelocityRoleLoginKeysTest {
    private val holder = UUID(1, 2)
    private val methods = mutableListOf<String>()

    private fun key(id: UUID? = holder, valid: Boolean = true, expired: Boolean = false): IdentifiedKey =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(IdentifiedKey::class.java)) { _, method, _ ->
            methods += method.name
            when (method.name) {
                "getSignatureHolder" -> id
                "isSignatureValid" -> valid
                "hasExpired" -> expired
                else -> error("No public key mutation or cryptographic material access is allowed: ${method.name}")
            }
        } as IdentifiedKey

    private fun connection(key: IdentifiedKey?, active: Boolean = true, state: ProtocolState = ProtocolState.LOGIN): InboundConnection =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(LoginPhaseConnection::class.java)) { _, method, _ ->
            methods += method.name
            when (method.name) {
                "isActive" -> active
                "getProtocolState" -> state
                "getIdentifiedKey" -> key
                else -> error("No native connection mutation is allowed: ${method.name}")
            }
        } as InboundConnection

    @Test fun `different role cannot inherit the original signed key and validation is read only`() {
        val original = key()
        val connection = connection(original)
        assertFailsWith<IllegalStateException> { VelocityRoleLoginKeys.verify(connection, UUID(3, 4)) }
        assertEquals(listOf("isActive", "getProtocolState", "getIdentifiedKey", "getSignatureHolder"), methods)
        methods.clear()
        VelocityRoleLoginKeys.verify(connection, holder)
        assertEquals(listOf("isActive", "getProtocolState", "getIdentifiedKey", "getSignatureHolder", "isSignatureValid", "hasExpired"), methods)
    }

    @Test fun `absent key permits role identity without inventing or clearing a key`() {
        VelocityRoleLoginKeys.verify(connection(null), UUID(3, 4))
        assertEquals(listOf("isActive", "getProtocolState", "getIdentifiedKey"), methods)
    }

    @Test fun `unbound invalid and expired keys all refuse role identity`() {
        for (key in listOf(key(id = null), key(valid = false), key(expired = true))) {
            assertFailsWith<IllegalStateException> { VelocityRoleLoginKeys.verify(connection(key), holder) }
        }
    }

    @Test fun `closed nonlogin and opaque initial connections cannot infer absence of a key`() {
        for (connection in listOf(connection(null, active = false), connection(null, state = ProtocolState.PLAY))) {
            methods.clear()
            assertFailsWith<IllegalStateException> { VelocityRoleLoginKeys.verify(connection, holder) }
            assertFalse("getIdentifiedKey" in methods)
        }
        val opaque = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(InboundConnection::class.java)) { _, method, _ ->
            error("Opaque initial connection cannot prove absence of a key: ${method.name}")
        } as InboundConnection
        assertFailsWith<IllegalStateException> { VelocityRoleLoginKeys.verify(opaque, holder) }
    }
}
