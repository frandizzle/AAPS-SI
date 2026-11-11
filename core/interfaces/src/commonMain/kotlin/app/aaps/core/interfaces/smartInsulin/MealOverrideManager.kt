package app.aaps.core.interfaces.smartInsulin

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.profile.Profile
import kotlin.math.floor

/**
 * Interface for the meal override system.
 * Lives in core/interfaces so both plugins/aps and plugins/automation can inject it.
 * Implementation: MealOverrideManagerImpl in plugins/aps
 */
interface MealOverrideManager {

    /** Active meal mode — returns override if not expired, else null */
    val activeMealMode: MealMode?

    /** The running meal mode with its pre-boluses, or null when no mode is active. */
    val activeState: MealOverrideState?

    /** Pre-bolus 1 dose delivered when this meal mode was activated — null if none or not via SmartMeal */
    val activeDoseU: Double?
    /** Non-null and > 0 when PB2 has been delivered this session — shows the delivered amount */
    val activePb2DoseU: Double?
    /** Non-null and > 0 when PB3 has been delivered this session — shows the delivered amount */
    val activePb3DoseU: Double?

    /** ISF multiplier for current loop cycle — 1.0 if no override active */
    val activeIsfMultiplier: Double

    /** True if DURA_ISF is enabled for the currently active meal mode */
    val activeDuraEnabled: Boolean

    /** DURA_ISF floor (mg/dL/U) for the currently active meal mode — 0.0 if not set/no override active */
    val activeDuraFloorMgdl: Double

    /** DURA_ISF strength (ramp weight) for the currently active meal mode — 1.0 default, 0 = no effect */
    val activeDuraStrength: Double

    /** Milliseconds remaining in the active mode window, or 0 if no override active */
    val modeTimeRemainingMs: Long

    /** Timestamp (ms) when the current override was activated, or 0 if no override active.
     *  Used by auto-cancel logic to protect the early-meal window from premature cancellation. */
    val modeStartMs: Long

    /** True while the rest of pre-bolus 1 waits for BG to rise. See [PB1_REST_FROM_MIN]. */
    val preBolus1RestPending: Boolean

    /** Status of the rest of pre-bolus 1, for the dialog and the SI tab; "" when there is none. */
    val preBolus1RestStatusText: String

    /** True if pre-bolus 2 is pending delivery (scheduled but not yet fired) */
    val preBolus2Pending: Boolean

    /** True if pre-bolus 3 is pending delivery (scheduled but not yet fired) */
    val preBolus3Pending: Boolean

    /** Human-readable status of pre-bolus 2 for display in dialog and tab UI.
     *  Examples: "PB2: 18min", "PB2: waiting — BG below target (5.1 <= 5.5mmol)",
     *            "PB2: delivered 14:32", "PB2: cancelled", "" if not scheduled */
    val preBolus2StatusText: String

    /** Human-readable status of pre-bolus 3 for display in dialog and tab UI. */
    val preBolus3StatusText: String

    /** Seconds until pre-bolus 2 fire time (negative = overdue, waiting on safety checks) */
    val preBolus2SecondsRemaining: Long?

    /** Seconds until pre-bolus 3 fire time. Returns null if PB2 not yet fired. */
    val preBolus3SecondsRemaining: Long?

    /**
     * Starts a meal mode now.
     *
     * @param eatDelayMs the meal is eaten this long after now ("Eat in"), see [MealOverrideState.eatDelayMs]. The caller
     *                   adds it to [modeWindowMs] and [preBolus2DelayMs] itself.
     */
    fun activateOverride(
        mode:             MealMode,
        doseU:            Double?,
        carbsG:           Int,
        modeWindowMs:     Long   = DEFAULT_MODE_WINDOW_MS,
        preBolus2U:       Double = 0.0,
        preBolus2DelayMs: Long   = 0L,
        preBolus3U:       Double = 0.0,
        preBolus3DelayMs: Long   = 0L,
        duraEnabled:      Boolean = false,
        duraFloorMgdl:    Double  = 0.0,
        duraStrength:     Double  = 1.0,
        eatDelayMs:       Long    = 0L
    )

    fun cancelOverride()

    /**
     * Start Protein/Fat (UAM) when the running mode runs out by itself. Call it right after
     * [activateOverride]. Ending the mode by hand does not start it.
     */
    fun followWithProteinFat()

    /**
     * True once, when a mode that was set to [followWithProteinFat] has just run out. The caller then
     * starts Protein/Fat.
     */
    fun takeProteinFatFollowOn(): Boolean

    /**
     * Gives the running mode [pct] percent less insulin, because it started soon after exercise. Call it right
     * after [activateOverride]; the pre-boluses are already cut by the caller. See `MealOverrideState.afterActivityPct`.
     */
    fun setAfterActivityCut(pct: Int)

    /**
     * Gives the running mode a rest of pre-bolus 1 of [restU] units: the part above the max pre-bolus. Call it
     * right after [activateOverride]. It fires from [PB1_REST_FROM_MIN] to [PB1_REST_UNTIL_MIN] minutes after
     * the start, only once BG rises, and only within the same IOB and loop limits as pre-bolus 2.
     */
    fun setPreBolus1Rest(restU: Double)

    /**
     * Gives pre-bolus 2 as [pieces] smaller doses, [SPREAD_GAP_MIN] apart from the pre-bolus 2 time, each only through
     * the safety gates. It is a budget for the first wave of fat and protein: the first piece comes sooner if BG shows
     * the food coming in, and SMBs the loop gives in the meal come off the pieces, see [countLoopSmb]. Pre-bolus 3 stays
     * its own dose, timed from when the pieces are done, for the fat and protein that come in hours later. Call it right
     * after [activateOverride]. For a meal of fat and protein with almost no carbs: it raises BG slowly over hours, so
     * one large dose can work before the food does. See [spreadPieces].
     */
    fun spreadPreBoluses(pieces: Int)

    /**
     * The SMBs the loop has given since the running meal started, [smbU] in all. With pre-bolus 2 in pieces they come off
     * the pieces still to come, so the loop and the pieces never both give insulin for the same fat and protein: the
     * loop gives it sooner when BG shows the food, and the pieces give what is left on time. Once the loop has given it
     * all, pre-bolus 2 is done. Call it every loop cycle, before [onLoopCycle].
     */
    fun countLoopSmb(smbU: Double)

    /**
     * Keeps the running meal mode going while its food still comes in. When the mode ends within [EXTEND_CHECK_MIN]
     * minutes and BG still rises, or its food list still has food to come, it ends [EXTEND_STEP_MIN] minutes from now.
     * At most [EXTEND_MAX_MIN] minutes more in all, and never longer than [MAX_MODE_MIN] from the start. Without this the
     * mode can end in the middle of a late rise from its own food, and UAM then starts a new meal on that rise. Only for
     * a meal the user started, not a UAM mode. Call it every loop cycle, before the meal mode is read.
     *
     * @param shortAvgDeltaMgdl the BG change per 5 min over the last 15 min
     * @param foodLeftG the meal's food still to come from its food list, or null without one
     * @return why the mode was made longer, or null when it was not
     */
    fun extendWhileFoodComes(nowMs: Long, shortAvgDeltaMgdl: Double, foodLeftG: Double?): Extension?

    /**
     * The user changed how much of the running meal's food was eaten: [carbsFactor] of its carbs and [fatProteinFactor]
     * of its fat and protein are left, for example 0.75 for three quarters. The pre-boluses still waiting change the
     * same way, since they were planned for the food as it was: the rest of pre-bolus 1 with the carbs, pre-bolus 2
     * and 3 with the fat and protein. Insulin already given stays as it is. A pre-bolus that grows never goes past
     * [maxPreBolusU], or past its own size if that was already more.
     */
    fun scaleWaitingPreBoluses(carbsFactor: Double, fatProteinFactor: Double, maxPreBolusU: Double)

    /** Why a meal mode was made longer, see [extendWhileFoodComes]. */
    enum class Extension { BG_RISING, FOOD_LEFT }

    /**
     * Insulin a pre-bolus started to give in the last [onLoopCycle], 0 when none fired. The bolus is not in IOB yet, so
     * the loop gives no SMB and no temp above basal in that same cycle: it would dose as if the pre-bolus were not there.
     */
    val preBolusThisCycleU: Double

    /** Cancels the rest of pre-bolus 1 if it still waits. */
    fun cancelPreBolus1Rest()

    /**
     * The pump gave [deliveredU] of pre-bolus 1, for example because the bolus was stopped. When that is less than
     * the running mode thinks it gave, the mode keeps the real amount, and the planned one beside it.
     */
    fun preBolus1Delivered(deliveredU: Double)

    /**
     * Adds food eaten later to the running meal mode, for example a dessert. Pre-bolus 2 and 3 only
     * grow while they still wait; one that has fired or was cancelled stays as it is, and the loop
     * covers the rest. The mode never gets shorter, and DURA and Protein/Fat are only turned on, never off.
     *
     * @param addedU         insulin given now for the added food (the caller delivers it)
     * @param preBolus2AddU  added to pre-bolus 2 if it still waits
     * @param preBolus3AddU  added to pre-bolus 3 if it still waits
     * @param modeEndMs      the mode runs at least until this time
     * @param duraFloorMgdl  used only when DURA was off and is turned on now
     * @param duraStrength   used only when DURA was off and is turned on now
     * @return false when no meal mode is running, so nothing was changed
     */
    fun addToMeal(
        addedU:        Double,
        preBolus2AddU: Double,
        preBolus3AddU: Double,
        modeEndMs:     Long,
        duraEnabled:   Boolean,
        duraFloorMgdl: Double,
        duraStrength:  Double,
        pfAfter:       Boolean
    ): Boolean

    /**
     * Cancel a pending pre-bolus 2 without cancelling the meal mode itself.
     * Also cancels PB3 if pending, as PB3 timing is relative to PB2 firing.
     */
    fun cancelPreBolus2()

    /**
     * Cancel a pending pre-bolus 3 without cancelling the meal mode itself.
     */
    fun cancelPreBolus3()

    /**
     * Called every loop cycle from SmartInsulinPlugin.invoke().
     * Checks if pre-bolus 2/3 is due, runs safety checks, fires if safe.
     */
    /**
     * @param loopRestraining true when the main loop is actively holding insulin back — inside a
     *   post-low rebound window, or, outside a meal mode, its last decision was a zero temp. A
     *   pre-bolus scheduled earlier carries no knowledge of what has happened since, so without this
     *   it can land as a fixed dose at the moment the loop has decided to deliver nothing. In a meal
     *   mode a zero temp does not count: the loop's prediction sees only the insulin, not the food
     *   still coming, so after any real pre-bolus it predicts a low and would hold pre-bolus 2 and 3
     *   nearly every time. Nor does a rebound window after a shallow dip with no food eaten since:
     *   in a meal mode that dip is usually the gap before the food comes in. BG falling is checked by
     *   the pre-bolus itself.
     */
    fun onLoopCycle(
        glucoseStatus:   GlucoseStatus,
        iobArray:        Array<IobTotal>,
        maxIobU:         Double,
        profile:         Profile? = null,
        loopRestraining: Boolean  = false
    )

    data class Pb2GateData(
        val isMmol:            Boolean,
        val profileTargetMgdl: Double,
        val bgMgdl:            Double,
        val maxIobU:           Double,
        val iobU:              Double,
        val deltaMgdl:         Double,
        val shortAvgDeltaMgdl: Double,
        /** The pre-bolus waiting, so IOB after it can be shown against max IOB. */
        val doseU:             Double  = 0.0,
        val loopRestraining:   Boolean = false,
        /** The waiting piece is led by BG, see [bgLedShare]: BG must be clearly above target, or above it and rising. */
        val bgLed:             Boolean = false
    )

    /** Last known safety-gate values for PB2; null if no active session or no gate check run yet */
    val pb2GateData: Pb2GateData?

    /** Last known safety-gate values for PB3; null if no active session or PB3 never pending */
    val pb3GateData: Pb2GateData?

    companion object {
        const val MIN_BG_FOR_PB2_MGDL        = 90.0   // ~5.0 mmol — don't fire PB2 if below this
        const val MAX_IOB_HEADROOM_RATIO      = 0.75   // IOB must be < 75% of maxIob to allow PB2
        // Delta safety: block PB2 if BG is falling on either last reading or sustained trend
        const val DELTA_INSTANT_BLOCK_MGDL    = -2.0   // single reading: -2 mg/dL = ~-0.11 mmol/5min
        const val SHORT_AVG_DELTA_BLOCK_MGDL  = -3.0   // avg of last 3: -3 mg/dL = ~-0.17 mmol/5min
        const val DEFAULT_MODE_WINDOW_MS  = 3 * 60 * 60 * 1000L

        /** A meal mode lasts at most this long: after that the loop's own fasting settings handle what is left. */
        const val MAX_MODE_MIN = 420

        // A meal mode made longer while its food still comes in, see extendWhileFoodComes.
        /** It is checked when it ends within this many minutes: two loop cycles, so one missed cycle does not end it. */
        const val EXTEND_CHECK_MIN    = 10
        /** Each time it ends this many minutes from now. */
        const val EXTEND_STEP_MIN     = 15
        /** At most this many minutes more in all. */
        const val EXTEND_MAX_MIN      = 90
        /** BG counts as still rising from this change per 5 min over the last 15 min, in mg/dL (0.1 mmol). */
        const val EXTEND_RISE_MGDL    = 1.8
        /** The food list counts as still coming in above this many grams. */
        const val EXTEND_FOOD_LEFT_G  = 5.0

        // Pre-bolus 1 rest: the part of pre-bolus 1 above the max pre-bolus. It is given once BG shows the
        // food coming in, not before [PB1_REST_FROM_MIN] minutes after the start, and dropped after
        // [PB1_REST_UNTIL_MIN]: by then pre-bolus 2 and the loop take over.
        const val PB1_REST_FROM_MIN  = 15
        const val PB1_REST_UNTIL_MIN = 45

        /** A meal with fewer carbs than this gives pre-bolus 2 in pieces. */
        const val SPREAD_MAX_CARBS_G = 10.0
        /** At most this many pieces, and none smaller than [SPREAD_MIN_PIECE_U]. */
        const val SPREAD_PIECES      = 6
        const val SPREAD_MIN_PIECE_U = 0.3
        /**
         * Minutes between pieces. Fast insulin needs about 15 to 20 min to show on BG, so by then the gates see a little
         * of the last piece; closer pieces would make the "not falling" gate check almost nothing.
         */
        const val SPREAD_GAP_MIN     = 20
        /** Pieces are rounded down to this, a bolus step most pumps can give. The last piece is what is left. */
        const val SPREAD_STEP_U      = 0.05

        /**
         * How many pieces pre-bolus 2 is given as, or 0 for the usual dose: only for a meal with almost no carbs, and only
         * when the insulin is enough for 2 pieces of at least [SPREAD_MIN_PIECE_U].
         *
         * @param carbsG the meal's carbs, from its food list
         * @param fatProteinU pre-bolus 2
         */
        fun spreadPieces(carbsG: Double, fatProteinU: Double): Int {
            if (carbsG >= SPREAD_MAX_CARBS_G) return 0
            val pieces = minOf(SPREAD_PIECES, (fatProteinU / SPREAD_MIN_PIECE_U + 1e-9).toInt())
            return if (pieces >= 2) pieces else 0
        }
        /** BG counts as rising from this change per 5 min, in mg/dL (about 0.1 mmol), with the 15 min average also up. */
        const val PB1_REST_RISE_MGDL = 2.0
        /** A smaller rest is left to the loop. */
        const val PB1_REST_MIN_U     = 0.5

        // Pieces led by BG: the second half of pre-bolus 2 in pieces, and all of pre-bolus 3. See [bgLedShare].
        /** Pre-bolus 2 in pieces gives this share on time; the pieces after it are led by BG. */
        const val ON_TIME_SHARE           = 0.5
        /** At least this far above target, in mg/dL (1 mmol/L), BG itself shows that more insulin is wanted. */
        const val CLEARLY_ABOVE_TARGET_MGDL = 18.0
        /** From this far above target, in mg/dL (3 mmol/L), a piece led by BG is given in full. */
        const val FULL_ABOVE_TARGET_MGDL  = 54.0
        /** Closer to target, a rise of at least this much in 5 min, in mg/dL (0.1 mmol/L), asks for a piece. */
        const val RISING_MGDL             = 1.8
        /** The share of a piece led by BG given where BG first asks for it. */
        const val BG_LED_MIN_SHARE        = 0.5

        /**
         * The share of a piece led by BG to give now, or null when BG does not ask for it. At the tail of a meal the fat and
         * protein are least sure: a light lunch stopped coming in early, a heavy dinner kept coming for hours. So BG leads:
         * no piece near target and flat, [BG_LED_MIN_SHARE] of it from [CLEARLY_ABOVE_TARGET_MGDL] above target (or above
         * target and rising), all of it from [FULL_ABOVE_TARGET_MGDL] above. 11 Nov: a 0.7 U piece at 5.8 with a 5.5 target
         * and a 4.4 U pre-bolus 3 were waiting 4.5 hours into a dinner while BG drifted down.
         */
        fun bgLedShare(bgMgdl: Double, targetMgdl: Double, deltaMgdl: Double): Double? {
            val above = bgMgdl - targetMgdl
            if (above < CLEARLY_ABOVE_TARGET_MGDL && !(above > 0.0 && deltaMgdl >= RISING_MGDL)) return null
            val part = ((above - CLEARLY_ABOVE_TARGET_MGDL) / (FULL_ABOVE_TARGET_MGDL - CLEARLY_ABOVE_TARGET_MGDL)).coerceIn(0.0, 1.0)
            return BG_LED_MIN_SHARE + (1.0 - BG_LED_MIN_SHARE) * part
        }

        /**
         * The next piece of [leftU] still to come over [piecesLeft] pieces: shared out, rounded down to [SPREAD_STEP_U], none
         * smaller than [SPREAD_MIN_PIECE_U]. The last piece is all that is left.
         */
        fun nextPieceU(leftU: Double, piecesLeft: Int): Double {
            val bigEnough = (leftU / SPREAD_MIN_PIECE_U + 1e-6).toInt().coerceAtLeast(1)
            val pieces = minOf(piecesLeft, bigEnough)
            if (pieces <= 1) return leftU
            return (floor(leftU / pieces / SPREAD_STEP_U + 1e-6) * SPREAD_STEP_U).coerceAtLeast(SPREAD_STEP_U).coerceAtMost(leftU)
        }
    }
}
