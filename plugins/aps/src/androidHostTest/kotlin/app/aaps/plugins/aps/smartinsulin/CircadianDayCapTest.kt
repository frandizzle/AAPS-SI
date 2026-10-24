package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.keys.StringNonKey
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The weekday cap, the weaker limits, and moving the learning onto a new profile.
 *
 * The cap comes from a real night: Sunday 00-01 h had learned ISF ×1.22 and basal ×0.98 while every
 * other day had about ×0.7 and ×0.57, and the loop dosed into a low.
 */
class CircadianDayCapTest {

    private val sunday = 0
    private val key = StringNonKey.ApsSmartInsulinCircadianState.key

    private fun state(global: Double, day: Double = global, dayOf: Int = sunday) = DayOfWeekCircadianState(
        days = Array(7) { d -> CircadianState(DoubleArray(24) { if (d == dayOf) day else global }, DoubleArray(24) { 1.0 }) },
        global = CircadianState(DoubleArray(24) { global }, DoubleArray(24) { 1.0 })
    )

    private fun learner(isf: DayOfWeekCircadianState, basal: DayOfWeekCircadianState = state(1.0)): Pair<CircadianLearner, FakePreferences> {
        val sp = FakePreferences()
        sp.edit { putString(key, JSONObject().put("isf", isf.toJson()).put("basal", basal.toJson()).put("aggr", state(1.0).toJson()).toString()) }
        return CircadianLearner(FakeAAPSLogger(collect = false), sp) to sp
    }

    @Test
    fun `a weekday is at most 15 percent stronger than all days`() {
        val (l, _) = learner(isf = state(global = 0.75, day = 1.22), basal = state(global = 0.57, day = 0.98))
        assertEquals(0.75 * 1.15, l.isfMultiplier(0, sunday, 30), 1e-9)
        assertEquals(0.57 * 1.15, l.basalMultiplier(0, sunday, 30), 1e-9)
        // Other days are not touched.
        assertEquals(0.75, l.isfMultiplier(0, 1, 30), 1e-9)
    }

    @Test
    fun `a weekday may be weaker than all days by any amount`() {
        val (l, _) = learner(isf = state(global = 0.9, day = 0.6))
        assertEquals(0.6, l.isfMultiplier(0, sunday, 30), 1e-9)
    }

    @Test
    fun `the stored weekday value is pulled back when the learning loads`() {
        val (l, sp) = learner(isf = state(global = 0.75, day = 1.22))
        l.rescaleForProfileChange(DoubleArray(24) { 1.0 }, DoubleArray(24) { 1.0 })   // forces a save
        val saved = DayOfWeekCircadianState.fromJson(JSONObject(sp.getString(key, "")).getJSONObject("isf"))
        assertEquals(0.75 * 1.15, saved.days[sunday].values[0], 1e-9)
        assertEquals(0.75, saved.global.values[0], 1e-9)
    }

    @Test
    fun `ISF can now weaken to 0 5 and basal to 0 3`() {
        val (l, _) = learner(isf = state(global = 0.55), basal = state(global = 0.35))
        assertEquals(0.55, l.isfMultiplier(3, 2, 30), 1e-9)
        assertEquals(0.35, l.basalMultiplier(3, 2, 30), 1e-9)
        val (low, _) = learner(isf = state(global = 0.2), basal = state(global = 0.1))
        assertEquals(0.5, low.isfMultiplier(3, 2, 30), 1e-9)
        assertEquals(0.3, low.basalMultiplier(3, 2, 30), 1e-9)
        assertEquals(24, low.isfHoursAtWeakLimit())
        assertEquals(24, low.basalHoursAtWeakLimit())
        assertEquals(0, l.isfHoursAtWeakLimit())
    }

    @Test
    fun `strengthening is still limited to 1 5`() {
        val (l, _) = learner(isf = state(global = 2.0))
        assertEquals(1.5, l.isfMultiplier(3, 2, 30), 1e-9)
    }

    @Test
    fun `moving onto a new profile keeps the dosing the same`() {
        // Profile ISF 36 mg/dL with ×0.7 doses ISF 51.4. The new profile has ISF 51.4, so ×1.0 now.
        // Profile basal 1.1 with ×0.55 doses 0.605. The new profile has 0.605, so ×1.0 now.
        val (l, _) = learner(isf = state(global = 0.7), basal = state(global = 0.55))
        val oldIsf = 36.0
        val oldBasal = 1.1
        val dosingIsfBefore = oldIsf / l.isfMultiplier(4, 3, 30)
        val dosingBasalBefore = oldBasal * l.basalMultiplier(4, 3, 30)

        val newIsf = 51.4
        val newBasal = 0.605
        l.rescaleForProfileChange(DoubleArray(24) { newIsf / oldIsf }, DoubleArray(24) { newBasal / oldBasal })

        assertEquals(dosingIsfBefore, newIsf / l.isfMultiplier(4, 3, 30), 0.1)
        assertEquals(dosingBasalBefore, newBasal * l.basalMultiplier(4, 3, 30), 1e-3)
        assertEquals(1.0, l.globalIsfMultiplier(4), 0.01)
        assertEquals(1.0, l.globalBasalMultiplier(4), 1e-9)
        // Confidence is kept: the data behind it is the same.
        assertEquals(1.0, l.globalIsfConfidence(4), 1e-9)
    }
}
