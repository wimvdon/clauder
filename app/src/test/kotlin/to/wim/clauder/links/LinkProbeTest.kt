package to.wim.clauder.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkProbeTest {
    @Test
    fun `every candidate has its own label`() {
        val labels = LinkProbes.all.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `candidates cover the claude scheme, https and the app's own activities`() {
        val all = LinkProbes.all
        assertTrue(all.any { it.uri == "claude://projects" && it.pkg == ClaudeApp.PACKAGE })
        assertTrue(all.any { it.uri == "https://claude.ai/artifacts" && it.pkg == null })
        assertTrue(all.any { it.component == LinkProbes.DEEP_LINK_ACTIVITY && it.uri == "https://claude.ai/projects" })
        assertTrue(all.any { it.launcher })
    }

    @Test
    fun `labels say where an intent goes`() {
        assertEquals("VIEW claude://projects · Claude app only", LinkProbe(uri = "claude://projects", pkg = ClaudeApp.PACKAGE).label)
        assertEquals("VIEW https://claude.ai/projects", LinkProbe(uri = "https://claude.ai/projects").label)
        assertEquals(
            "VIEW https://claude.ai/projects → .deeplink.DeepLinkActivity",
            LinkProbe(uri = "https://claude.ai/projects", pkg = ClaudeApp.PACKAGE, component = LinkProbes.DEEP_LINK_ACTIVITY).label,
        )
        assertEquals(
            "VIEW → .deeplink.DeepLinkActivity + EXTRA_SHORTCUT_TYPE=CHAT",
            LinkProbe(pkg = ClaudeApp.PACKAGE, component = LinkProbes.DEEP_LINK_ACTIVITY, extras = mapOf("EXTRA_SHORTCUT_TYPE" to "CHAT")).label,
        )
    }

    @Test
    fun `typed text becomes a probe only when it is a link`() {
        assertNull(LinkProbes.custom("  ", claudeOnly = true))
        assertNull(LinkProbes.custom("claude.ai/projects", claudeOnly = true))
        assertEquals(LinkProbe(uri = "claude://x", pkg = ClaudeApp.PACKAGE), LinkProbes.custom(" claude://x ", claudeOnly = true))
        assertEquals(LinkProbe(uri = "claude://x"), LinkProbes.custom("claude://x", claudeOnly = false))
        val intent = "intent:#Intent;component=com.anthropic.claude/.x;end"
        assertEquals(LinkProbe(intentUri = intent), LinkProbes.custom(intent, claudeOnly = true))
    }

    @Test
    fun `the report lists what each probe resolved to and did`() {
        val probe = LinkProbe(uri = "claude://projects", pkg = ClaudeApp.PACKAGE)
        val other = LinkProbe(uri = "claude://code", pkg = ClaudeApp.PACKAGE)
        val report = LinkProbes.report(
            "1.2",
            listOf(".MainActivity"),
            listOf(
                Triple(probe, ProbeResolution("com.anthropic.claude/.MainActivity", filter = "schemes claude"), ProbeOutcome.Started),
                Triple(other, ProbeResolution(null), ProbeOutcome.Failed("denied")),
            ),
        )
        assertEquals(
            """
            Claude app: 1.2
            Exported activities: .MainActivity
            VIEW claude://projects · Claude app only
              resolves to: com.anthropic.claude/.MainActivity
              filter: schemes claude
              result: started
            VIEW claude://code · Claude app only
              resolves to: nothing
              result: failed: denied
            """.trimIndent(),
            report,
        )
    }
}
