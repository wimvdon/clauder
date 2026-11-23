package to.wim.clauder.tasks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import to.wim.clauder.ClauderApp

/** The "Resolved" button on a session that waits for you: resolves the thread on claude.ai (when
 * signed in there) and tells the Worker, so it counts as done until the person writes in it again. */
class ResolveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val app = context.applicationContext as ClauderApp
        TaskNotifier.cancel(app, key)
        val pending = goAsync()
        app.scope.launch {
            try {
                // Unreachable: bring the notification back, so the button can be tried again. On
                // success the Worker's push redraws everything.
                if (!app.resolveThread(key)) {
                    TaskNotifier.applyAll(app, app.board(), app.store.muted.first(), app.store.current().notify)
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
