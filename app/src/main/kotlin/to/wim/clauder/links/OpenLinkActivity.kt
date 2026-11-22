package to.wim.clauder.links

import android.app.Activity
import android.os.Bundle

/** Invisible stop between a notification or widget and the Claude app. */
class OpenLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ClaudeApp.openAny(this, intent?.dataString)
        finish()
    }
}
