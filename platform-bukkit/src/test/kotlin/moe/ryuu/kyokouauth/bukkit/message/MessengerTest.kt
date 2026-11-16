package moe.ryuu.kyokouauth.bukkit.message

import moe.ryuu.kyokouauth.core.text.RichText
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `§`-coded form is what the login-phase Disconnect packet carries, so a hex colour has to
 * survive the legacy serializer rather than degrade to the nearest named colour.
 *
 * It does so through the `§x§R§R§G§G§B§B` escape, which is why the plugin's floor is 1.16.
 */
class MessengerTest {

    @Test
    fun `a hex colour becomes the section-x escape`() {
        val legacy = Messenger().legacy(RichText.parse("<#22D3EE>hello"))

        assertTrue(legacy.contains("§x"), "hex must not fall back to a named colour: $legacy")
        assertEquals("hello", legacy.replace(Regex("§."), ""), legacy)
    }

    @Test
    fun `named colours still use the two-character form`() {
        val legacy = Messenger().legacy(RichText.parse("<red>hello"))

        assertEquals("§chello", legacy)
    }

    @Test
    fun `placeholders and decoration survive`() {
        val legacy = Messenger().legacy(RichText.parse("<#22D3EE><u>Steve</u>"))

        assertTrue(legacy.contains("§x"), legacy)
        assertTrue(legacy.contains("§n"), "underline must survive: $legacy")
        assertTrue(legacy.contains("Steve"), legacy)
    }

    @Test
    fun `pure white collapses to the named colour`() {
        // Adventure emits §f for #FFFFFF because a client renders the two identically. Recorded
        // here so the shorter form is not mistaken for a lost colour.
        assertEquals("§fSteve", Messenger().legacy(RichText.parse("<#FFFFFF>Steve")))
    }
}
