package app.aaps.ui.compose.smartMealDialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.aaps.core.interfaces.smartInsulin.LearnedMeal
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.smartMealDialog.food.FoodItem
import app.aaps.ui.compose.smartMealDialog.food.SavedFood
import app.aaps.ui.compose.smartMealDialog.food.SavedFoodList
import app.aaps.ui.compose.smartMealDialog.food.SavedFoodList.MAX_NAME
import app.aaps.ui.compose.smartMealDialog.food.manualFood

/** Asks for the name to save a food or a meal under. */
@Composable
internal fun SaveNameDialog(title: String, initialName: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initialName.take(MAX_NAME)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME) },
                    label = { Text(stringResource(UiStrings.si_food_save_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(stringResource(UiStrings.si_food_save_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(CoreUiStrings.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiStrings.cancel)) } }
    )
}

/** Type in a food by hand: what a weight of it holds, as on a label or in a recipe. */
@Composable
internal fun ManualFoodDialog(onAdd: (FoodItem) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var portion by remember { mutableStateOf("") }
    var grams by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    // An empty value counts as 0, except the weight, which has to be given.
    fun num(text: String) = if (text.isBlank()) 0.0 else text.trim().replace(',', '.').toDoubleOrNull()
    val g = num(grams)
    val c = num(carbs)
    val p = num(protein)
    val f = num(fat)
    val item = if (g != null && c != null && p != null && f != null) manualFood(name, portion, g, c, p, f) else null
    val tooMuch = g != null && g > 0.0 && c != null && p != null && f != null && c + p + f > g
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(UiStrings.si_food_manual_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(MAX_NAME) }, singleLine = true,
                    label = { Text(stringResource(UiStrings.si_food_save_name)) }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = portion, onValueChange = { portion = it.take(MAX_NAME) }, singleLine = true,
                    label = { Text(stringResource(UiStrings.si_food_manual_portion)) }, modifier = Modifier.fillMaxWidth()
                )
                NumberField(grams, { grams = it }, stringResource(UiStrings.si_food_manual_grams))
                Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                    NumberField(carbs, { carbs = it }, stringResource(UiStrings.si_food_manual_carbs), Modifier.weight(1f))
                    NumberField(protein, { protein = it }, stringResource(UiStrings.si_food_manual_protein), Modifier.weight(1f))
                    NumberField(fat, { fat = it }, stringResource(UiStrings.si_food_manual_fat), Modifier.weight(1f))
                }
                if (tooMuch) Text(stringResource(UiStrings.si_food_manual_invalid), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else Text(stringResource(UiStrings.si_food_manual_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { item?.let(onAdd) }, enabled = item != null) { Text(stringResource(CoreUiStrings.add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiStrings.cancel)) } }
    )
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth()) =
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.length <= 7) onChange(v) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )

/**
 * The My foods page: saved foods and meals, most used first, and the recent list below. Tapping an entry
 * adds it to the meal and closes the page.
 */
@Composable
internal fun MyFoodsDialog(
    foods: List<SavedFood>,
    learned: Map<String, LearnedMeal>,
    recent: List<FoodItem>,
    df: (Double) -> String,
    factor: (Double) -> String,
    onAdd: (SavedFood) -> Unit,
    onAddRecent: (FoodItem) -> Unit,
    onSaveRecent: (FoodItem, String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onForgetLearning: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<SavedFood?>(null) }
    var deleting by remember { mutableStateOf<SavedFood?>(null) }
    var savingRecent by remember { mutableStateOf<FoodItem?>(null) }
    val shown = SavedFoodList.search(foods, query)
    val shownRecent = recent.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(AapsSpacing.extraLarge), verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(UiStrings.si_food_my_foods), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = stringResource(CoreUiStrings.cancel)) }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(CoreUiStrings.search)) },
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(AapsSpacing.small), modifier = Modifier.weight(1f)) {
                    if (foods.isEmpty())
                        item { Hint(stringResource(UiStrings.si_food_my_foods_empty)) }
                    items(shown, key = { "saved:" + it.name }) { food ->
                        val t = food.totals
                        val l = learned[food.name]
                        EntryRow(
                            title = food.name,
                            detail = stringResource(UiStrings.si_food_subtitle_totals, df(t.carbsG), df(t.proteinG), df(t.fatG)),
                            learnedText = l?.let { stringResource(UiStrings.si_food_learned_short, factor(it.carbFactor), factor(it.fatProteinFactor), it.meals) },
                            onClick = { onAdd(food) }
                        ) {
                            if (l != null)
                                IconButton(onClick = { onForgetLearning(food.name) }) { Icon(Icons.Filled.RestartAlt, contentDescription = stringResource(UiStrings.si_food_learning_reset)) }
                            IconButton(onClick = { renaming = food }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(UiStrings.si_food_rename)) }
                            IconButton(onClick = { deleting = food }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(CoreUiStrings.delete)) }
                        }
                    }
                    if (shownRecent.isNotEmpty()) {
                        item {
                            Column(Modifier.padding(top = AapsSpacing.large)) {
                                Text(stringResource(UiStrings.si_food_recent), style = MaterialTheme.typography.titleMedium)
                                Hint(stringResource(UiStrings.si_food_recent_hint))
                            }
                        }
                        items(shownRecent) { item ->
                            EntryRow(
                                title = item.name,
                                detail = stringResource(UiStrings.si_food_item_detail, df(item.grams), df(item.carbsG), df(item.proteinG), df(item.fatG)),
                                onClick = { onAddRecent(item) }
                            ) {
                                IconButton(onClick = { savingRecent = item }) { Icon(Icons.Outlined.StarBorder, contentDescription = stringResource(UiStrings.si_food_save_item)) }
                            }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { food ->
        SaveNameDialog(
            title = stringResource(UiStrings.si_food_rename),
            initialName = food.name,
            onSave = { onRename(food.name, it); renaming = null },
            onDismiss = { renaming = null }
        )
    }
    savingRecent?.let { item ->
        SaveNameDialog(
            title = stringResource(UiStrings.si_food_save_item),
            initialName = item.name,
            onSave = { onSaveRecent(item, it); savingRecent = null },
            onDismiss = { savingRecent = null }
        )
    }
    deleting?.let { food ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(UiStrings.si_food_delete_title, food.name)) },
            confirmButton = { TextButton(onClick = { onDelete(food.name); deleting = null }) { Text(stringResource(CoreUiStrings.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(CoreUiStrings.cancel)) } }
        )
    }
}

@Composable
private fun EntryRow(title: String, detail: String, onClick: () -> Unit, learnedText: String? = null, actions: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(AapsSpacing.large), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(start = AapsSpacing.large, top = AapsSpacing.small, bottom = AapsSpacing.small), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                learnedText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
            actions()
        }
    }
}
