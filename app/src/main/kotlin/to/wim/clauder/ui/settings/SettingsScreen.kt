package to.wim.clauder.ui.settings

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.wim.clauder.BuildConfig
import to.wim.clauder.ClauderApp
import to.wim.clauder.R
import to.wim.clauder.push.ConnectOutcome
import to.wim.clauder.push.Push
import to.wim.clauder.push.WorkerClient
import to.wim.clauder.links.ClaudeApp
import to.wim.clauder.settings.GithubStatus
import to.wim.clauder.settings.HookSetup
import to.wim.clauder.settings.SetupCheck
import to.wim.clauder.settings.Settings
import to.wim.clauder.settings.ThemeMode
import to.wim.clauder.settings.WorkerUrl
import to.wim.clauder.tasks.TaskNotifier
import to.wim.clauder.ui.theme.readableWidth

/** The Worker Wim runs. Filled in so connecting only needs the token. */
const val DEFAULT_WORKER = "clauder.wim-d57.workers.dev"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: ClauderApp,
    settings: Settings,
    resumes: Int,
    onBack: () -> Unit,
    onConnectClaudeAi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.readableWidth().padding(bottom = 24.dp)) {
                ConnectionSection(app, settings)
                ClaudeAiSection(app, onConnect = onConnectClaudeAi)
                NotificationSection(app, settings, resumes)
                EnvironmentSection(settings)
                if (settings.isConnected) GithubSection(settings)
                AppearanceSection(app, settings)
                Text(
                    stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ConnectionSection(app: ClauderApp, settings: Settings) {
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(WorkerUrl.host(settings.workerUrl).ifBlank { DEFAULT_WORKER }) }
    var token by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<ConnectOutcome?>(null) }

    SettingsSection(stringResource(R.string.section_connection)) {
        if (settings.isConnected) {
            SettingsBlock {
                Text(stringResource(R.string.connect_connected, WorkerUrl.host(settings.workerUrl)), style = MaterialTheme.typography.bodyLarge)
                (outcome as? ConnectOutcome.Connected)?.let { OutcomeText(it) }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        busy = true
                        Push.disconnect(app.store)
                        outcome = null
                        busy = false
                    }
                }, enabled = !busy) { Text(stringResource(R.string.action_disconnect)) }
            }
        } else {
            SettingsBlock {
                Text(
                    stringResource(R.string.connect_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.worker_address)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    isError = outcome == ConnectOutcome.InvalidAddress,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.worker_token)) },
                    supportingText = { Text(stringResource(R.string.worker_token_help)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    isError = outcome == ConnectOutcome.MissingToken || outcome == ConnectOutcome.WrongToken,
                    modifier = Modifier.fillMaxWidth(),
                )
                outcome?.let { OutcomeText(it) }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            outcome = Push.connect(app.store, address, token)
                            if (outcome is ConnectOutcome.Connected) token = ""
                            busy = false
                        }
                    },
                    enabled = !busy,
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    }
                    Text(stringResource(R.string.action_connect))
                }
            }
        }
    }
}

@Composable
private fun OutcomeText(outcome: ConnectOutcome) {
    val (text, isError) = when (outcome) {
        is ConnectOutcome.Connected -> stringResource(if (outcome.pushReady) R.string.connect_ok else R.string.connect_ok_no_push) to !outcome.pushReady
        ConnectOutcome.InvalidAddress -> stringResource(R.string.connect_invalid_address) to true
        ConnectOutcome.MissingToken -> stringResource(R.string.connect_missing_token) to true
        ConnectOutcome.WrongToken -> stringResource(R.string.connect_wrong_token) to true
        is ConnectOutcome.Unreachable -> stringResource(R.string.connect_unreachable, outcome.detail) to true
        ConnectOutcome.PushUnavailable -> stringResource(R.string.connect_push_unavailable) to true
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun NotificationSection(app: ClauderApp, settings: Settings, resumes: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checks by remember { mutableIntStateOf(0) }
    val granted = remember(resumes, checks) { TaskNotifier.canNotify(context) }
    val promoted = remember(resumes, checks) { canPromote(context) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        checks++
        if (!ok) context.startActivity(appNotificationSettings(context))
    }
    val res = LocalResources.current

    SettingsSection(stringResource(R.string.section_notifications)) {
        SettingsBlock {
            if (!granted) {
                Text(stringResource(R.string.notifications_off), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.notifications_off_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startActivity(appNotificationSettings(context))
                    }
                }) {
                    Text(stringResource(R.string.action_allow))
                }
            } else if (!promoted) {
                Text(stringResource(R.string.live_updates_off), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.live_updates_off_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { context.startActivity(promotionSettings(context)) }) {
                    Text(stringResource(R.string.action_open_settings))
                }
            } else {
                Text(stringResource(R.string.notifications_on), style = MaterialTheme.typography.bodyLarge)
            }
            if (settings.isConnected) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            testing = true
                            testResult = when (val r = WorkerClient(settings.workerUrl, settings.token).sendTest()) {
                                is WorkerClient.Result.Ok -> when {
                                    !r.value.fcmConfigured -> res.getString(R.string.test_no_push)
                                    r.value.devices == 0 -> res.getString(R.string.test_no_phones)
                                    else -> res.getQuantityString(R.plurals.test_sent, r.value.sent, r.value.sent)
                                }
                                WorkerClient.Result.Unauthorized -> res.getString(R.string.connect_wrong_token)
                                is WorkerClient.Result.Failed -> res.getString(R.string.test_failed, r.message)
                            }
                            testing = false
                        }
                    },
                    enabled = !testing,
                ) { Text(stringResource(R.string.action_send_test)) }
                testResult?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        if (granted) {
            SwitchSetting(
                title = stringResource(R.string.summary_setting),
                summary = stringResource(R.string.summary_setting_summary).takeIf { settings.waitingAlerts },
                checked = settings.summaryNotification,
                onChange = { on -> scope.launch { app.store.setSummaryNotification(on) } },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SettingsBlock {
                    Text(stringResource(R.string.tile_setting), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.tile_setting_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { requestTile(context) }) { Text(stringResource(R.string.action_add_tile)) }
                }
            }
            SwitchSetting(
                title = stringResource(R.string.waiting_alert),
                summary = stringResource(R.string.waiting_alert_setting_summary),
                checked = settings.waitingAlerts,
                onChange = { on -> scope.launch { app.store.setWaitingAlerts(on) } },
            )
            if (settings.waitingAlerts) SettingsBlock {
                Text(
                    stringResource(R.string.waiting_alert_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { context.startActivity(TaskNotifier.waitingChannelSettings(context)) }) {
                    Text(stringResource(R.string.action_change_alert))
                }
            }
        }
    }
}

/** Asks the system to add the Clauder tile to Quick Settings (Android 13 and later). */
private fun requestTile(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    context.getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
        android.content.ComponentName(context, to.wim.clauder.tile.ClauderTileService::class.java),
        context.getString(R.string.app_name),
        android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_stat_clauder),
        context.mainExecutor,
    ) { }
}

private fun canPromote(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA ||
        context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()

private fun appNotificationSettings(context: Context): Intent =
    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)

private fun promotionSettings(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        appNotificationSettings(context)
    }

@Composable
private fun EnvironmentSection(settings: Settings) {
    val host = WorkerUrl.host(settings.workerUrl).ifBlank { DEFAULT_WORKER }
    SettingsSection(stringResource(R.string.section_environments)) {
        if (settings.isConnected) SetupCheckBlock(settings)
        ExpandableSetting(
            title = stringResource(R.string.env_steps_title),
            summary = stringResource(R.string.env_steps_summary),
        ) {
            SettingsBlock {
                Step(1, stringResource(R.string.env_step_open))
                Step(2, stringResource(R.string.env_step_script))
                CopyBlock(HookSetup.script(host))
                Step(3, stringResource(R.string.env_step_variable))
                CopyBlock(if (settings.token.isNotBlank()) "CLAUDER_HOOK_TOKEN=${settings.token}" else "CLAUDER_HOOK_TOKEN=…")
                Step(4, stringResource(R.string.env_step_domain))
                CopyBlock(host)
                Text(
                    stringResource(R.string.env_step_new),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/** Whether hook events reach the Worker: when the last one came, and from current hooks or not. */
@Composable
private fun SetupCheckBlock(settings: Settings) {
    val scope = rememberCoroutineScope()
    val res = LocalResources.current
    var result by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    suspend fun check() {
        checking = true
        result = when (val r = WorkerClient(settings.workerUrl, settings.token).status()) {
            is WorkerClient.Result.Ok -> {
                val now = System.currentTimeMillis()
                fun ago(at: Long) = DateUtils.getRelativeTimeSpanString(minOf(at, now), now, DateUtils.MINUTE_IN_MILLIS).toString()
                when (val c = SetupCheck.from(r.value)) {
                    SetupCheck.NoEvents -> res.getString(R.string.setup_check_none)
                    is SetupCheck.Working -> res.getString(R.string.setup_check_ok, ago(c.atMillis))
                    is SetupCheck.OldHooks -> res.getString(R.string.setup_check_old, ago(c.atMillis))
                }
            }
            WorkerClient.Result.Unauthorized -> res.getString(R.string.connect_wrong_token)
            is WorkerClient.Result.Failed -> res.getString(R.string.setup_check_failed, r.message)
        }
        checking = false
    }
    LaunchedEffect(settings.workerUrl, settings.token) { check() }
    SettingsBlock {
        Text(
            result ?: stringResource(R.string.setup_check_running),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { scope.launch { check() } }, enabled = !checking) {
            Text(stringResource(R.string.action_check_again))
        }
    }
}

/** GitHub sends CI results and PR states to the Worker once its GitHub App is installed. */
@Composable
private fun GithubSection(settings: Settings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = LocalResources.current
    var status by remember { mutableStateOf<GithubStatus?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun failure(r: WorkerClient.Result<*>): String? = when (r) {
        is WorkerClient.Result.Ok -> null
        WorkerClient.Result.Unauthorized -> res.getString(R.string.connect_wrong_token)
        is WorkerClient.Result.Failed -> res.getString(R.string.github_failed, r.message)
    }
    suspend fun check() {
        busy = true
        val r = WorkerClient(settings.workerUrl, settings.token).github()
        status = (r as? WorkerClient.Result.Ok)?.value
        message = failure(r)
        busy = false
    }
    LaunchedEffect(settings.workerUrl, settings.token) { check() }
    SettingsSection(stringResource(R.string.section_github)) {
        SettingsBlock {
            val current = status
            val now = System.currentTimeMillis()
            val text = when {
                current == null -> message ?: stringResource(R.string.github_checking)
                !current.connected -> stringResource(R.string.github_intro)
                current.lastEventAtMillis == null -> stringResource(R.string.github_no_events)
                else -> stringResource(
                    R.string.github_ok,
                    DateUtils.getRelativeTimeSpanString(minOf(current.lastEventAtMillis, now), now, DateUtils.MINUTE_IN_MILLIS).toString(),
                )
            }
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (current != null) message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current?.connected == true) {
                    current.installUrl?.let { url ->
                        Button(onClick = { ClaudeApp.openAny(context, url) }) { Text(stringResource(R.string.action_github_repos)) }
                    }
                    OutlinedButton(onClick = { scope.launch { check() } }, enabled = !busy) { Text(stringResource(R.string.action_check_again)) }
                } else if (current != null) {
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                val r = WorkerClient(settings.workerUrl, settings.token).githubConnect()
                                val url = (r as? WorkerClient.Result.Ok)?.value
                                message = failure(r) ?: if (url == null) res.getString(R.string.github_failed, "") else null
                                url?.let { ClaudeApp.openAny(context, it) }
                                busy = false
                            }
                        },
                        enabled = !busy,
                    ) { Text(stringResource(R.string.action_connect_github)) }
                    OutlinedButton(onClick = { scope.launch { check() } }, enabled = !busy) { Text(stringResource(R.string.action_check_again)) }
                } else {
                    OutlinedButton(onClick = { scope.launch { check() } }, enabled = !busy) { Text(stringResource(R.string.action_check_again)) }
                }
            }
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("$number.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Monospace text with a Copy button, for things to paste into the Claude app. */
@Composable
internal fun CopyBlock(text: String, maxLines: Int = 8) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                softWrap = false,
                maxLines = maxLines,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
            TextButton(
                onClick = { copy(context, text) },
                modifier = Modifier.align(Alignment.End).padding(end = 4.dp),
            ) {
                Icon(painterResource(R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.action_copy))
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
}

@Composable
private fun AppearanceSection(app: ClauderApp, settings: Settings) {
    val scope = rememberCoroutineScope()
    SettingsSection(stringResource(R.string.section_appearance)) {
        SettingsBlock {
            Text(stringResource(R.string.theme), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
            val options = listOf(ThemeMode.System to R.string.theme_system, ThemeMode.Light to R.string.theme_light, ThemeMode.Dark to R.string.theme_dark)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = settings.themeMode == mode,
                        onClick = { scope.launch { app.store.setTheme(mode) } },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) { Text(stringResource(label)) }
                }
            }
        }
        SwitchSetting(
            title = stringResource(R.string.dynamic_color),
            summary = stringResource(R.string.dynamic_color_summary),
            checked = settings.dynamicColor,
            onChange = { on -> scope.launch { app.store.setDynamicColor(on) } },
        )
    }
}
