package moe.ryuu.kyokouauth.bungee

import moe.ryuu.kyokouauth.core.text.RichText
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.chat.ClickEvent
import org.junit.jupiter.api.Test
import kotlin.test.*

class ComponentsTest {
    @Test fun `native components preserve RGB siblings styles Unicode and full click URL`() {
        val url = "https://microsoft.com/link?otc=TEST-CODE&next=%E4%B8%AD%E6%96%87"
        val rich = RichText.parse("<#55aaff><bold>中文 Ω</bold></#55aaff> plain <click:open_url:$url><underlined>登录</underlined></click>")
        val component = rich.toBungee()
        assertEquals(rich.plainText, component.toPlainText())
        assertEquals(ChatColor.of("#55aaff"), component.extra[0].color)
        assertTrue(component.extra[0].isBold)
        assertFalse(component.extra[1].isBold)
        assertNull(component.extra[1].clickEvent)
        val link = component.extra.last()
        assertEquals(ClickEvent.Action.OPEN_URL, link.clickEvent.action)
        assertEquals(url, link.clickEvent.value)
        assertTrue(link.isUnderlined)
    }
}
