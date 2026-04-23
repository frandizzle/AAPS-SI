package app.aaps.ui.compose.overview.graphs

import android.text.Spannable
import android.text.SpannableStringBuilder
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
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent

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
    val labelBackgroundColor = MaterialTheme.colorScheme.surface
    val labelBackground = rememberShapeComponent(
        fill = Fill(labelBackgroundColor),
        shape = RoundedCornerShape(4.dp),
    )
    val label = rememberTextComponent(
        style = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp),
        background = labelBackground,
        padding = Insets(horizontal = 8.dp, vertical = 4.dp),
    )
    
    val guideline = rememberLineComponent(
        fill = Fill(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)),
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

                SpannableStringBuilder().apply {
                    // Line 1: Time
                    append(data.time)
                    
                    // Line 2: BG (Delta)
                    append("\n\uD83E\uDE78 ") // Blood Drop Emoji
                    val bgStart = length
                    append("%.1f".format(data.bgValue))
                    setSpan(
                        ForegroundColorSpan(data.bgColor.toArgb()),
                        bgStart,
                        length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append(" ${data.deltaText}")
                    
                    // Line 3: IOB
                    append("\n\uD83D\uDC89 ") // Syringe Emoji
                    append(data.iobText)
                }
            }
        },
        guideline = guideline,
    )
}
