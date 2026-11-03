package app.aaps.ui.compose.smartMealDialog.food

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The plain foods searched on the phone, from the Australian Food Composition Database. */
class AustralianFoodsTest {

    private val table = AustralianFoods.parse(
        listOf(
            """
Chicken, thigh, lean flesh, baked, no added fat|0|0|24.2|8.7|
Chicken, thigh, lean flesh, skin & fat, baked, no added fat|0|0|22.1|13.5|
Chicken, breast, lean flesh, baked, no added fat|0|0|31|3|0
Sauce, chicken flavoured|9.5|0.5|1|2|
""",
            """
Milk, cow, fluid, regular fat|4.7|0|3.4|3.5|4.7|l
Milkfish, fillet, raw|0|0|23.4|10.7|
Juice, orange, no added sugar|8.1|0.4|0.6|0.1|8.1|l
broken line
"""
        )
    )

    @Test
    fun `the table is read, liquids included, and bad lines are left out`() {
        assertEquals(7, table.size)
        assertTrue(table.single { it.name.startsWith("Milk,") }.liquid)
        assertEquals(null, table.single { it.name.startsWith("Sauce") }.sugars)
    }

    @Test
    fun `a food from the table comes with a guessed speed`() {
        assertEquals(FoodSpeed.FAST, table.single { it.name.startsWith("Juice") }.toItem().speed)
        // Milk sugar comes with as much protein and fat.
        assertEquals(FoodSpeed.SLOW, table.single { it.name.startsWith("Milk,") }.toItem().speed)
        assertEquals(FoodSpeed.NORMAL, table.single { it.name.startsWith("Sauce") }.toItem().speed)
    }

    @Test
    fun `every word must match the start of a word, and plurals still match`() {
        val found = AustralianFoods.search("chicken thighs", from = table).map { it.name }
        assertEquals(listOf("Chicken, thigh, lean flesh, baked, no added fat", "Chicken, thigh, lean flesh, skin & fat, baked, no added fat"), found)
    }

    @Test
    fun `a name starting with the first word comes first, then the shorter one`() {
        val found = AustralianFoods.search("chicken", from = table).map { it.name }
        // Same number of words: the shorter name first.
        assertEquals(listOf("Chicken, thigh, lean flesh, baked, no added fat", "Chicken, breast, lean flesh, baked, no added fat"), found.take(2))
        assertEquals("Sauce, chicken flavoured", found.last())
    }

    @Test
    fun `a whole word comes before part of one`() {
        assertEquals(listOf("Milk, cow, fluid, regular fat", "Milkfish, fillet, raw"), AustralianFoods.search("milk", from = table).map { it.name })
    }

    @Test
    fun `one letter finds nothing`() {
        assertTrue(AustralianFoods.search("c", from = table).isEmpty())
    }

    @Test
    fun `a food is added as 100 g, counted as it is`() {
        val item = table.first().toItem()
        assertEquals(100.0, item.baseGrams, 1e-9)
        assertEquals(24.2, item.proteinG, 1e-9)
        assertEquals(FoodSource.SEARCH, item.source)
    }

    @Test
    fun `the real table loads and finds a cooked chicken thigh`() {
        assertTrue(AustralianFoods.foods.size > 1500)
        assertTrue(AustralianFoods.search("chicken thigh baked").any { it.name.startsWith("Chicken, thigh") })
    }
}
