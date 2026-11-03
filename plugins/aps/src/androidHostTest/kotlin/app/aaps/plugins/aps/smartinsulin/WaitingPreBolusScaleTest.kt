package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.WaitingInsulin
import app.aaps.core.keys.StringNonKey
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/** The running meal's waiting pre-boluses change with the food when the user changes how much was eaten. */
class WaitingPreBolusScaleTest {

    private val sp = FakePreferences()
    private val commandQueue: CommandQueue = mock()
    private val start = System.currentTimeMillis() - 10 * 60_000L

    /** A Dinner started 10 min ago, with pre-bolus 1 given and the rest of it, pre-bolus 2 and 3 waiting. */
    private fun dinner(extra: JSONObject.() -> Unit = {}): MealOverrideManagerImpl {
        val json = JSONObject().apply {
            put("mode", MealMode.DINNER.name)
            put("triggerTimeMs", start)
            put("modeExpiryMs", start + 6 * 60 * 60_000L)
            put("doseU", 6.0)
            put("preBolus1RestU", 6.0)
            put("preBolus2U", 3.0)
            put("preBolus2DelayMs", 45 * 60_000L)
            put("preBolus3U", 1.0)
            put("preBolus3DelayMs", 60 * 60_000L)
            extra()
        }
        sp.edit { putString(StringNonKey.ApsSmartInsulinOverrideState.key, json.toString()) }
        return MealOverrideManagerImpl(mock(), sp, commandQueue, mock(), mock(), mock(), CoroutineScope(Dispatchers.Unconfined))
    }

    @Test
    fun `three quarters of the meal makes each waiting pre-bolus three quarters`() {
        val m = dinner()
        m.scaleWaitingPreBoluses(carbsFactor = 0.75, fatProteinFactor = 0.75, maxPreBolusU = 6.0)
        val s = m.activeState!!
        assertEquals(4.5, s.preBolus1RestU, 1e-9)
        assertEquals(2.25, s.preBolus2U, 1e-9)
        assertEquals(0.75, s.preBolus3U, 1e-9)
        // Pre-bolus 1 was given and stays.
        assertEquals(6.0, s.doseU!!, 1e-9)
    }

    @Test
    fun `less carbs changes only the rest of pre-bolus 1`() {
        val m = dinner()
        m.scaleWaitingPreBoluses(carbsFactor = 0.5, fatProteinFactor = 1.0, maxPreBolusU = 6.0)
        val s = m.activeState!!
        assertEquals(3.0, s.preBolus1RestU, 1e-9)
        assertEquals(3.0, s.preBolus2U, 1e-9)
        assertEquals(1.0, s.preBolus3U, 1e-9)
    }

    @Test
    fun `amounts round down to a bolus step`() {
        val m = dinner()
        m.scaleWaitingPreBoluses(carbsFactor = 0.33, fatProteinFactor = 0.33, maxPreBolusU = 6.0)
        val s = m.activeState!!
        assertEquals(1.95, s.preBolus1RestU, 1e-9)
        assertEquals(0.95, s.preBolus2U, 1e-9)
        assertEquals(0.3, s.preBolus3U, 1e-9)
    }

    @Test
    fun `a pre-bolus already given does not change`() {
        val m = dinner {
            put("preBolus1RestFiredMs", start + 5 * 60_000L)
            put("preBolus2FiredMs", start + 8 * 60_000L)
        }
        m.scaleWaitingPreBoluses(carbsFactor = 0.5, fatProteinFactor = 0.5, maxPreBolusU = 6.0)
        val s = m.activeState!!
        assertEquals(6.0, s.preBolus1RestU, 1e-9)
        assertEquals(3.0, s.preBolus2U, 1e-9)
        assertEquals(0.5, s.preBolus3U, 1e-9)
    }

    @Test
    fun `more food grows them, but not past the max pre-bolus`() {
        val m = dinner()
        m.scaleWaitingPreBoluses(carbsFactor = 1.5, fatProteinFactor = 1.5, maxPreBolusU = 4.0)
        val s = m.activeState!!
        // Already above the max: it stays, it does not grow.
        assertEquals(6.0, s.preBolus1RestU, 1e-9)
        assertEquals(4.0, s.preBolus2U, 1e-9)
        assertEquals(1.5, s.preBolus3U, 1e-9)
    }

    @Test
    fun `in pieces only what is still to come changes`() {
        val m = dinner {
            put("preBolus3U", 0.0)
            put("preBolus2Pieces", 4)
            put("preBolus2PiecesGiven", 1)
            put("preBolus2GivenU", 0.75)
            put("preBolus2LoopU", 0.25)
        }
        m.scaleWaitingPreBoluses(carbsFactor = 1.0, fatProteinFactor = 0.5, maxPreBolusU = 6.0)
        val s = m.activeState!!
        // 2.0 U was still to come: now 1.0 U, on top of the 1.0 U the pieces and the loop gave.
        assertEquals(2.0, s.preBolus2U, 1e-9)
        assertEquals(1.0, s.preBolus2LeftU, 1e-9)
    }

    @Test
    fun `pieces with nothing left to give are done`() {
        val m = dinner {
            put("preBolus3U", 0.0)
            put("preBolus2Pieces", 4)
            put("preBolus2PiecesGiven", 3)
            put("preBolus2GivenU", 2.9)
        }
        m.scaleWaitingPreBoluses(carbsFactor = 1.0, fatProteinFactor = 0.3, maxPreBolusU = 6.0)
        assertFalse(m.activeState!!.preBolus2Pending)
    }

    @Test
    fun `the forecast counts only the waiting pre-boluses that fit under max IOB`() {
        val s = dinner().activeState!!
        // Room for all: the rest, then pre-bolus 2 and 3.
        assertEquals(WaitingInsulin(restU = 6.0, preBolusU = 4.0), s.waitingThatFits(iobU = 2.0, maxIobU = 12.0))
        // As at a dinner: 8.32 U on board, max 12. Only 3.68 U can still come, all of it the rest.
        val tight = s.waitingThatFits(iobU = 8.32, maxIobU = 12.0)
        assertEquals(3.68, tight.restU, 1e-9)
        assertEquals(0.0, tight.preBolusU, 1e-9)
        // At max IOB nothing waiting can come, so none of its food is taken off.
        assertEquals(WaitingInsulin(0.0, 0.0), s.waitingThatFits(iobU = 12.5, maxIobU = 12.0))
    }

    @Test
    fun `the change is kept over a restart`() {
        dinner().scaleWaitingPreBoluses(carbsFactor = 0.75, fatProteinFactor = 0.75, maxPreBolusU = 6.0)
        val restored = MealOverrideManagerImpl(mock(), sp, commandQueue, mock(), mock(), mock(), CoroutineScope(Dispatchers.Unconfined))
        assertEquals(4.5, restored.activeState!!.preBolus1RestU, 1e-9)
        assertEquals(2.25, restored.activeState!!.preBolus2U, 1e-9)
    }
}
