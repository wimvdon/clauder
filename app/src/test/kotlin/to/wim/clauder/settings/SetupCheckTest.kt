package to.wim.clauder.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupCheckTest {
    private fun check(json: String) = SetupCheck.from(HookStatus.fromJson(JSONObject(json)))

    @Test
    fun `no event yet asks for the steps and a new thread`() {
        assertEquals(SetupCheck.NoEvents, check("""{"hooksVersion":"v","lastEvent":null}"""))
        assertEquals(SetupCheck.NoEvents, check("""{}"""))
    }

    @Test
    fun `an event from current or unlabeled hooks means the hooks work`() {
        val at = java.time.Instant.parse("2026-11-23T09:00:00.000Z").toEpochMilli()
        assertEquals(
            SetupCheck.Working(at),
            check("""{"lastEvent":{"at":"2026-11-23T09:00:00.000Z","scriptCurrent":true,"settingsCurrent":true}}"""),
        )
        // An old script updates itself at the next prompt, and HTTP hooks don't name a script.
        assertEquals(
            SetupCheck.Working(at),
            check("""{"lastEvent":{"at":"2026-11-23T09:00:00.000Z","scriptCurrent":false,"settingsCurrent":null}}"""),
        )
    }

    @Test
    fun `an event from a session on old settings says so`() {
        val at = java.time.Instant.parse("2026-11-23T09:00:00.000Z").toEpochMilli()
        assertEquals(
            SetupCheck.OldHooks(at),
            check("""{"lastEvent":{"at":"2026-11-23T09:00:00.000Z","scriptCurrent":true,"settingsCurrent":false}}"""),
        )
    }
}
