package to.wim.clauder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import to.wim.clauder.ClauderApp
import to.wim.clauder.settings.Settings
import to.wim.clauder.ui.settings.SettingsScreen
import to.wim.clauder.ui.tasks.TasksScreen

/** The sessions list, with Settings as a child page opened from its top app bar. */
@Composable
fun ClauderRoot(app: ClauderApp, settings: Settings, resumes: Int) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showSettings) { showSettings = false }
    if (showSettings) {
        SettingsScreen(app, settings, resumes, onBack = { showSettings = false })
    } else {
        TasksScreen(app, settings, resumes, onOpenSettings = { showSettings = true })
    }
}
