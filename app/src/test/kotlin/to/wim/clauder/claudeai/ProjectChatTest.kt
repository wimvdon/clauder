package to.wim.clauder.claudeai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectChatTest {
    private fun at(time: String) = java.time.Instant.parse("2026-11-23T${time}Z").toEpochMilli()
    private val now = at("12:00:00")
    private val project = RemoteProject("chan_1", "Clauder", null, chatMark = "10", hasUnread = true)

    private fun person(id: String, time: String) = ChatMessage(id, ChatMessage.Author.Person, at(time), "hi")
    private fun claude(id: String, time: String, text: String? = "Done, PR #62 is open.") =
        ChatMessage(id, ChatMessage.Author.Claude, at(time), text)

    @Test
    fun `top-level messages only, newest first, with their author`() {
        val body = """{"data":[
            {"id":"m1","author_account_id":"user_1","author_session_id":null,"body":"Can you build it?","created_at":"2026-11-23T11:00:00.123456Z","thread_root_id":null},
            {"id":"m3","author_account_id":null,"author_session_id":"session_9","body":"Started it.","created_at":"2026-11-23T11:01:00Z","thread_root_id":"m3"},
            {"id":"m2","author_account_id":null,"author_agent_id":"agent_1","body":"a reply","created_at":"2026-11-23T11:02:00Z","thread_root_id":"m1"},
            {"id":"m4","body":"notice","created_at":"2026-11-23T11:03:00Z"}
        ],"next_cursor":null}"""
        val messages = ProjectChat.messages(body)
        assertEquals(listOf("m4", "m3", "m1"), messages.map { it.id })
        assertEquals(
            listOf(ChatMessage.Author.Other, ChatMessage.Author.Claude, ChatMessage.Author.Person),
            messages.map { it.author },
        )
        assertEquals("Started it.", messages[1].text)
        assertEquals(emptyList<ChatMessage>(), ProjectChat.messages("not json"))
    }

    @Test
    fun `the alert text is plain, short, and cards have none`() {
        assertEquals("Done: PR #62 is open. See docs", ProjectChat.text("## Done:\n\n**PR #62** is `open`. See [docs](https://x)"))
        assertNull(ProjectChat.text("""{"kind":"status","text":"Plan"}"""))
        assertNull(ProjectChat.text("   "))
        assertNull(ProjectChat.text(null))
        val long = ProjectChat.text("word ".repeat(200))!!
        assertEquals(ProjectChat.MAX_TEXT, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `the first look only remembers where the chat stands`() {
        val out = ProjectChat.check(project, null, listOf(claude("c1", "11:59:00"), person("p1", "11:58:00")), now)
        assertNull(out.reply)
        assertEquals(at("11:59:00"), out.watch.lastClaudeMillis)
        assertEquals("10", out.watch.mark)
    }

    @Test
    fun `Claude's reply after the person's message alerts once with sound`() {
        val before = ChatWatch("9", lastClaudeMillis = at("11:00:00"))
        val first = ProjectChat.check(project, before, listOf(claude("c1", "11:59:00"), person("p1", "11:58:00")), now)
        assertEquals(ChatReply("chan_1", "Clauder", "Done, PR #62 is open.", sound = true), first.reply)
        assertEquals("p1", first.watch.alertFor)
        // A second reply to the same message updates the alert quietly.
        val second = ProjectChat.check(project.copy(chatMark = "11"), first.watch, listOf(claude("c2", "11:59:30", "Merged."), claude("c1", "11:59:00"), person("p1", "11:58:00")), now)
        assertEquals(ChatReply("chan_1", "Clauder", "Merged.", sound = false), second.reply)
        // Reading it again without anything new shows nothing new.
        assertNull(ProjectChat.check(project, second.watch, listOf(claude("c2", "11:59:30"), person("p1", "11:58:00")), now).reply)
    }

    @Test
    fun `no alert for Claude's own posts before the person wrote, cards or old replies`() {
        val before = ChatWatch("9", lastClaudeMillis = at("09:00:00"))
        assertNull(ProjectChat.check(project, before, listOf(person("p1", "11:58:00"), claude("c1", "11:00:00")), now).reply)
        assertNull(ProjectChat.check(project, before, listOf(claude("c1", "11:59:00", text = null), person("p1", "11:58:00")), now).reply)
        assertNull(ProjectChat.check(project, before, listOf(claude("c1", "09:30:00"), person("p1", "09:20:00")), now).reply)
        assertNull(ProjectChat.check(project, before, listOf(claude("c1", "11:59:00")), now).reply)
    }

    @Test
    fun `a message without an answer yet is followed for half an hour`() {
        val before = ChatWatch("9", lastClaudeMillis = at("09:00:00"))
        val out = ProjectChat.check(project, before, listOf(person("p1", "11:55:00"), claude("c1", "11:00:00")), now)
        assertEquals(at("11:55:00"), out.watch.waitingSince)
        assertTrue(ProjectChat.anyWaiting(listOf(out.watch), now))
        assertFalse(ProjectChat.anyWaiting(listOf(out.watch), at("12:30:00")))
        // The chat didn't change: no read, still waiting, until the half hour is over.
        assertEquals(at("11:55:00"), ProjectChat.check(project, out.watch, null, at("12:10:00")).watch.waitingSince)
        assertNull(ProjectChat.check(project, out.watch, null, at("12:40:00")).watch.waitingSince)
    }

    @Test
    fun `the alert goes away once the chat was read in the Claude app`() {
        val shown = ChatWatch("10", at("11:59:00"), alertFor = "p1", unreadSeen = true)
        val read = ProjectChat.check(project.copy(hasUnread = false), shown, null, now)
        assertTrue(read.cancel)
        assertNull(read.watch.alertFor)
        // claude.ai never said it was unread: keep the alert.
        assertFalse(ProjectChat.check(project.copy(hasUnread = false), shown.copy(unreadSeen = false), null, now).cancel)
        assertTrue(ProjectChat.check(project, shown.copy(unreadSeen = false), null, now).watch.unreadSeen)
    }

    @Test
    fun `watches read back the same from JSON`() {
        val watches = mapOf("chan_1" to ChatWatch("10", 5, "p1", true, 7), "chan_2" to ChatWatch(null, 0))
        assertEquals(watches, ProjectChat.watchesFromJson(ProjectChat.watchesToJson(watches)))
        assertEquals(emptyMap<String, ChatWatch>(), ProjectChat.watchesFromJson(null))
    }

    @Test
    fun `a round reads only the chats whose mark changed`() = runBlocking {
        val asked = mutableListOf<String>()
        val calls = object : ClaudeAiCalls {
            override var organization: String? = null
            override suspend fun get(path: String): CallResult {
                asked += path
                return CallResult(
                    200,
                    """{"data":[{"id":"c1","author_session_id":"s","body":"Ready.","created_at":"2026-11-23T11:59:00Z"},
                        {"id":"p1","author_account_id":"u","body":"Go","created_at":"2026-11-23T11:58:00Z"}]}""",
                )
            }
            override suspend fun stream(path: String, millis: Long) = CallResult(404, "")
        }
        val same = RemoteProject("chan_2", "Other", null, chatMark = "3")
        val watches = mapOf("chan_1" to ChatWatch("9", at("10:00:00")), "chan_2" to ChatWatch("3", at("10:00:00")))
        val round = ProjectChat.round(calls, listOf(project, same), watches, now)
        assertEquals(listOf("v1/code/channels/chan_1/timeline?limit=20"), asked)
        assertEquals(listOf("Ready."), round.replies.map { it.text })
        assertEquals("10", round.watches.getValue("chan_1").mark)
        assertEquals("https://claude.ai/code/project/chan_1", ProjectChat.url("chan_1"))
    }
}
