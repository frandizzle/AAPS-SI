package app.aaps.ui.compose.overview.chips

import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.navigation.ElementType
import app.aaps.core.ui.compose.navigation.color
import app.aaps.ui.compose.overview.graphs.TbrArrow
import app.aaps.ui.compose.overview.graphs.TbrUiState
import java.util.Locale

@Composable
internal fun TbrChip(
    modifier: Modifier = Modifier,
    state: TbrUiState,
    showIcon: Boolean = true
) {
    Surface(
        shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
        color = ElementType.INSULIN.color().copy(alpha = 0.15f),
        modifier = modifier.height(AapsSpacing.chipHeight)
    ) {
        val chipStyle = MaterialTheme.typography.bodySmall // Adjust to match Iob/Cob chips
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)
        ) {
            if (showIcon) {
                Icon(
                    imageVector = state.arrow.icon,
                    contentDescription = null,
                    tint = ElementType.INSULIN.color(),
                    modifier = Modifier.size(AapsSpacing.chipIconSize)
                )
            }
            Text(
                text = String.format(Locale.getDefault(), "%.2f U/h", state.rate),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = chipStyle,
                modifier = Modifier.padding(start = if (showIcon) AapsSpacing.medium else 0.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TbrChipUpPreview() {
    MaterialTheme {
        TbrChip(state = TbrUiState(rate = 1.5, profileBasal = 1.0, arrow = TbrArrow.UP))
    }
}

@Preview(showBackground = true)
@Composable
private fun TbrChipFlatPreview() {
    MaterialTheme {
        TbrChip(state = TbrUiState(rate = 1.0, profileBasal = 1.0, arrow = TbrArrow.FLAT))
    }
}
