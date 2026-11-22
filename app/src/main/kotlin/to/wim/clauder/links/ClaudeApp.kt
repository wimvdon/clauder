package to.wim.clauder.links

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import java.net.URI

/** Opens claude.ai links in the Claude app, and in the browser when the app is missing or
 * doesn't handle that page. */
object ClaudeApp {
    const val PACKAGE = "com.anthropic.claude"

    /** The action of the links Clauder opens, handled by [OpenLinkActivity]. */
    const val ACTION_OPEN = "to.wim.clauder.action.OPEN_LINK"

    /** Only https claude.ai links are opened, whoever sends them. */
    fun safeUrl(raw: String?): String? {
        val uri = try {
            URI(raw?.trim() ?: return null)
        } catch (e: java.net.URISyntaxException) {
            return null
        }
        if (uri.scheme != "https" || uri.host?.lowercase() != "claude.ai" || uri.port != -1 || uri.userInfo != null) return null
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "https://claude.ai${uri.rawPath.orEmpty().ifEmpty { "/" }}$query"
    }

    /** Other https links Claude attached (a pull request, a page): opened in their own app or the
     * browser. No other schemes, and no user names or passwords in the link. */
    fun safeWebUrl(raw: String?): String? {
        val uri = try {
            URI(raw?.trim() ?: return null)
        } catch (e: java.net.URISyntaxException) {
            return null
        }
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return uri.toString()
    }

    /** Opens a claude.ai link in the Claude app, any other safe link in its own app. */
    fun openAny(context: Context, raw: String?) {
        safeUrl(raw)?.let { return open(context, it) }
        val url = safeWebUrl(raw) ?: return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // Nothing to open it with.
        }
    }

    /** An intent that opens [url] through Clauder, for notifications and widgets. */
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
