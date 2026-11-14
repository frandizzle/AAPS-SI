package app.aaps.ui.compose.overview.chips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

/**
 * The running meal, opened from the meal line of the SmartInsulin block: what was eaten, how much of it has come in, when
 * the rest should be in, and the insulin for it. Times as the phone shows them (12 or 24 hour).
 */
@Composable
fun ActiveMealCard(meal: SmartInsulinOverview.ActiveMeal, modifier: Modifier = Modifier) {
    val dateUtil = LocalDateUtil.current
    val nowMs = dateUtil.now()
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)
    ) {
        Column(
            modifier = Modifier.padding(AapsSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)
        ) {
            Text(
                text = stringResource(UiStrings.si_active_meal_title, meal.mealLabel, dateUtil.timeString(meal.startMs), dateUtil.timeString(meal.endMs)),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            val leftMin = ((meal.endMs - nowMs) / 60_000L).coerceAtLeast(0L).toInt()
            Secondary(
                if (leftMin >= 60) stringResource(UiStrings.si_active_meal_left_hm, leftMin / 60, leftMin % 60)
                else stringResource(UiStrings.si_active_meal_left_m, leftMin)
            )
            Secondary(stringResource(UiStrings.si_active_meal_eaten, meal.eatenCarbsG, meal.eatenProteinG, meal.eatenFatG))

            // Carbs
            Part(
                title = stringResource(UiStrings.si_active_meal_carbs),
                inText = stringResource(UiStrings.si_active_meal_in, meal.carbsInG, meal.carbsTotalG),
                share = share(meal.carbsInG, meal.carbsTotalG),
                color = MaterialTheme.colorScheme.tertiary
            )
            Secondary(
                meal.carbsDoneMs?.takeIf { meal.carbsComing }?.let { stringResource(UiStrings.si_active_meal_carbs_done_by, dateUtil.timeString(it)) }
                    ?: stringResource(UiStrings.si_active_meal_all_in)
            )

            // Fat and protein
            if (meal.fatProteinTotalG > 0.0) {
                Part(
                    title = stringResource(UiStrings.si_active_meal_fat_protein),
                    inText = stringResource(UiStrings.si_active_meal_in_or_faded, meal.fatProteinInG, meal.fatProteinTotalG),
                    share = share(meal.fatProteinInG, meal.fatProteinTotalG),
                    color = MaterialTheme.colorScheme.secondary
                )
                val fromMs = meal.fatProteinFromMs
                val endMs = meal.fatProteinEndMs
                if (fromMs != null && endMs != null)
                    Secondary(stringResource(UiStrings.si_active_meal_fat_protein_window, dateUtil.timeString(fromMs), dateUtil.timeString(endMs)))
                if (meal.coveredG >= 1.0) Secondary(stringResource(UiStrings.si_active_meal_covered, meal.coveredG))
            }
            Secondary(stringResource(if (meal.carbsComing) UiStrings.si_active_meal_loop_carbs else UiStrings.si_active_meal_loop_tail))

            // Foods
            if (meal.foods.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = AapsSpacing.small))
                Text(stringResource(UiStrings.si_active_meal_foods), style = MaterialTheme.typography.labelLarge)
                meal.foods.forEach { Food(it, dateUtil) }
            }

            // Insulin
            HorizontalDivider(modifier = Modifier.padding(vertical = AapsSpacing.small))
            Text(stringResource(UiStrings.si_active_meal_insulin), style = MaterialTheme.typography.labelLarge)
            Secondary(stringResource(UiStrings.si_active_meal_insulin_given, meal.givenU, meal.loopSmbU))
            Secondary(stringResource(UiStrings.si_active_meal_insulin_waiting, meal.waitingU, meal.plannedU))
        }
    }
}

/** A part of the meal: its name and how much is in, over a bar of that share. */
@Composable
private fun Part(title: String, inText: String, share: Float, color: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text(inText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LinearProgressIndicator(progress = { share }, color = color, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Food(food: SmartInsulinOverview.ActiveMealFood, dateUtil: DateUtil) {
    Column {
        Text(
            stringResource(UiStrings.si_active_meal_food, dateUtil.timeString(food.eatenMs), food.name),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Secondary(stringResource(UiStrings.si_active_meal_food_grams, food.carbsG, food.proteinG, food.fatG))
        val fromMs = food.fatProteinFromMs
        val endMs = food.fatProteinEndMs
        if (fromMs != null && endMs != null)
            Secondary(stringResource(UiStrings.si_active_meal_food_fat_protein, dateUtil.timeString(fromMs), dateUtil.timeString(endMs)))
    }
}

@Composable
private fun Secondary(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun share(inG: Double, totalG: Double): Float = if (totalG > 0.0) (inG / totalG).coerceIn(0.0, 1.0).toFloat() else 0f
