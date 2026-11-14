package eu.faircode.netguard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import net.kollnig.missioncontrol.R
import net.kollnig.missioncontrol.ui.compose.AppRuleUi
import net.kollnig.missioncontrol.ui.compose.MainBannerState
import net.kollnig.missioncontrol.ui.compose.MainDialog
import net.kollnig.missioncontrol.ui.compose.MainScreenCallbacks
import net.kollnig.missioncontrol.ui.compose.MainScreenContent
import net.kollnig.missioncontrol.ui.compose.MainScreenModel
import net.kollnig.missioncontrol.ui.compose.TrackerControlTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityMainResearchNoticeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun researchModeNoticeReflectsBannerState() {
        val context = RuntimeEnvironment.getApplication()
        val expectedText = context.getString(R.string.msg_research_mode_notice)

        composeRule.setContent {
            TrackerControlTheme(useDynamicColor = false) {
                MainScreenContent(
                    model = MainScreenModel(
                        banners = MainBannerState(showResearchMode = true),
                    ),
                    callbacks = noopCallbacks(),
                    timelineContent = {},
                    vpnContent = {},
                )
            }
        }

        composeRule.onNodeWithTag("research_mode_notice").assertIsDisplayed()
        composeRule.onNodeWithText(expectedText).assertIsDisplayed()
    }

    @Test
    fun researchModeNoticeIsHiddenWhenDisabled() {
        composeRule.setContent {
            TrackerControlTheme(useDynamicColor = false) {
                MainScreenContent(
                    model = MainScreenModel(
                        banners = MainBannerState(showResearchMode = false),
                    ),
                    callbacks = noopCallbacks(),
                    timelineContent = {},
                    vpnContent = {},
                )
            }
        }

        composeRule.onNodeWithTag("research_mode_notice").assertDoesNotExist()
    }

    private fun noopCallbacks() = object : MainScreenCallbacks {
        override fun onSelectTab(tab: Int) {}
        override fun onToggleProtection(enabled: Boolean) {}
        override fun onSearchOpenChanged(open: Boolean) {}
        override fun onSearchQueryChanged(query: String) {}
        override fun onRefreshApps() {}
        override fun onToggleAppInternet(app: AppRuleUi) {}
        override fun onOpenAppDetails(app: AppRuleUi) {}
        override fun onDismissPushHint() {}
        override fun onDismissSystemHint() {}
        override fun onOpenSettings() {}
        override fun onRequestNotificationsPermission() {}
        override fun onRequestLocalNetworkPermission() {}
        override fun onOpenPrivateDnsHelp() {}
        override fun onSetFilterPref(key: String, value: Boolean) {}
        override fun onSetSortPref(sort: String) {}
        override fun onOpenLog() {}
        override fun onInvite() {}
        override fun onOpenSupport() {}
        override fun onExportCsv() {}
        override fun onOpenMoreApps() {}
        override fun onOpenDialog(dialog: MainDialog) {}
        override fun onDismissDialog() {}
        override fun onOpenBatteryOptimization() {}
        override fun onOpenDataSaver() {}
        override fun onOpenVpnSettings() {}
        override fun onRateApp() {}
        override fun onOpenUrl(url: String) {}
    }
}
