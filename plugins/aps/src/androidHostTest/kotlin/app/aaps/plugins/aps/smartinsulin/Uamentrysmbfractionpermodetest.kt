package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.keys.DoubleKey
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

/**
 * Confirms that [SmartInsulinPlugin.entrySmbFractionForMode] reads the correct
 * per-mode SP key for each UAM meal mode, and falls back to the global default
 * for non-UAM modes and UAM_PROTEIN_FAT.
 *
 * Each mode gets a distinct stub value so we can prove the right key was read,
 * not just that *some* double came back.
 *
 * Mode → expected fraction:
 *   UAM_BREAKFAST   → 1.0   (si_uam_entry_smb_fraction_breakfast)
 *   UAM_LUNCH       → 0.9   (si_uam_entry_smb_fraction_lunch)
 *   UAM_DINNER      → 0.85  (si_uam_entry_smb_fraction_dinner)
 *   UAM_SNACK       → 0.7   (si_uam_entry_smb_fraction_snack)
 *   UAM_AFTERNOON   → 0.75  (si_uam_entry_smb_fraction_afternoon)
 *   UAM_PROTEIN_FAT → 0.5   (falls back to global si_uam_entry_smb_fraction)
 *   FASTING         → 0.5   (falls back to global si_uam_entry_smb_fraction)
 */
class UamEntrySmbFractionPerModeTest {

    private val sp: SP = mock()

    // Minimal stub — only entrySmbFractionForMode() is called, which only touches SP.
    // All other constructor args are null/mock stubs that are never invoked.
    private lateinit var sut: SmartInsulinPlugin

    @BeforeEach
    fun setup() {
        // Default: return the registered default value for any unmatched key
        whenever(sp.getDouble(any<String>(), any<Double>())).thenAnswer { it.getArgument<Double>(1) }

        // Stub each per-mode key to a distinct, recognisable value
        whenever(sp.getDouble(eq(DoubleKey.ApsSmartInsulinUamEntrySmbFractionBreakfast.key), any())).thenReturn(1.0)
        whenever(sp.getDouble(eq(DoubleKey.ApsSmartInsulinUamEntrySmbFractionLunch.key),     any())).thenReturn(0.9)
        whenever(sp.getDouble(eq(DoubleKey.ApsSmartInsulinUamEntrySmbFractionDinner.key),    any())).thenReturn(0.85)
        whenever(sp.getDouble(eq(DoubleKey.ApsSmartInsulinUamEntrySmbFractionSnack.key),     any())).thenReturn(0.7)
        whenever(sp.getDouble(eq(DoubleKey.ApsSmartInsulinUamEntrySmbFractionAfternoon.key), any())).thenReturn(0.75)
        // Global fallback key
        whenever(sp.getDouble(eq(DoubleKey.ApsSmartInsulinUamEntrySmbFraction.key),          any())).thenReturn(0.5)

        sut = SmartInsulinPlugin(
            aapsLogger            = mock(),
            rh                    = mock(),
            rxBus                 = mock(),
            config                = mock(),
            profileFunction       = mock(),
            profileUtil           = mock(),
            iobCobCalculator      = mock(),
            mealOverrideManager   = mock(),
            glucoseStatusProvider = mock(),
            glucoseStatusCalculatorSMB = mock(),
            persistenceLayer      = mock(),
            processedTbrEbData    = mock(),
            hardLimits            = mock(),
            sp                    = sp,
            constraintsChecker    = mock(),
            activePlugin          = mock(),
            dateUtil              = mock(),
            determineBasalSmartInsulin = mock(),
            stftController        = mock(),
            uamController         = mock(),
            profileLearner        = mock(),
            bolusCurveTracker     = mock(),
            aggressionLearner     = mock(),
            basalLearner          = mock(),
            circadianLearner      = mock(),
            activityMonitor       = mock(),
            cgmWarmupGuard        = mock(),
            duraIsfTracker        = mock(),
            mealAbsorptionTracker = mock(),
            mealAbsorptionCsvLogger = mock(),
            modeIsfLearner        = mock(),
            uamEntryFractionLearner = mock(),
            unexplainedDropTracker  = mock(),
            duraStrengthLearner     = mock(),
            secondWaveDetector      = mock(),
            carbEpisodeManager      = mock(),
            activitySessionManager  = mock(),
            activitySessionLearner  = mock(),
            phoneStepCounter        = mock(),
            preferences             = mock(),
            notificationManager     = mock(),
            ch                      = mock()
        )
    }

    // ── Per-mode dispatch ─────────────────────────────────────────────────────

    @Test
    fun `UAM_BREAKFAST reads breakfast key`() {
        assertEquals(1.0, sut.entrySmbFractionForMode(MealMode.UAM_BREAKFAST), 0.001)
        verify(sp).getDouble(eq(DoubleKey.ApsSmartInsulinUamEntrySmbFractionBreakfast.key), any())
    }

    @Test
    fun `UAM_LUNCH reads lunch key`() {
        assertEquals(0.9, sut.entrySmbFractionForMode(MealMode.UAM_LUNCH), 0.001)
        verify(sp).getDouble(eq(DoubleKey.ApsSmartInsulinUamEntrySmbFractionLunch.key), any())
    }

    @Test
    fun `UAM_DINNER reads dinner key`() {
        assertEquals(0.85, sut.entrySmbFractionForMode(MealMode.UAM_DINNER), 0.001)
        verify(sp).getDouble(eq(DoubleKey.ApsSmartInsulinUamEntrySmbFractionDinner.key), any())
    }

    @Test
    fun `UAM_SNACK reads snack key`() {
        assertEquals(0.7, sut.entrySmbFractionForMode(MealMode.UAM_SNACK), 0.001)
        verify(sp).getDouble(eq(DoubleKey.ApsSmartInsulinUamEntrySmbFractionSnack.key), any())
    }

    @Test
    fun `UAM_AFTERNOON reads afternoon key`() {
        assertEquals(0.75, sut.entrySmbFractionForMode(MealMode.UAM_AFTERNOON), 0.001)
        verify(sp).getDouble(eq(DoubleKey.ApsSmartInsulinUamEntrySmbFractionAfternoon.key), any())
    }

    // ── Fallback modes ────────────────────────────────────────────────────────

    @Test
    fun `UAM_PROTEIN_FAT falls back to global key`() {
        // P/F is excluded from the entry-fraction window (it's a tail correction),
        // so it should read the global fallback key, not a per-mode key.
        assertEquals(0.5, sut.entrySmbFractionForMode(MealMode.UAM_PROTEIN_FAT), 0.001)
        verify(sp).getDouble(eq(DoubleKey.ApsSmartInsulinUamEntrySmbFraction.key), any())
        verify(sp, never()).getDouble(eq(DoubleKey.ApsSmartInsulinUamEntrySmbFractionBreakfast.key), any())
        verify(sp, never()).getDouble(eq(DoubleKey.ApsSmartInsulinUamEntrySmbFractionLunch.key),     any())
        verify(sp, never()).getDouble(eq(DoubleKey.ApsSmartInsulinUamEntrySmbFractionDinner.key),    any())
        verify(sp, never()).getDouble(eq(DoubleKey.ApsSmartInsulinUamEntrySmbFractionSnack.key),     any())
        verify(sp, never()).getDouble(eq(DoubleKey.ApsSmartInsulinUamEntrySmbFractionAfternoon.key), any())
    }

    @Test
    fun `FASTING falls back to global key`() {
        assertEquals(0.5, sut.entrySmbFractionForMode(MealMode.FASTING), 0.001)
        verify(sp).getDouble(eq(DoubleKey.ApsSmartInsulinUamEntrySmbFraction.key), any())
    }

    // ── No cross-contamination ────────────────────────────────────────────────

    /**
     * Reads all 5 UAM modes in sequence and confirms each returned a distinct value —
     * proving no two modes share the same key.
     */
    @Test
    fun `each UAM mode returns a distinct fraction — no shared keys`() {
        val results = listOf(
            MealMode.UAM_BREAKFAST to sut.entrySmbFractionForMode(MealMode.UAM_BREAKFAST),
            MealMode.UAM_LUNCH     to sut.entrySmbFractionForMode(MealMode.UAM_LUNCH),
            MealMode.UAM_DINNER    to sut.entrySmbFractionForMode(MealMode.UAM_DINNER),
            MealMode.UAM_SNACK     to sut.entrySmbFractionForMode(MealMode.UAM_SNACK),
            MealMode.UAM_AFTERNOON to sut.entrySmbFractionForMode(MealMode.UAM_AFTERNOON),
        )
        val values = results.map { it.second }
        assertEquals(values.size, values.toSet().size,
                     "Each UAM mode must resolve to a unique fraction value — duplicate suggests shared key: $results")
    }

    // ── Default value passthrough ─────────────────────────────────────────────

    /**
     * Confirms that the default value from DoubleKey is passed as the SP fallback,
     * so a fresh install with no saved prefs gets the registered default (0.8), not 0.0.
     */
    @Test
    fun `default value from DoubleKey is passed as SP fallback for each mode`() {
        // Unstub all keys so the thenAnswer default kicks in (returns argument[1] = the default)
        whenever(sp.getDouble(any<String>(), any<Double>())).thenAnswer { it.getArgument<Double>(1) }

        listOf(
            MealMode.UAM_BREAKFAST,
            MealMode.UAM_LUNCH,
            MealMode.UAM_DINNER,
            MealMode.UAM_SNACK,
            MealMode.UAM_AFTERNOON,
        ).forEach { mode ->
            assertEquals(0.8, sut.entrySmbFractionForMode(mode), 0.001,
                         "Mode $mode: default value should be 0.8 (DoubleKey default) when SP has no saved value")
        }
    }

    /**
     * 4.0's settings screen draws one level of groups inside a plugin; a group nested in a group
     * renders as a header that never expands. Keep every SI group flat.
     */
    @Test
    fun `settings groups are never nested inside another group`() {
        val nested = sut.getPreferenceScreenContent().items
            .filterIsInstance<PreferenceSubScreenDef>()
            .filter { group -> group.items.any { it is PreferenceSubScreenDef } }
            .map { it.key }
        assertEquals(emptyList<String>(), nested)
    }
}
