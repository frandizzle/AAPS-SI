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
        val s = cal.stillToCome(T0, T0 + 2 * HOUR)!!
        assertEquals(0.0, s.carbsG, 1e-9)
        assertEquals(10.0, s.fatProteinG, 1e-9)
        assertEquals(T0 + 2 * HOUR, s.carbsUntilMs)
        assertEquals(null, cal.stillToCome(T0, T0 + 5 * HOUR))     // after the window
    }

    @Test
    fun `food the insulin accounts for counts as seen when it is more than the rise`() {
        cal.recordEstimate(T0, T0 + 4 * HOUR, 80.0, 60.0, 30.0, 10.0)
        cal.onCycle(T0 + 2 * HOUR, InProgressEpisode(MealMode.DINNER, T0, estimatedGramsSoFar = 10.0, balanceGramsSoFar = 55.0))
        assertEquals(25.0, cal.foodStillToComeG(T0)!!, 1e-9)
    }

    @Test
    fun `carbs added later are expected over the 2 hours after they are eaten`() {
        cal.recordEstimate(T0, T0 + 4 * HOUR, 80.0, 60.0, 30.0, 10.0)
        cal.addToEstimate(T0, T0 + 4 * HOUR, 20.0, 20.0, 0.0, 0.0, eatenMs = T0 + 3 * HOUR)
        assertEquals(T0 + 5 * HOUR, cal.stillToCome(T0, T0 + 3 * HOUR)!!.carbsUntilMs)
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
        verify(csv, never()).logEstimate(any(), any(), any(), any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `a meal is not judged before its window and grace are over`() {
        cal.recordEstimate(T0, T0 + 3 * HOUR, 100.0, 100.0, 0.0, 0.0)
        cal.onEpisode(episode(T0, 120.0))
        cal.onCycle(T0 + 4 * HOUR)
        assertEquals(0, cal.mealsUsed)
        cal.onCycle(T0 + 3 * HOUR + MealEstimateCalibrationImpl.GRACE_MS + 1)
        assertEquals(1, cal.mealsUsed)
        verify(csv).logEstimate(eq(T0), eq(100.0), eq(0.0), eq(0.0), eq(100.0), eq(120.0), eq(1.2), eq(1.0), eq(120))
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
        verify(csv).logEstimate(eq(T0), eq(140.0), eq(0.0), eq(0.0), eq(140.0), eq(154.0), eq(1.1), eq(1.0), eq(120))
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

    @Test
    fun `fat and protein start 45 min after the meal, or after the first food that has them`() {
        cal.recordEstimate(T0, T0 + 3 * HOUR, 30.0, 10.0, 9.0, 9.0)
        assertEquals(T0 + 45 * MIN, cal.stillToCome(T0, T0)!!.fatProteinFromMs)
        // A meal of carbs only: fat and protein added an hour later start 45 min after that.
        cal.recordEstimate(T0 + DAY, T0 + DAY + 3 * HOUR, 40.0, 40.0, 0.0, 0.0)
        cal.addToEstimate(T0 + DAY, T0 + DAY + 3 * HOUR, 20.0, 5.0, 10.0, 10.0, T0 + DAY + HOUR)
        assertEquals(T0 + DAY + HOUR + 45 * MIN, cal.stillToCome(T0 + DAY, T0 + DAY)!!.fatProteinFromMs)
    }

    @Test
    fun `a logged food made smaller makes its meal smaller`() {
        // A cup of milk, 10 g carbs, 9 g protein, 9 g fat: 1.17 fat-protein units = 11.7 g.
        cal.recordEstimate(T0, T0 + 3 * HOUR, 21.7, 10.0, 9.0, 9.0)
        // Only a quarter of it was drunk.
        cal.changeLoggedFood(T0 + 10_000L, -7.5, -6.75, -6.75)
        val still = cal.stillToCome(T0, T0)!!
        assertEquals(2.5, still.carbsG, 1e-9)
        assertEquals(21.7 / 4, still.carbsG + still.fatProteinG, 1e-9)
    }

    @Test
    fun `a removed food leaves nothing, and food from another time changes no meal`() {
        cal.recordEstimate(T0, T0 + 3 * HOUR, 40.0, 40.0, 0.0, 0.0)
        cal.changeLoggedFood(T0 + 4 * HOUR, -40.0, 0.0, 0.0)   // after the window
        assertEquals(40.0, cal.foodStillToComeG(T0)!!, 1e-9)
        cal.changeLoggedFood(T0, -40.0, 0.0, 0.0)
        assertEquals(0.0, cal.foodStillToComeG(T0)!!, 1e-9)
    }

    @Test
    fun `the held back carbs follow BG, and that survives a restart`() {
        cal.recordEstimate(T0, T0 + 3 * HOUR, 40.0, 40.0, 0.0, 0.0)
        cal.updateCarbTopUp(T0, T0 + 20 * MIN, 120.0, 0.0, 0.0)
        assertEquals(CarbTopUp.WAITING, cal.stillToCome(T0, T0 + 20 * MIN)!!.carbTopUp)
        cal.updateCarbTopUp(T0, T0 + 25 * MIN, 125.0, 3.0, 2.0)
        cal = MealEstimateCalibrationImpl(sp, csv, log)
        assertEquals(CarbTopUp.GIVEN, cal.stillToCome(T0, T0 + 30 * MIN)!!.carbTopUp)
    }

    @Test
    fun `a meal with exercise is not judged, also after a restart`() {
        cal.recordEstimate(T0, T0 + 3 * HOUR, 100.0, 100.0, 0.0, 0.0)
        cal.noteExercise(T0 - MIN)                       // before the meal: not this one
        cal.noteExercise(T0 + 4 * HOUR)                  // after its window: not this one
        cal.noteExercise(T0 + HOUR)
        cal = MealEstimateCalibrationImpl(sp, csv, log)
        assertEquals(100.0, cal.foodStillToComeG(T0)!!, 1e-9)   // still food for the forecast
        cal.onEpisode(episode(T0, 60.0))
        cal.onCycle(T0 + 3 * HOUR + MealEstimateCalibrationImpl.GRACE_MS + 1)
        assertEquals(0, cal.mealsUsed)
        meal(T0 + DAY, 100.0, 120.0)
        assertEquals(1, cal.mealsUsed)
    }

    @Test
    fun `fast carbs are expected sooner and slow ones later, also for food added later and after a restart`() {
        cal.recordEstimate(T0, T0 + 4 * HOUR, 40.0, 40.0, 0.0, 0.0, carbsMin = 75)
        assertEquals(T0 + 75 * MIN, cal.stillToCome(T0, T0)!!.carbsUntilMs)
        cal.addToEstimate(T0, T0 + 4 * HOUR, 20.0, 20.0, 0.0, 0.0, eatenMs = T0 + HOUR, carbsMin = 180)
        cal = MealEstimateCalibrationImpl(sp, csv, log)
        assertEquals(T0 + 4 * HOUR, cal.stillToCome(T0, T0 + HOUR)!!.carbsUntilMs)
        cal.onEpisode(episode(T0, 60.0))
        cal.onCycle(T0 + 4 * HOUR + MealEstimateCalibrationImpl.GRACE_MS + 1)
        verify(csv).logEstimate(eq(T0), eq(60.0), eq(0.0), eq(0.0), eq(60.0), eq(60.0), eq(1.0), eq(1.0), eq(75))
    }

    @Test
    fun `food logged in a meal mode without an estimate becomes its food, but is not learned from`() {
        cal.logFood(T0, T0 + HOUR, 52.0, 36.0, 4.0, 16.0, eatenMs = T0 + 10 * MIN)
        val still = cal.stillToCome(T0, T0 + 10 * MIN)!!
        assertEquals(36.0, still.carbsG, 1e-9)
        assertEquals(16.0, still.fatProteinG, 1e-9)
        assertEquals(T0 + 10 * MIN + 2 * HOUR, still.carbsUntilMs)
        assertEquals(T0 + 10 * MIN + 2 * HOUR, still.windowEndMs)   // the food's 2 hours, past the mode's hour
        cal.onEpisode(episode(T0, 50.0))
        cal.onCycle(T0 + 3 * HOUR + MealEstimateCalibrationImpl.GRACE_MS)
        assertEquals(0, cal.mealsUsed)
    }

    @Test
    fun `food logged in an estimated meal adds to it, and the meal is not learned from`() {
        cal.recordEstimate(T0, T0 + 3 * HOUR, 40.0, 40.0, 0.0, 0.0)
        cal.logFood(T0, T0 + 3 * HOUR, 20.0, 20.0, 0.0, 0.0, eatenMs = T0 + HOUR)
        assertEquals(60.0, cal.foodStillToComeG(T0)!!, 1e-9)
        cal.onEpisode(episode(T0, 60.0))
        cal.onCycle(T0 + 3 * HOUR + MealEstimateCalibrationImpl.GRACE_MS + 1)
        assertEquals(0, cal.mealsUsed)
    }

    @Test
    fun `a meal after a snack still coming counts the snack as earlier food, and neither is learned from`() {
        cal.logFood(T0, T0 + HOUR, 40.0, 40.0, 0.0, 0.0, eatenMs = T0)
        cal.recordEstimate(T0 + HOUR, T0 + 4 * HOUR, 60.0, 60.0, 0.0, 0.0)
        val earlier = cal.earlierToCome(T0 + HOUR, T0 + HOUR)
        assertEquals(1, earlier.size)
        assertEquals(40.0, earlier.single().carbsG, 1e-9)
        assertEquals(0, cal.earlierToCome(T0 + HOUR, T0 + 3 * HOUR).size)   // the snack's window is over
        // The dinner's episode belongs to the dinner, not to the snack whose window it falls in.
        cal.onEpisode(episode(T0 + HOUR, 70.0))
        assertEquals(0.0, cal.foodStillToComeG(T0 + HOUR)!!, 1e-9)
        cal.onCycle(T0 + 4 * HOUR + MealEstimateCalibrationImpl.GRACE_MS + 1)
        assertEquals(0, cal.mealsUsed)
    }

    @Test
    fun `a meal after an earlier one whose food is all in is still learned from`() {
        cal.recordEstimate(T0, T0 + 3 * HOUR, 40.0, 40.0, 0.0, 0.0)
        cal.onEpisode(episode(T0, 40.0))
        cal.recordEstimate(T0 + 2 * HOUR, T0 + 5 * HOUR, 60.0, 60.0, 0.0, 0.0)
        cal.onEpisode(episode(T0 + 2 * HOUR, 66.0))
        cal.onCycle(T0 + 5 * HOUR + MealEstimateCalibrationImpl.GRACE_MS + 1)
        assertEquals(2, cal.mealsUsed)
    }

    private companion object {

        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val DAY = 24 * HOUR
        const val T0 = 1_790_000_000_000L
    }
}
