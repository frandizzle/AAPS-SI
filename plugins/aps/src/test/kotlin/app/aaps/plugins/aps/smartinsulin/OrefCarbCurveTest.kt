package app.aaps.plugins.aps.smartInsulin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * oref's COB prediction. ISF 50 mg/dL/U and CR 10 g/U throughout, so csf = 5 mg/dL per gram —
 * one 10 g portion is worth 50 mg/dL (~2.8 mmol) of eventual rise.
 */
class OrefCarbCurveTest {

    private val ISF = 50.0
    private val CR  = 10.0
    /** 36 ticks = 3 hours of forecast. */
    private fun flat(ticks: Int = 36) = List(ticks) { 0.0 }
    private fun insulin(perTick: Double, ticks: Int = 36) = List(ticks) { perTick }

    @Test
    fun `no carbs on board falls back to the insulin-only curve`() {
        val r = OrefCarbCurve.predict(100.0, ci = 2.0, cobG = 0.0, isfMgdl = ISF, carbRatio = CR, bgiPerTick = flat())
        // Current impact decays over an hour and then nothing more arrives.
        assertEquals(0.0, r.remainingCarbsG, 1e-9)
        assertTrue(r.bgs.last() > 100.0, "the observed impact still plays out")
        assertTrue(r.bgs.last() < 130.0, "but nothing is added beyond it, got ${r.bgs.last()}")
    }

    @Test
    fun `carbs that have not shown up yet still raise the forecast`() {
        // The case a deviation-only model cannot see: carbs entered, BG hasn't moved yet.
        val r = OrefCarbCurve.predict(100.0, ci = 0.0, cobG = 40.0, isfMgdl = ISF, carbRatio = CR, bgiPerTick = flat())
        assertEquals(40.0, r.remainingCarbsG, 1e-9)
        // 40g × 5 mg/dL = 200 mg/dL of rise, spread over the triangle.
        assertEquals(300.0, r.bgs.last(), 2.0)
    }

    @Test
    fun `the rise is spread as a triangle, not dumped in at once`() {
        val r = OrefCarbCurve.predict(100.0, ci = 0.0, cobG = 40.0, isfMgdl = ISF, carbRatio = CR, bgiPerTick = flat())
        val firstHalfHour = r.bgs[5] - 100.0
        val secondHour    = r.bgs[23] - r.bgs[11]
        assertTrue(firstHalfHour < secondHour, "absorption should ramp up, not start at full rate")
    }

    @Test
    fun `carbs already showing in BG are not counted twice`() {
        // Same 40g, but 4 mg/dL per 5min of it is already visible in the deviation.
        val withImpact = OrefCarbCurve.predict(100.0, ci = 4.0, cobG = 40.0, isfMgdl = ISF, carbRatio = CR, bgiPerTick = flat())
        val noImpact   = OrefCarbCurve.predict(100.0, ci = 0.0, cobG = 40.0, isfMgdl = ISF, carbRatio = CR, bgiPerTick = flat())
        assertTrue(withImpact.remainingCarbsG < noImpact.remainingCarbsG,
                   "grams behind the observed impact must come out of the remaining pile")
        // The total rise is broadly the food, however it is split between the two components.
        assertEquals(noImpact.bgs.max(), withImpact.bgs.max(), 40.0)
    }

    @Test
    fun `insulin pulls the curve back down`() {
        val bare   = OrefCarbCurve.predict(100.0, ci = 0.0, cobG = 40.0, isfMgdl = ISF, carbRatio = CR, bgiPerTick = flat())
        val dosed  = OrefCarbCurve.predict(100.0, ci = 0.0, cobG = 40.0, isfMgdl = ISF, carbRatio = CR,
                                           bgiPerTick = insulin(-5.0))
        assertTrue(dosed.eventualBg < bare.eventualBg)
        assertTrue(dosed.eventualBg < 150.0, "4U-ish of insulin against 40g should land it, got ${dosed.eventualBg}")
    }

    @Test
    fun `eventual BG is where it lands, not the peak`() {
        val r = OrefCarbCurve.predict(100.0, ci = 3.0, cobG = 30.0, isfMgdl = ISF, carbRatio = CR,
                                      bgiPerTick = insulin(-6.0))
        assertTrue(r.eventualBg < r.bgs.max(), "eventual must look past the peak")
    }

    @Test
    fun `a huge carb entry is capped before it runs the forecast`() {
        val r = OrefCarbCurve.predict(100.0, ci = 0.0, cobG = 250.0, isfMgdl = ISF, carbRatio = CR, bgiPerTick = flat())
        assertEquals(OrefCarbCurve.REMAINING_CARBS_CAP_G, r.remainingCarbsG, 1e-9)
    }

    @Test
    fun `the current impact lasts longer when more carbs sit behind it`() {
        val small = OrefCarbCurve.predict(100.0, ci = 3.0, cobG = 5.0, isfMgdl = ISF, carbRatio = CR, bgiPerTick = flat())
        val big   = OrefCarbCurve.predict(100.0, ci = 3.0, cobG = 60.0, isfMgdl = ISF, carbRatio = CR, bgiPerTick = flat())
        assertTrue(big.carbImpactDurationMins > small.carbImpactDurationMins,
                   "${big.carbImpactDurationMins} should outlast ${small.carbImpactDurationMins}")
    }

    @Test
    fun `carb impact duration never outruns the absorption window`() {
        val r = OrefCarbCurve.predict(100.0, ci = 0.5, cobG = 200.0, isfMgdl = ISF, carbRatio = CR, bgiPerTick = flat())
        assertTrue(r.carbImpactDurationMins <= OrefCarbCurve.DEFAULT_REMAINING_CA_TIME_MINS / 2 + 1e-6,
                   "got ${r.carbImpactDurationMins}min")
    }

    @Test
    fun `a stronger carb ratio means each gram counts for less`() {
        val strongCr = OrefCarbCurve.predict(100.0, ci = 0.0, cobG = 30.0, isfMgdl = ISF, carbRatio = 20.0, bgiPerTick = flat())
        val weakCr   = OrefCarbCurve.predict(100.0, ci = 0.0, cobG = 30.0, isfMgdl = ISF, carbRatio = 5.0, bgiPerTick = flat())
        assertTrue(strongCr.bgs.last() < weakCr.bgs.last(), "csf = ISF/CR — a bigger CR is a smaller csf")
    }

    @Test
    fun `a missing profile ratio cannot break the curve`() {
        val r = OrefCarbCurve.predict(100.0, ci = 1.0, cobG = 30.0, isfMgdl = ISF, carbRatio = 0.0, bgiPerTick = flat())
        assertEquals(0.0, r.remainingCarbsG, 1e-9)
        assertTrue(r.bgs.all { it.isFinite() })
    }
}
