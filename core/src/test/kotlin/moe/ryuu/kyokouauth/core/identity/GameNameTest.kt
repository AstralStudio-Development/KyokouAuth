package moe.ryuu.kyokouauth.core.identity

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `parse` is the only way a bindable name comes into existence, and it refuses exactly the shapes
 * that must never reach the name table. `forBedrock` is the one exception, and these tests pin down
 * how far that exception reaches: it produces a usable name for display, and it changes nothing
 * about what `parse` accepts.
 */
class GameNameTest {

    @Test
    fun `parse refuses the floodgate prefix`() {
        assertNull(GameName.parse(".Steve"))
        assertNull(GameName.parse(".a_b"))
    }

    @Test
    fun `parse refuses the vanilla edge cases`() {
        assertNull(GameName.parse(""))
        assertNull(GameName.parse("ab"))
        assertNull(GameName.parse("a".repeat(17)))
        assertNull(GameName.parse("Steve."))
        assertNull(GameName.parse("Ste ve"))
        assertNull(GameName.parse("Stève"))
    }

    @Test
    fun `parse accepts a plain java name`() {
        assertEquals("Steve", GameName.parse("Steve")?.value)
        assertEquals("a_b1", GameName.parse("a_b1")?.value)
    }

    @Test
    fun `bedrock entry keeps the prefix verbatim`() {
        val bedrock = GameName.forBedrock(".Steve")

        assertEquals(".Steve", bedrock.value)
        assertEquals(".steve", bedrock.key)
    }

    @Test
    fun `bedrock entry does not widen parse`() {
        // The point of the exception is that it is local: a name that arrives through parse still
        // cannot carry the prefix, so nothing that binds identities can be handed one.
        GameName.forBedrock(".Steve")

        assertNull(GameName.parse(".Steve"))
    }
}
