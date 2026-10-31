package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.smartInsulin.FoodLog
import app.aaps.core.interfaces.smartInsulin.FoodLogEntry
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
    fun `old days are removed and the rest survives a restart`() {
        foodLog.add(entry(T0, "Old"))
        foodLog.add(entry(T0 + (FoodLog.DAYS_KEPT + 2) * DAY, "New"))
        foodLog = FoodLogImpl(sp, log)
        assertEquals(listOf("New"), foodLog.entries(0L, Long.MAX_VALUE).map { it.name })
    }

    private companion object {

        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        const val T0 = 1_790_000_000_000L
    }
}
