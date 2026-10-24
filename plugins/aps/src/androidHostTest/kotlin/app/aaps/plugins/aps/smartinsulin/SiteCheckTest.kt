package app.aaps.plugins.aps.smartInsulin

import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * [SiteCheck]: meals where insulin acts much more slowly than usual on one pod ask the user to check the
 * site. Meals are fed in 5-minute steps: a flat start, a rise while insulin goes in, then the 2 h after
 * the peak.
 *
 * Unless a test is about learning, it starts with [BASELINE]: still rising 25 ± 15 min and a fall of
 * 0.54 ± 0.14 mmol per unit, which gives limits of 89.5 min (slow), 119.5 min (asks at once) and 0.204
 * mmol per unit (did not come down).
 */
class SiteCheckTest {

    private lateinit var sp: FakePreferences
    private lateinit var check: SiteCheck
    private var now = POD + 13 * HOUR
    private var bg = 110.0
    private var iob = 0.5

    @BeforeEach
    fun setUp() {
        sp = FakePreferences()
        check = SiteCheck(sp)
        assertTrue(check.importBaseline(BASELINE))
    }

    private fun cycle(
        newBg: Double, newIob: Double, meal: Boolean = true, unreliable: Boolean = false, site: Long? = POD
    ): SiteCheck.Episode? {
        val delta = newBg - bg
        bg = newBg
        iob = newIob
        now += STEP
        return check.onCycle(now, bg, delta, iob, meal, "Lunch (UAM)", site, unreliable)
    }

    /** Quiet time before a meal: no meal mode, flat BG. */
    private fun quiet(steps: Int = 6, site: Long? = POD) = repeat(steps) { cycle(110.0, 0.5, meal = false, site = site) }

    /**
     * One meal. [riseSteps] steps of [risePerStep] mg/dL while IOB climbs [iobPerStep] for
     * [iobSteps] steps, then [fallPerStep] mg/dL per step until judged. Returns the judged meal.
     */
    private fun meal(
        riseSteps: Int, risePerStep: Double, iobSteps: Int, iobPerStep: Double, fallPerStep: Double,
        unreliableAtStep: Int = -1, gapAtStep: Int = -1, site: Long? = POD
    ): SiteCheck.Episode? {
        quiet(site = site)
        var step = 0
        var b = 110.0
        var i = 0.5
        repeat(riseSteps) {
            b += risePerStep
            if (step < iobSteps) i += iobPerStep
            if (step == gapAtStep) now += 30 * MIN
            cycle(b, i, unreliable = step == unreliableAtStep, site = site)?.let { return it }
            step++
        }
        repeat(60) {
            b -= fallPerStep
            if (step < iobSteps) i += iobPerStep
            cycle(b, i, site = site)?.let { return it }
            step++
        }
        return null
    }

    // Normal: rises 4 mmol in 45 min, 30 min of it loaded, falls 4 mmol in 2 h with 5.4 U extra.
    private fun normalMeal() = meal(riseSteps = 9, risePerStep = 8.0, iobSteps = 9, iobPerStep = 0.6, fallPerStep = 3.0)
    // Slow: rises for 2 h, 105 min of it with over 2 U extra on board, then lands well.
    private fun slowMeal() = meal(riseSteps = 24, risePerStep = 3.0, iobSteps = 10, iobPerStep = 0.5, fallPerStep = 3.0)
    // Did not land: normal rise, then only 0.7 mmol down in 2 h with 5.4 U extra (0.12 per unit).
    private fun noLandingMeal() = meal(riseSteps = 9, risePerStep = 8.0, iobSteps = 9, iobPerStep = 0.6, fallPerStep = 0.5)
    // Both: rises for 2 h while loaded, then hardly comes down.
    private fun slowNoLandingMeal() = meal(riseSteps = 24, risePerStep = 3.0, iobSteps = 10, iobPerStep = 0.5, fallPerStep = 0.5)

    // --- Judging one meal ---

    @Test
    fun `a normal meal is judged and is not suspect`() {
        val e = normalMeal()
        assertNotNull(e)
        assertFalse(e!!.suspect)
        assertFalse(e.notify)
        assertEquals(30, e.risingLoadedMin)
        assertEquals(5.4, e.extraIobAtPeakU, 1e-6)
    }

    @Test
    fun `a meal is judged 2 h after its peak`() {
        val e = normalMeal()!!
        val peakMs = e.startMs + e.minutesToPeak * MIN
        assertEquals(peakMs + 2 * HOUR, now)
    }

    @Test
    fun `BG still rising for 90 min with insulin on board is slow`() {
        val e = slowMeal()!!
        assertTrue(e.slow)
        assertFalse(e.noLanding)
        assertEquals(105, e.risingLoadedMin)
    }

    @Test
    fun `still rising for an hour is not slow yet`() {
        // 65 min loaded: seen on normal pods too.
        val e = meal(riseSteps = 16, risePerStep = 3.0, iobSteps = 10, iobPerStep = 0.5, fallPerStep = 3.0)!!
        assertEquals(65, e.risingLoadedMin)
        assertFalse(e.suspect)
    }

    @Test
    fun `a fall of 0_25 mmol per unit still counts as landing`() {
        // 5.4 U extra, 1.35 mmol down in 2 h.
        val e = meal(riseSteps = 9, risePerStep = 8.0, iobSteps = 9, iobPerStep = 0.6, fallPerStep = 1.0125)!!
        assertEquals(0.25, e.fallPerUnitMmol!!, 0.01)
        assertFalse(e.noLanding)
    }

    @Test
    fun `BG that does not come down with insulin on board did not land`() {
        val e = noLandingMeal()!!
        assertFalse(e.slow)
        assertTrue(e.noLanding)
        assertTrue(e.fallPerUnitMmol!! <= 0.2)
    }

    @Test
    fun `the landing is not judged with little insulin on board`() {
        // Under 3 U extra at the peak: a small fall says nothing.
        val e = meal(riseSteps = 9, risePerStep = 8.0, iobSteps = 4, iobPerStep = 0.6, fallPerStep = 0.5)!!
        assertFalse(e.noLanding)
        assertNull(e.fallPerUnitMmol)
    }

    @Test
    fun `the meal starts where the rise began`() {
        // BG was 110 before the meal mode came on, already rising: the start is the low point.
        quiet()
        cycle(120.0, 0.5, meal = false)
        cycle(130.0, 0.5)
        var b = 130.0
        var i = 0.5
        var e: SiteCheck.Episode? = null
        repeat(8) { b += 8.0; i += 0.6; e = cycle(b, i) ?: e }
        repeat(30) { b -= 3.0; e = cycle(b, i) ?: e }
        assertEquals(110.0, e!!.startBgMgdl, 1e-6)
    }

    @Test
    fun `no rise of 2 mmol is no meal`() {
        quiet()
        var e: SiteCheck.Episode? = null
        repeat(60) { e = cycle(110.0 + (it % 2) * 10, 0.5 + it * 0.1) ?: e }
        assertNull(e)
    }

    // --- When it is not judged ---

    @Test
    fun `no meal mode, nothing judged`() {
        quiet()
        var b = 110.0
        var e: SiteCheck.Episode? = null
        repeat(24) { b += 3.0; e = cycle(b, 0.5 + it * 0.5, meal = false) ?: e }
        repeat(30) { b -= 0.5; e = cycle(b, 5.5, meal = false) ?: e }
        assertNull(e)
    }

    @Test
    fun `nothing is judged in the first 12 h of a pod`() {
        now = POD + 8 * HOUR
        assertNull(slowNoLandingMeal())
    }

    @Test
    fun `nothing is judged without a pod change record`() {
        assertNull(meal(riseSteps = 24, risePerStep = 3.0, iobSteps = 10, iobPerStep = 0.5, fallPerStep = 0.5, site = null))
    }

    @Test
    fun `exercise or CGM warm-up during the meal drops it`() {
        val e = meal(riseSteps = 24, risePerStep = 3.0, iobSteps = 10, iobPerStep = 0.5, fallPerStep = 0.5, unreliableAtStep = 12)
        // A new meal may start from the rest of the rise, but the one that began at 110 is never judged.
        assertTrue(e == null || e.startBgMgdl > 110.0)
    }

    @Test
    fun `a gap in the data drops the meal`() {
        val e = meal(riseSteps = 24, risePerStep = 3.0, iobSteps = 10, iobPerStep = 0.5, fallPerStep = 0.5, gapAtStep = 12)
        // A new meal may start after the gap, but the one that began at 110 is never judged.
        assertTrue(e == null || e.startBgMgdl > 110.0)
    }

    // --- When it asks ---

    @Test
    fun `one slow meal does not ask`() {
        assertFalse(slowMeal()!!.notify)
    }

    @Test
    fun `one meal that did not land does not ask`() {
        assertFalse(noLandingMeal()!!.notify)
    }

    @Test
    fun `one meal that is both slow and did not land asks at once`() {
        val e = slowNoLandingMeal()!!
        assertTrue(e.slow && e.noLanding)
        assertTrue(e.notify)
    }

    @Test
    fun `one meal still rising for 2 h with insulin on board asks at once`() {
        // 135 min loaded, lands well: slow enough on its own.
        val e = meal(riseSteps = 30, risePerStep = 2.5, iobSteps = 10, iobPerStep = 0.5, fallPerStep = 3.0)!!
        assertEquals(135, e.risingLoadedMin)
        assertFalse(e.noLanding)
        assertTrue(e.notify)
    }

    @Test
    fun `two suspect meals on one pod ask`() {
        assertFalse(slowMeal()!!.notify)
        val second = noLandingMeal()!!
        assertEquals(2, second.suspectsOnPod)
        assertTrue(second.notify)
    }

    @Test
    fun `normal meals in between do not count`() {
        slowMeal()
        normalMeal()
        normalMeal()
        assertEquals(1, normalMeal()!!.suspectsOnPod)
    }

    @Test
    fun `it asks a second time only after two more suspect meals, then never again on that pod`() {
        slowMeal()
        assertTrue(slowMeal()!!.notify)          // 2: first time
        assertFalse(slowMeal()!!.notify)         // 3
        assertTrue(slowMeal()!!.notify)          // 4: second time
        repeat(4) { assertFalse(slowMeal()!!.notify) }
    }

    @Test
    fun `site is fine silences it for the rest of the pod`() {
        slowMeal()
        check.markSiteFine()
        repeat(3) { assertFalse(slowNoLandingMeal()!!.notify) }
    }

    @Test
    fun `a new pod starts counting again, and asks again`() {
        slowMeal()
        assertTrue(slowMeal()!!.notify)
        check.markSiteFine()
        val newPod = now
        now += 13 * HOUR
        val e = meal(riseSteps = 24, risePerStep = 3.0, iobSteps = 10, iobPerStep = 0.5, fallPerStep = 3.0, site = newPod)!!
        assertEquals(1, e.suspectsOnPod)
        val e2 = meal(riseSteps = 24, risePerStep = 3.0, iobSteps = 10, iobPerStep = 0.5, fallPerStep = 3.0, site = newPod)!!
        assertTrue(e2.notify)
    }

    @Test
    fun `a pod change during a meal drops it`() {
        quiet()
        var b = 110.0
        var i = 0.5
        var e: SiteCheck.Episode? = null
        repeat(12) { b += 3.0; i += 0.5; e = cycle(b, i) ?: e }
        val newPod = now
        repeat(12) { b += 3.0; e = cycle(b, i, site = newPod) ?: e }
        repeat(30) { b -= 0.5; e = cycle(b, i, site = newPod) ?: e }
        assertNull(e)
    }

    @Test
    fun `counts and silence survive a restart`() {
        slowMeal()
        check.markSiteFine()
        check = SiteCheck(sp)
        val e = slowMeal()!!
        assertEquals(2, e.suspectsOnPod)
        assertFalse(e.notify)
    }

    // --- CSV row ---

    @Test
    fun `the CSV row has one value per column, in mmol`() {
        val e = noLandingMeal()!!
        val row = SiteCheckFiles(mock(), mock(), FakeAAPSLogger(collect = false)).row(e).split(",")
        assertEquals(17, row.size)
        assertEquals("6.1", row[5])                 // start BG 110 mg/dL
        assertEquals("true", row[13])               // no landing
    }

    // --- Status for the card ---

    @Test
    fun `the status lists this pod's judged meals, oldest first`() {
        normalMeal()
        slowMeal()
        val s = check.status(now)
        assertEquals(2, s.meals.size)
        assertFalse(s.meals[0].suspect)
        assertTrue(s.meals[1].slow)
        assertEquals(1, s.suspects)
        assertFalse(s.notified)
    }

    @Test
    fun `the meal list survives a restart`() {
        normalMeal()
        slowMeal()
        check = SiteCheck(sp)
        assertEquals(2, check.status(now).meals.size)
    }

    @Test
    fun `a new pod empties the meal list but keeps the totals`() {
        normalMeal()
        slowMeal()
        val newPod = now
        now += 13 * HOUR
        meal(riseSteps = 9, risePerStep = 8.0, iobSteps = 9, iobPerStep = 0.6, fallPerStep = 3.0, site = newPod)
        val s = check.status(now)
        assertEquals(1, s.meals.size)
        assertEquals(3, s.totalMeals)
        assertEquals(2, s.totalPods)
    }

    @Test
    fun `the status shows the meal being watched`() {
        quiet()
        var b = 110.0
        var i = 0.5
        repeat(8) { b += 5.0; i += 0.6; cycle(b, i) }
        val live = check.status(now).live!!
        assertEquals(110.0, live.startBgMgdl, 1e-6)
        assertEquals(150.0, live.bgMgdl, 1e-6)
        assertEquals(4.8, live.extraIobU, 1e-6)
        assertTrue(live.risen)
        assertTrue(live.risingLoadedMin > 0)
    }

    @Test
    fun `the status says when the pod is too new to judge`() {
        now = POD + 8 * HOUR
        cycle(110.0, 0.5, meal = false)
        val s = check.status(now)
        assertTrue(s.waiting)
        assertEquals(8.08, s.podAgeHours!!, 0.01)
    }

    // --- Learning the limits ---

    private fun fresh() {
        sp = FakePreferences()
        check = SiteCheck(sp)
    }

    /** Ends the current pod with [meals] normal meals, then starts a new one 13 h later. Returns its time. */
    private fun podWith(meals: Int, site: Long): Long {
        repeat(meals) { normalMeal(site) }
        val next = now
        now += 13 * HOUR
        quiet(site = next)
        return next
    }

    private fun normalMeal(site: Long) = meal(riseSteps = 9, risePerStep = 8.0, iobSteps = 9, iobPerStep = 0.6, fallPerStep = 3.0, site = site)

    @Test
    fun `the default baseline gives the limits it was made for`() {
        val l = check.status(now).limits!!
        assertEquals(89.5, l.slowMin, 1e-9)
        assertEquals(119.5, l.verySlowMin, 1e-9)
        assertEquals(0.204, l.landingPerU!!, 1e-9)
        assertEquals(25.0, l.typicalRisingMin, 1e-9)
        assertEquals(0.14, l.fallSpreadPerU!!, 1e-9)
    }

    @Test
    fun `while learning, even the worst meal is only measured`() {
        fresh()
        val e = slowNoLandingMeal()!!
        assertTrue(e.learning)
        assertFalse(e.slow || e.noLanding || e.notify)
        assertNull(check.status(now).limits)
    }

    @Test
    fun `a finished pod joins the baseline, the current one does not`() {
        fresh()
        repeat(3) { normalMeal(POD) }
        assertEquals(0, check.status(now).baselineMeals)
        podWith(0, POD)
        val s = check.status(now)
        assertEquals(3, s.baselineMeals)
        assertEquals(1, s.baselinePods)
    }

    @Test
    fun `it judges once it has 30 meals from 3 finished pods`() {
        fresh()
        var site = POD
        repeat(3) { site = podWith(10, site) }
        val s = check.status(now)
        assertEquals(30, s.baselineMeals)
        assertEquals(3, s.baselinePods)
        assertNotNull(s.limits)
        assertFalse(slowNoLandingMeal(site)!!.learning)
    }

    @Test
    fun `30 meals from only 2 pods is still learning`() {
        fresh()
        var site = POD
        repeat(2) { site = podWith(15, site) }
        assertEquals(30, check.status(now).baselineMeals)
        assertNull(check.status(now).limits)
    }

    @Test
    fun `a pod that asked to have its site checked does not join the baseline`() {
        val before = check.status(now).baselineMeals
        slowMeal()
        assertTrue(slowMeal()!!.notify)
        podWith(0, POD)
        assertEquals(before, check.status(now).baselineMeals)
        assertEquals(3, check.status(now).baselinePods)
    }

    @Test
    fun `it does join when the user said the site was fine`() {
        val before = check.status(now).baselineMeals
        slowMeal()
        assertTrue(slowMeal()!!.notify)
        check.markSiteFine()
        podWith(0, POD)
        assertEquals(before + 2, check.status(now).baselineMeals)
        assertEquals(4, check.status(now).baselinePods)
    }

    @Test
    fun `the baseline keeps the newest 60 meals`() {
        fresh()
        var site = POD
        repeat(7) { site = podWith(10, site) }
        assertEquals(60, check.status(now).baselineMeals)
        assertEquals(7, check.status(now).baselinePods)
    }

    @Test
    fun `meals that are all alike still get sensible limits`() {
        // No spread at all: without the floors every meal 1 min slower than usual would be slow.
        fresh()
        check.importBaseline(baseline(List(30) { 10.0 to 0.5 }))
        val l = check.status(now).limits!!
        assertEquals(60.0, l.slowMin, 1e-9)
        assertEquals(90.0, l.verySlowMin, 1e-9)
        assertEquals(0.3, l.landingPerU!!, 1e-9)
    }

    @Test
    fun `the landing is not judged until 15 meals had enough insulin`() {
        fresh()
        check.importBaseline(baseline(List(30) { (it * 2.0) to (if (it < 14) 0.5 else null) }))
        val l = check.status(now).limits!!
        assertNull(l.landingPerU)
        assertFalse(noLandingMeal()!!.noLanding)
    }

    @Test
    fun `someone with slower meals gets wider limits`() {
        // Middle 60 min, spread 30: slow from 189 min, so 105 min is normal for them.
        fresh()
        check.importBaseline(baseline(List(10) { 30.0 to 0.5 } + List(10) { 60.0 to 0.5 } + List(10) { 90.0 to 0.5 }))
        assertEquals(189.0, check.status(now).limits!!.slowMin, 1e-9)
        assertFalse(slowMeal()!!.slow)
    }

    @Test
    fun `a bad import changes nothing`() {
        assertFalse(check.importBaseline("not json"))
        assertFalse(check.importBaseline("""{"pods": 3, "meals": [{"r": -5, "f": 0.5}]}"""))
        assertEquals(89.5, check.status(now).limits!!.slowMin, 1e-9)
    }

    @Test
    fun `the baseline survives a restart`() {
        check = SiteCheck(sp)
        assertEquals(30, check.status(now).baselineMeals)
        assertEquals(89.5, check.status(now).limits!!.slowMin, 1e-9)
    }

    private fun slowNoLandingMeal(site: Long) = meal(riseSteps = 24, risePerStep = 3.0, iobSteps = 10, iobPerStep = 0.5, fallPerStep = 0.5, site = site)

    private companion object {

        fun baseline(meals: List<Pair<Double, Double?>>) =
            """{"pods": 3, "meals": [${meals.joinToString(",") { (r, f) -> """{"r": $r, "f": ${f ?: "null"}}""" }}]}"""

        val BASELINE = baseline(List(10) { 10.0 to 0.40 } + List(10) { 25.0 to 0.54 } + List(10) { 40.0 to 0.68 })

        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val STEP = 5 * MIN
        const val POD = 1_790_000_000_000L
    }
}
