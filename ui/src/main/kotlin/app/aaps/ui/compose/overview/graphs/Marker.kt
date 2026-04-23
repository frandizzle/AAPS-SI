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
        style = TextStyle(color = onSurfaceColor, fontSize = 13.sp),
        background = labelBackground,
        padding = Insets(horizontal = 8.dp, vertical = 4.dp),
        // 👇 Add this line to push the box upward
        margins = Insets(bottom = 48.dp)
    )
    
    val guideline = rememberLineComponent(
        fill = Fill(onSurfaceColor.copy(alpha = 0.2f)),
        thickness = 2.dp,
    )

    return rememberDefaultCartesianMarker(
        label = label,
        valueFormatter = remember(minTimestamp, getDetails) {
            DefaultCartesianMarker.ValueFormatter { _, targets ->
                val target = targets.firstOrNull() ?: return@ValueFormatter ""
                val x = target.x

                val timestamp = minTimestamp + (x * 60000).toLong()
                val data = getDetails(timestamp) ?: return@ValueFormatter ""

                "${data.time}\n🩸 %.1f ${data.deltaText}\n💉 ${data.iobText}".format(data.bgValue)
            }
        },
        // 👇 Changed from Top to AroundPoint to stop it from squishing the graph
        labelPosition = DefaultCartesianMarker.LabelPosition.AroundPoint,
        guideline = guideline,
    )
}
