package app.aaps.plugins.aps.smartInsulin

import android.content.Context
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
import app.aaps.plugins.aps.smartInsulin.SmartInsulinFragment
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
    private val stftController: StftController,
    private val uamController: UamController,
    private val profileLearner: ProfileLearner,
    private val bolusCurveTracker: BolusCurveTracker,
    private val aggressionLearner: AggressionLearner,
    private val basalLearner: BasalLearner,
    private val circadianLearner: CircadianLearner,
    private val activityMonitor:  ActivityMonitor,
    private val cgmWarmupGuard:   CgmWarmupGuard,
    private val aapsSchedulers:   app.aaps.core.interfaces.rx.AapsSchedulers,
    private val csvLogger: LoopCsvLogger,
    private val calculationWorkflow: CalculationWorkflow,
    private val overviewData: OverviewData
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
), APS, PluginConstraints, app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview {

    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.SMB
    override var lastAPSResult: APSResult? = null

    // ── Suspend / rebound tracking ────────────────────────────────────────────
    // Rebound protection only activates if BG actually went under the low threshold
    // during a suspend. A precautionary suspend that never caused a real low does
    // NOT trigger the rebound window.
    var bgWentLow: Boolean = false               // true once BG crossed below suspend threshold during a zero temp
    var reboundWindowStartMs: Long = 0L          // set ONLY when BG crosses back above lowGuard — NOT during suspend
    val msSinceLastSuspend: Long get() = if (reboundWindowStartMs > 0L) System.currentTimeMillis() - reboundWindowStartMs else Long.MAX_VALUE
    val inReboundWindow: Boolean get() = reboundWindowStartMs > 0L &&
        bgWentLow &&
        msSinceLastSuspend < REBOUND_GUARD_MS

    companion object {
        const val REBOUND_GUARD_MS = 60 * 60 * 1000L  // 60 min rebound protection window
    }

    // ── Cached Overview state ────────────────────────────────────────────────
    // Updated each invoke() so overviewState() can be called any time from UI threads.
    @Volatile private var cachedOverviewState: app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview.OverviewState =
        app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview.OverviewState(
            modeLine      = "Meal: Fasting",
            pb2Line       = null,
            learningState = "Learning"
        )

    // ── Reset all learners ────────────────────────────────────────────────────

    fun resetAllLearners() {
        aggressionLearner.reset()
        basalLearner.reset()
        circadianLearner.reset()
        profileLearner.resetProfiles()
        bgWentLow          = false
        reboundWindowStartMs = 0L
        aapsLogger.debug(LTag.APS, "SmartInsulinPlugin: all learners reset")
    }

    fun resetAggression() {
        aggressionLearner.reset()
        aapsLogger.debug(LTag.APS, "SmartInsulinPlugin: aggression reset")
    }

    fun resetBasal() {
        basalLearner.reset()
        circadianLearner.resetBasal()
        aapsLogger.debug(LTag.APS, "SmartInsulinPlugin: basal learners reset")
    }

    fun resetCircadian() {
        circadianLearner.reset()
        aapsLogger.debug(LTag.APS, "SmartInsulinPlugin: circadian reset")
    }

    fun resetProfiles() {
        profileLearner.resetProfiles()
        aapsLogger.debug(LTag.APS, "SmartInsulinPlugin: profiles reset")
    }

    // ── Status summary for tab UI ─────────────────────────────────────────────

    fun statusSummary(): String {
        val cal  = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val dow  = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1
        val day  = DayOfWeekCircadianState.DAY_LABELS[dow.coerceIn(0, 6)]
        return buildString {
            appendLine()

            // ── Active cycle values ───────────────────────────────────────────
            appendLine("── Active (Hour=${hour}:00, Day=$day) ─────────────────")
            val effectiveAggr = aggressionLearner.aggressiveness.coerceAtMost(circadianLearner.aggrCeiling(hour))
            appendLine("  Aggressiveness: ${"%.3f".format(effectiveAggr)}")
            appendLine("  TIR score: ${"%.3f".format(aggressionLearner.aggressiveness)} (>1.0=more aggressive, <1.0=backing off)")
            appendLine("  Circ ceiling: ${"%.3f".format(circadianLearner.aggrCeiling(hour))} (clamps score downward if < score)")
            appendLine("  Meal mode: aggressiveness locked to 1.0 during any non-fasting mode")
            appendLine("  ISF multiplier: ${"%.3f".format(circadianLearner.isfMultiplier(hour))}")
            appendLine("  Basal multiplier: ${"%.3f".format(basalLearner.multiplierClamped * circadianLearner.basalMultiplier(hour))} " +
                           "(flat=${"%.3f".format(basalLearner.multiplierClamped)} circ=${"%.3f".format(circadianLearner.basalMultiplier(hour))})")
            appendLine("  ${aggressionLearner.tirSummary}")
            if (inReboundWindow) appendLine("  ⚠️ REBOUND ACTIVE ${msSinceLastSuspend / 60_000}min elapsed")

            // ── Meal override / pre-bolus 2 status ───────────────────────────
            val activeMode = mealOverrideManager.activeMealMode
            if (activeMode != null) {
                val modeRemMins = mealOverrideManager.modeTimeRemainingMs / 60_000
                appendLine("  Mode active   : ${activeMode.label} (${modeRemMins}min remaining)")
            }
            val pb2Status = mealOverrideManager.preBolus2StatusText
            if (pb2Status.isNotEmpty()) {
                appendLine("  ${pb2Status.replace("PB2 waiting:", "PB2:").replace("PB2 active:", "PB2:")}")
            }

            // ── Learning state ────────────────────────────────────────────────
            appendLine()
            appendLine("── Learning ──────────────────────────")
            val state = cachedOverviewState
            val learningDisplay = when (state.learningState) {
                "limited" -> "Limited due to meal mode - DIA/Peak only"
                else      -> state.learningState
            }
            appendLine("  Learning: $learningDisplay")

            // ── Activity ─────────────────────────────────────────────────────
            val actLevel = activityMonitor.level
            appendLine("  Activity: ${actLevel.label} " +
                           "hr=${activityMonitor.avgHrBpm.toInt()}avg " +
                           "steps=${activityMonitor.lastSteps5min}/5m")
            appendLine()

            // ── Circadian tables ──────────────────────────────────────────────
            appendLine("── Circadian 24h ─────────────────────")
            appendLine("  h   ISF×   Bas×   Ceil   Conf%")
            for (h in 0..23) {
                val marker = if (h == hour) "▶" else " "
                appendLine("$marker ${h.toString().padStart(2)}  " +
                               "${"%.3f".format(circadianLearner.isfMultiplier(h))}  " +
                               "${"%.3f".format(circadianLearner.basalMultiplier(h))}  " +
                               "${"%.3f".format(circadianLearner.aggrCeiling(h))}  " +
                               "${"%.0f".format(circadianLearner.confidencePct(h))}%")
            }
            appendLine()

            // ── Profiles ──────────────────────────────────────────────────────
            appendLine("── Insulin Profiles ──────────────────")
            app.aaps.core.interfaces.smartInsulin.MealMode.entries.forEach { mode ->
                val p = profileLearner.getProfile(mode)
                appendLine("  ${mode.label.padEnd(10)}: peak=${p.peakMinutes.toInt()}m  dia=${p.diaMinutes.toInt()}m  n=${p.sampleCount}")
            }
        }.trimEnd()
    }

    // ── Public state accessors for Overview display ─────────────────────────

    /**
     * Returns a one-line suppression reason for the Overview "State" cell,
     * or null if learning is currently active.
     * Called from OverviewFragment.updateIobCob() each loop cycle.
     */
    override fun overviewState(): app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview.OverviewState =
        cachedOverviewState

    // ── RxBus subscriptions for HR and steps from wear ───────────────────────
    override fun onStart() {
        super.onStart()
        // ActivityMonitor now queries persistenceLayer directly each loop cycle.
        // No RxBus subscription needed — HR and steps are read from DB on demand.
        aapsLogger.debug(LTag.APS, "SmartInsulinPlugin: onStart")
    }

    override fun onStop() {
        super.onStop()
        aapsLogger.debug(LTag.APS, "SmartInsulinPlugin: onStop")
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
            MealMode.BREAKFAST     -> preferences.get(DoubleKey.ApsSmartInsulinBreakfastIsf)
            MealMode.LUNCH         -> preferences.get(DoubleKey.ApsSmartInsulinLunchIsf)
            MealMode.DINNER        -> preferences.get(DoubleKey.ApsSmartInsulinDinnerIsf)
            MealMode.LOW_CARB      -> preferences.get(DoubleKey.ApsSmartInsulinLowCarbIsf)
            MealMode.EXTENDED      -> preferences.get(DoubleKey.ApsSmartInsulinExtendedIsf)
            MealMode.UAM_BREAKFAST -> preferences.get(DoubleKey.ApsSmartInsulinUamBreakfastIsf)
            MealMode.UAM_LUNCH     -> preferences.get(DoubleKey.ApsSmartInsulinUamLunchIsf)
            MealMode.UAM_DINNER    -> preferences.get(DoubleKey.ApsSmartInsulinUamDinnerIsf)
            MealMode.UAM_SNACK     -> preferences.get(DoubleKey.ApsSmartInsulinUamSnackIsf)
            MealMode.UAM_LOW_CARB  -> preferences.get(DoubleKey.ApsSmartInsulinUamLowCarbIsf)
            MealMode.FASTING   -> 0.0  // always use profile ISF in fasting
        }
        val trueIsfMgdl   = profile.getIsfMgdl("SmartInsulinPlugin")
        // Circadian per-hour multipliers — computed here so circIsfMult is available for dosingIsfMgdl
        val circIsfMult   = circadianLearner.isfMultiplier()
        val circBasalMult = circadianLearner.basalMultiplier()
        val circAggrCeil  = circadianLearner.aggrCeiling()
        // Apply circadian ISF multiplier during fasting (>1 = higher ISF = less aggressive)
        // Meal mode ISF overrides are user-set — don't touch them
        // circIsfMult > 1.0 → divide → dosingISF goes DOWN → less insulin (insulin weaker than profile)
        // circIsfMult < 1.0 → divide → dosingISF goes UP   → more insulin (insulin stronger than profile)
        // This is correct: circIsfMult is a sensitivity multiplier, not a direct ISF scalar.
        val dosingIsfMgdl = when {
            modeIsfMmol > 0.0 -> modeIsfMmol * 18.0               // user meal-mode override — absolute
            else              -> trueIsfMgdl / circIsfMult         // divide: mult>1 → lower dosingISF → less insulin
        }

        // ── Tick the override manager — fires queued bolus when safe ──────────
        mealOverrideManager.onLoopCycle(
            glucoseStatus = glucoseStatus,
            iobArray      = iobArray,
            maxIobU       = constraintsChecker.getMaxIOBAllowed().value()
        )

        // ── STFT: short-term target reduction for stuck-high fasting BG ──────
        // Only runs in fasting, never overrides a deliberate temp target.
        // Adjusts targetBg downward to make the loop naturally more aggressive
        // without touching ISF, basal, aggressiveness, or any learners.
        // STFT runs every cycle — it handles meal mode and temp target suppression internally.
        // When a temp target is active we still call it so it can reset cleanly, but we
        // discard the adjusted value and keep the user's deliberate temp target.
        val profileTargetMgdl = profile.getTargetMgdl()
        val stftAdjusted = stftController.onLoopCycle(
            profileTargetMgdl = profileTargetMgdl,
            currentBgMgdl     = glucoseStatus.glucose,
            delta             = glucoseStatus.delta,
            mealMode          = mealMode
        )
        val stftTargetMgdl = if (!isTempTarget) stftAdjusted else targetBg

        // ── UAM: auto-detect unannounced meals from BG rise during fasting ────
        // Only fires in FASTING mode within configured time windows.
        // Activates the appropriate UAM mode via MealOverrideManager — no bolus,
        // ISF-only adjustment. Hard cutoff at configured night hour (default 23:00).
        val uamCurrentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        uamController.onLoopCycle(
            currentMealMode = mealMode,
            currentBgMmol   = glucoseStatus.glucose / 18.0,
            deltaMmol       = glucoseStatus.delta / 18.0,
            currentHour     = uamCurrentHour
        )

        // Track UAM mode expiry for re-arm — if previous cycle had a UAM mode and now we're fasting
        if (previousAPSResult != null && mealMode == MealMode.FASTING) {
            // Check if we just transitioned out of a UAM mode
            val prevMealMode = mealOverrideManager.activeMealMode
            if (prevMealMode == null) uamController.onUamModeExpired()
        }

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
            target_bg                       = stftTargetMgdl,
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
        // UAM modes share peak/DIA learning with their parent mode — they accumulate
        // separate observations but start from the same profile. This means UAM_LUNCH
        // uses LUNCH's learned peak/DIA until it has its own samples.
        val learnedProfileMode = when (mealMode) {
            MealMode.UAM_BREAKFAST -> MealMode.BREAKFAST
            MealMode.UAM_LUNCH     -> MealMode.LUNCH
            MealMode.UAM_DINNER    -> MealMode.DINNER
            MealMode.UAM_SNACK     -> MealMode.DINNER   // closest equivalent
            MealMode.UAM_LOW_CARB  -> MealMode.LOW_CARB
            else                   -> mealMode
        }
        val learnedProfile    = profileLearner.getProfile(learnedProfileMode)

        // ── Activity monitor — recompute from fed HR/steps data ─────────────
        // ActivityMonitor queries persistenceLayer directly — no feed calls needed.
        // See WiringNotes.md for the subscription setup.
        // If no data has been fed (no wear device, watch not worn), defaults to SEDENTARY.
        val restingHrBpm = preferences.get(DoubleKey.ApsSmartInsulinRestingHrBpm)
        activityMonitor.recompute(nowMs = now, restingHrBpm = restingHrBpm)

        // ── CGM warmup guard ─────────────────────────────────────────────────
        // Sensor start time: use gap detection (automatic) + TherapyEvent if available.
        // Pass glucoseStatus.date as latestBgTimestampMs — guard tracks gaps internally.
        // sensorInsertTimeMs = 0 means "unknown, use gap detection only".
        val cgmGuardEnabled = preferences.get(BooleanKey.ApsSmartInsulinCgmWarmupEnabled)
        // Query last sensor change from DB — covers fresh installs/rebuilds mid-sensor
        // where the gap-detection state was lost. Look back 30 days max.
        val sensorInsertTimeMs: Long = try {
            val sensorEvents = persistenceLayer.getTherapyEventDataFromTime(
                now - 30 * 24 * 60 * 60 * 1000L,
                TE.Type.SENSOR_CHANGE,
                true
            )
            sensorEvents.maxByOrNull { it.timestamp }?.timestamp ?: 0L
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "SmartInsulinPlugin: sensorChange query failed: ${e.message}")
            0L
        }
        val cgmState = cgmWarmupGuard.evaluate(
            enabled             = cgmGuardEnabled,
            sensorInsertTimeMs  = sensorInsertTimeMs,
            nowMs               = now,
            latestBgTimestampMs = glucoseStatus.date,
            deltaMmol           = glucoseStatus.delta / 18.0,
            shortAvgDeltaMmol   = glucoseStatus.shortAvgDelta / 18.0,
            longAvgDeltaMmol    = glucoseStatus.longAvgDelta / 18.0,
            noiseLevelRaw       = glucoseStatus.noise
        )

        // Suppress learning during CGM warmup — noisy readings corrupt all learned models
        // CGM warmup: suppress ISF/basal/TIR adaptive learning but keep rollercoaster protection
        // Activity: suppress all learning (BG changes are exercise-driven, not insulin-driven)
        val suppressAdaptiveLearning = activityMonitor.suppressLearning || cgmState.suppressLearning
        val suppressRollercoaster    = activityMonitor.suppressLearning  // activity only — not CGM warmup

        // Activity target offset (user-configured mmol offsets per activity level)
        val activityLightTarget    = preferences.get(DoubleKey.ApsSmartInsulinActivityLightTargetMmol)
        val activityModerateTarget = preferences.get(DoubleKey.ApsSmartInsulinActivityModerateTargetMmol)
        val activityHeavyTarget    = preferences.get(DoubleKey.ApsSmartInsulinActivityHeavyTargetMmol)
        val activityTargetEnabled    = preferences.get(BooleanKey.ApsSmartInsulinActivityTargetEnabled)
        val activityTargetOffsetMmol = if (activityTargetEnabled) {
            activityMonitor.targetOffsetMmol(
                lightMmol    = activityLightTarget,
                moderateMmol = activityModerateTarget,
                heavyMmol    = activityHeavyTarget
            )
        } else 0.0

        if (suppressAdaptiveLearning) {
            aapsLogger.debug(LTag.APS, "SmartInsulin: learning suppressed " +
                "(activity=${activityMonitor.level} cgmWarmup=${cgmState.inWarmup})")
        }

        // Record current BG zone for aggression learning
        // TIR thresholds use clinical standard: low < 3.9 mmol (70 mg/dL), high > 10.0 mmol (180 mg/dL)
        // Deliberately NOT using lowGuard — the loop's safety threshold is stricter than clinical TIR low
        aggressionLearner.recordBg(
            bgMgdl          = glucoseStatus.glucose,
            lowThreshMgdl   = 70.0,   // 3.9 mmol — clinical TIR low threshold
            highThreshMgdl  = 180.0,  // 10.0 mmol — clinical TIR high threshold
            mealMode        = mealMode
        )
        // During meal modes: aggressiveness = 1.0, loop uses profile ISF/basal + learned peak/DIA only
        // Fasting: apply circadian ceiling (which can only reduce aggressiveness, never inflate)
        val aggressiveness = if (mealMode != MealMode.FASTING) 1.0
        else aggressionLearner.aggressiveness.coerceAtMost(circAggrCeil)
        val tirSummary     = aggressionLearner.tirSummary

        // Feed basal learner — fasting only, no high temp target
        // High TT = deliberate conservative mode (exercise/illness) — don't learn from it
        // Meal modes = COB active, loop reacting to carbs — basal signal is meaningless
        val basalLearningEnabled = preferences.get(BooleanKey.ApsSmartInsulinBasalLearningEnabled)
        val highTempTarget = isTempTarget && targetBg > profileTargetMgdl


        // ── Cache Overview state — updated here where all conditions are in scope ──
        // highTempTarget, mealMode, cgmState, activityMonitor all available now.
        val learningEnabledCache = preferences.get(BooleanKey.ApsSmartInsulinEnableLearning)
        val isMealMode = mealMode != MealMode.FASTING
        val learningStateStr = when {
            !learningEnabledCache                -> "off: Learning disabled"
            activityMonitor.suppressLearning     -> "off: Activity ${activityMonitor.level.label}"
            cgmState.suppressLearning            -> "off: CGM warmup"
            highTempTarget                       -> "off: High temp target"
            isMealMode || mealMode.isUam         -> "limited"  // DIA/peak only — no basal/ISF learning
            else                                 -> "Learning"
        }
        val modeLineStr = mealOverrideManager.activeMealMode?.let { mode ->
            val prefix = if (mode.isUam) "Meal: UAM" else "Meal:"
            val shortLabel = when (mode) {
                MealMode.UAM_BREAKFAST -> "Breakfast"
                MealMode.UAM_LUNCH     -> "Lunch"
                MealMode.UAM_DINNER    -> "Dinner"
                MealMode.UAM_SNACK     -> "Snack"
                MealMode.UAM_LOW_CARB  -> "Low Carb"
                else                   -> mode.label
            }
            "$prefix $shortLabel ${mealOverrideManager.modeTimeRemainingMs / 60_000}m"
        } ?: "Meal: Fasting"
        val pb2LineStr = if (mealOverrideManager.preBolus2Pending) {
            val msRem = mealOverrideManager.preBolus2SecondsRemaining  // name says "Seconds" but returns ms
            when {
                msRem == null || msRem <= 0 -> "PB2 active: due"
                else                        -> "PB2 active: ${msRem / 60_000}m"
            }
        } else null
        cachedOverviewState = app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview.OverviewState(
            modeLine      = modeLineStr,
            pb2Line       = pb2LineStr,
            learningState = learningStateStr
        )

        val minsLastBolus = iobArray.firstOrNull()?.lastBolusTime
            ?.let { if (it > 0) (System.currentTimeMillis() - it) / 60_000.0 else Double.MAX_VALUE }
            ?: Double.MAX_VALUE
        if (basalLearningEnabled && mealMode == MealMode.FASTING && !highTempTarget && !suppressAdaptiveLearning) {
            basalLearner.onLoopCycle(
                bgMgdl        = glucoseStatus.glucose,
                deltaMgdl     = glucoseStatus.delta,
                cobG          = mealData.mealCOB,
                minsLastBolus = minsLastBolus,
                isfMgdl       = trueIsfMgdl,
                profileBasalU = profile.getBasal()
            )
        } else {
            aapsLogger.debug(LTag.APS, "BasalLearner suppressed: mode=$mealMode highTT=$highTempTarget activity=${activityMonitor.level} cgmWarmup=${cgmState.inWarmup}")
        }
        // Blend flat BasalLearner with circadian per-hour learning
        // Circadian takes over proportionally as its confidence grows
        val flatBasalMult  = if (basalLearningEnabled) basalLearner.multiplierClamped else 1.0
        val basalMultiplier = flatBasalMult * circBasalMult

        val maxSmbU           = preferences.get(DoubleKey.ApsSmartInsulinMaxSmb)
        val dawnWindowStart   = preferences.get(IntKey.ApsSmartInsulinDawnWindowStartHour)
        val dawnWindowEnd     = preferences.get(IntKey.ApsSmartInsulinDawnWindowEndHour)
        val dawnSmbReduction  = preferences.get(DoubleKey.ApsSmartInsulinDawnSmbReduction)

        aapsLogger.debug(LTag.APS, "SmartInsulin mode=$mealMode modeISF=${if (modeIsfMmol > 0.0) modeIsfMmol else null} dosingIsfMgdl=$dosingIsfMgdl learnedProfile=$learnedProfile")

        // ── Rebound protection tracking ───────────────────────────────────────
        // Computed BEFORE determine_basal() so inReboundWindow is correct on the
        // exact cycle where BG first crosses back above the threshold.
        // Matches the lowGuardMmol threshold used in determine_basal's SUSPEND decision.
        val REBOUND_LOW_THRESHOLD_MGDL = preferences.get(DoubleKey.ApsSmartInsulinLowGuardMmol) * 18.0
        val currentBgMgdl = glucoseStatus.glucose

        // Rebound window is only relevant during FASTING mode.
        // If a meal mode is active, clear any stale rebound state so it doesn't carry over
        // to the post-meal fasting period and cause unnecessary insulin restriction.
        if (mealMode != MealMode.FASTING && (bgWentLow || reboundWindowStartMs > 0L)) {
            bgWentLow = false
            reboundWindowStartMs = 0L
            aapsLogger.debug(LTag.APS, "SmartInsulin: rebound state cleared — meal mode active (${mealMode.label})")
        }

        // Rebound arming is keyed purely on actual BG threshold crossings — not on whether
        // the previous APS result was suspending. This ensures the recovery taper starts on
        // the exact loop where BG crosses back above lowGuard, with no one-cycle delay.

        // 1) BG is below lowGuard: mark that a real low occurred.
        //    If we were already in a rebound window, reset the timer so the cycle restarts
        //    fresh once BG recovers again.
        if (currentBgMgdl < REBOUND_LOW_THRESHOLD_MGDL) {
            if (!bgWentLow) {
                aapsLogger.debug(LTag.APS, "SmartInsulin: BG went low (${currentBgMgdl} mg/dL), watching for recovery")
            }
            bgWentLow = true
            if (reboundWindowStartMs > 0L) {
                reboundWindowStartMs = 0L
                aapsLogger.debug(LTag.APS, "SmartInsulin: BG dropped below lowGuard (${currentBgMgdl} mg/dL) during rebound window — resetting timer")
            }
        }

        // 2) BG has recovered above lowGuard after a real low — arm the rebound window
        //    immediately on this cycle.
        if (bgWentLow && reboundWindowStartMs == 0L && currentBgMgdl >= REBOUND_LOW_THRESHOLD_MGDL) {
            reboundWindowStartMs = now
            aapsLogger.debug(LTag.APS, "SmartInsulin: BG above ${REBOUND_LOW_THRESHOLD_MGDL} mg/dL after low — rebound window armed")
        }

        // 3) Clear state once the full 60-min rebound window has elapsed.
        if (bgWentLow && reboundWindowStartMs > 0L && !inReboundWindow) {
            reboundWindowStartMs = 0L
            bgWentLow = false
            aapsLogger.debug(LTag.APS, "SmartInsulin: rebound window elapsed — clearing")
        }

        val microBolusAllowed = constraintsChecker.isSMBModeEnabled(
            ConstraintObject(tempBasalFallback.not(), aapsLogger)
        ).also { inputConstraints.copyReasons(it) }.value()

        val apsResult = determineBasalSmartInsulin.determine_basal(
            glucoseStatus            = glucoseStatus,
            currentTemp              = currentTemp,
            iobArray                 = iobArray,
            oapsProfile              = oapsProfile,
            mealData                 = mealData,
            profile                  = profile,
            learnedProfile           = learnedProfile,
            mealMode                 = mealMode,
            lowGuardMmol             = preferences.get(DoubleKey.ApsSmartInsulinLowGuardMmol),
            warnGuardMmol            = preferences.get(DoubleKey.ApsSmartInsulinWarnGuardMmol),
            maxSmbU                  = maxSmbU,
            maxTbrU                  = preferences.get(DoubleKey.ApsSmartInsulinMaxTbr),
            aggressiveness           = aggressiveness,
            tirSummary               = aggressionLearner.tirSummary,
            basalMultiplier          = basalMultiplier,
            dosingIsfMgdl            = dosingIsfMgdl,
            microBolusAllowed        = microBolusAllowed,
            inReboundWindow          = inReboundWindow,
            msSinceLastSuspend       = msSinceLastSuspend,
            currentTime              = now,
            isTempTarget             = isTempTarget,
            profileTargetMgdl        = profileTargetMgdl,
            dawnWindowStartHour      = dawnWindowStart,
            dawnWindowEndHour        = dawnWindowEnd,
            dawnSmbReduction         = dawnSmbReduction,
            bgWentLow                = bgWentLow,
            activityLevel            = activityMonitor.level,
            activityTargetOffsetMmol = activityTargetOffsetMmol,
            cgmSmbFraction           = cgmState.smbFraction,
            cgmDeltaPlausible        = cgmState.deltaPlausible,
            cgmWarmupReason          = cgmState.reason
        )

        // Append STFT status to reason if active
        stftController.statusString()?.let { apsResult.reason += " | $it" }
        uamController.statusString()?.let  { apsResult.reason += " | $it" }

        apsResult.inputConstraints = inputConstraints
        apsResult.autosensResult   = autosensResult
        apsResult.iobData          = iobArray
        apsResult.glucoseStatus    = glucoseStatus
        apsResult.currentTemp      = currentTemp
        apsResult.oapsProfile      = oapsProfile
        apsResult.mealData         = mealData
        lastAPSResult              = apsResult
        lastAPSRun                 = now

        // ── BolusCurveTracker — meal modes only (peak/DIA learning from bolus curves)
        // This is intentionally NOT suppressed during high TT — a meal bolus during
        // a high TT is still a valid peak/DIA observation.
        if (learningEnabled) {
            bolusCurveTracker.onLoopCycle(glucoseStatus, mealMode, iobArray)
            apsResult.reason += " | ${bolusCurveTracker.statusSummary(mealMode)}"
        }

        // ── Circadian learner — fasting + no high TT only ─────────────────────
        // ISF/basal/aggr circadian learning is only valid during clean fasting windows.
        // The circadian learner itself also gates on mealMode==FASTING internally,
        // but we gate highTempTarget here before the call to avoid polluting bgHistory.
        // Circadian learner:
        //   - Always call during normal conditions
        //   - During CGM warmup: call with suppressAdaptiveLearning=true so rollercoaster still fires
        //   - During activity or high TT: skip entirely (BG movement isn't insulin-driven)
        if (!highTempTarget && !suppressRollercoaster) {
            circadianLearner.update(
                glucoseStatus            = glucoseStatus,
                iobArray                 = iobArray,
                mealMode                 = mealMode,
                cobG                     = mealData.mealCOB,
                profileIsfMgdl           = trueIsfMgdl,
                targetMgdl               = oapsProfile.target_bg.toDouble(),
                suppressAdaptiveLearning = suppressAdaptiveLearning
            )
        } else {
            aapsLogger.debug(LTag.APS, "CircadianLearner skipped: highTT=$highTempTarget activity=${activityMonitor.level}")
        }

        // Append per-cycle learner summary to reason — visible in Loop tab
        // Format: circ(ISF×1.00 bas×1.00 ceil=0.85) basal×1.02 aggr=0.92/1.10
        val circHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        // Activity status for Loop tab reason string
        // Always show HR and steps so data flow is visible even when sedentary
        val activitySuffix = when (activityMonitor.level) {
            ActivityMonitor.ActivityLevel.SEDENTARY ->
                " | hr=${activityMonitor.avgHrBpm.toInt()} steps=${activityMonitor.lastSteps5min}/5m"
            else -> {
                val offsetStr = "%.1f".format(activityTargetOffsetMmol)
                " | activity=${activityMonitor.level.label}(+${offsetStr}mmol" +
                    " hr=${activityMonitor.avgHrBpm.toInt()} steps=${activityMonitor.lastSteps5min}/5m)"
            }
        }
        // CGM warmup/block suffix
        val cgmSuffix = if (cgmState.reason.isNotEmpty()) " | ${cgmState.reason}" else ""

        apsResult.reason += " | circ(ISF×${"%.2f".format(circIsfMult)} bas×${"%.2f".format(circBasalMult)} ceil=${"%.2f".format(circAggrCeil)})" +
            " basal×${"%.2f".format(basalMultiplier)}" +
            " aggr=${"%.2f".format(aggressiveness)}/${"%.2f".format(aggressionLearner.aggressiveness)}" +
            (if (inReboundWindow) " rebound=${msSinceLastSuspend / 60_000}min" else "") +
            activitySuffix +
            cgmSuffix

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
            smbU              = apsResult.smb,
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
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSmartInsulinCgmWarmupEnabled,       title = R.string.si_cgm_warmup_enabled_title))
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
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsSmartInsulinMaxPreBolus,           title = R.string.si_max_prebolus_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsSmartInsulinPreBolus2DefaultU,         title = R.string.si_prebolus2_default_u_title))
            addPreference(AdaptiveIntPreference(   ctx = context, intKey    = IntKey.ApsSmartInsulinPreBolus2DefaultDelayMins,    title = R.string.si_prebolus2_default_delay_title))
            // Activity monitor — target raises during exercise
            addPreference(AdaptiveSwitchPreference(  ctx = context, booleanKey = BooleanKey.ApsSmartInsulinActivityTargetEnabled,         title = R.string.si_activity_target_enabled_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsSmartInsulinActivityLightTargetMmol,    title = R.string.si_activity_light_target_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsSmartInsulinActivityModerateTargetMmol, title = R.string.si_activity_moderate_target_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsSmartInsulinActivityHeavyTargetMmol,    title = R.string.si_activity_heavy_target_title))
        }
    }
}