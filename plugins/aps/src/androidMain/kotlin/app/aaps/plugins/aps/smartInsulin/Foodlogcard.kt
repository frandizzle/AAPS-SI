package app.aaps.plugins.aps.smartInsulin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.smartInsulin.FoodLog
import app.aaps.core.ui.compose.AapsTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Today's food: calories against the daily goal with a bar that fills, carbs, fat and protein the same way,
 * and what was eaten. The arrows step back through the last [FoodLog.DAYS_KEPT] days.
 *
 * @param daysBack 0 for today
 * @param onDelete asks before removing an entry
 */
@Composable
fun FoodLogSection(day: SmartInsulinPlugin.FoodDay, daysBack: Int, onDaysBack: (Int) -> Unit, onDelete: (Long, String) -> Unit) {
    val colors = AapsTheme.generalColors
    val dayFmt = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val kcal = day.entries.sumOf { it.kcal }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = { onDaysBack(daysBack + 1) }, enabled = daysBack < FoodLog.DAYS_KEPT - 1) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Day before")
        }
        Text(
            when (daysBack) {
                0    -> "Today"
                1    -> "Yesterday"
                else -> dayFmt.format(Date(day.startMs))
            },
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { onDaysBack(daysBack - 1) }, enabled = daysBack > 0) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Day after")
        }
    }

    // Calories, large, as the main number of the card.
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
        Text("%,.0f kcal".format(kcal), style = MaterialTheme.typography.headlineMedium)
        if (day.goalKcal > 0) {
            Text(
                " / %,d".format(day.goalKcal), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp).weight(1f)
            )
            val left = day.goalKcal - kcal
            Text(
                if (left >= 0) "%,.0f left".format(left) else "%,.0f over".format(-left),
                style = MaterialTheme.typography.titleSmall,
                color = if (left >= 0) MaterialTheme.colorScheme.onSurface else colors.statusCritical,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }
    }
    if (day.goalKcal > 0) {
        SiSpacer(8)
        GoalBar(kcal / day.goalKcal, MaterialTheme.colorScheme.primary, height = 12)
    }

    SiSpacer(14)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        MacroColumn("Carbs", day.entries.sumOf { it.carbsG }, day.goalCarbs, AapsTheme.elementColors.carbs, Modifier.weight(1f))
        MacroColumn("Fat", day.entries.sumOf { it.fatG }, day.goalFat, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
        MacroColumn("Protein", day.entries.sumOf { it.proteinG }, day.goalProtein, AapsTheme.elementColors.insulin, Modifier.weight(1f))
    }

    SiSpacer(14)
    if (day.entries.isEmpty())
        Text(
            "Nothing logged. Food from Smart Meal is logged when you start or add to a meal, or with Log only.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    day.entries.asReversed().forEach { e ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(timeFmt.format(Date(e.timeMs)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(e.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                Text(
                    "%.0f g carbs · %.0f g protein · %.0f g fat".format(e.carbsG, e.proteinG, e.fatG),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("%,.0f kcal".format(e.kcal), style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { onDelete(e.timeMs, e.name) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        }
    }
}

@Composable
private fun MacroColumn(label: String, grams: Double, goal: Int, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("%.0f g".format(grams), style = MaterialTheme.typography.titleMedium)
            if (goal > 0) Text(" / $goal", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (goal > 0) {
            SiSpacer(6)
            GoalBar(grams / goal, color, height = 8)
        }
    }
}

/** Fills to the goal. Past it, the bar is full and turns to the warning colour, then the critical one from [OVER_CRITICAL]. */
@Composable
private fun GoalBar(fraction: Double, color: Color, height: Int) {
    val colors = AapsTheme.generalColors
    val barColor = when {
        fraction >= OVER_CRITICAL -> colors.statusCritical
        fraction >= 1.0           -> colors.statusWarning
        else                      -> color
    }
    LinearProgressIndicator(
        progress = { fraction.toFloat().coerceIn(0f, 1f) },
        color = barColor,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
        modifier = Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(50))
    )
}

private const val OVER_CRITICAL = 1.1
