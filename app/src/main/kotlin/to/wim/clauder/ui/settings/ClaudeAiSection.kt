package to.wim.clauder.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.wim.clauder.ClauderApp
import to.wim.clauder.R
import to.wim.clauder.claudeai.ClaudeAiClient
import to.wim.clauder.claudeai.Probe

/** Sign in to claude.ai, test what its private endpoints return, and sign out again. */
@Composable
fun ClaudeAiSection(app: ClauderApp, onConnect: () -> Unit) {
    val scope = rememberCoroutineScope()
    val connected by app.claudeAiLogin.isConnected.collectAsState(initial = false)
    var testing by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var unreadable by remember { mutableStateOf(false) }

    SettingsSection(stringResource(R.string.section_claude_ai)) {
        SettingsBlock {
            if (connected) {
                Text(stringResource(R.string.claude_ai_connected), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                Row {
                    Button(
                        onClick = {
                            scope.launch {
                                testing = true
                                report = null
                                val key = app.claudeAiLogin.sessionKey()
                                unreadable = key == null
                                if (key != null) report = Probe.run(ClaudeAiClient(key), app.claudeAiLogin.organization())
                                testing = false
                            }
                        },
                        enabled = !testing,
                    ) {
                        if (testing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        }
                        Text(stringResource(R.string.action_test_claude_ai))
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                app.claudeAiLogin.clear()
                                report = null
                                unreadable = false
                            }
                        },
                        enabled = !testing,
                    ) { Text(stringResource(R.string.action_sign_out)) }
                }
                if (testing) Hint(stringResource(R.string.claude_ai_testing))
                if (unreadable) Hint(stringResource(R.string.claude_ai_unreadable), isError = true)
                report?.let {
                    Hint(stringResource(R.string.claude_ai_report_help))
                    CopyBlock(it, maxLines = 20)
                }
            } else {
                Text(
                    stringResource(R.string.claude_ai_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.claude_ai_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onConnect) { Text(stringResource(R.string.action_connect_claude_ai)) }
            }
        }
    }
}

@Composable
private fun Hint(text: String, isError: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}
