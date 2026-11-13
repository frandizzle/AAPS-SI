package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.keys.StringNonKey
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Pre-bolus 2 and 3 of a meal, given as smaller pieces that each go through the gates. */
class PreBolus2PiecesTest {

    private val sp = FakePreferences()
    private val commandQueue: CommandQueue = mock()

    /**
     * A Dinner started [minAgo] minutes ago, pre-bolus 2 due 45 min in, restored from the settings. [extra] adds or
     * changes fields, for example pieces already given.
     */
    private fun dinner(minAgo: Int, pb2: Double = 3.2, pb3: Double = 0.0, extra: JSONObject.() -> Unit = {}): MealOverrideManagerImpl {
        val start = System.currentTimeMillis() - minAgo * 60_000L
        val json = JSONObject().apply {
            put("mode", MealMode.DINNER.name)
            put("triggerTimeMs", start)
            put("modeExpiryMs", start + 4 * 60 * 60_000L)
            put("doseU", 0.1)
            put("preBolus2U", pb2)
            put("preBolus2DelayMs", 45 * 60_000L)
            put("preBolus3U", pb3)
            put("preBolus3DelayMs", 45 * 60_000L)
            extra()
        }
        sp.edit { putString(StringNonKey.ApsSmartInsulinOverrideState.key, json.toString()) }
        val ok: PumpEnactResult = mock()
        whenever(ok.success).thenReturn(true)
        runBlocking { whenever(commandQueue.bolus(any())).thenReturn(ok) }
        return MealOverrideManagerImpl(mock(), sp, commandQueue, mock(), mock(), mock(), CoroutineScope(Dispatchers.Unconfined))
    }

    private fun bg(mgdl: Double, delta: Double, avgDelta: Double = delta): GlucoseStatus = mock<GlucoseStatus>().also {
        whenever(it.glucose).thenReturn(mgdl)
        whenever(it.delta).thenReturn(delta)
        whenever(it.shortAvgDelta).thenReturn(avgDelta)
    }

    private fun iob(u: Double): Array<IobTotal> = arrayOf(IobTotal(0L).apply { iob = u })

    @Test
    fun `pre-bolus 2 is spread whatever the carbs, in pieces of at least 0_3 U`() {
        // 13 Nov lunch, 23 g carbs and 70 g protein: 5.65 U of pre-bolus 2 as 6 pieces of about 0.94 U, not at once.
        assertEquals(6, MealOverrideManager.spreadPieces(fatProteinU = 5.65))
        assertEquals(6, MealOverrideManager.spreadPieces(fatProteinU = 3.2))
        assertEquals(3, MealOverrideManager.spreadPieces(fatProteinU = 0.9))
        // Too little for 2 pieces: one dose, as before.
        assertEquals(0, MealOverrideManager.spreadPieces(fatProteinU = 0.5))
    }

    @Test
    fun `pre-bolus 3 stays its own dose, timed from when the pieces are done`() {
        // 10 Nov: folded into the pieces, all of a turkey dinner's fat and protein insulin was in within 2 hours, and
        // nothing was left for the wave 3 to 6 hours later.
        val m = dinner(minAgo = 5, pb2 = 1.95, pb3 = 1.25)
        m.spreadPreBoluses(6)
        val s = m.activeState!!
        assertEquals(1.95, s.preBolus2U, 1e-9)
        assertEquals(6, s.preBolus2Pieces)
        assertEquals(1.95, s.preBolus2WaitingU, 1e-9)
        assertEquals(1.25, s.preBolus3U, 1e-9)
        assertEquals(45 * 60_000L, s.preBolus3DelayMs)
        assertTrue(m.preBolus3Pending)
        // No time for it while pieces are still to come.
        assertEquals(null, s.preBolus3FireAtMs)
    }

    @Test
    fun `one piece at a time, and the next only after the gap`() {
        val m = dinner(minAgo = 50) { put("preBolus2Pieces", 6) }
        m.onLoopCycle(bg(120.0, 1.0), iob(1.0), 12.0)
        // 3.2 U over 6 pieces, rounded down to 0.05.
        runBlocking { verify(commandQueue).bolus(argThat { insulin == 0.5 }) }
        assertTrue(m.preBolus2Pending)
        assertEquals(2.7, m.activeState!!.preBolus2WaitingU, 1e-9)
        assertTrue(m.preBolus2StatusText.startsWith("PB2 piece 2 of 6"), m.preBolus2StatusText)
        m.onLoopCycle(bg(122.0, 1.0), iob(1.5), 12.0)
        runBlocking { verify(commandQueue, times(1)).bolus(any()) }
    }

    @Test
    fun `a piece waits while BG falls, and does not grow`() {
        val m = dinner(minAgo = 50) { put("preBolus2Pieces", 6) }
        m.onLoopCycle(bg(108.0, -4.5, avgDelta = -4.3), iob(1.3), 12.0)
        runBlocking { verify(commandQueue, never()).bolus(any()) }
        assertEquals(0.5, m.activeState!!.preBolus2NextPieceU, 1e-9)
        assertTrue(m.preBolus2StatusText.contains("BG falling"), m.preBolus2StatusText)
    }

    @Test
    fun `the last piece is what is left, and then pre-bolus 2 is done`() {
        val m = dinner(minAgo = 160) {
            put("preBolus2Pieces", 6)
            put("preBolus2PiecesGiven", 5)
            put("preBolus2GivenU", 2.5)
            put("preBolus2LastPieceMs", System.currentTimeMillis() - 25 * 60_000L)
        }
        // Past the first half the pieces are led by BG: 3 mmol above target the piece is given in full.
        m.onLoopCycle(bg(165.0, 0.5), iob(2.0), 12.0)
        runBlocking { verify(commandQueue).bolus(argThat { insulin in 0.699..0.701 }) }
        assertFalse(m.preBolus2Pending)
        assertEquals(3.2, m.activePb2DoseU!!, 1e-9)
    }

    @Test
    fun `stopping the pieces keeps what was given in the meal total`() {
        val m = dinner(minAgo = 80) {
            put("preBolus2Pieces", 6)
            put("preBolus2PiecesGiven", 2)
            put("preBolus2GivenU", 1.0)
            put("preBolus2LastPieceMs", System.currentTimeMillis() - 5 * 60_000L)
        }
        m.cancelPreBolus2()
        val s = m.activeState!!
        assertFalse(s.preBolus2Pending)
        assertEquals(0.1 + 1.0, s.plannedTotalU, 1e-9)
        assertTrue(m.preBolus2StatusText.contains("stopped after 1"), m.preBolus2StatusText)
    }

    @Test
    fun `the first piece comes sooner once BG shows the protein coming in`() {
        // 25 min in, BG rising: the first piece does not wait for the 45 min.
        val m = dinner(minAgo = 25) { put("preBolus2Pieces", 6) }
        m.onLoopCycle(bg(120.0, 3.0, avgDelta = 2.0), iob(0.5), 12.0)
        runBlocking { verify(commandQueue).bolus(argThat { insulin == 0.5 }) }
        // The next one keeps the 20 min gap.
        m.onLoopCycle(bg(124.0, 3.0, avgDelta = 3.0), iob(1.0), 12.0)
        runBlocking { verify(commandQueue, times(1)).bolus(any()) }
    }

    @Test
    fun `not sooner while BG is flat, or in the first quarter hour`() {
        dinner(minAgo = 25) { put("preBolus2Pieces", 6) }.onLoopCycle(bg(120.0, 0.5, avgDelta = 0.5), iob(0.5), 12.0)
        dinner(minAgo = 10) { put("preBolus2Pieces", 6) }.onLoopCycle(bg(120.0, 3.0, avgDelta = 2.0), iob(0.5), 12.0)
        runBlocking { verify(commandQueue, never()).bolus(any()) }
    }

    @Test
    fun `the loop's SMBs come off the pieces`() {
        val m = dinner(minAgo = 50) { put("preBolus2Pieces", 6) }
        m.countLoopSmb(1.0)
        assertEquals(2.2, m.activeState!!.preBolus2WaitingU, 1e-9)
        assertTrue(m.preBolus2StatusText.contains("loop gave 1.00 U"), m.preBolus2StatusText)
        m.onLoopCycle(bg(120.0, 1.0), iob(1.0), 12.0)
        // 2.2 U over 6 pieces, down to 0.05.
        runBlocking { verify(commandQueue).bolus(argThat { insulin in 0.349..0.351 }) }
    }

    @Test
    fun `when the loop gave it all, pre-bolus 2 is done, and a piece given is never taken back`() {
        val all = dinner(minAgo = 50) { put("preBolus2Pieces", 6) }
        all.countLoopSmb(4.0)
        assertFalse(all.preBolus2Pending)
        assertEquals(3.2, all.activeState!!.preBolus2LoopU, 1e-9)

        val part = dinner(minAgo = 120) {
            put("preBolus2Pieces", 6)
            put("preBolus2PiecesGiven", 5)
            put("preBolus2GivenU", 2.5)
            put("preBolus2LastPieceMs", System.currentTimeMillis() - 25 * 60_000L)
        }
        part.countLoopSmb(3.0)
        assertEquals(0.7, part.activeState!!.preBolus2LoopU, 1e-9)
        assertFalse(part.preBolus2Pending)
    }

    @Test
    fun `with little left, fewer pieces give it`() {
        // 0.5 U left over 4 pieces would be pieces under 0.3 U: it comes as one, and then pre-bolus 2 is done.
        val m = dinner(minAgo = 90) {
            put("preBolus2Pieces", 6)
            put("preBolus2PiecesGiven", 2)
            put("preBolus2GivenU", 1.0)
            put("preBolus2LastPieceMs", System.currentTimeMillis() - 25 * 60_000L)
            put("preBolus2LoopU", 1.7)
        }
        assertEquals(0.5, m.activeState!!.preBolus2NextPieceU, 1e-9)
        m.onLoopCycle(bg(165.0, 1.0), iob(1.0), 12.0)
        runBlocking { verify(commandQueue).bolus(argThat { insulin in 0.499..0.501 }) }
        assertFalse(m.preBolus2Pending)
    }

    // ── Led by BG: the second half of pre-bolus 2, and pre-bolus 3 ──────────
    // The profile target in these tests is 108 mg/dL (6.0 mmol): no profile is passed.

    /** Pre-bolus 2 with half of its 3.2 U given: the pieces after it are led by BG. */
    private fun secondHalf() = dinner(minAgo = 120) {
        put("preBolus2Pieces", 6)
        put("preBolus2PiecesGiven", 3)
        put("preBolus2GivenU", 1.6)
        put("preBolus2LastPieceMs", System.currentTimeMillis() - 25 * 60_000L)
    }

    @Test
    fun `the first half of pre-bolus 2 comes on time, just above target and flat`() {
        val m = dinner(minAgo = 50) { put("preBolus2Pieces", 6) }
        m.onLoopCycle(bg(112.0, 0.0), iob(1.0), 12.0)
        runBlocking { verify(commandQueue).bolus(argThat { insulin == 0.5 }) }
    }

    @Test
    fun `past the first half, a piece waits near target and flat`() {
        // 11 Nov, 20:42: a 0.7 U piece at 5.8 with a 5.5 target, BG drifting down 4.5 hours into a dinner.
        val m = secondHalf()
        assertTrue(m.activeState!!.preBolus2BgLed)
        m.onLoopCycle(bg(115.0, -1.8), iob(3.2), 12.0)
        runBlocking { verify(commandQueue, never()).bolus(any()) }
        assertTrue(m.preBolus2StatusText.contains("BG not 1 mmol above target or rising"), m.preBolus2StatusText)
        assertTrue(m.preBolus2StatusText.contains("led by BG"), m.preBolus2StatusText)
    }

    @Test
    fun `past the first half, a piece is half its size where BG first asks, and the rest is dropped`() {
        val m = secondHalf()
        // Above target and rising 0.1 mmol: half of the 0.5 U piece (1.6 U left over 3 pieces).
        m.onLoopCycle(bg(115.0, 2.0), iob(2.0), 12.0)
        runBlocking { verify(commandQueue).bolus(argThat { insulin in 0.249..0.251 }) }
        val s = m.activeState!!
        assertEquals(0.25, s.preBolus2DroppedU, 1e-9)
        // What was left out is not carried on: 1.1 U left over 2 pieces is 0.55 U, not 0.65.
        assertEquals(1.1, s.preBolus2LeftU, 1e-9)
        assertEquals(0.55, s.preBolus2NextPieceU, 1e-9)
        // The meal total counts only what was given.
        assertEquals(0.1 + 3.2 - 0.25, s.plannedTotalU, 1e-9)
    }

    @Test
    fun `a piece led by BG grows to its full size 3 mmol above target`() {
        assertEquals(0.5, MealOverrideManager.bgLedShare(126.0, 108.0, 0.0)!!, 1e-9)
        assertEquals(0.75, MealOverrideManager.bgLedShare(144.0, 108.0, 0.0)!!, 1e-9)
        assertEquals(1.0, MealOverrideManager.bgLedShare(162.0, 108.0, 0.0)!!, 1e-9)
        assertEquals(1.0, MealOverrideManager.bgLedShare(250.0, 108.0, 0.0)!!, 1e-9)
        assertEquals(null, MealOverrideManager.bgLedShare(125.0, 108.0, 1.7))
        assertEquals(null, MealOverrideManager.bgLedShare(105.0, 108.0, 5.0))
        // 2 mmol above target: three quarters of the 0.5 U piece, rounded down to 0.35 U.
        val m = secondHalf()
        m.onLoopCycle(bg(144.0, 0.0), iob(2.0), 12.0)
        runBlocking { verify(commandQueue).bolus(argThat { insulin in 0.349..0.351 }) }
    }

    /** A dinner whose pre-bolus 2 was done [pb2DoneMinAgo] minutes ago, with [pb3] U of pre-bolus 3 due 120 min after it. */
    private fun withPb3(pb3: Double, pb2DoneMinAgo: Int, extra: JSONObject.() -> Unit = {}) = dinner(minAgo = 300, pb2 = 3.0, pb3 = pb3) {
        put("preBolus2FiredMs", System.currentTimeMillis() - pb2DoneMinAgo * 60_000L)
        put("preBolus3DelayMs", 120 * 60_000L)
        put("modeExpiryMs", System.currentTimeMillis() + 3 * 60 * 60_000L)
        extra()
    }

    @Test
    fun `pre-bolus 3 comes in pieces led by BG, and waits near target and flat`() {
        // 11 Nov: a 4.4 U pre-bolus 3 would have come in one dose at about 5.6 and flat.
        val m = withPb3(pb3 = 4.4, pb2DoneMinAgo = 125)
        assertEquals(6, m.activeState!!.preBolus3Pieces)
        m.onLoopCycle(bg(112.0, 0.0), iob(2.0), 12.0)
        runBlocking { verify(commandQueue, never()).bolus(any()) }
        assertTrue(m.preBolus3StatusText.contains("piece 1 of 6"), m.preBolus3StatusText)
        assertTrue(m.preBolus3StatusText.contains("BG not 1 mmol above target or rising"), m.preBolus3StatusText)
        // 3 mmol above target: the full first piece, 4.4 U over 6 pieces rounded down.
        m.onLoopCycle(bg(165.0, 0.0), iob(2.0), 12.0)
        runBlocking { verify(commandQueue).bolus(argThat { insulin in 0.699..0.701 }) }
        val s = m.activeState!!
        assertEquals(1, s.preBolus3PiecesGiven)
        assertTrue(m.preBolus3Pending)
        // The next piece keeps the 20 min gap.
        m.onLoopCycle(bg(170.0, 1.0), iob(2.7), 12.0)
        runBlocking { verify(commandQueue, times(1)).bolus(any()) }
    }

    @Test
    fun `the loop's SMBs come off the pieces of pre-bolus 3, and a piece given is never taken back`() {
        // 13 Nov lunch: pre-bolus 3 of 3.05 U in pieces, and the loop's SMBs on the same wave on top of them.
        val m = withPb3(pb3 = 3.0, pb2DoneMinAgo = 130)
        m.countLoopSmbForPreBolus3(1.2)
        val s = m.activeState!!
        assertEquals(1.2, s.preBolus3LoopU, 1e-9)
        assertEquals(1.8, s.preBolus3LeftU, 1e-9)
        assertTrue(m.preBolus3StatusText.contains("loop gave 1.20 U"), m.preBolus3StatusText)
        // 3 mmol above target: the full piece, of what is left: 1.8 U over 6 pieces, 0.3 U.
        m.onLoopCycle(bg(165.0, 0.0), iob(2.0), 12.0)
        runBlocking { verify(commandQueue).bolus(argThat { insulin in 0.299..0.301 }) }
        // The loop then gives more than is left: pre-bolus 3 is done, and the piece stays given.
        m.countLoopSmbForPreBolus3(5.0)
        val after = m.activeState!!
        assertEquals(0.3, after.preBolus3GivenU, 1e-9)
        assertEquals(2.7, after.preBolus3LoopU, 1e-9)
        assertFalse(m.preBolus3Pending)
        // Saved with the meal.
        assertTrue(sp.getString(StringNonKey.ApsSmartInsulinOverrideState.key, "").contains("\"preBolus3LoopU\":2.7"))
    }

    @Test
    fun `with all the fat and protein still to come, the waiting pre-boluses do not fade`() {
        val m = dinner(minAgo = 60, pb2 = 3.2, pb3 = 1.95) { put("preBolus2Pieces", 6) }
        m.fadeWaitingPreBoluses(1.0)
        val s = m.activeState!!
        assertEquals(0.0, s.preBolus2DroppedU, 1e-9)
        assertEquals(0.0, s.preBolus3DroppedU, 1e-9)
    }

    @Test
    fun `the waiting pre-boluses fade with the fat and protein, pre-bolus 3 first`() {
        // Half the fat and protein left: of 5.15 U planned, at most 2.575 U may still wait. Pre-bolus 3 (1.95 U) goes
        // first, then 0.625 U of the pieces of pre-bolus 2.
        val m = dinner(minAgo = 60, pb2 = 3.2, pb3 = 1.95) { put("preBolus2Pieces", 6) }
        m.fadeWaitingPreBoluses(0.5)
        val s = m.activeState!!
        assertEquals(1.95, s.preBolus3DroppedU, 1e-9)
        assertFalse(s.preBolus3Pending)
        assertEquals(0.625, s.preBolus2DroppedU, 1e-9)
        assertEquals(2.575, s.preBolus2LeftU, 1e-9)
        assertTrue(s.preBolus2Pending)
        // The same share again changes nothing; none left drops the rest and ends pre-bolus 2.
        m.fadeWaitingPreBoluses(0.5)
        assertEquals(0.625, m.activeState!!.preBolus2DroppedU, 1e-9)
        m.fadeWaitingPreBoluses(0.0)
        assertFalse(m.activeState!!.preBolus2Pending)
        assertEquals(3.2, m.activeState!!.preBolus2DroppedU, 1e-9)
    }

    @Test
    fun `pieces already given are not taken back when the fat and protein fades`() {
        // 2 of 3.2 U given: 1.2 U waits. With 30 % left, 0.96 U of the 3.2 U plan may wait, so 0.24 U is dropped.
        val m = dinner(minAgo = 120, pb2 = 3.2) {
            put("preBolus2Pieces", 6); put("preBolus2PiecesGiven", 4); put("preBolus2GivenU", 2.0)
            put("preBolus2LastPieceMs", System.currentTimeMillis() - 5 * 60_000L)
        }
        m.fadeWaitingPreBoluses(0.3)
        val s = m.activeState!!
        assertEquals(2.0, s.preBolus2GivenU, 1e-9)
        assertEquals(0.24, s.preBolus2DroppedU, 1e-9)
        assertEquals(0.96, s.preBolus2LeftU, 1e-9)
    }

    @Test
    fun `one dose of pre-bolus 2 does not fade`() {
        val m = dinner(minAgo = 30, pb2 = 0.5)
        m.fadeWaitingPreBoluses(0.2)
        assertEquals(0.0, m.activeState!!.preBolus2DroppedU, 1e-9)
        assertTrue(m.activeState!!.preBolus2Pending)
    }

    @Test
    fun `pre-bolus 3 is not due before its time`() {
        val m = withPb3(pb3 = 2.4, pb2DoneMinAgo = 60)
        m.onLoopCycle(bg(170.0, 3.0), iob(1.0), 12.0)
        runBlocking { verify(commandQueue, never()).bolus(any()) }
    }

    @Test
    fun `the last piece of pre-bolus 3 ends it, and only what was given counts`() {
        val m = withPb3(pb3 = 2.4, pb2DoneMinAgo = 230) {
            put("preBolus3PiecesGiven", 5)
            put("preBolus3GivenU", 1.4)
            put("preBolus3DroppedU", 0.6)
            put("preBolus3LastPieceMs", System.currentTimeMillis() - 25 * 60_000L)
        }
        // 0.4 U left, half of it at the gate.
        m.onLoopCycle(bg(126.0, 0.0), iob(1.0), 12.0)
        runBlocking { verify(commandQueue).bolus(argThat { insulin in 0.199..0.201 }) }
        assertFalse(m.preBolus3Pending)
        assertEquals(1.6, m.activePb3DoseU!!, 1e-9)
        assertTrue(m.preBolus3StatusText.contains("gave 1.60 U of 2.40 U"), m.preBolus3StatusText)
    }

    /** A meal whose mode ends 50 ms from now, with pre-bolus 3 pieces still to come; returned once it has ended. */
    private fun endedWithPb3(pb2DoneMinAgo: Int): MealOverrideManagerImpl {
        val m = withPb3(pb3 = 2.4, pb2DoneMinAgo = pb2DoneMinAgo) {
            put("modeExpiryMs", System.currentTimeMillis() + 50L)
            put("preBolus3PiecesGiven", 1)
            put("preBolus3GivenU", 0.4)
            put("preBolus3LastPieceMs", System.currentTimeMillis() - 19 * 60_000L)
        }
        Thread.sleep(100L)
        return m
    }

    @Test
    fun `when the meal mode ends, a pre-bolus 3 piece still minutes away is dropped and leaves the screen`() {
        // 12 Nov: "PB3: in 40s" stayed on screen in Fasting after the meal mode had ended.
        val m = endedWithPb3(pb2DoneMinAgo = 160)
        // Off the screen at once, before the next loop cycle.
        assertFalse(m.preBolus3Pending)
        assertEquals("", m.preBolus3StatusText)
        assertEquals(null, m.pb3GateData)
        m.onLoopCycle(bg(165.0, 2.0), iob(1.0), 12.0)
        runBlocking { verify(commandQueue, never()).bolus(any()) }
        assertEquals(null, m.activeState)
    }

    @Test
    fun `when the meal mode ends, a pre-bolus 3 piece due with the gates open is not given`() {
        // Due (the last piece 25 min ago), BG high and rising: before, it would have fired after the meal.
        val m = withPb3(pb3 = 2.4, pb2DoneMinAgo = 170) {
            put("modeExpiryMs", System.currentTimeMillis() + 50L)
            put("preBolus3PiecesGiven", 1)
            put("preBolus3GivenU", 0.4)
            put("preBolus3LastPieceMs", System.currentTimeMillis() - 25 * 60_000L)
        }
        Thread.sleep(100L)
        m.onLoopCycle(bg(165.0, 2.0), iob(1.0), 12.0)
        runBlocking { verify(commandQueue, never()).bolus(any()) }
        assertEquals(null, m.activeState)
    }

    @Test
    fun `a restored meal keeps its pre-bolus 3 pieces`() {
        val m = withPb3(pb3 = 2.4, pb2DoneMinAgo = 140) {
            put("preBolus3PiecesGiven", 1)
            put("preBolus3GivenU", 0.2)
            put("preBolus3DroppedU", 0.2)
            put("preBolus3LastPieceMs", System.currentTimeMillis() - 10 * 60_000L)
        }
        val s = m.activeState!!
        assertEquals(2.0, s.preBolus3WaitingU, 1e-9)
        assertEquals(0.4, s.preBolus3NextPieceU, 1e-9)
        val secs = m.preBolus3SecondsRemaining!!
        assertTrue(secs in 9 * 60..10 * 60, "$secs")
    }

    @Test
    fun `a restored meal keeps its pieces`() {
        val m = dinner(minAgo = 70) {
            put("preBolus2Pieces", 6)
            put("preBolus2PiecesGiven", 1)
            put("preBolus2GivenU", 0.5)
            put("preBolus2LastPieceMs", System.currentTimeMillis() - 10 * 60_000L)
        }
        val s = m.activeState!!
        assertEquals(1, s.preBolus2PiecesGiven)
        assertEquals(2.7, s.preBolus2WaitingU, 1e-9)
        // The next piece is 20 min after the last one.
        val secs = m.preBolus2SecondsRemaining!!
        assertTrue(secs in 9 * 60..10 * 60, "$secs")
    }
}
