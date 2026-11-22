package to.wim.clauder.tasks

/** Sessions the user muted from the notification: they stay on the Tasks screen and in the
 * widget, but get no notification until unmuted. */
object MuteList {
    fun mute(muted: Set<String>, key: String): Set<String> = muted + key

    fun unmute(muted: Set<String>, key: String): Set<String> = muted - key

    /** A muted session that closed is forgotten, so the set doesn't grow forever. */
    fun afterPush(muted: Set<String>, list: TaskList): Set<String> =
        if (list.state == SessionState.Ended) muted - list.key else muted
}
