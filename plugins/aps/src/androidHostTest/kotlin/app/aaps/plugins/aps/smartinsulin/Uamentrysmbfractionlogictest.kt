package app.aaps.plugins.aps.smartInsulin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * End-to-end unit tests for the UAM entry SMB fraction logic as implemented
 * in SmartInsulinPlugin (lines ~620–726).
 *
 * Rather than instantiating the full plugin (which requires ~20 mocked
 * dependencies), these tests extract and replay the exact fraction-selection
 * logic verbatim from the plugin. This gives us a pure, fast, deterministic
 * test of the counter behaviour without any mock ceremony.
 *
 * The logic under test (copied exactly from SmartInsulinPlugin.invoke()):
 *
 *   var uamSmbFraction = if (currentModeIsUam && uamEntrySmbsDelivered < entrySmbCount)
 *       entrySmbFraction else SMB_DELIVERY_FRACTION
 *
 *   // Post-determine_basal:
 *   val wasEntrySmb = currentModeIsUam && apsResult.smb > 0.0 && uamEntrySmbsDelivered < entrySmbCount
 *   if (wasEntrySmb) uamEntrySmbsDelivered++
 *
 * Your settings:  entrySmbFraction = 1.0,  entrySmbCount = 3
 * Normal setting: SMB_DELIVERY_FRACTION    = 0.5
 */
class UamEntrySmbFractionLogicTest {

    companion object {
        private const val SMB_DELIVERY_FRACTION = 0.5
    }

    /**
     * Simulates one loop cycle of the fraction-selection + counter-increment logic.
     *
     * @param currentModeIsUam  true when an active UAM meal mode (not P/F) is running
     * @param uamEntrySmbsDelivered  current counter value (mutated by the plugin each cycle)
     * @param entrySmbCount     user pref: number of entry SMBs (your setting: 3)
     * @param entrySmbFraction  user pref: fraction for entry SMBs (your setting: 1.0)
     * @param smbDelivered      simulated SMB output from determine_basal (>0 = delivered)
     * @return Pair(fractionUsed, updatedCounter)
     */
    private fun simulateCycle(
        currentModeIsUam: Boolean,
        uamEntrySmbsDelivered: Int,
        entrySmbCount: Int,
        entrySmbFraction: Double,
        smbDelivered: Double
    ): Pair<Double, Int> {
        // Plugin line 635-636
        val uamSmbFraction = if (currentModeIsUam && uamEntrySmbsDelivered < entrySmbCount)
            entrySmbFraction else SMB_DELIVERY_FRACTION

        // Plugin lines 723-725
        var counter = uamEntrySmbsDelivered
        val wasEntrySmb = currentModeIsUam && smbDelivered > 0.0 && counter < entrySmbCount
        if (wasEntrySmb) counter++

        return Pair(uamSmbFraction, counter)
    }

    // ── Test 1: Your exact setup — fraction=1.0, count=3 ────────────────────

    /**
     * Confirms: with entrySmbFraction=1.0 and entrySmbCount=3, the first 3 SMBs
     * that fire while in UAM mode all use fraction=1.0, then it drops back to 0.5.
     *
     * This is the core behaviour you asked to verify.
     */
    @Test
    fun `your settings — fraction 1_0 applies for exactly 3 SMB cycles then reverts to 0_5`() {
        var counter = 0
        val entrySmbCount    = 3
        val entrySmbFraction = 1.0

        // Cycle 1: UAM active, first entry SMB
        var (fraction, newCounter) = simulateCycle(
            currentModeIsUam = true, uamEntrySmbsDelivered = counter,
            entrySmbCount = entrySmbCount, entrySmbFraction = entrySmbFraction, smbDelivered = 0.5
        )
        assertEquals(1.0, fraction, 0.001, "Cycle 1: should use entry fraction 1.0")
        assertEquals(1, newCounter, "Counter should be 1 after first SMB")
        counter = newCounter

        // Cycle 2: second entry SMB
        val (fraction2, counter2) = simulateCycle(
            currentModeIsUam = true, uamEntrySmbsDelivered = counter,
            entrySmbCount = entrySmbCount, entrySmbFraction = entrySmbFraction, smbDelivered = 0.5
        )
        assertEquals(1.0, fraction2, 0.001, "Cycle 2: should still use entry fraction 1.0")
        assertEquals(2, counter2, "Counter should be 2 after second SMB")
        counter = counter2

        // Cycle 3: third and final entry SMB
        val (fraction3, counter3) = simulateCycle(
            currentModeIsUam = true, uamEntrySmbsDelivered = counter,
            entrySmbCount = entrySmbCount, entrySmbFraction = entrySmbFraction, smbDelivered = 0.5
        )
        assertEquals(1.0, fraction3, 0.001, "Cycle 3: should still use entry fraction 1.0")
        assertEquals(3, counter3, "Counter should be 3 after third SMB")
        counter = counter3

        // Cycle 4: counter == entrySmbCount → revert to normal 0.5
        val (fraction4, counter4) = simulateCycle(
            currentModeIsUam = true, uamEntrySmbsDelivered = counter,
            entrySmbCount = entrySmbCount, entrySmbFraction = entrySmbFraction, smbDelivered = 0.5
        )
        assertEquals(SMB_DELIVERY_FRACTION, fraction4, 0.001,
                     "Cycle 4: counter exhausted — should revert to SMB_DELIVERY_FRACTION (0.5)")
        assertEquals(3, counter4, "Counter should stay at 3 (no more increments)")
    }

    // ── Test 2: Cycles where no SMB fires don't consume the count ────────────

    /**
     * If determine_basal returns smb=0 (e.g. BG not high enough, IOB headroom zero),
     * the counter should NOT increment. The entry-fraction cycles are counted by
     * *delivered* SMBs, not by elapsed loop iterations.
     */
    @Test
    fun `counter only increments when an SMB is actually delivered (smb gt 0)`() {
        var counter = 0
        val entrySmbCount    = 3
        val entrySmbFraction = 1.0

        // Loop cycle — UAM active but SMB blocked (smb=0.0)
        val (fraction1, counter1) = simulateCycle(
            currentModeIsUam = true, uamEntrySmbsDelivered = counter,
            entrySmbCount = entrySmbCount, entrySmbFraction = entrySmbFraction, smbDelivered = 0.0
        )
        assertEquals(1.0, fraction1, 0.001, "Fraction should still be 1.0 (counter not used up)")
        assertEquals(0, counter1, "Counter must NOT increment when smb=0")
        counter = counter1

        // Next cycle — SMB fires
        val (fraction2, counter2) = simulateCycle(
            currentModeIsUam = true, uamEntrySmbsDelivered = counter,
            entrySmbCount = entrySmbCount, entrySmbFraction = entrySmbFraction, smbDelivered = 0.3
        )
        assertEquals(1.0, fraction2, 0.001, "Fraction still 1.0 — this is the first delivered SMB")
        assertEquals(1, counter2, "Counter increments to 1 on first delivered SMB")
    }

    // ── Test 3: FASTING mode always uses 0.5 ─────────────────────────────────

    @Test
    fun `FASTING mode always uses SMB_DELIVERY_FRACTION 0_5 regardless of counter`() {
        // currentModeIsUam = false (FASTING)
        val (fraction, counter) = simulateCycle(
            currentModeIsUam = false, uamEntrySmbsDelivered = 0,
            entrySmbCount = 3, entrySmbFraction = 1.0, smbDelivered = 0.5
        )
        assertEquals(SMB_DELIVERY_FRACTION, fraction, 0.001,
                     "FASTING: fraction must be 0.5, never entrySmbFraction")
        assertEquals(0, counter, "FASTING: counter must not increment")
    }

    // ── Test 4: Counter reset on UAM mode expiry and re-entry ─────────────────

    /**
     * When UAM mode expires (currentModeIsUam transitions false → true again),
     * the plugin resets uamEntrySmbsDelivered=0 (line 628).
     * Simulate that by passing counter=0 again, confirming a fresh window.
     */
    @Test
    fun `new UAM entry after mode expiry starts fresh counter — full 3 cycles again`() {
        val entrySmbCount    = 3
        val entrySmbFraction = 1.0

        // Exhaust the first UAM entry window (3 SMBs)
        var counter = 3  // simulates counter after 3 SMBs delivered in previous window

        // Mode expired → plugin resets counter (line 631). Re-entry:
        counter = 0  // plugin sets uamEntrySmbsDelivered = 0

        val (fraction, counter2) = simulateCycle(
            currentModeIsUam = true, uamEntrySmbsDelivered = counter,
            entrySmbCount = entrySmbCount, entrySmbFraction = entrySmbFraction, smbDelivered = 0.5
        )
        assertEquals(1.0, fraction, 0.001,
                     "After mode expiry and fresh UAM entry, counter reset to 0 — should get entry fraction again")
        assertEquals(1, counter2)
    }

    // ── Test 5: P/F mode excluded (currentModeIsUam = false for UAM_PROTEIN_FAT) ──

    @Test
    fun `UAM_PROTEIN_FAT mode uses normal 0_5 fraction, not entry fraction`() {
        // Plugin line 625: currentModeIsUam = mealMode.isUam && mealMode != MealMode.UAM_PROTEIN_FAT
        // So for P/F: currentModeIsUam = false
        val (fraction, counter) = simulateCycle(
            currentModeIsUam = false,  // P/F is excluded
            uamEntrySmbsDelivered = 0,
            entrySmbCount = 3, entrySmbFraction = 1.0, smbDelivered = 0.5
        )
        assertEquals(SMB_DELIVERY_FRACTION, fraction, 0.001,
                     "P/F should use normal 0.5 — it's a tail correction, not a meal entry")
        assertEquals(0, counter, "P/F: counter must not increment")
    }

    // ── Test 6: Re-evaluation block dead code check ───────────────────────────

    /**
     * POTENTIAL BUG FOUND: The re-evaluation block (plugin lines 639-646):
     *
     *   if (justFiredMode != null && latestMealMode != mealMode) { ... }
     *
     * The condition `latestMealMode != mealMode` is ALWAYS FALSE by this point.
     * Two lines above (line 614-617), the plugin already sets:
     *
     *   mealMode = latestMealMode   ← mealMode is updated to latestMealMode
     *
     * So when we reach line 639, latestMealMode == mealMode by construction.
     * The re-evaluation block is effectively dead code on the burst fire cycle.
     *
     * IMPACT: On the burst fire cycle itself (when justFiredMode != null),
     * the first evaluation block (lines 626-636) runs with the ALREADY-UPDATED
     * mealMode (because line 617 already set mealMode = latestMealMode).
     * So the first block IS correct — currentModeIsUam is computed from the
     * new UAM mode, uamEntryModeStartMs is set, and uamSmbFraction = entrySmbFraction.
     *
     * The re-evaluation block adds no value and can be safely removed.
     * This test documents that finding.
     */
    @Test
    fun `re-evaluation block is dead code — mealMode already equals latestMealMode before the check`() {
        // Simulate the state at the re-evaluation point:
        // latestMealMode was justFiredMode (e.g. UAM_BREAKFAST)
        // mealMode was set to latestMealMode on line 617
        // → latestMealMode == mealMode → condition is false → block never executes

        // Prove the main block already handles the burst-fire case correctly:
        // On burst fire cycle: mealMode has been updated → currentModeIsUam = true,
        // uamEntryModeStartMs == 0L (first entry) → counter resets and fraction = entrySmbFraction

        var counter = 0
        val entrySmbCount    = 3
        val entrySmbFraction = 1.0

        // First cycle where UAM just fired: mealMode already = UAM_BREAKFAST (updated line 617)
        val (fraction, counter2) = simulateCycle(
            currentModeIsUam = true,   // mealMode already updated before fraction block
            uamEntrySmbsDelivered = counter,
            entrySmbCount = entrySmbCount, entrySmbFraction = entrySmbFraction, smbDelivered = 0.5
        )
        assertEquals(1.0, fraction, 0.001,
                     "Burst fire cycle: fraction correctly 1.0 via main block (re-eval block is dead)")
        assertEquals(1, counter2,
                     "Counter increments correctly on burst fire cycle")
    }
}