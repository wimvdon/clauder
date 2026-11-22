package to.wim.clauder.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import to.wim.clauder.ClauderApp
import to.wim.clauder.tasks.TaskList
import to.wim.clauder.tasks.TaskNotifier

/** Receives the Worker's task-list pushes and turns each into a Live Update. */
class ClauderMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val list = TaskList.fromPushData(message.data) ?: return
        val app = application as ClauderApp
        if (!app.seqGate.accept(list)) return
        Push.onPush(list)
        TaskNotifier.show(this, list)
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
