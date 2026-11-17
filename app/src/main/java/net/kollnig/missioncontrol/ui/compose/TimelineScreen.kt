/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.ui.compose

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.text.format.DateUtils
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedToggleButton
import androidx.compose.material3.OutlinedToggleButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import net.kollnig.missioncontrol.R
import net.kollnig.missioncontrol.data.InsightsData
import net.kollnig.missioncontrol.data.TimelineEntry
import net.kollnig.missioncontrol.data.TrackerCategory
import java.text.NumberFormat

/** The four reasons an empty Timeline can be shown. */
enum class TimelineEmptyState {
    TRACKER_CONTROL_OFF,
    RECORDING_OFF,
    RECORDING_UNAVAILABLE,
    WATCHING
}

/** Immutable state for the Insights summary at the top of the Timeline. */
sealed interface TimelineInsightsState {
    data object Loading : TimelineInsightsState

    @Immutable
    data class Populated(val data: TimelineInsightsData) : TimelineInsightsState
}

@Immutable
data class TimelineInsightsData(
    val totalTrackingAttempts: Int,
    val blockedTrackingAttempts: Int,
    val uniqueTrackerCompanies: Int
) {
    /** Share of the selected period's tracker hosts that were blocked, 0..1; 0 when there were none. */
    val blockedFraction: Float
        get() = if (totalTrackingAttempts > 0) {
            (blockedTrackingAttempts.toFloat() / totalTrackingAttempts).coerceIn(0f, 1f)
        } else {
            0f
        }

    companion object {
        @JvmStatic
        fun from(data: InsightsData): TimelineInsightsData = TimelineInsightsData(
            totalTrackingAttempts = data.totalTrackingAttempts,
            blockedTrackingAttempts = data.blockedTrackingAttempts,
            uniqueTrackerCompanies = data.uniqueTrackerCompanies
        )
    }
}

/** Immutable tracker-contact row shown below an app. */
@Immutable
data class TimelineTrackerContact(
    val key: String,
    val companyName: String,
    val category: String?,
    val blocked: Boolean,
    val statusLabel: String
) {
    val displayName: String
        get() = if (category.isNullOrBlank()) companyName else "$companyName · $category"
}

/** Stable-keyed rows consumed by the single Timeline LazyColumn. */
sealed interface TimelineRow {
    val key: String

    @Immutable
    data class Section(
        override val key: String,
        val title: String
    ) : TimelineRow

    @Immutable
    data class App(
        override val key: String,
        val sectionKey: String,
        val uid: Int,
        val appName: String,
        val packageName: String?,
        val relativeTime: String,
        val summary: String,
        val blockedCount: Int,
        val allowedCount: Int,
        val contacts: List<TimelineTrackerContact>,
        val overflowCount: Int
    ) : TimelineRow {
        val clickable: Boolean
            get() = packageName != null
    }
}

/** The trailing windows, in days, the Insights summary can show; 1 is the last 24 hours. */
object TimelineInsightsPeriods {
    const val DEFAULT_DAYS = 7

    @JvmField
    val OPTIONS: List<Int> = listOf(1, 7, 30)

    /** Maps a stored value back onto a supported window. */
    @JvmStatic
    fun sanitize(days: Int): Int = if (days in OPTIONS) days else DEFAULT_DAYS
}

@Immutable
data class TimelineScreenModel(
    val insights: TimelineInsightsState,
    val emptyState: TimelineEmptyState?,
    val showHint: Boolean,
    val rows: List<TimelineRow>,
    val isRefreshing: Boolean = false,
    val insightsDays: Int = TimelineInsightsPeriods.DEFAULT_DAYS
) {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun initial(
            insightsDays: Int = TimelineInsightsPeriods.DEFAULT_DAYS
        ): TimelineScreenModel = TimelineScreenModel(
            insights = TimelineInsightsState.Loading,
            emptyState = null,
            showHint = false,
            rows = emptyList(),
            isRefreshing = false,
            insightsDays = insightsDays
        )
    }
}

/** Callbacks for actions whose implementation remains in TimelineFragment. */
interface TimelineScreenCallbacks {
    fun onEntryClick(uid: Int, appName: String, packageName: String)
    fun onOpenApp()
    fun onOpenSettings()
    fun onOpenInsights()

    /** The user picked another window ([days]; 1 means 24 hours) for the summary. */
    fun onInsightsDaysSelected(days: Int)
    fun onDismissHint()

    /** Pull-to-refresh gesture; rebuilds the timeline and the insights card. */
    fun onRefresh()
}

/** Handle used by Java to update Compose while retaining controller lifecycle ownership. */
class TimelineScreenController internal constructor(
    private val state: MutableState<TimelineScreenModel>
) {
    @Volatile
    private var valid = true

    fun updateTimeline(
        entries: List<TimelineEntry>,
        context: Context,
        emptyState: TimelineEmptyState?,
        showHint: Boolean
    ) {
        if (!valid) return
        state.value = state.value.copy(
            emptyState = if (entries.isEmpty()) emptyState else null,
            showHint = showHint,
            rows = timelineRows(entries, context),
            // The rebuilt list is the completion signal for a pull-to-refresh,
            // as it was for the SwipeRefreshLayout this screen replaced.
            isRefreshing = false
        )
    }

    /** Show or hide the pull-to-refresh indicator. */
    fun setRefreshing(refreshing: Boolean) {
        if (!valid) return
        state.value = state.value.copy(isRefreshing = refreshing)
    }

    fun updateInsights(data: InsightsData) {
        if (!valid) return
        state.value = state.value.copy(
            insights = TimelineInsightsState.Populated(TimelineInsightsData.from(data))
        )
    }

    fun setInsightsDays(days: Int) {
        if (!valid) return
        state.value = state.value.copy(insightsDays = days)
    }

    fun dismissHint() {
        if (!valid) return
        state.value = state.value.copy(showHint = false)
    }

    fun invalidate() {
        valid = false
    }
}

/** Java-facing entry point for the migrated Timeline screen. */
object TimelineScreen {
    @JvmStatic
    fun install(
        composeView: ComposeView,
        callbacks: TimelineScreenCallbacks
    ): TimelineScreenController = install(composeView, TimelineScreenModel.initial(), callbacks)

    @JvmStatic
    fun install(
        composeView: ComposeView,
        initialModel: TimelineScreenModel,
        callbacks: TimelineScreenCallbacks
    ): TimelineScreenController {
        val state = mutableStateOf(initialModel)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            TrackerControlTheme {
                TimelineScreenContent(state.value, callbacks)
            }
        }
        return TimelineScreenController(state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimelineScreenContent(
    model: TimelineScreenModel,
    callbacks: TimelineScreenCallbacks
) {
    val iconCache = remember { mutableMapOf<String, Drawable?>() }
    CompositionLocalProvider(LocalAppIconCache provides iconCache) {
        PullToRefreshBox(
            isRefreshing = model.isRefreshing,
            onRefresh = callbacks::onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            TimelineList(model, callbacks)
        }
    }
}

@Composable
private fun TimelineList(
    model: TimelineScreenModel,
    callbacks: TimelineScreenCallbacks
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item(key = "insights-summary") {
            TimelineInsightsSection(model.insights, model.insightsDays, callbacks)
        }

        model.emptyState?.let { emptyState ->
            item(key = "timeline-empty") {
                TimelineEmptyContent(emptyState, callbacks)
            }
        }

        if (model.showHint) {
            item(key = "timeline-hint") {
                TimelineHintContent(callbacks)
            }
        }

        model.rows.forEachIndexed { index, row ->
            item(key = row.key) {
                when (row) {
                    is TimelineRow.Section -> DetailsSectionHeading(text = row.title)
                    is TimelineRow.App -> {
                        val next = model.rows.getOrNull(index + 1)
                        val showDivider = next is TimelineRow.App &&
                            next.sectionKey == row.sectionKey
                        TimelineAppRow(
                            row = row,
                            showDivider = showDivider,
                            onClick = {
                                row.packageName?.let { packageName ->
                                    callbacks.onEntryClick(row.uid, row.appName, packageName)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

private val InsightsSectionPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)

/**
 * The Insights summary: a period selector above the ring and tiles. It sits
 * directly on the screen background rather than inside a card.
 */
@Composable
private fun TimelineInsightsSection(
    state: TimelineInsightsState,
    days: Int,
    callbacks: TimelineScreenCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(InsightsSectionPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TimelineInsightsPeriodSelector(
            selectedDays = days,
            onSelect = callbacks::onInsightsDaysSelected
        )
        TimelineInsightsSummary(state, callbacks)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TimelineInsightsPeriodSelector(
    selectedDays: Int,
    onSelect: (Int) -> Unit
) {
    val options = TimelineInsightsPeriods.OPTIONS
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        options.forEachIndexed { index, days ->
            val shapes = when (index) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            OutlinedToggleButton(
                checked = selectedDays == days,
                onCheckedChange = { checked -> if (checked) onSelect(days) },
                shapes = shapes,
                colors = OutlinedToggleButtonDefaults.colors(
                    checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("timeline-insights-period-$days")
            ) {
                Text(
                    text = stringResource(
                        when (days) {
                            1 -> R.string.timeline_insights_period_24h
                            30 -> R.string.timeline_insights_period_30d
                            else -> R.string.timeline_insights_period_7d
                        }
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TimelineInsightsSummary(
    state: TimelineInsightsState,
    callbacks: TimelineScreenCallbacks
) {
    val insightsTitle = stringResource(R.string.insights_title)
    when (state) {
        TimelineInsightsState.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(InsightsRingSize)
                    .semantics { contentDescription = insightsTitle }
                    .testTag("timeline-insights-loading"),
                contentAlignment = Alignment.Center
            ) {
                ExpressiveLoadingIndicator(contained = true)
            }
        }

        is TimelineInsightsState.Populated -> {
            val data = state.data
            val locale = LocalLocale.current.platformLocale
            val numberFormat = NumberFormat.getNumberInstance(locale)
            // One decimal, so a low block rate still shows movement week to week.
            val percentFormat = NumberFormat.getPercentInstance(locale).apply {
                minimumFractionDigits = 1
                maximumFractionDigits = 1
            }
            val blockedText = numberFormat.format(data.blockedTrackingAttempts)
            val ofTotalText = stringResource(
                R.string.timeline_insights_of_total,
                numberFormat.format(data.totalTrackingAttempts)
            )
            val blockedLabel = stringResource(R.string.insights_blocked)
            val colors = MaterialTheme.colorScheme

            // Tapping the summary opens the full Insights screen.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(
                        onClickLabel = insightsTitle,
                        role = Role.Button,
                        onClick = callbacks::onOpenInsights
                    )
                    .testTag("timeline-insights-card"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TimelineInsightsRing(
                    progress = data.blockedFraction,
                    label = blockedLabel,
                    valueText = blockedText,
                    ofTotalText = ofTotalText,
                    description = stringResource(
                        R.string.accessibility_stat_description,
                        "$blockedText $ofTotalText",
                        blockedLabel
                    )
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(InsightsTileSpacing)
                ) {
                    // The tile doubles as a meter: its leading part is
                    // filled to the same share the ring shows.
                    TimelineInsightsTile(
                        iconRes = R.drawable.ic_shield_check,
                        label = stringResource(R.string.timeline_insights_block_rate),
                        value = percentFormat.format(data.blockedFraction.toDouble()),
                        containerColor = colors.primaryContainer,
                        contentColor = colors.onPrimaryContainer,
                        fillFraction = data.blockedFraction,
                        fillColor = lerp(colors.primaryContainer, colors.primary, 0.25f)
                    )
                    // A dynamic dark scheme can hand out a light
                    // tertiaryContainer; on a dark surface that tile
                    // glares, so darken it and flip its content light.
                    val isDarkTheme = colors.surface.luminance() < 0.5f
                    val tertiaryIsLight = colors.tertiaryContainer.luminance() > 0.2f
                    val darkenTertiary = isDarkTheme && tertiaryIsLight
                    TimelineInsightsTile(
                        iconRes = R.drawable.ic_domain,
                        label = stringResource(R.string.insights_tracker_companies),
                        shortLabel = stringResource(R.string.timeline_insights_companies),
                        value = numberFormat.format(data.uniqueTrackerCompanies),
                        containerColor = if (darkenTertiary) {
                            lerp(colors.tertiaryContainer, Color.Black, 0.5f)
                        } else {
                            colors.tertiaryContainer
                        },
                        contentColor = if (darkenTertiary) {
                            lerp(colors.tertiaryContainer, Color.White, 0.6f)
                        } else {
                            colors.onTertiaryContainer
                        }
                    )
                }
            }
        }
    }
}

// Without the card's own 16.dp inset the ring and tiles get that width back.
// The ring matches the height of the two stacked tiles.
private val InsightsTileHeight = 72.dp
private val InsightsTileSpacing = 8.dp
private val InsightsRingSize = InsightsTileHeight * 2 + InsightsTileSpacing
private val InsightsRingStroke = 16.dp

/** Blocked-share ring with the blocked count, and the total it is out of, in its centre. */
@Composable
private fun TimelineInsightsRing(
    progress: Float,
    label: String,
    valueText: String,
    ofTotalText: String,
    description: String,
    modifier: Modifier = Modifier
) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val indicatorColor = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(InsightsRingSize)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("timeline-insights-progress")
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(clampedProgress, 0f..1f)
                }
        ) {
            val stroke = InsightsRingStroke.toPx()
            val arcTopLeft = Offset(stroke / 2f, stroke / 2f)
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = stroke)
            )
            // A zero sweep with a round cap would still paint a dot.
            if (clampedProgress > 0f) {
                drawArc(
                    color = indicatorColor,
                    startAngle = -90f,
                    sweepAngle = 360f * clampedProgress,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Column(
            // Narrower than the ring's inner diameter, so text stays off the stroke.
            modifier = Modifier.width(InsightsRingSize - InsightsRingStroke * 2 - 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = GoogleSansFlexRoundedFontFamily,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Shrinks rather than clips once the count reaches five or six digits.
            Text(
                text = valueText,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = GoogleSansFlexRoundedFontFamily
                ),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 16.sp,
                    maxFontSize = 36.sp,
                    stepSize = 1.sp
                )
            )
            Text(
                text = ofTotalText,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = GoogleSansFlexRoundedFontFamily,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Pill tile beside the ring: an icon in its own pill, a label and a value.
 * A non-zero [fillFraction] paints the leading part of the tile in [fillColor].
 */
@Composable
private fun TimelineInsightsTile(
    iconRes: Int,
    label: String,
    value: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    shortLabel: String? = null,
    fillFraction: Float = 0f,
    fillColor: Color = Color.Unspecified
) {
    // The icon pill moves away from the icon's own colour: lighter than a light
    // tile, darker than a dark one. Decided per tile, not per theme, because a
    // dynamic dark scheme can hand out a light container (tertiaryContainer on
    // some devices) whose dark content would vanish on a darkened pill.
    val isLightContainer = containerColor.luminance() > contentColor.luminance()
    // On a light tile the pill is only a step lighter than the unfilled
    // container, so it reads as part of the tile rather than a white hole.
    val iconContainerColor = if (isLightContainer) {
        lerp(containerColor, Color.White, 0.45f)
    } else {
        lerp(containerColor, Color.Black, 0.4f)
    }
    val clampedFill = fillFraction.coerceIn(0f, 1f)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val description = stringResource(R.string.accessibility_stat_description, value, label)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = InsightsTileHeight)
            .clip(RoundedCornerShape(24.dp))
            .background(containerColor)
            .drawBehind {
                if (clampedFill > 0f && fillColor.isSpecified) {
                    val fillWidth = size.width * clampedFill
                    drawRect(
                        color = fillColor,
                        topLeft = Offset(if (isRtl) size.width - fillWidth else 0f, 0f),
                        size = Size(fillWidth, size.height)
                    )
                }
            }
            // 6.dp on the icon's three outer sides keeps its pill concentric
            // with the tile's corners.
            .padding(start = 6.dp, top = 6.dp, end = 12.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = InsightsTileHeight - 12.dp)
                .background(iconContainerColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(22.dp)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val tileLineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both
            )
            val labelStyle = MaterialTheme.typography.labelLarge.copy(
                fontFamily = GoogleSansFlexRoundedFontFamily,
                fontSize = 14.sp,
                lineHeight = 16.sp,
                lineHeightStyle = tileLineHeightStyle,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp
            )
            if (shortLabel != null) {
                val textMeasurer = rememberTextMeasurer()
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val fitsFullLabel = remember(label, labelStyle, constraints.maxWidth, textMeasurer) {
                        !constraints.hasBoundedWidth || !textMeasurer.measure(
                            text = label,
                            style = labelStyle,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            constraints = Constraints(maxWidth = constraints.maxWidth)
                        ).hasVisualOverflow
                    }
                    Text(
                        text = if (fitsFullLabel) label else shortLabel,
                        color = contentColor,
                        style = labelStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    text = label,
                    color = contentColor,
                    style = labelStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = value,
                color = contentColor,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = GoogleSansFlexRoundedFontFamily,
                    fontSize = 26.sp,
                    lineHeight = 28.sp,
                    lineHeightStyle = tileLineHeightStyle,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.sp
                ),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TimelineEmptyContent(
    state: TimelineEmptyState,
    callbacks: TimelineScreenCallbacks
) {
    val (title, subtitle) = when (state) {
        TimelineEmptyState.TRACKER_CONTROL_OFF ->
            stringResource(R.string.timeline_empty_disabled_title) to
                stringResource(R.string.timeline_empty_disabled_subtitle)
        TimelineEmptyState.RECORDING_OFF ->
            stringResource(R.string.timeline_empty_recording_off_title) to
                stringResource(R.string.timeline_empty_recording_off_subtitle)
        TimelineEmptyState.RECORDING_UNAVAILABLE ->
            stringResource(R.string.timeline_empty_recording_unavailable_title) to
                stringResource(R.string.timeline_empty_recording_unavailable_subtitle)
        TimelineEmptyState.WATCHING ->
            stringResource(R.string.timeline_empty_enabled_title) to
                stringResource(R.string.timeline_empty_enabled_subtitle)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 28.dp)
            .semantics { contentDescription = "$title. $subtitle" },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = subtitle,
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        when (state) {
            TimelineEmptyState.RECORDING_OFF -> Button(
                onClick = callbacks::onOpenSettings,
                modifier = Modifier
                    .padding(top = 20.dp)
                    .testTag("timeline-empty-open-settings")
            ) {
                Text(stringResource(R.string.timeline_open_settings))
            }
            TimelineEmptyState.WATCHING -> Button(
                onClick = callbacks::onOpenApp,
                modifier = Modifier
                    .padding(top = 20.dp)
                    .testTag("timeline-empty-open-app")
            ) {
                Text(stringResource(R.string.timeline_open_app))
            }
            else -> Unit
        }
    }
}

@Composable
private fun TimelineHintContent(callbacks: TimelineScreenCallbacks) {
    val hintText = stringResource(R.string.timeline_hint_tap_entry)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .semantics { contentDescription = hintText },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.timeline_hint_tap_entry),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            OutlinedButton(
                onClick = callbacks::onDismissHint,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 8.dp)
                    .testTag("timeline-hint-dismiss")
            ) {
                Text(stringResource(R.string.timeline_hint_dismiss))
            }
        }
    }
}

@Composable
private fun TimelineAppRow(
    row: TimelineRow.App,
    showDivider: Boolean,
    onClick: () -> Unit
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .then(
            if (row.clickable) {
                Modifier.clickable(role = Role.Button, onClick = onClick)
            } else {
                Modifier
            }
        )
        .semantics(mergeDescendants = true) {
            if (row.clickable) role = Role.Button
        }

    // Keep the divider outside the clickable row and its vertical padding so
    // ripple/a11y bounds cover only the row and spacing stays 12.dp on both sides.
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = rowModifier
                .testTag("timeline-app-${row.uid}")
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            TimelineAppHeader(row)
            Text(
                text = row.summary,
                modifier = Modifier.padding(start = 48.dp, top = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TimelineTrackerList(row)
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag("timeline-divider"),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun TimelineAppHeader(row: TimelineRow.App) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TimelineAppIcon(row.packageName)
        Text(
            text = row.appName,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = row.relativeTime,
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TimelineTrackerList(row: TimelineRow.App) {
    Column(
        modifier = Modifier.padding(start = 48.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        row.contacts.forEach { contact ->
            TimelineTrackerRow(contact)
        }
        if (row.overflowCount > 0) {
            Text(
                text = stringResource(R.string.timeline_more_trackers, row.overflowCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TimelineTrackerRow(contact: TimelineTrackerContact) {
    val statusColour = if (contact.blocked) {
        colorResource(R.color.timeline_blocked)
    } else {
        colorResource(R.color.timeline_allowed)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = contact.statusLabel,
            modifier = Modifier.padding(end = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = statusColour,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = contact.displayName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Per-screen icon cache. The Timeline re-renders every 30 seconds, and without
 * this every visible row would hit the PackageManager again on each tick.
 * Scoped to the composition (rather than a process-wide map) so the drawables
 * are released with the screen.
 */
private val LocalAppIconCache = staticCompositionLocalOf<MutableMap<String, Drawable?>> {
    mutableMapOf()
}

@Composable
private fun TimelineAppIcon(packageName: String?) {
    val iconCache = LocalAppIconCache.current
    AndroidView(
        factory = { viewContext ->
            ImageView(viewContext).apply {
                importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        },
        update = { imageView ->
            val icon = packageName?.let { name ->
                if (iconCache.containsKey(name)) {
                    iconCache[name]
                } else {
                    findAppIcon(imageView.context, name).also { iconCache[name] = it }
                }
            }
            if (icon != null) {
                imageView.setImageDrawable(icon)
            } else {
                imageView.setImageResource(android.R.drawable.sym_def_app_icon)
            }
        },
        modifier = Modifier.size(36.dp)
    )
}

private fun findAppIcon(context: Context, packageName: String): Drawable? = try {
    val applicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
    context.packageManager.getApplicationIcon(applicationInfo)
} catch (_: PackageManager.NameNotFoundException) {
    null
}

private fun timelineRows(entries: List<TimelineEntry>, context: Context): List<TimelineRow> {
    if (entries.isEmpty()) return emptyList()

    val now = System.currentTimeMillis()
    val oneHourAgo = now - 60 * 60 * 1000L
    val startOfToday = startOfDay(0)
    val startOfYesterday = startOfDay(1)
    val rows = mutableListOf<TimelineRow>()
    var currentSection: String? = null
    var currentSectionKey: String? = null

    entries.forEach { entry ->
        val sectionTitle = when {
            entry.mostRecentTime >= oneHourAgo -> context.getString(R.string.timeline_section_last_hour)
            entry.mostRecentTime >= startOfToday -> context.getString(R.string.timeline_section_today)
            entry.mostRecentTime >= startOfYesterday -> context.getString(R.string.timeline_section_yesterday)
            else -> context.getString(R.string.timeline_section_this_week)
        }
        if (sectionTitle != currentSection) {
            currentSection = sectionTitle
            currentSectionKey = "section:$sectionTitle"
            rows += TimelineRow.Section(currentSectionKey!!, sectionTitle)
        }

        val blockedCount = entry.getBlockedCount()
        val allowedCount = entry.getAllowedCount()
        val summary = when {
            blockedCount > 0 && allowedCount > 0 ->
                context.getString(R.string.timeline_summary_mixed, blockedCount, allowedCount)
            blockedCount > 0 ->
                context.resources.getQuantityString(
                    R.plurals.timeline_trackers_blocked,
                    blockedCount,
                    blockedCount
                )
            else ->
                context.resources.getQuantityString(
                    R.plurals.timeline_trackers_allowed,
                    allowedCount,
                    allowedCount
                )
        }
        val contacts = entry.trackers.take(3).map { tracker ->
            val statusLabel = context.getString(
                if (tracker.blocked) R.string.timeline_tracker_blocked
                else R.string.timeline_tracker_allowed
            )
            TimelineTrackerContact(
                key = "contact:${entry.uid}:${tracker.companyName}:${tracker.blocked}",
                companyName = tracker.companyName,
                category = TrackerCategory.getDisplayName(context, tracker.category),
                blocked = tracker.blocked,
                statusLabel = statusLabel
            )
        }
        val relativeTime = DateUtils.getRelativeTimeSpanString(
            entry.mostRecentTime,
            now,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
        rows += TimelineRow.App(
            key = "entry:${entry.uid}",
            sectionKey = currentSectionKey!!,
            uid = entry.uid,
            appName = entry.appName,
            packageName = entry.packageName,
            relativeTime = relativeTime,
            summary = summary,
            blockedCount = blockedCount,
            allowedCount = allowedCount,
            contacts = contacts,
            overflowCount = (entry.trackers.size - 3).coerceAtLeast(0)
        )
    }
    return rows
}

private fun startOfDay(daysAgo: Int): Long {
    val calendar = java.util.Calendar.getInstance()
    calendar.add(java.util.Calendar.DAY_OF_YEAR, -daysAgo)
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
    calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0)
    calendar.set(java.util.Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

@Preview(showBackground = true)
@Preview(name = "Compact 360dp", showBackground = true, widthDp = 360)
@Composable
private fun TimelineScreenPreview() {
    TrackerControlTheme {
        TimelineScreenContent(
            model = TimelineScreenModel(
                insights = TimelineInsightsState.Populated(
                    TimelineInsightsData(
                        totalTrackingAttempts = 730,
                        blockedTrackingAttempts = 168,
                        uniqueTrackerCompanies = 59
                    )
                ),
                emptyState = null,
                showHint = true,
                rows = listOf(
                    TimelineRow.Section("section-preview", "Today"),
                    TimelineRow.App(
                        key = "app-preview",
                        sectionKey = "section-preview",
                        uid = 10001,
                        appName = "Example app",
                        packageName = "com.example.app",
                        relativeTime = "3 min ago",
                        summary = "2 trackers blocked · 1 allowed",
                        blockedCount = 2,
                        allowedCount = 1,
                        contacts = listOf(
                            TimelineTrackerContact(
                                key = "contact-preview",
                                companyName = "Example Analytics",
                                category = "Advertising",
                                blocked = true,
                                statusLabel = "Blocked"
                            )
                        ),
                        overflowCount = 0
                    )
                )
            ),
            callbacks = PreviewTimelineCallbacks
        )
    }
}

private object PreviewTimelineCallbacks : TimelineScreenCallbacks {
    override fun onEntryClick(uid: Int, appName: String, packageName: String) = Unit
    override fun onOpenApp() = Unit
    override fun onOpenSettings() = Unit
    override fun onOpenInsights() = Unit
    override fun onInsightsDaysSelected(days: Int) = Unit
    override fun onDismissHint() = Unit
    override fun onRefresh() = Unit
}
