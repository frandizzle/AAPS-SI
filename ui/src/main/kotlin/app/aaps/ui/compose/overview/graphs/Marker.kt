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
    val deltaText: String,
    val iobText: String
)

@Composable
fun rememberMarker(
    minTimestamp: Long,
    isVisible: Boolean, // 👇 Add this parameter
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
            // 👇 Ensures text aligns nicely on the left
            textAlign = androidx.compose.ui.text.style.TextAlign.Start
        ),
        background = labelBackground,
        padding = Insets(horizontal = 8.dp, vertical = 4.dp),
        // 👇 The 48dp margin pushes it up above your thumb
        margins = Insets(bottom = 48.dp),
        // 👇 CRITICAL: Allows all 3 lines to show
        lineCount = 3
    )

    val guideline = rememberLineComponent(
        // 👇 Hide the line unless we are scrubbing
        fill = Fill(if (isVisible) onSurfaceColor.copy(alpha = 0.2f) else Color.Transparent),
        thickness = 2.dp,
    )

    return rememberDefaultCartesianMarker(
        label = label,
        valueFormatter = remember(minTimestamp, getDetails, isVisible) {
            DefaultCartesianMarker.ValueFormatter { _, targets ->
                // 👇 Hide the text box completely unless we are scrubbing
                if (!isVisible) return@ValueFormatter ""

                val target = targets.firstOrNull() ?: return@ValueFormatter ""
                val x = target.x
                val timestamp = minTimestamp + (x * 60000).toLong()
                val data = getDetails(timestamp) ?: return@ValueFormatter ""

                "${data.time}\n🩸 %.1f ${data.deltaText}\n💉 ${data.iobText}".format(data.bgValue)
            }
        },
        labelPosition = DefaultCartesianMarker.LabelPosition.AroundPoint,
        guideline = guideline,
    )
}
