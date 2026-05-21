package app.aaps.plugins.aps.smartInsulin.ice

/**
 * Captured at the end of each loop cycle. The status card UI reads from this; the
 * data flywheel (later) will persist these for offline analysis.
 *
 * Self-contained — should be safe to render without further queries against
 * IceTracker (which may be mid-update on another cycle).
 */
data class IceDebugSnapshot(
    /** When this snapshot was taken (epoch ms). */
    val cycleTimeMs: Long,

    /** Current ICE reading at this cycle, mg/dL/h. Null if no signal yet. */
    val currentIceMgdlH: Double?,

    /** Decomposed confidence — UI can show the breakdown. */
    val confidence: IceConfidence,

    /** Snapshot of the recent ICE buffer (last 3h or so) for chart rendering. */
    val recentHistory: List<IceSample>,

    /**
     * Plain-text summary suitable for compact display.
     * Example: "ICE +0.4 mmol/h, confidence 0.7 (rising 4 cycles, consistent)"
     */
    val summaryText: String
) {
    val currentIceMmolH: Double?
        get() = currentIceMgdlH?.div(18.0)

    /** Quick lookup for UI badge — true means "ICE is meaningfully active right now". */
    val isActive: Boolean
        get() = confidence.score >= 0.4 && confidence.disabled == null
}