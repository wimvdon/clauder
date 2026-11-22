package to.wim.clauder.shortcuts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import to.wim.clauder.links.ClaudeApp
import javax.xml.parsers.DocumentBuilderFactory

class PinnedShortcutsTest {
    private val chat = ClaudeLink.parse("https://claude.ai/chat/1")!!

    @Test
    fun `the add dialog needs a claude link and a name`() {
        assertEquals(PinnedShortcuts.Draft.Valid(chat, "My chat"), PinnedShortcuts.draft(" https://claude.ai/chat/1 ", "  My   chat "))
        assertEquals(PinnedShortcuts.Draft.BadLink, PinnedShortcuts.draft("https://example.com/chat/1", "x"))
        assertEquals(PinnedShortcuts.Draft.MissingName, PinnedShortcuts.draft("https://claude.ai/chat/1", "   "))
    }

    @Test
    fun `names are trimmed and kept short`() {
        assertEquals("a b", PinnedShortcuts.cleanName(" a \n b "))
        assertEquals(PinnedShortcuts.MAX_NAME, PinnedShortcuts.cleanName("x".repeat(100))!!.length)
        assertNull(PinnedShortcuts.cleanName(""))
    }

    @Test
    fun `add, rename and remove`() {
        var list = PinnedShortcuts.add(emptyList(), chat, "One", "pin_1")
        list = PinnedShortcuts.add(list, ClaudeLink.parse("https://claude.ai/project/p")!!, "Two", "pin_2")
        assertEquals(listOf("One", "Two"), list.map { it.name })
        assertEquals(ClaudeLink.Kind.Project, list[1].kind)
        list = PinnedShortcuts.rename(list, "pin_1", "First")
        assertEquals(listOf("First", "Two"), list.map { it.name })
        list = PinnedShortcuts.remove(list, "pin_2")
        assertEquals(listOf("pin_1"), list.map { it.id })
    }

    @Test
    fun `only shortcuts still on the home screen are shown, unless the launcher can't say`() {
        val list = PinnedShortcuts.add(PinnedShortcuts.add(emptyList(), chat, "One", "pin_1"), chat, "Two", "pin_2")
        assertEquals(listOf("pin_2"), PinnedShortcuts.visible(list, setOf("pin_2", "other")).map { it.id })
        assertEquals(list, PinnedShortcuts.visible(list, null))
        assertEquals("pin_42", PinnedShortcuts.newId(42))
    }

    @Test
    fun `the static shortcuts open the briefing's pages`() {
        // Unit tests run in the app module's folder.
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/shortcuts.xml"))
        val intents = doc.getElementsByTagName("intent")
        val urls = (0 until intents.length).map { (intents.item(it) as org.w3c.dom.Element).getAttribute("android:data") }
        assertEquals(
            listOf(StaticShortcuts.NEW_CHAT, StaticShortcuts.CODE, StaticShortcuts.PROJECTS, StaticShortcuts.ARTIFACTS),
            urls,
        )
        // They go through Clauder's link opener, which picks the Claude app over the browser.
        val actions = (0 until intents.length).map { (intents.item(it) as org.w3c.dom.Element).getAttribute("android:action") }
        assertEquals(List(4) { ClaudeApp.ACTION_OPEN }, actions)
    }
}
