package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.smartInsulin.FoodLog
import app.aaps.core.interfaces.smartInsulin.FoodLogEntry
import app.aaps.core.interfaces.smartInsulin.FoodLogItem
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/** [FoodLogImpl]: what was eaten each day, for the calorie count. */
class FoodLogTest {

    private val sp = FakePreferences()
    private val log: AAPSLogger = mock()
    private var foodLog = FoodLogImpl(sp, log)

    private fun entry(timeMs: Long, name: String = "Wrap") = FoodLogEntry(timeMs, name, carbsG = 40.0, fibreG = 3.0, proteinG = 25.0, fatG = 10.0)

    @Test
    fun `calories are counted as on food labels`() {
        // 160 + 6 + 100 + 90
        assertEquals(356.0, entry(T0).kcal, 1e-9)
    }

    @Test
    fun `a day holds only its own entries, oldest first`() {
        foodLog.add(entry(T0 + 2 * HOUR, "Lunch"))
        foodLog.add(entry(T0 + HOUR, "Breakfast"))
        foodLog.add(entry(T0 + DAY + HOUR, "Tomorrow"))
        assertEquals(listOf("Breakfast", "Lunch"), foodLog.entries(T0, T0 + DAY).map { it.name })
    }

    @Test
    fun `an entry can be deleted, and two in the same millisecond stay apart`() {
        foodLog.add(entry(T0, "One"))
        foodLog.add(entry(T0, "Two"))
        assertEquals(2, foodLog.entries(T0, T0 + DAY).size)
        foodLog.delete(T0)
        assertEquals(listOf("Two"), foodLog.entries(T0, T0 + DAY).map { it.name })
    }

    @Test
    fun `an entry can be changed, for example to half`() {
        foodLog.add(entry(T0))
        foodLog.update(entry(T0).copy(carbsG = 20.0))
        assertEquals(20.0, foodLog.entries(T0, T0 + DAY).single().carbsG, 1e-9)
    }

    @Test
    fun `old days are removed and the rest survives a restart`() {
        foodLog.add(entry(T0, "Old"))
        foodLog.add(entry(T0 + (FoodLog.DAYS_KEPT + 2) * DAY, "New"))
        foodLog = FoodLogImpl(sp, log)
        assertEquals(listOf("New"), foodLog.entries(0L, Long.MAX_VALUE).map { it.name })
    }

    private val chicken = FoodLogItem("Chicken schnitzel", 150.0, carbsG = 15.0, fibreG = 1.0, proteinG = 24.0, fatG = 15.0)
    private val chips = FoodLogItem("Thick cut hot chips", 120.0, carbsG = 40.0, fibreG = 4.0, proteinG = 4.0, fatG = 8.0)

    /** A meal with its foods kept, as the Smart Meal dialog logs it. */
    private fun meal(timeMs: Long) = FoodLogEntry(timeMs, "Schnitzel and chips", 55.0, 5.0, 28.0, 23.0, listOf(chicken, chips))

    @Test
    fun `each food of a meal is kept, also over a restart`() {
        foodLog.add(meal(T0))
        foodLog.add(entry(T0 + HOUR))
        foodLog = FoodLogImpl(sp, log)
        val (logged, older) = foodLog.entries(T0, T0 + DAY)
        assertEquals(listOf(chicken, chips), logged.items)
        // An entry without foods, as logged before they were kept, still reads.
        assertEquals(emptyList<FoodLogItem>(), older.items)
        assertEquals(40.0, older.carbsG, 1e-9)
    }

    @Test
    fun `one food can change on its own, and the totals follow it`() {
        // Half the chips, all the chicken.
        val half = meal(T0).withAmounts(listOf(1.0, 0.5))
        assertEquals(chicken, half.items[0])
        assertEquals(60.0, half.items[1].grams, 1e-9)
        assertEquals(15.0 + 20.0, half.carbsG, 1e-9)
        assertEquals(24.0 + 2.0, half.proteinG, 1e-9)
        assertEquals(15.0 + 4.0, half.fatG, 1e-9)
        assertEquals(1.0 + 2.0, half.fibreG, 1e-9)
    }

    @Test
    fun `all of it can change together, with or without its foods`() {
        val meal = meal(T0).times(0.75)
        assertEquals(listOf(112.5, 90.0), meal.items.map { it.grams })
        assertEquals(55.0 * 0.75, meal.carbsG, 1e-9)
        val old = entry(T0).times(0.5)
        assertEquals(20.0, old.carbsG, 1e-9)
        assertEquals(5.0, old.fatG, 1e-9)
    }

    private companion object {

        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        const val T0 = 1_790_000_000_000L
    }
}
