package app.aaps.plugins.aps.smartInsulin

import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Golf and gym: declared no-food windows whose shape is learned — resistant early, then the
 * hormones clear and the late low lands. Target 110 mg/dL (~6.1 mmol) throughout.
 */
class ActivitySessionTest {

    private lateinit var sp: FakePreferences
    private lateinit var learner: ActivitySessionLearner
    private lateinit var manager: ActivitySessionManager

    private val BASE_MS  = 1_700_000_000_000L
    private val CYCLE_MS = 5 * 60_000L
    private val TARGET   = 110.0

    @BeforeEach
    fun setUp() {
        sp = FakePreferences()
        learner = ActivitySessionLearner(sp, FakeAAPSLogger(collect = false))
        manager = ActivitySessionManager(sp, FakeAAPSLogger(collect = false), learner)
    }

    private fun mins(m: Int) = BASE_MS + m * 60_000L

    /**
     * A whole round: [bgAt] gives BG by minute, a low at [lowAtMin] if set, stopped at [stopMin],
     * then the hour of tail watch runs so the verdict lands.
     */
    private fun round(
        label: SessionLabel = SessionLabel.GOLF, plannedMins: Int = 240, stopMin: Int = 240,
        bgAt: (Int) -> Double = { TARGET }, lowAtMin: Int? = null, snackAtMin: Int? = null
    ) {
        manager.start(label, BASE_MS, plannedMins)
        var m = 0
        while (m <= stopMin) {
            if (m == snackAtMin) manager.toggleSnack(mins(m))
            manager.onCycle(mins(m), bgAt(m), TARGET, lowActive = (lowAtMin != null && m >= lowAtMin))
            m += 5
        }
        manager.stop(mins(stopMin))
        var t = stopMin
        while (t <= stopMin + 65) {
            t += 5
            manager.onCycle(mins(t), bgAt(minOf(t, stopMin)), TARGET,
                            lowActive = (lowAtMin != null && t >= lowAtMin))
        }
    }

    // ── The session itself ───────────────────────────────────────────────────

    @Test
    fun `starting and stopping a session`() {
        assertNull(manager.active)
        manager.start(SessionLabel.GOLF, BASE_MS, 240)
        assertEquals(SessionLabel.GOLF, manager.active?.label)
        assertNotNull(manager.stop(mins(200)))
        assertNull(manager.active)
    }

    @Test
    fun `a session survives a restart`() {
        manager.start(SessionLabel.GOLF, BASE_MS, 240)
        val restored = ActivitySessionManager(sp, FakeAAPSLogger(collect = false), learner)
        assertEquals(SessionLabel.GOLF, restored.active?.label)
        assertEquals(240 * 60_000L, restored.active?.plannedMs)
    }

    @Test
    fun `a session left running is dropped once it is well past its planned end`() {
        manager.start(SessionLabel.GOLF, BASE_MS, 240)
        manager.onCycle(mins(300), TARGET, TARGET, lowActive = false)   // 1h over, inside the grace
        assertNotNull(manager.active)
        manager.onCycle(mins(340), TARGET, TARGET, lowActive = false)   // past the 90min grace
        assertNull(manager.active)
    }

    // ── The dosing curve ─────────────────────────────────────────────────────

    @Test
    fun `the resistant phase doses the learned multiplier at full insulin`() {
        val s = ActivitySession(SessionLabel.GOLF, BASE_MS, 240 * 60_000L)
        val d = sessionDosing(s, mins(60), isfMult = 0.9, washoutMins = 60)
        assertEquals(0.9, d.isfMultiplier, 1e-9)
        assertEquals(1.0, d.insulinFraction, 1e-9)
        assertFalse(d.inWashout)
    }

    @Test
    fun `the washout tapers insulin and lets the resistance correction go`() {
        val s = ActivitySession(SessionLabel.GOLF, BASE_MS, 240 * 60_000L)
        // Washout 60min before a 240min end → starts at 180min, half way through at 210min.
        val half = sessionDosing(s, mins(210), isfMult = 0.9, washoutMins = 60)
        assertTrue(half.inWashout)
        assertEquals(1.0 - 0.7 * 0.5, half.insulinFraction, 1e-9)
        assertEquals(0.95, half.isfMultiplier, 1e-9)   // 0.9 relaxing back toward 1.0

        val end = sessionDosing(s, mins(240), isfMult = 0.9, washoutMins = 60)
        assertEquals(WASHOUT_FLOOR_FRACTION, end.insulinFraction, 1e-9)
        assertEquals(1.0, end.isfMultiplier, 1e-9)
    }

    @Test
    fun `an overrun holds at the floor rather than falling off a cliff`() {
        val s = ActivitySession(SessionLabel.GOLF, BASE_MS, 240 * 60_000L)
        val over = sessionDosing(s, mins(300), isfMult = 0.9, washoutMins = 60)
        assertEquals(WASHOUT_FLOOR_FRACTION, over.insulinFraction, 1e-9)
    }

    @Test
    fun `no session means nothing is touched`() {
        val d = sessionDosing(null, mins(60), isfMult = 0.8, washoutMins = 60)
        assertEquals(1.0, d.isfMultiplier, 1e-9)
        assertEquals(1.0, d.insulinFraction, 1e-9)
    }

    // ── What each round teaches it ───────────────────────────────────────────

    @Test
    fun `a round that ran high with no low strengthens the resistant phase`() {
        round(bgAt = { TARGET + 40.0 })   // ~2.2mmol over target throughout
        assertEquals(0.97, learner.isfMultiplier(SessionLabel.GOLF), 1e-9)
        assertEquals(ActivitySessionLearner.DEFAULT_WASHOUT_MINS, learner.washoutMins(SessionLabel.GOLF))
        assertTrue(learner.lastOutcome.contains("strengthened"), learner.lastOutcome)
    }

    @Test
    fun `a round that tracked target changes nothing`() {
        round(bgAt = { TARGET + 5.0 })
        assertEquals(1.0, learner.isfMultiplier(SessionLabel.GOLF), 1e-9)
        assertTrue(learner.lastOutcome.contains("no change"), learner.lastOutcome)
    }

    @Test
    fun `the late low moves the washout earlier and eases the resistant phase`() {
        // The described pattern: high early, low at the 3-hour mark of a 4-hour round.
        round(bgAt = { m -> if (m < 170) TARGET + 40.0 else 70.0 }, lowAtMin = 180)
        // Washout now starts 240 − 180 + 45 = 105min before the end.
        assertEquals(105, learner.washoutMins(SessionLabel.GOLF))
        assertEquals(1.06, learner.isfMultiplier(SessionLabel.GOLF), 1e-9)
        assertTrue(learner.lastOutcome.contains("washout moved"), learner.lastOutcome)
    }

    @Test
    fun `a low always wins over a high earlier in the same round`() {
        round(bgAt = { m -> if (m < 170) TARGET + 60.0 else 70.0 }, lowAtMin = 180)
        assertTrue(learner.isfMultiplier(SessionLabel.GOLF) > 1.0,
                   "ran high early, but the low is what the round is judged on")
    }

    @Test
    fun `a low after the round still belongs to it, and sets a later washout`() {
        // Finished at 240, low on the drive home at 265 — inside the hour of tail watch, so it is
        // still this round's low. It landed late, so the backing-off should start late too:
        // 45min before the low is 220min in, which is 20min before the planned end.
        round(stopMin = 240, bgAt = { TARGET + 20.0 }, lowAtMin = 265)
        assertEquals(20, learner.washoutMins(SessionLabel.GOLF))
        assertTrue(learner.lastOutcome.contains("low 265min in"), learner.lastOutcome)
    }

    @Test
    fun `the washout never eats more than half the session`() {
        // A low very early would otherwise push the washout back past the start.
        round(bgAt = { 70.0 }, lowAtMin = 30)
        assertTrue(learner.washoutMins(SessionLabel.GOLF) <= 120,
                   "washout was ${learner.washoutMins(SessionLabel.GOLF)}min of a 240min round")
    }

    @Test
    fun `golf and gym learn separately`() {
        round(label = SessionLabel.GOLF, bgAt = { TARGET + 40.0 })
        assertEquals(0.97, learner.isfMultiplier(SessionLabel.GOLF), 1e-9)
        assertEquals(1.0, learner.isfMultiplier(SessionLabel.GYM), 1e-9)
    }

    @Test
    fun `learned shape survives a restart`() {
        round(bgAt = { TARGET + 40.0 })
        val restored = ActivitySessionLearner(sp, FakeAAPSLogger(collect = false))
        assertEquals(0.97, restored.isfMultiplier(SessionLabel.GOLF), 1e-9)
        assertEquals(1, restored.sessionCount(SessionLabel.GOLF))
    }

    @Test
    fun `repeated high rounds converge without running away`() {
        repeat(20) { round(bgAt = { TARGET + 60.0 }) }
        assertTrue(learner.isfMultiplier(SessionLabel.GOLF) >= 0.7,
                   "railed at the floor, not walked past it")
    }


    @Test
    fun `a round teed off high and coming down is not charged for the altitude`() {
        // 9mmol at the first tee after breakfast, gliding to 6.5 through the resistant phase.
        round(bgAt = { m -> (162.0 - m * 0.2).coerceAtLeast(117.0) })
        assertEquals(1.0, learner.isfMultiplier(SessionLabel.GOLF), 1e-9)
        assertTrue(learner.lastOutcome.contains("inherited high"), learner.lastOutcome)
    }

    @Test
    fun `a round that starts high and stalls there IS charged`() {
        round(bgAt = { 160.0 })
        assertEquals(0.97, learner.isfMultiplier(SessionLabel.GOLF), 1e-9)
    }

    @Test
    fun `a round that starts on target and climbs is charged`() {
        round(bgAt = { m -> (TARGET + m * 0.3).coerceAtMost(170.0) })
        assertEquals(0.97, learner.isfMultiplier(SessionLabel.GOLF), 1e-9)
    }

    // ── Had a snack: its own learned path ────────────────────────────────────

    private val HIGH = TARGET + 40.0

    @Test
    fun `a snack round that runs high strengthens golf plus snack, not plain golf`() {
        round(bgAt = { HIGH }, snackAtMin = 30)
        assertEquals(1.0, learner.isfMultiplier(SessionLabel.GOLF), 1e-9)
        assertEquals(0.97, learner.isfMultiplier(SessionLabel.GOLF, snack = true), 1e-9)
        assertTrue(learner.lastOutcome.startsWith("Golf + snack: ran"), learner.lastOutcome)
    }

    @Test
    fun `the snack path starts from what plain golf has learned`() {
        round(bgAt = { HIGH })                                   // plain golf → 0.97
        assertEquals(0.97, learner.isfMultiplier(SessionLabel.GOLF, snack = true), 1e-9)
        round(bgAt = { HIGH }, snackAtMin = 30)                  // snack path → 0.97 × 0.97
        assertEquals(0.97 * 0.97, learner.isfMultiplier(SessionLabel.GOLF, snack = true), 1e-9)
        assertEquals(0.97, learner.isfMultiplier(SessionLabel.GOLF), 1e-9)
        assertEquals(1, learner.sessionCount(SessionLabel.GOLF, snack = true))
    }

    @Test
    fun `after the tap the round doses with the snack path, before it with plain golf`() {
        round(bgAt = { HIGH })                                   // plain 0.97
        round(bgAt = { HIGH }, snackAtMin = 0)                   // snack 0.9409
        manager.start(SessionLabel.GOLF, BASE_MS, 240)
        assertEquals(0.97, manager.dosing(mins(30)).isfMultiplier, 1e-9)
        manager.toggleSnack(mins(30))
        assertEquals(0.97 * 0.97, manager.dosing(mins(35)).isfMultiplier, 1e-9)
    }

    @Test
    fun `a snack round is judged only from the snack on`() {
        // High before the snack (plain golf's part), on target after it.
        round(bgAt = { m -> if (m < 60) HIGH else TARGET }, snackAtMin = 60)
        assertEquals(1.0, learner.isfMultiplier(SessionLabel.GOLF, snack = true), 1e-9)
        assertEquals(1.0, learner.isfMultiplier(SessionLabel.GOLF), 1e-9)
        assertTrue(learner.lastOutcome.startsWith("Golf + snack: tracked target"), learner.lastOutcome)
    }

    @Test
    fun `a snack after the scored part of the round changes nothing`() {
        round(bgAt = { HIGH }, snackAtMin = 200)                 // scored part ends at 144min of 240
        assertEquals(1.0, learner.isfMultiplier(SessionLabel.GOLF, snack = true), 1e-9)
        assertEquals(1.0, learner.isfMultiplier(SessionLabel.GOLF), 1e-9)
        assertTrue(learner.lastOutcome.contains("snack came after the scored part"), learner.lastOutcome)
    }

    @Test
    fun `a low in a snack round eases the snack path only`() {
        round(lowAtMin = 200, snackAtMin = 30)
        assertEquals(1.06, learner.isfMultiplier(SessionLabel.GOLF, snack = true), 1e-9)
        assertEquals(1.0, learner.isfMultiplier(SessionLabel.GOLF), 1e-9)
    }

    @Test
    fun `tapping again takes the snack off and the round is plain golf`() {
        manager.start(SessionLabel.GOLF, BASE_MS, 240)
        assertTrue(manager.toggleSnack(mins(30)))
        assertFalse(manager.toggleSnack(mins(31)))
        assertNull(manager.snackAtMs)
    }

    @Test
    fun `no session means no snack to mark`() {
        assertFalse(manager.toggleSnack(mins(0)))
    }

    @Test
    fun `the snack mark and both paths survive a restart`() {
        round(bgAt = { HIGH })
        round(bgAt = { HIGH }, snackAtMin = 0)
        manager.start(SessionLabel.GOLF, BASE_MS, 240)
        manager.toggleSnack(mins(30))

        val learner2 = ActivitySessionLearner(sp, FakeAAPSLogger(collect = false))
        val manager2 = ActivitySessionManager(sp, FakeAAPSLogger(collect = false), learner2)
        assertEquals(mins(30), manager2.snackAtMs)
        assertEquals(0.97, learner2.isfMultiplier(SessionLabel.GOLF), 1e-9)
        assertEquals(0.97 * 0.97, learner2.isfMultiplier(SessionLabel.GOLF, snack = true), 1e-9)
        assertEquals(listOf("Golf", "Golf + snack"), learner2.rows().map { it.name })
    }
}
