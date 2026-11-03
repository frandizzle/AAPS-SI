package app.aaps.core.interfaces.smartInsulin

import kotlin.math.floor

/**
 * Immutable snapshot of an active meal mode override.
 *
 * [preBolus2U]       — pre-bolus 2 dose in units. 0.0 = not requested.
 * [preBolus2DelayMs] — ms after triggerTimeMs to fire the second bolus.
 * [preBolus2FiredMs] — wall-clock time it was actually delivered, or null if still pending.
 * [preBolus3U]       — pre-bolus 3 dose in units.
 * [preBolus3DelayMs] — ms AFTER PB2 FIRES to fire the third bolus.
 * [pfAfter]          — start Protein/Fat (UAM) when this mode runs out, for a meal with a slow fat and protein tail.
 * [addedU]           — insulin given now for food added to this meal later (dessert, a second plate).
 * [preBolus1RestU]   — the part of pre-bolus 1 above the max pre-bolus, given later once BG rises. 0.0 = none.
 * [preBolus1RestFiredMs] — when it was given; null = still waits, -1 = cancelled or failed, -2 = BG did not rise in time.
 * [preBolus1PlannedU] — pre-bolus 1 as planned, when the pump gave less (the bolus was stopped); [doseU] is then what
 *                       it really gave. Null when it gave all of it.
 * [afterActivityPct] — how much less insulin this meal gets after exercise, in percent; 0 = none. The loop makes the
 *                      mode's ISF weaker by the same share, and the meal learners do not judge the meal.
 * [preBolus2Pieces]  — pre-bolus 2 is given as this many pieces, [MealOverrideManager.SPREAD_GAP_MIN] apart, each
 *                      only through the safety gates. 0 = one dose. Pre-bolus 3 is then part of pre-bolus 2.
 * [preBolus2PiecesGiven], [preBolus2GivenU], [preBolus2LastPieceMs] — the pieces given so far, their insulin, and
 *                      when the last one was given.
 * [preBolus2LoopU]   — SMBs the loop gave in this meal, taken off the pieces still to come, see
 *                      [MealOverrideManager.countLoopSmb]. Never more than the pieces had left.
 * [extendedMs]       — how much later the mode ends than it was set to, because its food still came in, see
 *                      [MealOverrideManager.extendWhileFoodComes].
 */
data class MealOverrideState(
    val mode:             MealMode,
    val triggerTimeMs:    Long,
    val modeExpiryMs:     Long,
    val doseU:            Double? = null,  // pre-bolus 1 dose — null if not delivered via SmartMeal
    val preBolus2U:       Double = 0.0,
    val preBolus2DelayMs: Long   = 0L,
    val preBolus2FiredMs: Long?  = null,  // null = not yet fired
    val preBolus3U:       Double = 0.0,
    val preBolus3DelayMs: Long   = 0L,
    val preBolus3FiredMs: Long?  = null,
    val duraEnabled:      Boolean = false,  // DURA_ISF: strengthen ISF while BG sits stuck above target
    val duraFloorMgdl:    Double  = 0.0,    // lowest (strongest) ISF DURA may push toward; 0.0 = no-op
    val duraStrength:     Double  = 1.0,    // how fast DURA ramps while stuck (mirrors DuraIsfTracker.DEFAULT_WEIGHT)
    val pfAfter:          Boolean = false,
    val addedU:           Double  = 0.0,
    val afterActivityPct: Int     = 0,
    val preBolus1RestU:   Double  = 0.0,
    val preBolus1RestFiredMs: Long? = null,
    val preBolus1PlannedU: Double? = null,
    val preBolus2Pieces:  Int     = 0,
    val preBolus2PiecesGiven: Int = 0,
    val preBolus2GivenU:  Double  = 0.0,
    val preBolus2LastPieceMs: Long? = null,
    val preBolus2LoopU:   Double  = 0.0,
    val extendedMs:       Long    = 0L
) {
    /**
     * Pre-bolus 1 was stopped well short of the plan: at least [CUT_SHORT_MIN_U], or [CUT_SHORT_SHARE] of it, was
     * not given. The meal then got less insulin than planned, so a high says nothing about the meal's settings.
     */
    val preBolus1CutShort: Boolean
        get() = preBolus1PlannedU?.let { planned -> planned - (doseU ?: 0.0) >= minOf(CUT_SHORT_MIN_U, planned * CUT_SHORT_SHARE) - ROUNDING_U } ?: false

    /** True while the rest of pre-bolus 1 waits for BG to rise */
    val preBolus1RestPending: Boolean
        get() = preBolus1RestU > 0.0 && preBolus1RestFiredMs == null

    /** True if a second bolus was requested and hasn't fired yet. In pieces: until the last piece is given. */
    val preBolus2Pending: Boolean
        get() = preBolus2U > 0.0 && preBolus2FiredMs == null

    /** Pre-bolus 2 is given in pieces. */
    val preBolus2Spread: Boolean
        get() = preBolus2Pieces > 1

    /** Pre-bolus 2 insulin still to come: all of it, or what the pieces and the loop have not given yet. */
    val preBolus2WaitingU: Double
        get() = if (preBolus2Pending) preBolus2LeftU else 0.0

    /** What the pieces still have to give: pre-bolus 2 less the pieces given and the loop's SMBs taken off them. */
    val preBolus2LeftU: Double
        get() = (preBolus2U - preBolus2GivenU - preBolus2LoopU).coerceAtLeast(0.0)

    val preBolus3WaitingU: Double
        get() = if (preBolus3Pending) preBolus3U else 0.0

    /** When the next piece may be given: at the pre-bolus 2 time, then [MealOverrideManager.SPREAD_GAP_MIN] after the last one. */
    val preBolus2NextAtMs: Long
        get() = preBolus2LastPieceMs?.let { it + MealOverrideManager.SPREAD_GAP_MIN * 60_000L } ?: preBolus2FireAtMs

    /**
     * The next piece: what is left shared over the pieces left, rounded down to [MealOverrideManager.SPREAD_STEP_U].
     * The last piece is all that is left. A piece that waits for the gates stays the same size, so pieces never add up.
     * When the loop has given part of it, fewer pieces share what is left, none smaller than
     * [MealOverrideManager.SPREAD_MIN_PIECE_U].
     */
    val preBolus2NextPieceU: Double
        get() {
            val left = preBolus2LeftU
            val bigEnough = (left / MealOverrideManager.SPREAD_MIN_PIECE_U + ROUNDING_U).toInt().coerceAtLeast(1)
            val piecesLeft = minOf(preBolus2Pieces - preBolus2PiecesGiven, bigEnough)
            if (piecesLeft <= 1) return left
            val step = MealOverrideManager.SPREAD_STEP_U
            return (floor(left / piecesLeft / step + ROUNDING_U) * step).coerceAtLeast(step).coerceAtMost(left)
        }

    /**
     * The insulin of the waiting pre-boluses that can still be given with [iobU] on board: a pre-bolus never takes IOB
     * past [maxIobU], so what does not fit never comes. The rest of pre-bolus 1 comes first, then pre-bolus 2 and 3.
     * The loop's forecast takes off only the food this insulin covers, and gives the insulin for the rest itself.
     */
    fun waitingThatFits(iobU: Double, maxIobU: Double): WaitingInsulin {
        val roomU = (maxIobU - iobU).coerceAtLeast(0.0)
        val restU = (if (preBolus1RestPending) preBolus1RestU else 0.0).coerceAtMost(roomU)
        val preBolusU = (preBolus2WaitingU + preBolus3WaitingU).coerceAtMost(roomU - restU)
        return WaitingInsulin(restU, preBolusU)
    }

    /** Wall-clock time PB2 should fire */
    val preBolus2FireAtMs: Long
        get() = triggerTimeMs + preBolus2DelayMs

    /** True if PB2 fired successfully (positive timestamp) */
    val preBolus2FiredSuccessfully: Boolean
        get() = preBolus2FiredMs != null && preBolus2FiredMs > 0L

    /** True if a third bolus was requested and hasn't fired yet */
    val preBolus3Pending: Boolean
        get() = preBolus3U > 0.0 && preBolus3FiredMs == null

    /** All insulin this meal gives as fixed doses: what was given plus what is still planned. Cancelled pre-boluses do not count. */
    val plannedTotalU: Double
        get() = (doseU ?: 0.0) + addedU +
            (if (preBolus1RestFiredMs == null || preBolus1RestFiredMs > 0L) preBolus1RestU else 0.0) +
            (if (preBolus2FiredMs != -1L) preBolus2U else preBolus2GivenU) +
            (if (preBolus3FiredMs != -1L) preBolus3U else 0.0)

    /** Fire time for PB3: preBolus3DelayMs relative to PB2 fire time.
     *  Returns null if PB2 hasn't fired yet (can't compute PB3 trigger). */
    val preBolus3FireAtMs: Long?
        get() = if (preBolus2FiredSuccessfully) preBolus2FiredMs!! + preBolus3DelayMs else null

    companion object {

        const val CUT_SHORT_MIN_U = 0.5
        const val CUT_SHORT_SHARE = 0.1
        /** Pump amounts like 2.0 - 1.8 are not exact in a Double. */
        private const val ROUNDING_U = 1e-9
    }
}

/** Insulin of waiting pre-boluses, see [MealOverrideState.waitingThatFits]: [restU] of pre-bolus 1, [preBolusU] of pre-bolus 2 and 3. */
data class WaitingInsulin(val restU: Double, val preBolusU: Double)
