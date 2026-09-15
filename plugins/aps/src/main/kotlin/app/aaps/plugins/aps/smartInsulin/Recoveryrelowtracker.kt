package app.aaps.plugins.aps.smartInsulin

/**
 * Decides whether a dip back below the low guard during a recovery window is a real second low,
 * and counts how many there have been so the window can be lengthened for each one.
 *
 * The question is what BG did in between. Coming out of a low, CGM readings commonly flicker
 * across the guard — 4.7, 4.9, 4.7 — and that is still the first low, not a new one. So the time
 * back above the guard (the "excursion") only earns an extension if it was either a decent rise
 * ([RELOW_SPIKE_MARGIN_MGDL] over the guard) or held for [RELOW_SUSTAINED_MS]. Anything shorter
 * and smaller is treated as noise: the window restarts on the next crossing but is not extended.
 *
 * Excursion time is measured between reading timestamps rather than loop wall-clock, so an extra
 * loop run on the same reading can't make a single reading look sustained.
 */
internal class RecoveryRelowTracker {

    /** Genuine re-lows in the current low episode, capped at [RELOW_MAX_EXTENSIONS]. */
    var relowCount: Int = 0
        private set

    private var excursionActive = false
    private var excursionStartMs = 0L
    private var excursionLastAboveMs = 0L
    private var excursionPeakMgdl = 0.0

    /** Call for every reading at or above the guard while a recovery window is running. */
    fun recordAboveGuard(bgMgdl: Double, readingMs: Long) {
        if (!excursionActive) {
            excursionActive = true
            excursionStartMs = readingMs
            excursionPeakMgdl = bgMgdl
        }
        excursionLastAboveMs = readingMs
        if (bgMgdl > excursionPeakMgdl) excursionPeakMgdl = bgMgdl
    }

    fun excursionWasGenuine(lowGuardMgdl: Double): Boolean =
        excursionActive && (
            excursionPeakMgdl >= lowGuardMgdl + RELOW_SPIKE_MARGIN_MGDL ||
                excursionLastAboveMs - excursionStartMs >= RELOW_SUSTAINED_MS
            )

    /**
     * Call once when BG drops below the guard after a recovery window had started. Returns true
     * if this counts as a second low (and [relowCount] was bumped), false if it was noise.
     * Either way the excursion is closed — the next crossing starts a fresh one.
     */
    fun onDipBelowGuard(lowGuardMgdl: Double): Boolean {
        val genuine = excursionWasGenuine(lowGuardMgdl)
        if (genuine) relowCount = (relowCount + 1).coerceAtMost(RELOW_MAX_EXTENSIONS)
        clearExcursion()
        return genuine
    }

    /** The low episode is over (window expired, or learners reset). */
    fun reset() {
        relowCount = 0
        clearExcursion()
    }

    private fun clearExcursion() {
        excursionActive = false
        excursionStartMs = 0L
        excursionLastAboveMs = 0L
        excursionPeakMgdl = 0.0
    }

    companion object {
        /** A rise this far over the guard (1.0 mmol) is a real recovery, however brief. */
        const val RELOW_SPIKE_MARGIN_MGDL = 18.0
        /** Or this long above the guard — first reading to last, so four readings on a 5-min CGM. */
        const val RELOW_SUSTAINED_MS = 15 * 60_000L
        /** Each re-low adds another base window: 1 → double, 2 → triple, then no further. */
        const val RELOW_MAX_EXTENSIONS = 2
    }
}

/**
 * Total recovery window: the base preference, one extra base window per genuine re-low, plus the
 * rollercoaster extension (15 min each, capped at 45).
 */
internal fun reboundWindowMs(baseMins: Int, relowCount: Int, rollercoasters: Int): Long {
    val baseMs = baseMins.coerceAtLeast(0) * 60_000L
    val relowMs = baseMs * relowCount.coerceIn(0, RecoveryRelowTracker.RELOW_MAX_EXTENSIONS)
    val rollerMs = (rollercoasters.coerceAtLeast(0) * SmartInsulinPlugin.ROLLER_REBOUND_EXTENSION_MS)
        .coerceAtMost(SmartInsulinPlugin.ROLLER_REBOUND_EXTENSION_MAX_MS)
    return baseMs + relowMs + rollerMs
}
