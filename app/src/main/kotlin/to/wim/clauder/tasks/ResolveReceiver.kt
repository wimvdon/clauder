package to.wim.clauder.tasks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import to.wim.clauder.ClauderApp
import to.wim.clauder.push.WorkerClient

/** The "Resolved" button on a session that waits for you. Resolving a thread in the Claude app
 * sends Clauder nothing, so this tells the Worker instead; the thread counts as done until the
 * person writes in it again. */
class ResolveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val app = context.applicationContext as ClauderApp
        TaskNotifier.cancel(app, key)
        val pending = goAsync()
        app.scope.launch {
            try {
                val settings = app.store.current()
                val result = if (settings.isConnected) WorkerClient(settings.workerUrl, settings.token).resolve(key) else null
                // Unreachable: bring the notification back, so the button can be tried again. On
                // success the Worker's push redraws everything.
                if (result !is WorkerClient.Result.Ok) {
                    TaskNotifier.applyAll(app, app.store.cachedSessions(), app.store.muted.first(), settings.notify)
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
