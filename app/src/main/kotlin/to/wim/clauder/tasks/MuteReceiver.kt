package to.wim.clauder.tasks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import to.wim.clauder.ClauderApp

/** The notification's Mute button: hides this session's notification until it is unmuted on
 * the Tasks screen. */
class MuteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val app = context.applicationContext as ClauderApp
        TaskNotifier.cancel(app, key)
        val pending = goAsync()
        app.scope.launch {
            try {
                val muted = MuteList.mute(app.store.muted.first(), key)
                app.store.setMuted(muted)
                // The summary leaves a muted session out.
                if (app.store.current().summaryNotification) {
                    TaskNotifier.applyAll(app, app.store.cachedSessions(), muted, summaryMode = true)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_KEY = "key"
    }
}
