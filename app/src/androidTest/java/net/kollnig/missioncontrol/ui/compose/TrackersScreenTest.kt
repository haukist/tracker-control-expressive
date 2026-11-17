/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.ui.compose

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.kollnig.missioncontrol.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackersScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Records every callback payload so tests can assert the exact arguments. */
    private class RecordingCallbacks : TrackersScreenCallbacks {
        var appStateClicked = false
        var librariesClicked = false
        var sectionToggle: Pair<String, Boolean>? = null
        var companyBlockChange: Pair<String, Boolean>? = null
        var showMore: String? = null

        override fun onAppStateClick() {
            appStateClicked = true
        }

        override fun onLibrariesClick() {
            librariesClicked = true
        }

        override fun onSectionToggle(categoryName: String, checked: Boolean) {
            sectionToggle = categoryName to checked
        }

        override fun onCompanyBlockChanged(blockingKey: String, blocked: Boolean) {
            companyBlockChange = blockingKey to blocked
        }

        override fun onShowMore(categoryName: String) {
            showMore = categoryName
        }

        override fun onRefresh() = Unit
    }

    private fun categoryDescription(displayName: String) =
        context.getString(R.string.toggle_block_category_description, displayName)

    private fun blockCompanyLabel(companyName: String) =
        context.getString(R.string.feed_block_company_in_app, companyName, "Example App")

    private fun model(rows: List<TrackersRow>) = TrackersScreenModel(
        browserWarning = false,
        appStateTitle = "Protection",
        appStateValue = "Protected",
        appStateHint = "",
        appStateHintAccent = true,
        librarySummary = "Not analysed",
        rows = rows
    )

    private fun section(
        categoryName: String,
        displayName: String = categoryName,
        checked: Boolean,
        enabled: Boolean
    ) = TrackersRow.Section(
        key = "section:$categoryName",
        categoryName = categoryName,
        title = displayName,
        lastContact = "Last contact 3 minutes ago",
        explainer = null,
        switchChecked = checked,
        switchEnabled = enabled,
        switchDescription = categoryDescription(displayName)
    )

    private fun company(
        categoryName: String,
        blockingKey: String,
        name: String,
        statusBlocked: Boolean,
        hosts: List<String> = emptyList(),
        showAllowSwitch: Boolean = true,
        blockChecked: Boolean = statusBlocked,
        blockEnabled: Boolean = true,
        statusNote: String? = null
    ) = TrackersRow.Company(
        key = "company:$categoryName:$blockingKey",
        categoryName = categoryName,
        blockingKey = blockingKey,
        name = name,
        lastSeen = "3 minutes ago",
        status = if (statusBlocked) "Blocked" else "Allowed by you",
        statusBlocked = statusBlocked,
        hosts = hosts,
        uncertain = false,
        showAllowSwitch = showAllowSwitch,
        blockSwitchLabel = blockCompanyLabel(name),
        blockSwitchChecked = blockChecked,
        blockSwitchEnabled = blockEnabled,
        statusNote = statusNote
    )

    private fun setContent(rows: List<TrackersRow>, callbacks: TrackersScreenCallbacks) {
        composeRule.setContent {
            TrackerControlTheme {
                TrackersScreenContent(model = model(rows), callbacks = callbacks)
            }
        }
    }

    /** Brings a lazily composed row into view before interacting with it. */
    private fun scrollTo(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        composeRule.onNodeWithTag("trackers-list").performScrollToNode(matcher)
    }

    @Test
    fun usesSharedHeadingAndForwardsLibraryNavigation() {
        val callbacks = RecordingCallbacks()
        setContent(emptyList(), callbacks)

        composeRule.onNodeWithText(context.getString(R.string.block_tracking)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.libraries_row_heading)).performClick()
        composeRule.runOnIdle { assertTrue(callbacks.librariesClicked) }
    }

    @Test
    fun emptyRowListRendersHeaderWithoutAnySwitches() {
        val callbacks = RecordingCallbacks()
        setContent(emptyList(), callbacks)

        composeRule.onNodeWithText("Protection").assertExists()
        composeRule.onNodeWithText("Not analysed").assertExists()
        composeRule.onAllNodes(isToggleable()).assertCountEquals(0)
        composeRule.runOnIdle {
            assertNull(callbacks.sectionToggle)
            assertNull(callbacks.companyBlockChange)
        }
    }

    @Test
    fun appStateRowForwardsNavigationToProtection() {
        val callbacks = RecordingCallbacks()
        setContent(emptyList(), callbacks)

        composeRule.onNodeWithText("Protection").performClick()
        composeRule.runOnIdle { assertTrue(callbacks.appStateClicked) }
    }

    @Test
    fun sectionSwitchForwardsCategoryNameAndUncheckedTargetState() {
        val callbacks = RecordingCallbacks()
        setContent(
            listOf(
                section("Analytics", checked = true, enabled = true),
                section("Advertising", checked = false, enabled = true)
            ),
            callbacks
        )

        val analytics = hasContentDescription(categoryDescription("Analytics"))
        scrollTo(analytics)
        composeRule.onNode(analytics).assertIsOn().performClick()
        composeRule.runOnIdle { assertEquals("Analytics" to false, callbacks.sectionToggle) }
    }

    @Test
    fun sectionSwitchForwardsCategoryNameAndCheckedTargetState() {
        val callbacks = RecordingCallbacks()
        setContent(
            listOf(
                section("Analytics", checked = true, enabled = true),
                section("Advertising", checked = false, enabled = true)
            ),
            callbacks
        )

        val advertising = hasContentDescription(categoryDescription("Advertising"))
        scrollTo(advertising)
        composeRule.onNode(advertising).assertIsOff().performClick()
        composeRule.runOnIdle { assertEquals("Advertising" to true, callbacks.sectionToggle) }
    }

    @Test
    fun disabledSectionSwitchDoesNotForwardToggle() {
        val callbacks = RecordingCallbacks()
        setContent(
            listOf(section("Advertising", checked = false, enabled = false)),
            callbacks
        )

        val advertising = hasContentDescription(categoryDescription("Advertising"))
        scrollTo(advertising)
        composeRule.onNode(advertising).assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertNull(callbacks.sectionToggle) }
    }

    @Test
    fun tappingBlockedCompanyOpensFloatingDetailAndTogglingBlockOffAllowsIt() {
        val callbacks = RecordingCallbacks()
        setContent(
            listOf(
                section("Analytics", checked = true, enabled = true),
                company(
                    categoryName = "Analytics",
                    blockingKey = "example-analytics",
                    name = "Example Analytics",
                    statusBlocked = true,
                    hosts = listOf("tracker.example")
                )
            ),
            callbacks
        )

        scrollTo(hasText("Example Analytics"))
        composeRule.onNodeWithText("Example Analytics").performClick()

        composeRule.onNodeWithTag("company-detail-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("tracker.example").assertIsDisplayed()
        val blockSwitch = hasContentDescription(blockCompanyLabel("Example Analytics"))
        composeRule.onNode(blockSwitch).assertIsOn().performClick()
        composeRule.runOnIdle {
            assertEquals("example-analytics" to false, callbacks.companyBlockChange)
        }
    }

    @Test
    fun tappingAllowedCompanyOpensFloatingDetailAndTogglingBlockOnBlocksIt() {
        val callbacks = RecordingCallbacks()
        setContent(
            listOf(
                section("Analytics", checked = true, enabled = true),
                company(
                    categoryName = "Analytics",
                    blockingKey = "example-ads",
                    name = "Example Ads",
                    statusBlocked = false,
                    hosts = listOf("ads.example")
                )
            ),
            callbacks
        )

        scrollTo(hasText("Example Ads"))
        composeRule.onNodeWithText("Example Ads").performClick()

        composeRule.onNodeWithTag("company-detail-dialog").assertIsDisplayed()
        val blockSwitch = hasContentDescription(blockCompanyLabel("Example Ads"))
        composeRule.onNode(blockSwitch).assertIsOff().performClick()
        composeRule.runOnIdle {
            assertEquals("example-ads" to true, callbacks.companyBlockChange)
        }
    }

    @Test
    fun unblockedCategoryCompanyDetailOffersCategoryBlockButtonInsteadOfDeadSwitch() {
        val callbacks = RecordingCallbacks()
        setContent(
            listOf(
                section("Essential", checked = false, enabled = true),
                company(
                    categoryName = "Essential",
                    blockingKey = "frozen-co",
                    name = "Frozen Co",
                    statusBlocked = false,
                    hosts = listOf("frozen.example"),
                    blockEnabled = false,
                    statusNote = context.getString(R.string.category_unblocked_warning)
                )
            ),
            callbacks
        )

        scrollTo(hasText("Frozen Co"))
        composeRule.onNodeWithText("Frozen Co").performClick()

        composeRule.onNodeWithTag("company-detail-dialog").assertIsDisplayed()
        composeRule.onAllNodes(hasContentDescription(blockCompanyLabel("Frozen Co")))
            .assertCountEquals(0)
        composeRule.onNodeWithText(context.getString(R.string.feed_block_category_action, "Essential"))
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertEquals("Essential" to true, callbacks.sectionToggle) }
    }

    @Test
    fun internetBlockedCategoryAndCompanySwitchesStayInert() {
        val callbacks = RecordingCallbacks()
        setContent(
            listOf(
                section("Analytics", checked = true, enabled = false),
                company(
                    categoryName = "Analytics",
                    blockingKey = "no-internet-co",
                    name = "No Internet Co",
                    statusBlocked = true,
                    hosts = listOf("offline.example"),
                    blockEnabled = false
                )
            ),
            callbacks
        )

        val sectionSwitch = hasContentDescription(categoryDescription("Analytics"))
        scrollTo(sectionSwitch)
        composeRule.onNode(sectionSwitch).assertIsNotEnabled().performClick()

        scrollTo(hasText("No Internet Co"))
        composeRule.onNodeWithText("No Internet Co").performClick()

        val blockSwitch = hasContentDescription(blockCompanyLabel("No Internet Co"))
        composeRule.onNode(blockSwitch).assertIsNotEnabled().performClick()
        composeRule.runOnIdle {
            assertNull(callbacks.sectionToggle)
            assertNull(callbacks.companyBlockChange)
        }
    }

    @Test
    fun filterChipsFilterDisplayedCompanies() {
        val callbacks = RecordingCallbacks()
        setContent(
            listOf(
                section("Analytics", checked = true, enabled = true),
                company(
                    categoryName = "Analytics",
                    blockingKey = "example-analytics",
                    name = "Example Analytics",
                    statusBlocked = true,
                    hosts = listOf("tracker.example")
                ),
                company(
                    categoryName = "Analytics",
                    blockingKey = "example-ads",
                    name = "Example Ads",
                    statusBlocked = false,
                    hosts = listOf("ads.example")
                )
            ),
            callbacks
        )

        scrollTo(hasText(context.getString(R.string.feed_filter_blocked, 1)))
        composeRule.onNodeWithText(context.getString(R.string.feed_filter_blocked, 1)).performClick()

        composeRule.onNodeWithText("Example Analytics").assertExists()
        composeRule.onNodeWithText("Example Ads").assertDoesNotExist()
    }

    @Test
    fun showMoreRowForwardsItsCategoryName() {
        val callbacks = RecordingCallbacks()
        setContent(
            listOf(
                section("Analytics", checked = true, enabled = true),
                company(
                    categoryName = "Analytics",
                    blockingKey = "example-analytics",
                    name = "Example Analytics",
                    statusBlocked = true
                ),
                TrackersRow.ShowMore(
                    key = "more:Analytics",
                    text = "Show 3 more companies",
                    categoryName = "Analytics"
                )
            ),
            callbacks
        )

        scrollTo(hasText("Show 3 more companies"))
        composeRule.onNodeWithText("Show 3 more companies").performClick()
        composeRule.runOnIdle { assertEquals("Analytics", callbacks.showMore) }
    }
}
