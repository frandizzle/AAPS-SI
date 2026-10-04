package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [BgText]: text people read follows their units. Everything inside is mg/dL, so a mg/dL user
 * must never see "mmol" in a journal entry or status line.
 */
class BgTextTest {

    private val BASE_MS  = 1_700_000_000_000L
    private val CYCLE_MS = 5 * 60_000L

    @AfterEach fun backToMmol() { BgText.mmol = true }

    @Test fun `mmol - one decimal by default, more when asked`() {
        BgText.mmol = true
        assertEquals("9.1mmol", BgText.bg(164.0))
        assertEquals("3mmol", BgText.bg(54.0, 0))
        assertEquals("+1.8mmol", BgText.signed(32.4))
        assertEquals("-0.5mmol", BgText.signed(-9.0))
        assertEquals("mmol", BgText.unit)
    }

    @Test fun `mg_dL - whole numbers`() {
        BgText.mmol = false
        assertEquals("164mg/dL", BgText.bg(164.0))
        assertEquals("54mg/dL", BgText.bg(54.0, 0))
        assertEquals("+32mg/dL", BgText.signed(32.4))
        assertEquals("mg/dL", BgText.unit)
    }

    @Test fun `the low guard follows the same units`() {
        BgText.mmol = false
        // mg/dL guard 86: 85.4 shows as 85, below; in mmol it would be judged on 4.8 instead.
        assertTrue(bgBelowGuard(85.4, 86.0))
        assertFalse(bgBelowGuard(85.5, 86.0))
    }

    @Test fun `meal ISF journal entries are in mg_dL for a mg_dL user`() {
        BgText.mmol = false
        val learner = ModeIsfLearner(FakePreferences(), FakeAAPSLogger(collect = false))
        val run = { mode: MealMode?, start: Long, now: Long, bg: Double ->
            learner.onCycle(activeModeNow = mode, modeStartMs = start, bgMgdl = bg, targetMgdl = 100.0,
                            lowActive = false, duraMult = 1.0, deltaMgdl = 0.0, activityPerMin = 0.0,
                            fastingIsfMgdl = 50.0, carbRatio = 10.0, nowMs = now)
        }
        run(MealMode.DINNER, BASE_MS, BASE_MS, 150.0)
        run(null, 0L, BASE_MS + CYCLE_MS, 130.0)
        assertTrue(learner.lastOutcome.contains("ended 30mg/dL above target"), learner.lastOutcome)
        assertFalse(learner.lastOutcome.contains("mmol"), learner.lastOutcome)
    }

    @Test fun `UAM entry journal entries are in mg_dL for a mg_dL user`() {
        BgText.mmol = false
        val learner = UamEntryFractionLearner(FakePreferences(), FakeAAPSLogger(collect = false))
        val run = { mode: MealMode?, start: Long, now: Long, bg: Double ->
            learner.onCycle(activeModeNow = mode, modeStartMs = start, bgMgdl = bg, targetMgdl = 99.0,
                            lowActive = false, deltaMgdl = 0.0, activityPerMin = 0.0,
                            fastingIsfMgdl = 50.0, carbRatio = 10.0, nowMs = now, baseSignature = 0.8,
                            exerciseSuspected = false, insulinPeakMins = 55.0)
        }
        val peakMs = BASE_MS + 50 * 60_000L
        run(MealMode.UAM_LUNCH, BASE_MS, BASE_MS, 144.0)
        run(MealMode.UAM_LUNCH, BASE_MS, peakMs, 180.0)
        var t = peakMs
        repeat(16) { t += CYCLE_MS; run(null, 0L, t, 105.0) }
        assertTrue(learner.lastOutcome.contains("180mg/dL"), learner.lastOutcome)
        assertFalse(learner.lastOutcome.contains("mmol"), learner.lastOutcome)
    }
}
