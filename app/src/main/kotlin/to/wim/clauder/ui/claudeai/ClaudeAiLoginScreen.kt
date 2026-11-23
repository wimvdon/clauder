package to.wim.clauder.ui.claudeai

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import to.wim.clauder.ClauderApp
import to.wim.clauder.R
import to.wim.clauder.claudeai.ClaudeAi
import to.wim.clauder.claudeai.ClaudeAiLogin

/**
 * claude.ai's own sign-in page. As soon as claude.ai has set its sign-in cookie, Clauder keeps an
 * encrypted copy (ClaudeAiLogin), clears the page's cookies and closes the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaudeAiLoginScreen(app: ClauderApp, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(false) }
    BackHandler { onDone() }

    fun checkCookie() {
        if (saved) return
        val cookies = CookieManager.getInstance().getCookie(ClaudeAi.ORIGIN)
        val key = ClaudeAi.sessionKeyFrom(cookies) ?: return
        saved = true
        scope.launch {
            app.claudeAiLogin.save(key, ClaudeAi.organizationFrom(cookies))
            ClaudeAiLogin.forgetWebLogin()
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.claude_ai_login_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                stringResource(R.string.claude_ai_login_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            AndroidView(
                factory = { context -> signInWebView(context, onPage = { checkCookie() }) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    // Leaving the page without signing in leaves nothing behind.
    DisposableEffect(Unit) { onDispose { if (!saved) ClaudeAiLogin.forgetWebLogin() } }
}

// claude.ai's sign-in page needs JavaScript. The WebView exposes no app objects to the page
// (no addJavascriptInterface), so lint's SetJavaScriptEnabled warning is expected here.
private fun signInWebView(context: android.content.Context, onPage: () -> Unit): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    CookieManager.getInstance().setAcceptCookie(true)
    webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = onPage()
        override fun onPageFinished(view: WebView, url: String) = onPage()
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = onPage()
    }
    loadUrl(ClaudeAi.LOGIN_URL)
}
