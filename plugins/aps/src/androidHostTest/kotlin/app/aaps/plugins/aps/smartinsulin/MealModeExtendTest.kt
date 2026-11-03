package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.keys.StringNonKey
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/** A meal mode made longer while its own food still comes in, so UAM does not start a new meal on its late rise. */
class MealModeExtendTest {

    private val sp = FakePreferences()
    private val commandQueue: CommandQueue = mock()
    private val now = System.currentTimeMillis()
    private val min = 60_000L

    /** A meal started [minAgo] minutes ago that ends [endsInMin] minutes from now, restored from the settings. */
    private fun meal(minAgo: Int, endsInMin: Int, mode: MealMode = MealMode.LUNCH, extendedMin: Int = 0): MealOverrideManagerImpl {
        val json = JSONObject().apply {
            put("mode", mode.name)
            put("triggerTimeMs", now - minAgo * min)
            put("modeExpiryMs", now + endsInMin * min)
            put("extendedMs", extendedMin * min)
        }
        sp.edit { putString(StringNonKey.ApsSmartInsulinOverrideState.key, json.toString()) }
        return MealOverrideManagerImpl(mock(), sp, commandQueue, mock(), mock(), mock(), CoroutineScope(Dispatchers.Unconfined))
    }

    private val rising = 3.6
    private val flat = 0.0

    @Test
    fun `a mode about to end runs on while BG rises`() {
        val m = meal(minAgo = 295, endsInMin = 4)
        assertEquals(MealOverrideManager.Extension.BG_RISING, m.extendWhileFoodComes(now, rising, foodLeftG = 2.0))
        assertEquals(now + 15 * min, m.activeState!!.modeExpiryMs)
        assertEquals(11 * min, m.activeState!!.extendedMs)
    }

    @Test
    fun `and while its food list has food to come`() {
        val m = meal(minAgo = 295, endsInMin = 4)
        assertEquals(MealOverrideManager.Extension.FOOD_LEFT, m.extendWhileFoodComes(now, flat, foodLeftG = 8.0))
        assertEquals(now + 15 * min, m.activeState!!.modeExpiryMs)
    }

    @Test
    fun `it ends as set once BG is flat and the food is in`() {
        val m = meal(minAgo = 295, endsInMin = 4)
        assertNull(m.extendWhileFoodComes(now, flat, foodLeftG = 2.0))
        assertNull(m.extendWhileFoodComes(now, flat, foodLeftG = null))
        assertEquals(now + 4 * min, m.activeState!!.modeExpiryMs)
    }

    @Test
    fun `nothing changes while the end is still far off`() {
        val m = meal(minAgo = 60, endsInMin = 30)
        assertNull(m.extendWhileFoodComes(now, rising, foodLeftG = 20.0))
        assertEquals(now + 30 * min, m.activeState!!.modeExpiryMs)
    }

    @Test
    fun `at most 90 minutes more in all`() {
        val m = meal(minAgo = 200, endsInMin = 5, extendedMin = 80)
        assertEquals(MealOverrideManager.Extension.BG_RISING, m.extendWhileFoodComes(now, rising, foodLeftG = null))
        assertEquals(now + 15 * min, m.activeState!!.modeExpiryMs)
        assertEquals(90 * min, m.activeState!!.extendedMs)
        // At the limit it is not made longer again.
        assertNull(m.extendWhileFoodComes(now + 10 * min, rising, foodLeftG = null))
        assertEquals(now + 15 * min, m.activeState!!.modeExpiryMs)
    }

    @Test
    fun `never longer than 7 hours from the start`() {
        val m = meal(minAgo = 410, endsInMin = 5)
        m.extendWhileFoodComes(now, rising, foodLeftG = null)
        assertEquals(now + 10 * min, m.activeState!!.modeExpiryMs)
        assertNull(meal(minAgo = 415, endsInMin = 5).extendWhileFoodComes(now, rising, foodLeftG = null))
    }

    @Test
    fun `a UAM mode is not made longer`() {
        val m = meal(minAgo = 100, endsInMin = 4, mode = MealMode.entries.first { it.isUam })
        assertNull(m.extendWhileFoodComes(now, rising, foodLeftG = 20.0))
    }

    @Test
    fun `the longer end is kept over a restart`() {
        meal(minAgo = 295, endsInMin = 4).extendWhileFoodComes(now, rising, foodLeftG = null)
        val restored = MealOverrideManagerImpl(mock(), sp, commandQueue, mock(), mock(), mock(), CoroutineScope(Dispatchers.Unconfined))
        assertEquals(now + 15 * min, restored.activeState!!.modeExpiryMs)
        assertEquals(11 * min, restored.activeState!!.extendedMs)
    }
}
