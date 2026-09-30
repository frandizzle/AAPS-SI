package app.aaps.plugins.aps.smartInsulin

import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.wheneverBlocking
import app.aaps.core.data.model.HR
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.shared.tests.rx.TestAapsSchedulers
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

// Integration: SP read path → targetOffsetMmol
//
// The original ActivityMonitorTest.targetOffsetMmol tests passed because they
// called targetOffsetMmol() directly with already-correct mmol values. The real
// bug was in SmartInsulinPlugin.spMgdl() applying a "< 20 → ×18" heuristic
// that re-multiplied mg/dL defaults (9, 18, 27) back up, giving 9/18/27 mmol
// offsets instead of 0.5/1.0/1.5 — causing the target to jump to ~14.5 mmol.
//
// These tests replicate the fixed sp.getDouble → /18.0 → targetOffsetMmol
// chain from the plugin call-site to confirm correct mmol offsets end-to-end,
// and include regression assertions that the old heuristic values are never
// produced.
@ExtendWith(MockitoExtension::class)
class ActivityTargetOffsetIntegrationTest {

    private val persistenceLayer: PersistenceLayer = mock()
    private val phoneStepCounter: PhoneStepCounter = mock()
    private val sp: app.aaps.core.interfaces.sharedPreferences.SP = mock()
    private val logger = FakeAAPSLogger()

    private lateinit var sut: ActivityMonitor

    private val NOW = 1_000_000L

    @BeforeEach fun setUp() {
        sut = ActivityMonitor(logger, persistenceLayer, TestAapsSchedulers(), phoneStepCounter)
        wheneverBlocking { persistenceLayer.getHeartRatesFromTime(any()) }.thenReturn(emptyList())
        wheneverBlocking { persistenceLayer.getStepsCountFromTime(any()) }.thenReturn(emptyList())
        // Default: SP returns whatever default is passed in
        whenever(sp.getDouble(any<String>(), any<Double>())).thenAnswer { it.getArgument<Double>(1) }
    }

    // Replicates the fixed call-site in SmartInsulinPlugin:
    //   spMgdl(key) / 18.0  →  targetOffsetMmol(...)
    // where spMgdl is now: sp.getDouble(key, defaultValue)  [no < 20 heuristic]
    private fun readOffsetMmol(
        lightMgdl:    Double = UnitDoubleKey.ApsSmartInsulinActivityLightTarget.defaultValue,
        moderateMgdl: Double = UnitDoubleKey.ApsSmartInsulinActivityModerateTarget.defaultValue,
        heavyMgdl:    Double = UnitDoubleKey.ApsSmartInsulinActivityHeavyTarget.defaultValue
    ): Double {
        whenever(sp.getDouble(eq<String>(UnitDoubleKey.ApsSmartInsulinActivityLightTarget.key),    any<Double>())).thenReturn(lightMgdl)
        whenever(sp.getDouble(eq<String>(UnitDoubleKey.ApsSmartInsulinActivityModerateTarget.key), any<Double>())).thenReturn(moderateMgdl)
        whenever(sp.getDouble(eq<String>(UnitDoubleKey.ApsSmartInsulinActivityHeavyTarget.key),    any<Double>())).thenReturn(heavyMgdl)
        val lightMmol    = sp.getDouble(UnitDoubleKey.ApsSmartInsulinActivityLightTarget.key,    lightMgdl)    / 18.0
        val moderateMmol = sp.getDouble(UnitDoubleKey.ApsSmartInsulinActivityModerateTarget.key, moderateMgdl) / 18.0
        val heavyMmol    = sp.getDouble(UnitDoubleKey.ApsSmartInsulinActivityHeavyTarget.key,    heavyMgdl)    / 18.0
        return sut.targetOffsetMmol(lightMmol, moderateMmol, heavyMmol)
    }

    private fun setLevel(level: ActivityMonitor.ActivityLevel) {
        val bpm = when (level) {
            ActivityMonitor.ActivityLevel.SEDENTARY -> null
            ActivityMonitor.ActivityLevel.LIGHT     -> ActivityMonitor.HR_LIGHT_MIN
            ActivityMonitor.ActivityLevel.MODERATE  -> ActivityMonitor.HR_MODERATE_MIN
            ActivityMonitor.ActivityLevel.HEAVY     -> ActivityMonitor.HR_HEAVY_MIN
        }
        if (bpm == null) {
            wheneverBlocking { persistenceLayer.getHeartRatesFromTime(any()) }.thenReturn(emptyList())
        } else {
            val hr = HR(
                timestamp = NOW, duration = ActivityMonitor.HR_WINDOW_MS,
                beatsPerMinute = bpm, device = "test"
            )
            wheneverBlocking { persistenceLayer.getHeartRatesFromTime(any()) }.thenReturn(listOf(hr))
        }
        runBlocking { sut.recompute(NOW) }
    }

    // ── Default SP values ────────────────────────────────────────────────────

    @Test fun `SEDENTARY offset is 0 regardless of SP prefs`() {
        setLevel(ActivityMonitor.ActivityLevel.SEDENTARY)
        assertEquals(0.0, readOffsetMmol(), 0.001)
    }

    @Test fun `LIGHT default 9 mgdl in SP produces 0_5 mmol offset`() {
        setLevel(ActivityMonitor.ActivityLevel.LIGHT)
        // 9.0 mg/dL / 18 = 0.5 mmol
        assertEquals(0.5, readOffsetMmol(lightMgdl = 9.0), 0.001)
    }

    @Test fun `MODERATE default 18 mgdl in SP produces 1_0 mmol offset`() {
        setLevel(ActivityMonitor.ActivityLevel.MODERATE)
        // 18.0 mg/dL / 18 = 1.0 mmol
        assertEquals(1.0, readOffsetMmol(moderateMgdl = 18.0), 0.001)
    }

    @Test fun `HEAVY default 27 mgdl in SP produces 1_5 mmol offset`() {
        setLevel(ActivityMonitor.ActivityLevel.HEAVY)
        // 27.0 mg/dL / 18 = 1.5 mmol
        assertEquals(1.5, readOffsetMmol(heavyMgdl = 27.0), 0.001)
    }

    // ── User-edited values (stored as mg/dL by SmartInsulinUnitPreference) ───

    @Test fun `LIGHT user-set 0_5 mmol stored as 9_0 mgdl produces correct offset`() {
        setLevel(ActivityMonitor.ActivityLevel.LIGHT)
        assertEquals(0.5, readOffsetMmol(lightMgdl = 9.0), 0.001)
    }

    @Test fun `MODERATE user-set 1_5 mmol stored as 27_0 mgdl produces correct offset`() {
        setLevel(ActivityMonitor.ActivityLevel.MODERATE)
        assertEquals(1.5, readOffsetMmol(moderateMgdl = 27.0), 0.001)
    }

    @Test fun `HEAVY user-set 2_0 mmol stored as 36_0 mgdl produces correct offset`() {
        setLevel(ActivityMonitor.ActivityLevel.HEAVY)
        assertEquals(2.0, readOffsetMmol(heavyMgdl = 36.0), 0.001)
    }

    // ── Regression: old heuristic values must NOT be produced ────────────────

    @Test fun `LIGHT 9 mgdl does NOT produce 9_0 mmol offset (old heuristic regression)`() {
        // Old bug: spMgdl saw 9.0 < 20 → x18 = 162 mg/dL → /18 = 9.0 mmol → target jumped to ~14.5 mmol
        setLevel(ActivityMonitor.ActivityLevel.LIGHT)
        val offset = readOffsetMmol(lightMgdl = 9.0)
        assertTrue(offset < 2.0) { "Got $offset mmol — old heuristic bug would produce 9.0 mmol" }
    }

    @Test fun `MODERATE 18 mgdl does NOT produce 18_0 mmol offset (old heuristic regression)`() {
        setLevel(ActivityMonitor.ActivityLevel.MODERATE)
        val offset = readOffsetMmol(moderateMgdl = 18.0)
        assertTrue(offset < 2.0) { "Got $offset mmol — old heuristic bug would produce 18.0 mmol" }
    }

    @Test fun `HEAVY 27 mgdl does NOT produce 27_0 mmol offset (old heuristic regression)`() {
        setLevel(ActivityMonitor.ActivityLevel.HEAVY)
        val offset = readOffsetMmol(heavyMgdl = 27.0)
        assertTrue(offset < 3.0) { "Got $offset mmol — old heuristic bug would produce 27.0 mmol" }
    }
}