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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.kollnig.missioncontrol.R

data class VpnCountryUi(
    val code: String,
    val name: String,
    val provider: String,
    val flag: String,
    val isActive: Boolean,
    val isGenerating: Boolean,
    val enabled: Boolean,
)

data class VpnProfileUi(
    val id: String,
    val name: String,
    val summary: String,
    val isActive: Boolean,
    val enabled: Boolean,
)

data class VpnScreenModel(
    val isEnabled: Boolean = false,
    val isFirstTimeSetup: Boolean = false,
    val providerMode: String = "mullvad",
    val statusFlag: String = "",
    val statusTitle: String = "",
    val statusSummary: String = "",
    val showSettingsButton: Boolean = false,
    val settingsContentDescription: String = "",
    val progressMessage: String? = null,
    val countryErrorVisible: Boolean = false,
    val showProviderSetup: Boolean = false,
    val providerSetupMessage: String = "",
    val providerSetupActionLabel: String = "",
    val countries: List<VpnCountryUi> = emptyList(),
    val customProfiles: List<VpnProfileUi> = emptyList(),
    val wireGuardFooterLabel: String = "",
)

interface VpnScreenCallbacks {
    fun onToggleEnabled(enabled: Boolean)
    fun onSelectProviderMode(mode: String)
    fun onOpenSettings()
    fun onOpenMullvadSetup()
    fun onOpenMullvadAccountPage()
    fun onOpenIvpnSetup()
    fun onOpenIvpnAccountPage()
    fun onOpenWireGuardProfiles(setup: String?)
    fun onRetryCountries()
    fun onSelectCountry(country: VpnCountryUi)
    fun onSelectCustomProfile(profile: VpnProfileUi)
}

@Stable
class VpnScreenController {
    var model by mutableStateOf(VpnScreenModel())
        private set

    fun update(newModel: VpnScreenModel) {
        model = newModel
    }
}

object VpnScreen {
    @JvmStatic
    fun install(
        composeView: ComposeView,
        callbacks: VpnScreenCallbacks,
    ): VpnScreenController {
        val controller = VpnScreenController()
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            TrackerControlTheme {
                VpnScreenContent(
                    model = controller.model,
                    callbacks = callbacks,
                )
            }
        }
        return controller
    }
}

@Composable
fun VpnScreenContent(
    model: VpnScreenModel,
    callbacks: VpnScreenCallbacks,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(ExpressiveGroupSpacing),
    ) {
        item(key = "status_hero") {
            VpnStatusHeroCard(model = model, callbacks = callbacks)
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (model.isFirstTimeSetup) {
            item(key = "intro") {
                VpnFirstTimeIntroSection(callbacks = callbacks)
            }
            return@LazyColumn
        }

        item(key = "mode_selector") {
            VpnProviderModeSelector(
                selectedMode = model.providerMode,
                onSelectMode = callbacks::onSelectProviderMode,
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (!model.progressMessage.isNullOrBlank()) {
            item(key = "progress") {
                VpnProgressCard(message = model.progressMessage)
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        if (model.countryErrorVisible && model.providerMode != "wireguard") {
            item(key = "country_error") {
                VpnCountryErrorCard(onRetry = callbacks::onRetryCountries)
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        if (model.providerMode == "wireguard") {
            if (model.customProfiles.isEmpty()) {
                item(key = "empty_profiles") {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.vpn_no_wireguard_profiles),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
            } else {
                itemsIndexed(
                    items = model.customProfiles,
                    key = { _, item -> "profile_${item.id}" },
                ) { index, profile ->
                    VpnCustomProfileRow(
                        profile = profile,
                        index = index,
                        count = model.customProfiles.size,
                        onClick = { callbacks.onSelectCustomProfile(profile) },
                    )
                }
            }

            item(key = "wg_footer") {
                Spacer(modifier = Modifier.height(10.dp))
                VpnWireGuardFooter(
                    manageLabel = model.wireGuardFooterLabel,
                    onManageProfiles = { callbacks.onOpenWireGuardProfiles(null) },
                    onProtonSetup = { callbacks.onOpenWireGuardProfiles("proton") },
                )
            }
        } else if (model.showProviderSetup) {
            item(key = "provider_setup") {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = model.providerSetupMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                if (model.providerMode == "ivpn") {
                                    callbacks.onOpenIvpnSetup()
                                } else {
                                    callbacks.onOpenMullvadSetup()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = model.providerSetupActionLabel)
                        }
                    }
                }
            }
        } else {
            itemsIndexed(
                items = model.countries,
                key = { _, item -> "country_${item.provider}_${item.code}" },
            ) { index, country ->
                VpnCountryRow(
                    country = country,
                    index = index,
                    count = model.countries.size,
                    onClick = { callbacks.onSelectCountry(country) },
                )
            }
        }
    }
}

@Composable
private fun VpnStatusHeroCard(
    model: VpnScreenModel,
    callbacks: VpnScreenCallbacks,
) {
    val containerColor = if (model.isEnabled) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (model.isEnabled) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val switchCd = stringResource(R.string.vpn_enabled_switch_description)

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (model.statusFlag.isNotEmpty()) {
                Text(
                    text = model.statusFlag,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.statusTitle.ifEmpty {
                        stringResource(R.string.vpn_status_disconnected)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (model.statusSummary.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = model.statusSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (model.showSettingsButton) {
                FilledTonalIconButton(
                    onClick = callbacks::onOpenSettings,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings_black_24dp),
                        contentDescription = model.settingsContentDescription,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            Switch(
                checked = model.isEnabled,
                onCheckedChange = callbacks::onToggleEnabled,
                modifier = Modifier.semantics { contentDescription = switchCd },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VpnProviderModeSelector(
    selectedMode: String,
    onSelectMode: (String) -> Unit,
) {
    val options = listOf(
        "mullvad" to stringResource(R.string.vpn_mode_mullvad),
        "ivpn" to stringResource(R.string.vpn_mode_ivpn),
        "wireguard" to stringResource(R.string.vpn_mode_wireguard),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, (mode, label) ->
            val shapes = when (index) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            ToggleButton(
                checked = selectedMode == mode,
                onCheckedChange = { checked ->
                    if (checked) onSelectMode(mode)
                },
                shapes = shapes,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun VpnFirstTimeIntroSection(callbacks: VpnScreenCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.vpn_intro_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.vpn_intro_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ExpressiveGroupedColumn {
            ExpressiveGroupedSurface(index = 0, count = 4) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.vpn_intro_mullvad_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.vpn_intro_mullvad_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = callbacks::onOpenMullvadAccountPage,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = stringResource(R.string.vpn_get_mullvad_account),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Button(
                            onClick = callbacks::onOpenMullvadSetup,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = stringResource(R.string.vpn_enter_mullvad_account),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            ExpressiveGroupedSurface(index = 1, count = 4) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.vpn_intro_ivpn_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.vpn_intro_ivpn_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = callbacks::onOpenIvpnAccountPage,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = stringResource(R.string.vpn_get_ivpn_account),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Button(
                            onClick = callbacks::onOpenIvpnSetup,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = stringResource(R.string.vpn_enter_ivpn_account),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            ExpressiveGroupedSurface(index = 2, count = 4) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.vpn_intro_proton_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.vpn_intro_proton_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = { callbacks.onOpenWireGuardProfiles("proton") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(R.string.setting_wg_proton_setup))
                    }
                }
            }

            ExpressiveGroupedSurface(index = 3, count = 4) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.vpn_intro_wireguard_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.vpn_intro_wireguard_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = { callbacks.onOpenWireGuardProfiles(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(R.string.vpn_import_wireguard_profile))
                    }
                }
            }
        }
    }
}

@Composable
private fun VpnProgressCard(message: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExpressiveLoadingIndicator(contained = true)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun VpnCountryErrorCard(onRetry: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.vpn_country_load_failed_short),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = onRetry) {
                Text(text = stringResource(R.string.vpn_retry))
            }
        }
    }
}

@Composable
private fun VpnCountryRow(
    country: VpnCountryUi,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val containerColor = if (country.isActive) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = if (country.isActive) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    ExpressiveGroupedSurface(
        index = index,
        count = count,
        color = containerColor,
        contentColor = contentColor,
        onClick = onClick,
        enabled = country.enabled,
        modifier = Modifier.alpha(if (country.enabled || country.isGenerating) 1f else 0.5f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = country.flag,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = country.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (country.isActive) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            if (country.isGenerating) {
                ExpressiveLoadingIndicator(modifier = Modifier.size(28.dp))
            } else if (country.isActive) {
                Text(
                    text = stringResource(R.string.vpn_country_selected),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun VpnCustomProfileRow(
    profile: VpnProfileUi,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val containerColor = if (profile.isActive) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = if (profile.isActive) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    ExpressiveGroupedSurface(
        index = index,
        count = count,
        color = containerColor,
        contentColor = contentColor,
        onClick = onClick,
        enabled = profile.enabled,
        modifier = Modifier.alpha(if (profile.enabled) 1f else 0.5f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (profile.isActive) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (profile.summary.isNotEmpty()) {
                    Text(
                        text = profile.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.75f),
                    )
                }
            }
            if (profile.isActive) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.vpn_country_selected),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun VpnWireGuardFooter(
    manageLabel: String,
    onManageProfiles: () -> Unit,
    onProtonSetup: () -> Unit,
) {
    ExpressiveGroupedColumn {
        ExpressiveGroupedSurface(
            index = 0,
            count = 2,
            onClick = onManageProfiles,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = manageLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ExpressiveGroupedSurface(
            index = 1,
            count = 2,
            onClick = onProtonSetup,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.setting_wg_proton_setup),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
