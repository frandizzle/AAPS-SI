package app.aaps.plugins.aps.smartInsulin

import android.content.Context
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.model.GlucoseUnit
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
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.getPassedDurationToTimeInMinutes
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.objects.extensions.target
import app.aaps.core.utils.MidnightUtils
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveUnitPreference
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.plugins.aps.OpenAPSFragment
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor

@Singleton
open class SmartInsulinPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val rxBus: RxBus,
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
    private val preferences: Preferences,
    private val constraintsChecker: ConstraintsChecker,
    private val activePlugin: ActivePlugin,
    private val dateUtil: DateUtil,
    private val determineBasalSmartInsulin: DetermineBasalSmartInsulin,
    private val profileLearner: ProfileLearner,
    private val bolusCurveTracker: BolusCurveTracker,
    private val aggressionLearner: AggressionLearner,
    private val basalLearner: BasalLearner
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.APS)
        .fragmentClass(OpenAPSFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_generic_icon)
        .pluginName(R.string.smart_insulin)
        .shortName(R.string.smart_insulin_short)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .preferencesVisibleInSimpleMode(false)
        .showInList { config.APS }
        .description(R.string.smart_insulin_description),
    aapsLogger, rh
), APS, PluginConstraints {

    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.SMB
    override var lastAPSResult: APSResult? = null

    // ── Suspend / rebound tracking ────────────────────────────────────────────
    // Tracks the last time a SUSPEND or CAUTION zone was active so the NORMAL
    // branch can apply graduated rebound protection after a low event.
    var lastSuspendMs: Long = 0L
    val msSinceLastSuspend: Long get() = System.currentTimeMillis() - lastSuspendMs
    val inReboundWindow: Boolean get() = lastSuspendMs > 0L && msSinceLastSuspend < REBOUND_GUARD_MS

    companion object {
        const val REBOUND_GUARD_MS = 90 * 60 * 1000L  // 90 min rebound protection window
    }

    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
        aapsLogger.debug(LTag.APS, "SmartInsulin invoke from $initiator")
        lastAPSResult = null

        val profile = profileFunction.getProfile() ?: run {
            rxBus.send(EventResetOpenAPSGui(rh.gs(app.aaps.core.ui.R.string.no_profile_set)))
            return
        }
        if (!isEnabled()) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_disabled)))
            return
        }
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData ?: run {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_no_glucose_data)))
            return
        }

        if (!hardLimits.checkHardLimits(profile.dia, app.aaps.core.ui.R.string.profile_dia, hardLimits.minDia(), hardLimits.maxDia())) return
        if (!hardLimits.checkHardLimits(
                profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight()),
                app.aaps.core.ui.R.string.profile_carbs_ratio_value,
                hardLimits.minIC(), hardLimits.maxIC()
            )
        ) return
        if (!hardLimits.checkHardLimits(profile.getIsfMgdl("SmartInsulinPlugin"), app.aaps.core.ui.R.string.profile_sensitivity_value, HardLimits.MIN_ISF, HardLimits.MAX_ISF)) return
        if (!hardLimits.checkHardLimits(profile.getMaxDailyBasal(), app.aaps.core.ui.R.string.profile_max_daily_basal_value, 0.02, hardLimits.maxBasal())) return

        val inputConstraints = ConstraintObject(0.0, aapsLogger)

        val now = dateUtil.now()
        val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
        val currentTemp = CurrentTemp(
            duration       = tb?.plannedRemainingMinutes ?: 0,
            rate           = tb?.convertedToAbsolute(now, profile) ?: 0.0,
            minutesrunning = tb?.getPassedDurationToTimeInMinutes(now)
        )

        var minBg    = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetLowMgdl(), 0.1),  app.aaps.core.ui.R.string.profile_low_target,  HardLimits.LIMIT_MIN_BG[0],    HardLimits.LIMIT_MIN_BG[1])
        var maxBg    = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetHighMgdl(), 0.1), app.aaps.core.ui.R.string.profile_high_target, HardLimits.LIMIT_MAX_BG[0],    HardLimits.LIMIT_MAX_BG[1])
        var targetBg = hardLimits.verifyHardLimits(profile.getTargetMgdl(),                         app.aaps.core.ui.R.string.temp_target_value,   HardLimits.LIMIT_TARGET_BG[0], HardLimits.LIMIT_TARGET_BG[1])
        var isTempTarget = false
        persistenceLayer.getTemporaryTargetActiveAt(now)?.let { tt ->
            isTempTarget = true
            minBg    = hardLimits.verifyHardLimits(tt.lowTarget,  app.aaps.core.ui.R.string.temp_target_low_target,  HardLimits.LIMIT_TEMP_MIN_BG[0],    HardLimits.LIMIT_TEMP_MIN_BG[1])
            maxBg    = hardLimits.verifyHardLimits(tt.highTarget, app.aaps.core.ui.R.string.temp_target_high_target, HardLimits.LIMIT_TEMP_MAX_BG[0],    HardLimits.LIMIT_TEMP_MAX_BG[1])
            targetBg = hardLimits.verifyHardLimits(tt.target(),   app.aaps.core.ui.R.string.temp_target_value,       HardLimits.LIMIT_TEMP_TARGET_BG[0], HardLimits.LIMIT_TEMP_TARGET_BG[1])
        }

        val autosensResult = AutosensResult()
        val iobArray = iobCobCalculator.calculateIobArrayForSMB(
            autosensResult,
            SMBDefaults.exercise_mode,
            SMBDefaults.half_basal_exercise_target,
            isTempTarget
        )
        val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()

        // ── Meal mode — check override first, fall back to auto-detect ────────
        val mealMode = MealModeDetector.detect(
            mealData        = mealData,
            glucoseStatus   = glucoseStatus,
            overrideManager = mealOverrideManager
        )

        // ── ISF multiplier — read from user prefs, not hardcoded enum default ─
        val isfMultiplier = when (mealMode) {
            MealMode.BREAKFAST -> preferences.get(DoubleKey.ApsSmartInsulinBreakfastIsfMultiplier)
            MealMode.LUNCH     -> preferences.get(DoubleKey.ApsSmartInsulinLunchIsfMultiplier)
            MealMode.DINNER    -> preferences.get(DoubleKey.ApsSmartInsulinDinnerIsfMultiplier)
            MealMode.LOW_CARB  -> preferences.get(DoubleKey.ApsSmartInsulinLowCarbIsfMultiplier)
            MealMode.EXTENDED  -> preferences.get(DoubleKey.ApsSmartInsulinExtendedIsfMultiplier)
            MealMode.FASTING   -> 1.0
        }

        // ── Tick the override manager — fires queued bolus when safe ──────────
        mealOverrideManager.onLoopCycle(
            glucoseStatus = glucoseStatus,
            iobArray      = iobArray,
            maxIobU       = constraintsChecker.getMaxIOBAllowed().value()
        )

        // ── Build OapsProfile — apply per-meal ISF multiplier to sens ─────────
        val pump       = activePlugin.activePump
        val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
        val oapsProfile = OapsProfile(
            dia                              = profile.dia,
            min_5m_carbimpact               = 0.0,
            max_iob                         = constraintsChecker.getMaxIOBAllowed().also { inputConstraints.copyReasons(it) }.value(),
            max_daily_basal                 = profile.getMaxDailyBasal(),
            max_basal                       = constraintsChecker.getMaxBasalAllowed(profile).also { inputConstraints.copyReasons(it) }.value(),
            min_bg                          = minBg,
            max_bg                          = maxBg,
            target_bg                       = targetBg,
            carb_ratio                      = profile.getIc(),
            sens                            = profile.getIsfMgdl("SmartInsulinPlugin") * isfMultiplier,
            autosens_adjust_targets         = false,
            max_daily_safety_multiplier     = preferences.get(DoubleKey.ApsMaxDailyMultiplier),
            current_basal_safety_multiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier),
            high_temptarget_raises_sensitivity = false,
            low_temptarget_lowers_sensitivity  = false,
            sensitivity_raises_target       = preferences.get(BooleanKey.ApsSensitivityRaisesTarget),
            resistance_lowers_target        = preferences.get(BooleanKey.ApsResistanceLowersTarget),
            adv_target_adjustments          = SMBDefaults.adv_target_adjustments,
            exercise_mode                   = SMBDefaults.exercise_mode,
            half_basal_exercise_target      = SMBDefaults.half_basal_exercise_target,
            maxCOB                          = SMBDefaults.maxCOB,
            skip_neutral_temps              = pump.setNeutralTempAtFullHour(),
            remainingCarbsCap               = SMBDefaults.remainingCarbsCap,
            enableUAM                       = constraintsChecker.isUAMEnabled().also { inputConstraints.copyReasons(it) }.value(),
            A52_risk_enable                 = SMBDefaults.A52_risk_enable,
            SMBInterval                     = preferences.get(IntKey.ApsMaxSmbFrequency),
            enableSMB_with_COB              = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithCob),
            enableSMB_with_temptarget       = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithLowTt),
            allowSMB_with_high_temptarget   = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithHighTt),
            enableSMB_always                = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAlways),
            enableSMB_after_carbs           = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAfterCarbs),
            // maxSMBBasalMinutes: set to max so it never constrains our flat ApsSmartInsulinMaxSmb cap
            maxSMBBasalMinutes              = Int.MAX_VALUE,
            maxUAMSMBBasalMinutes           = Int.MAX_VALUE,
            bolus_increment                 = pump.pumpDescription.bolusStep,
            carbsReqThreshold               = preferences.get(IntKey.ApsCarbsRequestThreshold),
            current_basal                   = pump.baseBasalRate,
            temptargetSet                   = isTempTarget,
            autosens_max                    = preferences.get(DoubleKey.AutosensMax),
            out_units                       = if (profileFunction.getUnits() == GlucoseUnit.MMOL) "mmol/L" else "mg/dl",
            lgsThreshold                    = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsLgsThreshold)).toInt(),
            variable_sens                   = 0.0,
            insulinDivisor                  = 0,
            TDD                             = 0.0
        )

        val learningEnabled   = preferences.get(BooleanKey.ApsSmartInsulinEnableLearning)
        val learnedProfile    = profileLearner.getProfile(mealMode)
        val lowGuardMmol      = preferences.get(DoubleKey.ApsSmartInsulinLowGuardMmol)
        val warnGuardMmol     = preferences.get(DoubleKey.ApsSmartInsulinWarnGuardMmol)

        // Record current BG zone for aggression learning
        val highThreshMgdl = 180.0
        aggressionLearner.recordBg(
            bgMgdl          = glucoseStatus.glucose,
            lowThreshMgdl   = lowGuardMmol * 18.0,
            highThreshMgdl  = highThreshMgdl
        )
        val aggressiveness = aggressionLearner.aggressiveness
        val tirSummary     = aggressionLearner.tirSummary

        // Feed basal learner -- learns from any clean fasting window, day or night.
        // Overnight observations are confidence-weighted higher than daytime ones.
        val basalLearningEnabled = preferences.get(BooleanKey.ApsSmartInsulinBasalLearningEnabled)
        val minsLastBolus = iobArray.firstOrNull()?.lastBolusTime
            ?.let { if (it > 0) (System.currentTimeMillis() - it) / 60_000.0 else Double.MAX_VALUE }
            ?: Double.MAX_VALUE
        val basalOnlyIob  = iobArray.firstOrNull()?.basaliob ?: 0.0
        val currentIob    = iobArray.firstOrNull()?.iob ?: 0.0
        if (basalLearningEnabled) {
            basalLearner.onLoopCycle(
                bgMgdl        = glucoseStatus.glucose,
                deltaMgdl     = glucoseStatus.delta,
                cobG          = mealData.mealCOB,
                minsLastBolus = minsLastBolus,
                basalOnlyIobU = basalOnlyIob,
                currentIobU   = currentIob,
                isfMgdl       = profile.getIsfMgdl("SmartInsulin") * isfMultiplier,
                profileBasalU = profile.getBasal()
            )
        }
        val basalMultiplier = if (basalLearningEnabled) basalLearner.multiplierClamped else 1.0

        val maxSmbU           = preferences.get(DoubleKey.ApsSmartInsulinMaxSmb)
        val maxTbrU           = preferences.get(DoubleKey.ApsSmartInsulinMaxTbr)

        aapsLogger.debug(LTag.APS, "SmartInsulin mode=$mealMode isfMultiplier=$isfMultiplier learnedProfile=$learnedProfile")

        val microBolusAllowed = constraintsChecker.isSMBModeEnabled(
            ConstraintObject(tempBasalFallback.not(), aapsLogger)
        ).also { inputConstraints.copyReasons(it) }.value()

        val apsResult = determineBasalSmartInsulin.determine_basal(
            glucoseStatus         = glucoseStatus,
            currentTemp           = currentTemp,
            iobArray              = iobArray,
            oapsProfile           = oapsProfile,
            mealData              = mealData,
            profile               = profile,
            learnedProfile        = learnedProfile,
            mealMode              = mealMode,
            lowGuardMmol          = lowGuardMmol,
            warnGuardMmol         = warnGuardMmol,
            maxSmbU               = maxSmbU,
            maxTbrU               = maxTbrU,
            aggressiveness        = aggressiveness,
            tirSummary            = tirSummary,
            basalMultiplier       = basalMultiplier,
            microBolusAllowed     = microBolusAllowed,
            inReboundWindow       = inReboundWindow,
            msSinceLastSuspend    = msSinceLastSuspend,
            currentTime           = now
        )

        // Track suspend state for rebound protection
        val reason = apsResult.reason
        if (reason.contains("SUSPEND") || reason.contains("CAUTION") || reason.contains("LGS_SUSPEND")) {
            lastSuspendMs = now
            aapsLogger.debug(LTag.APS, "SmartInsulin: suspend recorded at $now")
        }

        apsResult.inputConstraints = inputConstraints
        apsResult.autosensResult   = autosensResult
        apsResult.iobData          = iobArray
        apsResult.glucoseStatus    = glucoseStatus
        apsResult.currentTemp      = currentTemp
        apsResult.oapsProfile      = oapsProfile
        apsResult.mealData         = mealData
        lastAPSResult              = apsResult
        lastAPSRun                 = now

        // Append learning status to reason so it's visible in the Loop tab
        if (learningEnabled) {
            bolusCurveTracker.onLoopCycle(glucoseStatus, mealMode, iobArray)
            apsResult.reason += " | ${bolusCurveTracker.statusSummary()}"
        }

        aapsLogger.debug(LTag.APS, "SmartInsulin result: $apsResult")

        rxBus.send(EventOpenAPSUpdateGui())
    }

    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? =
        glucoseStatusCalculatorSMB.getGlucoseStatusData(allowOldData)

    override fun configuration(): JSONObject =
        JSONObject()
            .put(BooleanKey.ApsSmartInsulinEnableLearning, preferences)
            .put(IntKey.ApsSmartInsulinPredictionHorizonMins, preferences)
            .put(DoubleKey.ApsSmartInsulinLearningRate, preferences)
            .put(DoubleKey.ApsSmartInsulinLowGuardMmol, preferences)
            .put(DoubleKey.ApsSmartInsulinWarnGuardMmol, preferences)

    override fun applyConfiguration(configuration: JSONObject) {
        configuration
            .store(BooleanKey.ApsSmartInsulinEnableLearning, preferences)
            .store(IntKey.ApsSmartInsulinPredictionHorizonMins, preferences)
            .store(DoubleKey.ApsSmartInsulinLearningRate, preferences)
            .store(DoubleKey.ApsSmartInsulinLowGuardMmol, preferences)
            .store(DoubleKey.ApsSmartInsulinWarnGuardMmol, preferences)
    }

    override fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (isEnabled()) {
            val maxIobPref = preferences.get(DoubleKey.ApsSmbMaxIob)
            maxIob.setIfSmaller(maxIobPref, rh.gs(R.string.limiting_iob, maxIobPref, rh.gs(R.string.maxvalueinpreferences)), this)
            maxIob.setIfSmaller(hardLimits.maxIobSMB(), rh.gs(R.string.limiting_iob, hardLimits.maxIobSMB(), rh.gs(R.string.hardlimit)), this)
        }
        return maxIob
    }

    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        if (isEnabled()) {
            var maxBasal = preferences.get(DoubleKey.ApsMaxBasal)
            if (maxBasal < profile.getMaxDailyBasal()) {
                maxBasal = profile.getMaxDailyBasal()
                absoluteRate.addReason(rh.gs(R.string.increasing_max_basal), this)
            }
            absoluteRate.setIfSmaller(maxBasal, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxBasal, rh.gs(R.string.maxvalueinpreferences)), this)
            val maxFromBasalMultiplier = floor(preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier) * profile.getBasal() * 100) / 100
            absoluteRate.setIfSmaller(maxFromBasalMultiplier, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxFromBasalMultiplier, rh.gs(R.string.max_basal_multiplier)), this)
            val maxFromDaily = floor(profile.getMaxDailyBasal() * preferences.get(DoubleKey.ApsMaxDailyMultiplier) * 100) / 100
            absoluteRate.setIfSmaller(maxFromDaily, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxFromDaily, rh.gs(R.string.max_daily_basal_multiplier)), this)
        }
        return absoluteRate
    }

    override fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (!preferences.get(BooleanKey.ApsUseSmb))
            value.set(false, rh.gs(R.string.smb_disabled_in_preferences), this)
        return value
    }

    override fun isUAMEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (!preferences.get(BooleanKey.ApsUseUam))
            value.set(false, rh.gs(R.string.uam_disabled_in_preferences), this)
        return value
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null && requiredKey != "smart_insulin_settings") return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "smart_insulin_settings"
            title = rh.gs(R.string.smart_insulin)
            initialExpandedChildrenCount = 0

            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmb,                        title = R.string.enable_smb))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbAlways,                  title = R.string.enable_smb_always))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbWithCob,                 title = R.string.enable_smb_with_cob))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbAfterCarbs,              title = R.string.enable_smb_after_carbs))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseUam,                        title = R.string.enable_uam))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmbMaxIob,                      title = R.string.openapssmb_max_iob_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsMaxBasal,                       title = R.string.openapsma_max_basal_title))
            addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsMaxSmbFrequency,                   title = R.string.smb_interval_summary))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinMaxSmb,             title = R.string.si_max_smb_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinMaxTbr,             title = R.string.si_max_tbr_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinAggressionMax,      title = R.string.si_aggression_max_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSmartInsulinBasalLearningEnabled, title = R.string.si_basal_learning_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSmartInsulinEnableLearning,    title = R.string.smart_insulin_enable_learning))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinLearningRate,       title = R.string.smart_insulin_learning_rate))
            addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinPredictionHorizonMins, title = R.string.smart_insulin_prediction_horizon))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinLowGuardMmol,       title = R.string.smart_insulin_low_guard))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinWarnGuardMmol,      title = R.string.smart_insulin_warn_guard))
            addPreference(AdaptiveUnitPreference(ctx = context, unitKey = UnitDoubleKey.ApsLgsThreshold, dialogMessage = R.string.lgs_threshold_summary, title = R.string.lgs_threshold_title))
            // Per-meal ISF multipliers
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinBreakfastIsfMultiplier, title = R.string.si_breakfast_isf_mult_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinLunchIsfMultiplier,     title = R.string.si_lunch_isf_mult_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinDinnerIsfMultiplier,    title = R.string.si_dinner_isf_mult_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinLowCarbIsfMultiplier,   title = R.string.si_lowcarb_isf_mult_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinExtendedIsfMultiplier,  title = R.string.si_extended_isf_mult_title))
            // Per-meal carb defaults
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsSmartInsulinBreakfastCarbsG, title = R.string.si_breakfast_carbs_g_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsSmartInsulinLunchCarbsG,     title = R.string.si_lunch_carbs_g_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsSmartInsulinDinnerCarbsG,    title = R.string.si_dinner_carbs_g_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsSmartInsulinModeWindowMins,  title = R.string.si_mode_window_mins_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsSmartInsulinMaxPreBolus, title = R.string.si_max_prebolus_title))
        }
    }
}