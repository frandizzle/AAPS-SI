package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.keys.interfaces.Preferences
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class MealPhaseTrackerTest {

    private val aapsLogger: AAPSLogger = mock()
    private val preferences: Preferences = mock()
    private lateinit var sut: MealPhaseTracker

    private val MIN  = 60_000L
    private val T5   = 5  * MIN;  private val T10  = 10 * MIN; private val T15  = 15 * MIN
    private val T20  = 20 * MIN;  private val T30  = 30 * MIN; private val T35  = 35 * MIN
    private val T40  = 40 * MIN;  private val T45  = 45 * MIN; private val T50  = 50 * MIN
    private val T55  = 55 * MIN;  private val T60  = 60 * MIN; private val T65  = 65 * MIN
    private val T70  = 70 * MIN;  private val T75  = 75 * MIN; private val T80  = 80 * MIN
    private val T120 = 120 * MIN; private val T140 = 140 * MIN; private val T150 = 150 * MIN
    private val T160 = 160 * MIN

    private val TARGET    = 5.5
    private val LOW_GUARD = 4.0
    private val MODE      = MealMode.UAM_LUNCH

    @BeforeEach fun setup() { sut = MealPhaseTracker(aapsLogger, preferences) }

    private fun cycle(now: Long, mode: MealMode, bg: Double, delta: Double = 0.0, target: Double = TARGET, lowGuard: Double = LOW_GUARD) =
        sut.onLoopCycle(now, mode, bg, shortAvgDelta = delta, delta = delta, target, lowGuard)

    private fun fasting(now: Long, bg: Double = 5.5) = cycle(now, MealMode.FASTING, bg)
    private fun meal(now: Long, bg: Double, delta: Double = 0.0) = cycle(now, MODE, bg, delta)

    /**
     * Drives tracker to PROTEIN_FAT phase. Returns P/F start timestamp.
     *
     * WHY the priming pattern works:
     * earlyAvgDelta(3) uses deltaHistory.subList(size-6, size-3).
     * Needs size >= 6. With 3 HIGH readings then LOW readings:
     *   At 6th reading (3rd LOW): history=[H,H,H,L,L,L]
     *   early=[H,H,H]=0.8, recent=[L,L,L]=0.05
     *   deltaSlowing=0.05 < 0.8-0.05=0.75 ✓ → 1st confirm
     * Two more LOW readings give 2nd and 3rd confirms → TRANSITION.
     *
     * Checking only enabled when phaseElapsedMs >= CARB_MIN_MS=40min.
     * Session starts at [sessionStart], so:
     *   T5,T10,T15 = 3 HIGH readings
     *   T40,T45,T50 = LOW readings (phaseElapsed=T40 ✓ at T40, first confirm at T50)
     *   T55 = 2nd confirm, T60 = 3rd confirm → P/F starts at T60
     */
    private fun driveToProteinFat(sessionStart: Long = 0L): Long {
        fasting(sessionStart - T5)
        meal(sessionStart, 9.0)
        meal(sessionStart + T5,  9.2, 0.80)
        meal(sessionStart + T10, 9.4, 0.80)
        meal(sessionStart + T15, 9.5, 0.80)
        meal(sessionStart + T40, 8.6, 0.05)
        meal(sessionStart + T45, 8.5, 0.05)
        meal(sessionStart + T50, 8.4, 0.05)  // recent=[0.05,0.05,0.05] ✓ 1st confirm
        meal(sessionStart + T55, 8.3, 0.05)  // 2nd confirm
        meal(sessionStart + T60, 8.2, 0.05)  // 3rd confirm → CARB→P/F
        return sessionStart + T60
    }

    /**
     * Drives tracker from P/F to TAIL. Returns TAIL start timestamp.
     *
     * P/F→TAIL needs recentAvgDelta(3) < -0.10 AND bgAboveTarget, 3 confirms.
     * 5 negative readings needed: first 3 flush recent avg, then 3rd/4th/5th are confirms.
     * phaseElapsedMs >= PF_MIN_MS=60min needed, so readings start at pfStart+T60.
     */
    private fun driveToTail(pfStart: Long): Long {
        meal(pfStart + T5,  8.0, 0.05)
        meal(pfStart + T10, 7.9, 0.05)
        meal(pfStart + T15, 7.8, 0.05)
        meal(pfStart + T60, 7.5, -0.15)   // recent=[0.05,0.05,-0.15]=-0.017 ✗
        meal(pfStart + T65, 7.2, -0.15)   // recent=[0.05,-0.15,-0.15]=-0.083 ✗
        meal(pfStart + T70, 6.9, -0.15)   // recent=[-0.15,-0.15,-0.15]=-0.15 ✓ 1st confirm
        meal(pfStart + T75, 6.6, -0.15)   // 2nd confirm
        meal(pfStart + T80, 6.3, -0.15)   // 3rd confirm → P/F→TAIL
        return pfStart + T80
    }

    // ── Session lifecycle ─────────────────────────────────────────────────────

    @Test fun `session does not start while fasting`() {
        fasting(0L); assertFalse(sut.sessionActive)
    }

    @Test fun `session starts on first non-fasting cycle`() {
        fasting(0L); meal(T5, 6.0)
        assertTrue(sut.sessionActive)
        assertEquals(MODE, sut.sessionMode)
        assertEquals(MealPhaseTracker.MealPhase.CARB, sut.currentPhase)
    }

    @Test fun `session invalidated when returning to fasting with BG elevated`() {
        fasting(0L); meal(T5, 8.0)
        cycle(T40, MealMode.FASTING, bg = 9.0)
        assertFalse(sut.sessionActive)
    }

    @Test fun `UAM auto-cancel at target counts as successful completion`() {
        var completed: MealPhaseTracker.CompletedMealSession? = null
        sut.onSessionComplete = { completed = it }
        fasting(0L); meal(T5, 8.0)
        cycle(T40, MealMode.FASTING, bg = TARGET + 0.3)
        assertFalse(sut.sessionActive)
        assertNotNull(completed, "Should complete, not invalidate")
        assertEquals(MODE, completed!!.mode)
    }

    // ── Carb phase ────────────────────────────────────────────────────────────

    @Test fun `stays in CARB before 40 min minimum`() {
        fasting(0L); meal(T5, 9.0, 0.8)
        meal(T10, 8.8, 0.10); meal(T15, 8.7, 0.08); meal(T20, 8.6, 0.05); meal(T30, 8.5, 0.05)
        assertEquals(MealPhaseTracker.MealPhase.CARB, sut.currentPhase, "Min not reached")
    }

    @Test fun `transitions CARB to P_F with correctly primed delta history`() {
        driveToProteinFat()
        assertEquals(MealPhaseTracker.MealPhase.PROTEIN_FAT, sut.currentPhase)
    }

    @Test fun `CARB to P_F transition resets on delta bounce`() {
        fasting(0L); meal(T5, 9.0)
        meal(T10, 9.2, 0.80); meal(T15, 9.4, 0.80); meal(T20, 9.5, 0.80)
        meal(T40, 8.6, 0.05); meal(T45, 8.5, 0.05)
        meal(T50, 8.4, 0.05)   // 1st confirm
        meal(T55, 8.3, 0.05)   // 2nd confirm
        meal(T60, 8.5, 0.40)   // BOUNCE > CARB_EXIT+0.05 → reset
        meal(T65, 8.4, 0.05)   // 1st confirm again (not 3rd)
        assertEquals(MealPhaseTracker.MealPhase.CARB, sut.currentPhase, "Counter reset by bounce")
    }

    // ── Protein/Fat phase ─────────────────────────────────────────────────────

    @Test fun `stays in P_F before 60 min minimum`() {
        val pfStart = driveToProteinFat()
        meal(pfStart + T5, 7.8, -0.15); meal(pfStart + T10, 7.5, -0.15)
        meal(pfStart + T15, 7.2, -0.15); meal(pfStart + T30, 7.0, -0.15)
        assertEquals(MealPhaseTracker.MealPhase.PROTEIN_FAT, sut.currentPhase, "60 min min not reached")
    }

    @Test fun `transitions P_F to TAIL after 60 min with negative delta`() {
        val pfStart = driveToProteinFat()
        driveToTail(pfStart)
        assertEquals(MealPhaseTracker.MealPhase.TAIL, sut.currentPhase)
        assertTrue(sut.isInTailPhase)
    }

    @Test fun `P_F to TAIL blocked when BG below target`() {
        val pfStart = driveToProteinFat()
        meal(pfStart + T5, 7.8, 0.05); meal(pfStart + T10, 7.6, 0.05)
        // BG below target blocks P/F→TAIL
        meal(pfStart + T60, 5.0, -0.15); meal(pfStart + T65, 4.8, -0.15); meal(pfStart + T70, 4.6, -0.15)
        assertEquals(MealPhaseTracker.MealPhase.PROTEIN_FAT, sut.currentPhase, "BG below target blocks transition")
    }

    // ── Tail phase ────────────────────────────────────────────────────────────

    @Test fun `tail completes when BG returns within 0_5 of target after 30 min`() {
        var completed: MealPhaseTracker.CompletedMealSession? = null
        sut.onSessionComplete = { completed = it }
        val pfStart = driveToProteinFat(); val tailStart = driveToTail(pfStart)
        meal(tailStart + T35, TARGET + 0.3)
        assertFalse(sut.sessionActive, "Session complete")
        assertNotNull(completed); assertFalse(completed!!.tailPhaseWentLow); assertTrue(completed!!.isClean)
    }

    @Test fun `tail completes on crash below target and sets tailPhaseWentLow`() {
        var completed: MealPhaseTracker.CompletedMealSession? = null
        sut.onSessionComplete = { completed = it }
        val pfStart = driveToProteinFat(); val tailStart = driveToTail(pfStart)
        meal(tailStart + T35, 3.5)   // crash below low guard
        assertFalse(sut.sessionActive, "Session completes on crash")
        assertNotNull(completed); assertTrue(completed!!.tailPhaseWentLow)
    }

    @Test fun `tail does not complete before 30 min minimum`() {
        val pfStart = driveToProteinFat(); val tailStart = driveToTail(pfStart)
        meal(tailStart + T10, TARGET + 0.2)
        assertTrue(sut.sessionActive, "Active — min not reached")
        assertEquals(MealPhaseTracker.MealPhase.TAIL, sut.currentPhase)
    }

    @Test fun `isInTailPhase is false during CARB and P_F, true during TAIL`() {
        assertFalse(sut.isInTailPhase)
        fasting(0L); meal(T5, 8.0); assertFalse(sut.isInTailPhase, "CARB")
        val pfStart = driveToProteinFat(); assertFalse(sut.isInTailPhase, "P/F")
        driveToTail(pfStart); assertTrue(sut.isInTailPhase, "TAIL")
    }

    // ── Low flags ─────────────────────────────────────────────────────────────

    @Test fun `carb low NOT flagged within 20 min gate`() {
        var c: MealPhaseTracker.CompletedMealSession? = null; sut.onSessionComplete = { c = it }
        fasting(0L); meal(T5, 6.0)
        meal(T5 + T10, 3.5)          // 10 min — within gate
        meal(T5 + T20, 3.5)          // 20 min — at gate boundary, NOT past it
        cycle(T5 + T30, MealMode.FASTING, TARGET + 0.3)
        assertNotNull(c); assertFalse(c!!.carbPhaseWentLow, "Gate should block within 20 min")
    }

    @Test fun `carb low IS flagged after 20 min gate`() {
        var c: MealPhaseTracker.CompletedMealSession? = null; sut.onSessionComplete = { c = it }
        fasting(0L); meal(T5, 6.0)
        meal(T5 + T20 + MIN, 3.5)    // 21 min — past gate
        cycle(T5 + T30, MealMode.FASTING, TARGET + 0.3)
        assertNotNull(c); assertTrue(c!!.carbPhaseWentLow, "Should flag after gate")
    }

    @Test fun `P_F low flagged immediately`() {
        var c: MealPhaseTracker.CompletedMealSession? = null; sut.onSessionComplete = { c = it }
        val pfStart = driveToProteinFat()
        meal(pfStart + T5, 3.5)
        cycle(pfStart + T20, MealMode.FASTING, TARGET + 0.3)
        assertNotNull(c); assertTrue(c!!.pfPhaseWentLow)
    }

    @Test fun `tail low flagged`() {
        var c: MealPhaseTracker.CompletedMealSession? = null; sut.onSessionComplete = { c = it }
        val pfStart = driveToProteinFat(); val tailStart = driveToTail(pfStart)
        meal(tailStart + T5, 3.5); meal(tailStart + T35, 3.8)
        assertNotNull(c); assertTrue(c!!.tailPhaseWentLow)
    }

    // ── P/F high flag ─────────────────────────────────────────────────────────

    @Test fun `P_F high NOT flagged when counter resets on dip`() {
        var c: MealPhaseTracker.CompletedMealSession? = null; sut.onSessionComplete = { c = it }
        val pfStart = driveToProteinFat()
        meal(pfStart + T5, 10.5); meal(pfStart + T10, 10.3)
        meal(pfStart + T20, 9.8)   // dip — resets counter
        meal(pfStart + T30, 10.1)  // only 1 confirm since reset
        cycle(pfStart + T40, MealMode.FASTING, TARGET + 0.3)
        assertNotNull(c); assertFalse(c!!.pfPhaseWentHigh, "Reset prevents flag")
    }

    @Test fun `P_F high IS flagged after 3 consecutive readings above 10 mmol`() {
        var c: MealPhaseTracker.CompletedMealSession? = null; sut.onSessionComplete = { c = it }
        val pfStart = driveToProteinFat()
        meal(pfStart + T5, 10.5); meal(pfStart + T10, 10.3); meal(pfStart + T20, 10.1)
        cycle(pfStart + T40, MealMode.FASTING, TARGET + 0.3)
        assertNotNull(c); assertTrue(c!!.pfPhaseWentHigh)
    }

    @Test fun `carb phase high above 10 mmol does NOT set pfPhaseWentHigh`() {
        var c: MealPhaseTracker.CompletedMealSession? = null; sut.onSessionComplete = { c = it }
        fasting(0L); meal(T5, 9.0); meal(T10, 12.0); meal(T15, 13.5); meal(T20, 14.0)
        cycle(T30, MealMode.FASTING, TARGET + 0.3)
        assertNotNull(c); assertFalse(c!!.pfPhaseWentHigh)
    }

    // ── Meal stacking ─────────────────────────────────────────────────────────

    @Test fun `meal stacking invalidates session during P_F`() {
        val pfStart = driveToProteinFat()
        assertEquals(MealPhaseTracker.MealPhase.PROTEIN_FAT, sut.currentPhase)
        meal(pfStart + T30 + T5, 9.0, 0.45); meal(pfStart + T30 + T10, 9.8, 0.48)
        meal(pfStart + T30 + T20, 10.5, 0.50)
        assertFalse(sut.sessionActive, "Stacking should invalidate")
    }

    @Test fun `high delta during CARB does not trigger stacking`() {
        fasting(0L); meal(T5, 9.0, 0.8)
        meal(T10, 10.5, 0.50); meal(T15, 12.0, 0.60); meal(T20, 13.0, 0.50)
        assertTrue(sut.sessionActive, "CARB high delta should not invalidate")
        assertEquals(MealPhaseTracker.MealPhase.CARB, sut.currentPhase)
    }

    // ── Manual bolus ──────────────────────────────────────────────────────────

    @Test fun `onManualBolus flags session without ending it`() {
        var c: MealPhaseTracker.CompletedMealSession? = null; sut.onSessionComplete = { c = it }
        fasting(0L); meal(T5, 8.0)
        sut.onManualBolus(2.0)
        assertTrue(sut.sessionActive)
        cycle(T30, MealMode.FASTING, TARGET + 0.3)
        assertNotNull(c)
        assertTrue(c!!.manualBolusDetected); assertEquals(2.0, c!!.manualBolusU, 0.001); assertFalse(c!!.isClean)
    }

    // ── Full integration ──────────────────────────────────────────────────────

    @Test fun `full clean session produces correct CompletedMealSession`() {
        var c: MealPhaseTracker.CompletedMealSession? = null; sut.onSessionComplete = { c = it }
        val pfStart = driveToProteinFat(0L)
        val tailStart = driveToTail(pfStart)
        meal(tailStart + T35, TARGET + 0.3)
        assertFalse(sut.sessionActive)
        assertNotNull(c)
        with(c!!) {
            assertEquals(MODE, mode)
            assertFalse(carbPhaseWentLow); assertFalse(pfPhaseWentLow)
            assertFalse(tailPhaseWentLow); assertFalse(pfPhaseWentHigh)
            assertFalse(manualBolusDetected); assertTrue(isClean)
            assertTrue(carbPhaseMins >= 40.0, "Carb >= 40min, was $carbPhaseMins")
            assertTrue(pfPhaseMins  >= 60.0,  "P/F >= 60min, was $pfPhaseMins")
            assertTrue(tailPhaseMins >= 30.0,  "Tail >= 30min, was $tailPhaseMins")
        }
    }

    // ── onMealModeExpired ─────────────────────────────────────────────────────

    @Test fun `P_F mode expiry advances to TAIL immediately`() {
        val pfStart = driveToProteinFat(0L)
        assertEquals(MealPhaseTracker.MealPhase.PROTEIN_FAT, sut.currentPhase)

        // P/F mode expires with BG still elevated
        sut.onMealModeExpired(now = pfStart + T30, bgMmol = 9.0, targetBgMmol = TARGET)

        assertEquals(MealPhaseTracker.MealPhase.TAIL, sut.currentPhase,
                     "P/F mode expiry with elevated BG should advance to TAIL immediately")
        assertTrue(sut.sessionActive)
    }

    @Test fun `CARB mode expiry advances to P_F not TAIL`() {
        fasting(0L); meal(T5, 8.0, 0.5)
        assertEquals(MealPhaseTracker.MealPhase.CARB, sut.currentPhase)

        sut.onMealModeExpired(now = T5 + T40, bgMmol = 9.0, targetBgMmol = TARGET)

        assertEquals(MealPhaseTracker.MealPhase.PROTEIN_FAT, sut.currentPhase,
                     "CARB mode expiry with elevated BG should advance to P/F not TAIL")
        assertTrue(sut.sessionActive)
    }

    @Test fun `mode expiry near target completes session`() {
        var completed: MealPhaseTracker.CompletedMealSession? = null
        sut.onSessionComplete = { completed = it }
        val pfStart = driveToProteinFat(0L)

        sut.onMealModeExpired(now = pfStart + T30, bgMmol = TARGET + 0.3, targetBgMmol = TARGET)

        assertFalse(sut.sessionActive, "Session should complete when mode expires near target")
        assertNotNull(completed)
    }

    @Test fun `session completes when BG returns to target after P_F expiry→TAIL`() {
        var completed: MealPhaseTracker.CompletedMealSession? = null
        sut.onSessionComplete = { completed = it }

        val pfStart   = driveToProteinFat(0L)
        val tailStart = pfStart + T30

        // P/F mode expires → TAIL
        sut.onMealModeExpired(now = tailStart, bgMmol = 9.0, targetBgMmol = TARGET)
        assertEquals(MealPhaseTracker.MealPhase.TAIL, sut.currentPhase)

        // BG returns to target after 35 min in tail
        meal(tailStart + T35, TARGET + 0.3)

        assertFalse(sut.sessionActive, "Session should complete when BG returns during TAIL")
        assertNotNull(completed)
        assertTrue(completed!!.tailPhaseMins >= 30.0,
                   "Tail should be >= 30min, was ${completed!!.tailPhaseMins}")
    }

    // ── Multi-session ─────────────────────────────────────────────────────────

    @Test fun `tracker resets cleanly and accepts new session after completion`() {
        fasting(0L); meal(T5, 8.0)
        cycle(T30, MealMode.FASTING, TARGET + 0.2)
        assertFalse(sut.sessionActive)
        fasting(T150); meal(T160, 8.5)
        assertTrue(sut.sessionActive, "New session should start cleanly")
        assertEquals(MealPhaseTracker.MealPhase.CARB, sut.currentPhase)
    }
}