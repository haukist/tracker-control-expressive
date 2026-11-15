package net.kollnig.missioncontrol.ui.compose

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sectionTitleAlignsWithGroupedCardStartEdge() {
        val model = SettingsScreenModel(
            page = SettingsPage.ROOT,
            pageTitle = "Settings",
            sections = listOf(
                SettingsSection(
                    title = "Blocking",
                    items = listOf(
                        SettingsItem.SwitchItem(
                            key = "test_toggle",
                            title = "Example setting",
                            checked = true,
                        )
                    ),
                )
            ),
        )
        composeRule.setContent {
            TrackerControlTheme {
                SettingsScreenContent(model = model, callbacks = NoOpCallbacks)
            }
        }

        val labelBounds = composeRule.onNodeWithText("Blocking").getUnclippedBoundsInRoot()
        // pref_<key> is attached directly to ExpressiveGroupedSurface (the card itself).
        val cardBounds = composeRule.onNodeWithTag("pref_test_toggle").getUnclippedBoundsInRoot()
        assertDpClose(cardBounds.left, labelBounds.left)
        assertDpClose(16.dp, labelBounds.left)
    }

    private fun assertDpClose(expected: Dp, actual: Dp, tolerance: Dp = 0.5.dp) {
        assertTrue(
            "Expected $expected ± $tolerance, got $actual",
            abs((expected - actual).value) <= tolerance.value,
        )
    }

    private object NoOpCallbacks : SettingsScreenCallbacks {
        override fun onNavigateUp() = Unit
        override fun onOpenPage(page: SettingsPage) = Unit
        override fun onToggleSwitch(key: String, checked: Boolean) = Unit
        override fun onSelectListOption(key: String, value: String) = Unit
        override fun onSubmitTextInput(key: String, value: String) = Unit
        override fun onActionClick(key: String) = Unit
    }
}
