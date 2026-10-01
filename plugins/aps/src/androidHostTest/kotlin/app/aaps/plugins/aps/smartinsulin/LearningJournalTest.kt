package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LearningJournalTest {

    private lateinit var sp: FakePreferences
    private lateinit var journal: LearningJournal

    @BeforeEach fun setUp() {
        sp = FakePreferences()
        journal = LearningJournal(sp, FakeAAPSLogger(collect = false))
    }

    @Test fun `entries come back newest first`() {
        journal.note("Meal ISF", "first", nowMs = 1_000)
        journal.note("DURA", "second", nowMs = 2_000)
        assertEquals(listOf("second", "first"), journal.entries().map { it.text })
    }

    @Test fun `a learner repeating its last verdict is recorded once`() {
        journal.note("Meal ISF", "Lunch: no change", nowMs = 1_000)
        journal.note("Meal ISF", "Lunch: no change", nowMs = 2_000)
        assertEquals(1, journal.entries().size)
    }

    @Test fun `the same text from another learner, or after a different verdict, is recorded`() {
        journal.note("Meal ISF", "no change", nowMs = 1_000)
        journal.note("DURA", "no change", nowMs = 2_000)
        journal.note("Meal ISF", "Lunch ISF ×0.95", nowMs = 3_000)
        journal.note("Meal ISF", "no change", nowMs = 4_000)
        assertEquals(4, journal.entries().size)
    }

    @Test fun `blank text is ignored and returned unchanged`() {
        assertEquals("", journal.note("DURA", ""))
        assertTrue(journal.entries().isEmpty())
    }

    @Test fun `note returns its text so it can wrap an assignment`() {
        assertEquals("Lunch ISF ×0.95", journal.note("Meal ISF", "Lunch ISF ×0.95"))
    }

    @Test fun `only the newest MAX_ENTRIES are kept`() {
        val now = System.currentTimeMillis()
        repeat(LearningJournal.MAX_ENTRIES + 25) { journal.note("Circadian", "change $it", nowMs = now + it) }
        val entries = journal.entries()
        assertEquals(LearningJournal.MAX_ENTRIES, entries.size)
        assertEquals("change ${LearningJournal.MAX_ENTRIES + 24}", entries.first().text)
        assertEquals("change 25", entries.last().text)
    }

    @Test fun `entries older than MAX_AGE are dropped`() {
        val now = System.currentTimeMillis()
        journal.note("Circadian", "old", nowMs = now - LearningJournal.MAX_AGE_MS - 60_000)
        journal.note("Circadian", "recent", nowMs = now)
        assertEquals(listOf("recent"), journal.entries().map { it.text })
    }

    @Test fun `the journal survives a restart`() {
        val now = System.currentTimeMillis()
        journal.note("Meal ISF", "Lunch ISF ×0.95", nowMs = now - 2_000)
        journal.note("Reset", "Aggressiveness score reset to 1.0", nowMs = now - 1_000)
        val restored = LearningJournal(sp, FakeAAPSLogger(collect = false))
        assertEquals(journal.entries(), restored.entries())
    }

    @Test fun `a corrupt saved journal starts empty instead of failing`() {
        sp.putString(app.aaps.core.keys.StringNonKey.ApsSmartInsulinLearningJournal.key, "{not json")
        assertTrue(LearningJournal(sp, FakeAAPSLogger(collect = false)).entries().isEmpty())
    }

    @Test fun `clear empties it, including what is saved`() {
        journal.note("DURA", "something")
        journal.clear()
        assertTrue(LearningJournal(sp, FakeAAPSLogger(collect = false)).entries().isEmpty())
    }

    // ── wiring: a learner's change reaches the journal ───────────────────────

    @Test fun `a learned insulin-profile change is journaled with before and after`() {
        val learner = ProfileLearner(FakeAAPSLogger(collect = false), FakePreferences(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock())
        learner.updateInsulinDefaults(diaMins = 540.0, peakMins = 55.0)
        learner.onChange = { journal.note("Insulin profile", it) }
        learner.observeBolusCurve(MealMode.FASTING, observedPeakMins = 65.0, observedDiaMins = 540.0, learningRate = 0.5)
        // Newest first: the peak change, then the observed-DIA diagnostic that preceded it.
        val (peak, dia) = journal.entries()
        assertEquals("Insulin profile", peak.source)
        assertTrue(peak.text.startsWith("Fasting: peak 55→60 min"), peak.text)
        assertTrue(peak.text.endsWith("(n=1)"), peak.text)
        assertTrue(dia.text.contains("diagnostic only"), dia.text)
    }
}
