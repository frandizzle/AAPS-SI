package app.aaps.plugins.aps.smartInsulin

import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SetMeal
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.Icons
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.ui.CoreUiStrings
import androidx.compose.runtime.Composable
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.icons.IcPluginInsulin
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.aps.ApsStrings
import app.aaps.core.keys.StringNonKey

import android.content.Context
import android.content.Intent
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.plugins.aps.openAPSSMB.GlucoseStatusCalculatorSMB
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.utils.MidnightUtils
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.data.model.getPassedDurationToTimeInMinutes
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.target
import org.json.JSONObject
import dev.zacsweers.metro.Inject
import kotlin.math.roundToInt
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import kotlin.math.floor

@SingleIn(AppScope::class)
open class SmartInsulinPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    preferences: Preferences,
    notificationManager: NotificationManager,
    val rxBus: RxBus,
    private val config: Config,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val iobCobCalculator: IobCobCalculator,
    private val mealOverrideManager: MealOverrideManager,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val glucoseStatusCalculatorSMB: GlucoseStatusCalculatorSMB,
    private val persistenceLayer: PersistenceLayer,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val hardLimits: HardLimits,
    private val sp: SP,
    private val constraintsChecker: ConstraintsChecker,
    private val activePlugin: ActivePlugin,
    private val dateUtil: DateUtil,
    private val determineBasalSmartInsulin: DetermineBasalSmartInsulin,
    private val stftController: StftController,
    private val uamController: UamController,
    private val profileLearner: ProfileLearner,
    private val bolusCurveTracker: BolusCurveTracker,
    private val aggressionLearner: AggressionLearner,
    private val basalLearner: BasalLearner,
    private val circadianLearner: CircadianLearner,
    private val activityMonitor:  ActivityMonitor,
    private val cgmWarmupGuard:   CgmWarmupGuard,
    private val duraIsfTracker:   DuraIsfTracker,
    private val mealAbsorptionTracker:   MealAbsorptionTracker,
    private val mealAbsorptionCsvLogger: MealAbsorptionCsvLogger,
    private val modeIsfLearner:          ModeIsfLearner,
    private val uamEntryFractionLearner: UamEntryFractionLearner,
    private val unexplainedDropTracker:  UnexplainedDropTracker,
    private val secondWaveDetector:      SecondWaveDetector,
    private val carbEpisodeManager:      CarbEpisodeManager,
    private val activitySessionManager: ActivitySessionManager,
    private val activitySessionLearner: ActivitySessionLearner,
    private val duraStrengthLearner:     DuraStrengthLearner,
    private val phoneStepCounter:        PhoneStepCounter,
    private val ch:                      ConcentrationHelper,
    private val learningJournal:         LearningJournal
) : PluginBaseWithPreferences(
    PluginDescription()
        .mainType(PluginType.APS)
        .icon(IcPluginInsulin)
        .pluginName(ApsStrings.smart_insulin)
        .shortName(ApsStrings.smart_insulin_short)
        .preferencesVisibleInSimpleMode(false)
        .showInList { config.APS }
        .description(ApsStrings.smart_insulin_description)
        .composeContent { plugin ->
            object : ComposablePluginContent {
                @Composable
                override fun Render(
                    setToolbarConfig: (ToolbarConfig) -> Unit,
                    onNavigateBack: () -> Unit,
                    onSettings: (() -> Unit)?
                ) {
                    SmartInsulinScreen(
                        plugin = plugin as SmartInsulinPlugin,
                        onNavigateBack = onNavigateBack,
                        onSettings = onSettings,
                        setToolbarConfig = setToolbarConfig
                    )
                }
            }
        },
    ownPreferences = emptyList(),
    aapsLogger, rh, preferences, notificationManager
), APS, PluginConstraints, SmartInsulinOverview {

    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.SMB
    override var lastAPSResult: APSResult? = null

    /** Set when determine_basal throws, cleared on the next successful cycle. Surfaced on the SI
     *  tab so a crashing dosing core is visible rather than looking like an idle loop. */
    var lastRunError: String? = null
        private set

    var bgWentLow: Boolean = false
    var minBgDuringLow: Double = Double.MAX_VALUE
    var iobAtLowTime: Double = 0.0
    var shortAvgDeltaAtLow: Double = 0.0
    var secondLowOccurred: Boolean = false
    var softLandingBypass: Boolean = false
    var uamEntrySmbsDelivered: Int = 0
    var uamEntryModeStartMs: Long = 0L
    var learningDirtyUntilMs: Long = 0L
    /** COB as of the last loop cycle, for the SI tab — reading it fresh is suspend in 4.0. */
    @Volatile private var lastCobG = 0.0

    /** Mode activation the second-wave detector is currently following. */
    private var secondWaveModeStartMs: Long = 0L

    // -- Post-mode insulin attribution ---------------------------------------
    // What the last mode left on board when it ended, and what the loop has delivered since. A low
    // after a mode is charged in proportion (see ModeInsulinShare) rather than by the clock.
    private var modeIobAtEndU        = 0.0
    private var postModeDeliveredU   = 0.0
    private var postModeSinceMs      = 0L
    private var lastModeStartSeenMs  = 0L
    private var modeEndedMs          = 0L   // last cycle a mode was running - when it ended, once it has
    /** Keeps learning paused after activity stops while BG is still dropping - see ActivityTail. */
    private val activityTail = ActivityTail()
    private var lastIobSampleMs      = 0L
    private var nudgeDisplaySessionIsfMgdl: Double = 0.0
    private var nudgeDisplaySessionBasalU: Double = 0.0
    /**
     * The aggression nudge's own "was" snapshot, held at the hour bucket's CENTRE rather than at
     * the live minute.
     *
     * Separate from [nudgeDisplaySessionIsfMgdl]/[nudgeDisplaySessionBasalU] (which the FuelTrim
     * card uses) because the nudge card is hour-scoped — its copy says "at this hour" — and the
     * live read is interpolated across the hour boundary. Comparing two live reads taken at
     * different minutes reports the clock walking between buckets as if the nudge had done it: at
     * :00 the blend is an exact 50/50 of the two hours, so an hour whose neighbour sits higher
     * shows basal RISING under a headline that says insulin is being removed. Reading both ends at
     * :30 takes the clock out of it, and makes these numbers agree with the 24h table, which is
     * already drawn at TABLE_BUCKET_MINUTE.
     */
    private var nudgeDisplaySessionIsfBucketMult: Double = 1.0
    private var nudgeDisplaySessionBasBucketMult: Double = 1.0
    /** Hour the snapshot above belongs to — a new hour is a new bucket, so it needs a new baseline. */
    private var nudgeDisplaySessionHour: Int = -1
    // Tracks the last nudge direction so we only capture session-start baseline once
    // per nudge episode (not on every cycle). Without this, "was ISF" keeps updating
    // mid-nudge and the "was ? now" comparison loses meaning.
    private var lastSeenNudgeState: String = "INACTIVE"
    @Volatile private var cachedProfileIsf: Double = 0.0
    @Volatile private var cachedProfileBasal: Double = 0.0
    // The profile's own ISF (mg/dL/U) and basal (U/h) for each hour, read at the bucket centre. The
    // per-hour table and the journal need these: the current hour's values are wrong for any hour
    // where the profile itself is different.
    @Volatile private var cachedHourIsf = DoubleArray(24)
    @Volatile private var cachedHourBasal = DoubleArray(24)
    @Volatile private var cachedProfileTarget: Double = 99.0
    @Volatile private var cachedSensorInsertTimeMs: Long = 0L
    @Volatile private var sensorCacheRefreshedAtMs: Long = 0L
    @Volatile private var cachedHba1cAvgMgdl: Double = 0.0
    @Volatile private var cachedHba1cEstimate: Double = 0.0
    @Volatile private var cachedHba1cWindowHours: Int = 0
    @Volatile private var cachedHba1cRefreshedAtMs: Long = 0L
    var previousMealModeForLockout: MealMode = MealMode.FASTING
    private var lockoutTrackerInitialized: Boolean = false
    var reboundWindowStartMs: Long = 0L
    /** Tells a real second low apart from CGM flicker across the guard, and counts them. */
    internal val relowTracker = RecoveryRelowTracker()
    // Recovery (rebound) window length. Base comes from the user preference
    // (ApsSmartInsulinReboundWindowMins); each genuine re-low adds another base window (see
    // RecoveryRelowTracker), and each consecutive rollercoaster adds ROLLER_REBOUND_EXTENSION_MS,
    // capped at ROLLER_REBOUND_EXTENSION_MAX_MS. Computed live so preference changes AND
    // extensions take effect immediately, and so the gate (inReboundWindow) always matches
    // totalReboundWindowMins shown in the UI.
    val reboundGuardMs: Long
        get() = reboundWindowMs(
            baseMins = sp.getInt(IntKey.ApsSmartInsulinReboundWindowMins.key, IntKey.ApsSmartInsulinReboundWindowMins.defaultValue),
            relowCount = relowTracker.relowCount,
            rollercoasters = circadianLearner.consecutiveRollercoasters
        )

    @Volatile private var cachedCgmSuppressLearning: Boolean = false
    @Volatile private var cachedOverviewState: SmartInsulinOverview.OverviewState = SmartInsulinOverview.OverviewState("Meal: Fasting", null, null, "Learning")

    val msSinceLastSuspend: Long get() = if (reboundWindowStartMs > 0L) System.currentTimeMillis() - reboundWindowStartMs else Long.MAX_VALUE
    val inReboundWindow: Boolean get() = reboundWindowStartMs > 0L && bgWentLow && msSinceLastSuspend < reboundGuardMs

    companion object {
        const val REBOUND_GUARD_MS              = 60 * 60 * 1000L
        const val ROLLER_REBOUND_EXTENSION_MS   = 15 * 60 * 1000L
        const val ROLLER_REBOUND_EXTENSION_MAX_MS = 45 * 60 * 1000L
        const val UAM_EXIT_MAX_DELTA_MMOL       = 0.5
        const val SMB_DELIVERY_FRACTION = 0.5
        private const val SENSOR_CACHE_REFRESH_MS = 6 * 60 * 60 * 1000L
        private const val HBA1C_CACHE_REFRESH_MS  = 2 * 60 * 60 * 1000L
        // Bounds for the COMBINED basal multiplier (flat BasalLearner + CircadianLearner).
        // Pump safety multipliers in setTempBasal remain the hard backstop; tune if needed.
        // Per-hour table rows are read at the bucket centre so the table shows each hour's own
        // learned value, not the boundary-interpolated value applying at the current minute.
        private const val TABLE_BUCKET_MINUTE  = 30
        /** At least this much of the insulin since a mode ended is the mode's: its insulin is most of it. */
        private const val MEAL_INSULIN_DOMINANT_SHARE = 0.5
        private const val JOURNAL_MULT_STEP    = 0.005
        private const val JOURNAL_AGGR_STEP    = 0.02
        private val JOURNAL_DAYS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        // Column widths for the learned-per-mode tables. 18 fits the longest mode label,
        // "Protein/Fat (UAM)", with a space to spare.
        private const val LEARNER_LABEL_W     = 18
        private const val LEARNER_VALUE_W     = 11
        private const val LEARNER_TRAIL_W     = 9
        private const val UAM_LABEL_SUFFIX    = " (UAM)"
        private const val MIN_TOTAL_BASAL_MULT = 0.5
        private const val MAX_TOTAL_BASAL_MULT = 1.5

        /** Tolerance on the at-target comparison (mg/dL) — ~0.01 mmol, so a reading sitting
         *  exactly on target counts as having reached it rather than missing by a rounding step. */
        private const val AUTO_CANCEL_TARGET_EPSILON_MGDL = 0.18

        /**
         * How much of the gap between the low guard and target counts as "undershot" for the
         * episode-outcome learners — the lower half of the room a mode has to work in.
         *
         * A fixed offset above the guard can't work, because the two settings move independently:
         * a 4.0 guard with a 5.5 target has 1.5 mmol of room, while a 4.7 guard with the same
         * target has 0.8, and +1 mmol there would land at 5.7 — above target, where nothing is
         * wrong at all. Scaling keeps the band meaning the same thing at any pair of settings,
         * and by construction it can never reach target.
         */
        private const val UNDERSHOOT_BAND_FRACTION = 0.5

        /** Absolute cap on the band's width (1 mmol), so a high profile target can't stretch it
         *  up into BG that is simply normal. */
        private const val UNDERSHOOT_BAND_MAX_MGDL = 18.0

        /**
         * Upper edge of the undershoot band the episode-outcome learners score against: BG below
         * this but at or above the low guard means the mode pushed too far without producing a hypo.
         *
         * Both edges come from settings the user already tunes. [targetMgdl] should be the profile
         * target, not the effective one — the band describes what counts as too low for this person,
         * which shouldn't drift with a temporary exercise target. A target at or below the guard
         * leaves no room and yields an empty band, so only frank lows score.
         */
        internal fun undershootCeilingMgdl(lowGuardMgdl: Double, targetMgdl: Double): Double =
            (lowGuardMgdl + UNDERSHOOT_BAND_FRACTION * (targetMgdl - lowGuardMgdl))
                .coerceAtMost(lowGuardMgdl + UNDERSHOOT_BAND_MAX_MGDL)

        /**
         * Whether an active meal override should be auto-cancelled because BG has come back to
         * profile target.
         *
         * Scope is deliberate: this applies to the AUTO-DETECTED (UAM) modes only — including
         * Protein/Fat. Those modes are the loop's own guess that food is on board, so once BG is
         * back at target the guess has served its purpose and holding an aggressive mode ISF
         * against an at-target BG only risks driving through it.
         *
         * The manually-set meal modes (Breakfast, Lunch, Dinner, Low Carb, Extended) are NOT
         * cancelled here. The user declared those deliberately and knows what they ate; they run
         * for their configured duration and are the user's to cancel.
         */
        internal fun shouldAutoCancelAtTarget(mode: MealMode?, bgMgdl: Double, targetMgdl: Double): Boolean {
            if (mode == null || !mode.isUam) return false
            return bgMgdl <= targetMgdl + AUTO_CANCEL_TARGET_EPSILON_MGDL
        }
    }

    val isMmol: Boolean get() = profileUtil.units == GlucoseUnit.MMOL
    val profileIsfMgdl: Double get() = cachedProfileIsf
    val profileBasalU: Double get() = cachedProfileBasal
    private val unitLabel: String get() = if (isMmol) "mmol" else "mg/dL"
    private fun fmtBg(mgdl: Double): String = if (isMmol) String.format("%.1f", mgdl / 18.0) else String.format("%.0f", mgdl)
    private fun fmtDelta(mmol: Double): String = if (isMmol) String.format("%+.2f", mmol) else String.format("%+.1f", mmol * 18.0)
    private fun fmtIsf(mgdl: Double): String = if (isMmol) String.format("%.2f", mgdl / 18.0) else String.format("%.0f", mgdl)

    /**
     * Basal multiplier — now sourced ENTIRELY from CircadianLearner's per-hour basal signals.
     *
     * Previously this combined the per-hour CircadianLearner multiplier with the OLD flat
     * (non-hour-aware) BasalLearner multiplier, summing their deviations from 1.0. Both
     * learners were estimating the same underlying fasting-drift signal, so running them
     * together double-counted it — and because the flat learner produces a single global
     * value with no per-hour resolution, its contribution dominated and flattened out the
     * genuine per-hour shape CircadianLearner had learned (visible in the SI tab table as
     * most hours collapsing toward the same Bas× value).
     *
     * CircadianLearner's basal learning is now far more capable than the old flat learner —
     * five distinct signals (drift, negIOB, predTrim, cyclic-delta, sub-target), SMB-aware
     * total-IOB gating, and genuine 24×7 per-hour/per-day resolution — so it fully supersedes
     * the old flat learner's drift-only approach. The flat BasalLearner instance is left in
     * place (still resettable, still toggleable in settings) but no longer contributes here;
     * its onLoopCycle is also no longer invoked (see below) so it doesn't silently accumulate
     * stale state in the background.
     */
    private fun combinedBasalMultiplier(
        hour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
        dow: Int  = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1,
        // Minute matters because the circadian buckets are interpolated across the hour
        // boundary. The per-hour TABLE wants a whole-hour value (:30 = the bucket's own centre,
        // no neighbour bleed), so it passes 30 explicitly; live dosing passes the real minute.
        minute: Int = java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE)
    ): Double {
        return circadianLearner.basalMultiplier(hour, dow, minute)
            .coerceIn(MIN_TOTAL_BASAL_MULT, MAX_TOTAL_BASAL_MULT)
    }

    fun resetAllLearners() {
        aggressionLearner.reset(); basalLearner.reset(); circadianLearner.reset(); profileLearner.resetProfiles()
        modeIsfLearner.reset(); uamEntryFractionLearner.reset(); duraStrengthLearner.reset()
        bgWentLow = false; reboundWindowStartMs = 0L; learningDirtyUntilMs = 0L
        previousMealModeForLockout = MealMode.FASTING; minBgDuringLow = Double.MAX_VALUE
        iobAtLowTime = 0.0; shortAvgDeltaAtLow = 0.0; secondLowOccurred = false
        softLandingBypass = false; uamEntrySmbsDelivered = 0; uamEntryModeStartMs = 0L
        relowTracker.reset()
        learningJournal.note("Reset", "All learners reset")
    }

    fun resetAggression() { aggressionLearner.reset(); aggressionLearner.recalculate(); learningJournal.note("Reset", "Aggressiveness score reset to 1.0") }
    fun resetIsf() { circadianLearner.resetIsf(); learningJournal.note("Reset", "Circadian ISF reset to 1.0") }
    fun resetBasal() { basalLearner.reset(); circadianLearner.resetBasal(); learningJournal.note("Reset", "Basal learning reset to 1.0") }
    fun resetCircadian() { circadianLearner.reset(); learningJournal.note("Reset", "All circadian learning reset") }
    fun resetProfiles() { profileLearner.resetProfiles(); learningJournal.note("Reset", "Insulin profiles reset to defaults") }
    fun resetModeLearners() {
        modeIsfLearner.reset(); uamEntryFractionLearner.reset(); duraStrengthLearner.reset()
        learningJournal.note("Reset", "Meal/UAM ISF, UAM entry fraction and DURA learning reset")
    }

    // -- Activity/stress sessions (golf, gym) ---------------------------------

    /** Starts [label] if nothing is running, stops it if that label is. Returns true if now running. */
    fun toggleActivitySession(label: SessionLabel): Boolean {
        val now = dateUtil.now()
        val running = activitySessionManager.active
        return when {
            running?.label == label -> { activitySessionManager.stop(now); false }
            running != null         -> { activitySessionManager.stop(now); activitySessionManager.start(label, now); true }
            else                    -> {
                // A P/F or UAM mode already running was started on the assumption there was food.
                // Declaring a session says there wasn't, so it goes.
                if (mealOverrideManager.activeMealMode?.isUam == true) mealOverrideManager.cancelOverride()
                activitySessionManager.start(label, now); true
            }
        }
    }

    fun activitySessionLabel(): SessionLabel? = activitySessionManager.active?.label
    fun activitySessionStatus(): String? = activitySessionManager.statusLine(dateUtil.now())
    /** label, learned ISF multiplier, learned washout minutes, sessions learned from. */
    fun activitySessionLearned(): List<Triple<SessionLabel, Pair<Double, Int>, Int>> = activitySessionLearner.rows()
    fun activitySessionLastOutcome(): String = activitySessionLearner.lastOutcome

    /** Plain-English version of what the bias dial is currently doing, for the SI tab. */
    private fun learningBiasSummary(): String {
        val b = LearningBias.from(sp)
        val addPct  = ((b.strengthenScale - 1.0) * 100).roundToInt()
        val barPct  = ((1.0 - b.barScale) * 100).roundToInt()
        val safePct = ((b.safetyScale - 1.0) * 100).roundToInt()
        return buildString {
            append(if (addPct >= 0) "insulin-adding steps +$addPct%" else "insulin-adding steps $addPct%")
            append(if (barPct >= 0) ", spike/stall bars ${barPct}% lower" else ", spike/stall bars ${-barPct}% higher")
            if (safePct > 0) append(", corrections after a low +$safePct%")
        }
    }

    /** How long after a mode ends its insulin can still be blamed for a low — the learned DIA for
     *  the mode that just ran, railed so a wild learned value can't make it silly either way. */
    private fun postModeWatchMs(): Long {
        val mode = mealOverrideManager.activeMealMode ?: previousMealModeForLockout
        val diaMins = profileLearner.getProfile(mode).diaMinutes
        return (diaMins.coerceIn(105.0, 240.0) * 60_000L).toLong()
    }

    /** User switch for the per-meal/UAM ISF learner. Off freezes it; what it learned is still dosed from. */
    fun modeIsfLearningEnabled(): Boolean =
        sp.getBoolean(BooleanKey.ApsSmartInsulinModeIsfLearningEnabled.key, BooleanKey.ApsSmartInsulinModeIsfLearningEnabled.defaultValue)

    /** User switch for the DURA strength learner. Off freezes it; what it learned is still applied. */
    fun duraLearningEnabled(): Boolean =
        sp.getBoolean(BooleanKey.ApsSmartInsulinDuraLearningEnabled.key, BooleanKey.ApsSmartInsulinDuraLearningEnabled.defaultValue)

    /** Carb episode line for the SI tab, or null when no carbs are on board. */
    fun carbEpisodeStatus(): String? =
        carbEpisodeManager.statusLine(lastCobG, dateUtil.now())

    /** Set while a second wave has been detected in the running episode — surfaced on the SI tab
     *  so this detector can be eyeballed on real data before it is trusted quietly. */
    fun secondWaveNote(): String? = secondWaveDetector.description.takeIf { it.isNotEmpty() }

    data class FragmentData(
        val hour: Int, val dayLabel: String, val mealMode: String, val modeRemMins: Int?,
        val aggressiveness: Double, val circCeil: Double, val isfMultiplier: Double,
        val nudgeSessionIsfMgdl: Double, val nudgeSessionBasalU: Double, val profileIsfMgdl: Double,
        // Hour-scoped pair for the aggression-nudge card: session baseline and current value, both
        // read at the bucket centre so neither carries hour-boundary interpolation.
        val nudgeSessionIsfBucketMult: Double, val nudgeSessionBasBucketMult: Double,
        val bucketIsfMultiplier: Double, val bucketBasalMultiplier: Double,
        val finalIsfMgdl: Double, val basalMultiplier: Double, val profileBasalU: Double, val finalBasalU: Double,
        val currentBgMgdl: Double, val profileTargetMgdl: Double, val lastBasalSignal: String,
        val lastAggrNudgeStatus: String, val lastAccelDebug: String, val lastPredTrimDebug: String,
        val inReboundWindow: Boolean, val reboundMins: Long, val reboundWindowMins: Int,
        val totalReboundWindowMins: Int, val consecutiveRollercoasters: Int, val hardLowPenaltyActive: Boolean,
        val softLandingBypass: Boolean, val bgWentLow: Boolean, val secondLowOccurred: Boolean,
        /** Genuine re-lows this episode — each one added another base window. */
        val relowCount: Int,
        val minBgDuringLow: Double, val iobAtLowTime: Double, val isMmol: Boolean,
        val learningState: String, val activityLevel: String, val avgHrBpm: Int, val steps5min: Int,
        /** Age of the newest steps record from the watch, or null if there is none in range. */
        val stepsAgeMs: Long?,
        val phoneSteps5min: Int, val stepsFromPhone: Boolean,
        val phoneStepState: PhoneStepCounter.State,
        val restingHrBpm: Double, val postMealLockoutMins: Long, val cgmWarmup: Boolean,
        val stftStatus: String?, val stftActive: Boolean, val uamStatusLine: String?, val uamDebug: String,
        val fuelTrimStrength: Double, val trimActive: Boolean, val trimMins: Long,
        val profileLearningStatus: String, val circadianRawStatus: String,
        val profilesRawStatus: String, val tirRawLine: String, val avgBgMgdl24h: Double,
        val estimatedHba1c: Double, val bgWindowHours: Int, val activeDoseU: Double?,
        val activePb2DoseU: Double?, val activePb3DoseU: Double?, val pb2Status: String, val pb3Status: String,
        val pb2GateData: MealOverrideManager.Pb2GateData?,
        val pb3GateData: MealOverrideManager.Pb2GateData?,
        val lowGuardMgdl: Double,
        val lastCycleSummary: String,
        /** Which earlier hours the last hard low was charged back to — see
         *  CircadianLearner.retroAttributeHardLow. */
        val lastRetroAttribution: String,
        val lastRunError: String?,
        val mealAbsorptionLog: String,
        val mealAbsorptionInProgress: String,
        val modeIsfLearnerStatus: String,
        val uamEntryFractionStatus: String,
        val duraStrengthStatus: String
    )

    fun fragmentData(): FragmentData {
        // The tab ticks every 10s while the loop recomputes every 5min, so without this the steps
        // and activity level on screen could be a full cycle behind the watch. Non-blocking — this
        // runs on the UI thread.
        activityMonitor.requestRefresh(dateUtil.now(), sp.getDouble(DoubleKey.ApsSmartInsulinRestingHrBpm.key, DoubleKey.ApsSmartInsulinRestingHrBpm.defaultValue))
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1
        val day = DayOfWeekCircadianState.DAY_LABELS[dow.coerceIn(0, 6)]
        val profileIsf = cachedProfileIsf
        val profileBasal = cachedProfileBasal
        val isfMult = circadianLearner.isfMultiplier(hour)
        val basalMult = combinedBasalMultiplier(hour)
        val activeMode = mealOverrideManager.activeMealMode
        val currentMealMode = activeMode ?: MealMode.FASTING
        val isLearningEnabled = sp.getBoolean(BooleanKey.ApsSmartInsulinEnableLearning.key, BooleanKey.ApsSmartInsulinEnableLearning.defaultValue)
        val nowMs = System.currentTimeMillis()
        val tbrStep = activePlugin.activePump.pumpDescription.tempAbsoluteStep.takeIf { it > 0.0 } ?: 0.05
        val rawFinalBasal = profileBasal * basalMult
        val roundedFinalBasal = Math.round(rawFinalBasal / tbrStep) * tbrStep

        // Same table the day-picker uses — one builder, marked at the current hour.
        val circRaw = circadianDataForDay(markerHour = hour)

        val profRaw = buildString {
            MealMode.entries.forEach { mode ->
                val p = profileLearner.getProfile(mode)
                appendLine("${mode.label.padEnd(16)}: peak=${p.peakMinutes.toInt()}m  dia=${p.diaMinutes.toInt()}m  n=${p.sampleCount}")
            }
        }

        val postMealLeft = if (learningDirtyUntilMs > 0L && nowMs < learningDirtyUntilMs) (learningDirtyUntilMs - nowMs) / 60_000L else 0L

        val dateFmt = java.text.SimpleDateFormat("dd/MM/yy", java.util.Locale.US)
        // Tab shows only the last 2 days — the tracker keeps 5 days in memory (for future
        // meal-shape learning) and the CSV file keeps everything permanently.
        val displayCutoffMs = nowMs - 2L * 24 * 60 * 60 * 1000
        val mealAbsorptionRaw = buildString {
            mealAbsorptionTracker.history.filter { it.startMs >= displayCutoffMs }.asReversed().forEach { e ->
                val durH = e.durationMs / 3_600_000
                val durM = (e.durationMs / 60_000) % 60
                appendLine(
                    "${dateFmt.format(java.util.Date(e.startMs)).padEnd(9)} ${e.mode.label.padEnd(20)} " +
                        "${"${durH}h${durM.toString().padStart(2, '0')}m".padEnd(10)} ${"%.0f".format(e.estimatedGrams)}g"
                )
            }
        }

        val mealAbsorptionInProgressText = mealAbsorptionTracker.inProgress?.let { p ->
            val elapsedMs = (nowMs - p.startMs).coerceAtLeast(0L)
            val elapsedH = elapsedMs / 3_600_000
            val elapsedM = (elapsedMs / 60_000) % 60
            "${p.mode.label} — ${elapsedH}h${elapsedM.toString().padStart(2, '0')}m so far — ~${"%.0f".format(p.estimatedGramsSoFar)}g estimated"
        } ?: ""

        // Learned per-mode ISF: show the configured base and what the learner turned it into,
        // so the adjustment is legible rather than an abstract multiplier. Modes with an
        // override set show base→effective; modes running on profile ISF show the multiplier
        // alone (their base varies hour to hour with circadian learning).
        // P/F expands into one row per ISF window; every other mode stays a single row.
        //
        // Only windows that are actually configured are listed. pfWindowFor sends an hour to BASE
        // whenever its window has no override set, so an unconfigured window can never be written
        // to again — and after the pre-split migration seeds every slot alike, listing them would
        // leave dead rows frozen at the migrated value forever. BASE is always listed: it is where
        // every unconfigured hour doses from.
        val learnerScopes: List<Pair<MealMode, PfWindow>> =
            MealMode.entries.filter { it != MealMode.FASTING }.flatMap { mode ->
                if (mode == MealMode.UAM_PROTEIN_FAT)
                    PfWindow.PF_WINDOWS.filter { it == PfWindow.BASE || pfIsfForWindow(it) > 0.0 }
                        .map { mode to it }
                else listOf(mode to PfWindow.NONE)
            }

        val modeIsfLearnerRaw = buildString {
            var rows = 0
            learnerScopes.forEach { (mode, window) ->
                val mult = modeIsfLearner.multiplier(mode, window)
                val n    = modeIsfLearner.episodeCount(mode, window)
                if (n == 0 && mult == 1.0) return@forEach
                if (rows++ == 0) appendLine(learnerHeader("ISF", "Learned"))
                val baseMgdl = if (window == PfWindow.NONE) modeIsfMgdl(mode, hour) else pfIsfForWindow(window)
                val valueTxt = if (baseMgdl > 0.0) {
                    val b = if (isMmol) baseMgdl / 18.0 else baseMgdl
                    val e = b * mult
                    val f = if (isMmol) "%.2f" else "%.0f"
                    "${f.format(b)}→${f.format(e)}"
                } else "profile"
                appendLine(learnerRow(PfWindow.label(mode, window), valueTxt, "×${"%.3f".format(mult)}", n))
            }
            if (rows == 0) appendLine("No completed episodes yet — learns after each meal/UAM mode ends.")
            if (!modeIsfLearningEnabled())
                appendLine("\nLearning OFF — these values are still dosed from, nothing new is learned.")
            else if (LearningBias.from(sp) != LearningBias.NEUTRAL)
                appendLine("\nBias: ${LearningBias.from(sp).label} — ${learningBiasSummary()}")
            else if (modeIsfLearner.lastOutcome.isNotEmpty()) appendLine("\nLast: ${modeIsfLearner.lastOutcome}")
        }.trimEnd()

        val duraStrengthRaw = buildString {
            var rows = 0
            learnerScopes.forEach { (mode, window) ->
                val f = duraStrengthLearner.factor(mode, window)
                val n = duraStrengthLearner.episodeCount(mode, window)
                val ceiling = duraStrengthLearner.ceiling(mode, window)
                if (n == 0 && f == 1.0 && ceiling == DuraStrengthLearner.NO_CEILING) return@forEach
                if (rows++ == 0) appendLine(learnerHeader("Strength", "Floor"))
                // The ceiling shown the way the user sets DURA's limit: as the strongest ISF it can
                // reach, from this mode's learned ISF. Approximate — circadian ISF moves the live
                // value through the day; the note under the table says so.
                val floorTxt = when {
                    ceiling == DuraStrengthLearner.NO_CEILING -> "—"
                    else -> {
                        val baseMgdl = if (window == PfWindow.NONE) modeIsfMgdl(mode, hour) else pfIsfForWindow(window)
                        if (baseMgdl > 0.0) {
                            val floorMgdl = baseMgdl * modeIsfLearner.multiplier(mode, window) / ceiling
                            if (isMmol) "%.2f".format(floorMgdl / 18.0) else "%.0f".format(floorMgdl)
                        } else "×${"%.2f".format(ceiling)}"
                    }
                }
                appendLine(learnerRow(PfWindow.label(mode, window), "×${"%.2f".format(f)}", floorTxt, n))
            }
            if (rows == 0) appendLine("No DURA interventions evaluated yet.")
            if (!duraLearningEnabled())
                appendLine("\nLearning OFF — these values are still applied, nothing new is learned.")
            if (duraStrengthLearner.lastOutcome.isNotEmpty()) appendLine("\nLast: ${duraStrengthLearner.lastOutcome}")
        }.trimEnd()

        val uamEntryFractionRaw = buildString {
            // Reasons are collected and emitted as one block AFTER the rows rather than
            // interleaved between them. Interleaved, a reason long enough to wrap put its
            // continuation at column 0, indistinguishable from the next mode's row, and the grid
            // stopped reading as a grid at all.
            val reasons = StringBuilder()
            var rows = 0
            MealMode.entries.filter { UamEntryFractionLearner.isEntryMode(it) }.forEach { mode ->
                val offset = uamEntryFractionLearner.offset(mode)
                val n      = uamEntryFractionLearner.episodeCount(mode)
                if (n == 0 && offset == 0.0) return@forEach
                if (rows++ == 0) appendLine(learnerHeader("Fraction", null))
                val configured = entrySmbFractionForMode(mode)
                val adjusted   = uamEntryFractionLearner.adjustedFraction(mode, configured)
                // Every row here is a UAM mode — the section header says so — so the suffix is
                // six characters of noise on every line.
                val shortLabel = mode.label.removeSuffix(UAM_LABEL_SUFFIX)
                appendLine(learnerRow(shortLabel, "${"%.2f".format(configured)}→${"%.2f".format(adjusted)}", null, n))
                // Per-mode, because the global "Last:" line only ever explains whichever mode
                // happened to evaluate most recently — useless for "why hasn't Lunch moved?".
                // The stored reason leads with the mode's own label, which is redundant against
                // the label this line is already keyed by.
                uamEntryFractionLearner.lastReason(mode).takeIf { it.isNotEmpty() }?.let {
                    reasons.appendLine(shortLabel.padEnd(LEARNER_LABEL_W) + it.removePrefix("${mode.label} "))
                }
            }
            if (rows == 0) appendLine("No completed UAM entry episodes yet.")
            // No "Last:" line here: every mode carries its own reason above, so a global one only
            // ever repeats whichever of them evaluated most recently, word for word.
            if (reasons.isNotEmpty()) append("\n" + reasons.toString().trimEnd())
        }.trimEnd()

        return FragmentData(
            hour = hour, dayLabel = day, mealMode = activeMode?.label ?: "Fasting",
            modeRemMins = if (activeMode != null) (mealOverrideManager.modeTimeRemainingMs / 60_000).toInt() else null,
            aggressiveness = aggressionLearner.aggressiveness.coerceAtMost(circadianLearner.aggrCeiling(hour)),
            circCeil = circadianLearner.aggrCeiling(hour), isfMultiplier = isfMult,
            nudgeSessionIsfMgdl = nudgeDisplaySessionIsfMgdl, nudgeSessionBasalU = nudgeDisplaySessionBasalU,
            nudgeSessionIsfBucketMult = nudgeDisplaySessionIsfBucketMult,
            nudgeSessionBasBucketMult = nudgeDisplaySessionBasBucketMult,
            bucketIsfMultiplier = circadianLearner.isfMultiplier(hour, dow, TABLE_BUCKET_MINUTE),
            bucketBasalMultiplier = combinedBasalMultiplier(hour, dow, TABLE_BUCKET_MINUTE),
            profileIsfMgdl = profileIsf, finalIsfMgdl = if (isfMult > 0) profileIsf / isfMult else 0.0,
            basalMultiplier = basalMult, profileBasalU = profileBasal, finalBasalU = roundedFinalBasal,
            currentBgMgdl = glucoseStatusProvider.glucoseStatusData?.glucose ?: 0.0, profileTargetMgdl = cachedProfileTarget,
            lastBasalSignal = circadianLearner.lastBasalSignal, lastAggrNudgeStatus = circadianLearner.lastAggrNudgeStatus,
            lastAccelDebug = circadianLearner.lastAccelDebug, lastPredTrimDebug = circadianLearner.lastPredTrimDebug,
            // Shown only while the recovery taper is actually applied: a meal mode skips it (see
            // determine_basal's effectiveRebound), so a countdown then would be for nothing.
            inReboundWindow = inReboundWindow && activeMode == null, reboundMins = msSinceLastSuspend / 60_000,
            reboundWindowMins = sp.getInt(IntKey.ApsSmartInsulinReboundWindowMins.key, IntKey.ApsSmartInsulinReboundWindowMins.defaultValue),
            totalReboundWindowMins = (reboundGuardMs / 60_000).toInt(),
            consecutiveRollercoasters = circadianLearner.consecutiveRollercoasters,
            hardLowPenaltyActive = circadianLearner.lastHardLowPenaltyMs > 0L && (System.currentTimeMillis() - circadianLearner.lastHardLowPenaltyMs) < 90 * 60_000L,
            softLandingBypass = softLandingBypass, bgWentLow = bgWentLow, secondLowOccurred = secondLowOccurred,
            relowCount = relowTracker.relowCount,
            minBgDuringLow = minBgDuringLow, iobAtLowTime = iobAtLowTime, isMmol = isMmol,
            learningState = getLearningState(), activityLevel = activityMonitor.level.label,
            avgHrBpm = activityMonitor.avgHrBpm.toInt(), steps5min = activityMonitor.lastSteps5min,
            stepsAgeMs = activityMonitor.lastStepsAgeMs,
            phoneSteps5min = activityMonitor.phoneSteps5min,
            stepsFromPhone = activityMonitor.stepsFromPhone,
            phoneStepState = activityMonitor.phoneStepState,
            restingHrBpm = sp.getDouble(DoubleKey.ApsSmartInsulinRestingHrBpm.key, DoubleKey.ApsSmartInsulinRestingHrBpm.defaultValue),
            postMealLockoutMins = postMealLeft, cgmWarmup = getLearningState().contains("CGM"),
            stftStatus = stftController.statusString(cachedProfileTarget), stftActive = stftController.isActive,
            uamStatusLine = uamController.statusString(), uamDebug = uamController.debugSummary(),
            fuelTrimStrength = circadianLearner.trimStrength,
            trimActive = circadianLearner.trimActive,
            trimMins = circadianLearner.trimMins,
            profileLearningStatus = if (!isLearningEnabled) "off: Disabled" else bolusCurveTracker.statusSummary(currentMealMode),
            circadianRawStatus = circRaw, profilesRawStatus = profRaw, tirRawLine = aggressionLearner.tirSummary,
            avgBgMgdl24h = cachedHba1cAvgMgdl, estimatedHba1c = cachedHba1cEstimate, bgWindowHours = cachedHba1cWindowHours,
            activeDoseU = mealOverrideManager.activeDoseU, activePb2DoseU = mealOverrideManager.activePb2DoseU,
            activePb3DoseU = mealOverrideManager.activePb3DoseU,
            pb2Status = mealOverrideManager.preBolus2StatusText, pb3Status = mealOverrideManager.preBolus3StatusText,
            pb2GateData = mealOverrideManager.pb2GateData?.copy(isMmol = isMmol),
            pb3GateData = mealOverrideManager.pb3GateData?.copy(isMmol = isMmol),
            lowGuardMgdl = spMgdl(UnitDoubleKey.ApsSmartInsulinLowGuard),
            lastCycleSummary = circadianLearner.lastCycleSummary,
            lastRetroAttribution = circadianLearner.lastRetroAttribution,
            lastRunError = lastRunError,
            mealAbsorptionLog = mealAbsorptionRaw,
            mealAbsorptionInProgress = mealAbsorptionInProgressText,
            modeIsfLearnerStatus = modeIsfLearnerRaw,
            uamEntryFractionStatus = uamEntryFractionRaw,
            duraStrengthStatus = duraStrengthRaw
        )
    }

    private fun getLearningState(): String {
        val activeMode = mealOverrideManager.activeMealMode
        val now = System.currentTimeMillis()
        val isMealModeActive = activeMode != null
        val effectivePostMealLockout = !isMealModeActive && learningDirtyUntilMs > 0L && now < learningDirtyUntilMs
        return when {
            !sp.getBoolean(BooleanKey.ApsSmartInsulinEnableLearning.key, BooleanKey.ApsSmartInsulinEnableLearning.defaultValue) -> "off: Disabled"
            activityMonitor.suppressLearning -> "off: Activity"
            activityTail.paused -> "off: After activity — BG still dropping"
            effectivePostMealLockout -> "off: Post-meal"
            activeMode == MealMode.UAM_PROTEIN_FAT -> "limited: P/F"
            isMealModeActive -> "limited: meal"
            else -> "Learning"
        }
    }

    /** How a mode reads on the overview's "Meal:" line: "Lunch" when started from Smart Meal, "Lunch UAM" when
     *  UAM detected it, so the two are told apart at a glance. Fasting stays "Fasting". */
    private fun overviewMealLabel(mode: MealMode): String =
        if (mode.isUam) "${mode.label.removeSuffix(" (UAM)")} UAM" else mode.label

    override fun overviewState(): SmartInsulinOverview.OverviewState {
        // Recompute modeLine and learningState live so they're always current.
        // Avoids stale display between loop cycles (e.g. mode expired but state still shows P/F).
        val activeMode = mealOverrideManager.activeMealMode
        val now        = System.currentTimeMillis()
        // Same reason: getLearningState() below reads suppressLearning, which is derived from the
        // activity level. Non-blocking; the next read picks up the result.
        activityMonitor.requestRefresh(now, sp.getDouble(DoubleKey.ApsSmartInsulinRestingHrBpm.key, DoubleKey.ApsSmartInsulinRestingHrBpm.defaultValue))

        val liveModeLine = activeMode?.let { mode ->
            val mins = mealOverrideManager.modeTimeRemainingMs / 60_000
            "Meal: ${overviewMealLabel(mode)} ${mins}m"
        } ?: "Meal: Fasting"

        // Pre-bolus lines show only while the pre-bolus is still to come. Once delivered they go —
        // they used to switch to "PB2: active <mode time left>", which just repeated the meal
        // countdown for the rest of the mode. The SI tab keeps the "delivered" confirmation.
        val livePb2Line = when {
            mealOverrideManager.preBolus2Pending -> {
                val secs = mealOverrideManager.preBolus2SecondsRemaining ?: 0L
                when {
                    secs >= 60 -> "PB2: in ${secs / 60}m"
                    secs > 0 -> "PB2: in ${secs}s"
                    else -> "PB2: waiting for gates"
                }
            }
            else -> null
        }

        val livePb3Line = when {
            mealOverrideManager.preBolus3Pending -> {
                val secs = mealOverrideManager.preBolus3SecondsRemaining
                when {
                    secs != null && secs >= 60 -> "PB3: in ${secs / 60}m"
                    secs != null && secs > 0 -> "PB3: in ${secs}s"
                    secs != null && secs <= 0 -> "PB3: waiting for gates"
                    mealOverrideManager.preBolus2Pending -> "PB3: waiting for PB2"
                    else -> "PB3: waiting"
                }
            }
            else -> null
        }

        val isMealModeActive = activeMode != null
        val effectivePostMealLockout = !isMealModeActive && learningDirtyUntilMs > 0L && now < learningDirtyUntilMs
        // Low guard first: while BG is under the guard, and then for the recovery window after it
        // comes back up, that is what the loop is doing, whatever learning is up to.
        val reboundMinsLeft = ((reboundGuardMs - msSinceLastSuspend + 59_999) / 60_000).coerceAtLeast(1)
        val liveLearningState = when {
            bgWentLow && reboundWindowStartMs == 0L  -> "low: guard"
            // Not while a meal mode runs: meal modes skip the recovery taper, so there is nothing
            // to count down. The low guard itself (the line above) still shows in any mode.
            inReboundWindow && activeMode == null    -> "low: recovering ${reboundMinsLeft}m left"
            !sp.getBoolean(BooleanKey.ApsSmartInsulinEnableLearning.key, BooleanKey.ApsSmartInsulinEnableLearning.defaultValue) -> "off: Learning disabled"
            activityMonitor.suppressLearning         -> "off: Activity ${activityMonitor.level.label}"
            activityTail.paused                      -> "off: After activity — BG still dropping"
            cachedCgmSuppressLearning                -> "off: CGM warmup"
            effectivePostMealLockout                 -> {
                val minsLeft = ((learningDirtyUntilMs - now) / 60_000).coerceAtLeast(1)
                "off: Post-meal ${minsLeft}m left"
            }
            cachedOverviewState.learningState.startsWith("off: High temp") -> cachedOverviewState.learningState
            activeMode == MealMode.UAM_PROTEIN_FAT   -> "limited: P/F mode"
            isMealModeActive                         -> "limited: meal mode"
            else                                     -> "Learning"
        }

        return cachedOverviewState.copy(
            modeLine = liveModeLine,
            pb2Line = livePb2Line,
            pb3Line = livePb3Line,
            learningState = liveLearningState
        )
    }

    fun statusSummary(): String {
        val activeMode = mealOverrideManager.activeMealMode
        val modeStr = if (activeMode != null) {
            val mins = (mealOverrideManager.modeTimeRemainingMs / 60_000).toInt()
            "${activeMode.label} (${mins}m)"
        } else "Fasting"
        return "Mode: $modeStr · ${getLearningState()}"
    }

    /**
     * The 24-row per-hour circadian table for one weekday. Rows are read at minute 30 — the
     * bucket's own centre — so the table shows each hour's learned value rather than the
     * boundary-interpolated value that happens to apply at the current minute.
     *
     * @param markerHour hour to flag with an arrow, or -1 for no marker.
     */
    fun circadianDataForDay(
        dow:        Int = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1,
        markerHour: Int = -1
    ): String {
        val hourIsf   = cachedHourIsf
        val hourBasal = cachedHourBasal
        val isfUnit = if (isMmol) "mmol/U" else "mg/dL/U"
        return buildString {
            appendLine("  Hr  ISF ($isfUnit)   Basal (U/h)  Ceil   Conf")
            for (h in 0..23) {
                val marker   = if (h == markerHour) "→" else " "
                val hIsfMult = circadianLearner.isfMultiplier(h, dow, minute = TABLE_BUCKET_MINUTE)
                val hBasMult = combinedBasalMultiplier(h, dow, minute = TABLE_BUCKET_MINUTE)
                val hIsf = if (hourIsf[h] > 0 && hIsfMult > 0) (if (isMmol) "%.2f".format(hourIsf[h] / hIsfMult / 18.0) else "%.1f".format(hourIsf[h] / hIsfMult)) else "—"
                val hBas = if (hourBasal[h] > 0) "%.3f".format(hourBasal[h] * hBasMult) else "—"
                appendLine("$marker ${h.toString().padStart(2)}  $hIsf  $hBas  ${"%.3f".format(circadianLearner.aggrCeiling(h, dow, minute = TABLE_BUCKET_MINUTE))}  ${"%.0f".format(circadianLearner.confidencePct(h, dow))}%")
            }
        }
    }

    init {
        // Every learner verdict goes to the journal; see LearningJournal.
        modeIsfLearner.onOutcome          = { learningJournal.note("Meal ISF", it) }
        // The journal shows the mode's ISF before and after, not the multiplier: the same base ISF
        // the SI tab's learned-ISF table and dosing use.
        modeIsfLearner.isfText = { mode, window, mult ->
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val baseMgdl = if (window == PfWindow.NONE) modeIsfMgdl(mode, hour) else pfIsfForWindow(window)
            if (baseMgdl <= 0.0) null
            else if (isMmol) "%.2f mmol/U".format(baseMgdl * mult / 18.0)
            else "%.0f mg/dL/U".format(baseMgdl * mult)
        }
        uamEntryFractionLearner.onOutcome = { learningJournal.note("UAM entry", it) }
        duraStrengthLearner.onOutcome     = { learningJournal.note("DURA", it) }
        activitySessionLearner.onOutcome  = { learningJournal.note("Activity", it) }
        profileLearner.onChange           = { learningJournal.note("Insulin profile", it) }
    }

    /** Learning journal, newest first, for the SI tab. */
    fun learningJournalEntries(): List<LearningJournal.Entry> = learningJournal.entries()

    // ── Learning journal digest for the continuous learners ───────────────────
    // Circadian ISF/basal and aggressiveness move a little every cycle; journaling each move would bury
    // everything else, so they are summarised: each hour's net change once that hour is over, and an
    // aggressiveness entry when it has moved enough to matter. FuelTrim is noted when it starts and ends.
    private var digestHour = -1
    private var digestDow = -1
    private var digestIsfMult = 1.0
    private var digestBasMult = 1.0
    private var digestAggr = Double.NaN
    private var digestTrimActive = false

    private fun journalLearningDigest(hour: Int, dow: Int) {
        if (hour != digestHour || dow != digestDow) {
            if (digestHour >= 0) {
                // The hour just finished, read at its bucket centre - the same value the 24h table shows -
                // so the comparison is learning only, never the interpolation into the next hour.
                val endIsf = circadianLearner.isfMultiplier(digestHour, digestDow, TABLE_BUCKET_MINUTE)
                val endBas = combinedBasalMultiplier(digestHour, digestDow, TABLE_BUCKET_MINUTE)
                val parts = listOfNotNull(
                    isfChange(digestHour, digestIsfMult, endIsf),
                    basalChange(digestHour, digestBasMult, endBas)
                )
                // Only the pull back toward the weekly average moved it: say so, it is not learning.
                val decayOnly = circadianLearner.takeWriteSources(digestDow, digestHour) == setOf(CircadianLearner.WriteSource.DECAY)
                if (parts.isNotEmpty())
                    learningJournal.note("Circadian", "${JOURNAL_DAYS[digestDow]} ${"%02d".format(digestHour)}:00 — ${parts.joinToString(", ")}" +
                        if (decayOnly) " — drifted back toward this hour's weekly average, not learned from BG" else "")
            }
            digestHour = hour
            digestDow = dow
            circadianLearner.takeWriteSources(dow, hour)  // start the new hour with a clean record
            digestIsfMult = circadianLearner.isfMultiplier(hour, dow, TABLE_BUCKET_MINUTE)
            digestBasMult = combinedBasalMultiplier(hour, dow, TABLE_BUCKET_MINUTE)
        }

        val aggr = aggressionLearner.aggressiveness
        if (digestAggr.isNaN()) digestAggr = aggr
        else if (kotlin.math.abs(aggr - digestAggr) >= JOURNAL_AGGR_STEP) {
            learningJournal.note("Aggressiveness", "score ${"%.2f".format(digestAggr)}→${"%.2f".format(aggr)}")
            digestAggr = aggr
        }

        val trimActive = circadianLearner.trimActive
        if (trimActive != digestTrimActive) {
            val strength = circadianLearner.trimStrength
            learningJournal.note(
                "FuelTrim",
                if (trimActive) "started at ${"%02d".format(hour)}:00 — ${if (strength > 0) "sustained high, adding" else "sustained low, reducing"} " +
                    "insulin (${"%.1f".format(kotlin.math.abs(strength) * 100)}%), cap this hour ×${"%.2f".format(circadianLearner.aggrCeiling(hour))}"
                else "ended at ${"%02d".format(hour)}:00"
            )
            digestTrimActive = trimActive
        }
    }

    /** "ISF 2.10→2.06 mmol/U (stronger)" when the multiplier moved by [JOURNAL_MULT_STEP] or more, else null.
     *  A higher multiplier is a smaller ISF, so stronger. Falls back to the multiplier with no profile yet. */
    private fun isfChange(hour: Int, from: Double, to: Double): String? {
        if (kotlin.math.abs(to - from) < JOURNAL_MULT_STEP) return null
        val dir = if (to > from) "stronger" else "weaker"
        val base = cachedHourIsf[hour]
        if (base <= 0.0 || from <= 0.0 || to <= 0.0) return "ISF ×${"%.3f".format(from)}→${"%.3f".format(to)} ($dir)"
        val scale = if (isMmol) 18.0 else 1.0
        val (a, b) = journalPair(base / from / scale, base / to / scale, if (isMmol) 2 else 1)
        return "ISF $a→$b ${if (isMmol) "mmol/U" else "mg/dL/U"} ($dir)"
    }

    /** "basal 0.75→0.78 U/h (more)", same rules as [isfChange]. */
    private fun basalChange(hour: Int, from: Double, to: Double): String? {
        if (kotlin.math.abs(to - from) < JOURNAL_MULT_STEP) return null
        val dir = if (to > from) "more" else "less"
        val base = cachedHourBasal[hour]
        if (base <= 0.0) return "basal ×${"%.3f".format(from)}→${"%.3f".format(to)} ($dir)"
        val (a, b) = journalPair(base * from, base * to, 2)
        return "basal $a→$b U/h ($dir)"
    }

    /** Both values at [decimals], or one more when that would print the same number twice. */
    private fun journalPair(from: Double, to: Double, decimals: Int): Pair<String, String> {
        val a = "%.${decimals}f".format(from)
        val b = "%.${decimals}f".format(to)
        return if (a != b) a to b else "%.${decimals + 1}f".format(from) to "%.${decimals + 1}f".format(to)
    }

    override suspend fun onStart() {
        super.onStart()
        learningDirtyUntilMs = sp.getString(StringNonKey.ApsSmartInsulinLearningDirtyUntil.key, "0").toLongOrNull() ?: 0L
        // No-op when the permission is missing or the device has no pedometer; the SI tab reports
        // which of those it is, and calling start() again after a grant is what picks it up.
        phoneStepCounter.start()
    }

    override suspend fun onStop() {
        phoneStepCounter.stop()
        super.onStop()
    }

    /** [RiseTurnGuard] on the newest raw readings (calibrated if calibrated), before smoothing. */
    private fun riseTurnNow(smoothedDeltaMgdl: Double): RiseTurnGuard.Result? {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return null
        // A gap that bucketing filled in is not a reading; stop the raw series there.
        val real = data.take(6).takeWhile { !it.filledGap }
        return RiseTurnGuard.apply(smoothedDeltaMgdl, real.map { it.calibratedOrValue }, real.map { it.timestamp })
    }

    /** Re-arm after the SI tab's permission request comes back granted. */
    fun startPhoneStepCounter() = phoneStepCounter.start()

    // spMgdl and activityOffsetMmol live in SmartInsulinSpReader.kt (same package)
    // so they can be unit-tested without constructing the full plugin.
    private fun spMgdl(key: UnitDoubleKey): Double = spMgdl(sp, key)

    /**
     * Header and row for the three "Learned …" tables on the SI tab.
     *
     * They hold different quantities, but they sit one under another and are read as a group, so
     * the label and value columns line up across all three and only the trailing column differs.
     * Before this they each invented their own spacing and their own way of writing the episode
     * count, which made three related tables look like three unrelated ones.
     */
    private fun learnerHeader(value: String, trailing: String?): String =
        "Mode".padEnd(LEARNER_LABEL_W) + value.padEnd(LEARNER_VALUE_W) +
            (trailing?.padEnd(LEARNER_TRAIL_W) ?: "") + "n"

    private fun learnerRow(label: String, value: String, trailing: String?, n: Int): String =
        label.padEnd(LEARNER_LABEL_W) + value.padEnd(LEARNER_VALUE_W) +
            (trailing?.padEnd(LEARNER_TRAIL_W) ?: "") + n

    private fun pfWindowFor(hour: Int): PfWindow {
        val dayStart      = sp.getInt(IntKey.ApsSmartInsulinUamProteinFatDayStartHour.key, IntKey.ApsSmartInsulinUamProteinFatDayStartHour.defaultValue)
        val dayEnd        = sp.getInt(IntKey.ApsSmartInsulinUamProteinFatDayEndHour.key, IntKey.ApsSmartInsulinUamProteinFatDayEndHour.defaultValue)
        val nightStart    = sp.getInt(IntKey.ApsSmartInsulinUamProteinFatNightStartHour.key, IntKey.ApsSmartInsulinUamProteinFatNightStartHour.defaultValue)
        val nightEnd      = sp.getInt(IntKey.ApsSmartInsulinUamProteinFatNightEndHour.key, IntKey.ApsSmartInsulinUamProteinFatNightEndHour.defaultValue)
        val overnightStart = sp.getInt(IntKey.ApsSmartInsulinUamProteinFatOvernightStartHour.key, IntKey.ApsSmartInsulinUamProteinFatOvernightStartHour.defaultValue)
        val overnightEnd   = sp.getInt(IntKey.ApsSmartInsulinUamProteinFatOvernightEndHour.key, IntKey.ApsSmartInsulinUamProteinFatOvernightEndHour.defaultValue)

        fun inWindow(start: Int, end: Int) =
            if (start <= end) hour in start..end else hour >= start || hour <= end

        val inDay       = inWindow(dayStart, dayEnd)
        val inNight     = inWindow(nightStart, nightEnd)
        val inOvernight = inWindow(overnightStart, overnightEnd)

        val dayIsf       = sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamProteinFatDayIsf.key, UnitDoubleKey.ApsSmartInsulinUamProteinFatDayIsf.defaultValue)
        val nightIsf     = sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamProteinFatNightIsf.key, UnitDoubleKey.ApsSmartInsulinUamProteinFatNightIsf.defaultValue)
        val overnightIsf = sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamProteinFatOvernightIsf.key, UnitDoubleKey.ApsSmartInsulinUamProteinFatOvernightIsf.defaultValue)
        val fallback     = sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamProteinFatIsf.key, UnitDoubleKey.ApsSmartInsulinUamProteinFatIsf.defaultValue)

        // Overnight takes priority over night when both match (overnight is more specific).
        // A window with no override set falls through — those hours dose from the base value and
        // so belong to PfWindow.BASE.
        return when {
            inOvernight && overnightIsf > 0.0 -> PfWindow.OVERNIGHT
            inDay       && dayIsf       > 0.0 -> PfWindow.DAY
            inNight     && nightIsf     > 0.0 -> PfWindow.NIGHT
            else                              -> PfWindow.BASE
        }
    }

    /** The configured P/F ISF for one window. */
    private fun pfIsfForWindow(window: PfWindow): Double = when (window) {
        PfWindow.OVERNIGHT -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamProteinFatOvernightIsf.key, UnitDoubleKey.ApsSmartInsulinUamProteinFatOvernightIsf.defaultValue)
        PfWindow.DAY       -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamProteinFatDayIsf.key, UnitDoubleKey.ApsSmartInsulinUamProteinFatDayIsf.defaultValue)
        PfWindow.NIGHT     -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamProteinFatNightIsf.key, UnitDoubleKey.ApsSmartInsulinUamProteinFatNightIsf.defaultValue)
        else               -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamProteinFatIsf.key, UnitDoubleKey.ApsSmartInsulinUamProteinFatIsf.defaultValue)
    }

    private fun pfIsfMgdl(hour: Int): Double = pfIsfForWindow(pfWindowFor(hour))

    /**
     * The P/F ISF window for a mode at an hour — [PfWindow.NONE] for every mode but P/F, which is
     * the only one whose configured ISF is time-of-day dependent. This is what splits the
     * learners' state slots, so it has to be derived from the same decision [pfWindowFor] makes
     * for dosing or a correction would be filed against a window it was never earned in.
     */
    private fun pfWindowForMode(mode: MealMode?, hour: Int): PfWindow =
        if (mode == MealMode.UAM_PROTEIN_FAT) pfWindowFor(hour) else PfWindow.NONE

    private fun modeIsfMgdl(mode: MealMode, hour: Int): Double = when (mode) {
        MealMode.BREAKFAST -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinBreakfastIsf.key, UnitDoubleKey.ApsSmartInsulinBreakfastIsf.defaultValue)
        MealMode.LUNCH -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinLunchIsf.key, UnitDoubleKey.ApsSmartInsulinLunchIsf.defaultValue)
        MealMode.DINNER -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinDinnerIsf.key, UnitDoubleKey.ApsSmartInsulinDinnerIsf.defaultValue)
        MealMode.LOW_CARB -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinLowCarbIsf.key, UnitDoubleKey.ApsSmartInsulinLowCarbIsf.defaultValue)
        MealMode.EXTENDED -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinExtendedIsf.key, UnitDoubleKey.ApsSmartInsulinExtendedIsf.defaultValue)
        MealMode.UAM_BREAKFAST -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamBreakfastIsf.key, UnitDoubleKey.ApsSmartInsulinUamBreakfastIsf.defaultValue)
        MealMode.UAM_LUNCH -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamLunchIsf.key, UnitDoubleKey.ApsSmartInsulinUamLunchIsf.defaultValue)
        MealMode.UAM_DINNER -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamDinnerIsf.key, UnitDoubleKey.ApsSmartInsulinUamDinnerIsf.defaultValue)
        MealMode.UAM_SNACK -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamSnackIsf.key, UnitDoubleKey.ApsSmartInsulinUamSnackIsf.defaultValue)
        MealMode.UAM_AFTERNOON -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamAfternoonIsf.key, UnitDoubleKey.ApsSmartInsulinUamAfternoonIsf.defaultValue)
        MealMode.UAM_PROTEIN_FAT -> pfIsfMgdl(hour)
        else -> 0.0
    }

    /**
     * Fingerprint of the user-set ISF a mode's learned correction is relative to, for
     * ModeIsfLearner's base-change reset.
     *
     * P/F used to need a weighted blend of all four of its prefs here, because one shared state
     * slot had to survive the windows rolling over. Now that each window keeps its own slot the
     * signature is simply that window's own ISF — so editing the Day value resets the Day
     * correction and leaves Night and Overnight alone, which is what a per-window base implies.
     * Only read when an episode opens, and the window is fixed for that episode's whole life.
     */
    private fun modeIsfOverrideSignature(mode: MealMode, hour: Int): Double = when (mode) {
        MealMode.UAM_PROTEIN_FAT -> pfIsfMgdl(hour)
        else -> modeIsfMgdl(mode, 0)  // non-P/F overrides are hour-independent; 0.0 = "profile ISF"
    }

    internal fun entrySmbFractionForMode(mode: MealMode): Double = when (mode) {
        MealMode.UAM_BREAKFAST -> sp.getDouble(DoubleKey.ApsSmartInsulinUamEntrySmbFractionBreakfast.key,  DoubleKey.ApsSmartInsulinUamEntrySmbFractionBreakfast.defaultValue)
        MealMode.UAM_LUNCH     -> sp.getDouble(DoubleKey.ApsSmartInsulinUamEntrySmbFractionLunch.key,      DoubleKey.ApsSmartInsulinUamEntrySmbFractionLunch.defaultValue)
        MealMode.UAM_DINNER    -> sp.getDouble(DoubleKey.ApsSmartInsulinUamEntrySmbFractionDinner.key,     DoubleKey.ApsSmartInsulinUamEntrySmbFractionDinner.defaultValue)
        MealMode.UAM_SNACK     -> sp.getDouble(DoubleKey.ApsSmartInsulinUamEntrySmbFractionSnack.key,      DoubleKey.ApsSmartInsulinUamEntrySmbFractionSnack.defaultValue)
        MealMode.UAM_AFTERNOON -> sp.getDouble(DoubleKey.ApsSmartInsulinUamEntrySmbFractionAfternoon.key,  DoubleKey.ApsSmartInsulinUamEntrySmbFractionAfternoon.defaultValue)
        else                   -> sp.getDouble(DoubleKey.ApsSmartInsulinUamEntrySmbFraction.key,           DoubleKey.ApsSmartInsulinUamEntrySmbFraction.defaultValue)
    }

    override suspend fun invoke(initiator: String, tempBasalFallback: Boolean) {
        val previousAPSResult = lastAPSResult; lastAPSResult = null
        val profile = profileFunction.getProfile() ?: return
        BgText.mmol = isMmol
        cachedProfileIsf = profile.getIsfMgdl("SmartInsulinPlugin")
        cachedProfileBasal = profile.getBasal()
        cachedHourIsf = DoubleArray(24) { profile.getIsfMgdlTimeFromMidnight(it * 3600 + TABLE_BUCKET_MINUTE * 60) }
        cachedHourBasal = DoubleArray(24) { profile.getBasalTimeFromMidnight(it * 3600 + TABLE_BUCKET_MINUTE * 60) }
        cachedProfileTarget = profile.getTargetMgdl()

        val now = dateUtil.now()
        if (now - cachedHba1cRefreshedAtMs >= HBA1C_CACHE_REFRESH_MS) {
            try {
                val todayStart = dateUtil.beginOfDay(now)
                val bgs = persistenceLayer.getBgReadingsDataFromTimeToTime(todayStart, now, true)
                if (bgs.size >= 24) {
                    cachedHba1cAvgMgdl = bgs.map { it.value }.average()
                    cachedHba1cEstimate = (cachedHba1cAvgMgdl + 46.7) / 28.7
                    cachedHba1cWindowHours = if (bgs.size >= 2) ((bgs.maxOf { it.timestamp } - bgs.minOf { it.timestamp }) / 3600000L).toInt().coerceAtLeast(1) else 0
                }
                cachedHba1cRefreshedAtMs = now
            } catch (_: Exception) {}
        }

        if (!isEnabled()) return
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData ?: return
        val nowCal = java.util.Calendar.getInstance().also { it.timeInMillis = now }
        val currentHour = nowCal.get(java.util.Calendar.HOUR_OF_DAY)
        val currentMinute = nowCal.get(java.util.Calendar.MINUTE)
        val currentDow    = nowCal.get(java.util.Calendar.DAY_OF_WEEK) - 1
        val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
        val currentTemp = CurrentTemp(tb?.plannedRemainingMinutes ?: 0, tb?.convertedToAbsolute(now, profile) ?: 0.0, tb?.getPassedDurationToTimeInMinutes(now))

        var targetBg = profile.getTargetMgdl()
        var isTempTarget = false
        persistenceLayer.getTemporaryTargetActiveAt(now)?.let { tt -> isTempTarget = true; targetBg = tt.target() }

        val autosensResult = AutosensResult()
        val iobArray = iobCobCalculator.calculateIobArrayForSMB(autosensResult, SMBDefaults.exercise_mode, SMBDefaults.half_basal_exercise_target, isTempTarget)
        val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()
        var mealMode = MealModeDetector.detect(mealOverrideManager)

        if (!lockoutTrackerInitialized) { previousMealModeForLockout = mealMode; lockoutTrackerInitialized = true }
        // P/F (UAM_PROTEIN_FAT) previously excluded from post-meal lockout — meaning learning
        // resumed immediately after P/F expired. P/F typically runs 2-4h after a meal during
        // fat/protein digestion; the BG signal during this period is not clean fasting data.
        // Including P/F here ensures the lockout fires when P/F ? FASTING, just like any other mode.
        if (previousMealModeForLockout != MealMode.FASTING && mealMode == MealMode.FASTING) {
            val lockoutMins = sp.getInt(IntKey.ApsSmartInsulinPostModeLockoutMins.key, IntKey.ApsSmartInsulinPostModeLockoutMins.defaultValue)
            if (lockoutMins > 0) { learningDirtyUntilMs = maxOf(learningDirtyUntilMs, now + lockoutMins * 60000L); sp.edit { putString(StringNonKey.ApsSmartInsulinLearningDirtyUntil.key, learningDirtyUntilMs.toString()) } }
        }
        previousMealModeForLockout = mealMode
        if (learningDirtyUntilMs > 0L && now >= learningDirtyUntilMs) { learningDirtyUntilMs = 0L; sp.edit { putString(StringNonKey.ApsSmartInsulinLearningDirtyUntil.key, "0") } }
        val inPostMealLockout = mealMode == MealMode.FASTING && now < learningDirtyUntilMs

        val mIsfMgdl = modeIsfMgdl(mealMode, currentHour)
        val trueIsfMgdl = profile.getIsfMgdl("SmartInsulinPlugin")
        val highTempTarget = isTempTarget && targetBg > profile.getTargetMgdl()

        if (now - sensorCacheRefreshedAtMs >= SENSOR_CACHE_REFRESH_MS) {
            try {
                val sensorEvents = persistenceLayer.getTherapyEventDataFromTime(now - 30L*24*3600000, TE.Type.SENSOR_CHANGE, true)
                cachedSensorInsertTimeMs = sensorEvents.maxByOrNull { it.timestamp }?.timestamp ?: 0L
                sensorCacheRefreshedAtMs = now
            } catch (_: Exception) {}
        }
        val cgmState = cgmWarmupGuard.evaluate(sp.getBoolean(BooleanKey.ApsSmartInsulinCgmWarmupEnabled.key, BooleanKey.ApsSmartInsulinCgmWarmupEnabled.defaultValue), cachedSensorInsertTimeMs, now, glucoseStatus.date, glucoseStatus.delta/18.0, glucoseStatus.shortAvgDelta/18.0, glucoseStatus.longAvgDelta/18.0, glucoseStatus.noise)

        // Ahead of every consumer, which it was not: this used to run ~270 lines further down,
        // after circadianLearner.update() had already read suppressLearning. So the learning gate
        // ran on the PREVIOUS cycle's activity — start walking and the learner kept learning from
        // one more cycle of exercise-contaminated data before noticing.
        activityMonitor.recompute(now, sp.getDouble(DoubleKey.ApsSmartInsulinRestingHrBpm.key, DoubleKey.ApsSmartInsulinRestingHrBpm.defaultValue))

        // -- Unexplained-drop detection (feeds the learners and the after-activity pause) ----
        // Measures BG movement not accounted for by insulin activity, so a low caused by
        // exercise (or missed food) doesn't get blamed on ISF. Uses the profile ISF as the
        // physiological reference, not a mode's deliberately-aggressive override. Updated here,
        // before any learner runs, because the after-activity pause below reads it.
        unexplainedDropTracker.onCycle(glucoseStatus.delta, iobArray.firstOrNull()?.activity ?: 0.0, trueIsfMgdl, now)
        // Learning pauses during activity, and stays paused after it while BG is still falling
        // faster than insulin explains - the drop a sprint keeps causing once you have sat down.
        activityTail.onCycle(now, activityMonitor.level, unexplainedDropTracker.stillDropping(now))
        val activityPausesLearning = activityMonitor.suppressLearning || activityTail.paused

        // -- Whose insulin is behind a post-mode low ---------------------------
        // While a mode runs, keep a live reading of what it has working; once it ends, add up what
        // the loop gives afterwards. Boluses come from the database (SMBs are boluses); the basal
        // side is the temp rate's difference from profile over the cycle.
        val modeRunning = mealOverrideManager.activeMealMode != null
        if (modeRunning) {
            if (mealOverrideManager.modeStartMs != lastModeStartSeenMs) {
                lastModeStartSeenMs = mealOverrideManager.modeStartMs
                postModeDeliveredU  = 0.0
                postModeSinceMs     = 0L
            }
            // Refreshed every cycle, so whenever the mode ends this holds its parting IOB and time.
            modeIobAtEndU  = (iobArray.firstOrNull()?.iob ?: 0.0).coerceAtLeast(0.0)
            postModeSinceMs = now
            modeEndedMs     = now
        } else if (postModeSinceMs > 0L) {
            val boluses = try {
                persistenceLayer.getBolusesFromTimeToTime(postModeSinceMs, now, true).sumOf { it.amount }
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "SmartInsulin share: bolus read failed: ${e.message}"); 0.0
            }
            val basalExtraU = previousAPSResult?.let { prev ->
                val hours = (now - lastIobSampleMs).coerceIn(0L, 10 * 60_000L) / 3_600_000.0
                ((prev.rate - profile.getBasal()) * hours).coerceAtLeast(0.0)
            } ?: 0.0
            postModeDeliveredU += boluses + basalExtraU
            postModeSinceMs = now
        }
        lastIobSampleMs = now
        val modeInsulinShare = ModeInsulinShare.share(modeIobAtEndU, postModeDeliveredU)
        // The meal's insulin is still most of what is working: a mode ended inside its own watch
        // and at least half of the insulin since is that mode's. The circadian learner is told, so a
        // low then is charged to the meal learners (meal ISF, DURA) and not to the fasting hours.
        // See CircadianLearner.update's mealInsulinDominant.
        val mealInsulinDominant = !modeRunning && modeEndedMs > 0L &&
            now - modeEndedMs <= postModeWatchMs() &&
            modeInsulinShare >= MEAL_INSULIN_DOMINANT_SHARE

        if (!highTempTarget) {
            // Capture multipliers BEFORE update() so "was" reflects the baseline the
            // loop was delivering before this cycle's nudge fires.
            val isfMultBefore        = circadianLearner.isfMultiplier(currentHour, currentDow, currentMinute)
            val totalBasalMultBefore = combinedBasalMultiplier(currentHour, currentDow, currentMinute)
            // Bucket-centre twins of the two above — see nudgeDisplaySessionIsfBucketMult.
            val isfBucketBefore      = circadianLearner.isfMultiplier(currentHour, currentDow, TABLE_BUCKET_MINUTE)
            val basBucketBefore      = combinedBasalMultiplier(currentHour, currentDow, TABLE_BUCKET_MINUTE)
            val lastDirection = run {
                val parts = lastSeenNudgeState.split("|")
                val p = parts.getOrNull(0) ?: "INACTIVE"
                if (p == "TRIM") parts.getOrNull(1)
                else if (p == "ACTIVE_HIGH" || p == "ACTIVE_LOW") p
                else null
            }

            circadianLearner.update(
                glucoseStatus            = glucoseStatus,
                iobArray                 = iobArray,
                mealMode                 = mealMode,
                cobG                     = mealData.mealCOB,
                profileIsfMgdl           = trueIsfMgdl,
                targetMgdl               = targetBg,
                suppressAdaptiveLearning = activityPausesLearning || cgmState.suppressLearning,
                lowGuardMgdl             = spMgdl(UnitDoubleKey.ApsSmartInsulinLowGuard),
                inPostMealLockout        = inPostMealLockout,
                inReboundWindow          = inReboundWindow,
                mealInsulinDominant      = mealInsulinDominant,
                aggressiveness           = aggressionLearner.aggressiveness,
                fastingPeakMins          = profileLearner.getProfile(MealMode.FASTING).peakMinutes,
                // Peak and DIA together — the retroactive low attribution needs the whole curve
                // to say how much of a 2h-old dose is still pulling BG down.
                fastingDiaMins           = profileLearner.getProfile(MealMode.FASTING).safeDiaMinutes,
                // Drive the learner from the loop's clock (dateUtil.now()), not its own
                // Calendar.getInstance() defaults — otherwise the learner can be reading a
                // different hour/minute than the cycle it is learning from, and nothing in a
                // test can pin either.
                hour                     = currentHour,
                dow                      = currentDow,
                minute                   = currentMinute,
                nowMs                    = now
            )

            // Detect a new nudge session starting — capture "was" ISF/basal at the moment
            // the nudge direction first appears. We only capture once per episode (direction
            // change) so "was ISF" stays pinned to the pre-nudge baseline, not the current value.
            val currentNudgeStatus = circadianLearner.lastAggrNudgeStatus
            val currentDirection = run {
                val parts = currentNudgeStatus.split("|")
                val p = parts.getOrNull(0) ?: "INACTIVE"
                if (p == "TRIM") parts.getOrNull(1)
                else if (p == "ACTIVE_HIGH" || p == "ACTIVE_LOW") p
                else null
            }
            // Re-armed by a direction change OR an hour change. The nudge session is deliberately
            // carried across hour boundaries by carryOverHourBoundary, but the DISPLAY must not be:
            // hour 11's learned basal and hour 12's are independent buckets, so their difference is
            // not something the nudge did. Learning is untouched by this — it is the baseline the
            // card compares against that moves.
            val newHour = currentHour != nudgeDisplaySessionHour
            if (currentDirection != null && (currentDirection != lastDirection || newHour)) {
                nudgeDisplaySessionIsfMgdl = if (isfMultBefore > 0) trueIsfMgdl / isfMultBefore else 0.0
                nudgeDisplaySessionBasalU  = cachedProfileBasal * totalBasalMultBefore
                nudgeDisplaySessionIsfBucketMult = isfBucketBefore
                nudgeDisplaySessionBasBucketMult = basBucketBefore
                nudgeDisplaySessionHour    = currentHour
                aapsLogger.debug(LTag.APS,
                                 "SmartInsulinPlugin: nudge baseline captured — dir=$currentDirection h=$currentHour " +
                                     "(${if (newHour) "new hour" else "direction change"}) " +
                                     "isf=${"%.1f".format(nudgeDisplaySessionIsfMgdl)} basal=${"%.3f".format(nudgeDisplaySessionBasalU)} " +
                                     "bucket isf×${"%.4f".format(isfBucketBefore)} bas×${"%.4f".format(basBucketBefore)}")
            }
            lastSeenNudgeState = currentNudgeStatus
        }
        val circIsfMult = circadianLearner.isfMultiplier(currentHour, currentDow, currentMinute)
        var dosingIsfMgdl = if (mIsfMgdl > 0.0) mIsfMgdl else trueIsfMgdl / circIsfMult

        mealOverrideManager.onLoopCycle(
            glucoseStatus, iobArray, constraintsChecker.getMaxIOBAllowed().value(), profile,
            // Rebound state and the previous decision are both carried over from the last cycle —
            // this call runs before determine_basal, so that is the freshest view available.
            loopRestraining = inReboundWindow || (previousAPSResult?.let { it.rate == 0.0 && it.duration > 0 } ?: false)
        )

        // Auto-cancel an auto-detected (UAM) mode once BG has returned to profile target.
        // Must be checked here (not in UamController) because UamController.onLoopCycle()
        // exits early when currentMealMode != FASTING and never runs during an active UAM mode.
        //
        // Protein/Fat used to be excluded from this, so it was the one UAM mode that kept running
        // an aggressive mode ISF after BG had already come back to target — it only ended on its
        // duration timer. Included now: see shouldAutoCancelAtTarget for the scope rule.
        val activeUamMode = mealOverrideManager.activeMealMode
        if (shouldAutoCancelAtTarget(activeUamMode, glucoseStatus.glucose, profile.getTargetMgdl())) {
            aapsLogger.debug(LTag.APS,
                             "SmartInsulin: UAM auto-cancel (${activeUamMode?.label}) — BG ${fmtBg(glucoseStatus.glucose)} " +
                                 "≤ target ${fmtBg(profile.getTargetMgdl())} $unitLabel")
            mealOverrideManager.cancelOverride()
        }

        // -- Entered carbs: the stock oref path -----------------------------------
        // Carbs on board means the loop was told what is coming, so the COB curve doses it and UAM
        // stands down — otherwise the rise gets dosed twice, once predicted and once observed.
        // The episode is filed under the meal window the clock was in when the carbs went in, and
        // closing it starts the post-meal lockout the same way a UAM mode ending does.
        val cobNowG = mealData.mealCOB
        lastCobG = cobNowG
        carbEpisodeManager.onCycle(cobNowG, now, uamController.mealWindowAt(currentHour, currentMinute))?.let { ended ->
            val lockoutMins = sp.getInt(IntKey.ApsSmartInsulinPostModeLockoutMins.key, IntKey.ApsSmartInsulinPostModeLockoutMins.defaultValue)
            if (lockoutMins > 0) {
                learningDirtyUntilMs = maxOf(learningDirtyUntilMs, now + lockoutMins * 60_000L)
                sp.edit { putString(StringNonKey.ApsSmartInsulinLearningDirtyUntil.key, learningDirtyUntilMs.toString()) }
            }
            aapsLogger.debug(LTag.APS, "SmartInsulin: carbs finished (${ended.label}) — post-meal lockout ${lockoutMins}min")
        }
        val carbEpisodeActive = carbEpisodeManager.active

        // -- Declared activity/stress session (golf, gym) ------------------------
        // A user-declared no-food window: hormones, not carbs. It blocks the meal modes from
        // reading a hormonal plateau as food, strengthens the resistant phase by what it has
        // learned, and tapers insulin off before the hormones clear.
        val sessionDose   = activitySessionManager.dosing(now)
        val sessionActive = activitySessionManager.active != null
        val sessionStatus = if (sessionActive) activitySessionManager.statusLine(now) ?: "" else ""

        uamController.onLoopCycle(mealMode, glucoseStatus.glucose/18.0, glucoseStatus.delta/18.0, glucoseStatus.shortAvgDelta/18.0, -((iobArray.firstOrNull()?.activity ?: 0.0) * dosingIsfMgdl * 5.0) / 18.0, currentHour, currentMinute, bgWentLow, inReboundWindow, if (bgWentLow) reboundWindowStartMs else 0L, highTempTarget, cgmState.inWarmup, inPostMealLockout, profile.getTargetMgdl()/18.0, softLandingBypass, glucoseStatus.date, noFoodSession = sessionActive || carbEpisodeActive)

        val justFiredMode = uamController.justFiredThisCycle
        val latestMealMode = justFiredMode ?: mealOverrideManager.activeMealMode ?: MealMode.FASTING
        if (latestMealMode != mealMode) {
            val lModeIsf = modeIsfMgdl(latestMealMode, currentHour)
            mealMode = latestMealMode
            if (lModeIsf > 0.0) dosingIsfMgdl = lModeIsf
        }

        // -- Learned per-mode ISF (episode-outcome learning) --------------------
        // Applied to the mode's base ISF before DURA — DURA is the within-episode rescue,
        // this is the across-episodes correction learned from how past activations ended.
        if (mealMode != MealMode.FASTING) {
            val learnedModeMult = modeIsfLearner.multiplier(mealMode, pfWindowForMode(mealMode, currentHour))
            if (learnedModeMult != 1.0) {
                dosingIsfMgdl *= learnedModeMult
                aapsLogger.debug(LTag.APS,
                                 "SmartInsulin modeISF: ${mealMode.label} learned ×${"%.3f".format(learnedModeMult)} → isf=${"%.1f".format(dosingIsfMgdl)}")
            }
        }

        // The session's learned resistant-phase multiplier. Below 1.0 strengthens, exactly like a
        // mode's learned ISF; it relaxes back toward 1.0 as the washout comes in, so the taper
        // below is never fighting a "you are resistant" correction that has already stopped being
        // true.
        if (sessionActive && sessionDose.isfMultiplier != 1.0) {
            dosingIsfMgdl *= sessionDose.isfMultiplier
            aapsLogger.debug(LTag.APS,
                             "SmartInsulin session: ISF ×${"%.2f".format(sessionDose.isfMultiplier)} → ${"%.1f".format(dosingIsfMgdl)}")
        }

        // -- DURA_ISF: strengthen ISF the longer BG sits stuck above target during a
        // DURA-enabled meal mode override. Tracker resets itself whenever DURA isn't
        // active (mode ended or toggle off), so a stuck plateau never leaks between activations.
        val duraActive = mealOverrideManager.activeMealMode != null && mealOverrideManager.activeDuraEnabled
        duraIsfTracker.onCycle(glucoseStatus.glucose, duraActive, glucoseStatus.delta)
        var duraStatusText = ""  // stays "" (hidden from reason string) unless DURA is actually strengthening ISF this cycle
        var duraMultThisCycle = 1.0  // exposed to ModeIsfLearner — big DURA interventions count as "mode ISF too weak"
        var duraAtCeilingThisCycle = false  // DURA wanted more than its learned ceiling allowed
        var duraAtFloorThisCycle   = false  // DURA's ISF was held at the configured floor
        if (duraActive) {
            val duraWindow = pfWindowForMode(mealOverrideManager.activeMealMode, currentHour)
            // Learned factor can only soften the configured strength (crash-direction learning).
            val effectiveDuraStrength = mealOverrideManager.activeDuraStrength *
                duraStrengthLearner.factor(mealOverrideManager.activeMealMode, duraWindow)
            val duraRaw     = duraIsfTracker.multiplier(targetBg, effectiveDuraStrength)
            // Strength sets how fast DURA climbs; the learned ceiling and your floor set how far.
            // See applyDuraLimits for exactly when each counts as having held DURA back.
            val limited = applyDuraLimits(
                preDuraIsfMgdl = dosingIsfMgdl,
                rawMult        = duraRaw,
                ceiling        = duraStrengthLearner.ceiling(mealOverrideManager.activeMealMode, duraWindow),
                floorMgdl      = mealOverrideManager.activeDuraFloorMgdl
            )
            val duraMult = limited.effectiveMult
            duraAtCeilingThisCycle = limited.atCeiling
            duraAtFloorThisCycle   = limited.atFloor
            duraMultThisCycle      = duraMult
            if (duraRaw > 1.0) {
                dosingIsfMgdl = limited.isfMgdl
                val duraStuckMins = duraIsfTracker.stuckMinutesForDisplay
                duraStatusText = "DURA=${"%.2f".format(duraMult)}x${when {
                    duraAtFloorThisCycle   -> " (floor)"
                    duraAtCeilingThisCycle -> " (ceiling)"
                    duraMult <= 1.0        -> " (ISF already at floor)"
                    else                   -> ""
                }} stuck=${"%.0f".format(duraStuckMins)}m"
                aapsLogger.debug(LTag.APS,
                                 "SmartInsulin DURA: mult=${"%.2f".format(duraMult)} " +
                                     "stuck=${"%.0f".format(duraStuckMins)}min isf->${"%.1f".format(dosingIsfMgdl)}")
            }
        }

        // -- Meal absorption estimator (observation-only) -----------------------
        // Deconvolves BG movement into "insulin's expected effect" vs a leftover residual,
        // same as oref0/AutoISF's carb-impact estimation — a single lumped carb-equivalent
        // grams figure, not a carbs/protein/fat breakdown (BG alone can't tell those apart).
        // Purely diagnostic: does not feed into dosing. Logged per completed mode activation
        // so it can be compared against what was actually eaten.
        val completedMealEpisode = mealAbsorptionTracker.onCycle(
            activeMode     = mealOverrideManager.activeMealMode,
            modeStartMs    = mealOverrideManager.modeStartMs,
            bgMgdl         = glucoseStatus.glucose,
            targetMgdl     = targetBg,
            deltaMgdl      = glucoseStatus.delta,
            activityPerMin = iobArray.firstOrNull()?.activity ?: 0.0,
            isfMgdl        = dosingIsfMgdl,
            carbRatio      = profile.getIc(),
            nowMs          = now
        )
        if (completedMealEpisode != null) mealAbsorptionCsvLogger.log(completedMealEpisode)

        // Exercise suspected: the drop is bigger than insulin explains, or activity has just ended
        // and BG is still dropping from it. A low then is learned by the meal learners at a small
        // step only. (The tracker itself is updated earlier, before the circadian learner.)
        val exerciseSuspected = unexplainedDropTracker.exerciseSuspected || activityTail.paused

        // Two severities of "this mode gave too much", both fed to the episode-outcome learners.
        // lowActive is a frank hypo (or its rebound window); undershootActive is the band just
        // above it — BG handed back at, say, 4.4 mmol. The learners used to see only the former,
        // so a mode that reliably undershot to just above the low guard was scored as a success
        // every time and never weakened.
        //
        // The band spans the lower half of the room between the low guard and target, so it
        // follows both settings instead of being pinned to a separate preference, and a mode that
        // lands on target can never be read as having undershot.
        val lowGuardNowMgdl   = spMgdl(UnitDoubleKey.ApsSmartInsulinLowGuard)
        val undershootCeiling = undershootCeilingMgdl(lowGuardNowMgdl, profile.getTargetMgdl())
        val lowActiveNow      = bgBelowGuard(glucoseStatus.glucose, lowGuardNowMgdl) || inReboundWindow

        // The session's own evidence gathering needs the low state, so it lands here rather than
        // with the dosing read above.
        activitySessionManager.onCycle(now, glucoseStatus.glucose, targetBg, lowActiveNow)
        val undershootNow     = !lowActiveNow && glucoseStatus.glucose < undershootCeiling

        // -- Per-mode ISF episode-outcome learner -------------------------------
        // Judges each completed mode activation after a settling tail (low → weaken,
        // still high / DURA had to rescue → strengthen, ate again → skip). Uses the
        // FASTING ISF for its tail contamination check — the mode ISF no longer applies
        // once the mode has ended.

        // Second helping inside a mode's own window: detected from the shape of absorption plus a
        // second BG peak, and it stops the episode being scored either way.
        val activeModeForWave = mealOverrideManager.activeMealMode
        if (activeModeForWave == null || mealOverrideManager.modeStartMs != secondWaveModeStartMs) {
            secondWaveDetector.reset()
            secondWaveModeStartMs = mealOverrideManager.modeStartMs
        }
        if (activeModeForWave != null) {
            secondWaveDetector.onCycle(
                bgMgdl = glucoseStatus.glucose, deltaMgdl = glucoseStatus.delta,
                activityPerMin = iobArray.firstOrNull()?.activity ?: 0.0,
                isfMgdl = trueIsfMgdl, carbRatio = profile.getIc()
            )
        }
        val secondWaveNow = secondWaveDetector.detected

        modeIsfLearner.onCycle(
            activeModeNow  = mealOverrideManager.activeMealMode,
            modeStartMs    = mealOverrideManager.modeStartMs,
            bgMgdl         = glucoseStatus.glucose,
            targetMgdl     = targetBg,
            lowActive      = lowActiveNow,
            duraMult       = duraMultThisCycle,
            deltaMgdl      = glucoseStatus.delta,
            activityPerMin = iobArray.firstOrNull()?.activity ?: 0.0,
            fastingIsfMgdl = trueIsfMgdl,
            carbRatio      = profile.getIc(),
            nowMs          = now,
            baseSignature  = mealOverrideManager.activeMealMode?.let { modeIsfOverrideSignature(it, currentHour) } ?: 0.0,
            exerciseSuspected = exerciseSuspected,
            undershootActive  = undershootNow,
            // Whether the shape knob still has travel. Asked of the entry-fraction learner rather
            // than recomputed here, so both learners agree on when front-loading is exhausted.
            entryShapeRailed  = mealOverrideManager.activeMealMode
                ?.let { uamEntryFractionLearner.isShapeRailed(it) } ?: false,
            secondWave        = secondWaveNow,
            preBolused        = (mealOverrideManager.activeDoseU ?: 0.0) > 0.0,
            // Entered carbs are not a dose this loop chose, so the dose-judging learners sit them
            // out. Only the insulin-curve learner (DIA/peak) runs against a carb episode.
            learningEnabled   = modeIsfLearningEnabled() && !carbEpisodeActive,
            modeInsulinShare  = modeInsulinShare,
            // Watch the mode's own insulin out rather than a fixed window — the share decides how
            // much of a late low is actually its, so a longer watch can't mis-charge one.
            watchMs           = postModeWatchMs(),
            pfWindow          = pfWindowForMode(mealOverrideManager.activeMealMode, currentHour)
        )

        // -- UAM entry-fraction shape learner -----------------------------------
        // Learns how front-loaded the UAM entry burst should be, arbitrated against the ISF
        // learner by timing: early lows are shape evidence (this learner), late lows and
        // ended-high are magnitude evidence (ModeIsfLearner). Same base-change reset pattern.
        uamEntryFractionLearner.onCycle(
            activeModeNow  = mealOverrideManager.activeMealMode,
            modeStartMs    = mealOverrideManager.modeStartMs,
            bgMgdl         = glucoseStatus.glucose,
            targetMgdl     = targetBg,
            lowActive      = lowActiveNow,
            deltaMgdl      = glucoseStatus.delta,
            activityPerMin = iobArray.firstOrNull()?.activity ?: 0.0,
            fastingIsfMgdl = trueIsfMgdl,
            carbRatio      = profile.getIc(),
            nowMs          = now,
            baseSignature  = mealOverrideManager.activeMealMode?.let { entrySmbFractionForMode(it) } ?: 0.0,
            exerciseSuspected = exerciseSuspected,
            // The learner holds its verdict until the entry insulin has actually peaked. Learned
            // per mode by ProfileLearner, so a short mode isn't judged on its own clock.
            insulinPeakMins   = mealOverrideManager.activeMealMode
                ?.let { profileLearner.getProfile(it).peakMinutes } ?: 0.0,
            secondWave        = secondWaveNow || carbEpisodeActive
        )

        // Shape evidence with nowhere left to go: the entry burst already carries the whole
        // computed requirement, so the only actuator still holding travel is the mode's ISF.
        // Forwarded here rather than inside either learner so the arbitration between the two
        // stays readable in one place.
        uamEntryFractionLearner.consumeMagnitudeHandoff()?.let { (railedMode, episodeStartMs) ->
            modeIsfLearner.noteShapeRailed(railedMode, episodeStartMs)
        }

        // -- DURA strength learner (crash direction only) -----------------------
        // Learns DOWN when DURA engaged and the episode crashed. Never learns up: "DURA had to
        // rescue this" is already ModeIsfLearner's signal to strengthen the mode's base ISF, and
        // acting on it here too would correct one problem twice.
        duraStrengthLearner.onCycle(
            activeModeNow     = mealOverrideManager.activeMealMode,
            modeStartMs       = mealOverrideManager.modeStartMs,
            lowActive         = lowActiveNow,
            duraMult          = duraMultThisCycle,
            exerciseSuspected = exerciseSuspected,
            nowMs             = now,
            baseSignature     = mealOverrideManager.activeDuraStrength,
            undershootActive  = undershootNow,
            pfWindow          = pfWindowForMode(mealOverrideManager.activeMealMode, currentHour),
            bgMgdl            = glucoseStatus.glucose,
            targetMgdl        = targetBg,
            duraStuckMinutes  = if (duraActive) duraIsfTracker.stuckMinutesForDisplay else 0.0,
            duraAtCeiling     = duraAtCeilingThisCycle,
            duraAtFloor       = duraAtFloorThisCycle,
            modeInsulinShare  = modeInsulinShare,
            watchMs           = postModeWatchMs(),
            learningEnabled   = duraLearningEnabled()
        )

        // -- UAM entry SMB fraction --------------------------------------------
        // For the first N SMBs after a UAM mode fires, use a reduced fraction
        // to soften the front-end response and avoid stacking before IOB propagates.
        // P/F excluded — it's a tail correction, not a meal entry event.
        // In FASTING mode, uamSmbFraction = SMB_DELIVERY_FRACTION (0.5).
        var currentModeIsUam = mealMode.isUam && mealMode != MealMode.UAM_PROTEIN_FAT
        if (currentModeIsUam && uamEntryModeStartMs == 0L) {
            uamEntryModeStartMs   = now
            uamEntrySmbsDelivered = 0
        } else if (!currentModeIsUam) {
            uamEntryModeStartMs   = 0L
            uamEntrySmbsDelivered = 0
        }
        val entrySmbCount    = sp.getInt(IntKey.ApsSmartInsulinUamEntrySmbCount.key, IntKey.ApsSmartInsulinUamEntrySmbCount.defaultValue)
        // Learned shape adjustment on top of the configured fraction (no-op until the entry
        // learner has evidence). The COUNT stays exactly as configured — deliberately manual.
        val entrySmbFraction = uamEntryFractionLearner.adjustedFraction(mealMode, entrySmbFractionForMode(mealMode))
        var uamSmbFraction   = if (currentModeIsUam && uamEntrySmbsDelivered < entrySmbCount)
            entrySmbFraction else SMB_DELIVERY_FRACTION

        // Re-evaluate if UAM fired this cycle
        if (justFiredMode != null && latestMealMode != mealMode) {
            currentModeIsUam = mealMode.isUam && mealMode != MealMode.UAM_PROTEIN_FAT
            if (currentModeIsUam && uamEntryModeStartMs == 0L) {
                uamEntryModeStartMs   = now
                uamEntrySmbsDelivered = 0
            }
            uamSmbFraction = if (currentModeIsUam && uamEntrySmbsDelivered < entrySmbCount)
                entrySmbFraction else SMB_DELIVERY_FRACTION
        }

        val stftAdjusted = stftController.onLoopCycle(profile.getTargetMgdl(), glucoseStatus.glucose, glucoseStatus.delta, glucoseStatus.shortAvgDelta, mealMode, isTempTarget, bgWentLow, inReboundWindow, cgmState.inWarmup, glucoseStatus.date)
        val stftTargetMgdl = if (!isTempTarget) stftAdjusted else targetBg

        profileLearner.updateInsulinDefaults(diaMins = profile.iCfg.dia * 60.0, peakMins = profile.iCfg.peak.toDouble())
        val oapsProfile = OapsProfile(dia = profile.iCfg.dia, min_5m_carbimpact = 0.0, max_iob = constraintsChecker.getMaxIOBAllowed().value(), max_daily_basal = profile.getMaxDailyBasal(), max_basal = constraintsChecker.getMaxBasalAllowed(profile).value(), min_bg = profile.getTargetLowMgdl(), max_bg = profile.getTargetHighMgdl(), target_bg = stftTargetMgdl, carb_ratio = profile.getIc(), sens = dosingIsfMgdl, autosens_adjust_targets = false, max_daily_safety_multiplier = sp.getDouble(DoubleKey.ApsMaxDailyMultiplier.key, DoubleKey.ApsMaxDailyMultiplier.defaultValue), current_basal_safety_multiplier = sp.getDouble(DoubleKey.ApsMaxCurrentBasalMultiplier.key, DoubleKey.ApsMaxCurrentBasalMultiplier.defaultValue), lgsThreshold = profileUtil.convertToMgdlDetect(sp.getDouble(UnitDoubleKey.ApsLgsThreshold.key, UnitDoubleKey.ApsLgsThreshold.defaultValue)).toInt(), high_temptarget_raises_sensitivity = false, low_temptarget_lowers_sensitivity = false, sensitivity_raises_target = sp.getBoolean(BooleanKey.ApsSensitivityRaisesTarget.key, BooleanKey.ApsSensitivityRaisesTarget.defaultValue), resistance_lowers_target = sp.getBoolean(BooleanKey.ApsResistanceLowersTarget.key, BooleanKey.ApsResistanceLowersTarget.defaultValue), adv_target_adjustments = SMBDefaults.adv_target_adjustments, exercise_mode = SMBDefaults.exercise_mode, half_basal_exercise_target = SMBDefaults.half_basal_exercise_target, maxCOB = SMBDefaults.maxCOB, skip_neutral_temps = activePlugin.activePump.setNeutralTempAtFullHour(), remainingCarbsCap = SMBDefaults.remainingCarbsCap, enableUAM = constraintsChecker.isUAMEnabled().value(), A52_risk_enable = SMBDefaults.A52_risk_enable, SMBInterval = sp.getInt(IntKey.ApsMaxSmbFrequency.key, IntKey.ApsMaxSmbFrequency.defaultValue), enableSMB_with_COB = sp.getBoolean(BooleanKey.ApsUseSmbWithCob.key, BooleanKey.ApsUseSmbWithCob.defaultValue), enableSMB_with_temptarget = sp.getBoolean(BooleanKey.ApsUseSmbWithLowTt.key, BooleanKey.ApsUseSmbWithLowTt.defaultValue), allowSMB_with_high_temptarget = sp.getBoolean(BooleanKey.ApsUseSmbWithHighTt.key, BooleanKey.ApsUseSmbWithHighTt.defaultValue), enableSMB_always = sp.getBoolean(BooleanKey.ApsUseSmbAlways.key, BooleanKey.ApsUseSmbAlways.defaultValue), enableSMB_after_carbs = sp.getBoolean(BooleanKey.ApsUseSmbAfterCarbs.key, BooleanKey.ApsUseSmbAfterCarbs.defaultValue), maxSMBBasalMinutes = Int.MAX_VALUE, maxUAMSMBBasalMinutes = Int.MAX_VALUE, bolus_increment = activePlugin.activePump.pumpDescription.bolusStep, carbsReqThreshold = sp.getInt(IntKey.ApsCarbsRequestThreshold.key, IntKey.ApsCarbsRequestThreshold.defaultValue), current_basal = ch.fromPump(activePlugin.activePump.baseBasalRate), temptargetSet = isTempTarget, autosens_max = sp.getDouble(DoubleKey.AutosensMax.key, DoubleKey.AutosensMax.defaultValue), out_units = if (isMmol) "mmol/L" else "mg/dl", variable_sens = 0.0, insulinDivisor = 0, TDD = 0.0)

        aggressionLearner.recordBg(glucoseStatus.glucose, 70.0, 180.0, mealMode, activityPausesLearning || cgmState.suppressLearning || inPostMealLockout, now)
        val aggressiveness = if (mealMode != MealMode.FASTING) 1.0 else aggressionLearner.aggressiveness.coerceAtMost(circadianLearner.aggrCeiling(currentHour, currentDow, currentMinute))

        // Use last MANUAL bolus only — SMBs fire every 5min during fasting and would permanently
        // block the basal learner if included. BS.Type.NORMAL = manual/wizard bolus only.
        // NOTE: the old flat BasalLearner is no longer invoked here. CircadianLearner's
        // per-hour basal signals (drift, negIOB, predTrim, cyclic-delta, sub-target) fully
        // supersede it, and running both double-counted the same fasting-drift signal while
        // flattening the per-hour shape in the SI tab table (the flat learner's single global
        // value dominated the additive combination). See combinedBasalMultiplier() above.
        // The ApsSmartInsulinBasalLearningEnabled toggle and basalLearner instance are left in
        // place (resettable, still present in settings) in case this needs revisiting, but the
        // toggle no longer has any effect on dosing or display. minsLastManualBolus (the old
        // learner's bolus-recency gate input) is removed too since nothing reads it now.
        val basalMultiplier = combinedBasalMultiplier(currentHour, currentDow, currentMinute)

        val REBOUND_LOW_THRESHOLD_MGDL = spMgdl(UnitDoubleKey.ApsSmartInsulinLowGuard)
        if (bgBelowGuard(glucoseStatus.glucose, REBOUND_LOW_THRESHOLD_MGDL)) {
            if (!bgWentLow) { iobAtLowTime = iobArray.firstOrNull()?.iob ?: 0.0; shortAvgDeltaAtLow = glucoseStatus.shortAvgDelta / 18.0; if (mealMode.isUam) mealOverrideManager.cancelOverride() }
            else if (reboundWindowStartMs > 0L) {
                // BG had come back above the guard and the recovery window was running — now it's
                // below again. A real rebound in between (a decent rise, or held a while) makes this
                // a second low and adds another base window; a reading or two of flicker doesn't.
                // Either way the window stops here and restarts from 30% on the next crossing, so
                // insulin comes back in from the bottom of the taper rather than where it had got to.
                if (relowTracker.onDipBelowGuard(REBOUND_LOW_THRESHOLD_MGDL)) secondLowOccurred = true
                reboundWindowStartMs = 0L
                softLandingBypass = false
            }
            if (glucoseStatus.glucose < minBgDuringLow) minBgDuringLow = glucoseStatus.glucose
            bgWentLow = true
        }
        // Exact complement of the low test above — the pair has to partition, or a BG in the gap
        // would be neither low nor recovered and the window would never arm.
        if (bgWentLow && !bgBelowGuard(glucoseStatus.glucose, REBOUND_LOW_THRESHOLD_MGDL)) {
            if (reboundWindowStartMs == 0L) reboundWindowStartMs = now
            relowTracker.recordAboveGuard(glucoseStatus.glucose, glucoseStatus.date)
        }
        if (bgWentLow && reboundWindowStartMs > 0L && !inReboundWindow) {
            reboundWindowStartMs = 0L; bgWentLow = false; minBgDuringLow = Double.MAX_VALUE; secondLowOccurred = false; softLandingBypass = false
            relowTracker.reset()
        }

        val microBolusAllowed = constraintsChecker.isSMBModeEnabled(ConstraintObject(tempBasalFallback.not(), aapsLogger)).value()
        // determine_basal is the only path in invoke() that could throw and leave lastAPSResult
        // null with nothing said about it — every other early return sets a reason the user can
        // see. An exception here means no decision this cycle; surface that rather than looking
        // like the loop simply had nothing to do.
        val apsResult = try {
            determineBasalSmartInsulin.determine_basal(
            glucoseStatus            = glucoseStatus,
            currentTemp              = currentTemp,
            iobArray                 = iobArray,
            oapsProfile              = oapsProfile,
            mealData                 = mealData,
            profile                  = profile,
            learnedProfile           = profileLearner.getProfile(if (mealMode.isUam) MealMode.entries.find { it.label == mealMode.label.removePrefix("UAM ") } ?: mealMode else mealMode),
            mealMode                 = mealMode,
            lowGuardMmol             = spMgdl(UnitDoubleKey.ApsSmartInsulinLowGuard)/18.0,
            warnGuardMmol            = spMgdl(UnitDoubleKey.ApsSmartInsulinWarnGuard)/18.0,
            maxSmbU                  = sp.getDouble(DoubleKey.ApsSmartInsulinMaxSmb.key, DoubleKey.ApsSmartInsulinMaxSmb.defaultValue),
            maxTbrU                  = sp.getDouble(DoubleKey.ApsSmartInsulinMaxTbr.key, DoubleKey.ApsSmartInsulinMaxTbr.defaultValue),
            aggressiveness           = aggressiveness,
            tirSummary               = aggressionLearner.tirSummary,
            basalMultiplier          = basalMultiplier,
            dosingIsfMgdl            = dosingIsfMgdl,
            microBolusAllowed        = microBolusAllowed,
            inReboundWindow          = inReboundWindow,
            msSinceLastSuspend       = msSinceLastSuspend,
            currentTime              = now,
            isTempTarget             = isTempTarget,
            profileTargetMgdl        = profile.getTargetMgdl(),
            dawnWindowStartHour      = sp.getInt(IntKey.ApsSmartInsulinDawnWindowStartHour.key, IntKey.ApsSmartInsulinDawnWindowStartHour.defaultValue),
            dawnWindowEndHour        = sp.getInt(IntKey.ApsSmartInsulinDawnWindowEndHour.key, IntKey.ApsSmartInsulinDawnWindowEndHour.defaultValue),
            dawnSmbReduction         = sp.getDouble(DoubleKey.ApsSmartInsulinDawnSmbReduction.key, DoubleKey.ApsSmartInsulinDawnSmbReduction.defaultValue),
            bgWentLow                = bgWentLow,
            activityLevel            = activityMonitor.level,
            activityTargetOffsetMmol = activityOffsetMmol(sp, activityMonitor),
            cgmSmbFraction           = cgmState.smbFraction,
            cgmDeltaPlausible        = cgmState.deltaPlausible,
            cgmWarmupReason          = cgmState.reason,
            uamSmbFraction           = uamSmbFraction,
            reboundWindowMins        = reboundGuardMs / 60_000.0,  // total window incl. rollercoaster extension
            circCeil                 = circadianLearner.aggrCeiling(currentHour, currentDow, currentMinute),
            fuelTrimStrength         = circadianLearner.trimStrength,
            isMmol                   = isMmol,
            duraStatusText           = duraStatusText,
            sessionInsulinFraction   = sessionDose.insulinFraction,
            sessionStatusText        = sessionStatus,
            riseTurn                 = riseTurnNow(glucoseStatus.delta)
            )
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "SmartInsulin: determine_basal threw — no decision this cycle", e)
            lastAPSResult = null
            lastAPSRun = now
            lastRunError = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
            return
        }

        lastAPSResult = apsResult; lastAPSRun = now; lastRunError = null

        // Increment UAM entry SMB counter if an entry-fraction SMB was delivered
        val fractionUsed = uamSmbFraction
        val wasEntrySmb = currentModeIsUam && apsResult.smb > 0.0 && uamEntrySmbsDelivered < entrySmbCount
        if (wasEntrySmb) {
            uamEntrySmbsDelivered++
            apsResult.reason += " | UAMEntry: SMB ${uamEntrySmbsDelivered}/$entrySmbCount @${(fractionUsed * 100).toInt()}%"
        }
        if (sp.getBoolean(BooleanKey.ApsSmartInsulinEnableLearning.key, BooleanKey.ApsSmartInsulinEnableLearning.defaultValue) && glucoseStatus.noise <= 1.5 && activityMonitor.level == ActivityMonitor.ActivityLevel.SEDENTARY) bolusCurveTracker.onLoopCycle(glucoseStatus, mealMode, iobArray, dosingIsfMgdl)

        // Snapshot state for Overview (re-computed live in overviewState() for time-sensitive parts)
        cachedCgmSuppressLearning = cgmState.suppressLearning

        val pb2DoseU = mealOverrideManager.activePb2DoseU
        val pb2Line = when {
            // PB2 pending — not yet delivered, show it's coming
            mealOverrideManager.preBolus2Pending -> {
                val secsLeft = mealOverrideManager.preBolus2SecondsRemaining ?: 0L
                when {
                    secsLeft > 60  -> "PB2 in ${secsLeft / 60}m"
                    secsLeft > 0   -> "PB2 in ${secsLeft}s"
                    else           -> "PB2 waiting for gates"
                }
            }
            // PB2 delivered — show delivered amount briefly then clear
            pb2DoseU != null && pb2DoseU > 0.0 && !mealOverrideManager.preBolus2Pending -> {
                "PB2 delivered ${"%.2f".format(pb2DoseU)}U"
            }
            else -> null
        }

        val pb3DoseU = mealOverrideManager.activePb3DoseU
        val pb3Line = when {
            mealOverrideManager.preBolus3Pending -> {
                val secsLeft = mealOverrideManager.preBolus3SecondsRemaining
                when {
                    secsLeft != null && secsLeft > 60 -> "PB3 in ${secsLeft / 60}m"
                    secsLeft != null && secsLeft > 0  -> "PB3 in ${secsLeft}s"
                    else                              -> "PB3 waiting for gates"
                }
            }
            pb3DoseU != null && pb3DoseU > 0.0 && !mealOverrideManager.preBolus3Pending -> {
                "PB3 delivered ${"%.2f".format(pb3DoseU)}U"
            }
            else -> null
        }

        cachedOverviewState = SmartInsulinOverview.OverviewState(
            modeLine = "Meal: ${overviewMealLabel(mealMode)}", // Re-computed in overviewState()
            pb2Line = pb2Line,
            pb3Line = pb3Line,
            learningState = if (highTempTarget) "off: High temp target" else getLearningState()
        )

        journalLearningDigest(currentHour, currentDow)
        rxBus.send(EventOpenAPSUpdateGui())
    }

    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? = glucoseStatusCalculatorSMB.getGlucoseStatusData(allowOldData)

    override suspend fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (isEnabled()) {
            val maxIobPref = preferences.get(DoubleKey.ApsSmbMaxIob)
            maxIob.setIfSmaller(maxIobPref, rh.gs(ApsStrings.limiting_iob, maxIobPref, rh.gs(ApsStrings.maxvalueinpreferences)), this)
            maxIob.setIfSmaller(hardLimits.maxIobSMB(), rh.gs(ApsStrings.limiting_iob, hardLimits.maxIobSMB(), rh.gs(ApsStrings.hardlimit)), this)
        }
        return maxIob
    }

    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        if (isEnabled()) {
            var maxBasal = preferences.get(DoubleKey.ApsMaxBasal)
            if (maxBasal < profile.getMaxDailyBasal()) maxBasal = profile.getMaxDailyBasal()
            absoluteRate.setIfSmaller(maxBasal, rh.gs(CoreUiStrings.limitingbasalratio, maxBasal, rh.gs(ApsStrings.maxvalueinpreferences)), this)
            val maxFromBasalMultiplier = floor(preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier) * profile.getBasal() * 100) / 100
            absoluteRate.setIfSmaller(maxFromBasalMultiplier, rh.gs(CoreUiStrings.limitingbasalratio, maxFromBasalMultiplier, rh.gs(ApsStrings.max_basal_multiplier)), this)
            val maxFromDaily = floor(profile.getMaxDailyBasal() * preferences.get(DoubleKey.ApsMaxDailyMultiplier) * 100) / 100
            absoluteRate.setIfSmaller(maxFromDaily, rh.gs(CoreUiStrings.limitingbasalratio, maxFromDaily, rh.gs(ApsStrings.max_daily_basal_multiplier)), this)
        }
        return absoluteRate
    }

    override suspend fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (!preferences.get(BooleanKey.ApsUseSmb)) value.set(false, rh.gs(ApsStrings.smb_disabled_in_preferences), this)
        return value
    }

    override fun isUAMEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (!preferences.get(BooleanKey.ApsUseUam)) value.set(false, rh.gs(ApsStrings.uam_disabled_in_preferences), this)
        return value
    }

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "smart_insulin_settings",
        title = ApsStrings.smart_insulin,
        items = listOf(
            PreferenceSubScreenDef(
                key = "si_safety_screen",
                title = ApsStrings.si_cat_safety,
                icon = Icons.Filled.Shield,
                items = listOf(
                    DoubleKey.ApsSmbMaxIob,
                    DoubleKey.ApsSmartInsulinMaxSmb,
                    DoubleKey.ApsSmartInsulinMaxTbr,
                    IntKey.ApsMaxSmbFrequency,
                    DoubleKey.ApsSmartInsulinAggressionMax
                )
            ),
            PreferenceSubScreenDef(
                key = "si_low_screen",
                title = ApsStrings.si_cat_low,
                icon = Icons.AutoMirrored.Filled.TrendingDown,
                items = listOf(
                    UnitDoubleKey.ApsLgsThreshold,
                    UnitDoubleKey.ApsSmartInsulinLowGuard,
                    UnitDoubleKey.ApsSmartInsulinWarnGuard,
                    IntKey.ApsSmartInsulinReboundWindowMins
                )
            ),
            PreferenceSubScreenDef(
                key = "si_learning_screen",
                title = ApsStrings.si_cat_learning,
                icon = Icons.Filled.Psychology,
                items = listOf(
                    BooleanKey.ApsSmartInsulinEnableLearning,
                    BooleanKey.ApsSmartInsulinBasalLearningEnabled,
                    BooleanKey.ApsSmartInsulinModeIsfLearningEnabled,
                    BooleanKey.ApsSmartInsulinDuraLearningEnabled,
                    IntKey.ApsSmartInsulinLearningBias,
                    IntKey.ApsSmartInsulinPostModeLockoutMins
                )
            ),
            PreferenceSubScreenDef(
                key = "si_meal_modes_screen",
                title = ApsStrings.si_cat_meal,
                icon = Icons.Filled.Restaurant,
                items = listOf(
                    DoubleKey.ApsSmartInsulinMaxPreBolus,
                    DoubleKey.ApsSmartInsulinPreBolus2DefaultU,
                    IntKey.ApsSmartInsulinPreBolus2DefaultDelayMins,
                    UnitDoubleKey.ApsSmartInsulinBreakfastIsf,
                    UnitDoubleKey.ApsSmartInsulinLunchIsf,
                    UnitDoubleKey.ApsSmartInsulinDinnerIsf,
                    UnitDoubleKey.ApsSmartInsulinLowCarbIsf,
                    UnitDoubleKey.ApsSmartInsulinExtendedIsf
                )
            ),
            PreferenceSubScreenDef(
                key = "si_cgm_screen",
                title = ApsStrings.si_cat_cgm,
                icon = Icons.Filled.Sensors,
                items = listOf(
                    BooleanKey.ApsSmartInsulinCgmWarmupEnabled,
                    BooleanKey.ApsSmartInsulinStftCgmWarmupBlock,
                    BooleanKey.ApsSmartInsulinUamCgmWarmupBlock
                )
            ),
            PreferenceSubScreenDef(
                key = "si_activity_screen",
                title = ApsStrings.si_cat_activity,
                icon = Icons.AutoMirrored.Filled.DirectionsRun,
                items = listOf(
                    UnitDoubleKey.ApsSmartInsulinActivityLightTarget,
                    UnitDoubleKey.ApsSmartInsulinActivityModerateTarget,
                    UnitDoubleKey.ApsSmartInsulinActivityHeavyTarget,
                    DoubleKey.ApsSmartInsulinRestingHrBpm
                )
            ),
            PreferenceSubScreenDef(
                key = "si_dawn_screen",
                title = ApsStrings.si_cat_dawn,
                icon = Icons.Filled.WbTwilight,
                items = listOf(
                    IntKey.ApsSmartInsulinDawnWindowStartHour,
                    IntKey.ApsSmartInsulinDawnWindowEndHour,
                    DoubleKey.ApsSmartInsulinDawnSmbReduction
                )
            ),
            PreferenceSubScreenDef(
                key = "si_uam_screen",
                title = ApsStrings.si_cat_uam,
                icon = Icons.Filled.AutoGraph,
                items = listOf(
                    BooleanKey.ApsSmartInsulinUamEnabled,
                    UnitDoubleKey.ApsSmartInsulinUamTriggerThreshold,
                    UnitDoubleKey.ApsSmartInsulinUamRiseMinDelta,
                    IntKey.ApsSmartInsulinUamRiseConsecutiveReadings,
                    UnitDoubleKey.ApsSmartInsulinUamBurstThreshold,
                    BooleanKey.ApsSmartInsulinUamWobbleTolerance,
                    DoubleKey.ApsSmartInsulinUamEntrySmbFraction,
                    IntKey.ApsSmartInsulinUamEntrySmbCount,
                    IntKey.ApsSmartInsulinUamDayStartHour,
                    IntKey.ApsSmartInsulinUamNightCutoffHour,
                    BooleanKey.ApsSmartInsulinUamDuraEnabled,
                    UnitDoubleKey.ApsSmartInsulinUamDuraFloor,
                    DoubleKey.ApsSmartInsulinUamDuraStrength
                )
            ),
            PreferenceSubScreenDef(
                key = "si_uam_breakfast_screen",
                title = ApsStrings.si_cat_uam_breakfast,
                items = listOf(
                    BooleanKey.ApsSmartInsulinUamBreakfastEnabled,
                    IntKey.ApsSmartInsulinUamBreakfastStartHour,
                    IntKey.ApsSmartInsulinUamBreakfastEndHour,
                    IntKey.ApsSmartInsulinUamBreakfastDurationMins,
                    UnitDoubleKey.ApsSmartInsulinUamBreakfastIsf,
                    DoubleKey.ApsSmartInsulinUamEntrySmbFractionBreakfast
                )
            ),
            PreferenceSubScreenDef(
                key = "si_uam_lunch_screen",
                title = ApsStrings.si_cat_uam_lunch,
                items = listOf(
                    BooleanKey.ApsSmartInsulinUamLunchEnabled,
                    IntKey.ApsSmartInsulinUamLunchStartHour,
                    IntKey.ApsSmartInsulinUamLunchEndHour,
                    IntKey.ApsSmartInsulinUamLunchDurationMins,
                    UnitDoubleKey.ApsSmartInsulinUamLunchIsf,
                    DoubleKey.ApsSmartInsulinUamEntrySmbFractionLunch
                )
            ),
            PreferenceSubScreenDef(
                key = "si_uam_afternoon_screen",
                title = ApsStrings.si_cat_uam_afternoon,
                items = listOf(
                    BooleanKey.ApsSmartInsulinUamAfternoonEnabled,
                    IntKey.ApsSmartInsulinUamAfternoonStartHour,
                    IntKey.ApsSmartInsulinUamAfternoonStartMinute,
                    IntKey.ApsSmartInsulinUamAfternoonEndHour,
                    IntKey.ApsSmartInsulinUamAfternoonEndMinute,
                    IntKey.ApsSmartInsulinUamAfternoonDurationMins,
                    UnitDoubleKey.ApsSmartInsulinUamAfternoonIsf,
                    DoubleKey.ApsSmartInsulinUamEntrySmbFractionAfternoon
                )
            ),
            PreferenceSubScreenDef(
                key = "si_uam_dinner_screen",
                title = ApsStrings.si_cat_uam_dinner,
                items = listOf(
                    BooleanKey.ApsSmartInsulinUamDinnerEnabled,
                    IntKey.ApsSmartInsulinUamDinnerStartHour,
                    IntKey.ApsSmartInsulinUamDinnerStartMinute,
                    IntKey.ApsSmartInsulinUamDinnerEndHour,
                    IntKey.ApsSmartInsulinUamDinnerEndMinute,
                    IntKey.ApsSmartInsulinUamDinnerDurationMins,
                    UnitDoubleKey.ApsSmartInsulinUamDinnerIsf,
                    DoubleKey.ApsSmartInsulinUamEntrySmbFractionDinner
                )
            ),
            PreferenceSubScreenDef(
                key = "si_uam_snack_screen",
                title = ApsStrings.si_cat_uam_snack,
                items = listOf(
                    BooleanKey.ApsSmartInsulinUamSnackEnabled,
                    IntKey.ApsSmartInsulinUamSnackStartHour,
                    IntKey.ApsSmartInsulinUamSnackEndHour,
                    IntKey.ApsSmartInsulinUamSnackDurationMins,
                    UnitDoubleKey.ApsSmartInsulinUamSnackIsf,
                    DoubleKey.ApsSmartInsulinUamEntrySmbFractionSnack
                )
            ),
            PreferenceSubScreenDef(
                key = "si_pf_screen",
                title = ApsStrings.si_cat_pf,
                icon = Icons.Filled.SetMeal,
                items = listOf(
                    BooleanKey.ApsSmartInsulinUamProteinFatEnabled,
                    UnitDoubleKey.ApsSmartInsulinUamProteinFatThreshold,
                    IntKey.ApsSmartInsulinUamProteinFatStuckReadings,
                    IntKey.ApsSmartInsulinUamProteinFatDurationMins,
                    BooleanKey.ApsSmartInsulinUamPfTakeover,
                    UnitDoubleKey.ApsSmartInsulinUamProteinFatIsf,
                    IntKey.ApsSmartInsulinUamProteinFatDayStartHour,
                    IntKey.ApsSmartInsulinUamProteinFatDayEndHour,
                    UnitDoubleKey.ApsSmartInsulinUamProteinFatDayIsf,
                    IntKey.ApsSmartInsulinUamProteinFatNightStartHour,
                    IntKey.ApsSmartInsulinUamProteinFatNightEndHour,
                    UnitDoubleKey.ApsSmartInsulinUamProteinFatNightIsf,
                    IntKey.ApsSmartInsulinUamProteinFatOvernightStartHour,
                    IntKey.ApsSmartInsulinUamProteinFatOvernightEndHour,
                    UnitDoubleKey.ApsSmartInsulinUamProteinFatOvernightIsf,
                    BooleanKey.ApsSmartInsulinPfDuraEnabled,
                    UnitDoubleKey.ApsSmartInsulinPfDuraFloor,
                    DoubleKey.ApsSmartInsulinPfDuraStrength
                )
            )
        ),
        icon = pluginDescription.icon
    )

}