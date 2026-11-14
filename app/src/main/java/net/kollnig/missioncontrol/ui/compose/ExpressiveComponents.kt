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
 */
package net.kollnig.missioncontrol.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.kollnig.missioncontrol.R

private val GroupedOuterRadius = 24.dp
private val GroupedInnerRadius = 6.dp
val ExpressiveGroupSpacing = 2.dp

/**
 * Returns Material 3 Expressive grouped-card corner geometry:
 * outer edges are deeply rounded ([GroupedOuterRadius]) while shared inner edges
 * between adjacent items use tight corners ([GroupedInnerRadius]).
 */
fun groupedItemShape(index: Int, count: Int): RoundedCornerShape {
    if (count <= 1) return RoundedCornerShape(GroupedOuterRadius)
    return when (index) {
        0 -> RoundedCornerShape(
            topStart = GroupedOuterRadius,
            topEnd = GroupedOuterRadius,
            bottomStart = GroupedInnerRadius,
            bottomEnd = GroupedInnerRadius
        )
        count - 1 -> RoundedCornerShape(
            topStart = GroupedInnerRadius,
            topEnd = GroupedInnerRadius,
            bottomStart = GroupedOuterRadius,
            bottomEnd = GroupedOuterRadius
        )
        else -> RoundedCornerShape(GroupedInnerRadius)
    }
}

/**
 * Tonal surface item belonging to an Expressive grouped list.
 */
@Composable
fun ExpressiveGroupedSurface(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable () -> Unit
) {
    val shape = groupedItemShape(index, count)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape),
        shape = shape,
        color = color,
        contentColor = contentColor,
        content = content
    )
}

/**
 * Vertical group container with standard 2.dp expressive spacing between items.
 */
@Composable
fun ExpressiveGroupedColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ExpressiveGroupSpacing),
        content = content
    )
}

/**
 * Section label above an Expressive grouped card/list.
 */
@Composable
fun ExpressiveSectionLabel(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { heading() }
    )
}

/**
 * Compact Material 3 Expressive TopAppBar using tonal surface colors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressiveTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onNavigateUp: (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        navigationIcon = {
            if (onNavigateUp != null) {
                IconButton(onClick = onNavigateUp) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(android.R.string.cancel)
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        scrollBehavior = scrollBehavior
    )
}

/**
 * Collapsing medium-height Material 3 Expressive TopAppBar for detail/settings screens.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveMediumTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onNavigateUp: (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    MediumFlexibleTopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        subtitle = if (!subtitle.isNullOrBlank()) {
            {
                Text(
                    text = subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else null,
        navigationIcon = {
            if (onNavigateUp != null) {
                IconButton(onClick = onNavigateUp) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(android.R.string.cancel)
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        scrollBehavior = scrollBehavior
    )
}

/**
 * Morphing-polygon Material 3 Expressive loading indicator.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveLoadingIndicator(
    modifier: Modifier = Modifier,
    contained: Boolean = false
) {
    if (contained) {
        ContainedLoadingIndicator(modifier = modifier)
    } else {
        LoadingIndicator(modifier = modifier)
    }
}

/**
 * Tonal alert/warning card for actionable notices across screens.
 */
@Composable
fun ExpressiveWarningBanner(
    text: String,
    icon: Painter,
    modifier: Modifier = Modifier,
    isError: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = containerColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
