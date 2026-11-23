package to.wim.clauder

import android.app.Application
import androidx.core.content.pm.ShortcutManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import to.wim.clauder.claudeai.ClaudeAiClient
import to.wim.clauder.claudeai.ClaudeAiJob
import to.wim.clauder.claudeai.ClaudeAiLogin
import to.wim.clauder.claudeai.ClaudeAiSync
import to.wim.clauder.claudeai.ProjectChat
import to.wim.clauder.claudeai.RemoteSnapshot
import to.wim.clauder.claudeai.RemoteThreads
import to.wim.clauder.push.Push
import to.wim.clauder.settings.SettingsStore
import to.wim.clauder.tasks.NotificationPlan
import to.wim.clauder.tasks.SeqGate
import to.wim.clauder.tasks.TaskList
import to.wim.clauder.tasks.TaskNotifier
import to.wim.clauder.tile.ClauderTileService
import to.wim.clauder.widget.Widgets

class ClauderApp : Application() {
    val store: SettingsStore by lazy { SettingsStore(this) }
    val seqGate = SeqGate()
    val claudeAiLogin: ClaudeAiLogin by lazy { ClaudeAiLogin(this) }

    /** For work that outlives a screen, such as registering a new FCM token. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Push.init(this)
        TaskNotifier.createChannels(this)
        scope.launch { Push.restore(store) }
        scope.launch {
            ClaudeAiSync.restore(claudeAiLogin.snapshot.first())
            if (claudeAiLogin.isConnected.first()) ClaudeAiJob.schedule(this@ClauderApp)
        }
        scope.launch { Push.refreshRegistration(store) }
        // Redraw the widgets when something they show changes in the app.
        scope.launch {
            combine(store.settings.map { it.dynamicColor to it.isConnected }, store.muted) { a, b -> a to b }
                .distinctUntilChanged()
                .drop(1)
                .collect { Widgets.updateTasks(this@ClauderApp) }
        }
        scope.launch { retireShortcuts() }
        // Switching to or from the summary, or turning the waiting alerts on or off, redraws them all.
        scope.launch {
            store.settings.map { it.notify }
                .distinctUntilChanged()
                .drop(1)
                .collect { prefs ->
                    TaskNotifier.applyAll(this@ClauderApp, board(), store.muted.first(), prefs)
                }
        }
    }

    /** Every session the phone knows, with claude.ai's last read laid over the hook sessions. */
    suspend fun board(): List<TaskList> =
        RemoteThreads.overlay(store.cachedSessions(), ClaudeAiSync.snapshot.value ?: claudeAiLogin.snapshot.first())

    /** Reads claude.ai in the background, when signed in there (see [syncClaudeAi]). */
    fun refreshClaudeAi() {
        scope.launch { syncClaudeAi() }
    }

    private val claudeAiSyncing = Mutex()

    /**
     * Reads claude.ai's thread lists ([ClaudeAiSync]) and project chats ([ProjectChat]), when signed
     * in there, and passes what changed on to the notifications, the widget and the tile. While a
     * project chat waits for Claude's reply, it reads again in about a minute ([ClaudeAiJob]).
     */
    suspend fun syncClaudeAi() {
        if (claudeAiSyncing.isLocked) return
        claudeAiSyncing.withLock {
            val key = claudeAiLogin.sessionKey() ?: return
            val now = System.currentTimeMillis()
            val watches = claudeAiLogin.chatWatches()
            val before = ClaudeAiSync.snapshot.value ?: claudeAiLogin.snapshot.first()
            ClaudeAiSync.restore(before)
            val calls = ClaudeAiClient(key)
            val snapshot = ClaudeAiSync.refresh(calls, claudeAiLogin.organization(), now) { claudeAiLogin.saveOrganization(it) }
            if (snapshot == null) {
                // Skipped (a read ran less than a minute ago) or claude.ai didn't answer: keep following.
                if (ProjectChat.anyWaiting(watches.values, now)) ClaudeAiJob.scheduleSoon(this)
                return
            }
            claudeAiLogin.saveSnapshot(snapshot)
            showRemoteChanges(before, snapshot)
            val round = ProjectChat.round(calls, snapshot.projects, watches, now)
            claudeAiLogin.saveChatWatches(round.watches)
            round.replies.forEach { TaskNotifier.showChatReply(this, it) }
            round.cancel.forEach { TaskNotifier.cancelChatReply(this, it) }
            if (ProjectChat.anyWaiting(round.watches.values, now)) ClaudeAiJob.scheduleSoon(this)
        }
    }

    /** Updates the notifications of sessions whose shown state the new read changed. The first read
     * after signing in shows what is there without a sound. */
    private suspend fun showRemoteChanges(before: RemoteSnapshot?, after: RemoteSnapshot) {
        val cached = store.cachedSessions()
        val board = RemoteThreads.overlay(cached, after)
        val changes = RemoteThreads.changes(RemoteThreads.overlay(cached, before), board)
        if (changes.shown.isEmpty() && changes.gone.isEmpty()) return
        val muted = store.muted.first()
        val prefs = store.current().notify
        val now = System.currentTimeMillis()
        changes.gone.forEach { TaskNotifier.cancel(this, it) }
        changes.shown.forEach { (previous, list) ->
            val alert = before != null && NotificationPlan.alertsAgain(previous, list, now)
            TaskNotifier.apply(this, list, board, muted, prefs, alert)
        }
        Widgets.updateTasks(this)
        ClauderTileService.refresh(this)
    }

    /** Clauder no longer makes shortcuts. Shortcuts pinned by an earlier version are greyed out
     * with a note, since only the launcher can remove them. */
    private suspend fun retireShortcuts() {
        store.forgetPinned()
        val pinned = ShortcutManagerCompat.getShortcuts(this, ShortcutManagerCompat.FLAG_MATCH_PINNED).map { it.id }
        if (pinned.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(this, pinned, getString(R.string.shortcuts_removed))
        ShortcutManagerCompat.removeAllDynamicShortcuts(this)
    }
}
