package app.aaps.plugins.aps.smartInsulin

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.smartInsulin.FoodLog
import app.aaps.core.interfaces.smartInsulin.FoodLogEntry
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
 * @param onChange changes how much of an entry was eaten: the entry as it was, and as it is now
 */
@Composable
fun FoodLogSection(
    day: SmartInsulinPlugin.FoodDay, daysBack: Int, onDaysBack: (Int) -> Unit, onDelete: (Long, String) -> Unit, onChange: (FoodLogEntry, FoodLogEntry) -> Unit
) {
    var changing by remember { mutableStateOf<FoodLogEntry?>(null) }
    val colors = AapsTheme.generalColors
    val dayFmt = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    // The phone's own choice of 12 or 24 hour time, for example "3:44 pm".
    val context = LocalContext.current
    val timeFmt = remember { DateFormat.getTimeFormat(context) }
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { changing = e }) {
            Text(timeFmt.format(Date(e.timeMs)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(e.name, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    "%.0f g carbs · %.0f g protein · %.0f g fat".format(e.carbsG, e.proteinG, e.fatG),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("%,.0f kcal".format(e.kcal), style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { onDelete(e.timeMs, e.name) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        }
    }
    if (day.entries.isNotEmpty())
        Text("Tap an entry to change how much was eaten.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    changing?.let { e -> AmountDialog(e, onSave = { onChange(e, it); changing = null }, onDismiss = { changing = null }) }
}

/**
 * How much of a logged food was really eaten, as a share of what was logged: ×0.5 for half, ×2 for twice. When the
 * entry kept its foods, each is listed with its weight, carbs, protein and fat, and can be changed on its own, for
 * example half the chips and all the chicken. The top control sets all of them together.
 */
@Composable
private fun AmountDialog(entry: FoodLogEntry, onSave: (FoodLogEntry) -> Unit, onDismiss: () -> Unit) {
    val count = entry.items.size.coerceAtLeast(1)
    var factors by remember { mutableStateOf(List(count) { 1.0 }) }
    val changed = entry.withAmounts(factors)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How much was eaten?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (entry.items.isEmpty()) {
                    Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                    SiSpacer(8)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.items.isNotEmpty()) Text("All", style = MaterialTheme.typography.titleMedium)
                    // Mixed amounts show no single factor; a step then starts from all of it.
                    AmountStepper(factors.distinct().singleOrNull(), min = AMOUNT_STEP, large = true) { f -> factors = List(count) { f } }
                    Text("%,.0f kcal".format(changed.kcal), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
                }
                entry.items.forEachIndexed { i, item ->
                    val now = item.times(factors[i])
                    SiSpacer(10)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        AmountStepper(factors[i], min = 0.0, large = false) { f -> factors = factors.toMutableList().also { it[i] = f } }
                    }
                    Text(
                        "%.0f g · %.0f g carbs · %.0f g protein · %.0f g fat".format(now.grams, now.carbsG, now.proteinG, now.fatG),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(changed) }, enabled = factors.any { it != 1.0 } && changed.kcal > 0.0) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** − ×1.00 +, in steps of [AMOUNT_STEP] from [min] to [MAX_AMOUNT]. A null [factor] shows none and steps from 1. */
@Composable
private fun AmountStepper(factor: Double?, min: Double, large: Boolean, onChange: (Double) -> Unit) {
    val from = factor ?: 1.0
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange((from - AMOUNT_STEP).coerceAtLeast(min)) }) { Icon(Icons.Filled.Remove, contentDescription = "Less") }
        Text(
            factor?.let { "×%.2f".format(it) } ?: "×–",
            style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall
        )
        IconButton(onClick = { onChange((from + AMOUNT_STEP).coerceAtMost(MAX_AMOUNT)) }) { Icon(Icons.Filled.Add, contentDescription = "More") }
    }
}

private const val AMOUNT_STEP = 0.25
private const val MAX_AMOUNT = 5.0

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
