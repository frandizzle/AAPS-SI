package app.aaps.ui.compose.smartMealDialog.food

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The insulin plan made from a food list. It only fills the dialog, so what matters is that it never asks for too much. */
class MealDosePlannerTest {

    private val settings = MealPlanSettings(
        pb1Pct = 50, pb2Pct = 25, pb3Pct = 40,
        maxPreBolusU = 8.0, maxTotalU = 10.0,
        carbRatio = 5.0, factor = 1.0, bolusStep = 0.05
    )

    private fun meal(carbs: Double, protein: Double = 0.0, fat: Double = 0.0) = MealTotals(carbs, 0.0, protein, fat)

    @Test
    fun `carbs only are split over pre-bolus 1 and 2`() {
        // 50 g at 5 g/U = 10 U for the carbs.
        val p = MealDosePlanner.plan(meal(50.0), settings)
        assertEquals(10.0, p.carbsU, 1e-9)
        assertEquals(5.0, p.pb1U, 1e-9)
        assertEquals(2.5, p.pb2U, 1e-9)
        assertEquals(0.0, p.pb3U, 1e-9)
        assertEquals(MealDosePlanner.PB2_DELAY_MIN, p.pb2DelayMin)
        assertFalse(p.duraEnabled)
        assertFalse(p.pfAfter)
        assertEquals(180, p.durationMin)
        assertFalse(p.capped)
    }

    @Test
    fun `fat and protein go to pre-bolus 3 and make it a slow meal`() {
        // Last night's dinner: about 60 g carbs, 80 g protein, 25 g fat.
        val t = meal(60.0, protein = 80.0, fat = 25.0)
        assertEquals(5.45, t.fpu, 1e-9)
        val p = MealDosePlanner.plan(t, settings.copy(maxTotalU = 25.0))
        assertEquals(10.9, p.fatProteinU, 1e-9)
        assertEquals(6.0, p.pb1U, 1e-9)
        assertEquals(3.0, p.pb2U, 1e-9)
        assertEquals(4.35, p.pb3U, 1e-9)    // 40 % of 10.9, down to the bolus step
        assertTrue(p.duraEnabled)
        assertTrue(p.pfAfter)
        assertEquals(300, p.durationMin)
    }

    @Test
    fun `the total cap cuts the later pre-boluses first`() {
        val p = MealDosePlanner.plan(meal(100.0, protein = 50.0, fat = 20.0), settings)
        // Wanted 10 + 5 + 3.04 U; the total may be 10.
        assertEquals(8.0, p.pb1U, 1e-9)     // also held to the max pre-bolus
        assertEquals(2.0, p.pb2U, 1e-9)
        assertEquals(0.0, p.pb3U, 1e-9)
        assertTrue(p.capped)
    }

    @Test
    fun `the learned factor scales both parts`() {
        val p = MealDosePlanner.plan(meal(50.0, protein = 50.0), settings.copy(factor = 1.2))
        assertEquals(12.0, p.carbsU, 1e-9)
        assertEquals(4.8, p.fatProteinU, 1e-9)
    }

    @Test
    fun `without pre-bolus 2 the fat and protein dose takes its place`() {
        // Pre-bolus 3 counts from pre-bolus 2, so on its own it would never fire.
        val p = MealDosePlanner.plan(meal(10.0, protein = 60.0, fat = 20.0), settings.copy(pb2Pct = 0))
        assertEquals(0.0, p.pb3U, 1e-9)
        assertEquals(3.35, p.pb2U, 1e-9)    // 40 % of 8.4 U, down to the bolus step
        assertEquals(MealDosePlanner.SLOW_ONLY_DELAY_MIN, p.pb2DelayMin)
    }

    @Test
    fun `no carb ratio plans nothing`() {
        val p = MealDosePlanner.plan(meal(50.0), settings.copy(carbRatio = 0.0))
        assertEquals(0.0, p.pb1U + p.pb2U + p.pb3U, 1e-9)
    }

    @Test
    fun `doses are rounded down to the bolus step`() {
        val p = MealDosePlanner.plan(meal(33.0), settings.copy(bolusStep = 0.1))
        assertEquals(3.3, p.pb1U, 1e-9)     // 3.3 exactly
        assertEquals(1.6, p.pb2U, 1e-9)     // 1.65 down to 1.6
    }

    @Test
    fun `the length follows the fat and protein units`() {
        assertEquals(180, MealDosePlanner.durationFor(1.9))
        assertEquals(240, MealDosePlanner.durationFor(2.0))
        assertEquals(300, MealDosePlanner.durationFor(3.0))
    }

    @Test
    fun `item totals scale with the quantity`() {
        val rice = FoodItem("Rice", baseGrams = 158.0, portion = "1 cup", carbsPer100 = 28.0, fibrePer100 = 0.4,
                            proteinPer100 = 2.7, fatPer100 = 0.3, source = FoodSource.ESTIMATE)
        val twice = rice.copy(quantity = 2.0)
        assertEquals(44.24, rice.carbsG, 1e-9)
        assertEquals(88.48, twice.carbsG, 1e-9)
        val t = listOf(rice, twice).totals()
        assertEquals(132.72, t.carbsG, 1e-9)
        assertEquals(t.carbsG + t.fpu * 10.0, t.carbEquivG, 1e-9)
    }

    // --- Food added to a running meal ---

    /** Dinner gave 5 U and has 2.5 U waiting in pre-bolus 2, 60 min left, plenty of IOB room. */
    private val dinner = RunningMeal(plannedTotalU = 7.5, pb2WaitingU = 2.5, pb3WaitingU = 0.0, minutesLeft = 60, iobRoomU = 10.0, holdNow = false)

    @Test
    fun `a dessert gets its share now and in the waiting pre-bolus 2, without IOB taken off`() {
        // 25 g at 5 g/U = 5 U.
        val a = MealDosePlanner.planAddition(meal(25.0), settings.copy(maxTotalU = 20.0), dinner)
        assertEquals(2.5, a.nowU, 1e-9)
        assertEquals(1.25, a.pb2AddU, 1e-9)
        assertEquals(0.0, a.pb3AddU, 1e-9)
        assertEquals(MealDosePlanner.ADD_MIN_LEFT_MIN, a.minutesLeft)
        assertFalse(a.capped)
    }

    @Test
    fun `a fired pre-bolus gets nothing added, and a slow dessert keeps the mode on longer`() {
        val fired = dinner.copy(pb2WaitingU = 0.0)
        val a = MealDosePlanner.planAddition(meal(20.0, protein = 20.0, fat = 15.0), settings.copy(maxTotalU = 20.0), fired)
        assertEquals(0.0, a.pb2AddU, 1e-9)
        assertEquals(0.0, a.pb3AddU, 1e-9)
        assertTrue(a.duraEnabled)
        assertTrue(a.pfAfter)
        assertEquals(240, a.minutesLeft)
    }

    @Test
    fun `fat and protein go into pre-bolus 3 while it waits`() {
        val a = MealDosePlanner.planAddition(meal(0.0, protein = 40.0, fat = 20.0), settings.copy(maxTotalU = 20.0), dinner.copy(pb3WaitingU = 1.0))
        // 3.4 FPU = 6.8 U, 40 % of it.
        assertEquals(2.7, a.pb3AddU, 1e-9)
    }

    @Test
    fun `the max total counts what the meal already gave`() {
        // 10 U wanted for carbs; 7.5 U already planned of 10, so 2.5 U are left.
        val a = MealDosePlanner.planAddition(meal(100.0), settings, dinner)
        assertEquals(2.5, a.nowU, 1e-9)
        assertEquals(0.0, a.pb2AddU, 1e-9)
        assertTrue(a.capped)
    }

    @Test
    fun `a waiting pre-bolus does not grow past the max pre-bolus`() {
        val a = MealDosePlanner.planAddition(meal(100.0), settings.copy(maxTotalU = 50.0), dinner.copy(pb2WaitingU = 7.0))
        assertEquals(1.0, a.pb2AddU, 1e-9)
    }

    @Test
    fun `low or falling BG holds the dose now, and IOB room cuts it`() {
        val held = MealDosePlanner.planAddition(meal(25.0), settings.copy(maxTotalU = 20.0), dinner.copy(holdNow = true))
        assertEquals(0.0, held.nowU, 1e-9)
        assertTrue(held.heldForBg)
        assertEquals(1.25, held.pb2AddU, 1e-9)     // pre-bolus 2 has its own BG check before it fires

        val cut = MealDosePlanner.planAddition(meal(25.0), settings.copy(maxTotalU = 20.0), dinner.copy(iobRoomU = 1.0))
        assertEquals(1.0, cut.nowU, 1e-9)
        assertTrue(cut.cutForIob)

        val full = MealDosePlanner.planAddition(meal(25.0), settings.copy(maxTotalU = 20.0), dinner.copy(iobRoomU = -0.5))
        assertEquals(0.0, full.nowU, 1e-9)
    }

    @Test
    fun `the mode is never made shorter`() {
        val a = MealDosePlanner.planAddition(meal(10.0), settings, dinner.copy(minutesLeft = 200))
        assertEquals(200, a.minutesLeft)
    }
}
