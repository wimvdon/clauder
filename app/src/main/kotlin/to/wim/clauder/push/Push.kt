package to.wim.clauder.push

import android.content.Context
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import to.wim.clauder.BuildConfig
import to.wim.clauder.settings.SettingsStore
import to.wim.clauder.settings.WorkerUrl
import to.wim.clauder.tasks.SessionBoard
import to.wim.clauder.tasks.TaskList
import kotlin.coroutines.resume

/** Firebase setup and the link between this phone and the Worker. */
object Push {
    /** True when this build has a Firebase key (CI builds do; a local build may not). */
    val available: Boolean get() = BuildConfig.FIREBASE_API_KEY.isNotBlank()

    private val sessionsFlow = MutableStateFlow<List<TaskList>>(emptyList())

    /** Sessions shown on the Tasks screen, updated by every push while the app runs. */
    val sessions: StateFlow<List<TaskList>> = sessionsFlow.asStateFlow()

    fun onPush(list: TaskList) = sessionsFlow.update { SessionBoard.merge(it, list) }

    fun onFetched(lists: List<TaskList>) = sessionsFlow.update { SessionBoard.mergeAll(it, lists) }

    /** Called once from [to.wim.clauder.ClauderApp]. There is no google-services.json: the
     * options come from BuildConfig, so no Firebase file is kept in the repository. */
    fun init(context: Context) {
        if (!available || FirebaseApp.getApps(context).isNotEmpty()) return
        val options = FirebaseOptions.Builder()
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
            .build()
        FirebaseApp.initializeApp(context, options)
    }

    // getToken is deprecated in Firebase Messaging 26 in favour of register() plus a callback,
    // but it still returns the same token and keeps connecting a single suspend call.
    @Suppress("DEPRECATION")
    private suspend fun fcmToken(): String? {
        if (!available) return null
        return suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                cont.resume(if (task.isSuccessful) task.result else null)
            }
        }
    }

    private fun deviceName(): String = Build.MODEL.ifBlank { "Android" }

    /** Checks the address and token, registers this phone and saves the connection. */
    suspend fun connect(store: SettingsStore, address: String, token: String): ConnectOutcome {
        val url = WorkerUrl.normalize(address) ?: return ConnectOutcome.InvalidAddress
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) return ConnectOutcome.MissingToken
        val fcm = fcmToken() ?: return ConnectOutcome.PushUnavailable
        val outcome = Connection.outcome(WorkerClient(url, cleanToken).register(fcm, deviceName()))
        if (outcome is ConnectOutcome.Connected) {
            store.setConnection(url, cleanToken)
            store.setRegisteredFcmToken(fcm)
        }
        return outcome
    }

    /** Tells the Worker to stop sending to this phone, then forgets the connection. */
    suspend fun disconnect(store: SettingsStore) {
        val settings = store.current()
        if (settings.isConnected && settings.registeredFcmToken.isNotBlank()) {
            WorkerClient(settings.workerUrl, settings.token).unregister(settings.registeredFcmToken)
        }
        store.clearConnection()
        sessionsFlow.value = emptyList()
    }

    /** Registers again when Firebase handed out a new token since the last registration. */
    suspend fun refreshRegistration(store: SettingsStore, newToken: String? = null) {
        val settings = store.current()
        if (!settings.isConnected) return
        val fcm = newToken ?: fcmToken() ?: return
        if (fcm == settings.registeredFcmToken) return
        val result = WorkerClient(settings.workerUrl, settings.token).register(fcm, deviceName())
        if (result is WorkerClient.Result.Ok) store.setRegisteredFcmToken(fcm)
    }
}
