package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.Insets
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent

/**
 * Data structure for the interactive graph tooltip.
 */
data class MarkerData(
    val time: String,
    val bgValue: Double,
    val bgColor: Color,
    val rangeEmoji: String,
    val deltaText: String,
    val iobText: String
)

@Composable
fun rememberMarker(
    minTimestamp: Long,
    isVisible: Boolean,
    getDetails: (Long) -> MarkerData?
): DefaultCartesianMarker {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    val labelBackground = remember(surfaceColor, outlineColor) {
        ShapeComponent(
            fill = Fill(surfaceColor),
            shape = RoundedCornerShape(4.dp),
            strokeFill = Fill(outlineColor),
            strokeThickness = 1.dp
        )
    }

    val label = rememberTextComponent(
        style = TextStyle(
            color = onSurfaceColor,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Start
        ),
        background = labelBackground,
        padding = Insets(horizontal = 8.dp, vertical = 4.dp),
        // No top margin — with AroundPoint, any top margin gets baked into the
        // chart's reserved insets and eats visible plot area.
        lineCount = 3
    )

    val guideline = rememberLineComponent(
        fill = Fill(if (isVisible) onSurfaceColor.copy(alpha = 0.2f) else Color.Transparent),
        thickness = 2.dp,
    )

    return rememberDefaultCartesianMarker(
        label = label,
        valueFormatter = remember(minTimestamp, getDetails, isVisible) {
            DefaultCartesianMarker.ValueFormatter { _, targets ->
                if (!isVisible) return@ValueFormatter ""
                val bgTarget = targets.firstOrNull() ?: return@ValueFormatter ""
                val x = bgTarget.x
                val timestamp = minTimestamp + (x * 60000).toLong()
                val data = getDetails(timestamp) ?: return@ValueFormatter ""

                "${data.time}\n${data.rangeEmoji} %.1f ${data.deltaText}\n💉 ${data.iobText}"
                    .format(data.bgValue)
            }
        },
        // AroundPoint: label centered on the target point, no top-space
        // reservation. The chart keeps its full height; the label can draw
        // outside the layer bounds because composables aren't clipped.
        labelPosition = DefaultCartesianMarker.LabelPosition.AroundPoint,
        guideline = guideline,
    )
}