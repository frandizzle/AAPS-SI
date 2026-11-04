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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.smartMealDialog.food.AustralianFoods
import app.aaps.ui.compose.smartMealDialog.food.FoodItem
import app.aaps.ui.compose.smartMealDialog.food.FoodLookupResult
import app.aaps.ui.compose.smartMealDialog.food.MealTotals
import app.aaps.ui.compose.smartMealDialog.food.SavedFood
import app.aaps.ui.compose.smartMealDialog.food.SavedFoodList

/** At most this many of the user's own foods are shown above the search results. */
private const val MAX_MINE = 5

/**
 * The food search: the user's own foods first, then plain foods from the Australian food table on the phone, then
 * packaged products from Open Food Facts once asked for (that search allows only 10 a minute). Tapping a result adds
 * it to the meal and leaves the page open, so a meal of several foods is found in one go.
 */
@Composable
internal fun FoodSearchDialog(
    state: SmartMealDialogUiState,
    df: (Double) -> String,
    onSearchProducts: (String) -> Unit,
    onAdd: (FoodItem) -> Unit,
    onAddSaved: (SavedFood) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var added by remember { mutableStateOf<String?>(null) }
    val q = query.trim()
    val longEnough = q.length >= AustralianFoods.MIN_QUERY
    val saved = remember(q, state.savedFoods) { if (longEnough) SavedFoodList.search(state.savedFoods, q).take(MAX_MINE) else emptyList() }
    val recent = remember(q, state.recentFoods) {
        if (longEnough) state.recentFoods.filter { it.name.contains(q, ignoreCase = true) && saved.none { s -> s.name == it.name } }.take(MAX_MINE) else emptyList()
    }
    val plain = remember(q) { AustralianFoods.search(q) }
    // Results of an earlier search are for other words.
    val products = if (state.searchedFor.equals(q, ignoreCase = true)) state.searchProducts else emptyList()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(AapsSpacing.extraLarge), verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(UiStrings.si_food_search_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = stringResource(CoreUiStrings.close)) }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it; added = null }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(UiStrings.si_food_search_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    // The keyboard's search key also asks Open Food Facts: the plain foods are already shown.
                    keyboardActions = KeyboardActions(onSearch = { if (longEnough) onSearchProducts(q) }),
                    modifier = Modifier.fillMaxWidth()
                )
                added?.let { Text(stringResource(UiStrings.si_food_search_added, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(AapsSpacing.small), modifier = Modifier.weight(1f)) {
                    if (!longEnough) item { Hint(stringResource(UiStrings.si_food_search_type_more)) }
                    if (saved.isNotEmpty() || recent.isNotEmpty()) item { SectionTitle(stringResource(UiStrings.si_food_search_mine)) }
                    items(saved, key = { "saved:" + it.name }) { food ->
                        val t = food.totals
                        ResultRow(food.name, stringResource(UiStrings.si_food_totals_kcal, df(t.carbsG), df(t.proteinG), df(t.fatG), df(t.kcal))) {
                            onAddSaved(food); added = food.name
                        }
                    }
                    items(recent) { item ->
                        ResultRow(item.name, stringResource(UiStrings.si_food_item_detail_kcal, df(item.grams), df(item.carbsG), df(item.proteinG), df(item.fatG), df(item.kcal))) {
                            onAdd(item); added = item.name
                        }
                    }
                    if (longEnough) item { SectionTitle(stringResource(UiStrings.si_food_search_plain)) }
                    if (longEnough && plain.isEmpty()) item { Hint(stringResource(UiStrings.si_food_search_none)) }
                    // A drink is in the table twice, per 100 g and per 100 mL.
                    items(plain, key = { "plain:" + it.name + ":" + it.liquid }) { food ->
                        val per = if (food.liquid) UiStrings.si_food_search_per_100ml else UiStrings.si_food_search_per_100
                        ResultRow(food.name, stringResource(per, df(food.carbs), df(food.protein), df(food.fat), df(MealTotals.kcal(food.carbs, food.fibre, food.protein, food.fat)))) {
                            onAdd(food.toItem()); added = food.name
                        }
                    }
                    if (longEnough) item { SectionTitle(stringResource(UiStrings.si_food_search_products)) }
                    if (longEnough) item {
                        when {
                            state.searchBusy                                    -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            state.searchedFor.equals(q, ignoreCase = true) &&
                                state.searchError != null                       ->
                                Text(
                                    if (state.searchError == FoodLookupResult.Reason.NOT_FOUND) stringResource(UiStrings.si_food_search_none)
                                    else lookupErrorText(state.searchError, state.searchErrorDetail),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                                )

                            products.isEmpty()                                  ->
                                OutlinedButton(onClick = { onSearchProducts(q) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(UiStrings.si_food_search_online)) }

                            else                                                -> Unit
                        }
                    }
                    items(products) { item ->
                        val detail = stringResource(UiStrings.si_food_item_detail_kcal, df(item.grams), df(item.carbsG), df(item.proteinG), df(item.fatG), df(item.kcal))
                        ResultRow(item.name, if (item.portion.isNotBlank()) item.portion else null, detail) { onAdd(item); added = item.name }
                    }
                    item { Hint(stringResource(UiStrings.si_food_search_credit), Modifier.padding(top = AapsSpacing.medium)) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = AapsSpacing.small))

@Composable
private fun ResultRow(title: String, detail: String, onClick: () -> Unit) = ResultRow(title, null, detail, onClick)

/** One result: its name, a portion in words when it has one, and what it holds. Tapping adds it. */
@Composable
private fun ResultRow(title: String, portion: String?, detail: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(AapsSpacing.large),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(horizontal = AapsSpacing.large, vertical = AapsSpacing.medium)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            portion?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
