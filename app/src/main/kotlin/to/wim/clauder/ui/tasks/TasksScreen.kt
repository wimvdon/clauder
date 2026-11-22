package to.wim.clauder.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.wim.clauder.ClauderApp
import to.wim.clauder.R
import to.wim.clauder.links.ClaudeApp
import to.wim.clauder.push.Push
import to.wim.clauder.push.WorkerClient
import to.wim.clauder.settings.Settings
import to.wim.clauder.tasks.LiveUpdate
import to.wim.clauder.tasks.SessionState
import to.wim.clauder.tasks.TaskItem
import to.wim.clauder.tasks.TaskList
import to.wim.clauder.tasks.TaskStatus
import to.wim.clauder.ui.theme.EmptyState
import to.wim.clauder.ui.theme.readableWidth

private enum class Load { Loading, Ready, Failed }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(app: ClauderApp, settings: Settings, resumes: Int, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val sessions by Push.sessions.collectAsState()
    var load by remember { mutableStateOf(Load.Loading) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun fetch() {
        if (!settings.isConnected) return
        when (val result = WorkerClient(settings.workerUrl, settings.token).sessions()) {
            is WorkerClient.Result.Ok -> {
                Push.onFetched(result.value)
                load = Load.Ready
            }
            else -> load = if (Push.sessions.value.isEmpty()) Load.Failed else Load.Ready
        }
    }

    LaunchedEffect(settings.workerUrl, settings.token, resumes) { fetch() }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tasks_title)) }) },
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
                    sessions.isNotEmpty() -> SessionList(sessions)
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
private fun SessionList(sessions: List<TaskList>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(sessions, key = { it.key }) { SessionCard(it) }
    }
}

@Composable
private fun SessionCard(list: TaskList) {
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
                "${stateLabel(list.state)} · ${pluralStringResource(
                    if (list.hasSteps) R.plurals.step_counts else R.plurals.notification_counts,
                    list.items.size,
                    list.doneCount,
                    list.items.size,
                )}",
                style = MaterialTheme.typography.labelLarge,
                color = if (list.state == SessionState.Waiting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { if (list.items.isEmpty()) 0f else list.doneCount.toFloat() / list.items.size },
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list.items.forEach { TaskRow(it) }
            }
            if (list.hasSteps && list.tasks.isNotEmpty()) TaskDetails(list.tasks)
        }
    }
}

@Composable
private fun stateLabel(state: SessionState): String = stringResource(
    when (state) {
        SessionState.Running -> R.string.state_running
        SessionState.Waiting -> R.string.state_waiting
        SessionState.Done -> R.string.state_done
        SessionState.Ended -> R.string.state_ended
    },
)

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
