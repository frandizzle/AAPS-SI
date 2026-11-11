package app.aaps.plugins.aps.smartInsulin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FoodMatchTest {

    private val now = 10_000_000L
    private val unitAtLead = InsulinActivityCurve.activityFraction(60.0, 75.0, 480.0)

    private fun samples(foodMgdl: Double, count: Int = 6) = (0 until count).map { FoodMatch.Sample(now - it * 300_000L, foodMgdl) }.reversed()

    private fun topUp(
        samples: List<FoodMatch.Sample> = samples(10.0), foodLeftG: Double = 30.0, bgRising: Boolean = true,
        insulinAtLead: Double = 0.02, roomU: Double = 5.0, maxTopUpU: Double = FoodMatch.MAX_TOP_UP_U
    ) = FoodMatch.topUp(samples, now, foodLeftG, csfMgdlPerG = 9.0, bgRising, insulinAtLead, unitAtLead, isfMgdl = 45.0, bolusStep = 0.05, roomU, maxTopUpU)

    @Test fun `food coming in faster than the insulin later gets a small dose`() {
        val r = topUp()
        assertNotNull(r)
        assertTrue(r!!.doseU in 0.05..FoodMatch.MAX_TOP_UP_U)
        assertEquals(10.0, r.foodMgdl, 1e-9)
        assertEquals(4.5, r.insulinMgdl, 1e-9)
    }

    @Test fun `enough insulin at work later gives nothing`() {
        assertNull(topUp(insulinAtLead = 0.05))
    }

    @Test fun `no food seen gives nothing`() {
        assertNull(topUp(samples = samples(-2.0)))
    }

    @Test fun `too few readings give nothing`() {
        assertNull(topUp(samples = samples(10.0, count = 2)))
    }

    @Test fun `old readings do not count`() {
        val old = samples(10.0).map { it.copy(timeMs = it.timeMs - FoodMatch.LOOK_BACK_MS - 300_000L) }
        assertNull(topUp(samples = old))
    }

    @Test fun `one dose never passes the most for one top-up`() {
        assertEquals(FoodMatch.MAX_TOP_UP_U, topUp(samples = samples(200.0), foodLeftG = 500.0, insulinAtLead = 0.0)!!.doseU, 1e-9)
        assertEquals(0.6, topUp(samples = samples(200.0), foodLeftG = 500.0, insulinAtLead = 0.0, maxTopUpU = 0.6)!!.doseU, 1e-9)
    }

    @Test fun `the most for one top-up grows with BG from 1 to 3 mmol above target`() {
        // 9 Nov dinner: twelve top-ups in a row at 0.3 U while BG went from 8 to 11.8 with a 5.5 target.
        val target = 99.0
        assertEquals(0.3, FoodMatch.maxTopUpU(target, target), 1e-9)
        assertEquals(0.3, FoodMatch.maxTopUpU(target + 18.0, target), 1e-9)
        assertEquals(0.45, FoodMatch.maxTopUpU(target + 36.0, target), 1e-9)
        assertEquals(0.6, FoodMatch.maxTopUpU(target + 54.0, target), 1e-9)
        assertEquals(0.6, FoodMatch.maxTopUpU(target + 120.0, target), 1e-9)
    }

    @Test fun `the logged food limits what is expected once BG stops rising`() {
        // 2 g left is 18 mg/dL, spread over 2 hours.
        val flat = topUp(foodLeftG = 2.0, bgRising = false, insulinAtLead = 0.0)
        assertNotNull(flat)
        assertEquals(0.75, flat!!.foodMgdl, 1e-9)
        assertNull(topUp(foodLeftG = 0.0, bgRising = false, insulinAtLead = 0.0))
    }

    @Test fun `food past the logged amount still counts while BG rises`() {
        // The count was too low: all the logged food is in, but BG goes on rising.
        val r = topUp(foodLeftG = 0.0, bgRising = true)
        assertNotNull(r)
        assertEquals(10.0, r!!.foodMgdl, 1e-9)
    }

    @Test fun `flat BG near target allows no top-up`() {
        // 6 Nov: 5.7 with a 5.5 target, flat.
        assertFalse(FoodMatch.bgAllows(bgMgdl = 103.0, targetMgdl = 99.0, deltaMgdl = 0.0))
        assertFalse(FoodMatch.bgAllows(bgMgdl = 103.0, targetMgdl = 99.0, deltaMgdl = 0.5))
    }

    @Test fun `BG 1 mmol above target allows a top-up, also when flat`() {
        assertTrue(FoodMatch.bgAllows(bgMgdl = 117.0, targetMgdl = 99.0, deltaMgdl = 0.0))
        assertFalse(FoodMatch.bgAllows(bgMgdl = 116.0, targetMgdl = 99.0, deltaMgdl = 0.0))
    }

    @Test fun `a clear rise near target allows a top-up, but not under target`() {
        assertTrue(FoodMatch.bgAllows(bgMgdl = 103.0, targetMgdl = 99.0, deltaMgdl = 1.8))
        assertFalse(FoodMatch.bgAllows(bgMgdl = 95.0, targetMgdl = 99.0, deltaMgdl = 5.0))
    }

    @Test fun `once the carbs are done only BG 1 mmol above target allows a top-up`() {
        // 11 Nov: end of a lunch, 6.1 with a 5.5 target, rising 0.2.
        assertFalse(FoodMatch.bgAllows(bgMgdl = 110.0, targetMgdl = 99.0, deltaMgdl = 3.6, carbsDone = true))
        assertTrue(FoodMatch.bgAllows(bgMgdl = 110.0, targetMgdl = 99.0, deltaMgdl = 3.6, carbsDone = false))
        assertTrue(FoodMatch.bgAllows(bgMgdl = 117.0, targetMgdl = 99.0, deltaMgdl = 0.0, carbsDone = true))
    }

    @Test fun `top-ups stop at the limit for the meal`() {
        // 6 U planned: pre-bolus 2 and top-ups stop at 9 U.
        assertEquals(4.0, FoodMatch.roomU(plannedU = 6.0, preBolusU = 3.0, givenU = 2.0), 1e-9)
        assertEquals(0.0, FoodMatch.roomU(plannedU = 6.0, preBolusU = 3.0, givenU = 7.0), 1e-9)
        assertNull(topUp(roomU = 0.0))
        assertEquals(0.1, topUp(samples = samples(200.0), foodLeftG = 500.0, insulinAtLead = 0.0, roomU = 0.1)!!.doseU, 1e-9)
    }
}
