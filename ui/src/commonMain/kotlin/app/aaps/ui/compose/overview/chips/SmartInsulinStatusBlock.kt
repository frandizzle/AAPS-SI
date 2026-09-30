package app.aaps.ui.compose.overview.chips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview

private val SiGreen = Color(0xFF43A047)
private val SiAmber = Color(0xFFFB8C00)
private val SiRed = Color(0xFFE53935)
private val SiPurple = Color(0xFF9C27B0)
private val SiBlue = Color(0xFF64B5F6)

/**
 * SmartInsulin's meal mode, pending pre-boluses and learning state, shown under the running-mode /
 * profile / temp-target chips. Same layout and colours as the earlier 4.0 SmartInsulin build.
 */
@Composable
fun SmartInsulinStatusBlock(state: SmartInsulinOverview.OverviewState, modifier: Modifier = Modifier) {
    val labelColor = MaterialTheme.colorScheme.onSurface
    val isFasting = state.modeLine.contains("Fasting", ignoreCase = true)
    val mealValueColor = when {
        isFasting                                                  -> MaterialTheme.colorScheme.onSurfaceVariant
        state.modeLine.contains("Protein", ignoreCase = true) ||
            state.modeLine.contains("P/F", ignoreCase = true)      -> SiPurple
        else                                                       -> MaterialTheme.colorScheme.primary
    }

    val (learningValue, learningColor) = learningDisplay(state.learningState)

    Column(modifier = modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = if (isFasting) MaterialTheme.colorScheme.onSurfaceVariant else labelColor)) { append("Meal: ") }
                withStyle(SpanStyle(color = mealValueColor)) { append(state.modeLine.removePrefix("Meal:").trim()) }
            },
            fontSize = 13.sp,
            maxLines = 1
        )
        state.pb2Line?.let { Text(it, fontSize = 13.sp, color = SiBlue, maxLines = 1) }
        state.pb3Line?.let { Text(it, fontSize = 13.sp, color = SiBlue, maxLines = 1) }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = labelColor)) { append("State: ") }
                withStyle(SpanStyle(color = learningColor)) { append(learningValue) }
            },
            fontSize = 13.sp,
            maxLines = 2
        )
    }
}

private fun learningDisplay(learningState: String): Pair<String, Color> = when {
    learningState.equals("learning", ignoreCase = true) -> "Learning" to SiGreen
    learningState.startsWith("limited: P/F")            -> "Limited (P/F mode)" to SiPurple
    learningState.startsWith("limited")                 -> "Limited (meal mode)" to SiAmber
    learningState.startsWith("off:")                    -> {
        val reason = learningState.removePrefix("off:").trim()
        val text = when {
            reason.startsWith("Post-meal")         -> {
                val mins = reason.removePrefix("Post-meal").trim().removeSuffix("left").trim().removeSuffix("m").trim()
                if (mins.isEmpty()) "Post-meal pause" else "Post-meal pause — ${mins}m remaining"
            }

            reason.startsWith("High temp")         -> "Temp target active"
            reason.startsWith("CGM")               -> "CGM warmup"
            reason.startsWith("Learning disabled") -> "Disabled"
            else                                   -> reason
        }
        text to SiRed
    }

    else                                                -> learningState to SiGreen
}
