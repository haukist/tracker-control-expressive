package net.kollnig.missioncontrol.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import net.kollnig.missioncontrol.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun searchModeHidesOtherTopBarActionsAndFocusesSearchField() {
        composeRule.setContent {
            TrackerControlTheme(useDynamicColor = false) {
                MainScreenContent(
                    model = MainScreenModel(
                        selectedTab = MainTabs.APPS,
                        isSearchOpen = true,
                        searchQuery = "example",
                    ),
                    callbacks = NoOpMainScreenCallbacks(),
                )
            }
        }

        composeRule.onNodeWithTag("main_search_field").assertIsDisplayed().assertIsFocused()
        composeRule.onNodeWithTag("search_back_button").assertIsDisplayed()
        composeRule.onNodeWithTag("protection_switch").assertDoesNotExist()
        composeRule.onNodeWithTag("overflow_button").assertDoesNotExist()
    }

    @Test
    fun overflowMenuShowsSortOnAppsTabAndTriggersSelection() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var selectedSort = ""
        composeRule.setContent {
            TrackerControlTheme(useDynamicColor = false) {
                MainScreenContent(
                    model = MainScreenModel(
                        selectedTab = MainTabs.APPS,
                        isSearchOpen = false,
                    ),
                    callbacks = object : NoOpMainScreenCallbacks() {
                        override fun onSetSortPref(sort: String) {
                            selectedSort = sort
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithTag("protection_switch").assertIsDisplayed()
        composeRule.onNodeWithTag("overflow_button").performClick()
        composeRule.onNodeWithText(context.getString(R.string.menu_log)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.menu_sort)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.menu_sort_name)).performClick()

        assertEquals("name", selectedSort)
        assertTrue(selectedSort.isNotEmpty())
    }
}

private open class NoOpMainScreenCallbacks : MainScreenCallbacks {
    override fun onSelectTab(tab: Int) = Unit
    override fun onToggleProtection(enabled: Boolean) = Unit
    override fun onSearchOpenChanged(open: Boolean) = Unit
    override fun onSearchQueryChanged(query: String) = Unit
    override fun onRefreshApps() = Unit
    override fun onToggleAppInternet(app: AppRuleUi) = Unit
    override fun onOpenAppDetails(app: AppRuleUi) = Unit
    override fun onDismissPushHint() = Unit
    override fun onDismissSystemHint() = Unit
    override fun onOpenSettings() = Unit
    override fun onRequestNotificationsPermission() = Unit
    override fun onRequestLocalNetworkPermission() = Unit
    override fun onOpenPrivateDnsBlockedHelp() = Unit
    override fun onOpenPrivateDnsBypassHelp() = Unit
    override fun onSetFilterPref(key: String, value: Boolean) = Unit
    override fun onSetSortPref(sort: String) = Unit
    override fun onOpenLog() = Unit
    override fun onInvite() = Unit
    override fun onOpenSupport() = Unit
    override fun onExportCsv() = Unit
    override fun onOpenMoreApps() = Unit
    override fun onOpenDialog(dialog: MainDialog) = Unit
    override fun onDismissDialog() = Unit
    override fun onOpenBatteryOptimization() = Unit
    override fun onOpenDataSaver() = Unit
    override fun onOpenVpnSettings() = Unit
    override fun onRateApp() = Unit
    override fun onOpenUrl(url: String) = Unit
}
