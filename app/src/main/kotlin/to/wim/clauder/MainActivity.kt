package to.wim.clauder

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import to.wim.clauder.settings.Settings
import to.wim.clauder.ui.ClauderRoot
import to.wim.clauder.ui.theme.AppTheme
import to.wim.clauder.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {
    /** Bumped on every resume, so screens re-check permissions the user may have changed. */
    private val resumes = mutableIntStateOf(0)

    /** Text shared to Clauder from another app (a claude.ai link to pin), until it is handled. */
    private val sharedText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as ClauderApp
        if (savedInstanceState == null) readShare(intent)
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
                if (settings != null) ClauderRoot(app, current, resumes.intValue, sharedText.value) { sharedText.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readShare(intent)
    }

    private fun readShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            sharedText.value = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        }
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }
}
