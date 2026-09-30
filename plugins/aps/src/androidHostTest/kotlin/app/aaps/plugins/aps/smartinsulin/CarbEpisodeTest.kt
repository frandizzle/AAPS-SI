package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Entered carbs as an episode: filed under a meal window, closing when COB runs out. */
class CarbEpisodeTest {

    private lateinit var m: CarbEpisodeManager
    private val BASE_MS  = 1_700_000_000_000L
    private val CYCLE_MS = 5 * 60_000L

    @BeforeEach
    fun setUp() { m = CarbEpisodeManager(FakeAAPSLogger(collect = false)) }

    @Test
    fun `carbs open an episode filed under the window they were entered in`() {
        assertNull(m.onCycle(60.0, BASE_MS, MealMode.UAM_LUNCH))
        assertTrue(m.active)
        assertEquals(MealMode.UAM_LUNCH, m.activeMode)
    }

    @Test
    fun `a nibble below the threshold is not an episode`() {
        m.onCycle(3.0, BASE_MS, MealMode.UAM_LUNCH)
        assertFalse(m.active)
    }

    @Test
    fun `the episode closes when COB runs out, and reports itself for the lockout`() {
        m.onCycle(60.0, BASE_MS, MealMode.UAM_LUNCH)
        assertNull(m.onCycle(20.0, BASE_MS + CYCLE_MS, MealMode.UAM_LUNCH))
        val ended = m.onCycle(0.0, BASE_MS + 2 * CYCLE_MS, MealMode.UAM_LUNCH)
        assertEquals(MealMode.UAM_LUNCH, ended)
        assertFalse(m.active)
    }

    @Test
    fun `the window is fixed when the carbs go in, not re-read as the clock moves`() {
        // Eating at the end of the lunch window doesn't become an afternoon episode halfway through.
        m.onCycle(60.0, BASE_MS, MealMode.UAM_LUNCH)
        m.onCycle(40.0, BASE_MS + CYCLE_MS, MealMode.UAM_AFTERNOON)
        assertEquals(MealMode.UAM_LUNCH, m.activeMode)
    }

    @Test
    fun `carbs outside every window still make an episode`() {
        m.onCycle(40.0, BASE_MS, null)
        assertTrue(m.active, "a 2am snack is still carbs on board — UAM must still stand down")
    }

    @Test
    fun `topping up during an episode keeps one episode running`() {
        m.onCycle(30.0, BASE_MS, MealMode.UAM_DINNER)
        val start = m.startMs
        m.onCycle(70.0, BASE_MS + CYCLE_MS, MealMode.UAM_DINNER)   // more carbs entered
        assertEquals(start, m.startMs, "still the same meal")
        assertEquals(70.0, m.peakCobG, 1e-9)
    }

    @Test
    fun `a trace of COB left does not hold the episode open forever`() {
        m.onCycle(60.0, BASE_MS, MealMode.UAM_LUNCH)
        val ended = m.onCycle(0.4, BASE_MS + CYCLE_MS, MealMode.UAM_LUNCH)
        assertEquals(MealMode.UAM_LUNCH, ended)
    }

    @Test
    fun `a new meal later opens a fresh episode`() {
        m.onCycle(60.0, BASE_MS, MealMode.UAM_LUNCH)
        m.onCycle(0.0, BASE_MS + 10 * CYCLE_MS, MealMode.UAM_LUNCH)
        m.onCycle(45.0, BASE_MS + 60 * CYCLE_MS, MealMode.UAM_DINNER)
        assertTrue(m.active)
        assertEquals(MealMode.UAM_DINNER, m.activeMode)
    }
}
