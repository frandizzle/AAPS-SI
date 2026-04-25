package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

class UamControllerTest {
    private val preferences: Preferences = mock()
    private val sp: SP = mock()
    private val mealOverrideManager: MealOverrideManager = mock()
    private val profileUtil: ProfileUtil = mock()
    private val aapsLogger: AAPSLogger = mock()

    private lateinit var sut: UamController

    @BeforeEach
    fun setup() {
        whenever(preferences.get(any<BooleanKey>())).thenReturn(false)
        whenever(preferences.get(any<IntKey>())).thenReturn(0)
        whenever(preferences.get(any<StringKey>())).thenReturn("")

        whenever(preferences.get(BooleanKey.ApsSmartInsulinUamEnabled)).thenReturn(true)
        whenever(preferences.get(IntKey.ApsSmartInsulinUamDayStartHour)).thenReturn(0)
        whenever(preferences.get(IntKey.ApsSmartInsulinUamNightCutoffHour)).thenReturn(24)
        
        // Mock units as MMOL for the test
        whenever(profileUtil.units).thenReturn(app.aaps.core.data.model.GlucoseUnit.MMOL)
        
        // Defaults for UAM settings (mg/dL internally for these tests)
        whenever(sp.getDouble(eq(UnitDoubleKey.ApsSmartInsulinUamTriggerThreshold.key), any())).thenReturn(108.0) // 6.0 mmol
        whenever(sp.getDouble(eq(UnitDoubleKey.ApsSmartInsulinUamBurstThreshold.key), any())).thenReturn(18.0)  // 1.0 mmol
        whenever(sp.getDouble(eq(UnitDoubleKey.ApsSmartInsulinUamRiseMinDelta.key), any())).thenReturn(3.6)   // 0.2 mmol
        whenever(preferences.get(IntKey.ApsSmartInsulinUamRiseConsecutiveReadings)).thenReturn(3)
        
        // Enable a window
        whenever(preferences.get(BooleanKey.ApsSmartInsulinUamBreakfastEnabled)).thenReturn(true)
        whenever(preferences.get(IntKey.ApsSmartInsulinUamBreakfastStartHour)).thenReturn(0)
        whenever(preferences.get(IntKey.ApsSmartInsulinUamBreakfastEndHour)).thenReturn(24)
        whenever(preferences.get(IntKey.ApsSmartInsulinUamBreakfastDurationMins)).thenReturn(120)

        sut = UamController(preferences, sp, mealOverrideManager, profileUtil, aapsLogger)
    }

    private fun callOnLoop(bgMmol: Double, tMs: Long, hour: Int = 8, delta: Double = 0.0, avgDelta: Double = 0.0, bgi: Double = 0.0) {
        sut.onLoopCycle(
            currentMealMode    = MealMode.FASTING,
            currentBgMmol      = bgMmol,
            deltaMmol          = delta,
            shortAvgDeltaMmol  = avgDelta,
            bgiMmol            = bgi,
            currentHour        = hour,
            bgWentLow          = false,
            inReboundWindow    = false,
            lastLowTimeMs      = 0L,
            highTempTarget     = false,
            cgmInWarmup        = false,
            inPostMealLockout  = false,
            profileTargetMmol  = 5.0,
            bgTimestampMs      = tMs
        )
    }

    private fun verifyUamFired(mode: MealMode, durationMins: Long) {
        verify(mealOverrideManager).activateOverride(
            mode = eq(mode),
            doseU = isNull(),
            carbsG = eq(0),
            modeWindowMs = eq(durationMins * 60_000L),
            preBolus2U = any(),
            preBolus2DelayMs = any(),
            preBolus3U = any(),
            preBolus3DelayMs = any()
        )
    }

    private fun verifyNoUamFired() {
        verify(mealOverrideManager, never()).activateOverride(any(), anyOrNull(), any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `burst trigger fires when crossing the threshold`() {
        // Cycle 1: BG 5.5 mmol (below 6.0 trigger threshold)
        callOnLoop(5.5, 1000L)
        verifyNoUamFired()

        // Cycle 2: BG 6.6 mmol (+1.1 rise, above 6.0 trigger threshold)
        callOnLoop(6.6, 301000L, delta = 1.1, avgDelta = 1.1)
        
        verifyUamFired(MealMode.UAM_BREAKFAST, 120L)
    }

    @Test
    fun `burst trigger DOES NOT fire if current BG is still below trigger threshold`() {
        // triggerThreshold is 6.0 mmol (108 mg/dL)
        // Cycle 1: BG 4.5 mmol
        callOnLoop(4.5, 1000L)
        
        // Cycle 2: BG 5.6 mmol (+1.1 rise, still below 6.0 trigger threshold)
        callOnLoop(5.6, 301000L, delta = 1.1, avgDelta = 1.1)
        
        verifyNoUamFired()
    }

    @Test
    fun `burst trigger handles mmol users correctly (value saved as mmol)`() {
        // If a mmol user set 1.0, it might be saved as 1.0 in SP.
        whenever(sp.getDouble(eq(UnitDoubleKey.ApsSmartInsulinUamBurstThreshold.key), any())).thenReturn(1.0)
        
        // Cycle 1: BG 7.0 mmol
        callOnLoop(7.0, 1000L)
        
        // Cycle 2: BG 8.1 mmol (+1.1 rise, above 1.0 threshold)
        callOnLoop(8.1, 301000L, delta = 1.1, avgDelta = 1.1)
        
        verifyUamFired(MealMode.UAM_BREAKFAST, 120L)
    }

    @Test
    fun `burst trigger fails if bgTimestampMs is not updated`() {
        // Cycle 1: BG 5.5 mmol
        callOnLoop(5.5, 1000L)

        // Cycle 2: BG 6.6 mmol, but timestamp is still 1000L
        callOnLoop(6.6, 1000L, delta = 1.1, avgDelta = 1.1)
        
        verifyNoUamFired()
    }

    @Test
    fun `burst trigger fails if outside active meal window`() {
        whenever(preferences.get(BooleanKey.ApsSmartInsulinUamBreakfastEnabled)).thenReturn(false)
        
        callOnLoop(5.5, 1000L)
        callOnLoop(6.6, 301000L, delta = 1.1, avgDelta = 1.1)
        
        verifyNoUamFired()
    }
    
    @Test
    fun `burst trigger fails if outside general active window (night cutoff)`() {
        whenever(preferences.get(IntKey.ApsSmartInsulinUamNightCutoffHour)).thenReturn(22)
        whenever(preferences.get(IntKey.ApsSmartInsulinUamDayStartHour)).thenReturn(6)
        
        callOnLoop(5.5, 1000L, hour = 23)
        callOnLoop(6.6, 301000L, hour = 23, delta = 1.1, avgDelta = 1.1)
        
        verifyNoUamFired()
    }

    @Test
    fun `anchor is retained across multiple sub-threshold cycles`() {
        // Threshold 6.0, Burst 1.0
        callOnLoop(4.5, 1000L) // Anchor at 4.5
        callOnLoop(5.0, 301000L) 
        callOnLoop(5.5, 601000L)
        
        // Next cycle: 6.1 mmol (+1.6 from anchor 4.5, +0.6 from last 5.5)
        // It SHOULD trigger because it finally crossed the 6.0 threshold while meeting the burst rise.
        callOnLoop(6.1, 901000L, delta = 0.6, avgDelta = 0.6)
        
        verifyUamFired(MealMode.UAM_BREAKFAST, 120L)
    }

    @Test
    fun `anchor is invalidated after a significant fall`() {
        // Threshold 6.0, Burst 1.0
        callOnLoop(5.5, 1000L) // Anchor 5.5
        callOnLoop(5.0, 301000L) // BG fell, anchor should ideally reset or track the 5.0
        
        // Cycle 3: 6.4 mmol
        // 6.4 - 5.5 is only 0.9 (no trigger if using old anchor)
        // 6.4 - 5.0 is 1.4 (trigger if using new anchor)
        // If it DOES NOT trigger, it means it's respecting the rise from the LOWEST point or the last cycle.
        // Current logic uses (current - burstPrevBgMmol). 
        // 6.4 - 5.0 = 1.4. This SHOULD trigger.
        callOnLoop(6.4, 601000L, delta = 1.4, avgDelta = 1.4)
        
        verifyUamFired(MealMode.UAM_BREAKFAST, 120L)
    }

    @Test
    fun `burst trigger respects freshCycle gate even with aged anchor`() {
        // Cycle 1: BG 5.0 mmol
        callOnLoop(5.0, 1000L)
        
        // Cycle 2: BG 6.5 mmol, but 1 hour later (not a "fresh cycle")
        callOnLoop(6.5, 3601000L, delta = 1.5, avgDelta = 1.5)
        
        verifyNoUamFired()
        
        // Cycle 3: BG 7.6 mmol, 5 mins after Cycle 2 (+1.1 rise)
        // This SHOULD trigger because 7.6 - 6.5 is a fresh rise above 1.0
        callOnLoop(7.6, 3901000L, delta = 1.1, avgDelta = 1.1)
        
        verifyUamFired(MealMode.UAM_BREAKFAST, 120L)
    }
}
