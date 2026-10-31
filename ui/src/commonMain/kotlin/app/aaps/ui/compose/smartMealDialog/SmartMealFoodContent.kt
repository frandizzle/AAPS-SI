package app.aaps.ui.compose.smartMealDialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.aaps.core.interfaces.smartInsulin.LearnedMeal
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.smartMealDialog.food.BarcodeScanner
import app.aaps.ui.compose.smartMealDialog.food.FoodItem
import app.aaps.ui.compose.smartMealDialog.food.FoodLookupResult
import app.aaps.ui.compose.smartMealDialog.food.FoodSource
import app.aaps.ui.compose.smartMealDialog.food.MealPhotoLauncher
import app.aaps.ui.compose.smartMealDialog.food.SaveResult
import app.aaps.ui.compose.smartMealDialog.food.SavedFoodList

/**
 * The inside of the "What are you eating?" card: describe, photograph, scan, type in or pick the meal
 * from My foods, adjust the list, then fill in the insulin plan.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SmartMealFoodContent(
    state: SmartMealDialogUiState,
    viewModel: SmartMealDialogViewModel,
    photoLauncher: MealPhotoLauncher?,
    canScan: Boolean,
    onScan: () -> Unit,
    fmtU: (Double) -> String
) {
    val df = viewModel.decimalFormatter
    var showMyFoods by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var savingMeal by remember { mutableStateOf(false) }
    var savingIndex by remember { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        if (state.estimatesEnabled) {
            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::updateDescription,
                placeholder = { Text(stringResource(UiStrings.si_food_describe_hint)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.estimatesEnabled && photoLauncher != null) {
                OutlinedButton(onClick = photoLauncher.takePhoto) { ButtonContent(Icons.Filled.PhotoCamera, stringResource(UiStrings.si_food_take_photo)) }
                OutlinedButton(onClick = photoLauncher.pickPhoto) { ButtonContent(Icons.Filled.PhotoLibrary, stringResource(UiStrings.si_food_pick_photo)) }
            }
            if (canScan)
                OutlinedButton(onClick = onScan, enabled = !state.lookupBusy) { ButtonContent(Icons.Filled.QrCodeScanner, stringResource(UiStrings.si_food_scan)) }
            if (state.estimatesEnabled)
                FilledTonalButton(
                    onClick = viewModel::estimateMeal,
                    enabled = !state.lookupBusy && (state.description.isNotBlank() || state.hasPhoto)
                ) { ButtonContent(Icons.Filled.AutoAwesome, stringResource(UiStrings.si_food_estimate)) }
            OutlinedButton(onClick = { showMyFoods = true }) { ButtonContent(Icons.Filled.Bookmarks, stringResource(UiStrings.si_food_my_foods)) }
            OutlinedButton(onClick = { showManual = true }) { ButtonContent(Icons.Filled.Keyboard, stringResource(UiStrings.si_food_type_in)) }
        }
        // The most used saved entries, one tap each.
        if (state.savedFoods.isNotEmpty())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.savedFoods.take(QUICK_CHIPS).forEach { food ->
                    AssistChip(onClick = { viewModel.addSavedFood(food) }, label = { Text(food.name) })
                }
            }
        if (state.hasPhoto)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(UiStrings.si_food_photo_added), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::clearPhoto) { Text(stringResource(UiStrings.si_food_remove_photo)) }
            }
        if (state.lookupBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.lookupError?.let { reason ->
            Text(lookupErrorText(reason, state.lookupDetail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        state.foodItems.forEachIndexed { index, item ->
            FoodRow(
                item = item,
                df = { df.to0Decimal(it) },
                quantity = when {
                    item.quantity % 1.0 == 0.0       -> df.to0Decimal(item.quantity)
                    (item.quantity * 2) % 1.0 == 0.0 -> df.to1Decimal(item.quantity)
                    else                             -> df.to2Decimal(item.quantity)
                },
                onLess = { viewModel.changeQuantity(index, -quantityStep(item)) },
                onMore = { viewModel.changeQuantity(index, quantityStep(item)) },
                onRemove = { viewModel.removeFood(index) },
                onSave = { savingIndex = index }
            )
        }

        if (state.foodItems.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = viewModel::fillInFromFood, modifier = Modifier.weight(1f)) { Text(stringResource(UiStrings.si_food_fill_in)) }
                TextButton(onClick = viewModel::clearFood) { Text(stringResource(UiStrings.si_food_clear)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { savingMeal = true }) { Text(stringResource(UiStrings.si_food_save_meal)) }
                OutlinedButton(onClick = { viewModel.logOnly() }) { Text(stringResource(UiStrings.si_food_log_only)) }
            }
            Hint(stringResource(UiStrings.si_food_log_only_hint))
            Hint(stringResource(UiStrings.si_food_fill_hint))
        }
        state.loggedKcal?.let { Hint(stringResource(UiStrings.si_food_logged, df.to0Decimal(it))) }
        state.lastSave?.let { result ->
            when (result) {
                SaveResult.SAVED    -> Hint(stringResource(UiStrings.si_food_saved))
                SaveResult.REPLACED -> Hint(stringResource(UiStrings.si_food_save_replaced))
                SaveResult.FULL     -> Text(stringResource(UiStrings.si_food_save_full, SavedFoodList.MAX_SAVED), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                SaveResult.EMPTY    -> Unit
            }
        }
        state.sourceMeal?.let { name ->
            val learned = state.sourceLearned
            when {
                learned?.inUse == true ->
                    Hint(stringResource(UiStrings.si_food_meal_learned, name, df.to2Decimal(learned.carbFactor), df.to2Decimal(learned.fatProteinFactor), learned.meals))
                else                   -> Hint(stringResource(UiStrings.si_food_meal_learning, name, learned?.meals ?: 0, LearnedMeal.MIN_MEALS))
            }
            if (learned != null && learned.recentMeals >= ATE_SOON_MIN_MEALS && learned.ateSoon * 2 > learned.recentMeals)
                Hint(stringResource(UiStrings.si_food_meal_ate_soon))
        }
        if (state.planNoRatio) Text(stringResource(UiStrings.si_food_no_ratio), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        // The insulin the food needs, from a new meal's plan or from food added to the running one.
        val needU = state.plan?.let { it.carbsU to it.fatProteinU } ?: state.addition?.let { it.carbsU to it.fatProteinU }
        needU?.let { (carbsU, fatProteinU) ->
            Text(stringResource(UiStrings.si_food_plan_summary, fmtU(carbsU), fmtU(fatProteinU)), style = MaterialTheme.typography.bodyMedium)
            // A saved meal with its own learning says so above.
            if (state.sourceLearned?.inUse != true) Hint(
                if (state.calibrationMeals > 0 && state.calibrationFactor != 1.0)
                    stringResource(UiStrings.si_food_plan_factor, df.to2Decimal(state.calibrationFactor), state.calibrationMeals)
                else stringResource(UiStrings.si_food_plan_learning)
            )
            if (state.plan?.capped == true) Text(stringResource(UiStrings.si_food_plan_capped), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }

    if (showMyFoods)
        MyFoodsDialog(
            foods = state.savedFoods,
            learned = state.learnedMeals,
            recent = state.recentFoods,
            df = { df.to0Decimal(it) },
            factor = { df.to2Decimal(it) },
            onAdd = { viewModel.addSavedFood(it); showMyFoods = false },
            onAddRecent = { viewModel.addRecentFood(it); showMyFoods = false },
            onSaveRecent = viewModel::saveRecentFood,
            onRename = viewModel::renameSavedFood,
            onDelete = viewModel::deleteSavedFood,
            onForgetLearning = viewModel::forgetMealLearning,
            onDismiss = { showMyFoods = false }
        )
    state.logCheckKcal?.let { kcal ->
        AlertDialog(
            onDismissRequest = viewModel::cancelLogCheck,
            title = { Text(stringResource(UiStrings.si_food_log_check_title)) },
            text = { Text(stringResource(UiStrings.si_food_log_check, df.to0Decimal(kcal))) },
            confirmButton = { TextButton(onClick = { viewModel.logOnly(force = true) }) { Text(stringResource(UiStrings.si_food_log_anyway)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelLogCheck) { Text(stringResource(CoreUiStrings.cancel)) } }
        )
    }
    if (showManual)
        ManualFoodDialog(onAdd = { viewModel.addManualFood(it); showManual = false }, onDismiss = { showManual = false })
    if (savingMeal)
        SaveNameDialog(
            title = stringResource(UiStrings.si_food_save_meal),
            initialName = state.foodItems.singleOrNull()?.name.orEmpty(),
            onSave = { viewModel.saveMeal(it); savingMeal = false },
            onDismiss = { savingMeal = false }
        )
    savingIndex?.let { index ->
        SaveNameDialog(
            title = stringResource(UiStrings.si_food_save_item),
            initialName = state.foodItems.getOrNull(index)?.name.orEmpty(),
            onSave = { viewModel.saveFood(index, it); savingIndex = null },
            onDismiss = { savingIndex = null }
        )
    }
}

private const val QUICK_CHIPS = 5
/** The hint to wait after pre-bolus 1 needs this many recent meals. */
private const val ATE_SOON_MIN_MEALS = 3

/** Half servings for a packet, quarter portions for an estimate. */
private fun quantityStep(item: FoodItem): Double =
    if (item.source == FoodSource.BARCODE || item.source == FoodSource.MANUAL) 0.5 else SmartMealDialogViewModel.QUANTITY_STEP

@Composable
private fun FoodRow(item: FoodItem, df: (Double) -> String, quantity: String, onLess: () -> Unit, onMore: () -> Unit, onRemove: () -> Unit, onSave: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (item.portion.isNotBlank()) stringResource(UiStrings.si_food_portion, item.portion, df(item.baseGrams))
                        else stringResource(UiStrings.si_food_portion_grams, df(item.baseGrams)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onSave) { Icon(Icons.Outlined.StarBorder, contentDescription = stringResource(UiStrings.si_food_save_item)) }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = stringResource(UiStrings.si_food_remove_item)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(UiStrings.si_food_item_detail, df(item.grams), df(item.carbsG), df(item.proteinG), df(item.fatG)),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onLess) { Icon(Icons.Filled.Remove, contentDescription = stringResource(UiStrings.si_food_less)) }
                Text(stringResource(UiStrings.si_food_quantity, quantity), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onMore) { Icon(Icons.Filled.Add, contentDescription = stringResource(UiStrings.si_food_more)) }
            }
        }
    }
}

@Composable
private fun ButtonContent(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    Text(text)
}

/** Small grey help text, used across the Smart Meal dialog. */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

@Composable
private fun lookupErrorText(reason: FoodLookupResult.Reason, detail: String): String = when (reason) {
    FoodLookupResult.Reason.NOT_SET_UP    -> stringResource(UiStrings.si_food_err_not_set_up)
    FoodLookupResult.Reason.OFFLINE       -> stringResource(UiStrings.si_food_err_offline)
    FoodLookupResult.Reason.INVALID_KEY   -> stringResource(UiStrings.si_food_err_invalid_key)
    FoodLookupResult.Reason.LIMIT_REACHED -> stringResource(UiStrings.si_food_err_limit)
    FoodLookupResult.Reason.NOT_FOOD      -> stringResource(UiStrings.si_food_err_not_food)
    FoodLookupResult.Reason.NOT_FOUND     -> stringResource(UiStrings.si_food_err_not_found)
    FoodLookupResult.Reason.BAD_RESPONSE  -> stringResource(UiStrings.si_food_err_bad)
    FoodLookupResult.Reason.OTHER         -> stringResource(UiStrings.si_food_err_other, detail)
}

/** Full-screen camera that closes on the first barcode it reads. */
@Composable
internal fun BarcodeScanDialog(onScanned: (String) -> Unit, onDismiss: () -> Unit) {
    var done by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column {
                Text(stringResource(UiStrings.si_food_scan_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    BarcodeScanner(
                        onScanned = { code -> if (!done) { done = true; onScanned(code) } },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text(stringResource(CoreUiStrings.cancel)) }
            }
        }
    }
}
