package app.aaps.plugins.aps.smartInsulin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The top of the Circadian 24h card: profile against learned, and the apply and undo buttons. */
class LearnedProfileCardTest {

    private val plan = LearnedProfilePlan.build(
        (0 until 24).map { h -> LearnedProfilePlan.Hour(h, 36.0, 1.1, 36.0 / 0.7, 1.1 * 0.55, if (h < 4) 0.3 else 1.0, 1.0) },
        isfStepMgdl = 1.8, basalStep = 0.05, basalMin = 0.05
    )

    private fun status(previous: String? = null, applied: String? = null, pending: Boolean = false, atLimit: Int = 0) =
        SmartInsulinPlugin.LearnedProfileStatus(plan, atLimit, atLimit, applied, previous, 0L, pending, autoOn = true)

    private fun rows(s: SmartInsulinPlugin.LearnedProfileStatus) = buildLearnedProfileCard(s, isMmol = true).items

    @Test
    fun `profile against learned`() {
        val r = rows(status()).filterIsInstance<SiItem.Row>()
        assertEquals("ISF: profile 2.00 → learned 2.75 mmol/U", r[0].primary)
        assertTrue(r[0].detail!!.contains("20 of 24 hours have enough data"), r[0].detail)
        assertEquals(SiTone.INFO, r[0].tone)
        assertEquals("Basal: profile 26.4 → learned 14.4 U a day", r[1].primary)
    }

    @Test
    fun `learning at its limit is called out`() {
        assertTrue(rows(status(atLimit = 5)).filterIsInstance<SiItem.Row>().any { it.primary == "Learning is at its limit" })
        assertTrue(rows(status()).filterIsInstance<SiItem.Row>().none { it.primary == "Learning is at its limit" })
    }

    @Test
    fun `undo only after an apply`() {
        assertEquals(listOf(SiAction.APPLY_LEARNED_PROFILE), rows(status()).filterIsInstance<SiItem.Action>().map { it.action })
        val after = rows(status(previous = "Main", applied = "SI learned 25 Oct"))
        assertEquals(listOf(SiAction.APPLY_LEARNED_PROFILE, SiAction.UNDO_LEARNED_PROFILE), after.filterIsInstance<SiItem.Action>().map { it.action })
        assertTrue(after.filterIsInstance<SiItem.Row>().any { it.primary == "In use: SI learned 25 Oct" })
    }

    @Test
    fun `waiting for the pump`() {
        assertTrue(rows(status(pending = true, applied = "SI learned 25 Oct")).filterIsInstance<SiItem.Row>().any { it.primary == "Waiting for the pump" })
    }

    @Test
    fun `the preview lists every block`() {
        val text = learnedProfilePreview(plan, isMmol = true)
        assertTrue(text.contains("00–04 h  2.00"), text)
        assertTrue(text.contains("04–24 h  2.90"), text)
        assertTrue(text.contains("00–24 h  0.60"), text)
    }
}
