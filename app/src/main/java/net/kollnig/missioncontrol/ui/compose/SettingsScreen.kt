/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl. If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright © 2026 TrackerControl
 */
package net.kollnig.missioncontrol.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.kollnig.missioncontrol.R

enum class SettingsPage {
    ROOT,
    ADVANCED,
    WIREGUARD,
    TAILSCALE,
    DEVELOPMENT,
}

sealed interface SettingsItem {
    val key: String
    val title: String
    val summary: String?
    val enabled: Boolean

    data class SwitchItem(
        override val key: String,
        override val title: String,
        override val summary: String? = null,
        val checked: Boolean,
        override val enabled: Boolean = true,
    ) : SettingsItem

    data class NavigationItem(
        override val key: String,
        val targetPage: SettingsPage,
        override val title: String,
        override val summary: String? = null,
        override val enabled: Boolean = true,
    ) : SettingsItem

    data class ListItem(
        override val key: String,
        override val title: String,
        override val summary: String? = null,
        val value: String,
        val entries: List<Pair<String, String>>,
        override val enabled: Boolean = true,
    ) : SettingsItem

    data class TextInputItem(
        override val key: String,
        override val title: String,
        override val summary: String? = null,
        val value: String,
        val isPassword: Boolean = false,
        override val enabled: Boolean = true,
        val isNumber: Boolean = false,
    ) : SettingsItem {
        constructor(
            key: String,
            title: String,
            summary: String?,
            value: String,
            isPassword: Boolean,
            enabled: Boolean,
        ) : this(key, title, summary, value, isPassword, enabled, false)
    }

    data class ActionItem(
        override val key: String,
        override val title: String,
        override val summary: String? = null,
        override val enabled: Boolean = true,
    ) : SettingsItem
}

data class SettingsSection(
    val title: String? = null,
    val items: List<SettingsItem>,
)

data class SettingsScreenModel(
    val page: SettingsPage = SettingsPage.ROOT,
    val pageTitle: String = "",
    val sections: List<SettingsSection> = emptyList(),
)

interface SettingsScreenCallbacks {
    fun onNavigateUp()
    fun onOpenPage(page: SettingsPage)
    fun onToggleSwitch(key: String, checked: Boolean)
    fun onSelectListOption(key: String, value: String)
    fun onSubmitTextInput(key: String, value: String)
    fun onActionClick(key: String)
}

@Stable
class SettingsScreenController {
    var model by mutableStateOf(SettingsScreenModel())
        private set

    fun update(newModel: SettingsScreenModel) {
        model = newModel
    }
}

object SettingsScreen {
    @JvmStatic
    fun install(
        composeView: ComposeView,
        callbacks: SettingsScreenCallbacks,
    ): SettingsScreenController {
        val controller = SettingsScreenController()
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            TrackerControlTheme {
                SettingsScreenContent(
                    model = controller.model,
                    callbacks = callbacks,
                )
            }
        }
        return controller
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenContent(
    model: SettingsScreenModel,
    callbacks: SettingsScreenCallbacks,
) {
    var activeListDialog by remember(model.page) { mutableStateOf<SettingsItem.ListItem?>(null) }
    var activeTextDialog by remember(model.page) { mutableStateOf<SettingsItem.TextInputItem?>(null) }

    Scaffold(
        topBar = {
            ExpressiveTopAppBar(
                title = model.pageTitle.ifEmpty { stringResource(R.string.menu_settings) },
                onNavigateUp = callbacks::onNavigateUp,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("settings_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(
                items = model.sections,
                key = { index, section -> "${model.page.name}_${index}_${section.title ?: ""}" },
            ) { _, section ->
                if (section.items.isNotEmpty()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (!section.title.isNullOrBlank()) {
                            ExpressiveSectionLabel(
                                text = section.title,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                            )
                        }
                        ExpressiveGroupedColumn {
                            section.items.forEachIndexed { index, item ->
                                SettingsItemRow(
                                    item = item,
                                    index = index,
                                    count = section.items.size,
                                    onClick = {
                                        when (item) {
                                            is SettingsItem.SwitchItem ->
                                                callbacks.onToggleSwitch(item.key, !item.checked)
                                            is SettingsItem.NavigationItem ->
                                                callbacks.onOpenPage(item.targetPage)
                                            is SettingsItem.ListItem ->
                                                activeListDialog = item
                                            is SettingsItem.TextInputItem ->
                                                activeTextDialog = item
                                            is SettingsItem.ActionItem ->
                                                callbacks.onActionClick(item.key)
                                        }
                                    },
                                    onCheckedChange = { checked ->
                                        if (item is SettingsItem.SwitchItem) {
                                            callbacks.onToggleSwitch(item.key, checked)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    activeListDialog?.let { listItem ->
        SettingsListDialog(
            item = listItem,
            onDismiss = { activeListDialog = null },
            onSelect = { value ->
                activeListDialog = null
                callbacks.onSelectListOption(listItem.key, value)
            },
        )
    }

    activeTextDialog?.let { textItem ->
        SettingsTextInputDialog(
            item = textItem,
            onDismiss = { activeTextDialog = null },
            onConfirm = { value ->
                activeTextDialog = null
                callbacks.onSubmitTextInput(textItem.key, value)
            },
        )
    }
}

@Composable
private fun SettingsItemRow(
    item: SettingsItem,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    val interactionModifier = if (item is SettingsItem.SwitchItem) {
        Modifier.toggleable(
            value = item.checked,
            enabled = item.enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier.clickable(enabled = item.enabled, onClick = onClick)
    }
    ExpressiveGroupedSurface(
        index = index,
        count = count,
        modifier = Modifier
            .testTag("pref_${item.key}")
            .alpha(if (item.enabled) 1f else 0.5f)
            .then(interactionModifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val summary = item.summary
                if (!summary.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when (item) {
                is SettingsItem.SwitchItem -> {
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = item.checked,
                        onCheckedChange = null,
                        enabled = item.enabled,
                    )
                }
                is SettingsItem.NavigationItem -> {
                    Spacer(modifier = Modifier.width(12.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun SettingsListDialog(
    item: SettingsItem.ListItem,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                item.entries.forEach { (entryLabel, entryValue) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(entryValue) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = item.value == entryValue,
                            onClick = { onSelect(entryValue) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = entryLabel,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun SettingsTextInputDialog(
    item: SettingsItem.TextInputItem,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(item.key, item.value) { mutableStateOf(item.value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (item.isNumber) KeyboardType.Number else KeyboardType.Text,
                ),
                visualTransformation = if (item.isPassword) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
