package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.keys.StringNonKey
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** The rest of pre-bolus 1: given 15 to 45 min into the meal once BG rises, or not at all. */
class PreBolus1RestTest {

    private val sp = FakePreferences()
    private val commandQueue: CommandQueue = mock()

    /** A Dinner started [minAgo] minutes ago with a [restU] rest of pre-bolus 1 still waiting, restored from the settings. */
    private fun dinner(minAgo: Int, restU: Double = 3.0, eatInMin: Int = 0): MealOverrideManagerImpl {
        val start = System.currentTimeMillis() - minAgo * 60_000L
        val json = JSONObject().apply {
            put("mode", MealMode.DINNER.name)
            put("triggerTimeMs", start)
            put("modeExpiryMs", start + 3 * 60 * 60_000L)
            put("doseU", 6.0)
            put("preBolus1RestU", restU)
            put("eatDelayMs", eatInMin * 60_000L)
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
    fun `nothing before 15 minutes, even when BG rises`() {
        val m = dinner(minAgo = 10)
        m.onLoopCycle(bg(120.0, 5.0), iob(5.0), 12.0)
        assertTrue(m.preBolus1RestPending)
        runBlocking { verify(commandQueue, never()).bolus(any()) }
        assertTrue(m.preBolus1RestStatusText.contains("checks BG in 5min"))
    }

    @Test
    fun `with eat in, its window counts from when the meal is eaten`() {
        // Eaten 20 min after the start, so 25 min in is only 5 min after eating.
        val m = dinner(minAgo = 25, eatInMin = 20)
        m.onLoopCycle(bg(120.0, 5.0, avgDelta = 3.0), iob(5.0), 12.0)
        assertTrue(m.preBolus1RestPending)
        assertTrue(m.preBolus1RestStatusText.contains("checks BG in 10min"))
        runBlocking { verify(commandQueue, never()).bolus(any()) }
        // 40 min in is 20 min after eating: it fires.
        dinner(minAgo = 40, eatInMin = 20).onLoopCycle(bg(120.0, 5.0, avgDelta = 3.0), iob(5.0), 12.0)
        runBlocking { verify(commandQueue).bolus(any()) }
    }

    @Test
    fun `it waits while BG is flat, and fires once BG rises`() {
        val m = dinner(minAgo = 20)
        m.onLoopCycle(bg(110.0, 0.5), iob(5.0), 12.0)
        assertTrue(m.preBolus1RestPending)
        assertTrue(m.preBolus1RestStatusText.contains("BG not rising yet"))
        m.onLoopCycle(bg(118.0, 4.0, avgDelta = 3.0), iob(5.0), 12.0)
        assertFalse(m.preBolus1RestPending)
        runBlocking { verify(commandQueue).bolus(argThat { insulin == 3.0 }) }
        assertTrue(m.preBolus1RestStatusText.startsWith("PB1 rest: delivered"))
    }

    @Test
    fun `BG below target but rising is enough`() {
        val m = dinner(minAgo = 20)
        m.onLoopCycle(bg(95.0, 3.0, avgDelta = 2.0), iob(5.0), 12.0)
        assertFalse(m.preBolus1RestPending)
    }

    @Test
    fun `low BG, high IOB or a loop holding insulin keep it back`() {
        val m = dinner(minAgo = 20)
        m.onLoopCycle(bg(85.0, 4.0), iob(5.0), 12.0)
        m.onLoopCycle(bg(120.0, 4.0), iob(9.5), 12.0)
        m.onLoopCycle(bg(120.0, 4.0), iob(5.0), 12.0, loopRestraining = true)
        assertTrue(m.preBolus1RestPending)
        runBlocking { verify(commandQueue, never()).bolus(any()) }
    }

    @Test
    fun `when not all of it fits under max IOB, the part that does is given`() {
        // As at a dinner: 8.3 U on board, max 12, so 3.7 U fits of a 6 U rest.
        val m = dinner(minAgo = 20, restU = 6.0)
        m.onLoopCycle(bg(120.0, 4.0, avgDelta = 3.0), iob(8.3), 12.0)
        runBlocking { verify(commandQueue).bolus(argThat { insulin in 3.7 - 1e-9..3.7 + 1e-9 }) }
        assertFalse(m.preBolus1RestPending)
        // Only what was given stays as the rest; the loop gives the insulin for the food of the other part.
        assertEquals(3.7, m.activeState!!.preBolus1RestU, 1e-9)
        assertEquals(3.7, m.preBolusThisCycleU, 1e-9)
    }

    @Test
    fun `a part too small to be worth a bolus waits`() {
        // A small max IOB: 0.45 U fits, less than is worth a bolus.
        val m = dinner(minAgo = 20)
        m.onLoopCycle(bg(120.0, 4.0, avgDelta = 3.0), iob(1.15), 1.6)
        assertTrue(m.preBolus1RestPending)
        assertTrue(m.preBolus1RestStatusText.contains("IOB would go past max"))
        assertEquals(0.0, m.preBolusThisCycleU, 1e-9)
        runBlocking { verify(commandQueue, never()).bolus(any()) }
    }

    @Test
    fun `the pre-bolus given counts for one cycle only`() {
        val m = dinner(minAgo = 20)
        m.onLoopCycle(bg(118.0, 4.0, avgDelta = 3.0), iob(5.0), 12.0)
        assertEquals(3.0, m.preBolusThisCycleU, 1e-9)
        m.onLoopCycle(bg(120.0, 2.0), iob(8.0), 12.0)
        assertEquals(0.0, m.preBolusThisCycleU, 1e-9)
    }

    @Test
    fun `the rest dose is all of it, or what fits rounded down to a bolus step`() {
        assertEquals(3.0, MealOverrideManagerImpl.restDoseU(3.0, iobU = 5.0, maxIobU = 12.0), 1e-9)
        assertEquals(1.65, MealOverrideManagerImpl.restDoseU(3.0, iobU = 8.33, maxIobU = 10.0), 1e-9)
        assertEquals(0.0, MealOverrideManagerImpl.restDoseU(3.0, iobU = 13.0, maxIobU = 12.0), 1e-9)
    }

    @Test
    fun `after 45 minutes without a rise it is dropped and no longer counts`() {
        val m = dinner(minAgo = 46)
        m.onLoopCycle(bg(120.0, 4.0), iob(5.0), 12.0)
        assertFalse(m.preBolus1RestPending)
        runBlocking { verify(commandQueue, never()).bolus(any()) }
        assertEquals(6.0, m.activeState!!.plannedTotalU, 1e-9)
        assertTrue(m.preBolus1RestStatusText.contains("did not rise"))
    }

    @Test
    fun `it can be cancelled, and is kept over a restart`() {
        val m = dinner(minAgo = 5)
        assertEquals(9.0, m.activeState!!.plannedTotalU, 1e-9)
        m.cancelPreBolus1Rest()
        assertFalse(m.preBolus1RestPending)
        val again = MealOverrideManagerImpl(mock(), sp, commandQueue, mock(), mock(), mock(), CoroutineScope(Dispatchers.Unconfined))
        assertEquals(-1L, again.activeState!!.preBolus1RestFiredMs)
        assertEquals("PB1 rest: cancelled", again.preBolus1RestStatusText)
    }

    @Test
    fun `a meal without a rest shows nothing`() {
        val m = MealOverrideManagerImpl(mock(), FakePreferences(), commandQueue, mock(), mock(), mock(), CoroutineScope(Dispatchers.Unconfined))
        m.activateOverride(MealMode.DINNER, 6.0, 0, modeWindowMs = 60 * 60_000L)
        assertEquals("", m.preBolus1RestStatusText)
        m.setPreBolus1Rest(0.0)
        assertNull(m.activeState!!.preBolus1RestFiredMs)
        assertFalse(m.preBolus1RestPending)
    }
}
