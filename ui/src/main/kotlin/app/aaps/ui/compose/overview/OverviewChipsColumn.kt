package app.aaps.ui.compose.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TT
import app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview
import app.aaps.core.ui.compose.icons.IcSettingsOff
import app.aaps.core.ui.compose.navigation.ElementType
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.ui.compose.main.TempTargetChipState
import app.aaps.ui.compose.overview.chips.IobCobChipsRow
import app.aaps.ui.compose.overview.chips.ProfileChip
import app.aaps.ui.compose.overview.chips.RunningModeChip
import app.aaps.ui.compose.overview.chips.TempTargetChip
import app.aaps.ui.compose.overview.graphs.IobUiState
import app.aaps.ui.compose.overview.graphs.TbrUiState

@Composable
fun OverviewChipsColumn(
    runningMode: RM.Mode,
    runningModeText: String,
    runningModeProgress: Float,
    isSimpleMode: Boolean,
    profileName: String,
    isProfileModified: Boolean,
    profileProgress: Float,
    tempTargetText: String,
    tempTargetState: TempTargetChipState,
    tempTargetProgress: Float,
    tempTargetReason: TT.Reason?,
    iobUiState: IobUiState,
    tbrUiState: TbrUiState,
    onNavigate: (NavigationRequest) -> Unit,
    modifier: Modifier = Modifier,
    siOverviewState: SmartInsulinOverview.OverviewState? = null,
    trailingContent: @Composable (RowScope.() -> Unit)? = null
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (trailingContent != null) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val chipsWidth = (maxWidth * 0.4f).coerceIn(140.dp, 220.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.width(chipsWidth),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        NarrowChips(
                            runningMode = runningMode,
                            runningModeText = runningModeText,
                            runningModeProgress = runningModeProgress,
                            isSimpleMode = isSimpleMode,
                            profileName = profileName,
                            isProfileModified = isProfileModified,
                            profileProgress = profileProgress,
                            tempTargetText = tempTargetText,
                            tempTargetState = tempTargetState,
                            tempTargetProgress = tempTargetProgress,
                            tempTargetReason = tempTargetReason,
                            tbrUiState = tbrUiState,
                            onNavigate = onNavigate
                        )
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        content = trailingContent
                    )
                }
            }
        } else {
            NarrowChips(
                runningMode = runningMode,
                runningModeText = runningModeText,
                runningModeProgress = runningModeProgress,
                isSimpleMode = isSimpleMode,
                profileName = profileName,
                isProfileModified = isProfileModified,
                profileProgress = profileProgress,
                tempTargetText = tempTargetText,
                tempTargetState = tempTargetState,
                tempTargetProgress = tempTargetProgress,
                tempTargetReason = tempTargetReason,
                tbrUiState = tbrUiState,
                onNavigate = onNavigate
            )
        }
        IobCobChipsRow(
            iobUiState = iobUiState,
            tbrUiState = tbrUiState
        )
        siOverviewState?.let { SmartInsulinStatusChip(state = it) }
    }
}

@Composable
private fun SmartInsulinStatusChip(state: SmartInsulinOverview.OverviewState) {
    val whiteColor = MaterialTheme.colorScheme.onSurface

    val mealValueColor = when {
        state.modeLine.contains("Fasting", ignoreCase = true) ->
            MaterialTheme.colorScheme.onSurfaceVariant
        state.modeLine.contains("Protein", ignoreCase = true) ||
            state.modeLine.contains("P/F", ignoreCase = true) ->
            Color(0xFF9C27B0)
        else ->
            MaterialTheme.colorScheme.primary
    }

    val learningColor: Color
    val learningValue: String
    when {
        state.learningState == "Learning" -> {
            learningColor = Color(0xFF43A047)
            learningValue = "Learning"
        }
        state.learningState == "limited: P/F mode" -> {
            learningColor = Color(0xFF9C27B0)
            learningValue = "Limited (P/F mode)"
        }
        state.learningState.startsWith("limited") -> {
            learningColor = Color(0xFFFB8C00)
            learningValue = "Limited (meal mode)"
        }
        state.learningState.startsWith("off:") -> {
            learningColor = Color(0xFFE53935)
            val reason = state.learningState.removePrefix("off:").trim()
            learningValue = when {
                reason.startsWith("Post-meal") -> {
                    val mins = reason.removePrefix("Post-meal").trim().removeSuffix("left").trim().removeSuffix("m").trim()
                    "Post-meal pause — ${mins}m remaining"
                }
                reason.startsWith("Activity") -> reason
                reason.startsWith("High temp") -> "Temp target active"
                reason.startsWith("CGM") -> "CGM warmup"
                reason.startsWith("Learning disabled") -> "Disabled"
                else -> reason
            }
        }
        else -> {
            learningColor = Color(0xFF43A047)
            learningValue = state.learningState
        }
    }

    val mealValue = state.modeLine.removePrefix("Meal:").trim()
    val isFasting = state.modeLine.contains("Fasting", ignoreCase = true)

    Column(
        modifier = Modifier.padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        BasicText(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = if (isFasting) MaterialTheme.colorScheme.onSurfaceVariant else whiteColor)) {
                    append("Meal: ")
                }
                withStyle(SpanStyle(color = mealValueColor)) {
                    append(mealValue)
                }
            },
            style = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
            maxLines = 1
        )
        state.pb2Line?.let { pb2 ->
            Text(text = pb2, fontSize = 14.sp, color = Color(0xFFFB8C00), maxLines = 1)
        }
        BasicText(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = whiteColor)) {
                    append("State: ")
                }
                withStyle(SpanStyle(color = learningColor)) {
                    append(learningValue)
                }
            },
            style = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
            maxLines = 2
        )
    }
}

@Composable
private fun NarrowChips(
    runningMode: RM.Mode,
    runningModeText: String,
    runningModeProgress: Float,
    isSimpleMode: Boolean,
    profileName: String,
    isProfileModified: Boolean,
    profileProgress: Float,
    tempTargetText: String,
    tempTargetState: TempTargetChipState,
    tempTargetProgress: Float,
    tempTargetReason: TT.Reason?,
    tbrUiState: TbrUiState,
    onNavigate: (NavigationRequest) -> Unit
) {
    if (runningModeText.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RunningModeChip(
                mode = runningMode,
                text = runningModeText,
                progress = runningModeProgress,
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(NavigationRequest.Element(ElementType.RUNNING_MODE)) }
            )
            if (isSimpleMode) {
                Icon(
                    imageVector = IcSettingsOff,
                    contentDescription = stringResource(app.aaps.core.ui.R.string.simple_mode),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(20.dp)
                )
            }
        }
    }
    if (profileName.isNotEmpty()) {
        ProfileChip(
            profileName = profileName,
            isModified = isProfileModified,
            progress = profileProgress,
            onClick = { onNavigate(NavigationRequest.Element(ElementType.PROFILE_MANAGEMENT)) }
        )
    }
    if (tempTargetText.isNotEmpty()) {
        TempTargetChip(
            targetText = tempTargetText,
            state = tempTargetState,
            progress = tempTargetProgress,
            reason = tempTargetReason,
            onClick = { onNavigate(NavigationRequest.Element(ElementType.TEMP_TARGET_MANAGEMENT)) }
        )
    }
}

