package to.wim.clauder.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test
    fun `worker addresses are normalised to https hosts`() {
        assertEquals("https://clauder.wim-d57.workers.dev", WorkerUrl.normalize("clauder.wim-d57.workers.dev"))
        assertEquals("https://clauder.wim-d57.workers.dev", WorkerUrl.normalize(" https://Clauder.wim-d57.workers.dev/ "))
        assertEquals("https://example.com:8443", WorkerUrl.normalize("https://example.com:8443"))
        assertNull(WorkerUrl.normalize(""))
        assertNull(WorkerUrl.normalize("http://clauder.example.dev"))
        assertNull(WorkerUrl.normalize("localhost"))
        assertNull(WorkerUrl.normalize("https://user@example.com"))
        assertNull(WorkerUrl.normalize("not a url"))
        assertEquals("clauder.wim-d57.workers.dev", WorkerUrl.host("https://clauder.wim-d57.workers.dev"))
        assertEquals("", WorkerUrl.host(""))
    }

    @Test
    fun `waiting alerts start on and travel with the summary setting`() {
        assertEquals(to.wim.clauder.tasks.NotifyPrefs(summary = false, waitingAlerts = true), Settings().notify)
        assertEquals(
            to.wim.clauder.tasks.NotifyPrefs(summary = true, waitingAlerts = false),
            Settings(summaryNotification = true, waitingAlerts = false).notify,
        )
    }

    @Test
    fun `connected needs both an address and a token`() {
        assertFalse(Settings().isConnected)
        assertFalse(Settings(workerUrl = "https://a.dev").isConnected)
        assertTrue(Settings(workerUrl = "https://a.dev", token = "t").isConnected)
    }

    @Test
    fun `the setup line fetches the installer from this phone's Worker`() {
        assertEquals(
            "curl -fsS https://w.example.dev/install.sh | bash || echo \"WARN: Clauder hooks not installed\"",
            HookSetup.script("w.example.dev"),
        )
    }
}
