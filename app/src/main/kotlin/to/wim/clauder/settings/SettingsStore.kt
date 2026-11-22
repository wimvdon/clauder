package to.wim.clauder.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import to.wim.clauder.tasks.TaskList

/** Backed up by Android (see res/xml/data_extraction_rules.xml): theme, notification style and
 * Worker address. */
private val Context.backedUp: DataStore<Preferences> by preferencesDataStore(name = "backed_up_settings")

/** Never backed up: the Worker token, FCM registration and the cached sessions. */
private val Context.deviceOnly: DataStore<Preferences> by preferencesDataStore(name = "device_state")

class SettingsStore(context: Context) {
    private val backedUp = context.applicationContext.backedUp
    private val deviceOnly = context.applicationContext.deviceOnly

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val summaryNotification = booleanPreferencesKey("summary_notification")
        val waitingAlerts = booleanPreferencesKey("waiting_alerts")
        val workerUrl = stringPreferencesKey("worker_url")
        val token = stringPreferencesKey("token")
        val registeredFcmToken = stringPreferencesKey("registered_fcm_token")
        // Earlier versions kept pinned shortcuts here; only removed now.
        val pinned = stringPreferencesKey("pinned_shortcuts")
        val sessions = stringPreferencesKey("sessions_cache")
        val muted = stringSetPreferencesKey("muted_sessions")
    }

    val settings: Flow<Settings> = combine(backedUp.data, deviceOnly.data) { b, d ->
        Settings(
            themeMode = b[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            dynamicColor = b[Keys.dynamicColor] ?: false,
            summaryNotification = b[Keys.summaryNotification] ?: false,
            waitingAlerts = b[Keys.waitingAlerts] ?: true,
            workerUrl = b[Keys.workerUrl].orEmpty(),
            token = d[Keys.token].orEmpty(),
            registeredFcmToken = d[Keys.registeredFcmToken].orEmpty(),
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setTheme(mode: ThemeMode) = backedUp.edit { it[Keys.theme] = mode.name }
    suspend fun setDynamicColor(on: Boolean) = backedUp.edit { it[Keys.dynamicColor] = on }
    suspend fun setSummaryNotification(on: Boolean) = backedUp.edit { it[Keys.summaryNotification] = on }
    suspend fun setWaitingAlerts(on: Boolean) = backedUp.edit { it[Keys.waitingAlerts] = on }

    suspend fun setConnection(workerUrl: String, token: String) {
        backedUp.edit { it[Keys.workerUrl] = workerUrl }
        deviceOnly.edit {
            it[Keys.token] = token
            it.remove(Keys.registeredFcmToken)
        }
    }

    suspend fun clearConnection() {
        backedUp.edit { it.remove(Keys.workerUrl) }
        deviceOnly.edit {
            it.remove(Keys.sessions)
            it.remove(Keys.token)
            it.remove(Keys.registeredFcmToken)
        }
    }

    suspend fun setRegisteredFcmToken(token: String) = deviceOnly.edit { it[Keys.registeredFcmToken] = token }

    /** Removes the list of pinned shortcuts that earlier versions kept. */
    suspend fun forgetPinned() = deviceOnly.edit { it.remove(Keys.pinned) }

    /** The last task lists, so the widget and the Tasks screen have something after a restart. */
    val sessions: Flow<List<TaskList>> = deviceOnly.data.map { prefs ->
        val text = prefs[Keys.sessions]
        if (text == null) {
            emptyList()
        } else {
            try {
                TaskList.listFromJson(text)
            } catch (e: org.json.JSONException) {
                emptyList()
            }
        }
    }

    suspend fun cachedSessions(): List<TaskList> = sessions.first()

    suspend fun saveSessions(lists: List<TaskList>) = deviceOnly.edit { it[Keys.sessions] = TaskList.listToJson(lists) }

    val muted: Flow<Set<String>> = deviceOnly.data.map { it[Keys.muted].orEmpty() }

    suspend fun setMuted(keys: Set<String>) = deviceOnly.edit { it[Keys.muted] = keys }
}
