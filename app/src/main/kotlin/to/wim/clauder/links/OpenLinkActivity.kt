package to.wim.clauder.links

import android.app.Activity
import android.os.Bundle

/** Invisible stop between a shortcut, notification or widget and the Claude app. */
class OpenLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ClaudeApp.safeUrl(intent?.dataString)?.let { ClaudeApp.open(this, it) }
        finish()
    }
}
