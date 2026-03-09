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
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.workflow.CalculationWorkflow
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
    private val basalLearner: BasalLearner,
    private val circadianLearner: CircadianLearner,
    private val csvLogger: LoopCsvLogger,
    private val calculationWorkflow: CalculationWorkflow,
    private val overviewData: OverviewData
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
    // Rebound protection only activates if BG actually went under the low threshold
    // during a suspend. A precautionary suspend that never caused a real low does
    // NOT trigger the rebound window.
    var bgWentLow: Boolean = false               // true once BG crossed below lowGuardMmol during suspend
    var previousBgMgdl: Double = 0.0             // BG from previous loop cycle for crossing detection
    var reboundWindowStartMs: Long = 0L          // set ONLY when BG crosses back above lowGuard — NOT during suspend
    val msSinceLastSuspend: Long get() = if (reboundWindowStartMs > 0L) System.currentTimeMillis() - reboundWindowStartMs else Long.MAX_VALUE
    val inReboundWindow: Boolean get() = reboundWindowStartMs > 0L &&
        bgWentLow &&
        msSinceLastSuspend < REBOUND_GUARD_MS

    companion object {
        const val REBOUND_GUARD_MS = 90 * 60 * 1000L  // 90 min rebound protection window
    }

    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
        aapsLogger.debug(LTag.APS, "SmartInsulin invoke from $initiator")
        val previousAPSResult = lastAPSResult   // save before nulling — used for rebound tracking
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
        val mealMode = MealModeDetector.detect(overrideManager = mealOverrideManager)

        // ── ISF multiplier — read from user prefs, not hardcoded enum default ─
        // Absolute ISF override per meal mode — 0.0 means not set, fall back to profile ISF
        // When set, this fully replaces profile ISF for both prediction and dosing
        val modeIsfMmol = when (mealMode) {
            MealMode.BREAKFAST -> preferences.get(DoubleKey.ApsSmartInsulinBreakfastIsf)
            MealMode.LUNCH     -> preferences.get(DoubleKey.ApsSmartInsulinLunchIsf)
            MealMode.DINNER    -> preferences.get(DoubleKey.ApsSmartInsulinDinnerIsf)
            MealMode.LOW_CARB  -> preferences.get(DoubleKey.ApsSmartInsulinLowCarbIsf)
            MealMode.EXTENDED  -> preferences.get(DoubleKey.ApsSmartInsulinExtendedIsf)
            MealMode.FASTING   -> 0.0  // always use profile ISF in fasting
        }
        val trueIsfMgdl   = profile.getIsfMgdl("SmartInsulinPlugin")
        // Apply circadian ISF multiplier during fasting (>1 = higher ISF = less aggressive)
        // Meal mode ISF overrides are user-set — don't touch them
        val dosingIsfMgdl = when {
            modeIsfMmol > 0.0 -> modeIsfMmol * 18.0           // user meal-mode override
            else              -> trueIsfMgdl * circIsfMult     // profile ISF × circadian learned multiplier
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
            sens                            = dosingIsfMgdl,  // meal mode ISF if set, else true profile ISF
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
        // TIR thresholds use clinical standard: low < 3.9 mmol (70 mg/dL), high > 10.0 mmol (180 mg/dL)
        // Deliberately NOT using lowGuard — the loop's safety threshold is stricter than clinical TIR low
        aggressionLearner.recordBg(
            bgMgdl          = glucoseStatus.glucose,
            lowThreshMgdl   = 70.0,   // 3.9 mmol — clinical TIR low threshold
            highThreshMgdl  = 180.0   // 10.0 mmol — clinical TIR high threshold
        )
        // Circadian aggressiveness ceiling — clamp global aggression downward per hour-of-day
        val circIsfMult    = circadianLearner.isfMultiplier()
        val circBasalMult  = circadianLearner.basalMultiplier()
        val circAggrCeil   = circadianLearner.aggrCeiling()
        val aggressiveness = aggressionLearner.aggressiveness.coerceAtMost(circAggrCeil)
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
                isfMgdl       = trueIsfMgdl,
                profileBasalU = profile.getBasal()
            )
        }
        // Blend flat BasalLearner with circadian per-hour learning
        // Circadian takes over proportionally as its confidence grows
        val flatBasalMult  = if (basalLearningEnabled) basalLearner.multiplierClamped else 1.0
        val basalMultiplier = flatBasalMult * circBasalMult

        val maxSmbU           = preferences.get(DoubleKey.ApsSmartInsulinMaxSmb)
        val maxTbrU           = preferences.get(DoubleKey.ApsSmartInsulinMaxTbr)
        val dawnWindowStart   = preferences.get(IntKey.ApsSmartInsulinDawnWindowStartHour)
        val dawnWindowEnd     = preferences.get(IntKey.ApsSmartInsulinDawnWindowEndHour)
        val dawnSmbReduction  = preferences.get(DoubleKey.ApsSmartInsulinDawnSmbReduction)
        val profileTargetMgdl = profile.getTargetMgdl()

        aapsLogger.debug(LTag.APS, "SmartInsulin mode=$mealMode modeISF=${if (modeIsfMmol > 0.0) modeIsfMmol else null} dosingIsfMgdl=$dosingIsfMgdl learnedProfile=$learnedProfile")

        // ── Rebound protection tracking ───────────────────────────────────────
        // Computed BEFORE determine_basal() so inReboundWindow is correct on the
        // exact cycle where BG first crosses back above the threshold.
        // Uses lowGuardMmol so rebound tracking matches the suspend threshold exactly.
        val REBOUND_LOW_THRESHOLD_MGDL = lowGuardMmol * 18.0
        val currentBgMgdl = glucoseStatus.glucose

        // During any suspend/caution, track if BG went low
        // We check the previous result's reason to know if we were suspending last cycle
        val wasSuspending = previousAPSResult?.reason?.let {
            it.contains("SUSPEND") || it.contains("CAUTION") || it.contains("LGS_SUSPEND")
        } ?: false

        if (wasSuspending) {
            // Track that BG went low — but do NOT start the rebound countdown yet.
            // The window only starts once BG recovers above the threshold.
            if (currentBgMgdl < REBOUND_LOW_THRESHOLD_MGDL) {
                bgWentLow = true
                aapsLogger.debug(LTag.APS, "SmartInsulin: BG went low (${currentBgMgdl} mg/dL), will watch for crossing")
            }
        } else {
            // Not suspending — check if BG just crossed back UP through threshold
            if (bgWentLow && previousBgMgdl < REBOUND_LOW_THRESHOLD_MGDL && currentBgMgdl >= REBOUND_LOW_THRESHOLD_MGDL) {
                // Arm the window NOW — from the crossing moment, not from when suspend started
                reboundWindowStartMs = now
                aapsLogger.debug(LTag.APS, "SmartInsulin: BG crossed back above ${REBOUND_LOW_THRESHOLD_MGDL} mg/dL — rebound window armed")
            }
            if (!inReboundWindow) {
                reboundWindowStartMs = 0L
                bgWentLow = false
            }
        }
        previousBgMgdl = currentBgMgdl

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
            dosingIsfMgdl         = dosingIsfMgdl,
            microBolusAllowed     = microBolusAllowed,
            inReboundWindow       = inReboundWindow,
            msSinceLastSuspend    = msSinceLastSuspend,
            currentTime           = now,
            isTempTarget          = isTempTarget,
            profileTargetMgdl     = profileTargetMgdl,
            dawnWindowStartHour   = dawnWindowStart,
            dawnWindowEndHour     = dawnWindowEnd,
            dawnSmbReduction      = dawnSmbReduction,
            bgWentLow             = bgWentLow
        )

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
            apsResult.reason += " | ${bolusCurveTracker.statusSummary(mealMode)}"
        }

        // ── Circadian learner update ──────────────────────────────────────────
        circadianLearner.update(
            glucoseStatus  = glucoseStatus,
            iobArray       = iobArray,
            mealMode       = mealMode,
            cobG           = mealData.mealCOB,
            profileIsfMgdl = trueIsfMgdl,
            profileBasalUh = profile.getBasal(),
            targetMgdl     = oapsProfile.target_bg.toDouble()
        )

        // ── CSV logging ───────────────────────────────────────────────────────
        val zone = when {
            apsResult.reason.contains("LGS_SUSPEND") -> "LGS_SUSPEND"
            apsResult.reason.contains("SUSPEND")     -> "SUSPEND"
            apsResult.reason.contains("CAUTION")     -> "CAUTION"
            else                                     -> "NORMAL"
        }
        csvLogger.log(LoopCsvLogger.LogRow(
            timestampMs       = now,
            bgMmol            = glucoseStatus.glucose / 18.0,
            delta             = glucoseStatus.shortAvgDelta / 18.0,
            iob               = iobArray.firstOrNull()?.iob ?: 0.0,
            cob               = mealData.mealCOB,
            mealMode          = mealMode.name,
            isfUsedMmol       = dosingIsfMgdl / 18.0,
            basalUsed         = profile.getBasal() * basalMultiplier,
            aggrUsed          = aggressiveness,
            circIsfMult       = circIsfMult,
            circBasalMult     = circBasalMult,
            circAggrCeil      = circAggrCeil,
            smbU              = apsResult.units ?: 0.0,
            tbrRate           = apsResult.rate ?: profile.getBasal(),
            zone              = zone,
            reboundActive     = inReboundWindow,
            reboundElapsedMin = (msSinceLastSuspend / 60_000).toInt().coerceAtMost(999)
        ))

        // Append mode time remaining if an override is active
        val modeRemainingMs = mealOverrideManager.modeTimeRemainingMs
        if (modeRemainingMs > 0L) {
            val modeRemainingMins = modeRemainingMs / 60_000
            apsResult.reason += " | Time left in ${mealMode.label} mode: ${modeRemainingMins}min"
        }

        aapsLogger.debug(LTag.APS, "SmartInsulin result: $apsResult")

        calculationWorkflow.runOnReceivedPredictions(overviewData)
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
            addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinDawnWindowStartHour,   title = R.string.si_dawn_start_hour_title))
            addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinDawnWindowEndHour,     title = R.string.si_dawn_end_hour_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinDawnSmbReduction,   title = R.string.si_dawn_smb_reduction_title))
            // Per-meal ISF multipliers
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinBreakfastIsf, title = R.string.si_breakfast_isf_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinLunchIsf,     title = R.string.si_lunch_isf_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinDinnerIsf,    title = R.string.si_dinner_isf_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinLowCarbIsf,   title = R.string.si_lowcarb_isf_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinExtendedIsf,  title = R.string.si_extended_isf_title))
            // Per-meal carb defaults
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsSmartInsulinBreakfastCarbsG, title = R.string.si_breakfast_carbs_g_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsSmartInsulinLunchCarbsG,     title = R.string.si_lunch_carbs_g_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsSmartInsulinDinnerCarbsG,    title = R.string.si_dinner_carbs_g_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsSmartInsulinModeWindowMins,  title = R.string.si_mode_window_mins_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsSmartInsulinMaxPreBolus, title = R.string.si_max_prebolus_title))
        }
    }
}