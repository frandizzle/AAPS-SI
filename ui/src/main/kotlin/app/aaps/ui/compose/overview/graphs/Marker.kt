package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun rememberMarker(
    minTimestamp: Long,
    getDetails: (Long) -> String?
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
                getDetails(timestamp) ?: ""
            }
        },
        guideline = guideline,
    )
}
