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
 * A running P/F must not lock UAM out for its whole window.
 *
 * P/F is auto-detected from a stuck-high pattern and dosed for a slow digestive tail. Eating real
 * carbs on top of it produces a spike that gentle ISF was never sized for, and P/F cannot escalate
 * itself — it only ever fires from FASTING. So a rise inside a configured UAM meal window is
 * allowed to replace it. The sustained-rise path holds a 1.5x delta bar so that P/F's own
 * residual drift can't promote itself to a full meal mode.
 */
class UamPfTakeoverTest {

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
        whenever(sp.getBoolean(eq(BooleanKey.ApsSmartInsulinUamProteinFatEnabled.key), any())).thenReturn(true)
        whenever(sp.getBoolean(eq(BooleanKey.ApsSmartInsulinUamPfTakeover.key), any())).thenReturn(true)
        whenever(sp.getInt(eq(IntKey.ApsSmartInsulinUamDayStartHour.key), any())).thenReturn(0)
        whenever(sp.getInt(eq(IntKey.ApsSmartInsulinUamNightCutoffHour.key), any())).thenReturn(24)
        whenever(profileUtil.units).thenReturn(app.aaps.core.data.model.GlucoseUnit.MMOL)

        // Trigger 6.0 mmol, burst 1.0 mmol, rise min delta 0.2 mmol over 3 readings.
        whenever(sp.getDouble(eq(UnitDoubleKey.ApsSmartInsulinUamTriggerThreshold.key), any())).thenReturn(108.0)
        whenever(sp.getDouble(eq(UnitDoubleKey.ApsSmartInsulinUamBurstThreshold.key), any())).thenReturn(18.0)
        whenever(sp.getDouble(eq(UnitDoubleKey.ApsSmartInsulinUamRiseMinDelta.key), any())).thenReturn(3.6)
        whenever(sp.getInt(eq(IntKey.ApsSmartInsulinUamRiseConsecutiveReadings.key), any())).thenReturn(3)
        // P/F would need only 3 flat readings to fire — makes the "P/F can't re-fire itself" test real.
        whenever(sp.getDouble(eq(UnitDoubleKey.ApsSmartInsulinUamProteinFatThreshold.key), any())).thenReturn(108.0)
        whenever(sp.getInt(eq(IntKey.ApsSmartInsulinUamProteinFatStuckReadings.key), any())).thenReturn(3)

        // One meal window covering the whole day, so the resolved mode is always Breakfast (UAM).
        whenever(sp.getBoolean(eq(BooleanKey.ApsSmartInsulinUamBreakfastEnabled.key), any())).thenReturn(true)
        whenever(sp.getInt(eq(IntKey.ApsSmartInsulinUamBreakfastStartHour.key), any())).thenReturn(0)
        whenever(sp.getInt(eq(IntKey.ApsSmartInsulinUamBreakfastEndHour.key), any())).thenReturn(24)
        whenever(sp.getBoolean(eq(BooleanKey.ApsSmartInsulinUamLunchEnabled.key), any())).thenReturn(false)
        whenever(sp.getBoolean(eq(BooleanKey.ApsSmartInsulinUamDinnerEnabled.key), any())).thenReturn(false)
        whenever(sp.getBoolean(eq(BooleanKey.ApsSmartInsulinUamSnackEnabled.key), any())).thenReturn(false)
        whenever(sp.getBoolean(eq(BooleanKey.ApsSmartInsulinUamAfternoonEnabled.key), any())).thenReturn(false)

        sut = UamController(sp, mealOverrideManager, profileUtil, aapsLogger)
    }

    private fun cycle(
        bgMmol: Double,
        tMs: Long,
        mode: MealMode = MealMode.UAM_PROTEIN_FAT,
        delta: Double = 0.0,
        avgDelta: Double = 0.0
    ) = sut.onLoopCycle(
        currentMealMode   = mode,
        currentBgMmol     = bgMmol,
        deltaMmol         = delta,
        shortAvgDeltaMmol = avgDelta,
        bgiMmol           = 0.0,
        currentHour       = 8,
        bgWentLow         = false,
        inReboundWindow   = false,
        lastLowTimeMs     = 0L,
        highTempTarget    = false,
        cgmInWarmup       = false,
        inPostMealLockout = false,
        profileTargetMmol = 5.0,
        bgTimestampMs     = tMs
    )

    private fun verifyTookOver() =
        verify(mealOverrideManager).activateOverride(
            eq(MealMode.UAM_BREAKFAST), anyOrNull(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        )

    private fun verifyNothingFired() =
        verify(mealOverrideManager, never()).activateOverride(
            any(), anyOrNull(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        )

    // ── Takeover happens ──────────────────────────────────────────────────────

    @Test
    fun `a burst while P over F runs hands the episode to the UAM meal window`() {
        cycle(bgMmol = 7.0, tMs = 1_000L)            // anchor
        cycle(bgMmol = 8.6, tMs = 301_000L)          // +1.6 >= 1.0 -> takeover
        verifyTookOver()
    }

    @Test
    fun `two consecutive half-mmol rises take over — the burst bar is not scaled for P over F`() {
        // The real case: carbs land on top of a running P/F and BG climbs 0.5 a reading. That is a
        // meal by any reading, and under the old 1.5x burst bar it never fired.
        cycle(bgMmol = 7.0, tMs = 1_000L)            // anchor
        cycle(bgMmol = 7.5, tMs = 301_000L)          // +0.5 banked, under the bar
        cycle(bgMmol = 8.0, tMs = 601_000L)          // +1.0 total >= 1.0 -> takeover
        verifyTookOver()
    }

    @Test
    fun `the burst bar over P over F is the same one a fasting burst clears`() {
        cycle(bgMmol = 7.0, tMs = 1_000L)
        cycle(bgMmol = 8.2, tMs = 301_000L)          // +1.2, over the plain 1.0 bar
        verifyTookOver()
    }

    @Test
    fun `a sustained rise while P over F runs hands over at the dirty thresholds`() {
        // riseMinDelta 0.2 x1.5 = 0.3 mmol per reading, 3 readings.
        cycle(bgMmol = 7.0, tMs = 1_000L,   delta = 0.35, avgDelta = 0.35)
        cycle(bgMmol = 7.35, tMs = 301_000L, delta = 0.35, avgDelta = 0.35)
        cycle(bgMmol = 7.7, tMs = 601_000L, delta = 0.35, avgDelta = 0.35)
        verifyTookOver()
    }

    // ── Takeover is held to a higher bar than a fasting rise ──────────────────

    @Test
    fun `a rise short of the burst bar does not promote P over F to a meal mode`() {
        // +0.8 mmol — under the 1.0 bar, and the sustained-rise path needs 3 readings at 0.3.
        cycle(bgMmol = 7.0, tMs = 1_000L)
        cycle(bgMmol = 7.8, tMs = 301_000L)
        verifyNothingFired()
    }

    @Test
    fun `a rise that only clears the plain delta threshold does not take over`() {
        // 0.25 mmol per reading: over the plain 0.2 bar, under the 0.3 takeover bar.
        cycle(bgMmol = 7.0, tMs = 1_000L,   delta = 0.25, avgDelta = 0.25)
        cycle(bgMmol = 7.25, tMs = 301_000L, delta = 0.25, avgDelta = 0.25)
        cycle(bgMmol = 7.5, tMs = 601_000L, delta = 0.25, avgDelta = 0.25)
        verifyNothingFired()
    }

    // ── Scope ─────────────────────────────────────────────────────────────────

    @Test
    fun `takeover disabled leaves P over F to run its window out`() {
        whenever(sp.getBoolean(eq(BooleanKey.ApsSmartInsulinUamPfTakeover.key), any())).thenReturn(false)
        cycle(bgMmol = 7.0, tMs = 1_000L)
        cycle(bgMmol = 9.0, tMs = 301_000L)
        verifyNothingFired()
    }

    @Test
    fun `a UAM entry mode already running is never superseded`() {
        cycle(bgMmol = 7.0, tMs = 1_000L,   mode = MealMode.UAM_LUNCH)
        cycle(bgMmol = 9.0, tMs = 301_000L, mode = MealMode.UAM_LUNCH)
        verifyNothingFired()
    }

    @Test
    fun `a meal the user activated is never superseded`() {
        cycle(bgMmol = 7.0, tMs = 1_000L,   mode = MealMode.DINNER)
        cycle(bgMmol = 9.0, tMs = 301_000L, mode = MealMode.DINNER)
        verifyNothingFired()
    }

    @Test
    fun `P over F does not re-fire itself while it is already running`() {
        // Stuck-high and flat for well past the 3 readings P/F needs from FASTING.
        for (i in 0..5) cycle(bgMmol = 9.0, tMs = 1_000L + i * 300_000L, delta = 0.0, avgDelta = 0.0)
        verifyNothingFired()
    }
}
