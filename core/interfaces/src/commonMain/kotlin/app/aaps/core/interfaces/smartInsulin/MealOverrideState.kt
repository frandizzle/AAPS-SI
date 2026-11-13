package app.aaps.core.interfaces.smartInsulin

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
 *                      only through the safety gates. 0 = one dose. Pre-bolus 3 stays its own dose.
 * [preBolus2PiecesGiven], [preBolus2GivenU], [preBolus2LastPieceMs] — the pieces given so far, their insulin, and
 *                      when the last one was given.
 * [preBolus2LoopU]   — SMBs the loop gave in this meal, taken off the pieces still to come, see
 *                      [MealOverrideManager.countLoopSmb]. Never more than the pieces had left.
 * [extendedMs]       — how much later the mode ends than it was set to, because its food still came in, see
 *                      [MealOverrideManager.extendWhileFoodComes].
 * [eatDelayMs]       — the meal is eaten this long after the start ("Eat in"): pre-bolus 1 is given at the start so it
 *                      has time to act. The rest of pre-bolus 1 counts its window from [eatAtMs]. 0 = eaten at the start.
 * [preBolus2DroppedU], [preBolus3DroppedU] — what pieces led by BG left out ([MealOverrideManager.bgLedShare]). It is
 *                      not carried on to later pieces: if the food really comes, BG rises and the loop gives the rest.
 * [preBolus3PiecesGiven], [preBolus3GivenU], [preBolus3LastPieceMs] — the pieces of pre-bolus 3 given so far, their
 *                      insulin, and when the last one was given. Pre-bolus 3 is always given in pieces led by BG.
 * [preBolus3LoopU]   — SMBs the loop gave from the pre-bolus 3 time on, taken off its pieces still to come, see
 *                      [MealOverrideManager.countLoopSmbForPreBolus3]. Never more than the pieces had left.
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
    val extendedMs:       Long    = 0L,
    val eatDelayMs:       Long    = 0L,
    val preBolus2DroppedU: Double = 0.0,
    val preBolus3PiecesGiven: Int = 0,
    val preBolus3GivenU:  Double  = 0.0,
    val preBolus3LastPieceMs: Long? = null,
    val preBolus3DroppedU: Double = 0.0,
    val preBolus3LoopU:   Double  = 0.0
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

    /**
     * What the pieces still have to give: pre-bolus 2 less the pieces given, the loop's SMBs taken off them, and what
     * pieces led by BG left out.
     */
    val preBolus2LeftU: Double
        get() = (preBolus2U - preBolus2GivenU - preBolus2LoopU - preBolus2DroppedU).coerceAtLeast(0.0)

    /**
     * The pieces of pre-bolus 2 are led by BG from here on, see [MealOverrideManager.bgLedShare]: the first
     * [MealOverrideManager.ON_TIME_SHARE] of it is in.
     */
    val preBolus2BgLed: Boolean
        get() = preBolus2Spread &&
            preBolus2GivenU + preBolus2LoopU + preBolus2DroppedU >= preBolus2U * MealOverrideManager.ON_TIME_SHARE - ROUNDING_U

    /** When the next piece may be given: at the pre-bolus 2 time, then [MealOverrideManager.SPREAD_GAP_MIN] after the last one. */
    val preBolus2NextAtMs: Long
        get() = preBolus2LastPieceMs?.let { it + MealOverrideManager.SPREAD_GAP_MIN * 60_000L } ?: preBolus2FireAtMs

    /**
     * The next piece: what is left shared over the pieces left, see [MealOverrideManager.nextPieceU]. A piece that waits
     * for the gates stays the same size, so pieces never add up. When the loop has given part of it, fewer pieces share
     * what is left.
     */
    val preBolus2NextPieceU: Double
        get() = MealOverrideManager.nextPieceU(preBolus2LeftU, preBolus2Pieces - preBolus2PiecesGiven)

    /** Pre-bolus 3 is always given in pieces led by BG, as many as fit pieces of [MealOverrideManager.SPREAD_MIN_PIECE_U]. */
    val preBolus3Pieces: Int
        get() = (preBolus3U / MealOverrideManager.SPREAD_MIN_PIECE_U + ROUNDING_U).toInt().coerceIn(1, MealOverrideManager.SPREAD_PIECES)

    /**
     * What the pieces of pre-bolus 3 still have to give: less what was given, the loop's SMBs taken off them, and what
     * pieces led by BG left out.
     */
    val preBolus3LeftU: Double
        get() = (preBolus3U - preBolus3GivenU - preBolus3LoopU - preBolus3DroppedU).coerceAtLeast(0.0)

    val preBolus3WaitingU: Double
        get() = if (preBolus3Pending) preBolus3LeftU else 0.0

    /** The next piece of pre-bolus 3, see [MealOverrideManager.nextPieceU]. */
    val preBolus3NextPieceU: Double
        get() = MealOverrideManager.nextPieceU(preBolus3LeftU, preBolus3Pieces - preBolus3PiecesGiven)

    /** When the next piece of pre-bolus 3 may be given: at its time, then [MealOverrideManager.SPREAD_GAP_MIN] after the last one. */
    val preBolus3NextAtMs: Long?
        get() = preBolus3LastPieceMs?.let { it + MealOverrideManager.SPREAD_GAP_MIN * 60_000L } ?: preBolus3FireAtMs

    /**
     * The fat and protein insulin pre-bolus 2 and 3 gave so far, for the limit on the loop's top-ups. Pre-bolus 2 once
     * done counts as planned less what pieces led by BG left out, as before pieces could leave any out.
     */
    val fatProteinPreBolusGivenU: Double
        get() = (if (preBolus2FiredSuccessfully) preBolus2U - preBolus2DroppedU else preBolus2GivenU) + preBolus3GivenU

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

    /** When the meal is eaten: the start, or later with "Eat in". */
    val eatAtMs: Long get() = triggerTimeMs + eatDelayMs

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
            (if (preBolus2FiredMs != -1L) preBolus2U - preBolus2DroppedU else preBolus2GivenU) +
            (if (preBolus3FiredMs != -1L) preBolus3U - preBolus3DroppedU else preBolus3GivenU)

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
