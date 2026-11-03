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

/** Pre-bolus 2 and 3 of a meal with almost no carbs, given as small pieces that each go through the gates. */
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
    fun `only a meal with almost no carbs is spread, in pieces of at least 0_3 U`() {
        assertEquals(0, MealOverrideManager.spreadPieces(carbsG = 23.0, fatProteinU = 3.2))
        assertEquals(6, MealOverrideManager.spreadPieces(carbsG = 2.0, fatProteinU = 3.2))
        assertEquals(3, MealOverrideManager.spreadPieces(carbsG = 0.0, fatProteinU = 0.9))
        // Too little for 2 pieces: one dose, as before.
        assertEquals(0, MealOverrideManager.spreadPieces(carbsG = 0.0, fatProteinU = 0.5))
    }

    @Test
    fun `pre-bolus 3 becomes part of the pieces`() {
        val m = dinner(minAgo = 5, pb2 = 1.95, pb3 = 1.25)
        m.spreadPreBoluses(6)
        val s = m.activeState!!
        assertEquals(3.2, s.preBolus2U, 1e-9)
        assertEquals(0.0, s.preBolus3U, 1e-9)
        assertFalse(m.preBolus3Pending)
        assertEquals(6, s.preBolus2Pieces)
        assertEquals(3.2, s.preBolus2WaitingU, 1e-9)
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
        m.onLoopCycle(bg(130.0, 0.5), iob(2.0), 12.0)
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
        m.onLoopCycle(bg(120.0, 1.0), iob(1.0), 12.0)
        runBlocking { verify(commandQueue).bolus(argThat { insulin in 0.499..0.501 }) }
        assertFalse(m.preBolus2Pending)
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
