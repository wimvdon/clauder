package to.wim.clauder

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import to.wim.clauder.push.Push
import to.wim.clauder.settings.SettingsStore
import to.wim.clauder.tasks.SeqGate
import to.wim.clauder.tasks.TaskNotifier
import to.wim.clauder.widget.Widgets

class ClauderApp : Application() {
    val store: SettingsStore by lazy { SettingsStore(this) }
    val seqGate = SeqGate()

    /** For work that outlives a screen, such as registering a new FCM token. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Push.init(this)
        TaskNotifier.createChannels(this)
        scope.launch { Push.restore(store) }
        scope.launch { Push.refreshRegistration(store) }
        // Redraw the widgets when something they show changes in the app.
        scope.launch {
            combine(store.settings.map { it.dynamicColor to it.isConnected }, store.muted) { a, b -> a to b }
                .distinctUntilChanged()
                .drop(1)
                .collect { Widgets.updateTasks(this@ClauderApp) }
        }
        scope.launch {
            combine(store.settings.map { it.dynamicColor }, store.pinned) { a, b -> a to b }
                .distinctUntilChanged()
                .drop(1)
                .collect { Widgets.updateShortcuts(this@ClauderApp) }
        }
    }
}
