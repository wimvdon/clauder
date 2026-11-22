package to.wim.clauder.ui.linktester

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import java.net.URISyntaxException
import to.wim.clauder.ClauderApp
import to.wim.clauder.R
import to.wim.clauder.links.ClaudeApp
import to.wim.clauder.links.LinkProbe
import to.wim.clauder.links.LinkProbes
import to.wim.clauder.links.ProbeOutcome
import to.wim.clauder.links.ProbeResolution
import to.wim.clauder.settings.Settings
import to.wim.clauder.ui.theme.AppTheme
import to.wim.clauder.ui.theme.isDarkTheme
import to.wim.clauder.ui.theme.readableWidth

/** Test builds only: tries ways of opening the Claude app, to find the links its widgets use. */
class LinkTesterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as ClauderApp
        setContent {
            val settings by app.store.settings.collectAsState(initial = null)
            val current = settings ?: Settings()
            AppTheme(darkTheme = isDarkTheme(current.themeMode), dynamicColor = current.dynamicColor) {
                LinkTesterScreen(onBack = ::finish)
            }
        }
    }
}

private class ClaudeAppInfo(val version: String?, val activities: List<String>, val apk: String? = null)

private fun claudeAppInfo(context: Context): ClaudeAppInfo = try {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= 33) {
        pm.getPackageInfo(ClaudeApp.PACKAGE, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(ClaudeApp.PACKAGE, PackageManager.GET_ACTIVITIES)
    }
    val activities = info.activities.orEmpty().filter { it.exported }.map { it.name.removePrefix(ClaudeApp.PACKAGE) }
    ClaudeAppInfo(info.versionName ?: "?", activities, info.applicationInfo?.sourceDir)
} catch (e: PackageManager.NameNotFoundException) {
    ClaudeAppInfo(null, emptyList())
}

/** Copies the Claude app's own APK to a file the user picked, so its widgets' links can be looked up. */
private fun saveApk(context: Context, apk: String, target: Uri): Boolean = try {
    val out = context.contentResolver.openOutputStream(target) ?: throw IOException("no output")
    out.use { File(apk).inputStream().use { input -> input.copyTo(it) } }
    true
} catch (e: IOException) {
    false
} catch (e: SecurityException) {
    false
}

private fun LinkProbe.toIntent(context: Context): Intent? {
    if (launcher) return context.packageManager.getLaunchIntentForPackage(pkg ?: ClaudeApp.PACKAGE)
    if (intentUri != null) {
        return try {
            Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME)
        } catch (e: URISyntaxException) {
            null
        }
    }
    val intent = Intent(action)
    uri?.let { intent.data = it.toUri() }
    if (component != null) intent.setClassName(pkg ?: ClaudeApp.PACKAGE, component) else pkg?.let { intent.setPackage(it) }
    extras.forEach { (key, value) -> intent.putExtra(key, value) }
    return intent
}

private fun describe(filter: IntentFilter?): String? {
    filter ?: return null
    val parts = buildList {
        filter.schemesIterator()?.asSequence()?.toList()?.takeIf { it.isNotEmpty() }?.let { add("schemes ${it.joinToString()}") }
        filter.authoritiesIterator()?.asSequence()?.map { it.host }?.toList()?.takeIf { it.isNotEmpty() }?.let { add("hosts ${it.joinToString()}") }
        filter.pathsIterator()?.asSequence()?.map { it.path }?.toList()?.takeIf { it.isNotEmpty() }?.let { add("paths ${it.joinToString()}") }
    }
    return parts.joinToString("; ").ifBlank { null }
}

private fun resolve(context: Context, probe: LinkProbe): ProbeResolution {
    val intent = probe.toIntent(context) ?: return ProbeResolution(null)
    val pm = context.packageManager
    val flags = PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER
    val matches: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= 33) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, flags)
    }
    val first = matches.firstOrNull()?.activityInfo ?: return ProbeResolution(null)
    val name = "${first.packageName}/${first.name.removePrefix(first.packageName)}" +
        if (matches.size > 1) " (+${matches.size - 1})" else ""
    return ProbeResolution(name, first.exported, describe(matches.first().filter))
}

private fun fire(context: Context, probe: LinkProbe): ProbeOutcome {
    val intent = probe.toIntent(context) ?: return ProbeOutcome.NoMatch
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ProbeOutcome.Started
    } catch (e: ActivityNotFoundException) {
        ProbeOutcome.NoMatch
    } catch (e: SecurityException) {
        ProbeOutcome.Failed(e.message ?: e.javaClass.simpleName)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkTesterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.link_tester_title)
    val info = remember { claudeAppInfo(context) }
    var custom by rememberSaveable { mutableStateOf("") }
    var claudeOnly by rememberSaveable { mutableStateOf(true) }
    val customProbes = remember { mutableStateOf(emptyList<LinkProbe>()) }
    val outcomes = remember { mutableStateMapOf<LinkProbe, ProbeOutcome>() }
    val probes = customProbes.value + LinkProbes.all
    val resolutions = remember(probes) { probes.associateWith { resolve(context, it) } }

    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<Boolean?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { target ->
        val apk = info.apk
        if (target != null && apk != null) {
            scope.launch { saved = withContext(Dispatchers.IO) { saveApk(context, apk, target) } }
        }
    }

    fun tryProbe(probe: LinkProbe) {
        outcomes[probe] = fire(context, probe)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.link_tester_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val text = LinkProbes.report(info.version, info.activities, probes.map { Triple(it, resolutions[it], outcomes[it]) })
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText(clipLabel, text))
                    }) {
                        Icon(painterResource(R.drawable.ic_copy), contentDescription = stringResource(R.string.link_tester_copy))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(Modifier.readableWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        info.version?.let { stringResource(R.string.link_tester_app_version, it) }
                            ?: stringResource(R.string.link_tester_not_installed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (info.apk != null) {
                        Text(stringResource(R.string.link_tester_save_app_summary), style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { save.launch("claude-${info.version}.apk") }) {
                                Text(stringResource(R.string.link_tester_save_app))
                            }
                            saved?.let {
                                Text(
                                    stringResource(if (it) R.string.link_tester_saved else R.string.link_tester_save_failed),
                                    color = if (it) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it },
                        label = { Text(stringResource(R.string.link_tester_custom)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.link_tester_claude_only), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = claudeOnly, onCheckedChange = { claudeOnly = it })
                    }
                    val probe = LinkProbes.custom(custom, claudeOnly)
                    Button(
                        onClick = {
                            probe ?: return@Button
                            if (probe !in customProbes.value) customProbes.value = listOf(probe) + customProbes.value
                            tryProbe(probe)
                        },
                        enabled = probe != null,
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(stringResource(R.string.action_try)) }
                }
            }
            items(probes) { probe ->
                HorizontalDivider(Modifier.readableWidth())
                ProbeRow(probe, resolutions[probe], outcomes[probe]) { tryProbe(probe) }
            }
            if (info.activities.isNotEmpty()) {
                item {
                    Column(Modifier.readableWidth().padding(16.dp)) {
                        Text(
                            stringResource(R.string.link_tester_activities),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.semantics { heading() },
                        )
                        info.activities.forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProbeRow(probe: LinkProbe, resolution: ProbeResolution?, outcome: ProbeOutcome?, onTry: () -> Unit) {
    ListItem(
        modifier = Modifier.readableWidth(),
        headlineContent = { Text(probe.label, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) },
        supportingContent = {
            Column {
                val target = resolution?.activity
                Text(
                    when {
                        target == null -> stringResource(R.string.link_tester_no_match)
                        !resolution.exported -> stringResource(R.string.link_tester_opens_closed, target)
                        else -> stringResource(R.string.link_tester_opens, target)
                    },
                )
                resolution?.filter?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                outcome?.let {
                    Text(
                        when (it) {
                            ProbeOutcome.Started -> stringResource(R.string.link_tester_started)
                            ProbeOutcome.NoMatch -> stringResource(R.string.link_tester_no_match)
                            is ProbeOutcome.Failed -> stringResource(R.string.link_tester_failed, it.reason)
                        },
                        color = if (it == ProbeOutcome.Started) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        trailingContent = { TextButton(onClick = onTry) { Text(stringResource(R.string.action_try)) } },
    )
}
