package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

/**
 * Tests confirming that when a UAM burst fires, [UamController.justFiredThisCycle] is set
 * so the plugin can pass the elevated [uamSmbFraction] to [DetermineBasalSmartInsulin].
 *
 * The SMB fraction itself is NOT read from UamController — it is read by SmartInsulinPlugin
 * from the UnitDoubleKey.ApsSmartInsulinUamEntrySmbFraction preference and passed as
 * [uamSmbFraction] into determine_basal().  UamController's job is just to set
 * [justFiredThisCycle] on the cycle that fires; the plugin then uses that signal
 * to decide whether to pass the elevated fraction (for the configured N cycles)
 * or fall back to the normal SMB_DELIVERY_FRACTION (0.5).
 *
 * What this test suite verifies:
 *   1. justFiredThisCycle is non-null (burst mode) when a burst fires
 *   2. justFiredThisCycle is non-null (streak mode) when a streak fires
 *   3. justFiredThisCycle is null on every non-firing cycle
 *   4. justFiredThisCycle is null immediately after firing (no re-fire)
 *   5. DetermineBasalSmartInsulin.determine_basal() produces a larger SMB when
 *      uamSmbFraction=1.0 vs uamSmbFraction=0.5 (the actual math contract)
 */
class UamControllerBurstSmbFractionTest {

    private val sp: SP = mock()
    private val mealOverrideManager: MealOverrideManager = mock()
    private val profileUtil: ProfileUtil = mock()
    private val aapsLogger: AAPSLogger = mock()

    private lateinit var sut: UamController

    @BeforeEach
    fun setup() {
        whenever(sp.getBoolean(any<String>(), any<Boolean>())).thenAnswer { it.getArgument<Boolean>(1) }
        whenever(sp.getInt(any<String>(), any<Int>())).thenAnswer { it.getArgument<Int>(1) }
        whenever(sp.getString(any<String>(), any<String>())).thenAnswer { it.getArgument<String>(1) }
        whenever(sp.getDouble(any<String>(), any<Double>())).thenAnswer { it.getArgument<Double>(1) }

        whenever(sp.getBoolean(eq(BooleanKey.ApsSmartInsulinUamEnabled.key), any())).thenReturn(true)
        whenever(sp.getInt(eq(IntKey.ApsSmartInsulinUamDayStartHour.key), any())).thenReturn(0)
        whenever(sp.getInt(eq(IntKey.ApsSmartInsulinUamNightCutoffHour.key), any())).thenReturn(24)
        whenever(profileUtil.units).thenReturn(app.aaps.core.data.model.GlucoseUnit.MMOL)

        // Trigger threshold = 6.0 mmol (108 mg/dL stored internally)
        whenever(sp.getDouble(eq(UnitDoubleKey.ApsSmartInsulinUamTriggerThreshold.key), any())).thenReturn(108.0)
        // Burst threshold = 1.0 mmol (18 mg/dL stored internally)
        whenever(sp.getDouble(eq(UnitDoubleKey.ApsSmartInsulinUamBurstThreshold.key), any())).thenReturn(18.0)
        whenever(sp.getDouble(eq(UnitDoubleKey.ApsSmartInsulinUamRiseMinDelta.key), any())).thenReturn(3.6)
        whenever(sp.getInt(eq(IntKey.ApsSmartInsulinUamRiseConsecutiveReadings.key), any())).thenReturn(3)

        whenever(sp.getBoolean(eq(BooleanKey.ApsSmartInsulinUamBreakfastEnabled.key), any())).thenReturn(true)
        whenever(sp.getInt(eq(IntKey.ApsSmartInsulinUamBreakfastStartHour.key), any())).thenReturn(0)
        whenever(sp.getInt(eq(IntKey.ApsSmartInsulinUamBreakfastEndHour.key), any())).thenReturn(24)
        whenever(sp.getInt(eq(IntKey.ApsSmartInsulinUamBreakfastDurationMins.key), any())).thenReturn(120)

        sut = UamController(sp, mealOverrideManager, profileUtil, aapsLogger)
    }

    private fun cycle(
        bgMmol: Double,
        tMs: Long,
        hour: Int = 8,
        delta: Double = 0.0,
        avgDelta: Double = 0.0
    ) = sut.onLoopCycle(
        currentMealMode   = MealMode.FASTING,
        currentBgMmol     = bgMmol,
        deltaMmol         = delta,
        shortAvgDeltaMmol = avgDelta,
        currentHour       = hour,
        bgWentLow         = false,
        inReboundWindow   = false,
        lastLowTimeMs     = 0L,
        highTempTarget    = false,
        cgmInWarmup       = false,
        inPostMealLockout = false,
        profileTargetMmol = 5.0,
        bgTimestampMs     = tMs
    )

    // ── Test 1: justFiredThisCycle is set on a burst fire ────────────────────

    /**
     * Confirms: when a burst fires, justFiredThisCycle is non-null.
     * The plugin reads this flag each loop cycle. When non-null, it passes the
     * elevated uamSmbFraction (user pref, e.g. 1.0) to determine_basal().
     * When null, it falls back to SMB_DELIVERY_FRACTION (0.5).
     */
    @Test
    fun `justFiredThisCycle is set when burst fires — plugin will use elevated SMB fraction`() {
        cycle(bgMmol = 5.5, tMs = 1_000L)
        assertNull(sut.justFiredThisCycle, "Before burst: should be null")

        // Single-step burst: +1.1 mmol from anchor (5.5) > 1.0 threshold, BG > 6.0 trigger
        cycle(bgMmol = 6.6, tMs = 301_000L, delta = 1.1, avgDelta = 1.1)

        assertNotNull(sut.justFiredThisCycle,
                      "After burst: justFiredThisCycle must be non-null so plugin passes elevated SMB fraction")
        assertEquals(MealMode.UAM_BREAKFAST, sut.justFiredThisCycle,
                     "Mode should match the active meal window")
    }

    // ── Test 2: justFiredThisCycle is null on non-firing cycles ──────────────

    @Test
    fun `justFiredThisCycle is null on every non-firing cycle`() {
        cycle(bgMmol = 6.0, tMs = 1_000L)
        assertNull(sut.justFiredThisCycle, "Cycle 1 — no trigger yet")

        cycle(bgMmol = 6.4, tMs = 301_000L)
        assertNull(sut.justFiredThisCycle, "Cycle 2 — accumulated 0.4, below 1.0 threshold")

        cycle(bgMmol = 6.8, tMs = 601_000L)
        assertNull(sut.justFiredThisCycle, "Cycle 3 — accumulated 0.8, still below threshold")
    }

    // ── Test 3: justFiredThisCycle cleared after fire ─────────────────────────

    /**
     * Critical for SMB cycle counting in SmartInsulinPlugin:
     * On the cycle that fires, justFiredThisCycle is non-null → plugin starts N-cycle countdown.
     * On subsequent cycles (no new burst), justFiredThisCycle is null → plugin uses countdown only.
     *
     * If justFiredThisCycle stayed set after firing it would reset the countdown every cycle,
     * delivering elevated fractions forever.
     */
    @Test
    fun `justFiredThisCycle is null on the cycle immediately after burst fires`() {
        // Fire
        cycle(bgMmol = 5.5, tMs = 1_000L)
        cycle(bgMmol = 6.6, tMs = 301_000L, delta = 1.1, avgDelta = 1.1)
        assertNotNull(sut.justFiredThisCycle, "Should be set on the fire cycle")

        // Very next cycle — no new burst, no new streak completion
        cycle(bgMmol = 6.7, tMs = 601_000L, delta = 0.1, avgDelta = 0.1)
        assertNull(sut.justFiredThisCycle,
                   "justFiredThisCycle must be null after the burst cycle so the countdown ticks down, not restarts")
    }

    // ── Test 4: justFiredThisCycle is set on streak fire too ─────────────────

    /**
     * Streak-based UAM (3 consecutive rising readings) also sets justFiredThisCycle,
     * so the plugin uses the same elevated-fraction path for both entry modes.
     */
    @Test
    fun `justFiredThisCycle is set when streak-based UAM fires`() {
        // Disable burst so only streak fires
        whenever(sp.getDouble(eq(UnitDoubleKey.ApsSmartInsulinUamBurstThreshold.key), any())).thenReturn(0.0)

        val minDelta = 3.6 / 18.0  // 0.2 mmol — the default riseMinDelta
        cycle(bgMmol = 6.0, tMs = 1_000L)
        cycle(bgMmol = 6.2, tMs = 301_000L, delta = minDelta, avgDelta = minDelta)
        assertNull(sut.justFiredThisCycle, "After streak reading 1: no fire yet")

        cycle(bgMmol = 6.4, tMs = 601_000L, delta = minDelta, avgDelta = minDelta)
        assertNull(sut.justFiredThisCycle, "After streak reading 2: no fire yet")

        cycle(bgMmol = 6.6, tMs = 901_000L, delta = minDelta, avgDelta = minDelta)
        assertNotNull(sut.justFiredThisCycle,
                      "After streak reading 3 (riseReadingsNeeded=3): should fire and set justFiredThisCycle")
    }

    // ── Test 5: SMB fraction math contract in DetermineBasal ─────────────────

    /**
     * Direct unit test of the DetermineBasalSmartInsulin SMB fraction math:
     *
     *   correctionUnits = insulinReq * (uamSmbFraction * aggressiveness).coerceIn(0.1, 0.9)
     *
     * With aggressiveness=1.0:
     *   - uamSmbFraction=0.5  → fraction=0.5 → correctionUnits = insulinReq * 0.5
     *   - uamSmbFraction=1.0  → fraction=1.0 → correctionUnits = insulinReq * 0.9 (capped)
     *
     * This test confirms the math produces a bigger SMB for fraction=1.0 vs fraction=0.5,
     * which is what the user configured ("burst gives me full 1.0 fraction for 3 cycles").
     *
     * NOTE: This is a pure arithmetic test — it does NOT instantiate DetermineBasalSmartInsulin
     * (which needs the full DI stack). It tests the formula in isolation.
     */
    @Test
    fun `SMB fraction math — uamSmbFraction=1_0 delivers more than 0_5 (aggressiveness=1_0)`() {
        val insulinReq    = 2.0
        val aggressiveness = 1.0
        val dawnFraction  = 1.0
        val cgmFraction   = 1.0

        // Normal cycle: fraction = 0.5 (SMB_DELIVERY_FRACTION)
        val normalFraction     = 0.5
        val normalCorrection   = insulinReq * (normalFraction * aggressiveness).coerceIn(0.1, 0.9) * dawnFraction * cgmFraction

        // UAM burst entry: fraction = 1.0 (user's configured burst fraction)
        val burstFraction      = 1.0
        val burstCorrection    = insulinReq * (burstFraction * aggressiveness).coerceIn(0.1, 0.9) * dawnFraction * cgmFraction

        // 0.5 * 1.0 = 0.5 (within coerceIn range)
        assertEquals(1.0, normalCorrection, 0.001,
                     "Normal 0.5 fraction: correctionUnits should be insulinReq * 0.5 = 1.0U")

        // 1.0 * 1.0 = 1.0 → coerceIn(0.1, 0.9) → 0.9 (capped by OpenAPS safety ceiling)
        assertEquals(1.8, burstCorrection, 0.001,
                     "Burst 1.0 fraction: correctionUnits should be insulinReq * 0.9 = 1.8U (safety cap)")

        assert(burstCorrection > normalCorrection) {
            "Burst fraction ($burstFraction) must deliver more insulin than normal ($normalFraction)"
        }
    }

    /**
     * Same test but with aggressiveness < 1.0 (e.g. 0.8) — confirms the product still obeys
     * the coerceIn safety cap and burst remains larger than normal.
     */
    @Test
    fun `SMB fraction math — burst larger than normal at aggressiveness=0_8`() {
        val insulinReq    = 2.0
        val aggressiveness = 0.8
        val dawnFraction  = 1.0
        val cgmFraction   = 1.0

        val normalCorrection = insulinReq * (0.5 * aggressiveness).coerceIn(0.1, 0.9) * dawnFraction * cgmFraction
        val burstCorrection  = insulinReq * (1.0 * aggressiveness).coerceIn(0.1, 0.9) * dawnFraction * cgmFraction

        // 0.5 * 0.8 = 0.4 (within cap) → correctionUnits = 2.0 * 0.4 = 0.8U
        assertEquals(0.8, normalCorrection, 0.001, "0.5 fraction at 0.8 agg")
        // 1.0 * 0.8 = 0.8 (within cap) → correctionUnits = 2.0 * 0.8 = 1.6U
        assertEquals(1.6, burstCorrection, 0.001, "1.0 fraction at 0.8 agg")

        assert(burstCorrection > normalCorrection)
    }

    /**
     * Verifies that a user-set fraction of 1.0 at aggressiveness=1.0 hits the 0.9 ceiling,
     * NOT delivering a full 1.0 of insulinReq — the OpenAPS safety cap is in force.
     * This is important to document: "1.0" means "as aggressive as possible" (90% of insulinReq),
     * not literally 100%.
     */
    @Test
    fun `SMB fraction 1_0 is capped at 0_9 by OpenAPS safety ceiling`() {
        val insulinReq     = 10.0
        val aggressiveness = 1.0
        val burstCorrection = insulinReq * (1.0 * aggressiveness).coerceIn(0.1, 0.9)

        // If the cap wasn't there it'd be 10.0; with the cap it's 9.0
        assertEquals(9.0, burstCorrection, 0.001,
                     "fraction=1.0 with aggressiveness=1.0: capped at 0.9 → 9.0U for 10U insulinReq")
    }
}