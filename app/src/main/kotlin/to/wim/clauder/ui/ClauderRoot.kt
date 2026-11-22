package to.wim.clauder.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import to.wim.clauder.ClauderApp
import to.wim.clauder.R
import to.wim.clauder.settings.Settings
import to.wim.clauder.ui.settings.SettingsScreen
import to.wim.clauder.ui.shortcuts.ShortcutsScreen
import to.wim.clauder.ui.tasks.TasksScreen

private enum class Destination(@StringRes val label: Int, @DrawableRes val icon: Int, @DrawableRes val selectedIcon: Int) {
    Tasks(R.string.nav_tasks, R.drawable.ic_checklist, R.drawable.ic_checklist),
    Shortcuts(R.string.nav_shortcuts, R.drawable.ic_bookmark_outlined, R.drawable.ic_bookmark_filled),
    Settings(R.string.nav_settings, R.drawable.ic_settings_outlined, R.drawable.ic_settings_filled),
}

@Composable
fun ClauderRoot(app: ClauderApp, settings: Settings, resumes: Int, sharedText: String?, onSharedHandled: () -> Unit) {
    var destination by rememberSaveable { mutableStateOf(Destination.Tasks) }
    // Text shared to Clauder opens the Shortcuts tab with the Add dialog.
    LaunchedEffect(sharedText) { if (sharedText != null) destination = Destination.Shortcuts }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { d ->
                    NavigationBarItem(
                        selected = destination == d,
                        onClick = { destination = d },
                        icon = { Icon(painterResource(if (destination == d) d.selectedIcon else d.icon), contentDescription = null) },
                        label = { Text(stringResource(d.label)) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(bottom = padding.calculateBottomPadding())
        when (destination) {
            Destination.Tasks -> TasksScreen(app, settings, resumes, onOpenSettings = { destination = Destination.Settings }, modifier = modifier)
            Destination.Shortcuts -> ShortcutsScreen(app, resumes, sharedText, onSharedHandled, modifier = modifier)
            Destination.Settings -> SettingsScreen(app, settings, resumes, modifier = modifier)
        }
    }
}
