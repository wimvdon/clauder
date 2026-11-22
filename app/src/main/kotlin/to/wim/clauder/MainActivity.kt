package to.wim.clauder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import to.wim.clauder.settings.Settings
import to.wim.clauder.ui.ClauderRoot
import to.wim.clauder.ui.theme.AppTheme
import to.wim.clauder.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {
    /** Bumped on every resume, so screens re-check permissions the user may have changed. */
    private val resumes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as ClauderApp
        setContent {
            val settings by app.store.settings.collectAsState(initial = null)
            val current = settings ?: Settings()
            val dark = isDarkTheme(current.themeMode)
            // Status and navigation bar icons follow the app's theme, not the system's.
            LaunchedEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            AppTheme(darkTheme = dark, dynamicColor = current.dynamicColor) {
                if (settings != null) ClauderRoot(app, current, resumes.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }
}
