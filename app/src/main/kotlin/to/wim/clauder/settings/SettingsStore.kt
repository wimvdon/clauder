package to.wim.clauder.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import to.wim.clauder.shortcuts.PinnedCodec
import to.wim.clauder.shortcuts.PinnedShortcut

/** Backed up by Android (see res/xml/data_extraction_rules.xml): theme and Worker address. */
private val Context.backedUp: DataStore<Preferences> by preferencesDataStore(name = "backed_up_settings")

/** Never backed up: the Worker token, FCM registration and pinned shortcuts (home screen
 * shortcuts belong to this phone). */
private val Context.deviceOnly: DataStore<Preferences> by preferencesDataStore(name = "device_state")

class SettingsStore(context: Context) {
    private val backedUp = context.applicationContext.backedUp
    private val deviceOnly = context.applicationContext.deviceOnly

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val workerUrl = stringPreferencesKey("worker_url")
        val token = stringPreferencesKey("token")
        val registeredFcmToken = stringPreferencesKey("registered_fcm_token")
        val pinned = stringPreferencesKey("pinned_shortcuts")
    }

    val settings: Flow<Settings> = combine(backedUp.data, deviceOnly.data) { b, d ->
        Settings(
            themeMode = b[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            dynamicColor = b[Keys.dynamicColor] ?: false,
            workerUrl = b[Keys.workerUrl].orEmpty(),
            token = d[Keys.token].orEmpty(),
            registeredFcmToken = d[Keys.registeredFcmToken].orEmpty(),
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setTheme(mode: ThemeMode) = backedUp.edit { it[Keys.theme] = mode.name }
    suspend fun setDynamicColor(on: Boolean) = backedUp.edit { it[Keys.dynamicColor] = on }

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
            it.remove(Keys.token)
            it.remove(Keys.registeredFcmToken)
        }
    }

    suspend fun setRegisteredFcmToken(token: String) = deviceOnly.edit { it[Keys.registeredFcmToken] = token }

    val pinned: Flow<List<PinnedShortcut>> = deviceOnly.data.map { PinnedCodec.decode(it[Keys.pinned]) }

    suspend fun savePinned(list: List<PinnedShortcut>) = deviceOnly.edit { it[Keys.pinned] = PinnedCodec.encode(list) }
}
