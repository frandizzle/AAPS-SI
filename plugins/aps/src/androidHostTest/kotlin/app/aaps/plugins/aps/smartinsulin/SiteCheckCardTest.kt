package app.aaps.plugins.aps.smartInsulin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The Site / pod health card: the top line for each state, the live line and the meal list. */
class SiteCheckCardTest {

    private val now = 1_790_100_000_000L

    private fun meal(slow: Boolean = false, noLanding: Boolean = false, startMs: Long = now - 5 * HOUR) = SiteCheck.Episode(
        startMs = startMs, siteChangeMs = POD, podAgeHours = 20.0, mode = "Lunch (UAM)",
        startBgMgdl = 110.0, peakBgMgdl = 180.0, minutesToPeak = 60, risingLoadedMin = if (slow) 105 else 25,
        extraIobAtPeakU = 5.0, fall2hMgdl = if (noLanding) 9.0 else 72.0, slow = slow, noLanding = noLanding,
        suspectsOnPod = 0, notify = false
    )

    private val limits = SiteCheck.Limits(90.0, 120.0, 0.198, 20.0, 20.0, 0.54, 0.18)

    private fun status(
        meals: List<SiteCheck.Episode> = emptyList(), podAgeHours: Double? = 30.0, notified: Boolean = false,
        silenced: Boolean = false, live: SiteCheck.Live? = null, pod: Long? = POD, limits: SiteCheck.Limits? = this.limits
    ) = SiteCheck.Status(
        siteChangeMs = pod, podAgeHours = if (pod == null) null else podAgeHours, meals = meals,
        suspects = meals.count { it.suspect }, notified = notified, silenced = silenced, live = live, totalMeals = 23, totalPods = 4,
        limits = limits, baselineMeals = if (limits == null) 12 else 60, baselinePods = if (limits == null) 1 else 5
    )

    private fun rows(s: SiteCheck.Status) = buildSiteCheckCard(s, isMmol = true, nowMs = now).items
    private fun top(s: SiteCheck.Status) = rows(s).first() as SiItem.Row

    @Test
    fun `normal pod`() {
        val t = top(status(listOf(meal(), meal())))
        assertEquals("Normal", t.primary)
        assertEquals(SiTone.GOOD, t.tone)
        assertTrue(t.detail!!.contains("1 d 6 h old · 2 meals checked, none suspect"), t.detail)
    }

    @Test
    fun `new pod waits`() {
        val t = top(status(podAgeHours = 6.5))
        assertEquals("Waiting", t.primary)
        assertTrue(t.detail!!.contains("6 h old. Meals are checked from 12 h"), t.detail)
    }

    @Test
    fun `no meals yet is not called normal`() {
        assertEquals("No meals checked yet", top(status()).primary)
    }

    @Test
    fun `one suspect meal is watching`() {
        val t = top(status(listOf(meal(), meal(slow = true))))
        assertEquals("Watching", t.primary)
        assertEquals(SiTone.WARNING, t.tone)
    }

    @Test
    fun `after the notification it says check the site, with the button`() {
        val items = rows(status(listOf(meal(slow = true), meal(noLanding = true)), notified = true))
        val t = items.first() as SiItem.Row
        assertEquals("Check the site", t.primary)
        assertEquals(SiTone.CRITICAL, t.tone)
        assertTrue(items.any { it is SiItem.Action && it.action == SiAction.SITE_IS_FINE })
    }

    @Test
    fun `marked fine has no button`() {
        val items = rows(status(listOf(meal(slow = true), meal(slow = true)), notified = true, silenced = true))
        assertEquals("Site marked fine", (items.first() as SiItem.Row).primary)
        assertTrue(items.none { it is SiItem.Action })
    }

    @Test
    fun `no pod change on record`() {
        assertEquals("No pod change recorded", top(status(pod = null)).primary)
    }

    @Test
    fun `the meal list is newest first, with a verdict each`() {
        val rows = rows(status(listOf(meal(startMs = now - 9 * HOUR), meal(slow = true, noLanding = true, startMs = now - 3 * HOUR))))
            .filterIsInstance<SiItem.Row>().drop(1)
        assertTrue(rows[0].primary.endsWith("slow, did not come down"), rows[0].primary)
        assertEquals(SiTone.WARNING, rows[0].tone)
        assertTrue(rows[1].primary.endsWith("normal"), rows[1].primary)
        assertTrue(rows[1].detail!!.contains("Rose 3.9 mmol · still rising 25 min with 5.0 U extra · fell 4.0 mmol in 2 h (0.80 mmol per U)"), rows[1].detail)
    }

    @Test
    fun `the live line shows the rise while it is going on`() {
        val live = SiteCheck.Live("Dinner (UAM)", now - 40 * MIN, 110.0, 160.0, now - 5 * MIN, 160.0, 25, 3.2, risen = true)
        val row = rows(status(live = live)).filterIsInstance<SiItem.Row>().first { it.primary.startsWith("Watching now") }
        assertTrue(row.detail!!.contains("Up 2.8 mmol so far · still rising 25 min with insulin on board (3.2 U extra)"), row.detail)
    }

    @Test
    fun `the live line shows the landing check after the peak`() {
        val live = SiteCheck.Live("Dinner (UAM)", now - 90 * MIN, 110.0, 180.0, now - 30 * MIN, 150.0, 25, 4.0, risen = true)
        val row = rows(status(live = live)).filterIsInstance<SiItem.Row>().first { it.primary.startsWith("Watching now") }
        assertTrue(row.detail!!.startsWith("Peaked at 10.0 at "), row.detail)
        assertTrue(row.detail!!.contains("checking how it comes down until"), row.detail)
    }

    @Test
    fun `while learning it says how far it has got`() {
        val t = top(status(limits = null))
        assertEquals("Learning your normal", t.primary)
        assertTrue(t.detail!!.startsWith("12 of 30 meals from 1 of 3 finished pods"), t.detail)
    }

    @Test
    fun `meals judged while learning are shown as measured`() {
        val e = meal(slow = true).copy(slow = false, learning = true)
        val row = rows(status(listOf(e), limits = null)).filterIsInstance<SiItem.Row>()[1]
        assertTrue(row.primary.endsWith("measured"), row.primary)
        assertEquals(SiTone.MUTED, row.tone)
    }

    @Test
    fun `the note shows the learned limits and the log`() {
        val note = rows(status()).filterIsInstance<SiItem.Note>().single().text
        assertTrue(note.contains("learned from 60 meals on finished pods"), note)
        assertTrue(note.contains("still rising 90 min or more with insulin on board (your normal 20 ± 20 min), asks at once from 120 min"), note)
        assertTrue(note.contains("0.20 mmol per U or less in 2 h (your normal 0.54 mmol per U ± 0.18)"), note)
        assertTrue(note.contains("Log: 23 meals on 4 pods"), note)
    }

    @Test
    fun `the note while learning explains where the limits come from`() {
        val note = rows(status(limits = null)).filterIsInstance<SiItem.Note>().single().text
        assertTrue(note.contains("Limits are learned from your own meals on finished pods"), note)
    }

    @Test
    fun `mg per dL users get mg per dL`() {
        val rows = buildSiteCheckCard(status(listOf(meal())), isMmol = false, nowMs = now).items.filterIsInstance<SiItem.Row>()
        assertTrue(rows[1].detail!!.contains("Rose 70 mg/dL"), rows[1].detail)
    }

    private companion object {

        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val POD = 1_790_000_000_000L
    }
}
