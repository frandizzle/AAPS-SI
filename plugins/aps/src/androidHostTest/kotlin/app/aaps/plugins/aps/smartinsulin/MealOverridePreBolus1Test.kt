package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.ICfg
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/** A pre-bolus 1 the pump did not give all of: the mode keeps what it really gave. */
class MealOverridePreBolus1Test {

    private val sp = FakePreferences()
    private fun manager() = MealOverrideManagerImpl(mock(), sp, mock(), mock(), mock(), mock(), CoroutineScope(Dispatchers.Unconfined))
    private var mgr = manager()

    private fun bolus(timestamp: Long, amount: Double, type: BS.Type = BS.Type.NORMAL, valid: Boolean = true) =
        BS(timestamp = timestamp, amount = amount, type = type, isValid = valid, iCfg = ICfg("insulin", 55, 9 * 60 * 60_000L, 1.0))

    @Test
    fun `a stopped pre-bolus 1 keeps what was given and what was planned, also after a restart`() {
        mgr.activateOverride(MealMode.DINNER, 5.35, 0, modeWindowMs = HOUR)
        mgr.preBolus1Delivered(3.95)
        mgr = manager()
        val s = mgr.activeState!!
        assertEquals(3.95, s.doseU!!, 1e-9)
        assertEquals(5.35, s.preBolus1PlannedU!!, 1e-9)
        assertEquals(3.95, s.plannedTotalU, 1e-9)
        // Read again later: the plan stays the first one.
        mgr.preBolus1Delivered(3.5)
        assertEquals(3.5, mgr.activeState!!.doseU!!, 1e-9)
        assertEquals(5.35, mgr.activeState!!.preBolus1PlannedU!!, 1e-9)
    }

    @Test
    fun `a whole pre-bolus 1, or rounding, changes nothing`() {
        mgr.activateOverride(MealMode.DINNER, 5.35, 0, modeWindowMs = HOUR)
        mgr.preBolus1Delivered(5.35)
        mgr.preBolus1Delivered(5.32)
        assertEquals(5.35, mgr.activeState!!.doseU!!, 1e-9)
        assertNull(mgr.activeState!!.preBolus1PlannedU)
    }

    @Test
    fun `a mode without pre-bolus 1 is not given one`() {
        mgr.activateOverride(MealMode.DINNER, null, 0, modeWindowMs = HOUR)
        mgr.preBolus1Delivered(2.0)
        assertNull(mgr.activeState!!.doseU)
    }

    @Test
    fun `the pre-bolus 1 record is the first normal bolus just after the start`() {
        val t = 1_790_000_000_000L
        val pb1 = bolus(t + 20_000L, 3.95)
        val boluses = listOf(
            bolus(t - 5 * MIN, 1.0),                    // a bolus before the meal
            bolus(t + 5_000L, 0.4, BS.Type.SMB),        // an SMB
            bolus(t + 10_000L, 5.35, valid = false),    // a removed record
            pb1,
            bolus(t + 20 * MIN, 1.2)                     // the rest of pre-bolus 1, later
        )
        assertEquals(pb1, MealOverrideManagerImpl.preBolus1Record(boluses, t))
        assertNull(MealOverrideManagerImpl.preBolus1Record(listOf(bolus(t + 20 * MIN, 1.2)), t))
    }

    private companion object {

        const val MIN = 60_000L
        const val HOUR = 60 * MIN
    }
}
