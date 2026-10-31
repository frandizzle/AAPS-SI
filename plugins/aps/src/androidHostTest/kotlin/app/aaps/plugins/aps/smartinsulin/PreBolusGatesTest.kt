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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** The gates pre-bolus 2 fires through: tonight's held 6 U pre-bolus 2, and the IOB limit after the dose. */
class PreBolusGatesTest {

    private val sp = FakePreferences()
    private val commandQueue: CommandQueue = mock()

    /** A Dinner started 70 min ago whose 6 U pre-bolus 2 was due at 60 min. */
    private fun dinner(): MealOverrideManagerImpl {
        val start = System.currentTimeMillis() - 70 * 60_000L
        val json = JSONObject().apply {
            put("mode", MealMode.DINNER.name)
            put("triggerTimeMs", start)
            put("modeExpiryMs", start + 4 * 60 * 60_000L)
            put("doseU", 6.0)
            put("preBolus2U", 6.0)
            put("preBolus2DelayMs", 60 * 60_000L)
        }
        sp.edit { putString(StringNonKey.ApsSmartInsulinOverrideState.key, json.toString()) }
        val ok: PumpEnactResult = mock()
        whenever(ok.success).thenReturn(true)
        runBlocking { whenever(commandQueue.bolus(any())).thenReturn(ok) }
        return MealOverrideManagerImpl(mock(), sp, commandQueue, mock(), mock(), mock(), CoroutineScope(Dispatchers.Unconfined))
    }

    /** BG 9.1 mmol and flat, as tonight. */
    private val flat: GlucoseStatus = mock<GlucoseStatus>().also {
        whenever(it.glucose).thenReturn(164.0)
        whenever(it.delta).thenReturn(0.0)
        whenever(it.shortAvgDelta).thenReturn(-0.2)
    }

    private fun iob(u: Double): Array<IobTotal> = arrayOf(IobTotal(0L).apply { iob = u })

    @Test
    fun `tonight's pre-bolus 2 fires when the loop is not holding insulin back`() {
        val m = dinner()
        m.onLoopCycle(flat, iob(4.14), 12.0, loopRestraining = false)
        assertFalse(m.preBolus2Pending)
        runBlocking { verify(commandQueue).bolus(any()) }
    }

    @Test
    fun `a loop holding insulin back still holds it, and the tab shows why`() {
        val m = dinner()
        m.onLoopCycle(flat, iob(4.14), 12.0, loopRestraining = true)
        assertTrue(m.preBolus2Pending)
        assertTrue(m.pb2GateData!!.loopRestraining)
        assertTrue(m.preBolus2StatusText.contains("Loop restraining insulin"))
    }

    @Test
    fun `a pre-bolus that would take IOB past max waits`() {
        val m = dinner()
        // 6.5 U is under 75 % of 12 U, but 6.5 + 6 = 12.5 U is past max.
        m.onLoopCycle(flat, iob(6.5), 12.0)
        assertTrue(m.preBolus2Pending)
        runBlocking { verify(commandQueue, never()).bolus(any()) }
        assertTrue(m.preBolus2StatusText.contains("IOB would go past max"))
    }
}
