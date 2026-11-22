package to.wim.clauder.links

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import to.wim.clauder.shortcuts.ClaudeLink

/** Opens claude.ai links in the Claude app, and in the browser when the app is missing or
 * doesn't handle that page. */
object ClaudeApp {
    const val PACKAGE = "com.anthropic.claude"

    /** The action of every Clauder shortcut, handled by [OpenLinkActivity]. */
    const val ACTION_OPEN = "to.wim.clauder.action.OPEN_LINK"

    /** Only https claude.ai links are opened, whoever sends them. */
    fun safeUrl(raw: String?): String? = raw?.let(ClaudeLink::parse)?.url

    /** An intent that opens [url] through Clauder, for shortcuts, notifications and widgets. */
    fun openIntent(context: Context, url: String): Intent =
        Intent(ACTION_OPEN, url.toUri()).setClass(context, OpenLinkActivity::class.java)

    fun open(context: Context, url: String) {
        val view = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(Intent(view).setPackage(PACKAGE))
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(view)
            } catch (e: ActivityNotFoundException) {
                // No browser either; nothing to open it with.
            }
        }
    }
}
