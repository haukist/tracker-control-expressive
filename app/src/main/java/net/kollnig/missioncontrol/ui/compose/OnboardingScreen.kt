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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.kollnig.missioncontrol.R

data class OnboardingSlideUi(
    val titleResId: Int,
    val title: String,
    val description: String,
    val iconResId: Int,
    val actionLabel: String? = null,
    val actionEnabled: Boolean = false,
    val warningResId: Int = 0,
    val isBlockingModeSlide: Boolean = false,
)

data class OnboardingScreenModel(
    val slides: List<OnboardingSlideUi> = emptyList(),
    val selectedBlockingOption: String = "standard",
    val isPlayStoreBuild: Boolean = false,
)

interface OnboardingScreenCallbacks {
    fun onSlideAction(titleResId: Int)
    fun onSelectBlockingOption(option: String)
    fun onNextOrFinish(currentPage: Int, slide: OnboardingSlideUi)
}

@Stable
class OnboardingScreenController {
    var model by mutableStateOf(OnboardingScreenModel())
        private set

    var requestedPage by mutableIntStateOf(-1)
        private set

    fun update(newModel: OnboardingScreenModel) {
        model = newModel
    }

    fun requestPage(page: Int) {
        requestedPage = page
    }

    internal fun clearPageRequest() {
        requestedPage = -1
    }
}

object OnboardingScreen {
    @JvmStatic
    fun install(
        composeView: ComposeView,
        callbacks: OnboardingScreenCallbacks,
    ): OnboardingScreenController {
        val controller = OnboardingScreenController()
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            TrackerControlTheme {
                OnboardingScreenContent(
                    controller = controller,
                    callbacks = callbacks,
                )
            }
        }
        return controller
    }
}

@Composable
fun OnboardingScreenContent(
    controller: OnboardingScreenController,
    callbacks: OnboardingScreenCallbacks,
) {
    val model = controller.model
    val slides = model.slides
    val pagerState = rememberPagerState(pageCount = { slides.size.coerceAtLeast(1) })

    LaunchedEffect(controller.requestedPage) {
        val target = controller.requestedPage
        if (target in slides.indices) {
            pagerState.animateScrollToPage(target)
            controller.clearPageRequest()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (slides.isNotEmpty()) {
                LinearProgressIndicator(
                    progress = { (pagerState.currentPage + 1) / slides.size.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        },
        bottomBar = {
            if (slides.isNotEmpty()) {
                val isFirst = pagerState.currentPage == 0
                val isLast = pagerState.currentPage == slides.lastIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (!isFirst) {
                        OutlinedButton(
                            onClick = {
                                controller.requestPage((pagerState.currentPage - 1).coerceAtLeast(0))
                            },
                        ) {
                            Text(stringResource(R.string.title_previous))
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        slides.indices.forEach { idx ->
                            Box(
                                modifier = Modifier
                                    .size(if (idx == pagerState.currentPage) 10.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (idx == pagerState.currentPage) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        }
                                    ),
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val slide = slides[pagerState.currentPage]
                            callbacks.onNextOrFinish(pagerState.currentPage, slide)
                        },
                    ) {
                        Text(
                            stringResource(
                                if (isLast) R.string.title_finish else R.string.title_next
                            )
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        if (slides.isNotEmpty()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) { page ->
                val slide = slides[page]
                if (slide.isBlockingModeSlide) {
                    BlockingModeSlideContent(
                        slide = slide,
                        selectedOption = model.selectedBlockingOption,
                        isPlayStoreBuild = model.isPlayStoreBuild,
                        onSelectOption = callbacks::onSelectBlockingOption,
                    )
                } else {
                    StandardSlideContent(
                        slide = slide,
                        onAction = { callbacks.onSlideAction(slide.titleResId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StandardSlideContent(
    slide: OnboardingSlideUi,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(96.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(slide.iconResId),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = slide.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = slide.description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (!slide.actionLabel.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(24.dp))
            FilledTonalButton(
                onClick = onAction,
                enabled = slide.actionEnabled,
            ) {
                Text(slide.actionLabel)
            }
        }
    }
}

private data class BlockingOptionItem(
    val key: String,
    val labelRes: Int,
    val descRes: Int,
)

@Composable
private fun BlockingModeSlideContent(
    slide: OnboardingSlideUi,
    selectedOption: String,
    isPlayStoreBuild: Boolean,
    onSelectOption: (String) -> Unit,
) {
    val options = buildList {
        add(
            BlockingOptionItem(
                "minimal",
                R.string.onboarding_blockingmode_minimal_label,
                R.string.onboarding_blockingmode_minimal_desc,
            )
        )
        if (!isPlayStoreBuild) {
            add(
                BlockingOptionItem(
                    "standard",
                    R.string.onboarding_blockingmode_standard_label,
                    R.string.onboarding_blockingmode_standard_desc,
                )
            )
            add(
                BlockingOptionItem(
                    "strict",
                    R.string.onboarding_blockingmode_strict_label,
                    R.string.onboarding_blockingmode_strict_desc,
                )
            )
        }
        add(
            BlockingOptionItem(
                "research",
                R.string.onboarding_blockingmode_research_label,
                R.string.onboarding_blockingmode_research_desc,
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = slide.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = slide.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ExpressiveGroupedColumn {
            options.forEachIndexed { index, option ->
                val selected = selectedOption == option.key
                ExpressiveGroupedSurface(
                    index = index,
                    count = options.size,
                    color = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.clickable { onSelectOption(option.key) },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { onSelectOption(option.key) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(option.labelRes),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(option.descRes),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
