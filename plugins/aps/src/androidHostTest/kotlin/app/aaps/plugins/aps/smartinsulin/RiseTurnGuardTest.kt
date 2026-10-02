package app.aaps.plugins.aps.smartInsulin

import app.aaps.plugins.aps.smartInsulin.RiseTurnGuard.Level
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * [RiseTurnGuard]: at the top of a rise, use the raw CGM step when the smoothed delta lags behind.
 *
 * Values in the scenarios are mmol/L, as read off a phone; the guard itself works in mg/dL.
 * Where the smoothed delta was measured (UKF on the 2 Oct G7 data) it is given as is. Elsewhere a
 * simple trend-following smoother stands in for UKF: like UKF it carries the trend on for a reading
 * or two after the raw rise has stopped, which is the only property these tests need.
 */
class RiseTurnGuardTest {

    private val T0 = 1_700_000_000_000L
    private val FIVE_MIN = 5 * 60_000L

    /** The guard at the newest reading of [rawMmol] (oldest first), with this smoothed delta. */
    private fun at(rawMmol: List<Double>, smoothedDeltaMmol: Double, gapMin: Long = 5): RiseTurnGuard.Result {
        val newestFirst = rawMmol.reversed().map { it * 18.0 }
        val times = newestFirst.indices.map { T0 - it * gapMin * 60_000L }
        return RiseTurnGuard.apply(smoothedDeltaMmol * 18.0, newestFirst, times)
    }

    /** A trend-following smoother (Holt), the stand-in for UKF. Smoothed delta per reading, mmol. */
    private fun holtDeltas(rawMmol: List<Double>, a: Double = 0.4, b: Double = 0.6): List<Double> {
        var level = rawMmol[0]; var trend = 0.0
        return rawMmol.mapIndexed { i, x ->
            if (i > 0) {
                val prev = level
                level = a * x + (1 - a) * (level + trend)
                trend = b * (level - prev) + (1 - b) * trend
            }
            trend
        }
    }

    /** The guard at every reading of a series, smoothed by [holtDeltas]. */
    private fun walk(rawMmol: List<Double>): List<RiseTurnGuard.Result> {
        val sm = holtDeltas(rawMmol)
        return rawMmol.indices.map { i -> at(rawMmol.take(i + 1), sm[i]) }
    }

    private fun mmol(r: RiseTurnGuard.Result) = r.deltaMgdl / 18.0

    // ── the morning it was built for ─────────────────────────────────────────

    private val oct2 = List(6) { 5.5 } + listOf(5.1, 5.6, 6.2, 7.6, 9.3, 10.2, 10.4)

    @Test fun `2 Oct 8_26 - the rise has slowed once, so halfway`() {
        // raw steps +1.4, +1.7, +0.9; UKF said +1.2
        val r = at(oct2.dropLast(1), smoothedDeltaMmol = 1.2)
        assertEquals(Level.HALF, r.level)
        assertEquals(1.05, mmol(r), 0.01)
    }

    @Test fun `2 Oct 8_31 - slowed again, so the raw step`() {
        // raw steps +1.7, +0.9, +0.2; UKF still said +0.8
        val r = at(oct2, smoothedDeltaMmol = 0.8)
        assertEquals(Level.RAW, r.level)
        assertEquals(0.2, mmol(r), 0.01)
    }

    @Test fun `2 Oct - nothing changes while the rise was building`() {
        listOf(0.4, 0.8, 1.2).forEachIndexed { i, ukf ->
            val upTo = oct2.size - 4 + i     // 7.6, 9.3 and before
            assertEquals(Level.NONE, at(oct2.take(upTo), ukf).level, "at ${oct2[upTo - 1]}")
        }
    }

    // ── food rises: the guard must get out of the way ────────────────────────

    @Test fun `stair-step food rise - one cycle halved at the pause, then normal again`() {
        val raw = List(6) { 5.5 } + listOf(6.5, 7.4, 7.5, 8.5, 9.4)
        val res = walk(raw)
        val byBg = raw.zip(res).drop(6)
        assertEquals(Level.NONE, byBg[0].second.level, "6.5")
        assertEquals(Level.NONE, byBg[1].second.level, "7.4: +0.9 after +1.0 is not a sharp slowdown")
        assertEquals(Level.HALF, byBg[2].second.level, "7.5: the pause")
        assertEquals(Level.NONE, byBg[3].second.level, "8.5: the rise is back")
        assertEquals(Level.NONE, byBg[4].second.level, "9.4")
    }

    @Test fun `a steady rise is never touched`() {
        val raw = List(6) { 5.5 } + List(12) { 5.5 + 0.6 * (it + 1) }
        walk(raw).forEach { assertEquals(Level.NONE, it.level) }
    }

    @Test fun `a rise that speeds up is never touched`() {
        val raw = List(6) { 5.5 } + listOf(5.8, 6.3, 7.0, 7.9, 9.0, 10.3)
        walk(raw).forEach { assertEquals(Level.NONE, it.level) }
    }

    @Test fun `a gradual slowdown is left to the smoothing`() {
        // steps +1.0, +0.8, +0.6, +0.4, +0.2: each only 0.2 smaller, never sharp
        val raw = List(6) { 5.5 } + listOf(6.5, 7.3, 7.9, 8.3, 8.5)
        walk(raw).forEach { assertEquals(Level.NONE, it.level) }
    }

    @Test fun `a rise that pauses for one reading and then carries on is only halved once`() {
        val raw = List(6) { 5.5 } + listOf(6.3, 7.1, 7.2, 8.0, 8.8, 9.6)
        val levels = walk(raw).drop(6).map { it.level }
        assertEquals(1, levels.count { it != Level.NONE }, "levels: $levels")
    }

    // ── the top of a rise ────────────────────────────────────────────────────

    @Test fun `an abrupt stop - halved, then raw while it stays flat`() {
        val raw = List(6) { 5.5 } + listOf(6.5, 7.5, 8.5, 8.5, 8.5, 8.5)
        val levels = walk(raw).drop(6).map { it.level }
        assertEquals(listOf(Level.NONE, Level.NONE, Level.NONE, Level.HALF, Level.RAW, Level.RAW), levels)
    }

    @Test fun `a stop that turns into a fall uses the falling raw step`() {
        val raw = List(6) { 5.5 } + listOf(6.5, 7.5, 8.5, 8.6, 8.3)
        val last = walk(raw).last()
        assertEquals(Level.RAW, last.level)
        assertEquals(-0.3, mmol(last), 0.01)
    }

    @Test fun `once the smoothing has caught up there is nothing left to do`() {
        // A long flat top: the slowdown is out of memory and the smoother has settled.
        val raw = List(6) { 5.5 } + listOf(6.5, 7.5, 8.5) + List(8) { 8.5 }
        assertEquals(Level.NONE, walk(raw).last().level)
    }

    // ── noise ────────────────────────────────────────────────────────────────

    @Test fun `a single low reading during a rise is floored at flat, not a predicted fall`() {
        // +1.0, +1.0, then a -1.0 dip (compression or noise)
        val r = at(listOf(5.5, 5.5, 5.5, 6.5, 7.5, 6.5), smoothedDeltaMmol = 0.9)
        assertEquals(Level.HALF, r.level)
        assertTrue(mmol(r) >= 0.0, "one reading must not predict a fall, got ${mmol(r)}")
        assertEquals(0.45, mmol(r), 0.01)
    }

    @Test fun `the reading after a single low dip is back to normal`() {
        val r = at(listOf(5.5, 5.5, 5.5, 6.5, 7.5, 6.5, 8.6), smoothedDeltaMmol = 0.9)
        assertEquals(Level.NONE, r.level)
    }

    @Test fun `random noise around a steady rise never raises the delta`() {
        val rnd = Random(42)
        repeat(2_000) {
            val raw = List(6) { 5.5 } + List(8) { i -> 5.5 + 0.5 * (i + 1) + rnd.nextDouble(-0.3, 0.3) }
            val sm = rnd.nextDouble(0.0, 1.5)
            val r = at(raw, sm)
            assertTrue(r.deltaMgdl <= sm * 18.0 + 1e-9, "raised: ${r.deltaMgdl} > ${sm * 18}")
        }
    }

    // ── never more insulin, and only when it applies ─────────────────────────

    @Test fun `never above the smoothed delta, whatever the readings`() {
        val rnd = Random(7)
        repeat(5_000) {
            val raw = List(rnd.nextInt(0, 8)) { rnd.nextDouble(2.5, 22.0) }
            val sm = rnd.nextDouble(-2.0, 2.0)
            val r = at(raw, sm)
            assertTrue(r.deltaMgdl <= sm * 18.0 + 1e-9, "raised: ${r.deltaMgdl} > ${sm * 18}")
            if (r.level == Level.NONE) assertEquals(sm * 18.0, r.deltaMgdl, 1e-9)
        }
    }

    @Test fun `a raw step bigger than the smoothed one changes nothing`() {
        val r = at(listOf(5.5, 5.5, 6.5, 8.0, 8.4), smoothedDeltaMmol = 0.2)
        assertEquals(Level.NONE, r.level)
    }

    @Test fun `a rise that picks back up after a pause is left to the smoothing, even below it`() {
        // steps +1.2, +0.1, then +0.5: the rise is back, so this is food carrying on, not a top
        val r = at(listOf(5.5, 5.5, 6.7, 6.8, 7.3), smoothedDeltaMmol = 0.9)
        assertEquals(Level.NONE, r.level)
        assertEquals(0.9, mmol(r), 1e-9)
    }

    @Test fun `a fall that gets steeper is not touched - the guard is only for rises`() {
        // smoothed already says falling; the raw fall speeding up must not be used to cut more
        val r = at(listOf(8.0, 8.0, 7.9, 7.0, 6.0), smoothedDeltaMmol = -0.3)
        assertEquals(Level.NONE, r.level)
        assertEquals(-0.3, mmol(r), 1e-9)
    }

    @Test fun `not rising - nothing to do`() {
        assertEquals(Level.NONE, at(listOf(9.0, 8.5, 8.4, 8.4, 8.4), smoothedDeltaMmol = 0.0).level)
        assertEquals(Level.NONE, at(listOf(9.0, 8.5, 8.0, 7.9, 7.9), smoothedDeltaMmol = -0.3).level)
    }

    @Test fun `readings not 5 minutes apart are not used`() {
        assertEquals(Level.NONE, at(oct2, smoothedDeltaMmol = 0.8, gapMin = 10).level)
        assertEquals(Level.NONE, at(oct2, smoothedDeltaMmol = 0.8, gapMin = 1).level)
    }

    @Test fun `a gap inside the window stops the steps there`() {
        // newest step is 5 min, the one before it spans a 15-min gap: only one usable step
        val raw = listOf(10.4, 10.2, 9.3).map { it * 18 }
        val r = RiseTurnGuard.apply(0.8 * 18, raw, listOf(T0, T0 - FIVE_MIN, T0 - 4 * FIVE_MIN))
        assertEquals(Level.NONE, r.level)
    }

    @Test fun `too few readings, or sensor error values, change nothing`() {
        assertEquals(Level.NONE, at(listOf(10.2), 0.8).level)
        assertEquals(Level.NONE, at(listOf(10.2, 10.4), 0.8).level)
        val withError = listOf(7.6, 9.3, 10.2).map { it * 18 }.reversed() + listOf(38.0)
        assertEquals(Level.NONE, RiseTurnGuard.apply(0.8 * 18, withError,
                                                     withError.indices.map { T0 - it * FIVE_MIN }).level)
    }
}
