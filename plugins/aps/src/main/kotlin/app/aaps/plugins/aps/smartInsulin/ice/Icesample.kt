package app.aaps.plugins.aps.smartInsulin.ice

/**
 * One cycle's observation in the ICE rolling buffer.
 *
 * ## Computation
 *
 * Between this sample and the previous one (typically 5 min apart):
 * - [observedDeltaMgdl] = bgMgdl(this) − bgMgdl(previous)
 * - [modeledDeltaMgdl]  = −avgActivity × isf × intervalMin  (insulin-only prediction)
 * - [iceMgdlPerHour]    = (observed − modeled) × (60 / intervalMin)
 *
 * Positive ICE = something other than insulin pushing BG up (carbs, protein conversion,
 * stress, dawn, illness). Negative ICE = unmodeled BG-lowering force (exercise
 * sensitivity, late insulin peak, sensor drift down).
 *
 * The first sample after start has null observed/modeled/ice fields because there's
 * no previous sample to diff against. Same after a gap > MAX_INTERVAL_MIN.
 */
data class IceSample(
    /** When this CGM reading came in (epoch ms). */
    val timestampMs: Long,

    /** BG at this cycle, mg/dL. */
    val bgMgdl: Double,

    /** Current insulin action rate, U/min (from IobTotal.activity at this moment). */
    val activityUperMin: Double,

    /** ISF used for the modeled-insulin calculation this cycle, mg/dL per U. */
    val isfMgdlPerU: Double,

    /** Observed BG change since previous cycle (mg/dL). Null if no previous cycle. */
    val observedDeltaMgdl: Double? = null,

    /** Modeled insulin-only BG change since previous cycle (mg/dL). Negative when insulin is active. */
    val modeledDeltaMgdl: Double? = null,

    /** ICE in mg/dL per hour. Null if no previous cycle. Convert to mmol/h by dividing by 18. */
    val iceMgdlPerHour: Double? = null,

    /** Interval since previous cycle, minutes. Null if no previous cycle. */
    val intervalMin: Double? = null,

    /** If ICE was suppressed this cycle, the reason. Null = ICE active. */
    val disabled: IceDisableReason? = null
) {
    val iceMmolPerHour: Double?
        get() = iceMgdlPerHour?.div(18.0)
}