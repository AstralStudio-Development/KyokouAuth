package moe.ryuu.kyokouauth.proxy

import moe.ryuu.kyokouauth.core.text.MessageCatalogue
import moe.ryuu.kyokouauth.core.text.RichText
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RoleSelectionMessagesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `selector uses brand labels neutral names and a short action instead of storage jargon`() {
        val messages = MessageCatalogue("zh_CN", directory.toFile()).also { it.load() }
        assertEquals("请选择角色", messages.plain("role-selection.title"))
        val microsoft = messages.parsed("role-selection.microsoft", "name" to "SampleU")
        val premium = messages.parsed("role-selection.premium", "name" to "SampleJ")
        assertEquals("微软角色 » SampleU", microsoft.plainText)
        assertEquals("正版角色 » SampleJ", premium.plainText)
        assertEquals(RichText.Colour.Hex(0x22D3EE), microsoft.spans.first().style.colour)
        assertEquals(RichText.Colour.Hex(0x22D3EE), premium.spans.first().style.colour)
        assertFalse(premium.spans.any { it.style.colour == RichText.Colour.Hex(0x4ADE80) }, "Role type is not a success status")
        assertEquals("点击进入服务器", messages.plain("role-selection.enter"))
    }
}
