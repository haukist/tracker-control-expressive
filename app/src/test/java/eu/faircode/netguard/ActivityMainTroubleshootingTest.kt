package eu.faircode.netguard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import net.kollnig.missioncontrol.ui.compose.TrackerControlTheme
import net.kollnig.missioncontrol.ui.compose.TroubleshootingLocalNetworkSection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityMainTroubleshootingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun localNetworkSectionUpdatesWhenPermissionIsGranted() {
        var granted by mutableStateOf(false)

        composeRule.setContent {
            TrackerControlTheme(useDynamicColor = false) {
                TroubleshootingLocalNetworkSection(
                    granted = granted,
                    onGrant = {},
                )
            }
        }

        composeRule.onNodeWithTag("troubleshooting_local_network_section").assertIsDisplayed()
        composeRule.onNodeWithTag("troubleshooting_local_network_action").assertIsDisplayed()
        composeRule.onNodeWithTag("troubleshooting_local_network_ok").assertDoesNotExist()

        granted = true
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("troubleshooting_local_network_action").assertDoesNotExist()
        composeRule.onNodeWithTag("troubleshooting_local_network_ok").assertIsDisplayed()
    }
}
