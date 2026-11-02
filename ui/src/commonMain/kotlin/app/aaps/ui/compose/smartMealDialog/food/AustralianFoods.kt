package app.aaps.ui.compose.smartMealDialog.food

/**
 * Plain foods from the Australian Food Composition Database ([australianFoodData]), searched on the phone without a
 * network. Packaged products are searched online in Open Food Facts instead.
 */
internal object AustralianFoods {

    /** One food of the table, per 100 g. [liquid] foods are per 100 mL, taken as 100 g. */
    data class Food(val name: String, val carbs: Double, val fibre: Double, val protein: Double, val fat: Double, val liquid: Boolean) {

        internal val words: List<String> = words(name)

        fun toItem(): FoodItem = FoodItem(
            name = name, baseGrams = 100.0, portion = "",
            carbsPer100 = carbs, fibrePer100 = fibre, proteinPer100 = protein, fatPer100 = fat,
            source = FoodSource.SEARCH
        )
    }

    /** Fewer letters than this find too much to be useful. */
    const val MIN_QUERY = 2

    val foods: List<Food> by lazy { parse(australianFoodData) }

    internal fun parse(chunks: List<String>): List<Food> = chunks.flatMap { it.lineSequence() }.mapNotNull { line ->
        val f = line.split('|')
        if (f.size < 5) return@mapNotNull null
        Food(
            name = f[0],
            carbs = f[1].toDoubleOrNull() ?: return@mapNotNull null,
            fibre = f[2].toDoubleOrNull() ?: 0.0,
            protein = f[3].toDoubleOrNull() ?: 0.0,
            fat = f[4].toDoubleOrNull() ?: 0.0,
            liquid = f.getOrNull(5) == "l"
        )
    }

    /**
     * The foods whose name has a word starting with every word of [query], best first: the name starting with the
     * first word of the query (whole word before part of one, so "milk" puts milk before milkfish), then names that
     * say less. "chicken thighs" finds "Chicken, thigh, ...".
     */
    fun search(query: String, limit: Int = 30, from: List<Food> = foods): List<Food> {
        val q = words(query).map { it.singular() }
        if (q.isEmpty() || query.trim().length < MIN_QUERY) return emptyList()
        return from.asSequence()
            .filter { food -> q.all { w -> food.words.any { it.startsWith(w) } } }
            .sortedWith(compareBy<Food>({ firstWordRank(it, q[0]) }, { it.words.size }, { it.name.length }))
            .take(limit)
            .toList()
    }

    private fun firstWordRank(food: Food, word: String): Int {
        val first = food.words.firstOrNull() ?: return 2
        return when {
            first.singular() == word -> 0
            first.startsWith(word)   -> 1
            else                     -> 2
        }
    }

    private fun words(text: String): List<String> = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** "thighs" also finds "thigh"; short words like "gas" stay as they are. */
    private fun String.singular(): String = if (length > 3 && endsWith('s') && !endsWith("ss")) dropLast(1) else this
}
