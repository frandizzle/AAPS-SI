package app.aaps.ui.compose.overview.chips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.overview.graph.TbrState
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.icons.IcNoTbr
import app.aaps.core.ui.compose.icons.IcTbrHigh
import app.aaps.core.ui.compose.icons.IcTbrLow

/**
 * The SmartInsulin overview's chip row: IOB, current basal rate and last SMB side by side across the
 * full width, as in the earlier 4.0 SmartInsulin build.
 */
@Composable
fun SmartInsulinChipsRow(
    iobUiState: IobUiState,
    tbrState: TbrState,
    basalRateText: String,
    cobUiState: CobUiState,
    onIobChipClick: () -> Unit,
    onTbrChipClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IobChip(state = iobUiState, onClick = onIobChipClick, modifier = Modifier.weight(0.8f))
        BasalRateChip(tbrState = tbrState, text = basalRateText, onClick = onTbrChipClick, modifier = Modifier.weight(0.85f))
        CobChip(state = cobUiState, modifier = Modifier.weight(1.3f))
    }
}

@Composable
private fun BasalRateChip(tbrState: TbrState, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = AapsTheme.elementColors.tempBasal
    val haptic = LocalHapticFeedback.current
    Surface(
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
        color = color.copy(alpha = 0.2f),
        modifier = modifier.height(AapsSpacing.chipHeight)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = AapsSpacing.medium)
        ) {
            Icon(
                imageVector = when (tbrState) {
                    TbrState.HIGH -> IcTbrHigh
                    TbrState.LOW  -> IcTbrLow
                    TbrState.NONE -> IcNoTbr
                },
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(AapsSpacing.chipIconSize)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}
