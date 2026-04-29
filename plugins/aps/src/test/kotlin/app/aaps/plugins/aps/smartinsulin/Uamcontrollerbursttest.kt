package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

/**
 * Burst-calculation focused tests for [UamController].
 *
 * These tests focus on the *accumulation math* and edge cases:
 *
 *  1. Burst accumulates correctly across 3+ readings (total = sum of deltas)
 *  2. Burst does NOT fire when total rise is enough but BG is still below trigger threshold
 *  3. Burst does NOT re-fire immediately after firing (state is cleared)
 *  4. Burst resets anchor on BG drop, then re-accumulates from new low
 *  5. Burst threshold of exactly 0 disables burst entirely
 *  6. Burst accumulates during below-threshold readings but only fires once above
 */
class UamControllerBurstTest {

    private val sp: SP = mock()
    private val mealOverrideManager: MealOverrideManager = mock()
    private val profileUtil: ProfileUtil = mock()
    private val aapsLogger: AAPSLogger = mock()

    private lateinit var sut: UamController

    // Burst threshold = 1.0 mmol, trigger threshold = 6.0 mmol
    @BeforeEach
    fun setup() {
        // Mock default SP behavior to return the provided default values
        whenever(sp.getBoolean(any<String>(), any<Boolean>())).thenAnswer { invocation -> invocation.getArgument<Boolean>(1) }
        whenever(sp.getInt(any<String>(), any<Int>())).thenAnswer { invocation -> invocation.getArgument<Int>(1) }
        whenever(sp.getString(any<String>(), any<String>())).thenAnswer { invocation -> invocation.getArgument<String>(1) }
        whenever(sp.getDouble(any<String>(), any<Double>())).thenAnswer { invocation -> invocation.getArgument<Double>(1) }

        whenever(sp.getBoolean(eq(BooleanKey.ApsSmartInsulinUamEnabled.key), any<Boolean>())).thenReturn(true)
        whenever(sp.getInt(eq(IntKey.ApsSmartInsulinUamDayStartHour.key), any())).thenReturn(0)
        whenever(sp.getInt(eq(IntKey.ApsSmartInsulinUamNightCutoffHour.key), any())).thenReturn(24)
        whenever(profileUtil.units).thenReturn(app.aaps.core.data.model.GlucoseUnit.MMOL)

        // Trigger threshold = 6.0 mmol (108 mg/dL)
        whenever(sp.getDouble(eq(UnitDoubleKey.ApsSmartInsulinUamTriggerThreshold.key), any())).thenReturn(108.0)
        // Burst threshold = 1.0 mmol (18 mg/dL internal)
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
        bgiMmol           = 0.0,
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

    private fun verifyFired(times: Int = 1) =
        verify(mealOverrideManager, times(times)).activateOverride(any(), anyOrNull(), any(), any(), any(), any(), any(), any())

    private fun verifyNotFired() =
        verify(mealOverrideManager, never()).activateOverride(any(), anyOrNull(), any(), any(), any(), any(), any(), any())

    // ── Test 1: 3-reading accumulation ───────────────────────────────────────

    @Test
    fun `burst fires after 3-reading accumulation when total exceeds threshold`() {
        // Each step is +0.4 mmol — below burst threshold (1.0) individually.
        // After 3 steps: total rise = 1.2 mmol > 1.0 threshold → should fire.
        // All readings above trigger threshold (6.0 mmol).
        cycle(bgMmol = 6.0, tMs = 1_000L)           // anchor seeds
        cycle(bgMmol = 6.4, tMs = 301_000L)          // rise +0.4, total = 0.4 — no fire
        cycle(bgMmol = 6.8, tMs = 601_000L)          // rise +0.4, total = 0.8 — no fire
        cycle(bgMmol = 7.2, tMs = 901_000L)          // rise +0.4, total = 1.2 ≥ 1.0 → FIRE

        verifyFired()
    }

    @Test
    fun `burst fires in single reading when rise exceeds threshold in one step`() {
        cycle(bgMmol = 5.5, tMs = 1_000L)   // anchor at 5.5
        cycle(bgMmol = 6.6, tMs = 301_000L, delta = 1.1, avgDelta = 1.1)  // +1.1 ≥ 1.0 → FIRE
        verifyFired()
    }

    @Test
    fun `burst does not fire on second step when total is still below threshold`() {
        cycle(bgMmol = 6.0, tMs = 1_000L)
        cycle(bgMmol = 6.4, tMs = 301_000L)   // total = 0.4 — not enough
        cycle(bgMmol = 6.8, tMs = 601_000L)   // total = 0.8 — still not enough

        verifyNotFired()
    }

    // ── Test 2: Burst blocked when BG below trigger threshold ─────────────────

    @Test
    fun `burst does not fire when total rise is large but BG is still below trigger threshold`() {
        // BG rises from 4.5 to 5.8 mmol — total rise = 1.3 mmol > 1.0 threshold
        // BUT current BG (5.8) is still below trigger threshold (6.0)
        // Burst accumulates but should NOT fire until BG crosses trigger threshold
        cycle(bgMmol = 4.5, tMs = 1_000L)
        cycle(bgMmol = 5.0, tMs = 301_000L)
        cycle(bgMmol = 5.5, tMs = 601_000L)
        cycle(bgMmol = 5.8, tMs = 901_000L)   // rise = 1.3 but BG < 6.0 trigger → NO fire

        verifyNotFired()
    }

    @Test
    fun `burst fires on the exact cycle BG crosses trigger threshold with sufficient accumulated rise`() {
        // BG rises from 4.5, accumulating >1.0 mmol total rise.
        // Should only fire when BG finally crosses 6.0 mmol.
        cycle(bgMmol = 4.5, tMs = 1_000L)     // anchor
        cycle(bgMmol = 5.0, tMs = 301_000L)   // +0.5, total 0.5, below trigger
        cycle(bgMmol = 5.5, tMs = 601_000L)   // +0.5, total 1.0, but BG below trigger — no fire
        cycle(bgMmol = 6.1, tMs = 901_000L)   // +0.6, total 1.6 ≥ 1.0, BG > 6.0 trigger → FIRE

        verifyFired()
    }

    // ── Test 3: No immediate re-fire after burst ───────────────────────────────

    @Test
    fun `burst does not re-fire immediately on the next reading after firing`() {
        // Fire burst
        cycle(bgMmol = 5.5, tMs = 1_000L)
        cycle(bgMmol = 6.6, tMs = 301_000L, delta = 1.1, avgDelta = 1.1)
        verifyFired(times = 1)

        // Next reading — state should be cleared, no re-fire
        cycle(bgMmol = 6.8, tMs = 601_000L, delta = 0.2, avgDelta = 0.2)
        // Still only 1 total call — no second fire
        verifyFired(times = 1)
    }

    @Test
    fun `burst requires fresh accumulation after firing before it can fire again`() {
        // First burst
        cycle(bgMmol = 5.5, tMs = 1_000L)
        cycle(bgMmol = 6.6, tMs = 301_000L, delta = 1.1, avgDelta = 1.1)
        verifyFired(times = 1)

        // After burst clears, BG needs to accumulate another 1.0 mmol rise to fire again.
        // Rising 0.4 mmol — not enough for a second burst.
        cycle(bgMmol = 6.7, tMs = 601_000L)
        cycle(bgMmol = 6.9, tMs = 901_000L)
        cycle(bgMmol = 7.0, tMs = 1_201_000L)

        // Still only the original 1 fire — total accumulated since reset is only 0.4 mmol
        verifyFired(times = 1)
    }

    // ── Test 4: Anchor reset on drop then re-accumulation ────────────────────

    @Test
    fun `burst re-anchors from new low after BG drops and accumulates fresh rise`() {
        // Initial rise — not enough to fire
        cycle(bgMmol = 6.0, tMs = 1_000L)
        cycle(bgMmol = 6.3, tMs = 301_000L)   // +0.3, not enough

        // BG drops — anchor resets to this lower value
        cycle(bgMmol = 6.0, tMs = 601_000L)   // drop → anchor resets to 6.0

        // Fresh rise from new anchor — 1.1 mmol rise from 6.0
        cycle(bgMmol = 7.1, tMs = 901_000L, delta = 1.1, avgDelta = 1.1)

        verifyFired()
    }

    // ── Test 5: Burst threshold of 0 disables burst ──────────────────────────

    @Test
    fun `burst does not fire when burst threshold is zero (disabled)`() {
        whenever(sp.getDouble(eq(UnitDoubleKey.ApsSmartInsulinUamBurstThreshold.key), any())).thenReturn(0.0)

        cycle(bgMmol = 5.5, tMs = 1_000L)
        cycle(bgMmol = 7.0, tMs = 301_000L, delta = 1.5, avgDelta = 1.5)

        verifyNotFired()
    }

    // ── Test 6: Accumulation during below-threshold readings ─────────────────

    @Test
    fun `burst accumulates rise across readings that include below-threshold BG`() {
        // BG starts low, rises steadily. Accumulation starts before trigger threshold.
        // Burst fires once BG crosses trigger AND total rise >= burst threshold.
        cycle(bgMmol = 4.0, tMs = 1_000L)     // anchor at 4.0
        cycle(bgMmol = 4.8, tMs = 301_000L)   // +0.8, below trigger — accumulating
        cycle(bgMmol = 5.6, tMs = 601_000L)   // +0.8, total 1.6, still below trigger
        // Total accumulated = 1.6 mmol > 1.0 threshold.
        // BG = 5.6 is STILL below trigger (6.0) — should NOT fire yet.
        verifyNotFired()

        // One more reading crosses trigger threshold
        cycle(bgMmol = 6.2, tMs = 901_000L)   // total 2.2, BG > 6.0 → FIRE
        verifyFired()
    }
}
