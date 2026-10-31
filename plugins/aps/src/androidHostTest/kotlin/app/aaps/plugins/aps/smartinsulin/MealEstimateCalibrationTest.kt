package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/** [MealEstimateCalibrationImpl]: how estimated meals compare with what the meal tracker saw. */
class MealEstimateCalibrationTest {

    private val sp = FakePreferences()
    private val csv: MealAbsorptionCsvLogger = mock()
    private val log: AAPSLogger = mock()
    private var cal = MealEstimateCalibrationImpl(sp, csv, log)

    /** One estimated meal of [estimateG] that the tracker saw as [trackerG], split over [episodes]. */
    private fun meal(startMs: Long, estimateG: Double, vararg trackerG: Double) {
        cal.recordEstimate(startMs, startMs + 3 * HOUR, estimateG, estimateG, 0.0, 0.0)
        trackerG.forEachIndexed { i, g -> cal.onEpisode(episode(startMs + i * 2 * HOUR, g)) }
        cal.onCycle(startMs + 3 * HOUR + MealEstimateCalibrationImpl.GRACE_MS + 1)
    }

    private fun episode(startMs: Long, grams: Double) = CompletedMealEpisode(startMs, MealMode.DINNER, HOUR, grams)

    @Test
    fun `food still to come is the estimate less what the tracker has seen`() {
        cal.recordEstimate(T0, T0 + 3 * HOUR, 80.0, 60.0, 30.0, 10.0)
        assertEquals(80.0, cal.foodStillToComeG(T0)!!, 1e-9)
        cal.onCycle(T0 + HOUR, InProgressEpisode(MealMode.DINNER, T0, 30.0))
        assertEquals(50.0, cal.foodStillToComeG(T0)!!, 1e-9)
        // The episode ends, then a Protein/Fat one starts inside the same window.
        cal.onEpisode(episode(T0, 60.0))
        cal.onCycle(T0 + 2 * HOUR, InProgressEpisode(MealMode.UAM_PROTEIN_FAT, T0 + 2 * HOUR, 30.0))
        assertEquals(0.0, cal.foodStillToComeG(T0)!!, 1e-9)    // never below zero
        assertEquals(null, cal.foodStillToComeG(T0 + DAY))    // a meal without an estimate
    }

    @Test
    fun `what the tracker saw is taken off the carbs first`() {
        // 60 g carbs, 20 g fat and protein as carbs; the tracker has seen 70 g.
        cal.recordEstimate(T0, T0 + 4 * HOUR, 80.0, 60.0, 30.0, 10.0)
        cal.onCycle(T0 + 2 * HOUR, InProgressEpisode(MealMode.DINNER, T0, 70.0))
        val s = cal.stillToCome(T0 + 2 * HOUR)!!
        assertEquals(0.0, s.carbsG, 1e-9)
        assertEquals(10.0, s.fatProteinG, 1e-9)
        assertEquals(T0 + 2 * HOUR, s.carbsUntilMs)
        assertEquals(null, cal.stillToCome(T0 + 5 * HOUR))     // after the window
    }

    @Test
    fun `carbs added later are expected over the 2 hours after they are eaten`() {
        cal.recordEstimate(T0, T0 + 4 * HOUR, 80.0, 60.0, 30.0, 10.0)
        cal.addToEstimate(T0, T0 + 4 * HOUR, 20.0, 20.0, 0.0, 0.0, eatenMs = T0 + 3 * HOUR)
        assertEquals(T0 + 5 * HOUR, cal.stillToCome(T0 + 3 * HOUR)!!.carbsUntilMs)
    }

    @Test
    fun `a meal not for learning still counts as food, but is not judged`() {
        repeat(3) {
            cal.recordEstimate(T0 + it * DAY, T0 + it * DAY + 3 * HOUR, 100.0, 100.0, 0.0, 0.0, learn = false)
            assertEquals(100.0, cal.foodStillToComeG(T0 + it * DAY)!!, 1e-9)
            cal.onEpisode(episode(T0 + it * DAY, 150.0))
            cal.onCycle(T0 + it * DAY + 3 * HOUR + MealEstimateCalibrationImpl.GRACE_MS + 1)
        }
        assertEquals(0, cal.mealsUsed)
    }

    @Test
    fun `the factor stays 1 until three meals are judged`() {
        meal(T0, 100.0, 140.0)
        meal(T0 + DAY, 100.0, 140.0)
        assertEquals(1.0, cal.factor, 1e-9)
        assertEquals(2, cal.mealsUsed)
        meal(T0 + 2 * DAY, 100.0, 140.0)
        assertEquals(1.4, cal.factor, 1e-9)
    }

    @Test
    fun `a meal and the Protein-Fat after it count together`() {
        repeat(3) { meal(T0 + it * DAY, 100.0, 84.0, 52.0) }
        assertEquals(1.36, cal.factor, 1e-9)
    }

    @Test
    fun `the median keeps one odd meal from moving the factor`() {
        meal(T0, 100.0, 110.0)
        meal(T0 + DAY, 100.0, 120.0)
        meal(T0 + 2 * DAY, 100.0, 300.0)   // a site problem, say
        assertEquals(1.2, cal.factor, 1e-9)
    }

    @Test
    fun `the factor is held between its limits`() {
        repeat(3) { meal(T0 + it * DAY, 100.0, 250.0) }
        assertEquals(MealEstimateCalibrationImpl.MAX_FACTOR, cal.factor, 1e-9)
        cal.reset()
        repeat(3) { meal(T0 + it * DAY, 100.0, 40.0) }
        assertEquals(MealEstimateCalibrationImpl.MIN_FACTOR, cal.factor, 1e-9)
    }

    @Test
    fun `an episode outside the window or a meal without one is not used`() {
        cal.recordEstimate(T0, T0 + 3 * HOUR, 100.0, 100.0, 0.0, 0.0)
        cal.onEpisode(episode(T0 + 5 * HOUR, 80.0))
        cal.onCycle(T0 + 3 * HOUR + MealEstimateCalibrationImpl.GRACE_MS + 1)
        assertEquals(0, cal.mealsUsed)
        verify(csv, never()).logEstimate(any(), any(), any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `a meal is not judged before its window and grace are over`() {
        cal.recordEstimate(T0, T0 + 3 * HOUR, 100.0, 100.0, 0.0, 0.0)
        cal.onEpisode(episode(T0, 120.0))
        cal.onCycle(T0 + 4 * HOUR)
        assertEquals(0, cal.mealsUsed)
        cal.onCycle(T0 + 3 * HOUR + MealEstimateCalibrationImpl.GRACE_MS + 1)
        assertEquals(1, cal.mealsUsed)
        verify(csv).logEstimate(eq(T0), eq(100.0), eq(0.0), eq(0.0), eq(100.0), eq(120.0), eq(1.2), eq(1.0))
    }

    @Test
    fun `what is learned and what is waiting survive a restart`() {
        repeat(3) { meal(T0 + it * DAY, 100.0, 130.0) }
        cal.recordEstimate(T0 + 10 * DAY, T0 + 10 * DAY + 3 * HOUR, 50.0, 50.0, 0.0, 0.0)
        cal = MealEstimateCalibrationImpl(sp, csv, log)
        assertEquals(1.3, cal.factor, 1e-9)
        cal.onEpisode(episode(T0 + 10 * DAY, 75.0))
        cal.onCycle(T0 + 10 * DAY + 3 * HOUR + MealEstimateCalibrationImpl.GRACE_MS + 1)
        assertEquals(4, cal.mealsUsed)
    }

    @Test
    fun `a dessert is added to the meal it belongs to, and the window grows`() {
        cal.recordEstimate(T0, T0 + 3 * HOUR, 100.0, 100.0, 0.0, 0.0)
        cal.addToEstimate(T0, T0 + 5 * HOUR, 40.0, 40.0, 0.0, 0.0, T0 + HOUR)
        cal.onEpisode(episode(T0, 100.0))
        cal.onEpisode(episode(T0 + 4 * HOUR, 54.0))     // only inside the longer window
        cal.onCycle(T0 + 3 * HOUR + MealEstimateCalibrationImpl.GRACE_MS + 1)
        assertEquals(0, cal.mealsUsed)                    // not judged before the new end
        cal.onCycle(T0 + 5 * HOUR + MealEstimateCalibrationImpl.GRACE_MS + 1)
        assertEquals(1, cal.mealsUsed)
        verify(csv).logEstimate(eq(T0), eq(140.0), eq(0.0), eq(0.0), eq(140.0), eq(154.0), eq(1.1), eq(1.0))
    }

    @Test
    fun `a dessert for a meal without an estimate is not recorded`() {
        cal.addToEstimate(T0, T0 + 5 * HOUR, 40.0, 40.0, 0.0, 0.0, T0 + HOUR)
        cal.onEpisode(episode(T0, 60.0))
        cal.onCycle(T0 + 5 * HOUR + MealEstimateCalibrationImpl.GRACE_MS + 1)
        assertEquals(0, cal.mealsUsed)
    }

    @Test
    fun `food added without an estimate drops the meal`() {
        cal.recordEstimate(T0, T0 + 3 * HOUR, 100.0, 100.0, 0.0, 0.0)
        cal.dropEstimate(T0)
        cal.onEpisode(episode(T0, 150.0))
        cal.onCycle(T0 + 3 * HOUR + MealEstimateCalibrationImpl.GRACE_MS + 1)
        assertEquals(0, cal.mealsUsed)
    }

    private companion object {

        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        const val T0 = 1_790_000_000_000L
    }
}
