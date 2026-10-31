package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.keys.StringNonKey
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * A meal mode set to be followed by Protein/Fat hands over once, and only when it runs out by itself.
 * Food added to a running meal changes only what still waits.
 */
class MealOverrideFollowOnTest {

    private val sp = FakePreferences()
    private val manager = MealOverrideManagerImpl(mock(), sp, mock(), mock(), mock(), mock(), CoroutineScope(Dispatchers.Unconfined))

    /** A mode whose time is already over: a window of -1 ms. */
    private fun startExpired() = manager.activateOverride(MealMode.DINNER, null, 0, modeWindowMs = -1L)

    @Test
    fun `a planned meal hands over to Protein-Fat once when it runs out`() {
        startExpired()
        manager.followWithProteinFat()
        assertTrue(manager.takeProteinFatFollowOn())
        assertFalse(manager.takeProteinFatFollowOn())
    }

    @Test
    fun `a mode not set to follow does not hand over`() {
        startExpired()
        assertFalse(manager.takeProteinFatFollowOn())
    }

    @Test
    fun `a running mode does not hand over yet`() {
        manager.activateOverride(MealMode.DINNER, null, 0, modeWindowMs = 60 * 60_000L)
        manager.followWithProteinFat()
        assertFalse(manager.takeProteinFatFollowOn())
        assertTrue(sp.getString(StringNonKey.ApsSmartInsulinOverrideState.key, "").contains("\"pfAfter\":true"))
    }

    @Test
    fun `ending the mode by hand does not start Protein-Fat`() {
        startExpired()
        manager.followWithProteinFat()
        manager.activeMealMode   // the loop notices the end first
        manager.cancelOverride()
        assertFalse(manager.takeProteinFatFollowOn())
    }

    @Test
    fun `a new mode started meanwhile is not replaced`() {
        startExpired()
        manager.followWithProteinFat()
        manager.activeMealMode
        manager.activateOverride(MealMode.UAM_DINNER, null, 0, modeWindowMs = 60 * 60_000L)
        assertFalse(manager.takeProteinFatFollowOn())
    }

    // --- Add to meal ---

    private fun startDinner(pb2U: Double = 2.0, pb3U: Double = 1.0) =
        manager.activateOverride(MealMode.DINNER, 3.0, 0, modeWindowMs = 60 * 60_000L,
                                 preBolus2U = pb2U, preBolus2DelayMs = 30 * 60_000L, preBolus3U = pb3U, preBolus3DelayMs = 60 * 60_000L)

    @Test
    fun `added food grows the waiting pre-boluses and makes the mode longer`() {
        startDinner()
        val end = System.currentTimeMillis() + 3 * 60 * 60_000L
        assertTrue(manager.addToMeal(2.5, 1.0, 0.5, end, duraEnabled = true, duraFloorMgdl = 30.0, duraStrength = 1.5, pfAfter = true))
        val s = manager.activeState!!
        assertEquals(2.5, s.addedU, 1e-9)
        assertEquals(3.0, s.preBolus2U, 1e-9)
        assertEquals(1.5, s.preBolus3U, 1e-9)
        assertEquals(end, s.modeExpiryMs)
        assertTrue(s.duraEnabled)
        assertEquals(30.0, s.duraFloorMgdl, 1e-9)
        assertTrue(s.pfAfter)
        assertEquals(3.0 + 2.5 + 3.0 + 1.5, s.plannedTotalU, 1e-9)
        assertTrue(sp.getString(StringNonKey.ApsSmartInsulinOverrideState.key, "").contains("\"addedU\":2.5"))
    }

    @Test
    fun `a cancelled pre-bolus is not brought back, and the mode is never made shorter`() {
        startDinner()
        manager.cancelPreBolus2()           // also cancels pre-bolus 3
        val before = manager.activeState!!.modeExpiryMs
        manager.addToMeal(1.0, 1.0, 1.0, System.currentTimeMillis(), duraEnabled = false, duraFloorMgdl = 0.0, duraStrength = 1.0, pfAfter = false)
        val s = manager.activeState!!
        assertEquals(2.0, s.preBolus2U, 1e-9)
        assertEquals(1.0, s.preBolus3U, 1e-9)
        assertEquals(before, s.modeExpiryMs)
        assertFalse(s.pfAfter)
        assertEquals(3.0 + 1.0, s.plannedTotalU, 1e-9)
    }

    @Test
    fun `DURA that is already on keeps its own floor`() {
        manager.activateOverride(MealMode.DINNER, null, 0, modeWindowMs = 60 * 60_000L, duraEnabled = true, duraFloorMgdl = 40.0, duraStrength = 1.0)
        manager.addToMeal(0.0, 0.0, 0.0, 0L, duraEnabled = true, duraFloorMgdl = 20.0, duraStrength = 2.0, pfAfter = false)
        assertEquals(40.0, manager.activeState!!.duraFloorMgdl, 1e-9)
    }

    @Test
    fun `nothing is added when no meal runs`() {
        assertFalse(manager.addToMeal(1.0, 0.0, 0.0, 0L, duraEnabled = false, duraFloorMgdl = 0.0, duraStrength = 1.0, pfAfter = false))
    }
}
