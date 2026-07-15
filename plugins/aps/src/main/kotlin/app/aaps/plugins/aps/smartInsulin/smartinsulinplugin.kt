package app.aaps.plugins.aps.smartInsulin

import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
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
import app.aaps.core.objects.extensions.getPassedDurationToTimeInMinutes
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.target
import app.aaps.core.validators.preferences.*
import app.aaps.plugins.aps.smartInsulin.SmartInsulinFragment
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor

@Singleton
open class SmartInsulinPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
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
    private val cgmWarmupGuard:   CgmWarmupGuard
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.APS)
        .fragmentClass(SmartInsulinFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_generic_icon)
        .pluginName(R.string.smart_insulin)
        .shortName(R.string.smart_insulin_short)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .preferencesVisibleInSimpleMode(false)
        .showInList { config.APS }
        .description(R.string.smart_insulin_description),
    aapsLogger, rh
), APS, PluginConstraints, SmartInsulinOverview {

    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.SMB
    override var lastAPSResult: APSResult? = null

    var bgWentLow: Boolean = false
    var minBgDuringLow: Double = Double.MAX_VALUE
    var iobAtLowTime: Double = 0.0
    var shortAvgDeltaAtLow: Double = 0.0
    var secondLowOccurred: Boolean = false
    var softLandingBypass: Boolean = false
    var uamEntrySmbsDelivered: Int = 0
    var uamEntryModeStartMs: Long = 0L
    var learningDirtyUntilMs: Long = 0L
    private var nudgeDisplaySessionIsfMgdl: Double = 0.0
    private var nudgeDisplaySessionBasalU: Double = 0.0
    // Tracks the last nudge direction so we only capture session-start baseline once
    // per nudge episode (not on every cycle). Without this, "was ISF" keeps updating
    // mid-nudge and the "was ? now" comparison loses meaning.
    private var lastSeenNudgeState: String = "INACTIVE"
    @Volatile private var cachedProfileIsf: Double = 0.0
    @Volatile private var cachedProfileBasal: Double = 0.0
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
    // Recovery (rebound) window length. Base comes from the user preference
    // (ApsSmartInsulinReboundWindowMins); each consecutive rollercoaster extends it by
    // ROLLER_REBOUND_EXTENSION_MS, capped at ROLLER_REBOUND_EXTENSION_MAX_MS. Computed live so
    // preference changes AND rollercoaster detection take effect immediately, and so the gate
    // (inReboundWindow) always matches totalReboundWindowMins shown in the UI.
    val reboundGuardMs: Long
        get() {
            val baseMs = sp.getInt(IntKey.ApsSmartInsulinReboundWindowMins.key, IntKey.ApsSmartInsulinReboundWindowMins.defaultValue)
                .coerceAtLeast(0) * 60_000L
            val extMs  = (circadianLearner.consecutiveRollercoasters.coerceAtLeast(0) * ROLLER_REBOUND_EXTENSION_MS)
                .coerceAtMost(ROLLER_REBOUND_EXTENSION_MAX_MS)
            return baseMs + extMs
        }

    @Volatile private var cachedLearningEnabled: Boolean = false
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
        private const val MIN_TOTAL_BASAL_MULT = 0.5
        private const val MAX_TOTAL_BASAL_MULT = 1.5
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
        dow: Int  = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1
    ): Double {
        return circadianLearner.basalMultiplier(hour, dow)
            .coerceIn(MIN_TOTAL_BASAL_MULT, MAX_TOTAL_BASAL_MULT)
    }

    fun resetAllLearners() {
        aggressionLearner.reset(); basalLearner.reset(); circadianLearner.reset(); profileLearner.resetProfiles()
        bgWentLow = false; reboundWindowStartMs = 0L; learningDirtyUntilMs = 0L
        previousMealModeForLockout = MealMode.FASTING; minBgDuringLow = Double.MAX_VALUE
        iobAtLowTime = 0.0; shortAvgDeltaAtLow = 0.0; secondLowOccurred = false
        softLandingBypass = false; uamEntrySmbsDelivered = 0; uamEntryModeStartMs = 0L
    }

    fun resetAggression() { aggressionLearner.reset(); aggressionLearner.recalculate() }
    fun resetIsf() { circadianLearner.resetIsf() }
    fun resetBasal() { basalLearner.reset(); circadianLearner.resetBasal() }
    fun resetCircadian() { circadianLearner.reset() }
    fun resetProfiles() { profileLearner.resetProfiles() }

    data class FragmentData(
        val hour: Int, val dayLabel: String, val mealMode: String, val modeRemMins: Int?,
        val aggressiveness: Double, val circCeil: Double, val isfMultiplier: Double,
        val nudgeSessionIsfMgdl: Double, val nudgeSessionBasalU: Double, val profileIsfMgdl: Double,
        val finalIsfMgdl: Double, val basalMultiplier: Double, val profileBasalU: Double, val finalBasalU: Double,
        val currentBgMgdl: Double, val profileTargetMgdl: Double, val lastBasalSignal: String,
        val lastAggrNudgeStatus: String, val lastAccelDebug: String, val lastPredTrimDebug: String,
        val inReboundWindow: Boolean, val reboundMins: Long, val reboundWindowMins: Int,
        val totalReboundWindowMins: Int, val consecutiveRollercoasters: Int, val hardLowPenaltyActive: Boolean,
        val softLandingBypass: Boolean, val bgWentLow: Boolean, val secondLowOccurred: Boolean,
        val minBgDuringLow: Double, val iobAtLowTime: Double, val isMmol: Boolean,
        val learningState: String, val activityLevel: String, val avgHrBpm: Int, val steps5min: Int,
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
        val lastCycleSummary: String
    )

    fun fragmentData(): FragmentData {
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

        val circRaw = buildString {
            val isfUnit = if (isMmol) "mmol/U" else "mg/dL/U"
            appendLine("  Hr  ISF ($isfUnit)   Basal (U/h)  Ceil   Conf")
            for (h in 0..23) {
                val marker = if (h == hour) "→" else " "
                val hIsfMult = circadianLearner.isfMultiplier(h)
                val hBasMult = combinedBasalMultiplier(h)
                val hIsf = if (profileIsf > 0 && hIsfMult > 0) (if (isMmol) "%.2f".format(profileIsf / hIsfMult / 18.0) else "%.1f".format(profileIsf / hIsfMult)) else "—"
                val hBas = if (profileBasal > 0) "%.3f".format(profileBasal * hBasMult) else "—"
                appendLine("$marker ${h.toString().padStart(2)}  $hIsf  $hBas  ${"%.3f".format(circadianLearner.aggrCeiling(h))}  ${"%.0f".format(circadianLearner.confidencePct(h))}%")
            }
        }

        val profRaw = buildString {
            MealMode.entries.forEach { mode ->
                val p = profileLearner.getProfile(mode)
                appendLine("${mode.label.padEnd(16)}: peak=${p.peakMinutes.toInt()}m  dia=${p.diaMinutes.toInt()}m  n=${p.sampleCount}")
            }
        }

        val postMealLeft = if (learningDirtyUntilMs > 0L && nowMs < learningDirtyUntilMs) (learningDirtyUntilMs - nowMs) / 60_000L else 0L

        return FragmentData(
            hour = hour, dayLabel = day, mealMode = activeMode?.label ?: "Fasting",
            modeRemMins = if (activeMode != null) (mealOverrideManager.modeTimeRemainingMs / 60_000).toInt() else null,
            aggressiveness = aggressionLearner.aggressiveness.coerceAtMost(circadianLearner.aggrCeiling(hour)),
            circCeil = circadianLearner.aggrCeiling(hour), isfMultiplier = isfMult,
            nudgeSessionIsfMgdl = nudgeDisplaySessionIsfMgdl, nudgeSessionBasalU = nudgeDisplaySessionBasalU,
            profileIsfMgdl = profileIsf, finalIsfMgdl = if (isfMult > 0) profileIsf / isfMult else 0.0,
            basalMultiplier = basalMult, profileBasalU = profileBasal, finalBasalU = roundedFinalBasal,
            currentBgMgdl = glucoseStatusProvider.glucoseStatusData?.glucose ?: 0.0, profileTargetMgdl = cachedProfileTarget,
            lastBasalSignal = circadianLearner.lastBasalSignal, lastAggrNudgeStatus = circadianLearner.lastAggrNudgeStatus,
            lastAccelDebug = circadianLearner.lastAccelDebug, lastPredTrimDebug = circadianLearner.lastPredTrimDebug,
            inReboundWindow = inReboundWindow, reboundMins = msSinceLastSuspend / 60_000,
            reboundWindowMins = sp.getInt(IntKey.ApsSmartInsulinReboundWindowMins.key, IntKey.ApsSmartInsulinReboundWindowMins.defaultValue),
            totalReboundWindowMins = (reboundGuardMs / 60_000).toInt(),
            consecutiveRollercoasters = circadianLearner.consecutiveRollercoasters,
            hardLowPenaltyActive = circadianLearner.lastHardLowPenaltyMs > 0L && (System.currentTimeMillis() - circadianLearner.lastHardLowPenaltyMs) < 90 * 60_000L,
            softLandingBypass = softLandingBypass, bgWentLow = bgWentLow, secondLowOccurred = secondLowOccurred,
            minBgDuringLow = minBgDuringLow, iobAtLowTime = iobAtLowTime, isMmol = isMmol,
            learningState = getLearningState(), activityLevel = activityMonitor.level.label,
            avgHrBpm = activityMonitor.avgHrBpm.toInt(), steps5min = activityMonitor.lastSteps5min,
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
            lastCycleSummary = circadianLearner.lastCycleSummary
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
            effectivePostMealLockout -> "off: Post-meal"
            activeMode == MealMode.UAM_PROTEIN_FAT -> "limited: P/F"
            isMealModeActive -> "limited: meal"
            else -> "Learning"
        }
    }

    override fun overviewState(): SmartInsulinOverview.OverviewState {
        // Recompute modeLine and learningState live so they're always current.
        // Avoids stale display between loop cycles (e.g. mode expired but state still shows P/F).
        val activeMode = mealOverrideManager.activeMealMode
        val now        = System.currentTimeMillis()

        val liveModeLine = activeMode?.let { mode ->
            val mins = mealOverrideManager.modeTimeRemainingMs / 60_000
            val label = if (mode.isUam) {
                when (mode) {
                    MealMode.UAM_BREAKFAST    -> "Breakfast"
                    MealMode.UAM_LUNCH        -> "Lunch"
                    MealMode.UAM_DINNER       -> "Dinner"
                    MealMode.UAM_SNACK        -> "Snack"
                    MealMode.UAM_PROTEIN_FAT  -> "Protein/Fat"
                    MealMode.UAM_AFTERNOON    -> "Afternoon"
                    else                      -> mode.label
                }
            } else mode.label
            "Meal: $label ${mins}m"
        } ?: "Meal: Fasting"

        val livePb2Line = when {
            // BUG FIX: must check > 0.0 not just != null. activePb2DoseU can be set to 0.0
            // when a meal mode is activated without PB2 selected, which was incorrectly
            // displaying "PB2: active 70m" on the overview (matches cached check at line 581).
            (mealOverrideManager.activePb2DoseU ?: 0.0) > 0.0 -> {
                val mins = (mealOverrideManager.modeTimeRemainingMs / 60_000).toInt()
                "PB2: active ${mins}m"
            }
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
            // Same fix as PB2 above — must check > 0.0 not just != null.
            (mealOverrideManager.activePb3DoseU ?: 0.0) > 0.0 -> {
                val mins = (mealOverrideManager.modeTimeRemainingMs / 60_000).toInt()
                "PB3: active ${mins}m"
            }
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
        val liveLearningState = when {
            !cachedLearningEnabled                   -> "off: Learning disabled"
            activityMonitor.suppressLearning         -> "off: Activity ${activityMonitor.level.label}"
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

    fun circadianDataForDay(dow: Int): String {
        val profileIsf   = cachedProfileIsf
        val profileBasal = cachedProfileBasal
        val isfUnit = if (isMmol) "mmol/U" else "mg/dL/U"
        return buildString {
            appendLine("  Hr  ISF ($isfUnit)   Basal (U/h)  Ceil   Conf")
            for (h in 0..23) {
                val hIsfMult = circadianLearner.isfMultiplier(h, dow)
                val hBasMult = combinedBasalMultiplier(h, dow)
                val hIsf = if (profileIsf > 0 && hIsfMult > 0) (if (isMmol) "%.2f".format(profileIsf / hIsfMult / 18.0) else "%.1f".format(profileIsf / hIsfMult)) else "—"
                val hBas = if (profileBasal > 0) "%.3f".format(profileBasal * hBasMult) else "—"
                appendLine(" ${h.toString().padStart(2)}  $hIsf  $hBas  ${"%.3f".format(circadianLearner.aggrCeiling(h, dow))}  ${"%.0f".format(circadianLearner.confidencePct(h, dow))}%")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        learningDirtyUntilMs = sp.getString(StringKey.ApsSmartInsulinLearningDirtyUntil.key, "0").toLongOrNull() ?: 0L
    }

    // spMgdl and activityOffsetMmol live in SmartInsulinSpReader.kt (same package)
    // so they can be unit-tested without constructing the full plugin.
    private fun spMgdl(key: UnitDoubleKey): Double = spMgdl(sp, key)

    private fun pfIsfMgdl(hour: Int): Double {
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

        // Overnight takes priority over night when both match (overnight is more specific)
        return when {
            inOvernight && overnightIsf > 0.0 -> overnightIsf
            inDay       && dayIsf       > 0.0 -> dayIsf
            inNight     && nightIsf     > 0.0 -> nightIsf
            else                              -> fallback
        }
    }

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

    internal fun entrySmbFractionForMode(mode: MealMode): Double = when (mode) {
        MealMode.UAM_BREAKFAST -> sp.getDouble(DoubleKey.ApsSmartInsulinUamEntrySmbFractionBreakfast.key,  DoubleKey.ApsSmartInsulinUamEntrySmbFractionBreakfast.defaultValue)
        MealMode.UAM_LUNCH     -> sp.getDouble(DoubleKey.ApsSmartInsulinUamEntrySmbFractionLunch.key,      DoubleKey.ApsSmartInsulinUamEntrySmbFractionLunch.defaultValue)
        MealMode.UAM_DINNER    -> sp.getDouble(DoubleKey.ApsSmartInsulinUamEntrySmbFractionDinner.key,     DoubleKey.ApsSmartInsulinUamEntrySmbFractionDinner.defaultValue)
        MealMode.UAM_SNACK     -> sp.getDouble(DoubleKey.ApsSmartInsulinUamEntrySmbFractionSnack.key,      DoubleKey.ApsSmartInsulinUamEntrySmbFractionSnack.defaultValue)
        MealMode.UAM_AFTERNOON -> sp.getDouble(DoubleKey.ApsSmartInsulinUamEntrySmbFractionAfternoon.key,  DoubleKey.ApsSmartInsulinUamEntrySmbFractionAfternoon.defaultValue)
        else                   -> sp.getDouble(DoubleKey.ApsSmartInsulinUamEntrySmbFraction.key,           DoubleKey.ApsSmartInsulinUamEntrySmbFraction.defaultValue)
    }

    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
        val previousAPSResult = lastAPSResult; lastAPSResult = null
        val profile = profileFunction.getProfile() ?: return
        cachedProfileIsf = profile.getIsfMgdl("SmartInsulinPlugin")
        cachedProfileBasal = profile.getBasal()
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
        val currentHour = java.util.Calendar.getInstance().also { it.timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
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
            if (lockoutMins > 0) { learningDirtyUntilMs = maxOf(learningDirtyUntilMs, now + lockoutMins * 60000L); sp.edit { putString(StringKey.ApsSmartInsulinLearningDirtyUntil.key, learningDirtyUntilMs.toString()) } }
        }
        previousMealModeForLockout = mealMode
        if (learningDirtyUntilMs > 0L && now >= learningDirtyUntilMs) { learningDirtyUntilMs = 0L; sp.edit { putString(StringKey.ApsSmartInsulinLearningDirtyUntil.key, "0") } }
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

        if (!highTempTarget) {
            // Capture multipliers BEFORE update() so "was" reflects the baseline the
            // loop was delivering before this cycle's nudge fires.
            val isfMultBefore        = circadianLearner.isfMultiplier()
            val totalBasalMultBefore = combinedBasalMultiplier()
            val lastDirection = run {
                val parts = lastSeenNudgeState.split("|")
                val p = parts.getOrNull(0) ?: "INACTIVE"
                if (p == "TRIM") parts.getOrNull(1)
                else if (p == "ACTIVE_HIGH" || p == "ACTIVE_LOW") p
                else null
            }

            circadianLearner.update(glucoseStatus, iobArray, mealMode, mealData.mealCOB, trueIsfMgdl, targetBg, activityMonitor.suppressLearning || cgmState.suppressLearning, spMgdl(UnitDoubleKey.ApsSmartInsulinLowGuard), inPostMealLockout, aggressionLearner.aggressiveness, profileLearner.getProfile(MealMode.FASTING).peakMinutes)

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
            if (currentDirection != null && currentDirection != lastDirection) {
                nudgeDisplaySessionIsfMgdl = if (isfMultBefore > 0) trueIsfMgdl / isfMultBefore else 0.0
                nudgeDisplaySessionBasalU  = cachedProfileBasal * totalBasalMultBefore
                aapsLogger.debug(LTag.APS,
                                 "SmartInsulinPlugin: nudge baseline captured — dir=$currentDirection " +
                                     "isf=${"%.1f".format(nudgeDisplaySessionIsfMgdl)} basal=${"%.3f".format(nudgeDisplaySessionBasalU)}")
            }
            lastSeenNudgeState = currentNudgeStatus
        }
        val circIsfMult = circadianLearner.isfMultiplier()
        var dosingIsfMgdl = if (mIsfMgdl > 0.0) mIsfMgdl else trueIsfMgdl / circIsfMult

        mealOverrideManager.onLoopCycle(glucoseStatus, iobArray, constraintsChecker.getMaxIOBAllowed().value(), profile)

        // Auto-cancel UAM mode if BG has returned to profile target or below.
        // Must be checked here (not in UamController) because UamController.onLoopCycle()
        // exits early when currentMealMode != FASTING and never runs during an active UAM mode.
        val activeUamMode = mealOverrideManager.activeMealMode
        if (activeUamMode != null && activeUamMode.isUam && activeUamMode != MealMode.UAM_PROTEIN_FAT) {
            val bgMmol = glucoseStatus.glucose / 18.0
            val targetMmol = profile.getTargetMgdl() / 18.0
            if (bgMmol <= targetMmol + 0.01) {
                aapsLogger.debug(LTag.APS, "SmartInsulin: UAM auto-cancel — BG ${String.format("%.1f", bgMmol)} ≤ target ${String.format("%.1f", targetMmol)} mmol")
                mealOverrideManager.cancelOverride()
            }
        }

        uamController.onLoopCycle(mealMode, glucoseStatus.glucose/18.0, glucoseStatus.delta/18.0, glucoseStatus.shortAvgDelta/18.0, -((iobArray.firstOrNull()?.activity ?: 0.0) * dosingIsfMgdl * 5.0) / 18.0, currentHour, bgWentLow, inReboundWindow, if (bgWentLow) reboundWindowStartMs else 0L, highTempTarget, cgmState.inWarmup, inPostMealLockout, profile.getTargetMgdl()/18.0, softLandingBypass, glucoseStatus.date)

        val justFiredMode = uamController.justFiredThisCycle
        val latestMealMode = justFiredMode ?: mealOverrideManager.activeMealMode ?: MealMode.FASTING
        if (latestMealMode != mealMode) {
            val lModeIsf = modeIsfMgdl(latestMealMode, currentHour)
            mealMode = latestMealMode
            if (lModeIsf > 0.0) dosingIsfMgdl = lModeIsf
        }

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
        val entrySmbFraction = entrySmbFractionForMode(mealMode)
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

        val oapsProfile = OapsProfile(dia = profile.dia, min_5m_carbimpact = 0.0, max_iob = constraintsChecker.getMaxIOBAllowed().value(), max_daily_basal = profile.getMaxDailyBasal(), max_basal = constraintsChecker.getMaxBasalAllowed(profile).value(), min_bg = profile.getTargetLowMgdl(), max_bg = profile.getTargetHighMgdl(), target_bg = stftTargetMgdl, carb_ratio = profile.getIc(), sens = dosingIsfMgdl, autosens_adjust_targets = false, max_daily_safety_multiplier = sp.getDouble(DoubleKey.ApsMaxDailyMultiplier.key, DoubleKey.ApsMaxDailyMultiplier.defaultValue), current_basal_safety_multiplier = sp.getDouble(DoubleKey.ApsMaxCurrentBasalMultiplier.key, DoubleKey.ApsMaxCurrentBasalMultiplier.defaultValue), lgsThreshold = profileUtil.convertToMgdlDetect(sp.getDouble(UnitDoubleKey.ApsLgsThreshold.key, UnitDoubleKey.ApsLgsThreshold.defaultValue)).toInt(), high_temptarget_raises_sensitivity = false, low_temptarget_lowers_sensitivity = false, sensitivity_raises_target = sp.getBoolean(BooleanKey.ApsSensitivityRaisesTarget.key, BooleanKey.ApsSensitivityRaisesTarget.defaultValue), resistance_lowers_target = sp.getBoolean(BooleanKey.ApsResistanceLowersTarget.key, BooleanKey.ApsResistanceLowersTarget.defaultValue), adv_target_adjustments = SMBDefaults.adv_target_adjustments, exercise_mode = SMBDefaults.exercise_mode, half_basal_exercise_target = SMBDefaults.half_basal_exercise_target, maxCOB = SMBDefaults.maxCOB, skip_neutral_temps = activePlugin.activePump.setNeutralTempAtFullHour(), remainingCarbsCap = SMBDefaults.remainingCarbsCap, enableUAM = constraintsChecker.isUAMEnabled().value(), A52_risk_enable = SMBDefaults.A52_risk_enable, SMBInterval = sp.getInt(IntKey.ApsMaxSmbFrequency.key, IntKey.ApsMaxSmbFrequency.defaultValue), enableSMB_with_COB = sp.getBoolean(BooleanKey.ApsUseSmbWithCob.key, BooleanKey.ApsUseSmbWithCob.defaultValue), enableSMB_with_temptarget = sp.getBoolean(BooleanKey.ApsUseSmbWithLowTt.key, BooleanKey.ApsUseSmbWithLowTt.defaultValue), allowSMB_with_high_temptarget = sp.getBoolean(BooleanKey.ApsUseSmbWithHighTt.key, BooleanKey.ApsUseSmbWithHighTt.defaultValue), enableSMB_always = sp.getBoolean(BooleanKey.ApsUseSmbAlways.key, BooleanKey.ApsUseSmbAlways.defaultValue), enableSMB_after_carbs = sp.getBoolean(BooleanKey.ApsUseSmbAfterCarbs.key, BooleanKey.ApsUseSmbAfterCarbs.defaultValue), maxSMBBasalMinutes = Int.MAX_VALUE, maxUAMSMBBasalMinutes = Int.MAX_VALUE, bolus_increment = activePlugin.activePump.pumpDescription.bolusStep, carbsReqThreshold = sp.getInt(IntKey.ApsCarbsRequestThreshold.key, IntKey.ApsCarbsRequestThreshold.defaultValue), current_basal = activePlugin.activePump.baseBasalRate, temptargetSet = isTempTarget, autosens_max = sp.getDouble(DoubleKey.AutosensMax.key, DoubleKey.AutosensMax.defaultValue), out_units = if (isMmol) "mmol/L" else "mg/dl", variable_sens = 0.0, insulinDivisor = 0, TDD = 0.0)

        activityMonitor.recompute(now, sp.getDouble(DoubleKey.ApsSmartInsulinRestingHrBpm.key, DoubleKey.ApsSmartInsulinRestingHrBpm.defaultValue))
        aggressionLearner.recordBg(glucoseStatus.glucose, 70.0, 180.0, mealMode, activityMonitor.suppressLearning || cgmState.suppressLearning || inPostMealLockout, now)
        val aggressiveness = if (mealMode != MealMode.FASTING) 1.0 else aggressionLearner.aggressiveness.coerceAtMost(circadianLearner.aggrCeiling())

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
        val basalMultiplier = combinedBasalMultiplier()

        val REBOUND_LOW_THRESHOLD_MGDL = spMgdl(UnitDoubleKey.ApsSmartInsulinLowGuard)
        if (glucoseStatus.glucose < REBOUND_LOW_THRESHOLD_MGDL) {
            if (!bgWentLow) { iobAtLowTime = iobArray.firstOrNull()?.iob ?: 0.0; shortAvgDeltaAtLow = glucoseStatus.shortAvgDelta / 18.0; if (mealMode.isUam) mealOverrideManager.cancelOverride() }
            else if (softLandingBypass && reboundWindowStartMs > 0L) { secondLowOccurred = true; softLandingBypass = false }
            if (glucoseStatus.glucose < minBgDuringLow) minBgDuringLow = glucoseStatus.glucose
            // Preserve reboundWindowStartMs across re-lows so the elapsed counter keeps ticking.
            // Re-lows during an active window are handled by secondLowOccurred (line above) and
            // the rollercoaster mechanism, which extends reboundGuardMs via consecutiveRollercoasters.
            // Genuine window expiry is cleaned up by the line below (when msSinceLastSuspend >= reboundGuardMs).
            bgWentLow = true
        }
        if (bgWentLow && reboundWindowStartMs == 0L && glucoseStatus.glucose >= REBOUND_LOW_THRESHOLD_MGDL) reboundWindowStartMs = now
        if (bgWentLow && reboundWindowStartMs > 0L && !inReboundWindow) { reboundWindowStartMs = 0L; bgWentLow = false; minBgDuringLow = Double.MAX_VALUE; secondLowOccurred = false; softLandingBypass = false }

        val microBolusAllowed = constraintsChecker.isSMBModeEnabled(ConstraintObject(tempBasalFallback.not(), aapsLogger)).value()
        val apsResult = determineBasalSmartInsulin.determine_basal(
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
            targetRespectEnabled     = true,
            reboundWindowMins        = reboundGuardMs / 60_000.0,  // total window incl. rollercoaster extension
            circCeil                 = circadianLearner.aggrCeiling(),
            fuelTrimStrength         = circadianLearner.trimStrength,
            isMmol                   = isMmol
        )

        lastAPSResult = apsResult; lastAPSRun = now

        // Increment UAM entry SMB counter if an entry-fraction SMB was delivered
        val fractionUsed = uamSmbFraction
        val wasEntrySmb = currentModeIsUam && apsResult.smb > 0.0 && uamEntrySmbsDelivered < entrySmbCount
        if (wasEntrySmb) {
            uamEntrySmbsDelivered++
            apsResult.reason += " | UAMEntry: SMB ${uamEntrySmbsDelivered}/$entrySmbCount @${(fractionUsed * 100).toInt()}%"
        }
        if (sp.getBoolean(BooleanKey.ApsSmartInsulinEnableLearning.key, BooleanKey.ApsSmartInsulinEnableLearning.defaultValue) && glucoseStatus.noise <= 1.5 && activityMonitor.level == ActivityMonitor.ActivityLevel.SEDENTARY) bolusCurveTracker.onLoopCycle(glucoseStatus, mealMode, iobArray, dosingIsfMgdl)

        // Snapshot state for Overview (re-computed live in overviewState() for time-sensitive parts)
        cachedLearningEnabled = sp.getBoolean(BooleanKey.ApsSmartInsulinEnableLearning.key, BooleanKey.ApsSmartInsulinEnableLearning.defaultValue)
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
            modeLine = "Meal: ${mealMode.label}", // Re-computed in overviewState()
            pb2Line = pb2Line,
            pb3Line = pb3Line,
            learningState = if (highTempTarget) "off: High temp target" else getLearningState()
        )

        rxBus.send(EventOpenAPSUpdateGui())
    }

    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? = glucoseStatusCalculatorSMB.getGlucoseStatusData(allowOldData)

    override fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (isEnabled()) {
            val maxIobPref = sp.getDouble(DoubleKey.ApsSmbMaxIob.key, DoubleKey.ApsSmbMaxIob.defaultValue)
            maxIob.setIfSmaller(maxIobPref, rh.gs(R.string.limiting_iob, maxIobPref, rh.gs(R.string.maxvalueinpreferences)), this)
            maxIob.setIfSmaller(hardLimits.maxIobSMB(), rh.gs(R.string.limiting_iob, hardLimits.maxIobSMB(), rh.gs(R.string.hardlimit)), this)
        }
        return maxIob
    }

    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        if (isEnabled()) {
            var maxBasal = sp.getDouble(DoubleKey.ApsMaxBasal.key, DoubleKey.ApsMaxBasal.defaultValue)
            if (maxBasal < profile.getMaxDailyBasal()) maxBasal = profile.getMaxDailyBasal()
            absoluteRate.setIfSmaller(maxBasal, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxBasal, rh.gs(R.string.maxvalueinpreferences)), this)
            val maxFromBasalMultiplier = floor(sp.getDouble(DoubleKey.ApsMaxCurrentBasalMultiplier.key, DoubleKey.ApsMaxCurrentBasalMultiplier.defaultValue) * profile.getBasal() * 100) / 100
            absoluteRate.setIfSmaller(maxFromBasalMultiplier, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxFromBasalMultiplier, rh.gs(R.string.max_basal_multiplier)), this)
            val maxFromDaily = floor(profile.getMaxDailyBasal() * sp.getDouble(DoubleKey.ApsMaxDailyMultiplier.key, DoubleKey.ApsMaxDailyMultiplier.defaultValue) * 100) / 100
            absoluteRate.setIfSmaller(maxFromDaily, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxFromDaily, rh.gs(R.string.max_daily_basal_multiplier)), this)
        }
        return absoluteRate
    }

    override fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (!sp.getBoolean(BooleanKey.ApsUseSmb.key, BooleanKey.ApsUseSmb.defaultValue)) value.set(false, rh.gs(R.string.smb_disabled_in_preferences), this)
        return value
    }

    override fun isUAMEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (!sp.getBoolean(BooleanKey.ApsUseUam.key, BooleanKey.ApsUseUam.defaultValue)) value.set(false, rh.gs(R.string.uam_disabled_in_preferences), this)
        return value
    }

    override fun configuration(): JSONObject = JSONObject()

    override fun applyConfiguration(configuration: JSONObject) {}

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "smart_insulin_settings"
            title = rh.gs(R.string.smart_insulin)

            // -- Learning ---------------------------------------------------------
            addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinEnableLearning, R.string.smart_insulin_enable_learning_summary, R.string.smart_insulin_enable_learning))
            addPreference(AdaptiveDoublePreference(context, null, DoubleKey.ApsSmartInsulinLearningRate, R.string.smart_insulin_learning_rate_summary, R.string.smart_insulin_learning_rate))

            // -- SMB / TBR / Aggression caps --------------------------------------
            addPreference(AdaptiveDoublePreference(context, null, DoubleKey.ApsSmartInsulinMaxSmb, null, R.string.si_max_smb_title))
            addPreference(AdaptiveDoublePreference(context, null, DoubleKey.ApsSmartInsulinMaxTbr, null, R.string.si_max_tbr_title))
            addPreference(AdaptiveDoublePreference(context, null, DoubleKey.ApsSmartInsulinAggressionMax, null, R.string.si_aggression_max_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsSmbMaxIob, dialogMessage = R.string.openapssmb_max_iob_summary, title = R.string.openapssmb_max_iob_title))

            // -- Pre-bolus --------------------------------------------------------
            addPreference(AdaptiveDoublePreference(context, null, DoubleKey.ApsSmartInsulinMaxPreBolus, R.string.si_max_prebolus_summary, R.string.si_max_prebolus_title))
            addPreference(AdaptiveDoublePreference(context, null, DoubleKey.ApsSmartInsulinPreBolus2DefaultU, null, R.string.si_prebolus2_default_u_title))
            addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinPreBolus2DefaultDelayMins, null, null, R.string.si_prebolus2_default_delay_title))

            // -- Prediction & guards -----------------------------------------------
            addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinPredictionHorizonMins, R.string.smart_insulin_prediction_horizon_summary, null, R.string.smart_insulin_prediction_horizon))
            addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinLowGuard, profileUtil, sp, R.string.smart_insulin_low_guard_summary, R.string.smart_insulin_low_guard))
            addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinWarnGuard, profileUtil, sp, R.string.smart_insulin_warn_guard_summary, R.string.smart_insulin_warn_guard))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsMaxSmbFrequency, title = R.string.smb_interval_summary))
            addPreference(AdaptiveUnitPreference(ctx = context, unitKey = UnitDoubleKey.ApsLgsThreshold, dialogMessage = R.string.lgs_threshold_summary, title = R.string.lgs_threshold_title))


            // -- Post-meal lockout & rebound window -------------------------------
            addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinPostModeLockoutMins, null, null, R.string.si_post_mode_lockout_mins_title))
            addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinReboundWindowMins, R.string.si_rebound_window_mins_summary, null, R.string.si_rebound_window_mins_title))

            // -- CGM warmup & smoothing --------------------------------------------
            addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinCgmWarmupEnabled, null, R.string.si_cgm_warmup_enabled_title))
            addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinStftCgmWarmupBlock, null, R.string.si_stft_cgm_warmup_block_title))
            addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinFirstDayCgmSmoothing, R.string.si_first_day_cgm_smoothing_summary, R.string.si_first_day_cgm_smoothing_title))

            // -- Target assist -----------------------------------------------------
            addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinTargetRespectEnabled, null, R.string.si_target_respect_enabled_title))

            // -- Meal Modes sub-screen ---------------------------------------------
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "si_meal_modes_screen"
                title = rh.gs(R.string.si_meal_modes_title)
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinModeWindowMins, R.string.si_mode_window_mins_summary, null, R.string.si_mode_window_mins_title))
                addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinLowCarbMode, R.string.smart_insulin_low_carb_mode_summary, R.string.smart_insulin_low_carb_mode))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinLowCarbThresholdG, R.string.smart_insulin_low_carb_threshold_summary, null, R.string.smart_insulin_low_carb_threshold))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinBreakfastCarbsG, null, null, R.string.si_breakfast_carbs_g_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinLunchCarbsG, null, null, R.string.si_lunch_carbs_g_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinDinnerCarbsG, null, null, R.string.si_dinner_carbs_g_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinLowCarbCarbsG, null, null, R.string.si_lowcarb_carbs_g_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinExtendedCarbsG, null, null, R.string.si_extended_carbs_g_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinBreakfastIsf, profileUtil, sp, R.string.si_isf_summary, R.string.si_breakfast_isf_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinLunchIsf, profileUtil, sp, R.string.si_isf_summary, R.string.si_lunch_isf_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinDinnerIsf, profileUtil, sp, R.string.si_isf_summary, R.string.si_dinner_isf_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinLowCarbIsf, profileUtil, sp, R.string.si_isf_summary, R.string.si_lowcarb_isf_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinExtendedIsf, profileUtil, sp, R.string.si_isf_summary, R.string.si_extended_isf_title))
            })

            // -- Activity & Dawn sub-screen ----------------------------------------
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "si_activity_dawn_screen"
                title = rh.gs(R.string.si_activity_dawn_title)
                addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinActivityTargetEnabled, null, R.string.si_activity_target_enabled_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinActivityLightTarget, profileUtil, sp, null, R.string.si_activity_light_target_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinActivityModerateTarget, profileUtil, sp, null, R.string.si_activity_moderate_target_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinActivityHeavyTarget, profileUtil, sp, null, R.string.si_activity_heavy_target_title))
                addPreference(AdaptiveDoublePreference(context, null, DoubleKey.ApsSmartInsulinRestingHrBpm, null, R.string.si_resting_hr_bpm_title))
                addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinBasalLearningEnabled, null, R.string.si_basal_learning_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinDawnWindowStartHour, R.string.si_dawn_start_hour_summary, null, R.string.si_dawn_start_hour_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinDawnWindowEndHour, R.string.si_dawn_end_hour_summary, null, R.string.si_dawn_end_hour_title))
                addPreference(AdaptiveDoublePreference(context, null, DoubleKey.ApsSmartInsulinDawnSmbReduction, R.string.si_dawn_smb_reduction_summary, R.string.si_dawn_smb_reduction_title))
            })

            // -- UAM Auto-Detection sub-screen -------------------------------------
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "si_uam_screen"
                title = rh.gs(R.string.si_uam_settings_title)
                addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinUamEnabled, null, R.string.si_uam_enabled_title))
                addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinUamCgmWarmupBlock, null, R.string.si_uam_cgm_warmup_block_title))
                addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinUamWobbleTolerance, R.string.si_uam_wobble_tolerance_summary, R.string.si_uam_wobble_tolerance_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinUamTriggerThreshold, profileUtil, sp, null, R.string.si_uam_trigger_threshold_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinUamRiseMinDelta, profileUtil, sp, null, R.string.si_uam_rise_min_delta_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinUamBurstThreshold, profileUtil, sp, null, R.string.si_uam_burst_threshold_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamRiseConsecutiveReadings, null, null, R.string.si_uam_rise_readings_title))
                addPreference(AdaptiveDoublePreference(context, null, DoubleKey.ApsSmartInsulinUamEntrySmbFraction, null, R.string.si_uam_entry_smb_fraction_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamEntrySmbCount, null, null, R.string.si_uam_entry_smb_count_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamDayStartHour, null, null, R.string.si_uam_day_start_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamNightCutoffHour, null, null, R.string.si_uam_night_cutoff_title))
                // Breakfast
                addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinUamBreakfastEnabled, null, R.string.si_uam_breakfast_enabled_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamBreakfastStartHour, null, null, R.string.si_uam_breakfast_start_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamBreakfastEndHour, null, null, R.string.si_uam_breakfast_end_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamBreakfastDurationMins, null, null, R.string.si_uam_breakfast_duration_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinUamBreakfastIsf, profileUtil, sp, null, R.string.si_uam_breakfast_isf_title))
                addPreference(AdaptiveDoublePreference(context, null, DoubleKey.ApsSmartInsulinUamEntrySmbFractionBreakfast, null, R.string.si_uam_entry_smb_fraction_breakfast_title))
                // Lunch
                addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinUamLunchEnabled, null, R.string.si_uam_lunch_enabled_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamLunchStartHour, null, null, R.string.si_uam_lunch_start_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamLunchEndHour, null, null, R.string.si_uam_lunch_end_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamLunchDurationMins, null, null, R.string.si_uam_lunch_duration_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinUamLunchIsf, profileUtil, sp, null, R.string.si_uam_lunch_isf_title))
                addPreference(AdaptiveDoublePreference(context, null, DoubleKey.ApsSmartInsulinUamEntrySmbFractionLunch, null, R.string.si_uam_entry_smb_fraction_lunch_title))
                // Dinner
                addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinUamDinnerEnabled, null, R.string.si_uam_dinner_enabled_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamDinnerStartHour, null, null, R.string.si_uam_dinner_start_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamDinnerEndHour, null, null, R.string.si_uam_dinner_end_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamDinnerDurationMins, null, null, R.string.si_uam_dinner_duration_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinUamDinnerIsf, profileUtil, sp, null, R.string.si_uam_dinner_isf_title))
                addPreference(AdaptiveDoublePreference(context, null, DoubleKey.ApsSmartInsulinUamEntrySmbFractionDinner, null, R.string.si_uam_entry_smb_fraction_dinner_title))
                // Snack
                addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinUamSnackEnabled, null, R.string.si_uam_snack_enabled_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamSnackStartHour, null, null, R.string.si_uam_snack_start_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamSnackEndHour, null, null, R.string.si_uam_snack_end_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamSnackDurationMins, null, null, R.string.si_uam_snack_duration_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinUamSnackIsf, profileUtil, sp, null, R.string.si_uam_snack_isf_title))
                addPreference(AdaptiveDoublePreference(context, null, DoubleKey.ApsSmartInsulinUamEntrySmbFractionSnack, null, R.string.si_uam_entry_smb_fraction_snack_title))
                // Afternoon
                addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinUamAfternoonEnabled, null, R.string.si_uam_afternoon_enabled_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamAfternoonStartHour, null, null, R.string.si_uam_afternoon_start_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamAfternoonEndHour, null, null, R.string.si_uam_afternoon_end_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamAfternoonDurationMins, null, null, R.string.si_uam_afternoon_duration_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinUamAfternoonIsf, profileUtil, sp, null, R.string.si_uam_afternoon_isf_title))
                addPreference(AdaptiveDoublePreference(context, null, DoubleKey.ApsSmartInsulinUamEntrySmbFractionAfternoon, null, R.string.si_uam_entry_smb_fraction_afternoon_title))
                // Protein/Fat
                addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinUamProteinFatEnabled, null, R.string.si_uam_proteinfat_enabled_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamProteinFatDurationMins, null, null, R.string.si_uam_proteinfat_duration_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamProteinFatStuckReadings, null, null, R.string.si_uam_proteinfat_stuck_readings_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinUamProteinFatThreshold, profileUtil, sp, null, R.string.si_uam_proteinfat_threshold_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinUamProteinFatIsf, profileUtil, sp, null, R.string.si_uam_proteinfat_isf_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamProteinFatDayStartHour, null, null, R.string.si_uam_proteinfat_day_start_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamProteinFatDayEndHour, null, null, R.string.si_uam_proteinfat_day_end_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinUamProteinFatDayIsf, profileUtil, sp, null, R.string.si_uam_proteinfat_day_isf_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamProteinFatNightStartHour, null, null, R.string.si_uam_proteinfat_night_start_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamProteinFatNightEndHour, null, null, R.string.si_uam_proteinfat_night_end_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinUamProteinFatNightIsf, profileUtil, sp, null, R.string.si_uam_proteinfat_night_isf_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamProteinFatOvernightStartHour, null, null, R.string.si_uam_proteinfat_overnight_start_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamProteinFatOvernightEndHour, null, null, R.string.si_uam_proteinfat_overnight_end_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinUamProteinFatOvernightIsf, profileUtil, sp, null, R.string.si_uam_proteinfat_overnight_isf_title))
            })
        }
    }
}