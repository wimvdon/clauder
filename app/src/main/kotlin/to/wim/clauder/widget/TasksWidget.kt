package to.wim.clauder.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartActivity as startIntent
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.TextAlign
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.flow.first
import to.wim.clauder.ClauderApp
import to.wim.clauder.MainActivity
import to.wim.clauder.R
import to.wim.clauder.links.ClaudeApp
import to.wim.clauder.tasks.LiveUpdate
import to.wim.clauder.tasks.SessionBoard
import to.wim.clauder.tasks.SessionState
import to.wim.clauder.tasks.Summary
import to.wim.clauder.tasks.TaskNotifier
import to.wim.clauder.tasks.TaskList
import to.wim.clauder.ui.tasks.stateLabelRes

/** Home screen widget: the active sessions with their progress. */
class TasksWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = (context.applicationContext as ClauderApp).store
        val firstSettings = store.current()
        val firstSessions = store.cachedSessions()
        val firstMuted = store.muted.first()
        provideContent {
            // Read inside the content, so a widget that is already showing follows every change.
            val settings by store.settings.collectAsState(firstSettings)
            val sessions by store.sessions.collectAsState(firstSessions)
            val muted by store.muted.collectAsState(firstMuted)
            val now = System.currentTimeMillis()
            val (active, finished) = SessionBoard.split(SessionBoard.sort(sessions).map { it.shown(now) })
            val colors = widgetColors(settings.dynamicColor)
            if (colors != null) {
                GlanceTheme(colors = colors) { Content(settings.isConnected, active, finished, muted) }
            } else {
                GlanceTheme { Content(settings.isConnected, active, finished, muted) }
            }
        }
    }

    @Composable
    private fun Content(connected: Boolean, active: List<TaskList>, finished: List<TaskList>, muted: Set<String>) {
        val context = LocalContext.current
        val now = System.currentTimeMillis()
        Column(
            GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(android.R.dimen.system_app_widget_background_radius)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            // Header: the title in the serif face, and one line on what Claude is doing overall.
            Row(
                GlanceModifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    context.getString(R.string.widget_tasks_title),
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp, fontFamily = FontFamily.Serif),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
                if (connected) {
                    Text(
                        TaskNotifier.summaryText(context, Summary.from(active + finished, muted, now)),
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                        maxLines = 1,
                    )
                }
            }
            val sessions = SessionBoard.groups(active).flatMap { it.second }.ifEmpty { finished.take(1) }
            when {
                !connected -> Message(context.getString(R.string.widget_connect))
                sessions.isEmpty() -> Message(context.getString(R.string.widget_no_sessions))
                else -> LazyColumn {
                    items(sessions, itemId = { it.key.hashCode().toLong() }) { list -> Session(context, list, list.key in muted) }
                }
            }
        }
    }

    @Composable
    private fun Message(text: String) {
        Box(
            GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surfaceVariant)
                .cornerRadius(android.R.dimen.system_app_widget_inner_radius)
                .clickable(actionStartActivity<MainActivity>()),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center),
                modifier = GlanceModifier.padding(16.dp),
            )
        }
    }

    /** One session as a tonal card. A session that waits on you uses the primary container, so it
     * stands out from the ones that are only working; its state line says so in words too. */
    @Composable
    private fun Session(context: Context, list: TaskList, muted: Boolean) {
        val url = LiveUpdate.from(list)?.openUrl ?: LiveUpdate.CODE_URL
        val waiting = list.state == SessionState.Waiting
        val container = if (waiting) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.surfaceVariant
        val onContainer = if (waiting) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSurface
        val onContainerVariant = if (waiting) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSurfaceVariant
        val state = context.getString(stateLabelRes(list))
        val counts = context.resources.getQuantityString(
            if (list.hasSteps) R.plurals.step_counts else R.plurals.notification_counts,
            list.items.size,
            list.doneCount,
            list.items.size,
        )
        val line = listOfNotNull(state, counts, context.getString(R.string.session_muted).takeIf { muted }).joinToString(" · ")
        Column(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Column(
                GlanceModifier
                    .fillMaxWidth()
                    .background(container)
                    .cornerRadius(android.R.dimen.system_app_widget_inner_radius)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .clickable(startIntent(ClaudeApp.openIntent(context, url))),
            ) {
                list.group?.let {
                    Text(
                        it,
                        style = TextStyle(
                            color = if (waiting) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                    )
                }
                Text(
                    list.title,
                    style = TextStyle(color = onContainer, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
                Text(
                    line,
                    style = TextStyle(
                        color = onContainerVariant,
                        fontSize = 12.sp,
                        fontWeight = if (waiting) FontWeight.Medium else FontWeight.Normal,
                    ),
                    maxLines = 1,
                )
                // While waiting, what Claude asks matters more than the step it's on.
                val detail = list.need?.takeIf { waiting }
                (detail ?: list.current?.subject)?.let {
                    Text(
                        it,
                        style = TextStyle(color = if (detail != null) onContainer else onContainerVariant, fontSize = 13.sp),
                        maxLines = if (detail != null) 2 else 1,
                    )
                }
                if (list.items.isNotEmpty()) {
                    Spacer(GlanceModifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = list.doneCount.toFloat() / list.items.size,
                        modifier = GlanceModifier.fillMaxWidth().height(4.dp).cornerRadius(2.dp),
                        color = GlanceTheme.colors.primary,
                        backgroundColor = if (waiting) GlanceTheme.colors.surface else GlanceTheme.colors.secondaryContainer,
                    )
                }
            }
        }
    }
}

class TasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TasksWidget()
}
