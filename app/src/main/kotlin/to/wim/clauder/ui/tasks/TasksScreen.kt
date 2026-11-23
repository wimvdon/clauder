package to.wim.clauder.ui.tasks

import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.wim.clauder.ClauderApp
import to.wim.clauder.R
import to.wim.clauder.links.ClaudeApp
import to.wim.clauder.push.Push
import to.wim.clauder.push.WorkerClient
import to.wim.clauder.settings.HookSetup
import to.wim.clauder.settings.Settings
import to.wim.clauder.settings.WorkerUrl
import to.wim.clauder.tasks.CiState
import to.wim.clauder.tasks.LiveUpdate
import to.wim.clauder.tasks.MuteList
import to.wim.clauder.tasks.PrState
import to.wim.clauder.tasks.PullRequest
import to.wim.clauder.tasks.SegmentBar
import to.wim.clauder.tasks.SessionBoard
import to.wim.clauder.tasks.SessionState
import to.wim.clauder.tasks.TaskItem
import to.wim.clauder.tasks.TaskList
import to.wim.clauder.tasks.TaskNotifier
import to.wim.clauder.tasks.TaskStatus
import to.wim.clauder.tasks.Usage
import to.wim.clauder.ui.theme.EmptyState
import to.wim.clauder.ui.theme.readableWidth
import to.wim.clauder.widget.Widgets

private enum class Load { Loading, Ready, Failed }

/** After Mark resolved, reload the list once the Worker has pushed the change. */
private const val RESOLVE_REFRESH_MS = 1_500L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(app: ClauderApp, settings: Settings, resumes: Int, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val sessions by Push.sessions.collectAsState()
    val muted by app.store.muted.collectAsState(emptySet())
    var load by remember { mutableStateOf(Load.Loading) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun fetch() {
        if (!settings.isConnected) return
        when (val result = WorkerClient(settings.workerUrl, settings.token).sessions()) {
            is WorkerClient.Result.Ok -> {
                Push.record(app.store, result.value)
                Widgets.updateTasks(app)
                load = Load.Ready
            }
            else -> load = if (Push.sessions.value.isEmpty()) Load.Failed else Load.Ready
        }
    }

    LaunchedEffect(settings.workerUrl, settings.token, resumes) { fetch() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tasks_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings_outlined), contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
    ) { padding ->
        val inner = Modifier.padding(padding)
        when {
            !settings.isConnected -> EmptyState(
                icon = R.drawable.ic_key,
                title = stringResource(R.string.tasks_setup_title),
                body = stringResource(R.string.tasks_setup_body),
                actionLabel = stringResource(R.string.action_open_settings),
                onAction = onOpenSettings,
                modifier = inner,
            )
            else -> PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        fetch()
                        refreshing = false
                    }
                },
                modifier = inner.fillMaxSize(),
            ) {
                when {
                    sessions.isNotEmpty() -> SessionList(
                        sessions,
                        muted,
                        hookFix = stringResource(R.string.old_hooks_message, HookSetup.update(WorkerUrl.host(settings.workerUrl))),
                        onUnmute = { list ->
                            scope.launch {
                                val after = MuteList.unmute(muted, list.key)
                                app.store.setMuted(after)
                                TaskNotifier.apply(app, list, sessions, after, app.store.current().notify)
                            }
                        },
                        onResolve = { list ->
                            scope.launch {
                                val result = WorkerClient(settings.workerUrl, settings.token).resolve(list.key)
                                if (result is WorkerClient.Result.Ok) {
                                    TaskNotifier.cancel(app, list.key)
                                    // The Worker pushes the change; this also catches it when the push is late.
                                    delay(RESOLVE_REFRESH_MS)
                                    fetch()
                                } else {
                                    Toast.makeText(app, R.string.resolve_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    )
                    load == Load.Failed -> EmptyState(
                        icon = R.drawable.ic_cloud_off,
                        title = stringResource(R.string.tasks_error_title),
                        body = stringResource(R.string.tasks_error_body),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = { scope.launch { load = Load.Loading; fetch() } },
                    )
                    load == Load.Ready -> EmptyState(
                        icon = R.drawable.ic_inbox,
                        title = stringResource(R.string.tasks_empty_title),
                        body = stringResource(R.string.tasks_empty_body),
                    )
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun SessionList(
    sessions: List<TaskList>,
    muted: Set<String>,
    hookFix: String,
    onUnmute: (TaskList) -> Unit,
    onResolve: (TaskList) -> Unit,
) {
    // The shown state depends on the time (a long silence makes a session idle), so re-read it
    // every minute.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    val board = SessionBoard.board(sessions, now)
    // Projects the user folded (every project starts open), and whether the user opened the list of
    // idle and finished sessions (it starts folded).
    val folded = remember { mutableStateMapOf<String, Boolean>() }
    var inactiveOpen by rememberSaveable { mutableStateOf(false) }
    val rows = SessionBoard.rows(board, foldedGroups = folded.filterValues { it }.keys, inactiveOpen = inactiveOpen)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(rows, key = { row ->
            when (row) {
                is SessionBoard.Row.Header -> "group:${row.group.orEmpty()}"
                is SessionBoard.Row.Active -> row.list.key
                is SessionBoard.Row.InactiveHeader -> "inactive"
                is SessionBoard.Row.Inactive -> row.list.key
            }
        }) { row ->
            when (row) {
                is SessionBoard.Row.Header -> GroupHeader(
                    row.group ?: stringResource(R.string.tasks_section_other),
                    count = row.count,
                    open = row.open,
                    onToggle = { folded[row.group.orEmpty()] = row.open },
                )
                is SessionBoard.Row.Active -> SessionCard(row.list, row.list.key in muted, hookFix, onUnmute, onResolve)
                is SessionBoard.Row.InactiveHeader -> InactiveHeader(row.count, row.open) { inactiveOpen = !row.open }
                is SessionBoard.Row.Inactive -> InactiveRow(row.list, row.list.key in muted, hookFix)
            }
        }
    }
}

/** "Inactive" with a count and a chevron, below every project, set off by a line: shows or hides
 * every idle and finished session. */
@Composable
private fun InactiveHeader(count: Int, open: Boolean, onToggle: () -> Unit) {
    Column(Modifier.readableWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = 4.dp)
                .semantics(mergeDescendants = true) { heading() },
        ) {
            Text(
                stringResource(R.string.tasks_section_inactive),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$count",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                painterResource(R.drawable.ic_expand_more),
                contentDescription = stringResource(if (open) R.string.section_expanded else R.string.section_collapsed),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f),
            )
        }
    }
}

@Composable
private fun GroupHeader(text: String, count: Int, open: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .readableWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(horizontal = 4.dp)
            .semantics(mergeDescendants = true) { heading() },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!open) {
            Text(
                "$count",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            painterResource(R.drawable.ic_expand_more),
            contentDescription = stringResource(if (open) R.string.section_expanded else R.string.section_collapsed),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f),
        )
    }
}

/** An idle or finished session: its project or repository, title and state, without the steps. */
@Composable
private fun InactiveRow(list: TaskList, muted: Boolean, hookFix: String) {
    val context = LocalContext.current
    val url = LiveUpdate.from(list)?.openUrl ?: LiveUpdate.CODE_URL
    val line = listOfNotNull(
        stringResource(stateLabelRes(list)),
        countsLabel(list),
        usageLabel(list),
        stringResource(R.string.session_muted).takeIf { muted },
    ).joinToString(" · ")
    ListItem(
        overlineContent = list.group?.let { group ->
            { Text(group, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        headlineContent = { Text(list.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(line)
                if (list.oldHooks && !list.isFinished) OldHooksNote(hookFix)
            }
        },
        trailingContent = {
            Icon(
                painterResource(R.drawable.ic_open_in_new),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .readableWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = stringResource(R.string.session_open), role = Role.Button) {
                ClaudeApp.open(context, url)
            },
    )
}

/** A thread on old hook settings can't update them itself: says so, with a message to paste into
 * the thread that has Claude run the installer once. */
@Composable
private fun OldHooksNote(message: String) {
    val context = LocalContext.current
    val label = stringResource(R.string.app_name)
    Column {
        Text(
            stringResource(R.string.old_hooks_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(label, message))
        }) {
            Icon(painterResource(R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.old_hooks_copy))
        }
    }
}

/** The pull request and its CI, a reminder, helpers and the cost, with buttons for the pull
 * request and the page Claude attached. Shows nothing when there is none of these. */
@Composable
private fun SessionExtras(list: TaskList) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val update = LiveUpdate.from(list, now)
    val line = listOfNotNull(
        list.pr?.let { prLabel(it) },
        update?.holding?.takeIf { it != LiveUpdate.Holding.Ci }?.let { TaskNotifier.holdingText(context, update) },
        usageLabel(list),
    )
    if (line.isNotEmpty()) {
        Text(
            line.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = if (list.pr?.ci == CiState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (list.link != null || list.pr != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            list.link?.let { url ->
                OutlinedButton(onClick = { ClaudeApp.openAny(context, url) }) {
                    Text(
                        list.linkTitle?.let { stringResource(R.string.action_open_named, it) } ?: stringResource(R.string.action_open_link),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            list.pr?.let { pr ->
                OutlinedButton(onClick = { ClaudeApp.openAny(context, pr.url) }) {
                    Text(stringResource(R.string.action_open_pr, pr.number), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun prLabel(pr: PullRequest): String = when {
    pr.state == PrState.Merged -> stringResource(R.string.pr_merged, pr.number)
    pr.state == PrState.Closed -> stringResource(R.string.pr_closed, pr.number)
    pr.ci == CiState.Running -> stringResource(R.string.pr_ci_running, pr.number)
    pr.ci == CiState.Passed -> stringResource(R.string.pr_ci_passed, pr.number)
    pr.ci == CiState.Failed -> stringResource(R.string.pr_ci_failed, pr.number)
    else -> stringResource(R.string.pr_open, pr.number)
}

@Composable
private fun usageLabel(list: TaskList): String? =
    if (list.tokens > 0) stringResource(R.string.usage_line, Usage.tokens(list.tokens), Usage.dollars(list.cost)) else null

@Composable
private fun countsLabel(list: TaskList): String = pluralStringResource(
    if (list.hasSteps) R.plurals.step_counts else R.plurals.notification_counts,
    list.items.size,
    list.doneCount,
    list.items.size,
)

@Composable
private fun SessionCard(list: TaskList, muted: Boolean, hookFix: String, onUnmute: (TaskList) -> Unit, onResolve: (TaskList) -> Unit) {
    val context = LocalContext.current
    val url = LiveUpdate.from(list)?.openUrl ?: LiveUpdate.CODE_URL
    val openLabel = stringResource(R.string.session_open)
    ElevatedCard(
        modifier = Modifier
            .readableWidth()
            .clickable(onClickLabel = openLabel, role = Role.Button) {
                ClaudeApp.open(context, url)
            },
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    list.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painterResource(R.drawable.ic_open_in_new),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                "${stringResource(stateLabelRes(list))} · ${countsLabel(list)}",
                style = MaterialTheme.typography.labelLarge,
                color = if (list.state == SessionState.Waiting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            list.need?.takeIf { list.state == SessionState.Waiting }?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
            // Where the work stands after Claude's last turn, in one line.
            list.summary?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // The day's free AI allowance ran out, so this turn has no summary line.
            val now = System.currentTimeMillis()
            if (list.aiPaused(now)) {
                val resumes = DateFormat.getTimeFormat(LocalContext.current).format(java.util.Date(list.aiResumesMillis(now)))
                Text(
                    stringResource(R.string.ai_paused_note, resumes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Resolving the thread in the Claude app doesn't reach Clauder, so it can be done here.
            if (list.state == SessionState.Waiting) {
                FilledTonalButton(onClick = { onResolve(list) }) {
                    Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_resolve))
                }
            }
            if (list.oldHooks) OldHooksNote(hookFix)
            SessionExtras(list)
            SegmentedProgress(list)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list.items.forEach { TaskRow(it) }
            }
            if (list.hasSteps && list.tasks.isNotEmpty()) TaskDetails(list.tasks)
            if (muted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_notifications_off),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.session_muted),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onUnmute(list) }) { Text(stringResource(R.string.action_unmute)) }
                }
            }
        }
    }
}

/** One segment per step, like the notification. The empty segments use outlineVariant, which
 * stays visible on the card in dark mode, and the filled ones primary (at least 3:1 against both). */
@Composable
private fun SegmentedProgress(list: TaskList) {
    val bar = SegmentBar.of(list)
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .semantics(mergeDescendants = true) {
                progressBarRangeInfo = ProgressBarRangeInfo(list.doneCount.toFloat(), 0f..maxOf(list.items.size, 1).toFloat(), maxOf(list.items.size, 1))
            },
    ) {
        repeat(bar.segments) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(if (index < bar.filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

/** Claude Code's own tasks under a project thread's steps, folded away by default. */
@Composable
private fun TaskDetails(tasks: List<TaskItem>) {
    var open by rememberSaveable { mutableStateOf(false) }
    val done = tasks.count { it.status == TaskStatus.Completed }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(stringResource(R.string.tasks_toggle, done, tasks.size))
            Spacer(Modifier.width(4.dp))
            Icon(
                painterResource(R.drawable.ic_expand_more),
                contentDescription = stringResource(if (open) R.string.section_expanded else R.string.section_collapsed),
                modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f),
            )
        }
        if (open) tasks.forEach { TaskRow(it) }
    }
}

@Composable
private fun TaskRow(task: TaskItem) {
    val (icon, label, tint) = when (task.status) {
        TaskStatus.Completed -> Triple(R.drawable.ic_check_circle, R.string.task_done, MaterialTheme.colorScheme.primary)
        TaskStatus.InProgress -> Triple(R.drawable.ic_circle_active, R.string.task_in_progress, MaterialTheme.colorScheme.primary)
        TaskStatus.Pending -> Triple(R.drawable.ic_circle_outline, R.string.task_pending, MaterialTheme.colorScheme.outline)
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(painterResource(icon), contentDescription = stringResource(label), tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            task.subject,
            style = MaterialTheme.typography.bodyMedium,
            color = if (task.status == TaskStatus.Completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}
