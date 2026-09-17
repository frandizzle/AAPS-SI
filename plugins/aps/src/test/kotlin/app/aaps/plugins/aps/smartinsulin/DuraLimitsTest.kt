package app.aaps.plugins.aps.smartInsulin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [applyDuraLimits] — the clamp that decides what DURA doses AND whether a limit "held" it.
 *
 * "Held" feeds the stall learner's engagement test and picks which knob it loosens, so it must
 * mean the clamp fired against DURA's own push. A result that merely equals a limit is not that.
 * ISF in mg/dL; 18 = 1.0 mmol/U. Floor 12.6 = 0.70.
 */
class DuraLimitsTest {

    private val NONE = DuraStrengthLearner.NO_CEILING
    private val FLOOR = 12.6

    @Test
    fun `no limits — DURA's multiplier goes straight through`() {
        val r = applyDuraLimits(preDuraIsfMgdl = 18.0, rawMult = 1.30, ceiling = NONE, floorMgdl = 0.0)
        assertEquals(18.0 / 1.30, r.isfMgdl, 1e-9)
        assertEquals(1.30, r.effectiveMult, 1e-9)
        assertFalse(r.atCeiling); assertFalse(r.atFloor)
    }

    // ── Ceiling ──────────────────────────────────────────────────────────────

    @Test
    fun `raw push above the ceiling is held at it`() {
        val r = applyDuraLimits(18.0, rawMult = 1.44, ceiling = 1.374, floorMgdl = 0.0)
        assertEquals(1.374, r.effectiveMult, 1e-9)
        assertTrue(r.atCeiling)
    }

    @Test
    fun `raw push below a low ceiling is not held, even though nothing is above it`() {
        // Right after a strength cut DURA ramps slowly. ×1.05 under a ×1.09 ceiling is DURA not
        // trying, not DURA being stopped.
        val r = applyDuraLimits(18.0, rawMult = 1.05, ceiling = 1.09, floorMgdl = 0.0)
        assertEquals(1.05, r.effectiveMult, 1e-9)
        assertFalse(r.atCeiling)
    }

    @Test
    fun `raw push exactly at the ceiling is not held — the clamp never fired`() {
        val r = applyDuraLimits(18.0, rawMult = 1.09, ceiling = 1.09, floorMgdl = 0.0)
        assertFalse(r.atCeiling)
    }

    // ── Floor ────────────────────────────────────────────────────────────────

    @Test
    fun `DURA pushing past the floor is held at it`() {
        // 18 / 1.60 = 11.25, under the 12.6 floor.
        val r = applyDuraLimits(18.0, rawMult = 1.60, ceiling = NONE, floorMgdl = FLOOR)
        assertEquals(FLOOR, r.isfMgdl, 1e-9)
        assertEquals(18.0 / FLOOR, r.effectiveMult, 1e-9)
        assertTrue(r.atFloor)
    }

    @Test
    fun `DURA short of the floor is not held`() {
        val r = applyDuraLimits(18.0, rawMult = 1.20, ceiling = NONE, floorMgdl = FLOOR)
        assertFalse(r.atFloor)
        assertEquals(1.20, r.effectiveMult, 1e-9)
    }

    @Test
    fun `an ISF already under the floor before DURA is not DURA being held`() {
        // The false positive: mode ISF strengthened to 0.65 (11.7) under a 0.70 floor. Any raw
        // ×1.01 used to register as held — and so as engaged.
        val r = applyDuraLimits(11.7, rawMult = 1.01, ceiling = NONE, floorMgdl = FLOOR)
        assertFalse(r.atFloor)
        assertFalse(r.atCeiling)
        assertEquals(1.0, r.effectiveMult, 1e-9)
    }

    @Test
    fun `the floor never weakens the ISF below what it was before DURA`() {
        // Old behaviour raised 11.7 up to 12.6 — DURA on dosed LESS than DURA off.
        val r = applyDuraLimits(11.7, rawMult = 1.40, ceiling = NONE, floorMgdl = FLOOR)
        assertEquals(11.7, r.isfMgdl, 1e-9)
    }

    @Test
    fun `when both would bind only the floor is reported`() {
        // Ceiling holds DURA at ×1.50 → 12.0, still under the floor. The floor is what stops it;
        // loosening the ceiling would change nothing.
        val r = applyDuraLimits(18.0, rawMult = 1.80, ceiling = 1.50, floorMgdl = FLOOR)
        assertTrue(r.atFloor)
        assertFalse(r.atCeiling)
        assertEquals(FLOOR, r.isfMgdl, 1e-9)
    }

    @Test
    fun `ceiling holds and floor has room — only the ceiling is reported`() {
        val r = applyDuraLimits(18.0, rawMult = 1.80, ceiling = 1.20, floorMgdl = FLOOR)
        assertTrue(r.atCeiling)
        assertFalse(r.atFloor)
        assertEquals(1.20, r.effectiveMult, 1e-9)
    }

    @Test
    fun `DURA not pushing reports nothing`() {
        val r = applyDuraLimits(18.0, rawMult = 1.0, ceiling = 1.05, floorMgdl = FLOOR)
        assertEquals(18.0, r.isfMgdl, 1e-9)
        assertFalse(r.atCeiling); assertFalse(r.atFloor)
    }
}
