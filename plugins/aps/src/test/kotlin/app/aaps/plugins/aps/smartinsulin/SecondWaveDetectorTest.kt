package app.aaps.plugins.aps.smartInsulin

import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A second helping eaten inside a mode's own window, told apart from ordinary digestion lag.
 *
 * Both signals must agree — a re-accelerating absorption rate AND a second BG peak — and these
 * tests are mostly about the cases that must NOT fire. The rate thresholds are judgement calls
 * about food that doesn't absorb smoothly; the BG peak is a measured fact, and it has the veto.
 *
 * ISF 50 mg/dL/U, IC 10 g/U → 5 g per 50 mg/dL of unexplained rise.
 */
class SecondWaveDetectorTest {

    private lateinit var d: SecondWaveDetector

    @BeforeEach
    fun setUp() { d = SecondWaveDetector(FakeAAPSLogger(collect = false)) }

    /** One cycle: BG and the delta that got it there, with steady insulin activity. */
    private fun cycle(bg: Double, delta: Double, activity: Double = 0.01) =
        d.onCycle(bgMgdl = bg, deltaMgdl = delta, activityPerMin = activity, isfMgdl = 50.0, carbRatio = 10.0)

    /** Walks BG through [deltas], starting from [from]. */
    private fun run(from: Double, deltas: List<Double>, activity: Double = 0.01): Double {
        var bg = from
        deltas.forEach { bg += it; cycle(bg, it, activity) }
        return bg
    }

    @Test
    fun `a single clean meal never fires`() {
        // Rise, peak, decay, all one hump.
        run(110.0, listOf(8.0, 12.0, 14.0, 12.0, 8.0, 4.0, 1.0, -2.0, -4.0, -5.0, -5.0, -4.0))
        assertFalse(d.detected)
    }

    @Test
    fun `a bumpy mixed meal does not fire on the rate shape alone`() {
        // Fat staggering carb absorption: the rate decays and climbs again — the rate signal can
        // genuinely trip — but BG never makes a second peak, so the episode is not voided.
        run(110.0, listOf(10.0, 14.0, 12.0, 6.0, 2.0, 1.0, 5.0, 9.0, 8.0, 5.0, 2.0, -1.0, -3.0))
        assertFalse(d.detected, "a single bumpy meal must not void the episode")
    }

    @Test
    fun `a second meal with both signals fires`() {
        // First hump up to ~190, a real descent, then a second hump past the old high.
        var bg = run(110.0, listOf(10.0, 14.0, 16.0, 14.0, 10.0, 4.0, 0.0))
        bg = run(bg, listOf(-6.0, -8.0, -8.0, -6.0))          // down well off the peak
        run(bg, listOf(6.0, 12.0, 16.0, 14.0, 10.0))          // second entry, new high
        assertTrue(d.detected)
        assertTrue(d.description.contains("new high"), d.description)
    }

    @Test
    fun `a second BG peak with no re-acceleration does not fire`() {
        // BG drifts back over its old high while absorption stays flat — a rebound, not food.
        var bg = run(110.0, listOf(10.0, 14.0, 16.0, 12.0, 6.0))
        bg = run(bg, listOf(-6.0, -8.0, -6.0))
        run(bg, listOf(2.0, 2.0, 3.0, 2.0, 3.0, 2.0, 3.0))
        assertFalse(d.detected, "no absorption re-acceleration — BG alone must not void it")
    }

    @Test
    fun `a small wobble at the top is not a second peak`() {
        var bg = run(110.0, listOf(10.0, 14.0, 16.0, 12.0))
        bg = run(bg, listOf(-4.0, -3.0))                       // under the descent bar
        run(bg, listOf(4.0, 6.0, 8.0))
        assertFalse(d.detected)
    }

    @Test
    fun `a flat episode with no absorption at all never fires`() {
        run(150.0, listOf(0.0, 1.0, -1.0, 0.0, 1.0, -1.0, 0.0, 1.0, -1.0, 0.0))
        assertFalse(d.detected)
    }

    @Test
    fun `insulin working hard raises the bar rather than lowering it`() {
        // Same BG rises, but with heavy insulin activity the residual is smaller, so what looks
        // like absorption to a naive check is mostly the dose being outrun.
        run(110.0, listOf(4.0, 5.0, 4.0, 2.0, 1.0, 0.0, 2.0, 4.0, 5.0), activity = 0.05)
        assertFalse(d.detected)
    }

    @Test
    fun `one meal's own fat-protein tail past the earlier peak does not fire`() {
        // Carb hump to ~155, a real descent, then the P/F tail lifting BG slowly past that high.
        // The rate re-accelerates and BG does make a new high — but at tail speed, not food speed.
        var bg = run(110.0, listOf(8.0, 12.0, 14.0, 10.0, 4.0, 0.0))
        bg = run(bg, listOf(-6.0, -7.0, -7.0, -5.0))
        run(bg, listOf(2.0, 3.0, 3.0, 2.0, 3.0, 3.0, 2.0, 3.0, 3.0, 2.0, 3.0, 2.0))
        assertFalse(d.detected, "a slow tail must stay scoreable — it is the same meal")
    }

    @Test
    fun `a second helping that starts slow and then accelerates still fires`() {
        // The averaging trap: the first few readings of the climb are slow digestion, and only
        // then does it hit carb speed. Peak rate over the climb has to catch it.
        var bg = run(110.0, listOf(8.0, 12.0, 14.0, 10.0, 4.0, 0.0))
        bg = run(bg, listOf(-6.0, -7.0, -7.0, -5.0))
        run(bg, listOf(1.0, 2.0, 2.0, 6.0, 10.0, 12.0, 8.0))
        assertTrue(d.detected, "a slow start must not average a genuinely fast rise under the bar")
    }

    @Test
    fun `reset clears a detection for the next episode`() {
        var bg = run(110.0, listOf(10.0, 14.0, 16.0, 14.0, 10.0, 4.0, 0.0))
        bg = run(bg, listOf(-6.0, -8.0, -8.0, -6.0))
        run(bg, listOf(6.0, 12.0, 16.0, 14.0, 10.0))
        assertTrue(d.detected)
        d.reset()
        assertFalse(d.detected)
        assertTrue(d.description.isEmpty())
    }
}
