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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kollnig.missioncontrol.R

object MainTabs {
    const val TIMELINE = 0
    const val APPS = 1
    const val VPN = 2
}

enum class MainDialog {
    NONE,
    LEGEND,
    ABOUT,
    TROUBLESHOOTING,
}

data class AppRuleUi(
    val uid: Int,
    val packageName: String,
    val name: String,
    val hasInternet: Boolean,
    val isSystem: Boolean,
    val isChanged: Boolean,
    val isActiveProtection: Boolean,
    val isInternetBlocked: Boolean,
    val detailsText: String,
    val toggleContentDescription: String,
)

data class MainBannerState(
    val showResearchMode: Boolean = false,
    val showNotifications: Boolean = false,
    val showLocalNetwork: Boolean = false,
    val showPrivateDnsBlocked: Boolean = false,
    val privateDnsBypassMessage: String? = null,
)

data class MainFilterState(
    val manageSystem: Boolean = false,
    val showUser: Boolean = true,
    val showSystem: Boolean = false,
    val showNoInternet: Boolean = true,
    val showUnprotected: Boolean = false,
    val showFrozen: Boolean = false,
    val sort: String = "trackers_week",
)

data class TroubleshootingState(
    val showBatteryAction: Boolean = false,
    val showDataSaverAction: Boolean = false,
    val showLocalNetworkSection: Boolean = false,
    val localNetworkGranted: Boolean = false,
)

data class MainScreenModel(
    val selectedTab: Int = MainTabs.TIMELINE,
    val isProtectionEnabled: Boolean = false,
    val blockingModeLabel: String = "",
    val isNetworkActive: Boolean = true,
    val isQueueActive: Boolean = false,
    val isMetered: Boolean = false,
    val searchQuery: String = "",
    val isSearchOpen: Boolean = false,
    val isRefreshing: Boolean = false,
    val showPushHint: Boolean = false,
    val showSystemHint: Boolean = false,
    val banners: MainBannerState = MainBannerState(),
    val filter: MainFilterState = MainFilterState(),
    val apps: List<AppRuleUi> = emptyList(),
    val showInviteMenu: Boolean = true,
    val showSupportMenu: Boolean = true,
    val enableMoreAppsMenu: Boolean = true,
    val showRateButton: Boolean = true,
    val versionName: String = "",
    val versionCode: Int = 0,
    val copyrightText: String = "",
    val activeDialog: MainDialog = MainDialog.NONE,
    val troubleshooting: TroubleshootingState = TroubleshootingState(),
)

interface MainScreenCallbacks {
    fun onSelectTab(tab: Int)
    fun onToggleProtection(enabled: Boolean)
    fun onSearchOpenChanged(open: Boolean)
    fun onSearchQueryChanged(query: String)
    fun onRefreshApps()
    fun onToggleAppInternet(app: AppRuleUi)
    fun onOpenAppDetails(app: AppRuleUi)
    fun onDismissPushHint()
    fun onDismissSystemHint()
    fun onOpenSettings()
    fun onRequestNotificationsPermission()
    fun onRequestLocalNetworkPermission()
    fun onOpenPrivateDnsBlockedHelp()
    fun onOpenPrivateDnsBypassHelp()
    fun onSetFilterPref(key: String, value: Boolean)
    fun onSetSortPref(sort: String)
    fun onOpenLog()
    fun onInvite()
    fun onOpenSupport()
    fun onExportCsv()
    fun onOpenMoreApps()
    fun onOpenDialog(dialog: MainDialog)
    fun onDismissDialog()
    fun onOpenBatteryOptimization()
    fun onOpenDataSaver()
    fun onOpenVpnSettings()
    fun onRateApp()
    fun onOpenUrl(url: String)
}

@Stable
class MainScreenController {
    var model by mutableStateOf(MainScreenModel())
        private set

    fun update(newModel: MainScreenModel) {
        model = newModel
    }
}

object MainScreen {
    @JvmStatic
    fun installWithPager(
        composeView: ComposeView,
        callbacks: MainScreenCallbacks,
        fragmentPager: ViewPager2,
    ): MainScreenController = install(
        composeView = composeView,
        callbacks = callbacks,
        fragmentPagerContent = { selectedTab ->
            AndroidView(
                factory = { fragmentPager },
                update = { pager ->
                    val targetPage = if (selectedTab == MainTabs.VPN) 1 else 0
                    if (pager.currentItem != targetPage) {
                        pager.setCurrentItem(targetPage, false)
                    }
                    val targetVisibility = if (selectedTab == MainTabs.APPS) View.GONE else View.VISIBLE
                    if (pager.visibility != targetVisibility) {
                        pager.visibility = targetVisibility
                    }
                },
                modifier = if (selectedTab == MainTabs.APPS) {
                    Modifier.size(0.dp)
                } else {
                    Modifier.fillMaxSize()
                },
            )
        },
    )

    @JvmStatic
    fun install(
        composeView: ComposeView,
        callbacks: MainScreenCallbacks,
        fragmentPagerContent: @Composable (Int) -> Unit,
    ): MainScreenController {
        val controller = MainScreenController()
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            TrackerControlTheme {
                MainScreenContent(
                    model = controller.model,
                    callbacks = callbacks,
                    fragmentPagerContent = fragmentPagerContent,
                )
            }
        }
        return controller
    }

    @JvmStatic
    fun clearIconCache() {
        appIconCache.evictAll()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScreenContent(
    model: MainScreenModel,
    callbacks: MainScreenCallbacks,
    modifier: Modifier = Modifier,
    fragmentPagerContent: @Composable (Int) -> Unit = {},
) {
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    val isSearchMode = model.isSearchOpen && model.selectedTab == MainTabs.APPS

    val navItems = listOf(
        Triple(MainTabs.TIMELINE, R.string.tab_timeline, R.drawable.ic_history),
        Triple(MainTabs.APPS, R.string.tab_apps, R.drawable.ic_apps),
        Triple(MainTabs.VPN, R.string.tab_vpn, R.drawable.ic_shield_check),
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Row(modifier = Modifier.fillMaxSize()) {
            if (useRail) {
                WideNavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    navItems.forEach { (tab, labelRes, iconRes) ->
                        WideNavigationRailItem(
                            railExpanded = false,
                            selected = model.selectedTab == tab,
                            onClick = { callbacks.onSelectTab(tab) },
                            icon = {
                                Icon(
                                    painter = painterResource(iconRes),
                                    contentDescription = stringResource(labelRes),
                                )
                            },
                            label = { Text(stringResource(labelRes)) },
                        )
                    }
                }
            }

            Scaffold(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        navigationIcon = {
                            if (isSearchMode) {
                                IconButton(
                                    onClick = {
                                        callbacks.onSearchQueryChanged("")
                                        callbacks.onSearchOpenChanged(false)
                                    },
                                    modifier = Modifier.testTag("search_back_button"),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_arrow_back),
                                        contentDescription = stringResource(
                                            androidx.appcompat.R.string.abc_action_bar_up_description
                                        ),
                                    )
                                }
                            } else {
                                val switchCd = stringResource(R.string.switch_tracker_control_description)
                                val iconRes = if (model.isQueueActive) {
                                    R.drawable.ic_history
                                } else {
                                    R.drawable.ic_rocket2
                                }
                                Switch(
                                    checked = model.isProtectionEnabled,
                                    onCheckedChange = callbacks::onToggleProtection,
                                    modifier = Modifier
                                        .padding(start = 12.dp, end = 8.dp)
                                        .alpha(if (model.isNetworkActive) 1f else 0.6f)
                                        .testTag("protection_switch")
                                        .semantics { contentDescription = switchCd },
                                    thumbContent = {
                                        Icon(
                                            painter = painterResource(iconRes),
                                            contentDescription = null,
                                            modifier = Modifier.size(SwitchDefaults.IconSize),
                                        )
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedIconColor = MaterialTheme.colorScheme.primary,
                                    ),
                                )
                            }
                        },
                        title = {
                            if (isSearchMode) {
                                SearchField(
                                    query = model.searchQuery,
                                    onQueryChange = callbacks::onSearchQueryChanged,
                                    onClose = {
                                        callbacks.onSearchQueryChanged("")
                                        callbacks.onSearchOpenChanged(false)
                                    },
                                )
                            } else {
                                Column {
                                    Text(
                                        text = stringResource(R.string.your_privacy),
                                        style = MaterialTheme.typography.titleLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (model.isMetered) {
                                        Text(
                                            text = stringResource(R.string.title_metered),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                }
                            }
                        },
                        actions = {
                            if (!isSearchMode) {
                                IconButton(
                                    onClick = {
                                        if (model.selectedTab != MainTabs.APPS) {
                                            callbacks.onSelectTab(MainTabs.APPS)
                                        }
                                        callbacks.onSearchOpenChanged(true)
                                    },
                                    modifier = Modifier.testTag("search_button"),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_search_white_24dp),
                                        contentDescription = stringResource(R.string.menu_search),
                                    )
                                }

                                Box {
                                    IconButton(
                                        onClick = { showOverflowMenu = true },
                                        modifier = Modifier.testTag("overflow_button"),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_more_vert),
                                            contentDescription = stringResource(
                                                androidx.appcompat.R.string.abc_action_menu_overflow_description
                                            ),
                                        )
                                    }
                                    MainOverflowMenu(
                                        expanded = showOverflowMenu,
                                        model = model,
                                        onDismiss = { showOverflowMenu = false },
                                        onOpenSort = {
                                            showOverflowMenu = false
                                            showSortMenu = true
                                        },
                                        callbacks = callbacks,
                                    )
                                    SortDropdownMenu(
                                        expanded = showSortMenu,
                                        currentSort = model.filter.sort,
                                        onDismiss = { showSortMenu = false },
                                        onSelectSort = { sort ->
                                            callbacks.onSetSortPref(sort)
                                            showSortMenu = false
                                        },
                                    )
                                }
                            }
                        },
                    )
                },
                bottomBar = {
                    if (!useRail) {
                        ShortNavigationBar {
                            navItems.forEach { (tab, labelRes, iconRes) ->
                                ShortNavigationBarItem(
                                    selected = model.selectedTab == tab,
                                    onClick = { callbacks.onSelectTab(tab) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(iconRes),
                                            contentDescription = stringResource(labelRes),
                                        )
                                    },
                                    label = { Text(stringResource(labelRes)) },
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    UnifiedWarningBanners(
                        banners = model.banners,
                        callbacks = callbacks,
                    )

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        fragmentPagerContent(model.selectedTab)
                        if (model.selectedTab == MainTabs.APPS) {
                            AppsTabContent(
                                model = model,
                                callbacks = callbacks,
                            )
                        }
                    }
                }
            }
        }
    }

    when (model.activeDialog) {
        MainDialog.LEGEND -> LegendDialog(onDismiss = callbacks::onDismissDialog)
        MainDialog.ABOUT -> AboutDialog(model = model, callbacks = callbacks)
        MainDialog.TROUBLESHOOTING -> TroubleshootingDialog(
            state = model.troubleshooting,
            callbacks = callbacks,
        )
        MainDialog.NONE -> Unit
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.menu_search)) },
        trailingIcon = {
            IconButton(
                onClick = {
                    onQueryChange("")
                    onClose()
                },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close_white_24dp),
                    contentDescription = stringResource(android.R.string.cancel),
                )
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .testTag("main_search_field"),
    )
}

@Composable
private fun UnifiedWarningBanners(
    banners: MainBannerState,
    callbacks: MainScreenCallbacks,
) {
    val hasAny = banners.showResearchMode ||
        banners.showNotifications ||
        banners.showLocalNetwork ||
        banners.showPrivateDnsBlocked ||
        !banners.privateDnsBypassMessage.isNullOrBlank()

    if (!hasAny) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (banners.showResearchMode) {
            ExpressiveWarningBanner(
                text = stringResource(R.string.msg_research_mode_notice),
                icon = painterResource(R.drawable.ic_warning_amber),
                isError = false,
                onClick = callbacks::onOpenSettings,
                modifier = Modifier.testTag("research_mode_notice"),
            )
        }
        if (banners.showNotifications) {
            ExpressiveWarningBanner(
                text = stringResource(R.string.msg_notifications),
                icon = painterResource(R.drawable.twotone_notifications_24),
                isError = true,
                onClick = callbacks::onRequestNotificationsPermission,
            )
        }
        if (banners.showLocalNetwork) {
            ExpressiveWarningBanner(
                text = stringResource(R.string.msg_local_network),
                icon = painterResource(R.drawable.ic_warning_amber),
                isError = true,
                onClick = callbacks::onRequestLocalNetworkPermission,
            )
        }
        if (banners.showPrivateDnsBlocked) {
            ExpressiveWarningBanner(
                text = stringResource(R.string.msg_private_dns_row),
                icon = painterResource(R.drawable.ic_warning_amber),
                isError = true,
                onClick = callbacks::onOpenPrivateDnsBlockedHelp,
            )
        }
        if (!banners.privateDnsBypassMessage.isNullOrBlank()) {
            ExpressiveWarningBanner(
                text = banners.privateDnsBypassMessage,
                icon = painterResource(R.drawable.ic_warning_amber),
                isError = true,
                onClick = callbacks::onOpenPrivateDnsBypassHelp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppsTabContent(
    model: MainScreenModel,
    callbacks: MainScreenCallbacks,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        PullToRefreshBox(
            isRefreshing = model.isRefreshing,
            onRefresh = callbacks::onRefreshApps,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "protection_hero") {
                    ProtectionHeroCard(
                        enabled = model.isProtectionEnabled,
                        blockingModeLabel = model.blockingModeLabel,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                }

                if (model.showPushHint) {
                    item(key = "hint_push") {
                        HintCard(
                            text = stringResource(R.string.msg_push),
                            onDismiss = callbacks::onDismissPushHint,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                if (model.showSystemHint) {
                    item(key = "hint_system") {
                        HintCard(
                            text = stringResource(R.string.msg_system),
                            onDismiss = callbacks::onDismissSystemHint,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                item(key = "quick_filters") {
                    QuickFilterChipsRow(
                        filter = model.filter,
                        onSetPref = callbacks::onSetFilterPref,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                itemsIndexed(
                    items = model.apps,
                    key = { _, app -> "${app.uid}_${app.packageName}" },
                ) { index, app ->
                    AppRuleRow(
                        app = app,
                        index = index,
                        count = model.apps.size,
                        onToggleInternet = { callbacks.onToggleAppInternet(app) },
                        onOpenDetails = { callbacks.onOpenAppDetails(app) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtectionHeroCard(
    enabled: Boolean,
    blockingModeLabel: String,
) {
    val containerColor = if (enabled) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(
                    if (enabled) R.drawable.ic_shield_check else R.drawable.ic_shield_off
                ),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (enabled) R.string.app_name else R.string.msg_disabled
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (blockingModeLabel.isNotEmpty()) {
                    Text(
                        text = blockingModeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HintCard(
    text: String,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.ok))
            }
        }
    }
}

@Composable
private fun QuickFilterChipsRow(
    filter: MainFilterState,
    onSetPref: (String, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (filter.manageSystem) {
            FilterChip(
                selected = filter.showUser,
                onClick = { onSetPref("show_user", !filter.showUser) },
                label = { Text(stringResource(R.string.menu_app_user)) },
            )
            FilterChip(
                selected = filter.showSystem,
                onClick = { onSetPref("show_system", !filter.showSystem) },
                label = { Text(stringResource(R.string.menu_app_system)) },
            )
        }
        FilterChip(
            selected = filter.showNoInternet,
            onClick = { onSetPref("show_nointernet", !filter.showNoInternet) },
            label = { Text(stringResource(R.string.menu_app_nointernet)) },
        )
        FilterChip(
            selected = filter.showUnprotected,
            onClick = { onSetPref("show_unprotected", !filter.showUnprotected) },
            label = { Text(stringResource(R.string.menu_app_unprotected)) },
        )
        FilterChip(
            selected = filter.showFrozen,
            onClick = { onSetPref("show_frozen", !filter.showFrozen) },
            label = { Text(stringResource(R.string.menu_app_frozen)) },
        )
    }
}

@Composable
private fun AppRuleRow(
    app: AppRuleUi,
    index: Int,
    count: Int,
    onToggleInternet: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val containerColor = if (app.isChanged) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    ExpressiveGroupedSurface(
        index = index,
        count = count,
        onClick = onOpenDetails,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconImage(
                packageName = app.packageName,
                greyscale = app.hasInternet && app.isActiveProtection && app.isInternetBlocked,
                contentDescription = app.toggleContentDescription,
                onClick = onToggleInternet,
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (app.isSystem) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (app.detailsText.isNotEmpty()) {
                    Text(
                        text = app.detailsText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (app.isChanged) {
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }

            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val greyscaleColorFilter = ColorFilter.colorMatrix(
    ColorMatrix().apply { setToSaturation(0f) }
)

private val appIconCache = LruCache<String, ImageBitmap>(128)

@Composable
private fun AppIconImage(
    packageName: String,
    greyscale: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { 40.dp.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(
        initialValue = appIconCache.get(packageName),
        key1 = packageName,
        key2 = sizePx,
    ) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            appIconCache.get(packageName) ?: runCatching {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                drawableToBitmap(drawable, sizePx).asImageBitmap().also {
                    appIconCache.put(packageName, it)
                }
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .alpha(if (greyscale) 0.5f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                colorFilter = if (greyscale) greyscaleColorFilter else null,
                modifier = Modifier.size(40.dp),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_apps),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        val src = drawable.bitmap
        if (src.width <= sizePx && src.height <= sizePx) {
            return src
        }
        return Bitmap.createScaledBitmap(src, sizePx, sizePx, true)
    }
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(canvas)
    return bitmap
}

@Composable
private fun SortDropdownMenu(
    expanded: Boolean,
    currentSort: String,
    onDismiss: () -> Unit,
    onSelectSort: (String) -> Unit,
) {
    val options = listOf(
        "name" to R.string.menu_sort_name,
        "trackers_week" to R.string.menu_sort_companies_week,
        "trackers_all" to R.string.menu_sort_companies,
        "trackers_recent" to R.string.menu_sort_by_latest_detected_tracker,
    )
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        options.forEach { (key, labelRes) ->
            DropdownMenuItem(
                text = { Text(stringResource(labelRes)) },
                trailingIcon = {
                    RadioButton(selected = currentSort == key, onClick = null)
                },
                onClick = { onSelectSort(key) },
            )
        }
    }
}

@Composable
private fun MainOverflowMenu(
    expanded: Boolean,
    model: MainScreenModel,
    onDismiss: () -> Unit,
    onOpenSort: () -> Unit,
    callbacks: MainScreenCallbacks,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (model.selectedTab == MainTabs.APPS) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_sort)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_sort_white_24dp),
                        contentDescription = null,
                    )
                },
                onClick = onOpenSort,
            )
            HorizontalDivider()
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_log)) },
            onClick = {
                onDismiss()
                callbacks.onOpenLog()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_troubleshooting)) },
            onClick = {
                onDismiss()
                callbacks.onOpenDialog(MainDialog.TROUBLESHOOTING)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_settings)) },
            onClick = {
                onDismiss()
                callbacks.onOpenSettings()
            },
        )
    }
}

@Composable
private fun LegendDialog(onDismiss: () -> Unit) {
    val entries = listOf(
        R.drawable.ic_rocket2 to R.string.msg_inactive,
        R.drawable.ic_history to R.string.msg_queue,
        R.drawable.wifi_on to R.string.title_unmetered_allowed,
        R.drawable.wifi_off to R.string.title_unmetered_blocked,
        R.drawable.wifi_off_disabled to R.string.title_unmetered_disabled,
        R.drawable.other_on to R.string.title_metered_allowed,
        R.drawable.other_off to R.string.title_metered_blocked,
        R.drawable.other_off_disabled to R.string.title_metered_disabled,
        R.drawable.screen_on to R.string.title_interactive_allowed,
        R.drawable.host_allowed to R.string.title_host_allowed,
        R.drawable.host_blocked to R.string.title_host_blocked,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_legend)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                entries.forEach { (iconRes, labelRes) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = stringResource(labelRes),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
private fun AboutDialog(
    model: MainScreenModel,
    callbacks: MainScreenCallbacks,
) {
    AlertDialog(
        onDismissRequest = callbacks::onDismissDialog,
        title = { Text(stringResource(R.string.app_name)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "${model.versionName} (${model.versionCode})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (model.copyrightText.isNotEmpty()) {
                    Text(
                        text = model.copyrightText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (model.showRateButton) {
                    FilledTonalButton(
                        onClick = callbacks::onRateApp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.title_rate))
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                TextButton(
                    onClick = { callbacks.onOpenUrl("https://www.gnu.org/licenses/gpl-3.0.html") },
                ) {
                    Text(stringResource(R.string.app_eula))
                }
                TextButton(
                    onClick = { callbacks.onOpenUrl("https://trackercontrol.org/privacy") },
                ) {
                    Text(stringResource(R.string.app_privacy))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = callbacks::onDismissDialog) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
fun TroubleshootingDialog(
    state: TroubleshootingState,
    callbacks: MainScreenCallbacks,
) {
    AlertDialog(
        onDismissRequest = callbacks::onDismissDialog,
        title = { Text(stringResource(R.string.troubleshooting_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TroubleshootingSection(
                    title = stringResource(R.string.troubleshooting_battery_title),
                    description = stringResource(R.string.troubleshooting_battery_desc),
                    actionLabel = if (state.showBatteryAction) {
                        stringResource(R.string.troubleshooting_battery_action)
                    } else null,
                    okLabel = stringResource(R.string.troubleshooting_battery_ok),
                    onAction = callbacks::onOpenBatteryOptimization,
                )
                TroubleshootingSection(
                    title = stringResource(R.string.troubleshooting_datasaver_title),
                    description = stringResource(R.string.troubleshooting_datasaver_desc),
                    actionLabel = if (state.showDataSaverAction) {
                        stringResource(R.string.troubleshooting_datasaver_action)
                    } else null,
                    okLabel = stringResource(R.string.troubleshooting_datasaver_ok),
                    onAction = callbacks::onOpenDataSaver,
                )
                if (state.showLocalNetworkSection) {
                    TroubleshootingLocalNetworkSection(
                        granted = state.localNetworkGranted,
                        onGrant = callbacks::onRequestLocalNetworkPermission,
                    )
                }
                TroubleshootingSection(
                    title = stringResource(R.string.troubleshooting_alwayson_title),
                    description = stringResource(R.string.troubleshooting_alwayson_desc),
                    actionLabel = stringResource(R.string.troubleshooting_alwayson_action),
                    okLabel = null,
                    onAction = callbacks::onOpenVpnSettings,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = callbacks::onDismissDialog) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
fun TroubleshootingLocalNetworkSection(
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("troubleshooting_local_network_section"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.troubleshooting_local_network_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.troubleshooting_local_network_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!granted) {
            Button(
                onClick = onGrant,
                modifier = Modifier.testTag("troubleshooting_local_network_action"),
            ) {
                Text(stringResource(R.string.troubleshooting_local_network_action))
            }
        } else {
            Text(
                text = stringResource(R.string.troubleshooting_local_network_ok),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("troubleshooting_local_network_ok"),
            )
        }
    }
}

@Composable
private fun TroubleshootingSection(
    title: String,
    description: String,
    actionLabel: String?,
    okLabel: String?,
    onAction: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null) {
            FilledTonalButton(onClick = onAction) {
                Text(actionLabel)
            }
        } else if (okLabel != null) {
            Text(
                text = okLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
