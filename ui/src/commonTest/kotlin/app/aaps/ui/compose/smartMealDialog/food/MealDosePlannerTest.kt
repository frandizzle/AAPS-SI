package app.aaps.ui.compose.smartMealDialog.food

import app.aaps.core.interfaces.smartInsulin.CarbPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The insulin plan made from a food list. It only fills the dialog, so what matters is that it never asks for too much. */
class MealDosePlannerTest {

    private val settings = MealPlanSettings(
        carbPct = 80, fatProteinPct = 70, pb3SharePct = 40,
        maxPreBolusU = 8.0, maxTotalU = 10.0,
        carbRatio = 5.0, factor = 1.0, bolusStep = 0.05
    )

    private fun meal(carbs: Double, protein: Double = 0.0, fat: Double = 0.0) = MealTotals(carbs, 0.0, protein, fat)

    @Test
    fun `carbs only are all in pre-bolus 1`() {
        // 50 g at 5 g/U = 10 U for the carbs, 80 % of it now.
        val p = MealDosePlanner.plan(meal(50.0), settings)
        assertEquals(10.0, p.carbsU, 1e-9)
        assertEquals(8.0, p.pb1U, 1e-9)
        assertEquals(0.0, p.pb2U, 1e-9)
        assertEquals(0.0, p.pb3U, 1e-9)
        assertEquals(180, p.durationMin)
        assertFalse(p.capped)
    }

    @Test
    fun `with food match pre-bolus 2 keeps its size and there is no pre-bolus 3`() {
        // 4 FPU = 8 U, 70 % of it = 5.6 U, split over pre-bolus 2 and 3. With food match pre-bolus 3 is left to the loop.
        val heavy = meal(20.0, protein = 60.0, fat = 17.8)
        val before = MealDosePlanner.plan(heavy, settings)
        assertTrue(before.pb3U > 0.0)
        val p = MealDosePlanner.plan(heavy, settings.copy(foodMatch = true))
        assertEquals(0.0, p.pb3U, 1e-9)
        assertEquals(before.pb2U, p.pb2U, 1e-9)
        assertEquals(before.pb1U, p.pb1U, 1e-9)
    }

    @Test
    fun `with food match pre-bolus 2 comes 30 min after pre-bolus 1 and 10 min after the food at the soonest`() {
        assertEquals(30, MealDosePlanner.foodMatchPb2DelayMin(0))
        assertEquals(15, MealDosePlanner.foodMatchPb2DelayMin(15))
        assertEquals(10, MealDosePlanner.foodMatchPb2DelayMin(20))
        assertEquals(10, MealDosePlanner.foodMatchPb2DelayMin(25))
    }

    @Test
    fun `a little fat and protein gets no pre-bolus 2`() {
        // 0.85 FPU: the loop covers it.
        val p = MealDosePlanner.plan(meal(30.0, protein = 10.0, fat = 5.0), settings)
        assertEquals(0.0, p.pb2U, 1e-9)
        assertEquals(0.0, p.pb3U, 1e-9)
    }

    @Test
    fun `some fat and protein goes in pre-bolus 2 for the wave`() {
        // 2.1 FPU = 4.2 U, 70 % of it at 90 min.
        val p = MealDosePlanner.plan(meal(20.0, protein = 30.0, fat = 10.0), settings)
        assertEquals(3.2, p.pb1U, 1e-9)
        assertEquals(2.9, p.pb2U, 1e-9)
        assertEquals(0.0, p.pb3U, 1e-9)
        assertEquals(MealDosePlanner.PB2_DELAY_MIN, p.pb2DelayMin)
        // The fat and protein start at 45 min and take 3 h 6 min: 4 h 51, up to 5 h.
        assertEquals(300, p.durationMin)
    }

    @Test
    fun `a heavy meal splits the fat and protein over pre-bolus 2 and 3`() {
        // Last night's dinner: about 60 g carbs, 80 g protein, 25 g fat.
        val t = meal(60.0, protein = 80.0, fat = 25.0)
        assertEquals(5.45, t.fpu, 1e-9)
        val p = MealDosePlanner.plan(t, settings.copy(maxPreBolusU = 10.0, maxTotalU = 25.0))
        assertEquals(10.9, p.fatProteinU, 1e-9)
        assertEquals(9.6, p.pb1U, 1e-9)     // 80 % of 12 U
        assertEquals(4.55, p.pb2U, 1e-9)    // 60 % of 7.63 U, down to the bolus step
        assertEquals(3.05, p.pb3U, 1e-9)    // 40 % of 7.63 U
        assertEquals(MealDosePlanner.PB3_DELAY_MIN, p.pb3DelayMin)
        assertEquals(MealDosePlanner.MAX_MODE_MIN, p.durationMin)
    }

    @Test
    fun `a low carb meal with a lot of protein and fat is mostly pre-bolus 2 and 3`() {
        // 10 g carbs, 60 g protein, 40 g fat: 6 FPU = 12 U, 70 % of it = 8.4 U.
        val p = MealDosePlanner.plan(meal(10.0, protein = 60.0, fat = 40.0), settings.copy(maxTotalU = 25.0))
        assertEquals(1.6, p.pb1U, 1e-9)
        assertEquals(5.0, p.pb2U, 1e-9)
        assertEquals(3.35, p.pb3U, 1e-9)
    }

    @Test
    fun `the total cap cuts the later pre-boluses first`() {
        val p = MealDosePlanner.plan(meal(100.0, protein = 50.0, fat = 20.0), settings)
        // Wanted 16 + 3.19 + 2.13 U; the total may be 10.
        assertEquals(8.0, p.pb1U, 1e-9)     // also held to the max pre-bolus
        assertEquals(2.0, p.pb1RestU, 1e-9) // the carbs come before the fat and protein
        assertEquals(0.0, p.pb2U, 1e-9)
        assertEquals(0.0, p.pb3U, 1e-9)
        assertTrue(p.capped)
    }

    @Test
    fun `rounding each pre-bolus down to the pump step is not a cut`() {
        // 23 g carbs: 3.68 U, given as 3.65. 3.34 FPU at 70 %, half each: 2.338 U, given as 2.30 twice.
        // Together 0.1 U is lost to rounding, more than one step, but no limit cut anything.
        val p = MealDosePlanner.plan(meal(23.0, protein = 52.0, fat = 14.0), settings.copy(pb3SharePct = 50, maxPreBolusU = 6.0, maxTotalU = 15.0))
        assertEquals(3.65, p.pb1U, 1e-9)
        assertEquals(2.30, p.pb2U, 1e-9)
        assertEquals(2.30, p.pb3U, 1e-9)
        assertFalse(p.capped)
    }

    @Test
    fun `pre-bolus 1 above the max pre-bolus is given later as its rest`() {
        // 56.25 g at 5 g/U, 80 % = 9 U; the max pre-bolus is 6 U.
        val p = MealDosePlanner.plan(meal(56.25), settings.copy(maxPreBolusU = 6.0, maxTotalU = 15.0))
        assertEquals(6.0, p.pb1U, 1e-9)
        assertEquals(3.0, p.pb1RestU, 1e-9)
        assertFalse(p.capped)
        // Braver: a max of 7 U leaves 2 U, and a max of 9 U none.
        assertEquals(2.0, MealDosePlanner.plan(meal(56.25), settings.copy(maxPreBolusU = 7.0, maxTotalU = 15.0)).pb1RestU, 1e-9)
        assertEquals(0.0, MealDosePlanner.plan(meal(56.25), settings.copy(maxPreBolusU = 9.0, maxTotalU = 15.0)).pb1RestU, 1e-9)
    }

    /** BG 6.6 mmol, flat, at a target of 5.5 and a profile ISF of 1.8 mmol/U, nothing on board. */
    private val start = InsulinBalance(
        bgMgdl = 119.0, trendMgdl = 0.0, targetMgdl = 99.0, isfMgdl = 32.4, iobU = 0.0, waitingU = 0.0, foodStillToComeG = 0.0
    )

    @Test
    fun `insulin on board for earlier food is not taken off pre-bolus 1`() {
        // A 16 g snack with 1.3 U left from dinner: 0.62 - 1.3 + 3.2 = 2.52 U needed, so the 2.56 U plan is barely cut.
        val alone = MealDosePlanner.plan(meal(16.0), settings, start.copy(iobU = 1.3))
        assertEquals(2.5, alone.pb1U, 1e-9)
        assertTrue(alone.cutForBalance)
        // With 10 g of dinner still to come, that insulin is for it, and the plan stays.
        val dinner = MealDosePlanner.plan(meal(16.0), settings, start.copy(iobU = 1.3, foodStillToComeG = 10.0))
        assertEquals(2.55, dinner.pb1U, 1e-9)
        assertFalse(dinner.cutForBalance)
    }

    @Test
    fun `a meal soon after a correction gets less now`() {
        // BG 7.8 falling 0.5 mmol in 15 min, 3 U on board: 0.99 - 3 + 8 = 5.99 U, less than the 6.4 U plan.
        val p = MealDosePlanner.plan(meal(40.0), settings, start.copy(bgMgdl = 140.0, trendMgdl = -9.0, iobU = 3.0))
        assertEquals(5.95, p.pb1U, 1e-9)
        assertTrue(p.cutForBalance)
        assertFalse(p.capped)
        // Much more on board than the food needs: nothing now.
        val none = MealDosePlanner.plan(meal(40.0), settings, start.copy(iobU = 10.0))
        assertEquals(0.0, none.pb1U, 1e-9)
    }

    @Test
    fun `a high BG does not add to pre-bolus 1`() {
        val p = MealDosePlanner.plan(meal(40.0), settings, start.copy(bgMgdl = 250.0))
        assertEquals(6.4, p.pb1U, 1e-9)
        assertFalse(p.cutForBalance)
    }

    @Test
    fun `the rest of pre-bolus 1 is cut first`() {
        // 9 U wanted as 6 U now and 3 U later; 4 U on board and BG at target leave 11.25 - 4 = 7.25 U.
        val p = MealDosePlanner.plan(meal(56.25), settings.copy(maxPreBolusU = 6.0, maxTotalU = 15.0), start.copy(bgMgdl = 99.0, iobU = 4.0))
        assertEquals(6.0, p.pb1U, 1e-9)
        assertEquals(1.25, p.pb1RestU, 1e-9)
        assertTrue(p.cutForBalance)
    }

    @Test
    fun `a rest is never more than one max pre-bolus, and a small one is left to the loop`() {
        // 20 U for pre-bolus 1 with a max of 6 U: 6 U now, 6 U later, the rest is cut.
        val big = MealDosePlanner.plan(meal(125.0), settings.copy(maxPreBolusU = 6.0, maxTotalU = 25.0))
        assertEquals(6.0, big.pb1RestU, 1e-9)
        assertTrue(big.capped)
        // 6.4 U wanted: 0.4 U is too little to give on its own.
        val small = MealDosePlanner.plan(meal(40.0), settings.copy(maxPreBolusU = 6.0, maxTotalU = 15.0))
        assertEquals(6.0, small.pb1U, 1e-9)
        assertEquals(0.0, small.pb1RestU, 1e-9)
    }

    @Test
    fun `without pre-bolus 2 there is no pre-bolus 3`() {
        // Pre-bolus 3 counts from pre-bolus 2, so on its own it would never fire.
        val p = MealDosePlanner.plan(meal(50.0, protein = 60.0, fat = 40.0), settings.copy(maxTotalU = 8.0))
        assertEquals(8.0, p.pb1U, 1e-9)
        assertEquals(0.0, p.pb2U, 1e-9)
        assertEquals(0.0, p.pb3U, 1e-9)
    }

    @Test
    fun `the learned factor scales both parts`() {
        val p = MealDosePlanner.plan(meal(50.0, protein = 50.0), settings.copy(factor = 1.2))
        assertEquals(12.0, p.carbsU, 1e-9)
        assertEquals(4.8, p.fatProteinU, 1e-9)
    }

    @Test
    fun `pre-bolus 2 and 3 each use their own learned factor`() {
        // 10 g carbs, 60 g protein, 40 g fat: 6 FPU = 60 g, 36 g for the wave and 24 g for the tail.
        assertEquals(36.0 to 24.0, MealDosePlanner.fatProteinGrams(6.0, 40).let { (w, t) -> Math.round(w * 1e6) / 1e6 to Math.round(t * 1e6) / 1e6 })
        val p = MealDosePlanner.plan(meal(10.0, protein = 60.0, fat = 40.0), settings.copy(maxTotalU = 25.0, waveFactor = 1.0, tailFactor = 0.5))
        assertEquals(5.0, p.pb2U, 1e-9)     // 36 g / 5 × 70 % = 5.04
        assertEquals(1.65, p.pb3U, 1e-9)    // 24 g × 0.5 / 5 × 70 % = 1.68
    }

    @Test
    fun `pre-bolus 2 and 3 are timed so the insulin is strongest when the food comes in`() {
        // Half of the fat and protein in by 145 min, insulin strongest 55 min after a bolus: pre-bolus 2 at 90 min.
        assertEquals(90 to MealDosePlanner.PB3_DELAY_MIN, MealDosePlanner.learnedDelays(130, 145, 160, 55, heavy = false))
        // Heavy: pre-bolus 2 at the first quarter, pre-bolus 3 at the third.
        assertEquals(75 to 30, MealDosePlanner.learnedDelays(130, 145, 160, 55, heavy = true))
        // Never on top of pre-bolus 1, never past the dialog's limit.
        assertEquals(MealDosePlanner.MIN_LEARNED_PB2_MIN, MealDosePlanner.learnedDelays(80, 80, 90, 55, heavy = false)!!.first)
        assertEquals(MealDosePlanner.MAX_DELAY_MIN, MealDosePlanner.learnedDelays(300, 300, 400, 55, heavy = false)!!.first)
        assertNull(MealDosePlanner.learnedDelays(null, null, null, 55, heavy = false))
    }

    @Test
    fun `a wait is suggested only when BG rises before the insulin works`() {
        assertEquals(15, MealDosePlanner.suggestedWait(5))
        assertEquals(10, MealDosePlanner.suggestedWait(12))
        assertNull(MealDosePlanner.suggestedWait(20))
        assertNull(MealDosePlanner.suggestedWait(null))
    }

    @Test
    fun `a fast meal suggests a wait only with BG at target or above and not falling`() {
        assertEquals(MealDosePlanner.FAST_CARBS_WAIT_MIN, MealDosePlanner.fastCarbsWait(75, 110.0, 100.0, 0.0))
        assertEquals(MealDosePlanner.FAST_CARBS_WAIT_MIN, MealDosePlanner.fastCarbsWait(MealDosePlanner.FAST_MEAL_MAX_MIN, 100.0, 100.0, 5.0))
        assertNull(MealDosePlanner.fastCarbsWait(101, 110.0, 100.0, 0.0))   // not fast enough
        assertNull(MealDosePlanner.fastCarbsWait(75, 95.0, 100.0, 0.0))     // under target
        assertNull(MealDosePlanner.fastCarbsWait(75, 110.0, 100.0, -3.0))   // falling
        assertNull(MealDosePlanner.fastCarbsWait(75, null, 100.0, 0.0))     // no BG
    }

    @Test
    fun `no carb ratio plans nothing`() {
        val p = MealDosePlanner.plan(meal(50.0), settings.copy(carbRatio = 0.0))
        assertEquals(0.0, p.pb1U + p.pb2U + p.pb3U, 1e-9)
    }

    @Test
    fun `doses are rounded down to the bolus step`() {
        val p = MealDosePlanner.plan(meal(33.0), settings.copy(bolusStep = 0.1))
        assertEquals(5.2, p.pb1U, 1e-9)     // 5.28 down to 5.2
    }

    @Test
    fun `a meal given no pre-bolus 2 leaves its fat and protein to the loop`() {
        // A big latte: 1.17 fat-protein units would give a pre-bolus 2 and a mode for the fat and protein.
        val latte = meal(10.0, protein = 9.0, fat = 9.0)
        val usual = MealDosePlanner.plan(latte, settings, carbParts = listOf(CarbPart(10.0, 75)))
        assertTrue(usual.pb2U > 0.0)
        assertEquals(240, usual.durationMin)
        val off = MealDosePlanner.plan(latte, settings.copy(fatProteinPreBoluses = false), carbParts = listOf(CarbPart(10.0, 75)))
        assertEquals(0.0, off.pb2U, 1e-9)
        assertTrue(off.pb2LeftOut)
        assertFalse(off.capped)
        assertEquals(120, off.durationMin)
        // The plan with it is kept, to fill in if the user switches pre-bolus 2 on this time.
        assertEquals(usual.pb2U, off.withPb2!!.pb2U, 1e-9)
        assertEquals(usual.durationMin, off.withPb2!!.durationMin)
        assertEquals(usual.pb1U, off.pb1U, 1e-9)
        // Too little fat and protein for a pre-bolus 2 anyway: nothing was left out.
        assertFalse(MealDosePlanner.plan(meal(10.0), settings.copy(fatProteinPreBoluses = false)).pb2LeftOut)
    }

    @Test
    fun `the mode lasts as long as its food takes to come in`() {
        // Coffee with 200 mL of milk: 10 g of fast milk sugar, under 1 fat-protein unit: 75 min x 1.5, up to 2 h.
        assertEquals(120, MealDosePlanner.durationFor(meal(10.0, protein = 6.8, fat = 6.8), listOf(CarbPart(10.0, 75))))
        // Lunch: 50 g normal and 2 units, pre-bolus 2 at 1 h: the fat and protein, 45 min + 4 h.
        assertEquals(285, MealDosePlanner.durationFor(meal(50.0, protein = 25.0, fat = 11.1), listOf(CarbPart(50.0, 120)), lastPreBolusMin = 60))
        // Slow pizza with 3 units: 45 min + 5 h.
        assertEquals(345, MealDosePlanner.durationFor(meal(60.0, protein = 30.0, fat = 20.0), listOf(CarbPart(60.0, 180))))
        // A huge meal stops at 7 h.
        assertEquals(MealDosePlanner.MAX_MODE_MIN, MealDosePlanner.durationFor(meal(100.0, protein = 80.0, fat = 40.0), emptyList()))
        // Slow carbs alone: 3 h x 1.5.
        assertEquals(270, MealDosePlanner.durationFor(meal(40.0), listOf(CarbPart(40.0, 180))))
        // A late pre-bolus keeps it on an hour past it; no food at all is the shortest mode.
        assertEquals(270, MealDosePlanner.durationFor(meal(10.0), listOf(CarbPart(10.0, 75)), lastPreBolusMin = 210))
        assertEquals(MealDosePlanner.MIN_MODE_MIN, MealDosePlanner.durationFor(meal(0.0), emptyList()))
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
    fun `a dessert's carbs are given now, without IOB taken off`() {
        // 25 g at 5 g/U = 5 U, 80 % of it.
        val a = MealDosePlanner.planAddition(meal(25.0), settings.copy(maxTotalU = 20.0), dinner)
        assertEquals(4.0, a.nowU, 1e-9)
        assertEquals(0.0, a.pb2AddU, 1e-9)
        assertEquals(0.0, a.pb3AddU, 1e-9)
        // At least until the dessert is in: 2 h x 1.5.
        assertEquals(180, a.minutesLeft)
        assertFalse(a.capped)
    }

    @Test
    fun `a fired pre-bolus gets nothing added, and a slow dessert keeps the mode on longer`() {
        val fired = dinner.copy(pb2WaitingU = 0.0)
        val a = MealDosePlanner.planAddition(meal(20.0, protein = 20.0, fat = 15.0), settings.copy(maxTotalU = 20.0), fired)
        assertEquals(0.0, a.pb2AddU, 1e-9)
        assertEquals(0.0, a.pb3AddU, 1e-9)
        assertEquals(300, a.minutesLeft)
    }

    @Test
    fun `fat and protein go into the pre-boluses that still wait`() {
        // 3.4 FPU = 6.8 U, 70 % of it = 4.76 U.
        val heavy = meal(0.0, protein = 40.0, fat = 20.0)
        val both = MealDosePlanner.planAddition(heavy, settings.copy(maxTotalU = 20.0), dinner.copy(pb3WaitingU = 1.0))
        assertEquals(2.85, both.pb2AddU, 1e-9)
        assertEquals(1.9, both.pb3AddU, 1e-9)
        // Pre-bolus 2 has fired: pre-bolus 3 takes all of it.
        val onlyPb3 = MealDosePlanner.planAddition(heavy, settings.copy(maxTotalU = 20.0), dinner.copy(pb2WaitingU = 0.0, pb3WaitingU = 1.0))
        assertEquals(0.0, onlyPb3.pb2AddU, 1e-9)
        assertEquals(4.75, onlyPb3.pb3AddU, 1e-9)
    }

    @Test
    fun `the max total counts what the meal already gave`() {
        // 16 U wanted now; 7.5 U already planned of 10, so 2.5 U are left.
        val a = MealDosePlanner.planAddition(meal(100.0), settings, dinner)
        assertEquals(2.5, a.nowU, 1e-9)
        assertTrue(a.capped)
    }

    /** BG 8.3 mmol, slowly falling, profile ISF 1.8 mmol/U, target 5.5, 8.33 U on board. */
    private val evening = InsulinBalance(
        bgMgdl = 150.0, trendMgdl = -4.5, targetMgdl = 99.0, isfMgdl = 32.4, iobU = 8.33, waitingU = 0.0, foodStillToComeG = 0.0
    )

    @Test
    fun `a dessert on top of a meal the insulin on board already covers gets nothing now`() {
        // Dinner has all come in; 8.33 U on board is more than 25 g and BG above target need (1.44 + 5 = 6.44 U).
        val a = MealDosePlanner.planAddition(meal(25.0), settings.copy(maxTotalU = 20.0), dinner.copy(balance = evening))
        assertEquals(0.0, a.nowU, 1e-9)
        assertTrue(a.cutForBalance)
        assertFalse(a.capped)
    }

    @Test
    fun `food still to come is weighed like carbs on board`() {
        // 30 g of Dinner still to come: 1.44 - 8.33 + (30 + 25) / 5 = 4.11 U needed, more than the 4 U plan, which stays.
        val b = evening.copy(foodStillToComeG = 30.0)
        assertEquals(4.11, MealDosePlanner.neededNow(b, 25.0, 5.0), 0.01)
        val a = MealDosePlanner.planAddition(meal(25.0), settings.copy(maxTotalU = 20.0), dinner.copy(balance = b))
        assertEquals(4.0, a.nowU, 1e-9)
        assertFalse(a.cutForBalance)
        // 20 g to come: 2.11 U, so the dose now is cut to that, down to the bolus step.
        val c = MealDosePlanner.planAddition(meal(25.0), settings.copy(maxTotalU = 20.0), dinner.copy(balance = evening.copy(foodStillToComeG = 20.0)))
        assertEquals(2.1, c.nowU, 1e-9)
        assertTrue(c.cutForBalance)
    }

    @Test
    fun `pre-boluses still to come count as insulin`() {
        val b = evening.copy(foodStillToComeG = 30.0, waitingU = 2.5)
        assertEquals(1.61, MealDosePlanner.neededNow(b, 25.0, 5.0), 0.01)
    }

    @Test
    fun `a waiting pre-bolus does not grow past the max pre-bolus`() {
        val a = MealDosePlanner.planAddition(meal(0.0, protein = 100.0, fat = 50.0), settings.copy(maxTotalU = 50.0), dinner.copy(pb2WaitingU = 7.0))
        assertEquals(1.0, a.pb2AddU, 1e-9)
    }

    @Test
    fun `low or falling BG holds the dose now, and IOB room cuts it`() {
        val dessert = meal(25.0, protein = 30.0, fat = 10.0)
        val held = MealDosePlanner.planAddition(dessert, settings.copy(maxTotalU = 20.0), dinner.copy(holdNow = true))
        assertEquals(0.0, held.nowU, 1e-9)
        assertTrue(held.heldForBg)
        assertEquals(2.9, held.pb2AddU, 1e-9)     // pre-bolus 2 has its own BG check before it fires

        val cut = MealDosePlanner.planAddition(dessert, settings.copy(maxTotalU = 20.0), dinner.copy(iobRoomU = 1.0))
        assertEquals(1.0, cut.nowU, 1e-9)
        assertTrue(cut.cutForIob)

        val full = MealDosePlanner.planAddition(dessert, settings.copy(maxTotalU = 20.0), dinner.copy(iobRoomU = -0.5))
        assertEquals(0.0, full.nowU, 1e-9)
    }

    @Test
    fun `the mode is never made shorter`() {
        val a = MealDosePlanner.planAddition(meal(10.0), settings, dinner.copy(minutesLeft = 200))
        assertEquals(200, a.minutesLeft)
    }
}
