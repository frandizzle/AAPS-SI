package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Scope of the at-target auto-cancel: which meal modes end themselves when BG comes back to
 * profile target, and which run to their configured duration.
 *
 * The rule is "auto-detected modes cancel, manually-set modes do not". Protein/Fat was the one
 * exception to that — explicitly excluded by name, so it was the only UAM mode that kept an
 * aggressive mode ISF running against an at-target BG until its duration timer expired.
 */
class UamAutoCancelScopeTest {

    private val TARGET = 100.0  // mg/dL

    private fun cancels(mode: MealMode?, bgMgdl: Double) =
        SmartInsulinPlugin.shouldAutoCancelAtTarget(mode, bgMgdl, TARGET)

    // ── Auto-detected (UAM) modes — all cancel at target ──────────────────────

    @Test
    fun `every UAM mode auto-cancels at target`() {
        MealMode.entries.filter { it.isUam }.forEach { mode ->
            assertTrue(cancels(mode, TARGET),
                       "${mode.label} is auto-detected and must cancel once BG is back at target")
        }
    }

    @Test
    fun `protein fat auto-cancels at target like every other UAM mode`() {
        // The regression this file exists for. P/F is isUam, and used to be excluded by name.
        assertTrue(cancels(MealMode.UAM_PROTEIN_FAT, TARGET),
                   "Protein/Fat must not be a special case")
        assertTrue(cancels(MealMode.UAM_PROTEIN_FAT, TARGET - 20.0),
                   "Protein/Fat must cancel below target too")
    }

    @Test
    fun `UAM modes do not cancel while BG is still above target`() {
        MealMode.entries.filter { it.isUam }.forEach { mode ->
            assertFalse(cancels(mode, TARGET + 18.0),
                        "${mode.label} must keep running while BG is a full mmol above target")
        }
    }

    // ── Manually-set meal modes — never cancelled here ────────────────────────

    @Test
    fun `manually set meal modes never auto-cancel at target`() {
        listOf(MealMode.BREAKFAST, MealMode.LUNCH, MealMode.DINNER,
               MealMode.LOW_CARB, MealMode.EXTENDED).forEach { mode ->
            assertFalse(cancels(mode, TARGET),
                        "${mode.label} was set deliberately — it runs its duration, not until target")
            assertFalse(cancels(mode, TARGET - 30.0),
                        "${mode.label} must not auto-cancel even well below target")
        }
    }

    @Test
    fun `fasting and no active mode are never cancelled`() {
        assertFalse(cancels(MealMode.FASTING, TARGET), "Fasting is not an override")
        assertFalse(cancels(null, TARGET), "Nothing active means nothing to cancel")
    }

    // ── Boundary ─────────────────────────────────────────────────────────────

    @Test
    fun `a reading sitting exactly on target counts as reached`() {
        assertTrue(cancels(MealMode.UAM_LUNCH, TARGET),
                   "Exactly at target must count as reached, not as missing by a rounding step")
    }

    @Test
    fun `a reading just above target does not trigger`() {
        assertFalse(cancels(MealMode.UAM_LUNCH, TARGET + 1.0),
                    "1 mg/dL above target is still above target")
    }
}
