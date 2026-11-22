package to.wim.clauder.ui.shortcuts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.wim.clauder.ClauderApp
import to.wim.clauder.R
import to.wim.clauder.shortcuts.ClaudeLink
import to.wim.clauder.shortcuts.PinnedShortcut
import to.wim.clauder.shortcuts.PinnedShortcuts
import to.wim.clauder.shortcuts.ShortcutPinner
import to.wim.clauder.ui.theme.EmptyState
import to.wim.clauder.ui.theme.readableWidth

@Composable
private fun kindIcon(kind: ClaudeLink.Kind): Int = when (kind) {
    ClaudeLink.Kind.Chat -> R.drawable.ic_kind_chat
    ClaudeLink.Kind.Project -> R.drawable.ic_kind_project
    ClaudeLink.Kind.Artifact -> R.drawable.ic_kind_artifact
    ClaudeLink.Kind.CodeSession -> R.drawable.ic_kind_code
    ClaudeLink.Kind.Other -> R.drawable.ic_kind_link
}

/**
 * Pinned home screen shortcuts to claude.ai pages. [sharedText] is text shared to Clauder from
 * another app; when it is set the Add dialog opens with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutsScreen(app: ClauderApp, resumes: Int, sharedText: String?, onSharedHandled: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val stored by app.store.pinned.collectAsState(initial = emptyList())
    val pinnedIds = remember(resumes, stored) { ShortcutPinner.pinnedIds(context) }
    val shortcuts = PinnedShortcuts.visible(stored, pinnedIds)
    val snackbar = remember { SnackbarHostState() }
    var adding by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<PinnedShortcut?>(null) }

    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            adding = sharedText
            onSharedHandled()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.shortcuts_title)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = "" },
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text(stringResource(R.string.action_add)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (shortcuts.isEmpty()) {
            EmptyState(
                icon = R.drawable.ic_bookmark_outlined,
                title = stringResource(R.string.shortcuts_empty_title),
                body = stringResource(R.string.shortcuts_empty_body),
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 88.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    Text(
                        stringResource(R.string.shortcuts_static_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.readableWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(shortcuts, key = { it.id }) { shortcut ->
                    ListItem(
                        headlineContent = { Text(shortcut.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(shortcut.url.removePrefix("https://"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = { Icon(painterResource(kindIcon(shortcut.kind)), contentDescription = null) },
                        modifier = Modifier.readableWidth().clickable { editing = shortcut },
                    )
                }
            }
        }
    }

    adding?.let { initial ->
        AddShortcutDialog(
            initialText = initial,
            onDismiss = { adding = null },
            onAdd = { link, name ->
                adding = null
                val shortcut = PinnedShortcut(PinnedShortcuts.newId(System.currentTimeMillis()), name, link.url, link.kind)
                scope.launch {
                    if (ShortcutPinner.canPin(context) && ShortcutPinner.requestPin(context, shortcut)) {
                        app.store.savePinned(PinnedShortcuts.add(stored, link, name, shortcut.id))
                    } else {
                        snackbar.showSnackbar(res.getString(R.string.shortcut_pin_unsupported))
                    }
                }
            },
        )
    }

    editing?.let { shortcut ->
        EditShortcutDialog(
            shortcut = shortcut,
            onDismiss = { editing = null },
            onRename = { name ->
                editing = null
                scope.launch {
                    app.store.savePinned(PinnedShortcuts.rename(stored, shortcut.id, name))
                    ShortcutPinner.rename(context, shortcut.copy(name = name))
                }
            },
            onRemove = {
                editing = null
                scope.launch {
                    app.store.savePinned(PinnedShortcuts.remove(stored, shortcut.id))
                    ShortcutPinner.remove(context, shortcut.id)
                    snackbar.showSnackbar(res.getString(R.string.shortcut_removed_hint))
                }
            },
        )
    }
}

@Composable
private fun AddShortcutDialog(initialText: String, onDismiss: () -> Unit, onAdd: (ClaudeLink, String) -> Unit) {
    var link by rememberSaveable { mutableStateOf(ClaudeLink.find(initialText)?.url ?: initialText.trim()) }
    var name by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<PinnedShortcuts.Draft?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shortcut_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it; problem = null },
                    label = { Text(stringResource(R.string.shortcut_link)) },
                    placeholder = { Text("https://claude.ai/…") },
                    isError = problem == PinnedShortcuts.Draft.BadLink,
                    supportingText = if (problem == PinnedShortcuts.Draft.BadLink) {
                        { Text(stringResource(R.string.shortcut_bad_link)) }
                    } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; problem = null },
                    label = { Text(stringResource(R.string.shortcut_name)) },
                    isError = problem == PinnedShortcuts.Draft.MissingName,
                    supportingText = if (problem == PinnedShortcuts.Draft.MissingName) {
                        { Text(stringResource(R.string.shortcut_missing_name)) }
                    } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val draft = PinnedShortcuts.draft(link, name)) {
                    is PinnedShortcuts.Draft.Valid -> onAdd(draft.link, draft.name)
                    else -> problem = draft
                }
            }) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun EditShortcutDialog(shortcut: PinnedShortcut, onDismiss: () -> Unit, onRename: (String) -> Unit, onRemove: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(shortcut.name) }
    val clean = PinnedShortcuts.cleanName(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shortcut_edit_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.shortcut_name)) },
                    isError = clean == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onRemove) {
                    Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { clean?.let(onRename) }, enabled = clean != null) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
