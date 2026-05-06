package app.aaps.core.ui.compose.preference

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.core.keys.interfaces.LongPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.PreferenceVisibilityContext
import kotlinx.coroutines.delay

/**
 * Helper function to add preference content inline in a LazyListScope.
 * Handles PreferenceSubScreenDef only.
 */
fun LazyListScope.addPreferenceContent(
    content: Any,
    onShowMessage: (String) -> Unit,
    sectionState: PreferenceSectionState? = null
) {
    when (content) {
        is PreferenceSubScreenDef -> addPreferenceSubScreenDef(content, onShowMessage, sectionState)
    }
}

/**
 * Helper function to add PreferenceSubScreenDef inline in a LazyListScope.
 * This displays as one collapsible card with main content and nested subscreens inside.
 * Content is rendered using the new pattern (no NavigablePreferenceContent interface).
 */
fun LazyListScope.addPreferenceSubScreenDef(
    def: PreferenceSubScreenDef,
    onShowMessage: (String) -> Unit,
    sectionState: PreferenceSectionState? = null
) {
    val sectionKey = "${def.key}_main"
    item(key = sectionKey) {
        val isExpanded = sectionState?.isExpanded(sectionKey) ?: false
        // Get visibility context from CompositionLocal
        val visibilityContext = LocalVisibilityContext.current

        if (shouldShowSubScreenInline(def, visibilityContext)) {
            CollapsibleCardSectionContent(
                titleResId = def.titleResId,
                summaryItems = getVisibleSummaryItems(def, visibilityContext),
                expanded = isExpanded,
                onToggle = { sectionState?.toggle(sectionKey, SectionLevel.TOP_LEVEL) },
                icon = def.icon
            ) {
                // Render items in order, preserving the original structure
                RenderPreferenceItems(
                    items = def.items,
                    parentKey = def.key,
                    onShowMessage = onShowMessage,
                    sectionState = sectionState,
                    visibilityContext = visibilityContext
                )
            }
        }
    }
}

/**
 * Helper composable to render a list of preference items with visibility support.
 */
@Composable
private fun RenderPreferenceItems(
    items: List<Any>,
    parentKey: String,
    onShowMessage: (String) -> Unit,
    sectionState: PreferenceSectionState?,
    visibilityContext: PreferenceVisibilityContext?
) {
    items.forEach { item ->
        when (item) {
            is PreferenceKey          -> {
                HighlightablePreference(preferenceKey = item.key) {
                    AdaptivePreferenceItem(
                        key = item,
                        onShowMessage = onShowMessage,
                        visibilityContext = visibilityContext
                    )
                }
            }

            is PreferenceSubScreenDef -> {
                if (shouldShowSubScreenInline(item, visibilityContext)) {
                    // Render nested subscreen as simple collapsible section (no extra card)
                    val subSectionKey = "${parentKey}_${item.key}"
                    val isSubExpanded = sectionState?.isExpanded(subSectionKey) ?: false

                    // Header without card (no icon for nested subscreens)
                    ClickablePreferenceCategoryHeader(
                        titleResId = item.titleResId,
                        summaryItems = getVisibleSummaryItems(item, visibilityContext),
                        expanded = isSubExpanded,
                        onToggle = { sectionState?.toggle(subSectionKey, SectionLevel.SUB_SECTION, parentKey = parentKey) },
                        insideCard = true,
                        icon = item.icon
                    )

                    // Content without card wrapper
                    if (isSubExpanded) {
                        if (item.items.isNotEmpty()) {
                            val theme = LocalPreferenceTheme.current
                            Column(
                                modifier = Modifier.padding(start = theme.nestedContentIndent)
                            ) {
                                AdaptivePreferenceList(
                                    items = item.items,
                                    onShowMessage = onShowMessage,
                                    visibilityContext = visibilityContext,
                                    onNavigateToSubScreen = null // Nested subscreens not supported here
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Helper composable to calculate visibility state for any PreferenceItem.
 */
@Composable
private fun calculateItemVisibility(
    item: Any,
    visibilityContext: PreferenceVisibilityContext?
): PreferenceVisibilityState {
    return when (item) {
        is PreferenceKey          -> {
            if (item is IntentPreferenceKey) {
                calculateIntentPreferenceVisibility(item, visibilityContext)
            } else {
                val engineeringModeOnly = when (item) {
                    is BooleanPreferenceKey -> item.engineeringModeOnly
                    is IntPreferenceKey     -> item.engineeringModeOnly
                    is LongPreferenceKey    -> item.engineeringModeOnly
                    else                    -> false
                }
                calculatePreferenceVisibility(item, engineeringModeOnly, visibilityContext)
            }
        }

        is PreferenceSubScreenDef -> {
            PreferenceVisibilityState(
                visible = shouldShowSubScreenInline(item, visibilityContext),
                enabled = true
            )
        }

        else                      -> PreferenceVisibilityState(visible = true, enabled = true)
    }
}

/**
 * Helper composable to get visible summary items for a subscreen.
 */
@Composable
private fun getVisibleSummaryItems(
    subScreen: PreferenceSubScreenDef,
    visibilityContext: PreferenceVisibilityContext?
): List<Int> {
    return subScreen.items.mapNotNull { item ->
        val visibility = calculateItemVisibility(item, visibilityContext)
        if (visibility.visible) {
            when (item) {
                is PreferenceKey          -> item.titleResId.takeIf { it != 0 }
                is PreferenceSubScreenDef -> item.titleResId.takeIf { it != 0 }
                else                      -> null
            }
        } else null
    }
}

/**
 * Determines if a subscreen should be shown based on hideParentScreenIfHidden logic
 * and whether it has any visible items.
 * Used in inline rendering context (AllPreferencesScreen).
 */
@Composable
private fun shouldShowSubScreenInline(
    subScreen: PreferenceSubScreenDef,
    visibilityContext: PreferenceVisibilityContext?
): Boolean {
    var anyItemVisible = false

    // First check mandatory items (hideParentScreenIfHidden)
    for (item in subScreen.items) {
        val visibility = calculateItemVisibility(item, visibilityContext)

        if (visibility.visible) {
            anyItemVisible = true
        }

        if (item is PreferenceKey && item.hideParentScreenIfHidden && !visibility.visible) {
            return false
        }
    }

    // If no hideParentScreenIfHidden items found, only show if at least one item is visible
    return anyItemVisible
}

/**
 * Wrapper that highlights a preference if it matches the LocalHighlightKey.
 * Shows a brief color flash animation to draw attention to the preference.
 */
@Composable
private fun HighlightablePreference(
    preferenceKey: String,
    content: @Composable () -> Unit
) {
    val highlightKey = LocalHighlightKey.current
    val shouldHighlight = highlightKey == preferenceKey

    var isHighlighted by remember { mutableStateOf(shouldHighlight) }

    // Animate highlight fade out
    LaunchedEffect(shouldHighlight) {
        if (shouldHighlight) {
            isHighlighted = true
            delay(2000) // Keep highlight for 2 seconds
            isHighlighted = false
        }
    }

    val highlightColor by animateColorAsState(
        targetValue = if (isHighlighted) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 500),
        label = "highlightColor"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(highlightColor)
    ) {
        content()
    }
}
