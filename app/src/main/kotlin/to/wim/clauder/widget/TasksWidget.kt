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
import androidx.glance.layout.fillMaxHeight
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
import to.wim.clauder.claudeai.RemoteThreads
import to.wim.clauder.links.ClaudeApp
import to.wim.clauder.tasks.LiveUpdate
import to.wim.clauder.tasks.SegmentBar
import to.wim.clauder.tasks.SessionBoard
import to.wim.clauder.tasks.SessionState
import to.wim.clauder.tasks.Summary
import to.wim.clauder.tasks.TaskNotifier
import to.wim.clauder.tasks.TaskList
import to.wim.clauder.ui.tasks.stateLabelRes

/** Home screen widget: the sessions that need input or are running, by project, with their progress. */
class TasksWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as ClauderApp
        val store = app.store
        val firstSettings = store.current()
        val firstSessions = store.cachedSessions()
        val firstMuted = store.muted.first()
        val firstRemote = app.claudeAiLogin.snapshot.first()
        provideContent {
            // Read inside the content, so a widget that is already showing follows every change.
            val settings by store.settings.collectAsState(firstSettings)
            val hookSessions by store.sessions.collectAsState(firstSessions)
            val muted by store.muted.collectAsState(firstMuted)
            // claude.ai's last read, when signed in there: threads without hooks, and resolves by hand.
            val remote by app.claudeAiLogin.snapshot.collectAsState(firstRemote)
            val sessions = RemoteThreads.overlay(hookSessions, remote)
            val now = System.currentTimeMillis()
            val (finished, active) = SessionBoard.sort(sessions).map { it.shown(now) }.partition { SessionBoard.isInactive(it) }
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
            // Needs input, then Running, each grouped by project like the app; idle and finished
            // sessions stay in the app.
            val rows = SessionBoard.rows(SessionBoard.board(active, now), inactiveOpen = false, withInactive = false)
            when {
                !connected -> Message(context.getString(R.string.widget_connect))
                rows.isEmpty() -> Message(context.getString(R.string.widget_no_sessions))
                else -> LazyColumn {
                    items(rows, itemId = { SessionBoard.key(it).hashCode().toLong() }) { row ->
                        when (row) {
                            is SessionBoard.Row.StatusHeader -> StatusHeader(context, row)
                            is SessionBoard.Row.ProjectHeader -> ProjectHeader(row.group ?: context.getString(R.string.tasks_section_other))
                            is SessionBoard.Row.Session -> Session(context, row.list, row.list.key in muted)
                        }
                    }
                }
            }
        }
    }

    /** "Needs input" or "Running" with a count, in the serif face like the title. */
    @Composable
    private fun StatusHeader(context: Context, row: SessionBoard.Row.StatusHeader) {
        val title = context.getString(
            if (row.status == SessionBoard.Status.NeedsInput) R.string.tasks_section_needs_input else R.string.tasks_section_running,
        )
        Row(GlanceModifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = TextStyle(
                    color = if (row.status == SessionBoard.Status.NeedsInput) GlanceTheme.colors.primary else GlanceTheme.colors.onSurface,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Serif,
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Text("${row.count}", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
        }
    }

    /** A project's name above its sessions; it doesn't fold. */
    @Composable
    private fun ProjectHeader(text: String) {
        Text(
            text,
            style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
            modifier = GlanceModifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
        )
    }

    /** One segment per step, like the notification. The empty ones are a groove in the surface
     * colour, which in dark mode stands out from the card (the old track nearly vanished). */
    @Composable
    private fun Segments(bar: SegmentBar) {
        Row(GlanceModifier.fillMaxWidth().height(5.dp)) {
            repeat(bar.segments) { index ->
                Box(GlanceModifier.defaultWeight().fillMaxHeight().padding(horizontal = 1.5.dp)) {
                    Box(
                        GlanceModifier
                            .fillMaxSize()
                            .cornerRadius(2.dp)
                            .background(if (index < bar.filled) GlanceTheme.colors.primary else GlanceTheme.colors.surface),
                    ) {}
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
        val url = LiveUpdate.from(list)?.openUrl ?: ClaudeApp.safeUrl(list.url) ?: LiveUpdate.CODE_URL
        val waiting = list.state == SessionState.Waiting
        val container = if (waiting) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.surfaceVariant
        val onContainer = if (waiting) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSurface
        val onContainerVariant = if (waiting) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSurfaceVariant
        val state = context.getString(stateLabelRes(list))
        // A thread only claude.ai knows has no steps to count.
        val counts = if (list.items.isEmpty()) {
            null
        } else {
            context.resources.getQuantityString(
                if (list.hasSteps) R.plurals.step_counts else R.plurals.notification_counts,
                list.items.size,
                list.doneCount,
                list.items.size,
            )
        }
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
                // While waiting, what Claude asks matters more than the step it's on; after a turn,
                // its summary says more than the step.
                val detail = list.need?.takeIf { waiting } ?: list.summary
                (detail ?: list.current?.subject)?.let {
                    Text(
                        it,
                        style = TextStyle(color = if (detail != null) onContainer else onContainerVariant, fontSize = 13.sp),
                        maxLines = if (detail != null) 2 else 1,
                    )
                }
                if (list.items.isNotEmpty()) {
                    Spacer(GlanceModifier.height(8.dp))
                    Segments(SegmentBar.of(list, SegmentBar.WIDGET_MAX))
                }
            }
        }
    }
}


class TasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TasksWidget()
}
