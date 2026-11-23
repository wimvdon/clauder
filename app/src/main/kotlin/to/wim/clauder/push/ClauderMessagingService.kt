package to.wim.clauder.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import to.wim.clauder.ClauderApp
import to.wim.clauder.tasks.MuteList
import to.wim.clauder.tasks.NotificationPlan
import to.wim.clauder.tasks.RunAlert
import to.wim.clauder.tasks.TaskList
import to.wim.clauder.tasks.TaskNotifier
import to.wim.clauder.tile.ClauderTileService
import to.wim.clauder.widget.Widgets

/** Receives the Worker's task-list pushes and turns each into a Live Update. */
class ClauderMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val list = TaskList.fromPushData(message.data) ?: return
        val app = application as ClauderApp
        if (!app.seqGate.accept(list)) return
        // Firebase calls this on a background thread and keeps the process alive until it
        // returns, so the work is done here rather than in a coroutine that could be cut off.
        runBlocking {
            val previous = app.store.cachedSessions().firstOrNull { it.key == list.key }
            Push.record(app.store, listOf(list))
            val muted = app.store.muted.first()
            val alert = NotificationPlan.alertsAgain(previous, list, System.currentTimeMillis())
            // claude.ai's last read can say more (a thread resolved by hand), unless this push is newer.
            val board = app.board()
            // Not on the board: an older session of a thread that has a newer one, so it shows only once.
            val shown = board.firstOrNull { it.key == list.key }
            if (shown != null) TaskNotifier.apply(app, shown, board, muted, app.store.current().notify, alert) else TaskNotifier.cancel(app, list.key)
            if (list.key !in muted) RunAlert.finished(previous, list)?.let { TaskNotifier.showRunFinished(app, list, it) }
            val after = MuteList.afterPush(muted, list)
            if (after != muted) app.store.setMuted(after)
            Widgets.updateTasks(app)
            ClauderTileService.refresh(app)
        }
        app.refreshClaudeAi()
    }

    // Firebase Messaging 26 reports a new token through onRegistered; older paths still call
    // onNewToken. Both register the same way, and a token that is already registered is skipped.
    override fun onRegistered(token: String) = reRegister(token)

    @Deprecated("Replaced by onRegistered in Firebase Messaging 26")
    @Suppress("DEPRECATION")
    override fun onNewToken(token: String) = reRegister(token)

    private fun reRegister(token: String) {
        val app = application as ClauderApp
        app.scope.launch { Push.refreshRegistration(app.store, token) }
    }
}
