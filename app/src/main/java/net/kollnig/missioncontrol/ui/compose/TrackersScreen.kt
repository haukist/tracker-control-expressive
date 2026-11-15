/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.ui.compose

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.kollnig.missioncontrol.R

private val SectionOuterRadius = 20.dp
private val SectionInnerRadius = 4.dp

/** Immutable presentation state calculated by the Java tracker controller. */
data class TrackersScreenModel(
    val browserWarning: Boolean,
    val appStateTitle: String,
    val appStateValue: String?,
    val appStateHint: String,
    val appStateHintAccent: Boolean,
    val librarySummary: String,
    val rows: List<TrackersRow>,
    /** True while the tracker query runs; drives the pull-to-refresh spinner. */
    val isRefreshing: Boolean = false,
    val totalCompanyCount: Int? = null,
    val totalBlockedCount: Int? = null,
    val totalAllowedCount: Int? = null
)

/** Immutable row data; policy and persistence decisions stay in Java. */
sealed interface TrackersRow {
    val key: String

    data class Section(
        override val key: String,
        val categoryName: String,
        val title: String,
        val lastContact: String?,
        val explainer: String?,
        val switchChecked: Boolean,
        val switchEnabled: Boolean,
        val switchDescription: String
    ) : TrackersRow

    data class Company(
        override val key: String,
        val categoryName: String,
        val blockingKey: String,
        val name: String,
        val lastSeen: String?,
        val status: String,
        val statusBlocked: Boolean,
        val hosts: List<String>,
        val uncertain: Boolean,
        val showAllowSwitch: Boolean,
        val blockSwitchLabel: String,
        val blockSwitchChecked: Boolean,
        val blockSwitchEnabled: Boolean,
        val statusNote: String?
    ) : TrackersRow

    data class ShowMore(
        override val key: String,
        val text: String,
        val categoryName: String,
        val hasBlockedHidden: Boolean = true,
        val hasAllowedHidden: Boolean = true
    ) : TrackersRow
}

private enum class TrackerFilter {
    ALL,
    BLOCKED,
    ALLOWED
}

private data class PositionedFeedRow(
    val row: TrackersRow,
    val hasSectionChildren: Boolean,
    val isLastInSection: Boolean
)

/** Callbacks for actions whose implementation remains in TrackersFragment. */
interface TrackersScreenCallbacks {
    fun onAppStateClick()
    fun onLibrariesClick()
    fun onSectionToggle(categoryName: String, checked: Boolean)
    fun onCompanyBlockChanged(blockingKey: String, blocked: Boolean)
    fun onShowMore(categoryName: String)

    /** Pull-to-refresh gesture; re-runs the tracker query for this app. */
    fun onRefresh()
}

/** Handle used by Java to update Compose without moving data/lifecycle logic. */
class TrackersScreenController internal constructor(
    private val state: MutableState<TrackersScreenModel>
) {
    fun update(model: TrackersScreenModel) {
        state.value = model
    }
}

/** Java-facing entry point for the incrementally migrated Trackers tab. */
object TrackersScreen {
    @JvmStatic
    fun install(
        composeView: ComposeView,
        initialModel: TrackersScreenModel,
        callbacks: TrackersScreenCallbacks
    ): TrackersScreenController {
        val state = mutableStateOf(initialModel)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            TrackerControlTheme {
                TrackersScreenContent(state.value, callbacks)
            }
        }
        return TrackersScreenController(state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackersScreenContent(
    model: TrackersScreenModel,
    callbacks: TrackersScreenCallbacks
) {
    var selectedFilter by rememberSaveable { mutableStateOf(TrackerFilter.ALL) }
    var selectedCompanyKey by rememberSaveable { mutableStateOf<String?>(null) }

    val selectedCompany = remember(model.rows, selectedCompanyKey) {
        selectedCompanyKey?.let { key ->
            model.rows.filterIsInstance<TrackersRow.Company>().firstOrNull { it.key == key }
        }
    }
    val selectedSection = remember(model.rows, selectedCompany) {
        selectedCompany?.let { company ->
            model.rows
                .filterIsInstance<TrackersRow.Section>()
                .firstOrNull { it.categoryName == company.categoryName }
        }
    }

    LaunchedEffect(model.rows, selectedCompanyKey, model.isRefreshing) {
        if (selectedCompanyKey != null && selectedCompany == null && !model.isRefreshing) {
            selectedCompanyKey = null
        }
    }

    // isRefreshing also covers the initial async tracker query, which is what
    // the pre-Compose SwipeRefreshLayout spinner reported.
    PullToRefreshBox(
        isRefreshing = model.isRefreshing,
        onRefresh = callbacks::onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TrackersList(
            model = model,
            selectedFilter = selectedFilter,
            onFilterSelected = { selectedFilter = it },
            onOpenCompany = { company ->
                selectedCompanyKey = company.key
            },
            callbacks = callbacks
        )
    }

    if (selectedCompany != null) {
        CompanyDetailDialog(
            company = selectedCompany,
            parentSection = selectedSection,
            onDismiss = { selectedCompanyKey = null },
            onBlockCategory = { categoryName ->
                callbacks.onSectionToggle(categoryName, true)
            },
            onBlockCompanyChanged = { blocked ->
                callbacks.onCompanyBlockChanged(selectedCompany.blockingKey, blocked)
            }
        )
    }
}

private fun buildPositionedRows(
    rows: List<TrackersRow>,
    filter: TrackerFilter
): List<PositionedFeedRow> {
    val orphanCompanies = mutableListOf<TrackersRow.Company>()
    val sections = mutableListOf<Pair<TrackersRow.Section, MutableList<TrackersRow>>>()
    for (row in rows) {
        when (row) {
            is TrackersRow.Section -> sections.add(row to mutableListOf())
            is TrackersRow.Company -> {
                val matches = when (filter) {
                    TrackerFilter.ALL -> true
                    TrackerFilter.BLOCKED -> row.statusBlocked
                    TrackerFilter.ALLOWED -> !row.statusBlocked
                }
                if (matches) {
                    val lastSection = sections.lastOrNull()
                    if (lastSection != null) {
                        lastSection.second.add(row)
                    } else {
                        orphanCompanies.add(row)
                    }
                }
            }
            is TrackersRow.ShowMore -> {
                val keep = when (filter) {
                    TrackerFilter.ALL -> true
                    TrackerFilter.BLOCKED -> row.hasBlockedHidden
                    TrackerFilter.ALLOWED -> row.hasAllowedHidden
                }
                if (keep) {
                    sections.lastOrNull()?.second?.add(row)
                }
            }
        }
    }

    val positioned = mutableListOf<PositionedFeedRow>()
    orphanCompanies.forEachIndexed { index, company ->
        positioned.add(
            PositionedFeedRow(
                row = company,
                hasSectionChildren = true,
                isLastInSection = index == orphanCompanies.lastIndex
            )
        )
    }
    for ((section, children) in sections) {
        if (filter != TrackerFilter.ALL && children.isEmpty()) continue
        positioned.add(
            PositionedFeedRow(
                row = section,
                hasSectionChildren = children.isNotEmpty(),
                isLastInSection = children.isEmpty()
            )
        )
        children.forEachIndexed { index, child ->
            positioned.add(
                PositionedFeedRow(
                    row = child,
                    hasSectionChildren = true,
                    isLastInSection = index == children.lastIndex
                )
            )
        }
    }
    return positioned
}

@Composable
private fun TrackersList(
    model: TrackersScreenModel,
    selectedFilter: TrackerFilter,
    onFilterSelected: (TrackerFilter) -> Unit,
    onOpenCompany: (TrackersRow.Company) -> Unit,
    callbacks: TrackersScreenCallbacks
) {
    val companyRows = remember(model.rows) {
        model.rows.filterIsInstance<TrackersRow.Company>()
    }
    val allCount = model.totalCompanyCount ?: companyRows.size
    val blockedCount = remember(model.totalBlockedCount, companyRows) {
        model.totalBlockedCount ?: companyRows.count { it.statusBlocked }
    }
    val allowedCount = remember(model.totalAllowedCount, allCount, blockedCount) {
        model.totalAllowedCount ?: (allCount - blockedCount).coerceAtLeast(0)
    }
    val effectiveFilter = if (companyRows.isEmpty()) TrackerFilter.ALL else selectedFilter
    LaunchedEffect(companyRows.isEmpty()) {
        if (companyRows.isEmpty() && selectedFilter != TrackerFilter.ALL) {
            onFilterSelected(TrackerFilter.ALL)
        }
    }
    val positionedRows = remember(model.rows, effectiveFilter) {
        buildPositionedRows(model.rows, effectiveFilter)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("trackers-list"),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item(key = "header") {
            TrackersHeader(
                model = model,
                onAppStateClick = callbacks::onAppStateClick,
                onLibrariesClick = callbacks::onLibrariesClick
            )
        }
        if (companyRows.isNotEmpty()) {
            item(key = "filter-chips") {
                TrackerFilterChipsRow(
                    selectedFilter = effectiveFilter,
                    allCount = allCount,
                    blockedCount = blockedCount,
                    allowedCount = allowedCount,
                    onFilterSelected = onFilterSelected
                )
            }
        }
        if (companyRows.isNotEmpty() && positionedRows.isEmpty()) {
            item(key = "empty-filter") {
                Text(
                    text = stringResource(R.string.feed_no_matching_trackers),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(
            items = positionedRows,
            key = { it.row.key }
        ) { item ->
            when (val row = item.row) {
                is TrackersRow.Section -> {
                    TrackerSectionRow(
                        row = row,
                        hasChildren = item.hasSectionChildren,
                        onToggle = { checked ->
                            callbacks.onSectionToggle(row.categoryName, checked)
                        }
                    )
                    Spacer(
                        modifier = Modifier.height(
                            if (item.hasSectionChildren) ExpressiveGroupSpacing else 12.dp
                        )
                    )
                }

                is TrackersRow.Company -> {
                    TrackerCompanyRow(
                        row = row,
                        isLastInSection = item.isLastInSection,
                        onClick = { onOpenCompany(row) }
                    )
                    Spacer(
                        modifier = Modifier.height(
                            if (item.isLastInSection) 12.dp else ExpressiveGroupSpacing
                        )
                    )
                }

                is TrackersRow.ShowMore -> {
                    TrackerShowMoreRow(
                        row = row,
                        onClick = { callbacks.onShowMore(row.categoryName) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun TrackerFilterChipsRow(
    selectedFilter: TrackerFilter,
    allCount: Int,
    blockedCount: Int,
    allowedCount: Int,
    onFilterSelected: (TrackerFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedFilter == TrackerFilter.ALL,
            onClick = { onFilterSelected(TrackerFilter.ALL) },
            label = { Text(stringResource(R.string.feed_filter_all, allCount)) },
            leadingIcon = if (selectedFilter == TrackerFilter.ALL) {
                {
                    Icon(
                        painter = painterResource(R.drawable.ic_check_white_24dp),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                null
            }
        )
        FilterChip(
            selected = selectedFilter == TrackerFilter.BLOCKED,
            onClick = { onFilterSelected(TrackerFilter.BLOCKED) },
            label = { Text(stringResource(R.string.feed_filter_blocked, blockedCount)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_shield_check),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        )
        FilterChip(
            selected = selectedFilter == TrackerFilter.ALLOWED,
            onClick = { onFilterSelected(TrackerFilter.ALLOWED) },
            label = { Text(stringResource(R.string.feed_filter_allowed, allowedCount)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_shield_off),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        )
    }
}

@Composable
private fun TrackersHeader(
    model: TrackersScreenModel,
    onAppStateClick: () -> Unit,
    onLibrariesClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (model.browserWarning) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Text(
                    text = stringResource(R.string.browsers_not_supported),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { heading() },
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 24.sp
                )
                Text(
                    text = stringResource(R.string.browsers_not_supported_explanation),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        }

        DetailsSectionHeading(
            text = stringResource(R.string.block_tracking)
        )
        Text(
            text = stringResource(R.string.block_tracking_description),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge
        )

        TrackerSummaryRow(
            title = model.appStateTitle,
            value = model.appStateValue,
            hint = model.appStateHint,
            hintColour = if (model.appStateHintAccent) {
                colorResource(R.color.colorAccent)
            } else {
                MaterialTheme.colorScheme.primary
            },
            onClick = onAppStateClick
        )
        HeaderDivider()
        TrackerSummaryRow(
            title = stringResource(R.string.libraries_row_heading),
            value = model.librarySummary,
            hint = null,
            hintColour = MaterialTheme.colorScheme.onSurface,
            onClick = onLibrariesClick
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun TrackerSummaryRow(
    title: String,
    value: String?,
    hint: String?,
    hintColour: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            if (!value.isNullOrEmpty()) {
                Text(
                    text = value,
                    modifier = Modifier.padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
            if (!hint.isNullOrEmpty()) {
                Text(
                    text = hint,
                    modifier = Modifier.padding(top = 2.dp),
                    color = hintColour,
                    fontSize = 12.sp
                )
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HeaderDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 1.dp
    )
}

private fun sectionHeaderShape(hasChildren: Boolean): RoundedCornerShape =
    if (hasChildren) {
        RoundedCornerShape(
            topStart = SectionOuterRadius,
            topEnd = SectionOuterRadius,
            bottomStart = SectionInnerRadius,
            bottomEnd = SectionInnerRadius
        )
    } else {
        RoundedCornerShape(SectionOuterRadius)
    }

private fun sectionChildShape(isLastInSection: Boolean): RoundedCornerShape =
    if (isLastInSection) {
        // Top corners intentionally stay tight because the section header above
        // already carries the top outer corner radius.
        RoundedCornerShape(
            topStart = SectionInnerRadius,
            topEnd = SectionInnerRadius,
            bottomStart = SectionOuterRadius,
            bottomEnd = SectionOuterRadius
        )
    } else {
        RoundedCornerShape(SectionInnerRadius)
    }

@Composable
private fun TrackerSectionRow(
    row: TrackersRow.Section,
    hasChildren: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val configuration = LocalConfiguration.current
    @Suppress("DEPRECATION")
    val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        configuration.locales[0]
    } else {
        configuration.locale
    }
    val containerColor = if (row.switchChecked) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val titleColor = if (row.switchChecked) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = sectionHeaderShape(hasChildren),
        color = containerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // TalkBack spells real uppercase out letter by letter, so keep the
                // visual casing but announce the untouched title.
                Text(
                    text = row.title.uppercase(locale),
                    modifier = Modifier.semantics {
                        heading()
                        contentDescription = row.title
                    },
                    color = titleColor,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                if (!row.lastContact.isNullOrEmpty()) {
                    Text(
                        text = row.lastContact,
                        modifier = Modifier.padding(top = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!row.explainer.isNullOrEmpty()) {
                    Text(
                        text = row.explainer,
                        modifier = Modifier.padding(top = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Text(
                text = stringResource(R.string.title_block),
                modifier = Modifier
                    .padding(start = 8.dp, end = 8.dp)
                    .clearAndSetSemantics { },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (row.switchEnabled) {
                    titleColor
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            TrackerSwitch(
                checked = row.switchChecked,
                enabled = row.switchEnabled,
                contentDescription = row.switchDescription,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun TrackerCompanyRow(
    row: TrackersRow.Company,
    isLastInSection: Boolean,
    onClick: () -> Unit
) {
    val blockedColor = MaterialTheme.colorScheme.primary
    val previewHost = remember(row.hosts) {
        row.hosts.asSequence().filter { it.isNotBlank() }.minOrNull()
    }
    val supportingText = buildAnnotatedString {
        if (row.statusBlocked) {
            withStyle(SpanStyle(color = blockedColor, fontWeight = FontWeight.SemiBold)) {
                append(row.status)
            }
        } else {
            append(row.status)
        }
        if (!previewHost.isNullOrEmpty()) {
            append(" · ")
            append(previewHost)
        }
        if (!row.lastSeen.isNullOrEmpty()) {
            append(" · ")
            append(row.lastSeen)
        }
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = sectionChildShape(isLastInSection),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompanyStatusBadge(blocked = row.statusBlocked)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = supportingText,
                    modifier = Modifier.padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CompanyStatusBadge(
    blocked: Boolean,
    size: Dp = 40.dp
) {
    val blockedColor = MaterialTheme.colorScheme.primary
    val badgeBg = if (blocked) {
        blockedColor.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val iconTint = if (blocked) {
        blockedColor
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    val iconRes = if (blocked) {
        R.drawable.ic_shield_check
    } else {
        R.drawable.ic_shield_off
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(badgeBg),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(size * 0.52f),
            tint = iconTint
        )
    }
}

@Composable
private fun CompanyDetailDialog(
    company: TrackersRow.Company,
    parentSection: TrackersRow.Section?,
    onDismiss: () -> Unit,
    onBlockCategory: (String) -> Unit,
    onBlockCompanyChanged: (Boolean) -> Unit
) {
    val categoryTitle = parentSection?.title?.takeIf { it.isNotEmpty() } ?: company.categoryName
    val unblockedSection = parentSection?.takeIf {
        !it.switchChecked && it.switchEnabled && company.showAllowSwitch
    }
    val hostList = remember(company.hosts) {
        company.hosts.filter { it.isNotBlank() }.sorted()
    }
    val switchVisualLabel = stringResource(R.string.feed_block_company, company.name)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .testTag("company-detail-dialog"),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = company.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (categoryTitle.isNotEmpty()) {
                            Text(
                                text = categoryTitle,
                                modifier = Modifier.padding(top = 2.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close_white_24dp),
                            contentDescription = stringResource(R.string.action_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (unblockedSection != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            CompanyStatusBadge(blocked = company.statusBlocked, size = 36.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = company.status,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    text = company.statusNote
                                        ?: stringResource(R.string.category_unblocked_warning),
                                    modifier = Modifier.padding(top = 4.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { onBlockCategory(unblockedSection.categoryName) },
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_shield_check),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.feed_block_category_action, unblockedSection.title)
                        )
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            CompanyStatusBadge(blocked = company.statusBlocked, size = 36.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = company.status,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (company.statusBlocked) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                if (!company.statusNote.isNullOrEmpty()) {
                                    Text(
                                        text = company.statusNote,
                                        modifier = Modifier.padding(top = 4.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (company.showAllowSwitch) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            TrackerSwitch(
                                checked = company.blockSwitchChecked,
                                enabled = company.blockSwitchEnabled,
                                contentDescription = company.blockSwitchLabel,
                                onCheckedChange = onBlockCompanyChanged,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                label = switchVisualLabel
                            )
                        }
                    }
                }

                if (hostList.isNotEmpty() || !company.lastSeen.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    if (hostList.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.feed_observed_domains, hostList.size),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            hostList.forEach { host ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_domain),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = host,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            if (!company.lastSeen.isNullOrEmpty()) {
                                if (hostList.isNotEmpty()) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                                Text(
                                    text = stringResource(R.string.feed_last_seen_label, company.lastSeen),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (company.uncertain) {
                    Text(
                        text = stringResource(R.string.uncertain_entry),
                        modifier = Modifier.padding(top = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Normal,
                        fontStyle = FontStyle.Italic
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackerSwitch(
    checked: Boolean,
    enabled: Boolean,
    contentDescription: String,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    Row(
        modifier = modifier
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .semantics { this.contentDescription = contentDescription },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!label.isNullOrEmpty()) {
            Text(
                text = label,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
    }
}

@Composable
private fun TrackerShowMoreRow(
    row: TrackersRow.ShowMore,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = sectionChildShape(isLastInSection = true),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Text(
            text = row.text,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TrackersScreenPreview() {
    TrackerControlTheme {
        TrackersScreenContent(
            model = TrackersScreenModel(
                browserWarning = false,
                appStateTitle = "Protection",
                appStateValue = "Protected",
                appStateHint = "Tap to change protection",
                appStateHintAccent = true,
                librarySummary = "2 libraries detected",
                rows = listOf(
                    TrackersRow.Section(
                        key = "section:Analytics",
                        categoryName = "Analytics",
                        title = "Analytics",
                        lastContact = "Last contact 3 minutes ago",
                        explainer = null,
                        switchChecked = true,
                        switchEnabled = true,
                        switchDescription = "Toggle blocking Analytics"
                    ),
                    TrackersRow.Company(
                        key = "company:Analytics:example",
                        categoryName = "Analytics",
                        blockingKey = "example",
                        name = "Example Analytics",
                        lastSeen = "3 minutes ago",
                        status = "Blocked",
                        statusBlocked = true,
                        hosts = listOf("tracker.example"),
                        uncertain = false,
                        showAllowSwitch = true,
                        blockSwitchLabel = "Block Example Analytics in Example App",
                        blockSwitchChecked = true,
                        blockSwitchEnabled = true,
                        statusNote = null
                    )
                )
            ),
            callbacks = NoOpTrackersCallbacks
        )
    }
}

private object NoOpTrackersCallbacks : TrackersScreenCallbacks {
    override fun onAppStateClick() = Unit
    override fun onLibrariesClick() = Unit
    override fun onSectionToggle(categoryName: String, checked: Boolean) = Unit
    override fun onCompanyBlockChanged(blockingKey: String, blocked: Boolean) = Unit
    override fun onShowMore(categoryName: String) = Unit
    override fun onRefresh() = Unit
}
