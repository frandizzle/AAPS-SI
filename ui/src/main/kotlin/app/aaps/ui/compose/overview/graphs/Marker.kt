package app.aaps.ui.compose.overview.graphs

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
        margins = Insets(top = 8.dp),
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
                val bgTarget = targets.firstOrNull { it.toString().contains("layerIndex=0") }
                    ?: targets.firstOrNull()
                    ?: return@ValueFormatter ""

                val x = bgTarget.x
                val timestamp = minTimestamp + (x * 60000).toLong()
                val data = getDetails(timestamp) ?: return@ValueFormatter ""

                // Build a SpannableStringBuilder so we can color just the BG number.
                val bgStr = "%.1f".format(data.bgValue)
                val builder = SpannableStringBuilder()
                builder.append(data.time)
                builder.append("\n🩸 ")
                val bgStart = builder.length
                builder.append(bgStr)
                val bgEnd = builder.length
                builder.setSpan(
                    ForegroundColorSpan(data.bgColor.toArgb()),
                    bgStart,
                    bgEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builder.append(" ")
                builder.append(data.deltaText)
                builder.append("\n💉 ")
                builder.append(data.iobText)
                builder
            }
        },
        // 👇 Pin the label to the top of the chart so it only moves horizontally
        // while scrubbing, instead of jumping up/down with the BG curve height.
        labelPosition = DefaultCartesianMarker.LabelPosition.Top,
        guideline = guideline,
    )
}