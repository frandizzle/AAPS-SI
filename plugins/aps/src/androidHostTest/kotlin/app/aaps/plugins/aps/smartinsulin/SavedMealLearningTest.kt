package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/** [SavedMealLearningImpl]: each saved meal learns its own carb and fat/protein insulin. */
class SavedMealLearningTest {

    private val sp = FakePreferences()
    private val log: AAPSLogger = mock()
    private var learning = SavedMealLearningImpl(sp, log)

    /**
     * One 3 h Dinner started from the saved meal "Wrap", planned with 40 g carbs and 20 g of fat and protein.
     * The tracker sees [carbsSeen] in the first 2 h and [fpSeen] in the last hour.
     *
     * @param lowAtMin  BG is low at this minute after the start
     * @param riseAtMin BG goes up 1.7 mmol at this minute
     * @param endAtMin  the mode stops at this minute
     */
    private fun meal(start: Long, carbsSeen: Double, fpSeen: Double, lowAtMin: Int? = null, riseAtMin: Int = 30, endAtMin: Int = 180) {
        learning.recordMeal("Wrap", start, start + 180 * MIN, start + 180 * MIN, carbsG = 40.0, fatProteinG = 20.0)
        var grams = 0.0
        var m = 0
        while (m <= 180 + 60 + 10) {
            val active = m < endAtMin
            if (active) grams += if (m < 120) carbsSeen / 24.0 else fpSeen / 12.0
            learning.onCycle(
                nowMs = start + m * MIN,
                activeMode = if (active) MealMode.DINNER else null,
                modeStartMs = if (active) start else 0L,
                episodeStartMs = if (active) start else 0L,
                episodeGrams = if (active) grams else 0.0,
                bgMgdl = if (m >= riseAtMin) 130.0 else 100.0,
                lowActive = m == lowAtMin,
                undershootActive = false,
                contaminated = false
            )
            m += 5
        }
    }

    @Test
    fun `each part moves toward what BG showed, by one bounded step`() {
        meal(T0, carbsSeen = 60.0, fpSeen = 20.0)
        val l = learning.learned("Wrap")!!
        assertEquals(1.12, l.carbFactor, 1e-9)          // wanted 1.5, one step at most
        assertEquals(1.0, l.fatProteinFactor, 1e-9)
        assertEquals(1, l.meals)
        assertFalse(l.inUse)
        meal(T0 + DAY, carbsSeen = 60.0, fpSeen = 20.0)
        assertTrue(learning.learned("Wrap")!!.inUse)
        assertEquals(1.12 * 1.12, learning.learned("Wrap")!!.carbFactor, 1e-9)
    }

    @Test
    fun `a low cuts the part it happened in, and then nothing grows`() {
        meal(T0, carbsSeen = 60.0, fpSeen = 30.0, lowAtMin = 60)
        val l = learning.learned("Wrap")!!
        assertEquals(0.85, l.carbFactor, 1e-9)
        assertEquals(1.0, l.fatProteinFactor, 1e-9)     // saw 1.5 times the plan, but a low came first
    }

    @Test
    fun `a late low cuts the fat and protein`() {
        meal(T0, carbsSeen = 40.0, fpSeen = 20.0, lowAtMin = 200)
        val l = learning.learned("Wrap")!!
        assertEquals(1.0, l.carbFactor, 1e-9)
        assertEquals(0.85, l.fatProteinFactor, 1e-9)
    }

    @Test
    fun `a meal ended early is not learned from`() {
        meal(T0, carbsSeen = 60.0, fpSeen = 20.0, endAtMin = 60)
        assertNull(learning.learned("Wrap"))
    }

    @Test
    fun `a meal that got other food is not learned from`() {
        learning.recordMeal("Wrap", T0, T0 + 180 * MIN, T0 + 180 * MIN, 40.0, 20.0)
        learning.dropMeal(T0)
        learning.onCycle(T0 + 5 * HOUR, null, 0L, 0L, 0.0, 100.0, lowActive = false, undershootActive = false, contaminated = false)
        assertNull(learning.learned("Wrap"))
    }

    @Test
    fun `a rise right after the start is noted as eaten without waiting`() {
        meal(T0, 40.0, 20.0, riseAtMin = 10)
        meal(T0 + DAY, 40.0, 20.0, riseAtMin = 30)
        val l = learning.learned("Wrap")!!
        assertEquals(1, l.ateSoon)
        assertEquals(2, l.recentMeals)
    }

    @Test
    fun `the other meal learners sit out a watched meal`() {
        learning.recordMeal("Wrap", T0, T0 + 180 * MIN, T0 + 180 * MIN, 40.0, 20.0)
        assertFalse(learning.watching(T0 - MIN))
        assertTrue(learning.watching(T0 + 200 * MIN))    // the low watch after the window
        assertFalse(learning.watching(T0 + 300 * MIN))
    }

    @Test
    fun `what is learned survives a restart, a rename and is gone when forgotten`() {
        meal(T0, carbsSeen = 60.0, fpSeen = 20.0)
        learning = SavedMealLearningImpl(sp, log)
        assertEquals(1.12, learning.learned("Wrap")!!.carbFactor, 1e-9)
        learning.rename("Wrap", "Turkey wrap")
        assertNull(learning.learned("Wrap"))
        assertEquals(1.12, learning.learned("Turkey wrap")!!.carbFactor, 1e-9)
        learning.forget("Turkey wrap")
        assertNull(learning.learned("Turkey wrap"))
    }

    @Test
    fun `factors stay within their limits`() {
        var f = 1.0
        repeat(20) { f = SavedMealLearningImpl.step(f, 0.1, low = true, undershoot = false, anyLow = true) }
        assertEquals(SavedMealLearningImpl.MIN_FACTOR, f, 1e-9)
        repeat(20) { f = SavedMealLearningImpl.step(f, 3.0, low = false, undershoot = false, anyLow = false) }
        assertEquals(SavedMealLearningImpl.MAX_FACTOR, f, 1e-9)
    }

    private companion object {

        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val DAY = 24 * HOUR
        const val T0 = 1_790_000_000_000L
    }
}
