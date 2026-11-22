package to.wim.clauder.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import to.wim.clauder.R

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = 16.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        content()
    }
}

private val transparentRow @Composable get() = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)

@Composable
fun SwitchSetting(title: String, checked: Boolean, onChange: (Boolean) -> Unit, summary: String? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = transparentRow,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

/** Anything that isn't a row: a field, a button, an explanation. Lined up with the rows' text. */
@Composable
fun SettingsBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), content = content)
}

/** A row that shows longer instructions below it. */
@Composable
fun ExpandableSetting(title: String, summary: String?, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val state = stringResource(if (expanded) R.string.section_expanded else R.string.section_collapsed)
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = {
            Icon(
                painterResource(R.drawable.ic_expand_more),
                contentDescription = null,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        },
        colors = transparentRow,
        modifier = Modifier
            .clickable(role = Role.Button) { expanded = !expanded }
            .semantics { stateDescription = state },
    )
    AnimatedVisibility(expanded) { Column(content = content) }
}
