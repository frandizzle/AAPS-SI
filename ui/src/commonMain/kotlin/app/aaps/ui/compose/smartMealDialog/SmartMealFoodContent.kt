package app.aaps.ui.compose.smartMealDialog

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.ui.compose.AapsSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Search
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
import app.aaps.ui.compose.smartMealDialog.food.FoodSpeed
import app.aaps.ui.compose.smartMealDialog.food.MealDosePlanner
import app.aaps.ui.compose.smartMealDialog.food.MealPhotoLauncher
import app.aaps.ui.compose.smartMealDialog.food.SaveResult
import app.aaps.ui.compose.smartMealDialog.food.SavedFoodList

/**
 * The inside of the "What are you eating?" card: describe, photograph, scan, type in or pick the meal
 * from My foods, adjust the list, then fill in the insulin plan.
 */
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
    var showSearch by remember { mutableStateOf(false) }
    var savingMeal by remember { mutableStateOf(false) }
    var savingIndex by remember { mutableStateOf<Int?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    var choosingLog by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium), modifier = Modifier.padding(top = AapsSpacing.small)) {
        // Describe and estimate side by side; every other way to add food in one compact row below.
        if (state.estimatesEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.description,
                    onValueChange = viewModel::updateDescription,
                    placeholder = { Text(stringResource(UiStrings.si_food_describe_hint)) },
                    minLines = 2,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    onClick = viewModel::estimateMeal,
                    enabled = !state.lookupBusy && (state.description.isNotBlank() || state.hasPhoto)
                ) { ButtonContent(Icons.Filled.AutoAwesome, stringResource(UiStrings.si_food_estimate)) }
            }
        }
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            if (state.estimatesEnabled && photoLauncher != null) {
                SourceButton(Icons.Filled.PhotoCamera, stringResource(UiStrings.si_food_take_photo), onClick = photoLauncher.takePhoto)
                SourceButton(Icons.Filled.PhotoLibrary, stringResource(UiStrings.si_food_pick_photo), onClick = photoLauncher.pickPhoto)
            }
            if (canScan) SourceButton(Icons.Filled.QrCodeScanner, stringResource(UiStrings.si_food_scan), enabled = !state.lookupBusy, onClick = onScan)
            SourceButton(Icons.Filled.Search, stringResource(UiStrings.si_food_search)) { showSearch = true }
            SourceButton(Icons.Filled.Bookmarks, stringResource(UiStrings.si_food_my_foods)) { showMyFoods = true }
            SourceButton(Icons.Filled.Keyboard, stringResource(UiStrings.si_food_type_in)) { showManual = true }
        }
        // The recent foods first, then the most used saved ones: one tap each, in a row that scrolls sideways.
        val recentNames = state.recentFoods.map { it.name.lowercase() }.toSet()
        val saved = state.savedFoods.filter { it.name.lowercase() !in recentNames }.take(QUICK_SAVED)
        if (state.recentFoods.isNotEmpty() || saved.isNotEmpty())
            Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                state.recentFoods.forEach { item ->
                    AssistChip(onClick = { viewModel.addSelected(emptyList(), listOf(item)) }, label = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
                saved.forEach { food ->
                    AssistChip(onClick = { viewModel.addSavedFood(food) }, label = { Text(food.name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
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
                onSave = { savingIndex = index },
                onSpeed = { viewModel.changeSpeed(index) }
            )
        }

        if (state.foodItems.isNotEmpty()) {
            val t = state.foodTotals
            Text(
                stringResource(UiStrings.si_food_total, df.to0Decimal(t.carbsG), df.to0Decimal(t.proteinG), df.to0Decimal(t.fatG), df.to0Decimal(t.kcal)),
                style = MaterialTheme.typography.titleSmall
            )
        }
        PlanSummary(state, df, fmtU)

        if (state.foodItems.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = viewModel::fillInFromFood, modifier = Modifier.weight(1f)) { Text(stringResource(UiStrings.si_food_fill_in)) }
                TextButton(onClick = viewModel::clearFood) { Text(stringResource(UiStrings.si_food_clear)) }
            }
            state.editingMeal?.let { name ->
                Hint(stringResource(UiStrings.si_food_editing, name))
                Button(onClick = viewModel::saveChanges, modifier = Modifier.fillMaxWidth()) { Text(stringResource(UiStrings.si_food_save_changes)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium), verticalAlignment = Alignment.CenterVertically) {
                if (state.editingMeal == null) TextButton(onClick = { savingMeal = true }) { Text(stringResource(UiStrings.si_food_save_meal)) }
                // While a meal runs, logged food may count for the loop: ask whether it is to treat a low.
                TextButton(onClick = { if (state.canAdd) choosingLog = true else viewModel.logOnly() }) { Text(stringResource(UiStrings.si_food_log_only)) }
            }
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
        // The long explanations, out of the way until asked for.
        TextButton(onClick = { showHelp = !showHelp }) {
            Icon(if (showHelp) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            Spacer(Modifier.width(AapsSpacing.small))
            Text(stringResource(UiStrings.si_food_how_it_works))
        }
        if (showHelp) {
            Hint(stringResource(UiStrings.si_food_fill_hint))
            Hint(stringResource(UiStrings.si_food_speed_hint))
            Hint(stringResource(UiStrings.si_food_hold_to_save))
            Hint(stringResource(UiStrings.si_food_log_only_hint))
        }
    }

    if (choosingLog)
        AlertDialog(
            onDismissRequest = { choosingLog = false },
            title = { Text(stringResource(UiStrings.si_food_log_only)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                    Text(stringResource(UiStrings.si_food_log_choice))
                    if (state.logTreatingLow) Text(stringResource(UiStrings.si_food_log_was_low), color = MaterialTheme.colorScheme.tertiary)
                }
            },
            // The likely choice is the main button: treating a low when BG was low a moment ago.
            confirmButton = {
                val (first, firstLow) = if (state.logTreatingLow) UiStrings.si_food_log_treating_low to true else UiStrings.si_food_log_for_loop to false
                TextButton(onClick = { choosingLog = false; viewModel.updateLogTreatingLow(firstLow); viewModel.logOnly() }) { Text(stringResource(first)) }
            },
            dismissButton = {
                val (second, secondLow) = if (state.logTreatingLow) UiStrings.si_food_log_for_loop to false else UiStrings.si_food_log_treating_low to true
                TextButton(onClick = { choosingLog = false; viewModel.updateLogTreatingLow(secondLow); viewModel.logOnly() }) { Text(stringResource(second)) }
            }
        )

    if (showMyFoods)
        MyFoodsDialog(
            foods = state.savedFoods,
            learned = state.learnedMeals,
            recent = state.recentFoods,
            df = { df.to0Decimal(it) },
            factor = { df.to2Decimal(it) },
            onAddSelected = { foods, recent -> viewModel.addSelected(foods, recent); showMyFoods = false },
            onSaveRecent = viewModel::saveRecentFood,
            onRename = viewModel::renameSavedFood,
            onEdit = { viewModel.editSavedFood(it); showMyFoods = false },
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
    if (showSearch)
        FoodSearchDialog(
            state = state,
            df = { df.to0Decimal(it) },
            onSearchProducts = viewModel::searchProducts,
            onAdd = viewModel::addFood,
            onAddSaved = viewModel::addSavedFood,
            onDismiss = { showSearch = false; viewModel.clearSearch() }
        )
    if (showManual)
        ManualFoodDialog(onAdd = { viewModel.addFood(it); showManual = false }, onDismiss = { showManual = false })
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

/** Saved foods shown after the recent ones in the quick row. */
private const val QUICK_SAVED = 10

/** A portion that is only a weight, like "160 g" or "160.0g": the weight is shown anyway. */
private val GRAMS_ONLY = Regex("""^\d+([.,]\d+)?\s*g$""")

/** Half servings for a packet or 100 g, quarter portions for an estimate. */
private fun quantityStep(item: FoodItem): Double =
    if (item.source == FoodSource.BARCODE || item.source == FoodSource.MANUAL || item.source == FoodSource.SEARCH) 0.5
    else SmartMealDialogViewModel.QUANTITY_STEP

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FoodRow(
    item: FoodItem, df: (Double) -> String, quantity: String, onLess: () -> Unit, onMore: () -> Unit, onRemove: () -> Unit, onSave: () -> Unit,
    onSpeed: () -> Unit
) {
    // Hold the row to save the food to My foods.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(AapsSpacing.large),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = onSave, onLongClickLabel = stringResource(UiStrings.si_food_save_item))
    ) {
        Column(Modifier.padding(start = AapsSpacing.large, end = AapsSpacing.small, top = AapsSpacing.small, bottom = AapsSpacing.small)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    // A portion in words says more than the weight alone; a portion that is only a weight is left out.
                    if (item.portion.isNotBlank() && !item.portion.trim().matches(GRAMS_ONLY))
                        Text(item.portion, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Speed only matters for food with carbs.
                if (item.carbsG >= 1.0) AssistChip(onClick = onSpeed, label = { Text(speedLabel(item.speed)) })
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = stringResource(UiStrings.si_food_remove_item)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(UiStrings.si_food_item_detail, df(item.grams), df(item.carbsG), df(item.proteinG), df(item.fatG)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onLess) { Icon(Icons.Filled.Remove, contentDescription = stringResource(UiStrings.si_food_less)) }
                Text(stringResource(UiStrings.si_food_quantity, quantity), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onMore) { Icon(Icons.Filled.Add, contentDescription = stringResource(UiStrings.si_food_more)) }
            }
        }
    }
}

/**
 * What the food needs and what the plan gives now and later, with why it gives less, like the bolus summary of a bolus
 * calculator. Below it, what the plan learned from: the saved meal, all meals, or nothing yet.
 */
@Composable
private fun PlanSummary(state: SmartMealDialogUiState, df: DecimalFormatter, fmtU: (Double) -> String) {
    val plan = state.plan
    val addition = state.addition
    val learned = state.sourceLearned
    if (plan == null && addition == null && !state.planNoRatio && state.sourceMeal == null) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(AapsSpacing.large), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AapsSpacing.large), verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
            if (state.planNoRatio) Text(stringResource(UiStrings.si_food_no_ratio), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            val needU = plan?.let { it.carbsU to it.fatProteinU } ?: addition?.let { it.carbsU to it.fatProteinU }
            needU?.let { (carbsU, fatProteinU) ->
                AmountRow(stringResource(UiStrings.si_food_needs), fmtU(carbsU + fatProteinU), strong = true)
                Hint(stringResource(UiStrings.si_food_needs_parts, fmtU(carbsU), fmtU(fatProteinU)))
                if (plan != null) {
                    AmountRow(stringResource(UiStrings.si_food_now_pb1), fmtU(plan.pb1U))
                    val laterU = plan.pb1RestU + plan.pb2U + plan.pb3U
                    if (laterU > 0.0) AmountRow(stringResource(UiStrings.si_food_later), fmtU(laterU))
                    if (plan.capped) Why(stringResource(UiStrings.si_food_why_capped))
                    state.fastCarbsWaitMin?.let { Why(stringResource(UiStrings.si_food_fast_wait, it), warning = false) }
                }
                addition?.let { a ->
                    AmountRow(stringResource(UiStrings.si_food_now), fmtU(a.nowU))
                    val laterU = a.pb2AddU + a.pb3AddU
                    if (laterU > 0.0) AmountRow(stringResource(UiStrings.si_food_later), fmtU(laterU))
                    if (a.heldForBg) Why(stringResource(UiStrings.si_food_why_held))
                    if (a.cutForIob) Why(stringResource(UiStrings.si_food_why_iob))
                    if (a.cutForBalance) Why(stringResource(UiStrings.si_food_why_balance))
                    if (a.capped) Why(stringResource(UiStrings.si_food_why_capped))
                }
                // A saved meal with its own learning says so below.
                val general = state.generalLearned?.takeIf { it.inUse }
                if (learned?.inUse != true) Hint(
                    if (general != null)
                        stringResource(UiStrings.si_food_plan_parts, df.to2Decimal(general.carbFactor), df.to2Decimal(general.waveFactor), df.to2Decimal(general.tailFactor), general.meals)
                    else if (state.foodItems.none { it.source == FoodSource.ESTIMATE })
                        stringResource(UiStrings.si_food_plan_exact)
                    else if (state.calibrationMeals > 0 && state.calibrationFactor != 1.0)
                        stringResource(UiStrings.si_food_plan_factor, df.to2Decimal(state.calibrationFactor), state.calibrationMeals)
                    else stringResource(UiStrings.si_food_plan_learning)
                )
            }
            state.sourceMeal?.let { name ->
                if (learned?.inUse == true)
                    Hint(stringResource(UiStrings.si_food_meal_learned, name, df.to2Decimal(learned.carbFactor), df.to2Decimal(learned.waveFactor), df.to2Decimal(learned.tailFactor), learned.meals))
                else Hint(stringResource(UiStrings.si_food_meal_learning, name, learned?.meals ?: 0, LearnedMeal.MIN_MEALS))
                if (learned?.inUse == true) MealDosePlanner.suggestedWait(learned.riseMin)?.let { wait ->
                    Why(stringResource(UiStrings.si_food_meal_wait, learned.riseMin ?: 0, wait), warning = false)
                }
                state.learnedTiming?.let { (pb2, pb3) ->
                    Hint(
                        if (plan?.pb3U?.let { it > 0.0 } == true) stringResource(UiStrings.si_food_meal_timing_pb3, pb2, pb3)
                        else stringResource(UiStrings.si_food_meal_timing, pb2)
                    )
                }
            }
        }
    }
}

/** A label with an amount on the right, like a line of a bolus calculator. */
@Composable
private fun AmountRow(label: String, amount: String, strong: Boolean = false) {
    val style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = style, modifier = Modifier.weight(1f))
        Text(amount, style = style, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal)
    }
}

/** Why the plan gives less, or what to do with it: one short line. [warning] lines stand out. */
@Composable
private fun Why(text: String, warning: Boolean = true) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (warning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)

/** One way to add food: an icon over a short label, so all of them fit in one row. */
@Composable
private fun SourceButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = AapsSpacing.small)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun speedLabel(speed: FoodSpeed): String = when (speed) {
    FoodSpeed.FAST   -> stringResource(UiStrings.si_food_speed_fast)
    FoodSpeed.NORMAL -> stringResource(UiStrings.si_food_speed_normal)
    FoodSpeed.SLOW   -> stringResource(UiStrings.si_food_speed_slow)
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
internal fun lookupErrorText(reason: FoodLookupResult.Reason, detail: String): String = when (reason) {
    FoodLookupResult.Reason.NOT_SET_UP    -> stringResource(UiStrings.si_food_err_not_set_up)
    FoodLookupResult.Reason.OFFLINE       -> stringResource(UiStrings.si_food_err_offline)
    FoodLookupResult.Reason.INVALID_KEY   -> stringResource(UiStrings.si_food_err_invalid_key)
    FoodLookupResult.Reason.LIMIT_REACHED -> stringResource(UiStrings.si_food_err_limit)
    FoodLookupResult.Reason.BUSY          -> stringResource(UiStrings.si_food_err_busy)
    FoodLookupResult.Reason.TIMEOUT       -> stringResource(UiStrings.si_food_err_timeout)
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
