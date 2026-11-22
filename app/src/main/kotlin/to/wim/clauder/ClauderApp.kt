package to.wim.clauder

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import to.wim.clauder.push.Push
import to.wim.clauder.settings.SettingsStore
import to.wim.clauder.tasks.SeqGate
import to.wim.clauder.tasks.TaskNotifier

class ClauderApp : Application() {
    val store: SettingsStore by lazy { SettingsStore(this) }
    val seqGate = SeqGate()

    /** For work that outlives a screen, such as registering a new FCM token. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Push.init(this)
        TaskNotifier.createChannels(this)
        scope.launch { Push.refreshRegistration(store) }
    }
}
