package to.wim.clauder.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudeAppTest {
    @Test
    fun `only https claude ai links are opened`() {
        assertEquals("https://claude.ai/code", ClaudeApp.safeUrl("https://claude.ai/code"))
        assertNull(ClaudeApp.safeUrl("https://evil.example/claude.ai"))
        assertNull(ClaudeApp.safeUrl("http://claude.ai/new"))
        assertNull(ClaudeApp.safeUrl("intent://claude.ai#Intent;end"))
        assertNull(ClaudeApp.safeUrl(null))
    }
}
