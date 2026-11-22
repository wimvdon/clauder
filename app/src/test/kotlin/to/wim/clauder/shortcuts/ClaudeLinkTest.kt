package to.wim.clauder.shortcuts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudeLinkTest {
    @Test
    fun `recognises the kinds of claude links`() {
        assertEquals(ClaudeLink.Kind.Chat, ClaudeLink.parse("https://claude.ai/chat/123")?.kind)
        assertEquals(ClaudeLink.Kind.Project, ClaudeLink.parse("https://claude.ai/project/abc")?.kind)
        assertEquals(ClaudeLink.Kind.Artifact, ClaudeLink.parse("https://claude.ai/public/artifacts/x")?.kind)
        assertEquals(ClaudeLink.Kind.CodeSession, ClaudeLink.parse("https://claude.ai/code/session_01ab")?.kind)
        assertEquals(ClaudeLink.Kind.Other, ClaudeLink.parse("https://claude.ai/new")?.kind)
    }

    @Test
    fun `only https claude ai counts`() {
        assertNull(ClaudeLink.parse("http://claude.ai/chat/1"))
        assertNull(ClaudeLink.parse("https://claude.ai.evil.example/chat/1"))
        assertNull(ClaudeLink.parse("https://evil.example/?u=https://claude.ai"))
        assertNull(ClaudeLink.parse("https://user@claude.ai/chat/1"))
        assertNull(ClaudeLink.parse("https://claude.ai:8443/chat/1"))
    }

    @Test
    fun `the url is rebuilt without a fragment`() {
        assertEquals("https://claude.ai/chat/1?x=2", ClaudeLink.parse("https://CLAUDE.ai/chat/1?x=2#top")?.url)
        assertEquals("https://claude.ai/", ClaudeLink.parse("https://claude.ai")?.url)
    }

    @Test
    fun `finds the link in shared text`() {
        assertEquals("https://claude.ai/chat/9", ClaudeLink.find("Look at this: https://claude.ai/chat/9.")?.url)
        assertEquals("https://claude.ai/chat/9", ClaudeLink.find("claude.ai/chat/9")?.url)
        assertNull(ClaudeLink.find("nothing here"))
    }

    @Test
    fun `pinned shortcuts survive a round trip and bad entries are dropped`() {
        val list = listOf(PinnedShortcut("a", "My chat", "https://claude.ai/chat/1", ClaudeLink.Kind.Chat))
        assertEquals(list, PinnedCodec.decode(PinnedCodec.encode(list)))
        assertEquals(emptyList<PinnedShortcut>(), PinnedCodec.decode("""[{"id":"b","name":"x","url":"https://evil.example/"}]"""))
        assertEquals(emptyList<PinnedShortcut>(), PinnedCodec.decode("garbage"))
        assertEquals(emptyList<PinnedShortcut>(), PinnedCodec.decode(null))
    }
}
