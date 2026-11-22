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
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontFamily
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
        Column(
            GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(24.dp)
                .padding(16.dp),
        ) {
            Text(
                context.getString(R.string.widget_tasks_title),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp, fontFamily = FontFamily.Serif),
                modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
            )
            Spacer(GlanceModifier.height(8.dp))
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
        Text(
            text,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
            modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
        )
    }

    @Composable
    private fun Session(context: Context, list: TaskList, muted: Boolean) {
        val url = LiveUpdate.from(list)?.openUrl ?: LiveUpdate.CODE_URL
        val state = context.getString(stateLabelRes(list))
        val counts = context.resources.getQuantityString(
            if (list.hasSteps) R.plurals.step_counts else R.plurals.notification_counts,
            list.items.size,
            list.doneCount,
            list.items.size,
        )
        val line = listOfNotNull(state, counts, context.getString(R.string.session_muted).takeIf { muted }).joinToString(" · ")
        Column(
            GlanceModifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clickable(startIntent(ClaudeApp.openIntent(context, url))),
        ) {
            list.group?.let {
                Text(it, style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp), maxLines = 1)
            }
            Text(list.title, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp), maxLines = 1)
            Text(
                line,
                style = TextStyle(
                    color = if (list.state == SessionState.Waiting) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 13.sp,
                ),
                maxLines = 1,
            )
            // While waiting, what Claude asks matters more than the step it's on.
            val detail = list.need?.takeIf { list.state == SessionState.Waiting }
            (detail ?: list.current?.subject)?.let {
                Text(
                    it,
                    style = TextStyle(
                        color = if (detail != null) GlanceTheme.colors.onSurface else GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 13.sp,
                    ),
                    maxLines = if (detail != null) 2 else 1,
                )
            }
            Spacer(GlanceModifier.height(4.dp))
            LinearProgressIndicator(
                progress = if (list.items.isEmpty()) 0f else list.doneCount.toFloat() / list.items.size,
                modifier = GlanceModifier.fillMaxWidth(),
                color = GlanceTheme.colors.primary,
                backgroundColor = GlanceTheme.colors.secondaryContainer,
            )
        }
    }
}

class TasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TasksWidget()
}
