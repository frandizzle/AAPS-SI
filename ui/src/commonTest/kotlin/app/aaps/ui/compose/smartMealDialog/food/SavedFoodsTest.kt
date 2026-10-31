package app.aaps.ui.compose.smartMealDialog.food

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** My foods, the recent list and foods typed in by hand. */
class SavedFoodsTest {

    private fun food(name: String, grams: Double = 50.0, carbs: Double = 40.0, quantity: Double = 1.0) =
        FoodItem(name, baseGrams = grams, portion = "1 piece", quantity = quantity, carbsPer100 = carbs, fibrePer100 = 1.0,
                 proteinPer100 = 10.0, fatPer100 = 5.0, source = FoodSource.BARCODE)

    private val wrap = listOf(food("Wrap"), food("Turkey", carbs = 0.0, quantity = 2.0), food("Cheese", grams = 20.0, carbs = 1.0))

    @Test
    fun `a meal is saved with all its foods and amounts`() {
        val (list, result) = SavedFoodList.save(emptyList(), "  Turkey wrap ", wrap, 1L)
        assertEquals(SaveResult.SAVED, result)
        val saved = list.single()
        assertEquals("Turkey wrap", saved.name)
        assertEquals(3, saved.items.size)
        assertEquals(2.0, saved.items[1].quantity, 1e-9)
        assertEquals(wrap.totals().carbsG, saved.totals.carbsG, 1e-9)
    }

    @Test
    fun `the same name replaces the entry and keeps its uses`() {
        var list = SavedFoodList.save(emptyList(), "Turkey wrap", wrap, 1L).first
        list = SavedFoodList.markUsed(list, "Turkey wrap", 2L)
        val (again, result) = SavedFoodList.save(list, "turkey WRAP", wrap.take(1), 3L)
        assertEquals(SaveResult.REPLACED, result)
        assertEquals(1, again.size)
        assertEquals(1, again.single().uses)
        assertEquals(1, again.single().items.size)
    }

    @Test
    fun `a full list takes no new entry, and an empty one is not saved`() {
        var list = emptyList<SavedFood>()
        repeat(SavedFoodList.MAX_SAVED) { list = SavedFoodList.save(list, "Food $it", wrap, 1L).first }
        assertEquals(SaveResult.FULL, SavedFoodList.save(list, "One more", wrap, 1L).second)
        assertEquals(SaveResult.REPLACED, SavedFoodList.save(list, "Food 3", wrap, 1L).second)
        assertEquals(SaveResult.EMPTY, SavedFoodList.save(emptyList(), " ", wrap, 1L).second)
        assertEquals(SaveResult.EMPTY, SavedFoodList.save(emptyList(), "Air", emptyList(), 1L).second)
    }

    @Test
    fun `the most used come first, then the most recent`() {
        val list = listOf(SavedFood("A", wrap, uses = 1, lastUsedMs = 5L), SavedFood("B", wrap, uses = 3, lastUsedMs = 1L), SavedFood("C", wrap, uses = 1, lastUsedMs = 9L))
        assertEquals(listOf("B", "C", "A"), SavedFoodList.sorted(list).map { it.name })
    }

    @Test
    fun `search finds the name or a food inside`() {
        val list = listOf(SavedFood("Turkey wrap", wrap), SavedFood("Toast", listOf(food("Bread"))))
        assertEquals(listOf("Turkey wrap"), SavedFoodList.search(list, "cheese").map { it.name })
        assertEquals(listOf("Toast"), SavedFoodList.search(list, "TOA").map { it.name })
        assertEquals(2, SavedFoodList.search(list, "").size)
    }

    @Test
    fun `rename does not take a name already used, and delete removes`() {
        val list = listOf(SavedFood("Wrap", wrap), SavedFood("Toast", wrap))
        assertEquals(listOf("Wrap", "Toast"), SavedFoodList.rename(list, "Wrap", "toast").map { it.name })
        assertEquals(listOf("Lunch wrap", "Toast"), SavedFoodList.rename(list, "Wrap", "Lunch wrap").map { it.name })
        assertEquals(listOf("Toast"), SavedFoodList.delete(list, "Wrap").map { it.name })
    }

    @Test
    fun `the recent list keeps each food once, newest first, as one portion`() {
        var recent = SavedFoodList.remember(emptyList(), listOf(food("Wrap", quantity = 2.0)))
        recent = SavedFoodList.remember(recent, listOf(food("Yoghurt"), food("wrap")))
        assertEquals(listOf("Yoghurt", "wrap"), recent.map { it.name })
        assertEquals(1.0, recent[1].quantity, 1e-9)
        repeat(20) { recent = SavedFoodList.remember(recent, listOf(food("Food $it"))) }
        assertEquals(SavedFoodList.MAX_RECENT, recent.size)
    }

    @Test
    fun `My foods and the recent list survive being stored, and a broken value reads as empty`() {
        val list = listOf(SavedFood("Turkey wrap", wrap, uses = 4, lastUsedMs = 123L))
        val back = SavedFoodCodec.decodeFoods(SavedFoodCodec.encodeFoods(list)).single()
        assertEquals("Turkey wrap", back.name)
        assertEquals(4, back.uses)
        assertEquals(123L, back.lastUsedMs)
        assertEquals(wrap.map { it.copy(source = FoodSource.SAVED) }, back.items)
        assertEquals(wrap.map { it.copy(source = FoodSource.SAVED) }, SavedFoodCodec.decodeItems(SavedFoodCodec.encodeItems(wrap)))
        assertTrue(SavedFoodCodec.decodeFoods("not json").isEmpty())
        assertTrue(SavedFoodCodec.decodeFoods("").isEmpty())
        assertTrue(SavedFoodCodec.decodeItems("{}").isEmpty())
    }

    @Test
    fun `a food typed in by hand is kept per 100 g`() {
        // A home curry: 350 g with 45 g carbs, 30 g protein, 18 g fat.
        val curry = manualFood(" Curry ", "1 bowl", 350.0, 45.0, 30.0, 18.0)!!
        assertEquals("Curry", curry.name)
        assertEquals(FoodSource.MANUAL, curry.source)
        assertEquals(45.0, curry.carbsG, 1e-9)
        assertEquals(30.0, curry.proteinG, 1e-9)
        assertEquals(90.0, curry.copy(quantity = 2.0).carbsG, 1e-9)
    }

    @Test
    fun `numbers that cannot be right are refused`() {
        assertNull(manualFood("Curry", "", 0.0, 10.0, 0.0, 0.0))
        assertNull(manualFood("Curry", "", 100.0, -1.0, 0.0, 0.0))
        assertNull(manualFood("Curry", "", 100.0, 60.0, 30.0, 20.0))
        assertNull(manualFood(" ", "", 100.0, 10.0, 0.0, 0.0))
    }
}
