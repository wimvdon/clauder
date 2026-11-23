package to.wim.clauder.claudeai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

class ClaudeAiTest {

    @Test
    fun `finds the session key among other cookies`() {
        assertEquals("sk-ant-sid01-abc", ClaudeAi.sessionKeyFrom("lastActiveOrg=org1; sessionKey=sk-ant-sid01-abc; anthropic-device-id=d"))
        assertEquals("sk-ant-sid01-abc", ClaudeAi.sessionKeyFrom("sessionKey=sk-ant-sid01-abc"))
    }

    @Test
    fun `no session key without the cookie`() {
        assertNull(ClaudeAi.sessionKeyFrom(null))
        assertNull(ClaudeAi.sessionKeyFrom(""))
        assertNull(ClaudeAi.sessionKeyFrom("sessionKey=; other=1"))
        // Only the exact name counts, not sessionKeyLC.
        assertNull(ClaudeAi.sessionKeyFrom("sessionKeyLC=abc"))
    }

    @Test
    fun `paths encode the channel id`() {
        assertEquals("v1/code/channels/chan_1/threads?limit=20", ClaudeAi.Paths.threads("chan_1", 20))
        assertEquals("v1/code/channels/a%2Fb/messages/stream?scope=channel", ClaudeAi.Paths.channelStream("a/b"))
    }

    @Test
    fun `sealed text opens with the same key only`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val sealed = SealedText.seal(key, "sk-ant-sid01-secret")
        assertFalse(sealed.contains("secret"))
        assertEquals("sk-ant-sid01-secret", SealedText.open(key, sealed))
        assertNull(SealedText.open(other, sealed))
        assertNull(SealedText.open(key, "not base64 !"))
        assertNull(SealedText.open(key, ""))
    }

    private val channels = """{"data":[{"id":"chan_1","name":"Clauder","has_working_session":true}],"next_cursor":null}"""
    private val threads = """
        {"sections":[
          {"status":"working","count":2,"data":[{"id":"cmsg_1","thread_status":"working","display_name":"Fix it",
            "bound_sessions":[{"id":"session_1","status":"running","title":"Fix it"}]}],"next_cursor":"c"},
          {"status":"needs_input","count":1,"data":[{"id":"cmsg_2","thread_status":"needs_input"}]}
        ]}
    """.trimIndent()

    @Test
    fun `threads are described by status, counts and field names only`() {
        val text = Probe.describeThreads(threads)
        assertEquals(
            "sections=[working=2/1, needs_input=1/1], keys=[sections], thread_status=[needs_input,working], " +
                "thread keys=[bound_sessions,display_name,id,thread_status], session keys=[id,status,title]",
            text,
        )
        assertFalse(text.contains("Fix it"))
    }

    private val timeline = """
        {"data":[
          {"id":"m3","author_session_id":"session_9","body":"Here is the answer","created_at":"2026-11-23T09:42:00Z"},
          {"id":"m2","thread_root_id":"m1","author_account_id":"acc","body":"thanks","created_at":"2026-11-23T09:41:00Z"},
          {"id":"m1","thread_root_id":"m1","author_account_id":"acc","author_session_id":null,"body":"Question?","created_at":"2026-11-23T09:40:00Z"},
          {"id":"m0","server_notice":"x","created_at":"2026-11-23T09:39:00Z"}
        ],"next_cursor":null}
    """.trimIndent()

    @Test
    fun `project chat is described by author kind, place and time, without text`() {
        val text = Probe.describeTimeline(timeline)
        assertTrue(
            text,
            text.endsWith(
                "newest=[claude/top@2026-11-23T09:42:00Z person/reply@2026-11-23T09:41:00Z " +
                    "person/top@2026-11-23T09:40:00Z other/top@2026-11-23T09:39:00Z]",
            ),
        )
        assertTrue(text.startsWith("messages=4, keys=[data,next_cursor], message keys=[author_session_id,body,created_at,id]"))
        assertFalse(text.contains("answer"))
        assertFalse(text.contains("Question"))
    }

    @Test
    fun `streams are described by event names and data keys`() {
        val body = "event: thread_resolved\ndata: {\"thread_root_id\":\"cmsg_1\",\"resolved_at\":\"x\"}\n\n: keep-alive\ndata: ping\n"
        assertEquals("events=[thread_resolved], data lines=2, data keys=[resolved_at,thread_root_id]", Probe.describeStream(body))
    }

    @Test
    fun `a Cloudflare check is named`() {
        val line = Probe.line("channels", CallResult(403, "<title>Just a moment...</title>")) { it }
        assertEquals("channels: HTTP 403, Cloudflare check", line)
        assertEquals("channels: error UnknownHostException", Probe.line("channels", CallResult(error = "UnknownHostException")) { it })
    }

    @Test
    fun `the probe follows the first project and never repeats names or messages`() = runBlocking {
        val asked = mutableListOf<String>()
        val calls = object : ClaudeAiCalls {
            override var organization: String? = null

            override suspend fun get(path: String): CallResult {
                asked += path
                return when {
                    path == "api/organizations" -> CallResult(200, """[{"uuid":"$ORG","name":"Fix it"}]""")
                    path.startsWith("v1/code/channels?") -> CallResult(200, channels)
                    path.contains("/threads") -> CallResult(200, threads)
                    path.contains("/timeline") -> CallResult(200, timeline)
                    else -> CallResult(200, """{"unseen_count":3,"newest_cursor":"n"}""")
                }
            }

            override suspend fun stream(path: String, millis: Long): CallResult {
                asked += path
                assertEquals(ORG, organization)
                return CallResult(200, "event: session_activity\ndata: {\"session_id\":\"s\",\"working\":true}\n")
            }
        }
        val report = Probe.run(calls)
        assertEquals(
            listOf(
                "api/organizations",
                "v1/code/channels?scope=joined&limit=20",
                "v1/code/channels/chan_1/threads?limit=20",
                "v1/code/channels/chan_1/timeline?limit=10",
                "v1/code/channels/chan_1/messages/stream?scope=channel",
                "v1/code/notifications/summary",
                "v1/code/sessions/watch",
            ),
            asked,
        )
        assertEquals("organizations: HTTP 200, 1 in list, capabilities=[]", report.lines().first())
        assertTrue(report.lines()[1].startsWith("channels (org 1 of 1): HTTP 200, projects=1"))
        assertTrue(report.contains("notifications summary: HTTP 200, [newest_cursor,unseen_count]"))
        assertFalse(report.contains("Clauder"))
        assertFalse(report.contains("Fix it"))
    }

    @Test
    fun `no project means no thread calls`() = runBlocking {
        val tried = mutableListOf<String?>()
        val calls = object : ClaudeAiCalls {
            override var organization: String? = null
            override suspend fun get(path: String): CallResult = when {
                path == "api/organizations" -> CallResult(200, """[{"uuid":"$OTHER"},{"uuid":"$ORG"}]""")
                path.startsWith("v1/code/channels?") -> {
                    tried += organization
                    CallResult(400, """{"type":"error","error":{"type":"invalid_request_error","message":"scope is required"}}""")
                }
                else -> CallResult(404, """{"type":"error","error":{"type":"not_found_error","message":"Not found"}}""")
            }
            override suspend fun stream(path: String, millis: Long) = CallResult(401, "")
        }
        val report = Probe.run(calls, savedOrganization = ORG)
        assertEquals(listOf<String?>(ORG, OTHER), tried)
        assertTrue(report.contains("sign-in org=#2"))
        assertTrue(report.contains("channels (org 1 of 2): HTTP 400, invalid_request_error: scope is required"))
        assertTrue(report.contains("channels (org 2 of 2): HTTP 400"))
        assertTrue(report.contains("threads: skipped (no project)"))
        assertTrue(report.contains("notifications summary: HTTP 404, not_found_error: Not found"))
        assertFalse(report.contains(ORG))
    }

    @Test
    fun `the first organization that answers is kept`() = runBlocking {
        val calls = object : ClaudeAiCalls {
            override var organization: String? = null
            override suspend fun get(path: String): CallResult = when {
                path == "api/organizations" -> CallResult(200, """[{"uuid":"$OTHER"},{"uuid":"$ORG"}]""")
                path.startsWith("v1/code/channels?") ->
                    if (organization == ORG) CallResult(200, """{"data":[]}""") else CallResult(400, "{}")
                else -> CallResult(200, "{}")
            }
            override suspend fun stream(path: String, millis: Long) = CallResult(200, "")
        }
        val report = Probe.run(calls)
        assertEquals(ORG, calls.organization)
        assertTrue(report.contains("channels (org 2 of 2): HTTP 200, projects=0"))
    }

    @Test
    fun `the organization comes from the sign-in cookie or the list`() {
        assertEquals(ORG, ClaudeAi.organizationFrom("a=1; lastActiveOrg=$ORG; sessionKey=k"))
        assertNull(ClaudeAi.organizationFrom("lastActiveOrg=not-a-uuid"))
        assertNull(ClaudeAi.organizationFrom(null))
        assertEquals(listOf(ORG), ClaudeAi.organizations("""[{"name":"x"},{"uuid":"$ORG"}]"""))
        assertEquals(emptyList<String>(), ClaudeAi.organizations("{}"))
        assertEquals(
            listOf("[chat,claude_max]", "[]"),
            ClaudeAi.organizationCapabilities("""[{"uuid":"$ORG","name":"Secret","capabilities":["claude_max","chat"]},{"uuid":"x"}]"""),
        )
    }

    @Test
    fun `calls go to the site root with the Claude app's headers`() {
        assertEquals("https://claude.ai/", ClaudeAi.BASE)
        val headers = ClaudeAi.headers("k", ORG, "application/json")
        assertEquals("sessionKey=k", headers["Cookie"])
        assertEquals("2023-06-01", headers["Anthropic-Version"])
        assertEquals("ccr-byoc-2025-07-29", headers["Anthropic-Beta"])
        assertEquals(ORG, headers["X-Organization-Uuid"])
        assertFalse(ClaudeAi.headers("k", null, "text/event-stream").containsKey("X-Organization-Uuid"))
    }

    private companion object {
        const val ORG = "0b2c6a1e-3f4d-4e5a-9b8c-7d6e5f4a3b2c"
        const val OTHER = "11111111-2222-4333-8444-555555555555"
    }
}
