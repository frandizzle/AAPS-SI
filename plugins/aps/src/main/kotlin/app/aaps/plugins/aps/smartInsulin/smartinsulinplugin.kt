package app.aaps.plugins.aps.smartInsulin

import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.aps.SMBDefaults
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
    @Volatile var reboundGuardMs: Long = REBOUND_GUARD_MS

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
    }

    val isMmol: Boolean get() = profileUtil.units == GlucoseUnit.MMOL
    val profileIsfMgdl: Double get() = cachedProfileIsf
    val profileBasalU: Double get() = cachedProfileBasal
    private val unitLabel: String get() = if (isMmol) "mmol" else "mg/dL"
    private fun fmtBg(mgdl: Double): String = if (isMmol) String.format("%.1f", mgdl / 18.0) else String.format("%.0f", mgdl)
    private fun fmtDelta(mmol: Double): String = if (isMmol) String.format("%+.2f", mmol) else String.format("%+.1f", mmol * 18.0)
    private fun fmtIsf(mgdl: Double): String = if (isMmol) String.format("%.1f", mgdl / 18.0) else String.format("%.0f", mgdl)

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
        val fuelTrimStrength: Double, val profileLearningStatus: String, val circadianRawStatus: String,
        val profilesRawStatus: String, val tirRawLine: String, val avgBgMgdl24h: Double,
        val estimatedHba1c: Double, val bgWindowHours: Int, val activeDoseU: Double?,
        val activePb2DoseU: Double?, val activePb3DoseU: Double?, val pb2Status: String, val pb3Status: String,
        val pb2GateData: MealOverrideManager.Pb2GateData?
    )

    fun fragmentData(): FragmentData {
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1
        val day = DayOfWeekCircadianState.DAY_LABELS[dow.coerceIn(0, 6)]
        val profileIsf = cachedProfileIsf
        val profileBasal = cachedProfileBasal
        val isfMult = circadianLearner.isfMultiplier(hour)
        val basalMult = basalLearner.multiplierClamped * circadianLearner.basalMultiplier(hour)
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
                val marker = if (h == hour) "▶" else " "
                val hIsfMult = circadianLearner.isfMultiplier(h)
                val hBasMult = basalLearner.multiplierClamped * circadianLearner.basalMultiplier(h)
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
            profileLearningStatus = if (!isLearningEnabled) "off: Disabled" else bolusCurveTracker.statusSummary(currentMealMode),
            circadianRawStatus = circRaw, profilesRawStatus = profRaw, tirRawLine = aggressionLearner.tirSummary,
            avgBgMgdl24h = cachedHba1cAvgMgdl, estimatedHba1c = cachedHba1cEstimate, bgWindowHours = cachedHba1cWindowHours,
            activeDoseU = mealOverrideManager.activeDoseU, activePb2DoseU = mealOverrideManager.activePb2DoseU,
            activePb3DoseU = mealOverrideManager.activePb3DoseU,
            pb2Status = mealOverrideManager.preBolus2StatusText, pb3Status = mealOverrideManager.preBolus3StatusText,
            pb2GateData = mealOverrideManager.pb2GateData?.copy(isMmol = isMmol)
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
        val activeMode = mealOverrideManager.activeMealMode
        val modeLine = if (activeMode != null) {
            val mins = (mealOverrideManager.modeTimeRemainingMs / 60_000).toInt()
            "Meal: ${activeMode.label} ${mins}m"
        } else {
            "Meal: Fasting"
        }
        val pb2DoseU = mealOverrideManager.activePb2DoseU
        val pb2Line = if (pb2DoseU != null && pb2DoseU > 0.0) {
            val mins = (mealOverrideManager.modeTimeRemainingMs / 60_000).toInt()
            "PB2 active: ${mins}m"
        } else null
        return SmartInsulinOverview.OverviewState(
            modeLine = modeLine,
            pb2Line = pb2Line,
            learningState = getLearningState()
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
                val hBasMult = basalLearner.multiplierClamped * circadianLearner.basalMultiplier(h, dow)
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

    private fun spMgdl(key: UnitDoubleKey, mmolThreshold: Double = 20.0): Double {
        val raw = sp.getDouble(key.key, key.defaultValue)
        return if (raw < mmolThreshold) raw * 18.0 else raw
    }

    private fun pfIsfMgdl(hour: Int): Double {
        val dayStart = sp.getInt(IntKey.ApsSmartInsulinUamProteinFatDayStartHour.key, IntKey.ApsSmartInsulinUamProteinFatDayStartHour.defaultValue)
        val dayEnd = sp.getInt(IntKey.ApsSmartInsulinUamProteinFatDayEndHour.key, IntKey.ApsSmartInsulinUamProteinFatDayEndHour.defaultValue)
        val nightStart = sp.getInt(IntKey.ApsSmartInsulinUamProteinFatNightStartHour.key, IntKey.ApsSmartInsulinUamProteinFatNightStartHour.defaultValue)
        val nightEnd = sp.getInt(IntKey.ApsSmartInsulinUamProteinFatNightEndHour.key, IntKey.ApsSmartInsulinUamNightCutoffHour.defaultValue)
        val inDay = if (dayStart <= dayEnd) hour in dayStart..dayEnd else hour >= dayStart || hour <= dayEnd
        val inNight = if (nightStart <= nightEnd) hour in nightStart..nightEnd else hour >= nightStart || hour <= nightEnd
        val dayIsf = sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamProteinFatDayIsf.key, UnitDoubleKey.ApsSmartInsulinUamProteinFatDayIsf.defaultValue)
        val nightIsf = sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamProteinFatNightIsf.key, UnitDoubleKey.ApsSmartInsulinUamProteinFatNightIsf.defaultValue)
        val fallback = sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamProteinFatIsf.key, UnitDoubleKey.ApsSmartInsulinUamProteinFatIsf.defaultValue)
        return when { inDay && dayIsf > 0.0 -> dayIsf; inNight && nightIsf > 0.0 -> nightIsf; else -> fallback }
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
        if (previousMealModeForLockout != MealMode.FASTING && previousMealModeForLockout != MealMode.UAM_PROTEIN_FAT && mealMode == MealMode.FASTING) {
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
            circadianLearner.update(glucoseStatus, iobArray, mealMode, mealData.mealCOB, trueIsfMgdl, targetBg, activityMonitor.suppressLearning || cgmState.suppressLearning, spMgdl(UnitDoubleKey.ApsSmartInsulinLowGuard), inPostMealLockout, aggressionLearner.aggressiveness, profileLearner.getProfile(MealMode.FASTING).peakMinutes)
        }
        val circIsfMult = circadianLearner.isfMultiplier()
        var dosingIsfMgdl = if (mIsfMgdl > 0.0) mIsfMgdl else trueIsfMgdl / circIsfMult

        mealOverrideManager.onLoopCycle(glucoseStatus, iobArray, constraintsChecker.getMaxIOBAllowed().value(), profile)

        uamController.onLoopCycle(mealMode, glucoseStatus.glucose/18.0, glucoseStatus.delta/18.0, glucoseStatus.shortAvgDelta/18.0, -((iobArray.firstOrNull()?.activity ?: 0.0) * dosingIsfMgdl * 5.0) / 18.0, currentHour, bgWentLow, inReboundWindow, if (bgWentLow) reboundWindowStartMs else 0L, highTempTarget, cgmState.inWarmup, inPostMealLockout, profile.getTargetMgdl()/18.0, softLandingBypass, glucoseStatus.date)

        val latestMealMode = uamController.justFiredThisCycle ?: mealOverrideManager.activeMealMode ?: MealMode.FASTING
        if (latestMealMode != mealMode) {
            val lModeIsf = modeIsfMgdl(latestMealMode, currentHour)
            mealMode = latestMealMode
            if (lModeIsf > 0.0) dosingIsfMgdl = lModeIsf
        }

        val stftAdjusted = stftController.onLoopCycle(profile.getTargetMgdl(), glucoseStatus.glucose, glucoseStatus.delta, glucoseStatus.shortAvgDelta, mealMode, isTempTarget, bgWentLow, inReboundWindow, cgmState.inWarmup, glucoseStatus.date)
        val stftTargetMgdl = if (!isTempTarget) stftAdjusted else targetBg

        val oapsProfile = OapsProfile(dia = profile.dia, min_5m_carbimpact = 0.0, max_iob = constraintsChecker.getMaxIOBAllowed().value(), max_daily_basal = profile.getMaxDailyBasal(), max_basal = constraintsChecker.getMaxBasalAllowed(profile).value(), min_bg = profile.getTargetLowMgdl(), max_bg = profile.getTargetHighMgdl(), target_bg = stftTargetMgdl, carb_ratio = profile.getIc(), sens = dosingIsfMgdl, autosens_adjust_targets = false, max_daily_safety_multiplier = sp.getDouble(DoubleKey.ApsMaxDailyMultiplier.key, DoubleKey.ApsMaxDailyMultiplier.defaultValue), current_basal_safety_multiplier = sp.getDouble(DoubleKey.ApsMaxCurrentBasalMultiplier.key, DoubleKey.ApsMaxCurrentBasalMultiplier.defaultValue), lgsThreshold = profileUtil.convertToMgdlDetect(sp.getDouble(UnitDoubleKey.ApsLgsThreshold.key, UnitDoubleKey.ApsLgsThreshold.defaultValue)).toInt(), high_temptarget_raises_sensitivity = false, low_temptarget_lowers_sensitivity = false, sensitivity_raises_target = sp.getBoolean(BooleanKey.ApsSensitivityRaisesTarget.key, BooleanKey.ApsSensitivityRaisesTarget.defaultValue), resistance_lowers_target = sp.getBoolean(BooleanKey.ApsResistanceLowersTarget.key, BooleanKey.ApsResistanceLowersTarget.defaultValue), adv_target_adjustments = SMBDefaults.adv_target_adjustments, exercise_mode = SMBDefaults.exercise_mode, half_basal_exercise_target = SMBDefaults.half_basal_exercise_target, maxCOB = SMBDefaults.maxCOB, skip_neutral_temps = activePlugin.activePump.setNeutralTempAtFullHour(), remainingCarbsCap = SMBDefaults.remainingCarbsCap, enableUAM = constraintsChecker.isUAMEnabled().value(), A52_risk_enable = SMBDefaults.A52_risk_enable, SMBInterval = sp.getInt(IntKey.ApsMaxSmbFrequency.key, IntKey.ApsMaxSmbFrequency.defaultValue), enableSMB_with_COB = sp.getBoolean(BooleanKey.ApsUseSmbWithCob.key, BooleanKey.ApsUseSmbWithCob.defaultValue), enableSMB_with_temptarget = sp.getBoolean(BooleanKey.ApsUseSmbWithLowTt.key, BooleanKey.ApsUseSmbWithLowTt.defaultValue), allowSMB_with_high_temptarget = sp.getBoolean(BooleanKey.ApsUseSmbWithHighTt.key, BooleanKey.ApsUseSmbWithHighTt.defaultValue), enableSMB_always = sp.getBoolean(BooleanKey.ApsUseSmbAlways.key, BooleanKey.ApsUseSmbAlways.defaultValue), enableSMB_after_carbs = sp.getBoolean(BooleanKey.ApsUseSmbAfterCarbs.key, BooleanKey.ApsUseSmbAfterCarbs.defaultValue), maxSMBBasalMinutes = Int.MAX_VALUE, maxUAMSMBBasalMinutes = Int.MAX_VALUE, bolus_increment = activePlugin.activePump.pumpDescription.bolusStep, carbsReqThreshold = sp.getInt(IntKey.ApsCarbsRequestThreshold.key, IntKey.ApsCarbsRequestThreshold.defaultValue), current_basal = activePlugin.activePump.baseBasalRate, temptargetSet = isTempTarget, autosens_max = sp.getDouble(DoubleKey.AutosensMax.key, DoubleKey.AutosensMax.defaultValue), out_units = if (isMmol) "mmol/L" else "mg/dl", variable_sens = 0.0, insulinDivisor = 0, TDD = 0.0)

        activityMonitor.recompute(now, sp.getDouble(DoubleKey.ApsSmartInsulinRestingHrBpm.key, DoubleKey.ApsSmartInsulinRestingHrBpm.defaultValue))
        aggressionLearner.recordBg(glucoseStatus.glucose, 70.0, 180.0, mealMode, activityMonitor.suppressLearning || cgmState.suppressLearning || inPostMealLockout, now)
        val aggressiveness = if (mealMode != MealMode.FASTING) 1.0 else aggressionLearner.aggressiveness.coerceAtMost(circadianLearner.aggrCeiling())

        val minsLastBolus = iobArray.firstOrNull()?.lastBolusTime?.let { if (it > 0) (now - it) / 60000.0 else Double.MAX_VALUE } ?: Double.MAX_VALUE
        if (sp.getBoolean(BooleanKey.ApsSmartInsulinBasalLearningEnabled.key, BooleanKey.ApsSmartInsulinBasalLearningEnabled.defaultValue) && mealMode == MealMode.FASTING && !highTempTarget && !(activityMonitor.suppressLearning || cgmState.suppressLearning || inPostMealLockout)) {
            basalLearner.onLoopCycle(glucoseStatus.glucose, glucoseStatus.delta, mealData.mealCOB, minsLastBolus, trueIsfMgdl, profile.getBasal())
        }
        val basalMultiplier = (if (sp.getBoolean(BooleanKey.ApsSmartInsulinBasalLearningEnabled.key, BooleanKey.ApsSmartInsulinBasalLearningEnabled.defaultValue)) basalLearner.multiplierClamped else 1.0) * circadianLearner.basalMultiplier()

        val REBOUND_LOW_THRESHOLD_MGDL = spMgdl(UnitDoubleKey.ApsSmartInsulinLowGuard)
        if (glucoseStatus.glucose < REBOUND_LOW_THRESHOLD_MGDL) {
            if (!bgWentLow) { iobAtLowTime = iobArray.firstOrNull()?.iob ?: 0.0; shortAvgDeltaAtLow = glucoseStatus.shortAvgDelta / 18.0; if (mealMode.isUam) mealOverrideManager.cancelOverride() }
            else if (softLandingBypass && reboundWindowStartMs > 0L) { secondLowOccurred = true; softLandingBypass = false }
            if (glucoseStatus.glucose < minBgDuringLow) minBgDuringLow = glucoseStatus.glucose
            bgWentLow = true; if (reboundWindowStartMs > 0L) reboundWindowStartMs = 0L
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
            activityTargetOffsetMmol = activityMonitor.targetOffsetMmol(spMgdl(UnitDoubleKey.ApsSmartInsulinActivityLightTarget)/18.0, spMgdl(UnitDoubleKey.ApsSmartInsulinActivityModerateTarget)/18.0, spMgdl(UnitDoubleKey.ApsSmartInsulinActivityHeavyTarget)/18.0),
            cgmSmbFraction           = cgmState.smbFraction,
            cgmDeltaPlausible        = cgmState.deltaPlausible,
            cgmWarmupReason          = cgmState.reason,
            uamSmbFraction           = 1.0,
            targetRespectEnabled     = true,
            reboundWindowMins        = sp.getInt(IntKey.ApsSmartInsulinReboundWindowMins.key, IntKey.ApsSmartInsulinReboundWindowMins.defaultValue).toDouble(),
            circCeil                 = circadianLearner.aggrCeiling(),
            fuelTrimStrength         = circadianLearner.trimStrength,
            isMmol                   = isMmol
        )

        lastAPSResult = apsResult; lastAPSRun = now
        if (sp.getBoolean(BooleanKey.ApsSmartInsulinEnableLearning.key, BooleanKey.ApsSmartInsulinEnableLearning.defaultValue) && glucoseStatus.noise <= 1.5 && activityMonitor.level == ActivityMonitor.ActivityLevel.SEDENTARY) bolusCurveTracker.onLoopCycle(glucoseStatus, mealMode, iobArray)
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

            // ── Learning ─────────────────────────────────────────────────────────
            addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinEnableLearning, R.string.smart_insulin_enable_learning_summary, R.string.smart_insulin_enable_learning))
            addPreference(AdaptiveDoublePreference(context, null, DoubleKey.ApsSmartInsulinLearningRate, R.string.smart_insulin_learning_rate_summary, R.string.smart_insulin_learning_rate))

            // ── SMB / TBR / Aggression caps ──────────────────────────────────────
            addPreference(AdaptiveDoublePreference(context, null, DoubleKey.ApsSmartInsulinMaxSmb, null, R.string.si_max_smb_title))
            addPreference(AdaptiveDoublePreference(context, null, DoubleKey.ApsSmartInsulinMaxTbr, null, R.string.si_max_tbr_title))
            addPreference(AdaptiveDoublePreference(context, null, DoubleKey.ApsSmartInsulinAggressionMax, null, R.string.si_aggression_max_title))

            // ── Pre-bolus ────────────────────────────────────────────────────────
            addPreference(AdaptiveDoublePreference(context, null, DoubleKey.ApsSmartInsulinMaxPreBolus, R.string.si_max_prebolus_summary, R.string.si_max_prebolus_title))
            addPreference(AdaptiveDoublePreference(context, null, DoubleKey.ApsSmartInsulinPreBolus2DefaultU, null, R.string.si_prebolus2_default_u_title))
            addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinPreBolus2DefaultDelayMins, null, null, R.string.si_prebolus2_default_delay_title))

            // ── Prediction & guards ───────────────────────────────────────────────
            addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinPredictionHorizonMins, R.string.smart_insulin_prediction_horizon_summary, null, R.string.smart_insulin_prediction_horizon))
            addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinLowGuard, profileUtil, sp, R.string.smart_insulin_low_guard_summary, R.string.smart_insulin_low_guard))
            addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinWarnGuard, profileUtil, sp, R.string.smart_insulin_warn_guard_summary, R.string.smart_insulin_warn_guard))

            // ── Post-meal lockout & rebound window ───────────────────────────────
            addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinPostModeLockoutMins, null, null, R.string.si_post_mode_lockout_mins_title))
            addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinReboundWindowMins, R.string.si_rebound_window_mins_summary, null, R.string.si_rebound_window_mins_title))

            // ── CGM warmup & smoothing ────────────────────────────────────────────
            addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinCgmWarmupEnabled, null, R.string.si_cgm_warmup_enabled_title))
            addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinStftCgmWarmupBlock, null, R.string.si_stft_cgm_warmup_block_title))
            addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinFirstDayCgmSmoothing, R.string.si_first_day_cgm_smoothing_summary, R.string.si_first_day_cgm_smoothing_title))

            // ── Target assist ─────────────────────────────────────────────────────
            addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinTargetRespectEnabled, null, R.string.si_target_respect_enabled_title))

            // ── Meal Modes sub-screen ─────────────────────────────────────────────
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

            // ── Activity & Dawn sub-screen ────────────────────────────────────────
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

            // ── UAM Auto-Detection sub-screen ─────────────────────────────────────
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
                // Lunch
                addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinUamLunchEnabled, null, R.string.si_uam_lunch_enabled_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamLunchStartHour, null, null, R.string.si_uam_lunch_start_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamLunchEndHour, null, null, R.string.si_uam_lunch_end_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamLunchDurationMins, null, null, R.string.si_uam_lunch_duration_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinUamLunchIsf, profileUtil, sp, null, R.string.si_uam_lunch_isf_title))
                // Dinner
                addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinUamDinnerEnabled, null, R.string.si_uam_dinner_enabled_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamDinnerStartHour, null, null, R.string.si_uam_dinner_start_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamDinnerEndHour, null, null, R.string.si_uam_dinner_end_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamDinnerDurationMins, null, null, R.string.si_uam_dinner_duration_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinUamDinnerIsf, profileUtil, sp, null, R.string.si_uam_dinner_isf_title))
                // Snack
                addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinUamSnackEnabled, null, R.string.si_uam_snack_enabled_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamSnackStartHour, null, null, R.string.si_uam_snack_start_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamSnackEndHour, null, null, R.string.si_uam_snack_end_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamSnackDurationMins, null, null, R.string.si_uam_snack_duration_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinUamSnackIsf, profileUtil, sp, null, R.string.si_uam_snack_isf_title))
                // Afternoon
                addPreference(AdaptiveSwitchPreference(context, null, BooleanKey.ApsSmartInsulinUamAfternoonEnabled, null, R.string.si_uam_afternoon_enabled_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamAfternoonStartHour, null, null, R.string.si_uam_afternoon_start_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamAfternoonEndHour, null, null, R.string.si_uam_afternoon_end_title))
                addPreference(AdaptiveIntPreference(context, null, IntKey.ApsSmartInsulinUamAfternoonDurationMins, null, null, R.string.si_uam_afternoon_duration_title))
                addPreference(SmartInsulinUnitPreference(context, UnitDoubleKey.ApsSmartInsulinUamAfternoonIsf, profileUtil, sp, null, R.string.si_uam_afternoon_isf_title))
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
            })
        }
    }
}
