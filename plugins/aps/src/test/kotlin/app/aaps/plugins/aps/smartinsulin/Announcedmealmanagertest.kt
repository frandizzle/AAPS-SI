package app.aaps.plugins.aps.smartInsulin.ice

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for [AnnouncedMealManager], focused on auto-clear, lifecycle, and persistence.
 *
 * The most important assertion is the **negative-age regression test**:
 * a meal queried with a timestamp *older* than its announce time must NOT be
 * cleared. The loop calls [AnnouncedMealManager.expectedIceMgdlPerHour] with
 * `glucoseStatus.date` (the last CGM reading), which can routinely be a few
 * minutes before a freshly-announced meal. Earlier auto-clear logic incorrectly
 * treated this as "expired" and wiped the meal on the very next loop cycle.
 */
class AnnouncedMealManagerTest {

    private val logger: AAPSLogger = mock()
    private val sp: SP = mock()
    private val dateUtil: DateUtil = mock()
    private lateinit var manager: AnnouncedMealManager
    private val baseTime = 1_700_000_000_000L  // arbitrary fixed clock

    /** In-memory store backing the SP mock so tests can exercise the persistence path. */
    private val spStore = mutableMapOf<String, String>()

    @BeforeEach
    fun setup() {
        spStore.clear()
        // Wire mock SP through the in-memory store. Every any() needs an explicit type
        // parameter — Kotlin can't infer T across overloaded SP methods otherwise.
        whenever(sp.getString(any<String>(), any<String>())).doAnswer { inv ->
            spStore[inv.arguments[0] as String] ?: (inv.arguments[1] as String)
        }
        whenever(sp.putString(any<String>(), any<String>())).doAnswer { inv ->
            spStore[inv.arguments[0] as String] = inv.arguments[1] as String
            Unit
        }
        whenever(sp.remove(any<String>())).doAnswer { inv ->
            spStore.remove(inv.arguments[0] as String)
            Unit
        }
        whenever(dateUtil.now()).thenReturn(baseTime)
        manager = AnnouncedMealManager(logger, sp, dateUtil)
    }

    private fun meal(
        carbs: Double = 30.0,
        protein: Double = 30.0,
        fat: Double = 30.0,
        announceAt: Long = baseTime,
        bucket: GiBucket = GiBucket.MEDIUM
    ) = AnnouncedMeal(
        carbsG              = carbs,
        proteinG            = protein,
        fatG                = fat,
        giBucket            = bucket,
        commitmentPct       = 100,
        announceTimestampMs = announceAt
    )

    // ── Negative-age regression — the bug Shantelle saw ─────────────────────

    @Test
    @DisplayName("queries before announce time must NOT auto-clear the meal")
    fun `expectedIceMgdlPerHour does not clear when queried with older timestamp`() {
        val m = meal(announceAt = baseTime)
        manager.announceMeal(m)
        // Loop scenario: glucoseStatus.date is 2 min OLDER than the meal announcement
        // (meal was announced between CGM readings)
        val olderQueryTime = baseTime - 2 * 60_000L
        val rate = manager.expectedIceMgdlPerHour(olderQueryTime)
        // Rate should be 0 (meal hasn't started yet in this query's frame)
        assertEquals(0.0, rate, 1e-9)
        // But the meal must still be present for the next loop cycle to use
        assertNotNull(manager.activeMeal.value, "Negative-age query must not clear the meal")
        assertEquals(m, manager.activeMeal.value)
    }

    @Test
    @DisplayName("multiple negative-age queries in a row still preserve the meal")
    fun `repeated negative-age queries do not clear`() {
        val m = meal(announceAt = baseTime)
        manager.announceMeal(m)
        for (offsetMin in 1..5) {
            manager.expectedIceMgdlPerHour(baseTime - offsetMin * 60_000L)
            assertNotNull(manager.activeMeal.value, "Cleared after $offsetMin min negative-age query")
        }
    }

    // ── Genuine expiry — auto-clear is still correct ────────────────────────

    @Test
    @DisplayName("meals past their absorption window are auto-cleared")
    fun `expired meals auto-clear`() {
        // 30g/30g/30g → fatProteinDurationMin = 300 (5h), MEDIUM carb dur 180 → eff = 300
        val m = meal(announceAt = baseTime)
        manager.announceMeal(m)
        assertEquals(300, m.effectiveTotalDurationMin)
        // Query at 6h post-announce — past the 5h window
        val queryPastExpiry = baseTime + (300 + 60) * 60_000L
        val rate = manager.expectedIceMgdlPerHour(queryPastExpiry)
        assertEquals(0.0, rate, 1e-9)
        assertNull(manager.activeMeal.value, "Genuinely expired meal must auto-clear")
    }

    @Test
    @DisplayName("at-window-end is still active — does not clear at the exact boundary")
    fun `at-boundary does not auto-clear`() {
        val m = meal(announceAt = baseTime)
        manager.announceMeal(m)
        // Query exactly at the end of the window (300 min exact)
        val queryAtEnd = baseTime + 300 * 60_000L
        manager.expectedIceMgdlPerHour(queryAtEnd)
        // Should still be active at exactly the boundary
        assertNotNull(manager.activeMeal.value, "Meal at exact window boundary must not be cleared")
    }

    // ── In-range queries — return positive expected ICE ─────────────────────

    @Test
    @DisplayName("queries inside the active window return positive expected ICE")
    fun `in-range queries return non-zero rate`() {
        val m = meal(announceAt = baseTime)
        manager.announceMeal(m)
        // Sample at 90 min (well within both carb window and plateau)
        val queryMid = baseTime + 90 * 60_000L
        val rate = manager.expectedIceMgdlPerHour(queryMid)
        assertTrue(rate > 0.0, "Mid-window query should produce positive ICE, got $rate")
        assertNotNull(manager.activeMeal.value, "Mid-window query must not clear")
    }

    // ── Lifecycle: announce / edit / clear ──────────────────────────────────

    @Test
    @DisplayName("announceMeal replaces any previous meal")
    fun `announce replaces previous meal`() {
        val first = meal(carbs = 30.0, announceAt = baseTime)
        manager.announceMeal(first)
        val second = meal(carbs = 60.0, announceAt = baseTime + 60_000L)
        manager.announceMeal(second)
        assertEquals(60.0, manager.activeMeal.value?.carbsG)
        assertEquals(baseTime + 60_000L, manager.activeMeal.value?.announceTimestampMs)
    }

    @Test
    @DisplayName("editActiveMeal preserves announceTimestampMs")
    fun `edit preserves the announce timestamp`() {
        val m = meal(carbs = 30.0, announceAt = baseTime)
        manager.announceMeal(m)
        manager.editActiveMeal(carbsG = 70.0, proteinG = 30.0, fatG = 30.0, giBucketName = "MEDIUM")
        assertEquals(70.0, manager.activeMeal.value?.carbsG)
        assertEquals(baseTime, manager.activeMeal.value?.announceTimestampMs,
                     "edit must preserve the original announce timestamp")
    }

    @Test
    @DisplayName("editActiveMeal with no active meal is a no-op")
    fun `edit with nothing announced is a no-op`() {
        assertNull(manager.activeMeal.value)
        manager.editActiveMeal(carbsG = 30.0, proteinG = 0.0, fatG = 0.0, giBucketName = "MEDIUM")
        assertNull(manager.activeMeal.value, "Edit with no active meal must not create one")
    }

    @Test
    @DisplayName("clearMeal wipes the active meal")
    fun `clearMeal wipes the active meal`() {
        manager.announceMeal(meal(announceAt = baseTime))
        manager.clearMeal()
        assertNull(manager.activeMeal.value)
    }

    // ── Remaining macros & overview suffix ─────────────────────────────────

    @Test
    @DisplayName("macrosOverviewSuffix returns empty string when no meal")
    fun `macros suffix empty without meal`() {
        assertEquals("", manager.macrosOverviewSuffix(baseTime))
    }

    @Test
    @DisplayName("macrosOverviewSuffix shows decreasing grams over time")
    fun `macros suffix decreases over time`() {
        val m = meal(carbs = 60.0, protein = 60.0, fat = 60.0, announceAt = baseTime)
        manager.announceMeal(m)
        val early = manager.macrosOverviewSuffix(baseTime + 30 * 60_000L)
        val late  = manager.macrosOverviewSuffix(baseTime + 200 * 60_000L)
        assertTrue(early.isNotEmpty(), "Early suffix should be present: '$early'")
        assertTrue(late.isNotEmpty(), "Late suffix should be present: '$late'")
        // Both should contain COB / P / F markers
        assertTrue(early.contains("COB") || early.contains("P ") || early.contains("F "))
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("announcing a meal writes to SharedPreferences")
    fun `announce persists to SP`() {
        manager.announceMeal(meal(announceAt = baseTime))
        assertTrue(spStore.isNotEmpty(), "SP should have an entry after announce")
    }

    @Test
    @DisplayName("clearing a meal removes the SP entry")
    fun `clear removes SP entry`() {
        manager.announceMeal(meal(announceAt = baseTime))
        assertTrue(spStore.isNotEmpty())
        manager.clearMeal()
        assertTrue(spStore.isEmpty(), "SP entry should be removed after clearMeal")
    }

    @Test
    @DisplayName("editing a meal persists the new values")
    fun `edit persists changes`() {
        manager.announceMeal(meal(carbs = 30.0, announceAt = baseTime))
        manager.editActiveMeal(carbsG = 80.0, proteinG = 40.0, fatG = 40.0, giBucketName = "SLOW")
        // Construct a fresh manager — it must restore the EDITED values
        val restored = AnnouncedMealManager(logger, sp, dateUtil)
        val active = restored.activeMeal.value
        assertNotNull(active)
        assertEquals(80.0, active!!.carbsG)
        assertEquals(40.0, active.proteinG)
        assertEquals(GiBucket.SLOW, active.giBucket)
        assertEquals(baseTime, active.announceTimestampMs, "edit must preserve announce timestamp through reboot")
    }

    @Test
    @DisplayName("meal survives a simulated reboot")
    fun `meal survives reboot`() {
        manager.announceMeal(meal(carbs = 50.0, protein = 30.0, fat = 20.0, announceAt = baseTime))
        // Construct a fresh manager (simulating reboot — new process, same SP)
        val restored = AnnouncedMealManager(logger, sp, dateUtil)
        val active = restored.activeMeal.value
        assertNotNull(active, "Meal should be restored from SP after a fresh manager is constructed")
        assertEquals(50.0, active!!.carbsG)
        assertEquals(30.0, active.proteinG)
        assertEquals(20.0, active.fatG)
        assertEquals(GiBucket.MEDIUM, active.giBucket)
        assertEquals(baseTime, active.announceTimestampMs)
    }

    @Test
    @DisplayName("expired meals are discarded on restore — no resurrection after long reboot")
    fun `expired meal is discarded on restore`() {
        // Announce at baseTime
        manager.announceMeal(meal(carbs = 30.0, announceAt = baseTime))
        // Simulate a fresh manager 8 hours later — the meal's 5h window is long past
        whenever(dateUtil.now()).thenReturn(baseTime + 8 * 60 * 60 * 1000L)
        val restored = AnnouncedMealManager(logger, sp, dateUtil)
        assertNull(restored.activeMeal.value, "Expired meal must not be restored")
        assertTrue(spStore.isEmpty(), "SP entry must be cleared for expired meal")
    }

    @Test
    @DisplayName("corrupt SP entry is silently ignored")
    fun `corrupt SP ignored`() {
        spStore["smartinsulin_active_announced_meal"] = "garbage|nonsense|format"
        val restored = AnnouncedMealManager(logger, sp, dateUtil)
        assertNull(restored.activeMeal.value)
        assertTrue(spStore.isEmpty(), "Corrupt SP entry must be removed")
    }

    @Test
    @DisplayName("unknown version prefix is discarded")
    fun `unknown version discarded`() {
        spStore["smartinsulin_active_announced_meal"] =
            "v99|30.0|30.0|30.0|MEDIUM|100|$baseTime"
        val restored = AnnouncedMealManager(logger, sp, dateUtil)
        assertNull(restored.activeMeal.value, "Future-version SP entry must be discarded")
    }

    @Test
    @DisplayName("auto-clear on expiry also wipes SP")
    fun `auto-clear on expiry wipes SP`() {
        manager.announceMeal(meal(carbs = 30.0, announceAt = baseTime))
        assertTrue(spStore.isNotEmpty())
        // Query well past the window — should auto-clear and wipe SP
        val pastExpiry = baseTime + 6 * 60 * 60 * 1000L
        manager.expectedIceMgdlPerHour(pastExpiry)
        assertNull(manager.activeMeal.value)
        assertTrue(spStore.isEmpty(), "SP entry must be wiped on auto-clear")
    }
}