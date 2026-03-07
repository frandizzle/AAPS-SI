package app.aaps.plugins.aps.smartInsulin

import android.content.Context
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.plugins.aps.OpenAPSFragment
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
open class SmartInsulinPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val rxBus: RxBus,
    private val config: Config,
    private val profileFunction: ProfileFunction,
    private val iobCobCalculator: IobCobCalculator,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val persistenceLayer: PersistenceLayer,
    private val hardLimits: HardLimits,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val determineBasalSmartInsulin: DetermineBasalSmartInsulin,
    private val profileLearner: ProfileLearner,
    private val bolusCurveTracker: BolusCurveTracker,
    private val apsResultProvider: Provider<APSResult>
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

    // ── APS interface ────────────────────────────────────────────────
    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.SMB
    override var lastAPSResult: APSResult? = null

    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
        aapsLogger.debug(LTag.APS, "SmartInsulin invoke from $initiator")
        lastAPSResult = null

        // ── Guard clauses ────────────────────────────────────────────
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

        // ── Hard limits ──────────────────────────────────────────────
        if (!hardLimits.checkHardLimits(
                profile.dia,
                app.aaps.core.ui.R.string.profile_dia,
                hardLimits.minDia(),
                hardLimits.maxDia()
            )
        ) return
        if (!hardLimits.checkHardLimits(
                profile.getIsfMgdl("SmartInsulinPlugin"),
                app.aaps.core.ui.R.string.profile_sensitivity_value,
                HardLimits.MIN_ISF,
                HardLimits.MAX_ISF
            )
        ) return

        // ── Gather data ──────────────────────────────────────────────
        val now = dateUtil.now()
        val isTempTarget = persistenceLayer.getTemporaryTargetActiveAt(now) != null

        // Matches the real IobCobCalculator.calculateIobArrayForSMB signature
        val autosensResult = AutosensResult()
        val iobArray = iobCobCalculator.calculateIobArrayForSMB(
            autosensResult,
            SMBDefaults.exercise_mode,
            SMBDefaults.half_basal_exercise_target,
            isTempTarget
        )
        val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()

        // ── Meal mode detection ──────────────────────────────────────
        val lowCarbThresholdG  = preferences.get(IntKey.ApsSmartInsulinLowCarbThresholdG)
        val lowCarbModeEnabled = preferences.get(BooleanKey.ApsSmartInsulinLowCarbMode)
        val mealMode = MealModeDetector.detect(
            mealData         = mealData,
            glucoseStatus    = glucoseStatus,
            lowCarbThreshold = lowCarbThresholdG,
            lowCarbEnabled   = lowCarbModeEnabled
        )
        aapsLogger.debug(LTag.APS, "SmartInsulin mealMode: $mealMode")

        // ── Learned insulin profile for this mode ────────────────────
        val learningEnabled = preferences.get(BooleanKey.ApsSmartInsulinEnableLearning)
        val learnedProfile  = profileLearner.getProfile(mealMode)
        aapsLogger.debug(
            LTag.APS,
            "SmartInsulin learnedProfile: peak=${learnedProfile.peakMinutes} " +
                "dia=${learnedProfile.diaMinutes} confidence=${learnedProfile.confidence}"
        )

        // ── Prediction horizon and guard thresholds ──────────────────
        val predictionHorizonMins = preferences.get(IntKey.ApsSmartInsulinPredictionHorizonMins)
        val lowGuardMmol          = preferences.get(DoubleKey.ApsSmartInsulinLowGuardMmol)
        val warnGuardMmol         = preferences.get(DoubleKey.ApsSmartInsulinWarnGuardMmol)

        // ── Run determine_basal ──────────────────────────────────────
        val resultJson = determineBasalSmartInsulin.determine_basal(
            glucoseStatus         = glucoseStatus,
            iobArray              = iobArray,
            mealData              = mealData,
            profile               = profile,
            learnedProfile        = learnedProfile,
            mealMode              = mealMode,
            predictionHorizonMins = predictionHorizonMins,
            lowGuardMmol          = lowGuardMmol,
            warnGuardMmol         = warnGuardMmol,
            currentTime           = now
        )

        // ── Wrap result ───────────────────────────────────────────────
        // apsResultProvider.get() returns a fresh APSResult each time (Provider, not singleton)
        val apsResult = apsResultProvider.get()
        apsResult.json          = resultJson
        apsResult.glucoseStatus = glucoseStatus
        apsResult.iobData       = iobArray
        apsResult.mealData      = mealData
        lastAPSResult           = apsResult
        lastAPSRun              = now
        aapsLogger.debug(LTag.APS, "SmartInsulin result: $resultJson")
        rxBus.send(EventAPSCalculationFinished())

        // ── Post-cycle learning update ────────────────────────────────
        if (learningEnabled) {
            bolusCurveTracker.onLoopCycle(glucoseStatus, mealMode, iobArray)
        }

        rxBus.send(EventOpenAPSUpdateGui())
    }

    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? =
        glucoseStatusProvider.getGlucoseStatusData(allowOldData)

    // ── APS.configuration() — export plugin-specific settings ────────
    override fun configuration(): JSONObject =
        JSONObject()
            .put(BooleanKey.ApsSmartInsulinEnableLearning, preferences)
            .put(BooleanKey.ApsSmartInsulinLowCarbMode, preferences)
            .put(IntKey.ApsSmartInsulinLowCarbThresholdG, preferences)
            .put(IntKey.ApsSmartInsulinPredictionHorizonMins, preferences)
            .put(DoubleKey.ApsSmartInsulinLearningRate, preferences)
            .put(DoubleKey.ApsSmartInsulinLowGuardMmol, preferences)
            .put(DoubleKey.ApsSmartInsulinWarnGuardMmol, preferences)

    // ── APS.applyConfiguration() — import plugin-specific settings ───
    override fun applyConfiguration(configuration: JSONObject) {
        configuration
            .store(BooleanKey.ApsSmartInsulinEnableLearning, preferences)
            .store(BooleanKey.ApsSmartInsulinLowCarbMode, preferences)
            .store(IntKey.ApsSmartInsulinLowCarbThresholdG, preferences)
            .store(IntKey.ApsSmartInsulinPredictionHorizonMins, preferences)
            .store(DoubleKey.ApsSmartInsulinLearningRate, preferences)
            .store(DoubleKey.ApsSmartInsulinLowGuardMmol, preferences)
            .store(DoubleKey.ApsSmartInsulinWarnGuardMmol, preferences)
    }

    // ── Constraints ───────────────────────────────────────────────────

    override fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (isEnabled()) {
            val maxIobPref = preferences.get(DoubleKey.ApsSmbMaxIob)
            maxIob.setIfSmaller(
                maxIobPref,
                rh.gs(R.string.limiting_iob, maxIobPref, rh.gs(R.string.maxvalueinpreferences)),
                this
            )
            maxIob.setIfSmaller(
                hardLimits.maxIobSMB(),
                rh.gs(R.string.limiting_iob, hardLimits.maxIobSMB(), rh.gs(R.string.hardlimit)),
                this
            )
        }
        return maxIob
    }

    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        if (isEnabled()) {
            val maxBasal = preferences.get(DoubleKey.ApsMaxBasal)
                .coerceAtLeast(profile.getMaxDailyBasal())
            absoluteRate.setIfSmaller(
                maxBasal,
                rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxBasal, rh.gs(R.string.maxvalueinpreferences)),
                this
            )
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

    // ── Preferences UI ───────────────────────────────────────────────

    override fun addPreferenceScreen(
        preferenceManager: PreferenceManager,
        parent: PreferenceScreen,
        context: Context,
        requiredKey: String?
    ) {
        if (requiredKey != null && requiredKey != "smart_insulin_settings") return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "smart_insulin_settings"
            title = rh.gs(R.string.smart_insulin)
            initialExpandedChildrenCount = 0

            // ── Core SMB — reusing standard AAPS keys ────────────────
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmb,                        title = R.string.enable_smb))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbAlways,                  title = R.string.enable_smb_always))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbWithCob,                 title = R.string.enable_smb_with_cob))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbAfterCarbs,              title = R.string.enable_smb_after_carbs))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseUam,                        title = R.string.enable_uam))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmbMaxIob,                      title = R.string.openapssmb_max_iob_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsMaxBasal,                       title = R.string.openapsma_max_basal_title))
            addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsMaxSmbFrequency,                   title = R.string.smb_interval_summary))
            addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsMaxMinutesOfBasalToLimitSmb,       title = R.string.smb_max_minutes_summary))

            // ── Meal mode — new SI keys ───────────────────────────────
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSmartInsulinLowCarbMode,       title = R.string.smart_insulin_low_carb_mode))
            addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinLowCarbThresholdG,     title = R.string.smart_insulin_low_carb_threshold))

            // ── Learning — new SI keys ────────────────────────────────
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSmartInsulinEnableLearning,    title = R.string.smart_insulin_enable_learning))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinLearningRate,       title = R.string.smart_insulin_learning_rate))

            // ── Prediction / anti-rebound — new SI keys ──────────────
            addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinPredictionHorizonMins, title = R.string.smart_insulin_prediction_horizon))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinLowGuardMmol,       title = R.string.smart_insulin_low_guard))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinWarnGuardMmol,      title = R.string.smart_insulin_warn_guard))
        }
    }
}