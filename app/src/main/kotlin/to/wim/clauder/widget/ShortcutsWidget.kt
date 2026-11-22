package to.wim.clauder.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity as startIntent
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.flow.first
import to.wim.clauder.ClauderApp
import to.wim.clauder.R
import to.wim.clauder.links.ClaudeApp
import to.wim.clauder.shortcuts.ClaudeLink
import to.wim.clauder.shortcuts.StaticShortcuts

/** Home screen widget: New chat, Code, Projects, Artifacts and your pinned links. */
class ShortcutsWidget : GlanceAppWidget() {
    private data class Entry(val label: String, val url: String, val icon: Int)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = (context.applicationContext as ClauderApp).store
        val firstSettings = store.current()
        val firstPinned = store.pinned.first()
        val fixed = listOf(
            Entry(context.getString(R.string.shortcut_new_chat), StaticShortcuts.NEW_CHAT, R.drawable.ic_add),
            Entry(context.getString(R.string.shortcut_code), StaticShortcuts.CODE, R.drawable.ic_kind_code),
            Entry(context.getString(R.string.shortcut_projects), StaticShortcuts.PROJECTS, R.drawable.ic_kind_project),
            Entry(context.getString(R.string.shortcut_artifacts), StaticShortcuts.ARTIFACTS, R.drawable.ic_kind_artifact),
        )
        provideContent {
            val settings by store.settings.collectAsState(firstSettings)
            val pinned by store.pinned.collectAsState(firstPinned)
            val entries = fixed + pinned.map { Entry(it.name, it.url, kindIcon(it.kind)) }
            val colors = widgetColors(settings.dynamicColor)
            if (colors != null) GlanceTheme(colors = colors) { Content(entries) } else GlanceTheme { Content(entries) }
        }
    }

    private fun kindIcon(kind: ClaudeLink.Kind): Int = when (kind) {
        ClaudeLink.Kind.Chat -> R.drawable.ic_kind_chat
        ClaudeLink.Kind.Project -> R.drawable.ic_kind_project
        ClaudeLink.Kind.Artifact -> R.drawable.ic_kind_artifact
        ClaudeLink.Kind.CodeSession -> R.drawable.ic_kind_code
        ClaudeLink.Kind.Other -> R.drawable.ic_kind_link
    }

    @Composable
    private fun Content(entries: List<Entry>) {
        val context = LocalContext.current
        LazyColumn(
            GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(24.dp)
                .padding(8.dp),
        ) {
            items(entries, itemId = { it.url.hashCode().toLong() }) { entry ->
                Row(
                    GlanceModifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp)
                        .clickable(startIntent(ClaudeApp.openIntent(context, entry.url))),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        ImageProvider(entry.icon),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                        modifier = GlanceModifier.size(20.dp),
                    )
                    Spacer(GlanceModifier.width(12.dp))
                    Text(entry.label, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp), maxLines = 1)
                }
            }
        }
    }
}

class ShortcutsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShortcutsWidget()
}
