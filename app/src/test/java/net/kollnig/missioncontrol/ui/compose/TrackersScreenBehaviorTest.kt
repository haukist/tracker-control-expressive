/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.ui.compose

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import net.kollnig.missioncontrol.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackersScreenBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class RecordingCallbacks : TrackersScreenCallbacks {
        var sectionToggle: Pair<String, Boolean>? = null
        var companyBlockChange: Pair<String, Boolean>? = null
        var showMore: String? = null

        override fun onAppStateClick() = Unit
        override fun onLibrariesClick() = Unit
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

    private fun section(
        categoryName: String,
        checked: Boolean = true,
        enabled: Boolean = true
    ) = TrackersRow.Section(
        key = "section:$categoryName",
        categoryName = categoryName,
        title = categoryName,
        lastContact = "Last contact 3 minutes ago",
        explainer = null,
        switchChecked = checked,
        switchEnabled = enabled,
        switchDescription = context.getString(R.string.toggle_block_category_description, categoryName)
    )

    private fun company(
        categoryName: String = "Analytics",
        blockingKey: String,
        name: String,
        statusBlocked: Boolean,
        hosts: List<String> = listOf("tracker.example"),
        showAllowSwitch: Boolean = true,
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
        blockSwitchLabel = context.getString(R.string.feed_block_company_in_app, name, "Example App"),
        blockSwitchChecked = statusBlocked,
        blockSwitchEnabled = blockEnabled,
        statusNote = statusNote
    )

    private fun model(
        rows: List<TrackersRow>,
        totalCompanyCount: Int? = null,
        totalBlockedCount: Int? = null,
        totalAllowedCount: Int? = null
    ) = TrackersScreenModel(
        browserWarning = false,
        appStateTitle = "Protection",
        appStateValue = "Protected",
        appStateHint = "",
        appStateHintAccent = true,
        librarySummary = "Not analysed",
        rows = rows,
        totalCompanyCount = totalCompanyCount,
        totalBlockedCount = totalBlockedCount,
        totalAllowedCount = totalAllowedCount
    )

    @Test
    fun scrollToCompanyRowAndDismissDialogDoesNotReopenWhenRowReturns() {
        val sampleSection = section("Analytics")
        val sampleCompany = company(blockingKey = "ex", name = "Example Co", statusBlocked = true)
        var currentRows by mutableStateOf<List<TrackersRow>>(listOf(sampleSection, sampleCompany))

        composeRule.setContent {
            TrackerControlTheme(useDynamicColor = false) {
                TrackersScreenContent(model = model(currentRows), callbacks = RecordingCallbacks())
            }
        }

        composeRule.onNodeWithTag("trackers-list").performScrollToNode(hasText("Example Co"))
        composeRule.onNodeWithText("Example Co").performClick()
        composeRule.onNodeWithTag("company-detail-dialog").assertIsDisplayed()

        // Close button dismisses dialog cleanly.
        composeRule.onNodeWithContentDescription(context.getString(R.string.action_close)).performClick()
        composeRule.onNodeWithTag("company-detail-dialog").assertDoesNotExist()

        // Re-open, then remove the row so dialog closes and clears stale key.
        composeRule.onNodeWithText("Example Co").performClick()
        composeRule.onNodeWithTag("company-detail-dialog").assertIsDisplayed()

        currentRows = listOf(sampleSection)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("company-detail-dialog").assertDoesNotExist()

        // Returning the row must NOT unexpectedly reopen the dialog.
        currentRows = listOf(sampleSection, sampleCompany)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("company-detail-dialog").assertDoesNotExist()
    }

    @Test
    fun dialogRespectsJavaStatusNoteAndHidesSwitchForSharedIp() {
        val callbacks = RecordingCallbacks()
        val sharedNote = context.getString(R.string.allowed_shared_ip)
        composeRule.setContent {
            TrackerControlTheme(useDynamicColor = false) {
                TrackersScreenContent(
                    model = model(
                        listOf(
                            section("Analytics", checked = true, enabled = false),
                            company(
                                blockingKey = "offline",
                                name = "Offline Co",
                                statusBlocked = true,
                                blockEnabled = false,
                                statusNote = null
                            ),
                            company(
                                blockingKey = "shared",
                                name = "Shared IP Co",
                                statusBlocked = false,
                                showAllowSwitch = false,
                                blockEnabled = false,
                                statusNote = sharedNote
                            )
                        )
                    ),
                    callbacks = callbacks
                )
            }
        }

        // Disabled/NO_INTERNET row without statusNote must not synthesize feed_blocked_with_category.
        composeRule.onNodeWithTag("trackers-list").performScrollToNode(hasText("Offline Co"))
        composeRule.onNodeWithText("Offline Co").performClick()
        composeRule.onNodeWithTag("company-detail-dialog").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.feed_blocked_with_category, "Analytics"))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.action_close)).performClick()

        // Shared-IP row hides the company switch and displays the shared-IP note.
        composeRule.onNodeWithTag("trackers-list").performScrollToNode(hasText("Shared IP Co"))
        composeRule.onNodeWithText("Shared IP Co").performClick()
        composeRule.onNodeWithText(sharedNote).assertIsDisplayed()
        composeRule.onAllNodes(
            hasContentDescription(
                context.getString(R.string.feed_block_company_in_app, "Shared IP Co", "Example App")
            )
        ).assertCountEquals(0)
    }

    @Test
    fun allowedFilterEmptyStateAndZeroCompanyFallbackKeepSectionsVisible() {
        val callbacks = RecordingCallbacks()
        val blockedOnlySection = section("Analytics")
        val blockedCo = company(blockingKey = "blk", name = "Blocked Co", statusBlocked = true)
        var currentRows by mutableStateOf<List<TrackersRow>>(listOf(blockedOnlySection, blockedCo))

        composeRule.setContent {
            TrackerControlTheme(useDynamicColor = false) {
                TrackersScreenContent(model = model(currentRows), callbacks = callbacks)
            }
        }

        // Select Allowed (0) while companies exist -> empty filter message is shown.
        val allowedChipText = context.getString(R.string.feed_filter_allowed, 0)
        composeRule.onNodeWithTag("trackers-list").performScrollToNode(hasText(allowedChipText))
        composeRule.onNodeWithText(allowedChipText).performClick()
        composeRule.onNodeWithTag("trackers-list").performScrollToNode(
            hasText(context.getString(R.string.feed_no_matching_trackers))
        )
        composeRule.onNodeWithText(context.getString(R.string.feed_no_matching_trackers)).assertIsDisplayed()

        // If company rows disappear, section switches remain visible instead of being filtered out.
        currentRows = listOf(blockedOnlySection)
        composeRule.waitForIdle()
        val analyticsSwitch = hasContentDescription(
            context.getString(R.string.toggle_block_category_description, "Analytics")
        )
        composeRule.onNodeWithTag("trackers-list").performScrollToNode(analyticsSwitch)
        composeRule.onNode(analyticsSwitch).assertIsDisplayed()
    }

    @Test
    fun blockedFilterUsesTotalCountsAndKeepsReachableShowMore() {
        val callbacks = RecordingCallbacks()
        composeRule.setContent {
            TrackerControlTheme(useDynamicColor = false) {
                TrackersScreenContent(
                    model = model(
                        rows = listOf(
                            section("Analytics"),
                            company(blockingKey = "b1", name = "Blocked One", statusBlocked = true),
                            TrackersRow.ShowMore(
                                key = "more:Analytics",
                                text = "Show 2 more companies",
                                categoryName = "Analytics",
                                hasBlockedHidden = true,
                                hasAllowedHidden = false
                            )
                        ),
                        totalCompanyCount = 3,
                        totalBlockedCount = 3,
                        totalAllowedCount = 0
                    ),
                    callbacks = callbacks
                )
            }
        }

        val blockedChipText = context.getString(R.string.feed_filter_blocked, 3)
        composeRule.onNodeWithTag("trackers-list").performScrollToNode(hasText(blockedChipText))
        composeRule.onNodeWithText(blockedChipText)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("trackers-list").performScrollToNode(hasText("Show 2 more companies"))
        composeRule.onNodeWithText("Show 2 more companies").assertIsDisplayed().performClick()
        assertEquals("Analytics", callbacks.showMore)
    }
}
