package to.wim.clauder.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GithubStatusTest {
    @Test
    fun `not connected has nothing to install and no events`() {
        val status = GithubStatus.fromJson(JSONObject("""{"connected":false,"app":null,"installUrl":null,"lastEventAt":null}"""))
        assertEquals(GithubStatus(false, null, null), status)
    }

    @Test
    fun `connected says where to choose repositories and when GitHub last sent something`() {
        val status = GithubStatus.fromJson(
            JSONObject("""{"connected":true,"installUrl":"https://github.com/apps/clauder-x/installations/new","lastEventAt":"2026-11-23T10:00:00.000Z"}"""),
        )
        assertEquals("https://github.com/apps/clauder-x/installations/new", status.installUrl)
        assertEquals(java.time.Instant.parse("2026-11-23T10:00:00Z").toEpochMilli(), status.lastEventAtMillis)
        // Only GitHub links are opened.
        assertNull(GithubStatus.fromJson(JSONObject("""{"connected":true,"installUrl":"https://evil.example/x"}""")).installUrl)
    }

    @Test
    fun `the connect link must be this Worker's start page`() {
        val worker = "https://clauder.example.workers.dev"
        assertEquals(
            "$worker/github/start?state=abc",
            GithubStatus.connectUrl(JSONObject("""{"url":"$worker/github/start?state=abc"}"""), worker),
        )
        assertNull(GithubStatus.connectUrl(JSONObject("""{"url":"https://evil.example/github/start?state=abc"}"""), worker))
        assertNull(GithubStatus.connectUrl(JSONObject("{}"), worker))
    }
}
