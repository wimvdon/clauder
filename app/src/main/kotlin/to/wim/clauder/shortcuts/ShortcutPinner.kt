package to.wim.clauder.shortcuts

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import to.wim.clauder.R
import to.wim.clauder.links.ClaudeApp

/** Puts pinned shortcuts on the home screen and keeps them in step with the app's list. */
object ShortcutPinner {
    fun iconFor(kind: ClaudeLink.Kind): Int = when (kind) {
        ClaudeLink.Kind.Chat -> R.drawable.ic_shortcut_chat
        ClaudeLink.Kind.Project -> R.drawable.ic_shortcut_project
        ClaudeLink.Kind.Artifact -> R.drawable.ic_shortcut_artifact
        ClaudeLink.Kind.CodeSession -> R.drawable.ic_shortcut_code
        ClaudeLink.Kind.Other -> R.drawable.ic_shortcut_link
    }

    private fun info(context: Context, shortcut: PinnedShortcut): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, shortcut.id)
            .setShortLabel(shortcut.name)
            .setLongLabel(shortcut.name)
            .setIcon(IconCompat.createWithResource(context, iconFor(shortcut.kind)))
            .setIntent(ClaudeApp.openIntent(context, shortcut.url))
            .build()

    fun canPin(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Asks the launcher to add the shortcut. The launcher shows its own confirmation. */
    fun requestPin(context: Context, shortcut: PinnedShortcut): Boolean =
        ShortcutManagerCompat.requestPinShortcut(context, info(context, shortcut), null)

    fun rename(context: Context, shortcut: PinnedShortcut) {
        ShortcutManagerCompat.updateShortcuts(context, listOf(info(context, shortcut)))
    }

    /** Apps can't take a pinned shortcut off the home screen; disabling greys it out with a note. */
    fun remove(context: Context, id: String) {
        ShortcutManagerCompat.disableShortcuts(context, listOf(id), context.getString(R.string.shortcut_removed))
    }

    fun pinnedIds(context: Context): Set<String>? = try {
        ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).map { it.id }.toSet()
    } catch (e: IllegalStateException) {
        null
    }
}
