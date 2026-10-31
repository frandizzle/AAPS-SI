package app.aaps.ui.compose.smartMealDialog.food

import androidx.compose.runtime.Immutable
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * One entry in My foods: a single food, or several saved together as a meal, for example a turkey wrap
 * made of the wrap, turkey, cheese and lettuce. The name is what tells entries apart.
 *
 * @param uses       how often it was added to a meal, to put the common ones first
 * @param lastUsedMs when it was last added or saved
 */
@Immutable
data class SavedFood(val name: String, val items: List<FoodItem>, val uses: Int = 0, val lastUsedMs: Long = 0L) {

    val totals: MealTotals get() = items.totals()
}

/** What saving to My foods did. */
enum class SaveResult { SAVED, REPLACED, FULL, EMPTY }

/**
 * The rules for My foods and the recent list, without storage, so they can be tested on their own.
 * Every function returns a new list.
 */
object SavedFoodList {

    const val MAX_SAVED = 100
    const val MAX_ITEMS = 30
    const val MAX_RECENT = 10
    const val MAX_NAME = 60

    /** Most used first, then most recently used. */
    fun sorted(foods: List<SavedFood>): List<SavedFood> =
        foods.sortedWith(compareByDescending<SavedFood> { it.uses }.thenByDescending { it.lastUsedMs })

    /** Entries whose name, or the name of one of their foods, holds [query]. */
    fun search(foods: List<SavedFood>, query: String): List<SavedFood> {
        val q = query.trim()
        if (q.isEmpty()) return foods
        return foods.filter { f -> f.name.contains(q, ignoreCase = true) || f.items.any { it.name.contains(q, ignoreCase = true) } }
    }

    /**
     * Saves [items] as [name]. An entry with the same name (any case) is replaced, and keeps how often it
     * was used. A full list takes no new entry: dropping one the user saved would lose it without asking.
     */
    fun save(foods: List<SavedFood>, name: String, items: List<FoodItem>, nowMs: Long): Pair<List<SavedFood>, SaveResult> {
        val clean = name.trim().take(MAX_NAME)
        if (clean.isEmpty() || items.isEmpty()) return foods to SaveResult.EMPTY
        val kept = items.take(MAX_ITEMS)
        val old = foods.firstOrNull { it.name.equals(clean, ignoreCase = true) }
        if (old == null && foods.size >= MAX_SAVED) return foods to SaveResult.FULL
        val entry = SavedFood(clean, kept, old?.uses ?: 0, nowMs)
        return if (old == null) foods + entry to SaveResult.SAVED
        else foods.map { if (it === old) entry else it } to SaveResult.REPLACED
    }

    fun markUsed(foods: List<SavedFood>, name: String, nowMs: Long): List<SavedFood> =
        foods.map { if (it.name == name) it.copy(uses = it.uses + 1, lastUsedMs = nowMs) else it }

    fun delete(foods: List<SavedFood>, name: String): List<SavedFood> = foods.filterNot { it.name == name }

    /** Renames an entry. Nothing changes when the new name is empty or another entry already has it. */
    fun rename(foods: List<SavedFood>, name: String, newName: String): List<SavedFood> {
        val clean = newName.trim().take(MAX_NAME)
        if (clean.isEmpty() || foods.any { it.name != name && it.name.equals(clean, ignoreCase = true) }) return foods
        return foods.map { if (it.name == name) it.copy(name = clean) else it }
    }

    /** Puts [items] at the top of the recent list, once each, as one portion. */
    fun remember(recent: List<FoodItem>, items: List<FoodItem>): List<FoodItem> {
        val fresh = items.map { it.copy(quantity = 1.0) }
        return (fresh + recent).distinctBy { sameFood(it) }.take(MAX_RECENT)
    }

    private fun sameFood(item: FoodItem) = item.name.lowercase() to item.baseGrams

    /** An item from My foods or the recent list, ready to add to the meal. */
    fun forMeal(item: FoodItem): FoodItem = item.copy(source = FoodSource.SAVED)
}

/** Reads and writes My foods and the recent list as JSON. A broken value reads as an empty list. */
object SavedFoodCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeFoods(foods: List<SavedFood>): String = buildJsonArray {
        foods.forEach { f ->
            add(buildJsonObject {
                put("name", f.name)
                put("uses", f.uses)
                put("last", f.lastUsedMs)
                put("items", encodeItemArray(f.items))
            })
        }
    }.toString()

    fun decodeFoods(text: String): List<SavedFood> = array(text).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val name = (o["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val items = (o["items"] as? JsonArray).orEmpty().mapNotNull { decodeItem(it) }
        if (items.isEmpty()) null
        else SavedFood(name, items, (o["uses"] as? JsonPrimitive)?.intOrNull ?: 0, (o["last"] as? JsonPrimitive)?.longOrNull ?: 0L)
    }

    fun encodeItems(items: List<FoodItem>): String = encodeItemArray(items).toString()

    fun decodeItems(text: String): List<FoodItem> = array(text).mapNotNull { decodeItem(it) }

    private fun encodeItemArray(items: List<FoodItem>) = buildJsonArray {
        items.forEach { i ->
            add(buildJsonObject {
                put("name", i.name)
                put("grams", i.baseGrams)
                put("portion", i.portion)
                put("quantity", i.quantity)
                put("carbs", i.carbsPer100)
                put("fibre", i.fibrePer100)
                put("protein", i.proteinPer100)
                put("fat", i.fatPer100)
            })
        }
    }

    private fun decodeItem(e: JsonElement): FoodItem? {
        val o = e as? JsonObject ?: return null
        fun num(key: String) = (o[key] as? JsonPrimitive)?.doubleOrNull
        val name = (o["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null
        val grams = num("grams")?.takeIf { it > 0.0 } ?: return null
        return FoodItem(
            name = name,
            baseGrams = grams,
            portion = (o["portion"] as? JsonPrimitive)?.content.orEmpty(),
            quantity = num("quantity")?.takeIf { it > 0.0 } ?: 1.0,
            carbsPer100 = (num("carbs") ?: 0.0).coerceIn(0.0, 100.0),
            fibrePer100 = (num("fibre") ?: 0.0).coerceIn(0.0, 100.0),
            proteinPer100 = (num("protein") ?: 0.0).coerceIn(0.0, 100.0),
            fatPer100 = (num("fat") ?: 0.0).coerceIn(0.0, 100.0),
            source = FoodSource.SAVED
        )
    }

    private fun array(text: String): JsonArray =
        if (text.isBlank()) JsonArray(emptyList()) else runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: JsonArray(emptyList())
}

/**
 * My foods and the recent list, kept in the settings. My foods goes with a settings export, so a new phone
 * keeps it. The recent list does not: it is only a short memory of this phone's last lookups.
 */
@SingleIn(AppScope::class)
@Inject
class SavedFoodStore(private val preferences: Preferences) {

    var foods: List<SavedFood> = SavedFoodList.sorted(SavedFoodCodec.decodeFoods(preferences.get(StringNonKey.ApsSmartInsulinSavedFoods)))
        private set
    var recent: List<FoodItem> = SavedFoodCodec.decodeItems(preferences.get(StringNonKey.ApsSmartInsulinRecentFoods))
        private set

    fun save(name: String, items: List<FoodItem>, nowMs: Long): SaveResult {
        val (list, result) = SavedFoodList.save(foods, name, items, nowMs)
        if (result == SaveResult.SAVED || result == SaveResult.REPLACED) storeFoods(list)
        return result
    }

    fun markUsed(name: String, nowMs: Long) = storeFoods(SavedFoodList.markUsed(foods, name, nowMs))
    fun delete(name: String) = storeFoods(SavedFoodList.delete(foods, name))
    fun rename(name: String, newName: String) = storeFoods(SavedFoodList.rename(foods, name, newName))

    fun remember(items: List<FoodItem>) {
        if (items.isEmpty()) return
        recent = SavedFoodList.remember(recent, items)
        preferences.put(StringNonKey.ApsSmartInsulinRecentFoods, SavedFoodCodec.encodeItems(recent))
    }

    private fun storeFoods(list: List<SavedFood>) {
        foods = SavedFoodList.sorted(list)
        preferences.put(StringNonKey.ApsSmartInsulinSavedFoods, SavedFoodCodec.encodeFoods(foods))
    }
}
