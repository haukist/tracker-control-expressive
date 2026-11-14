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

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.viewpager2.widget.ViewPager2
import net.kollnig.missioncontrol.R

interface DetailsScreenCallbacks {
    fun onNavigateUp()
    fun onLaunchApp()
    fun onExportCsv()
    fun onUninstallApp()
    fun onClearActivity()
}

@Stable
class DetailsScreenController(
    initialTab: Int = 0,
) {
    var selectedTab by mutableIntStateOf(initialTab)
        private set

    fun selectTab(index: Int) {
        selectedTab = index
    }
}

object DetailsScreen {
    @JvmStatic
    fun installWithPager(
        composeView: ComposeView,
        appName: String,
        tabTitles: List<String>,
        viewPager: ViewPager2,
        callbacks: DetailsScreenCallbacks,
    ): DetailsScreenController {
        val controller = DetailsScreenController(viewPager.currentItem)
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                controller.selectTab(position)
            }
        })
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            TrackerControlTheme {
                DetailsScreenContent(
                    appName = appName,
                    tabTitles = tabTitles,
                    selectedTab = controller.selectedTab,
                    onSelectTab = { index ->
                        controller.selectTab(index)
                        viewPager.setCurrentItem(index, true)
                    },
                    pagerView = viewPager,
                    callbacks = callbacks,
                )
            }
        }
        return controller
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreenContent(
    appName: String,
    tabTitles: List<String>,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    pagerView: View,
    callbacks: DetailsScreenCallbacks,
) {
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            ExpressiveTopAppBar(
                title = stringResource(R.string.app_info),
                subtitle = appName,
                onNavigateUp = callbacks::onNavigateUp,
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                painter = painterResource(android.R.drawable.ic_menu_more),
                                contentDescription = null,
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.launch_app)) },
                                onClick = {
                                    showMenu = false
                                    callbacks.onLaunchApp()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_csv)) },
                                onClick = {
                                    showMenu = false
                                    callbacks.onExportCsv()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_uninstall)) },
                                onClick = {
                                    showMenu = false
                                    callbacks.onUninstallApp()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_clear_activity)) },
                                onClick = {
                                    showMenu = false
                                    callbacks.onClearActivity()
                                },
                            )
                        }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (tabTitles.isNotEmpty()) {
                PrimaryTabRow(
                    selectedTabIndex = selectedTab.coerceIn(0, tabTitles.lastIndex),
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { onSelectTab(index) },
                            text = {
                                Text(
                                    text = title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }

            AndroidView(
                factory = { pagerView },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }
}
