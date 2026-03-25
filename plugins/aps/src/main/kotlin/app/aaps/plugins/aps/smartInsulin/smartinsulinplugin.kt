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
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.interfaces.sharedPreferences.SP
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
    var minBgDuringLow: Double = Double.MAX_VALUE // lowest BG seen during current low event (mg/dL)
    var iobAtLowTime: Double = 0.0               // IOB when BG first crossed lowGuard
    var shortAvgDeltaAtLow: Double = 0.0         // shortAvgDelta (mmol) when BG first crossed lowGuard
    var secondLowOccurred: Boolean = false        // true if BG went low a second time — full lockout
    var softLandingBypass: Boolean = false        // true if soft landing — UAM allowed during rebound
    var uamEntrySmbsDelivered: Int = 0             // SMBs delivered since current UAM mode activated
    var uamEntryModeStartMs: Long = 0L             // timestamp when current UAM mode started
    var learningDirtyUntilMs: Long = 0L          // learning suppressed until this time after mode ends
    var previousMealModeForLockout: MealMode = MealMode.FASTING  // tracks transitions
    private var lockoutTrackerInitialized: Boolean = false        // prevents fake transition on first loop
    var reboundWindowStartMs: Long = 0L          // set ONLY when BG crosses back above lowGuard — NOT during suspend
    val msSinceLastSuspend: Long get() = if (reboundWindowStartMs > 0L) System.currentTimeMillis() - reboundWindowStartMs else Long.MAX_VALUE
    val inReboundWindow: Boolean get() = reboundWindowStartMs > 0L &&
        bgWentLow &&
        msSinceLastSuspend < REBOUND_GUARD_MS

    // ── PB2 gate snapshot — updated each invoke() for fragment display ────────
    @Volatile var pb2LastBgMgdl:            Double = 0.0
    @Volatile var pb2LastDeltaMgdl:         Double = 0.0
    @Volatile var pb2LastShortAvgDeltaMgdl: Double = 0.0
    @Volatile var pb2LastIobU:              Double = 0.0
    @Volatile var pb2LastMaxIobU:           Double = 0.0
    @Volatile var pb2ProfileTargetMgdl:     Double = 0.0

    companion object {
        const val REBOUND_GUARD_MS      = 60 * 60 * 1000L
        const val SMB_DELIVERY_FRACTION = 0.5
    }

    // ── Unit-aware display helpers ────────────────────────────────────────────
    // Internal BG values are always mg/dL; deltas from glucoseStatus are mg/dL.
    // shortAvgDeltaAtLow is stored in mmol (converted at capture site).
    // Use these for all user-visible strings.
    private val isMmol: Boolean get() =
        profileFunction.getUnits() == GlucoseUnit.MMOL
    private val unitLabel: String get() = if (isMmol) "mmol" else "mg/dL"
    /** Format a BG value in mg/dL to user units */
    private fun fmtBg(mgdl: Double): String =
        if (isMmol) String.format("%.1f", mgdl / 18.0)
        else        String.format("%.0f", mgdl)
    /** Format a delta value (internal mmol) to user units */
    private fun fmtDelta(mmol: Double): String =
        if (isMmol) String.format("%+.2f", mmol)
        else        String.format("%+.1f", mmol * 18.0)
    /** Format an ISF value in mg/dL to user units */
    private fun fmtIsf(mgdl: Double): String =
        if (isMmol) String.format("%.1f", mgdl / 18.0)
        else        String.format("%.0f", mgdl)

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
        bgWentLow            = false
        reboundWindowStartMs = 0L
        learningDirtyUntilMs = 0L
        previousMealModeForLockout = MealMode.FASTING
        minBgDuringLow       = Double.MAX_VALUE
        iobAtLowTime         = 0.0
        shortAvgDeltaAtLow   = 0.0
        secondLowOccurred    = false
        softLandingBypass        = false
        uamEntrySmbsDelivered    = 0
        uamEntryModeStartMs      = 0L
        aapsLogger.debug(LTag.APS, "SmartInsulinPlugin: all learners reset")
    }

    fun resetAggression() {
        aggressionLearner.reset()
        aggressionLearner.recalculate()
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

            // ── STFT / UAM debug ──────────────────────────────────────────────
            appendLine()
            appendLine("── STFT / UAM ────────────────────────")
            // STFT
            val stftStatus = stftController.statusString(profileFunction.getProfile()?.getTargetMgdl() ?: (5.5 * 18.0))
            if (stftStatus != null) appendLine("  $stftStatus") else appendLine("  STFT: inactive")
            // UAM status
            val uamStatus = uamController.statusString()
            if (uamStatus != null) appendLine("  $uamStatus") else appendLine("  UAM: idle")
            // UAM thresholds + last reject
            appendLine(uamController.debugSummary())
            // Post-meal lockout
            val nowSi = System.currentTimeMillis()
            if (learningDirtyUntilMs > 0L && nowSi < learningDirtyUntilMs) {
                val minsLeft = (learningDirtyUntilMs - nowSi) / 60_000
                appendLine("  Post-meal lockout: ${minsLeft}min left — UAM↑ thresholds ON")
            } else {
                appendLine("  Post-meal lockout: none")
            }
            // Safety state
            if (inReboundWindow) {
                val bypassNote = if (softLandingBypass) " — SOFT LANDING BYPASS ACTIVE (UAM allowed)" else " — full lockout"
                appendLine("  ⚠ Rebound: ${msSinceLastSuspend / 60_000}min elapsed$bypassNote")
            }
            if (bgWentLow && !inReboundWindow) appendLine("  ⚠ Recent low: watching for recovery")
            if (bgWentLow && minBgDuringLow < Double.MAX_VALUE) {
                val iob = iobAtLowTime
                appendLine("  Low detail: minBG=${fmtBg(minBgDuringLow)}$unitLabel " +
                               "velAtLow=${fmtDelta(shortAvgDeltaAtLow)}$unitLabel " +
                               "iobAtLow=${String.format("%.2f", iob)}U " +
                               if (secondLowOccurred) "⚠ SECOND LOW — full lockout" else "")
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

    // ── Structured data for fragment cards ───────────────────────────────────

    data class Pb2GateData(
        val bgMgdl:            Double,
        val deltaMgdl:         Double,
        val shortAvgDeltaMgdl: Double,
        val iobU:              Double,
        val maxIobU:           Double,
        val profileTargetMgdl: Double,
        val isMmol:            Boolean
    )

    data class FragmentData(
        val hour:               Int,
        val dayLabel:           String,
        val mealMode:           String,
        val modeRemMins:        Int?,
        val aggressiveness:     Double,
        val circCeil:           Double,
        val isfMultiplier:      Double,
        val profileIsfMgdl:     Double,
        val finalIsfMgdl:       Double,
        val basalMultiplier:    Double,
        val profileBasalU:      Double,
        val finalBasalU:        Double,
        val inReboundWindow:    Boolean,
        val reboundMins:        Long,
        val softLandingBypass:  Boolean,
        val bgWentLow:          Boolean,
        val secondLowOccurred:  Boolean,
        val minBgDuringLow:     Double,
        val iobAtLowTime:       Double,
        val pb2Status:          String,
        val isMmol:             Boolean,
        val learningState:      String,
        val activityLevel:      String,
        val avgHrBpm:           Int,
        val steps5min:          Int,
        val restingHrBpm:       Double,     // 0.0 = not configured, uses absolute thresholds
        val postMealLockoutMins: Long,
        val cgmWarmup:          Boolean,
        val stftStatus:         String?,
        val stftActive:         Boolean,
        val uamStatusLine:      String?,
        val uamDebug:           String,
        val circadianRawStatus: String,
        val profilesRawStatus:  String,
        val tirRawLine:         String,
        val pb2GateData:        Pb2GateData?,
        val activeDoseU:        Double?
    )

    fun fragmentData(): FragmentData {
        val cal     = java.util.Calendar.getInstance()
        val hour    = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val dow     = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1
        val day     = DayOfWeekCircadianState.DAY_LABELS[dow.coerceIn(0, 6)]
        val profile = profileFunction.getProfile()
        val profileIsf   = profile?.getIsfMgdl("SmartInsulinPlugin") ?: 0.0
        val profileBasal = profile?.getBasal() ?: 0.0
        val isfMult      = circadianLearner.isfMultiplier(hour)
        val basalMult    = basalLearner.multiplierClamped * circadianLearner.basalMultiplier(hour)
        val activeMode   = mealOverrideManager.activeMealMode
        val nowMs        = System.currentTimeMillis()

        val tbrStep           = activePlugin.activePump.pumpDescription.tempAbsoluteStep.takeIf { it > 0.0 } ?: 0.05
        val rawFinalBasal     = profileBasal * basalMult
        val roundedFinalBasal = Math.round(rawFinalBasal / tbrStep) * tbrStep

        val circRaw = buildString {
            for (h in 0..23) {
                val marker = if (h == hour) "▶" else " "
                appendLine("$marker ${h.toString().padStart(2)}  " +
                               "${"%.3f".format(circadianLearner.isfMultiplier(h))}  " +
                               "${"%.3f".format(circadianLearner.basalMultiplier(h))}  " +
                               "${"%.3f".format(circadianLearner.aggrCeiling(h))}  " +
                               "${"%.0f".format(circadianLearner.confidencePct(h))}%")
            }
        }

        val profRaw = buildString {
            app.aaps.core.interfaces.smartInsulin.MealMode.entries.forEach { mode ->
                val p = profileLearner.getProfile(mode)
                appendLine("${mode.label.padEnd(16)}: peak=${p.peakMinutes.toInt()}m  dia=${p.diaMinutes.toInt()}m  n=${p.sampleCount}")
            }
        }

        val postMealLeft = if (learningDirtyUntilMs > 0L && nowMs < learningDirtyUntilMs)
            (learningDirtyUntilMs - nowMs) / 60_000L else 0L

        return FragmentData(
            hour               = hour,
            dayLabel           = day,
            mealMode           = activeMode?.label ?: "Fasting",
            modeRemMins        = if (activeMode != null) (mealOverrideManager.modeTimeRemainingMs / 60_000).toInt() else null,
            aggressiveness     = aggressionLearner.aggressiveness.coerceAtMost(circadianLearner.aggrCeiling(hour)),
            circCeil           = circadianLearner.aggrCeiling(hour),
            isfMultiplier      = isfMult,
            profileIsfMgdl     = profileIsf,
            finalIsfMgdl       = profileIsf / isfMult,
            basalMultiplier    = basalMult,
            profileBasalU      = profileBasal,
            finalBasalU        = roundedFinalBasal,
            inReboundWindow    = inReboundWindow,
            reboundMins        = msSinceLastSuspend / 60_000,
            softLandingBypass  = softLandingBypass,
            bgWentLow          = bgWentLow,
            secondLowOccurred  = secondLowOccurred,
            minBgDuringLow     = minBgDuringLow,
            iobAtLowTime       = iobAtLowTime,
            pb2Status          = cachedOverviewState.pb2Line ?: "",
            isMmol             = profileFunction.getUnits() == app.aaps.core.data.model.GlucoseUnit.MMOL,
            learningState      = cachedOverviewState.learningState,
            activityLevel      = activityMonitor.level.label,
            avgHrBpm           = activityMonitor.avgHrBpm.toInt(),
            steps5min          = activityMonitor.lastSteps5min,
            restingHrBpm       = preferences.get(DoubleKey.ApsSmartInsulinRestingHrBpm),
            postMealLockoutMins = postMealLeft,
            cgmWarmup          = cachedOverviewState.learningState.contains("CGM"),
            stftStatus         = stftController.statusString(profile?.getTargetMgdl() ?: (5.5 * 18.0)),
            stftActive         = stftController.isActive,
            uamStatusLine      = uamController.statusString(),
            uamDebug           = uamController.debugSummary(),
            circadianRawStatus = circRaw,
            profilesRawStatus  = profRaw,
            tirRawLine         = aggressionLearner.tirSummary,
            pb2GateData        = if (mealOverrideManager.preBolus2Pending) Pb2GateData(
                bgMgdl            = pb2LastBgMgdl,
                deltaMgdl         = pb2LastDeltaMgdl,
                shortAvgDeltaMgdl = pb2LastShortAvgDeltaMgdl,
                iobU              = pb2LastIobU,
                maxIobU           = pb2LastMaxIobU,
                profileTargetMgdl = pb2ProfileTargetMgdl,
                isMmol            = profileFunction.getUnits() == app.aaps.core.data.model.GlucoseUnit.MMOL
            ) else null,
            activeDoseU        = mealOverrideManager.activeDoseU
        )
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
        // Restore persisted learningDirtyUntilMs so lockout survives app restart
        learningDirtyUntilMs = preferences.get(StringKey.ApsSmartInsulinLearningDirtyUntil).toLongOrNull() ?: 0L
        if (learningDirtyUntilMs > 0L)
            aapsLogger.debug(LTag.APS, "SmartInsulinPlugin: restored learningDirtyUntilMs=$learningDirtyUntilMs")
        aapsLogger.debug(LTag.APS, "SmartInsulinPlugin: onStart")
    }

    override fun onStop() {
        super.onStop()
        aapsLogger.debug(LTag.APS, "SmartInsulinPlugin: onStop")
    }

    /**
     * Read a UnitDoubleKey value from SharedPreferences, handling both storage formats:
     *  - New format (after AdaptiveUnitPreference fix): stored as mg/dL float via sp.putDouble
     *  - Old format (before fix): stored as mmol display value float (e.g. 5.0 for 5.0 mmol)
     *
     * Detection: if raw value < threshold (20.0 for BG keys, 36.0 for ISF keys), it was
     * stored in the old mmol format and needs ×18 to convert to mg/dL.
     * Once AdaptiveUnitPreference has written the correct mg/dL value, raw will be ≥ threshold
     * and no conversion is needed.
     *
     * This makes the plugin robust to the stored format — old values work correctly AND
     * newly saved values work correctly, with no need to re-enter settings.
     */
    private fun spMgdl(key: UnitDoubleKey, mmolThreshold: Double = 20.0): Double {
        val raw = sp.getDouble(key.key, key.defaultValue)
        return if (raw < mmolThreshold) raw * 18.0 else raw
    }

    /**
     * Returns the P/F ISF for a given hour, respecting day/night windows.
     * Day window is checked first, then night window, then fallback ISF.
     * 0.0 in day/night ISF means "skip this window, try next".
     * Fallback 0.0 means "use profile ISF" (handled by dosingIsfMgdl logic).
     * Both windows support midnight crossing (start > end).
     */
    private fun pfIsfMgdl(hour: Int): Double {
        val dayStart   = preferences.get(IntKey.ApsSmartInsulinUamProteinFatDayStartHour)
        val dayEnd     = preferences.get(IntKey.ApsSmartInsulinUamProteinFatDayEndHour)
        val nightStart = preferences.get(IntKey.ApsSmartInsulinUamProteinFatNightStartHour)
        val nightEnd   = preferences.get(IntKey.ApsSmartInsulinUamProteinFatNightEndHour)
        // Inclusive end hour — dayEnd=17 means 17:xx is still in the day window.
        // Supports midnight crossing (start > end).
        val inDay   = if (dayStart   <= dayEnd)   hour in dayStart..dayEnd
        else hour >= dayStart   || hour <= dayEnd
        val inNight = if (nightStart <= nightEnd) hour in nightStart..nightEnd
        else hour >= nightStart || hour <= nightEnd
        val dayIsf   = sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamProteinFatDayIsf.key,   UnitDoubleKey.ApsSmartInsulinUamProteinFatDayIsf.defaultValue)
        val nightIsf = sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamProteinFatNightIsf.key, UnitDoubleKey.ApsSmartInsulinUamProteinFatNightIsf.defaultValue)
        val fallback = sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamProteinFatIsf.key,      UnitDoubleKey.ApsSmartInsulinUamProteinFatIsf.defaultValue)
        return when {
            inDay   && dayIsf   > 0.0 -> dayIsf
            inNight && nightIsf > 0.0 -> nightIsf
            else                      -> fallback
        }
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
        var mealMode = MealModeDetector.detect(overrideManager = mealOverrideManager)

        // ── UAM entry SMB fraction tracking ──────────────────────────────────
        // For the first N SMBs after a UAM meal mode fires, apply a reduced delivery
        // fraction. Softens the front-end of the UAM response to avoid overcorrection
        // stacking before existing IOB has had time to affect predictions.
        // P/F excluded — it's a tail correction, not a meal entry event.
        var currentModeIsUam = mealMode.isUam && mealMode != MealMode.UAM_PROTEIN_FAT
        if (currentModeIsUam && uamEntryModeStartMs == 0L) {
            uamEntryModeStartMs   = now
            uamEntrySmbsDelivered = 0
            aapsLogger.debug(LTag.APS, "SmartInsulin: UAM entry tracking started for ${mealMode.label}")
        } else if (!currentModeIsUam) {
            uamEntryModeStartMs   = 0L
            uamEntrySmbsDelivered = 0
        }
        val entrySmbCount    = preferences.get(IntKey.ApsSmartInsulinUamEntrySmbCount)
        val entrySmbFraction = preferences.get(DoubleKey.ApsSmartInsulinUamEntrySmbFraction)
        var uamSmbFraction   = if (currentModeIsUam && uamEntrySmbsDelivered < entrySmbCount)
            entrySmbFraction else SMB_DELIVERY_FRACTION

        // ── Post-meal learning lockout ───────────────────────────────────────
        // When any meal or UAM mode expires (transition back to FASTING), mark BG data
        // as "dirty for learning" for a configurable window. Fat/protein tails and carb
        // residuals won't corrupt basal/ISF/aggressiveness learning.
        // UAM detection is completely unaffected — it runs independently of this flag.
        // Initialize tracker to current mode on first loop — prevents fake transition at startup
        if (!lockoutTrackerInitialized) {
            previousMealModeForLockout = mealMode
            lockoutTrackerInitialized = true
        }

        // P/F is a tail correction, not a real meal — don't trigger post-meal dirty window.
        // UAM meal modes should still fire normally after P/F expires.
        val previousWasRealMeal = previousMealModeForLockout != MealMode.FASTING &&
            previousMealModeForLockout != MealMode.UAM_PROTEIN_FAT
        if (previousWasRealMeal && mealMode == MealMode.FASTING) {
            val lockoutMins = preferences.get(IntKey.ApsSmartInsulinPostModeLockoutMins)
            if (lockoutMins > 0) {
                learningDirtyUntilMs = maxOf(learningDirtyUntilMs, now + lockoutMins * 60_000L)
                preferences.put(StringKey.ApsSmartInsulinLearningDirtyUntil, learningDirtyUntilMs.toString())
                aapsLogger.debug(LTag.APS,
                                 "SmartInsulin: ${previousMealModeForLockout.label} ended — " +
                                     "learning dirty for ${lockoutMins}min (until ${learningDirtyUntilMs})")
            }
        }
        previousMealModeForLockout = mealMode
        val timeSinceLastMealMs = if (learningDirtyUntilMs > 0L) learningDirtyUntilMs - now else 0L
        // Clear persisted dirty flag once window has passed
        if (learningDirtyUntilMs > 0L && now >= learningDirtyUntilMs) {
            learningDirtyUntilMs = 0L
            preferences.put(StringKey.ApsSmartInsulinLearningDirtyUntil, "0")
        }
        val inPostMealLockout = mealMode == MealMode.FASTING && now < learningDirtyUntilMs

        // ISF overrides: correctly stored as mg/dL by sp.putDouble — use sp.getDouble directly.
        // Do NOT use spMgdl() here — ISF values are already in mg/dL (e.g. 12.6), not mmol.
        // spMgdl would incorrectly multiply by 18 since 12.6 < 36.
        val modeIsfMgdl = when (mealMode) {
            MealMode.BREAKFAST     -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinBreakfastIsf.key,     UnitDoubleKey.ApsSmartInsulinBreakfastIsf.defaultValue)
            MealMode.LUNCH         -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinLunchIsf.key,         UnitDoubleKey.ApsSmartInsulinLunchIsf.defaultValue)
            MealMode.DINNER        -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinDinnerIsf.key,        UnitDoubleKey.ApsSmartInsulinDinnerIsf.defaultValue)
            MealMode.LOW_CARB      -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinLowCarbIsf.key,       UnitDoubleKey.ApsSmartInsulinLowCarbIsf.defaultValue)
            MealMode.EXTENDED      -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinExtendedIsf.key,      UnitDoubleKey.ApsSmartInsulinExtendedIsf.defaultValue)
            MealMode.UAM_BREAKFAST -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamBreakfastIsf.key,  UnitDoubleKey.ApsSmartInsulinUamBreakfastIsf.defaultValue)
            MealMode.UAM_LUNCH     -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamLunchIsf.key,      UnitDoubleKey.ApsSmartInsulinUamLunchIsf.defaultValue)
            MealMode.UAM_DINNER    -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamDinnerIsf.key,     UnitDoubleKey.ApsSmartInsulinUamDinnerIsf.defaultValue)
            MealMode.UAM_SNACK     -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamSnackIsf.key,      UnitDoubleKey.ApsSmartInsulinUamSnackIsf.defaultValue)
            MealMode.UAM_AFTERNOON -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamAfternoonIsf.key,  UnitDoubleKey.ApsSmartInsulinUamAfternoonIsf.defaultValue)
            MealMode.UAM_PROTEIN_FAT -> pfIsfMgdl(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
            MealMode.FASTING       -> 0.0
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
        var dosingIsfMgdl = when {
            modeIsfMgdl > 0.0 -> modeIsfMgdl                    // user meal-mode override — already mg/dL
            else              -> trueIsfMgdl / circIsfMult        // divide: mult>1 → lower dosingISF → less insulin
        }

        // ── Tick the override manager — fires queued bolus when safe ──────────
        val pb2MaxIob = constraintsChecker.getMaxIOBAllowed().value()
        mealOverrideManager.onLoopCycle(
            glucoseStatus = glucoseStatus,
            iobArray      = iobArray,
            maxIobU       = pb2MaxIob
        )
        pb2LastBgMgdl            = glucoseStatus.glucose
        pb2LastDeltaMgdl         = glucoseStatus.delta
        pb2LastShortAvgDeltaMgdl = glucoseStatus.shortAvgDelta
        pb2LastIobU              = iobArray.firstOrNull()?.iob ?: 0.0
        pb2LastMaxIobU           = pb2MaxIob
        pb2ProfileTargetMgdl     = profile.getTargetMgdl()

        // ── STFT: short-term target reduction for stuck-high fasting BG ──────
        // Only runs in fasting, never overrides a deliberate temp target.
        // Adjusts targetBg downward to make the loop naturally more aggressive
        // without touching ISF, basal, aggressiveness, or any learners.
        // STFT runs every cycle — it handles meal mode and temp target suppression internally.
        // When a temp target is active we still call it so it can reset cleanly, but we
        // discard the adjusted value and keep the user's deliberate temp target.
        val profileTargetMgdl = profile.getTargetMgdl()
        val highTempTarget    = isTempTarget && targetBg > profileTargetMgdl

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
        val cgmInWarmup = cgmState.inWarmup

        val stftAdjusted = stftController.onLoopCycle(
            profileTargetMgdl = profileTargetMgdl,
            currentBgMgdl     = glucoseStatus.glucose,
            delta             = glucoseStatus.delta,
            mealMode          = mealMode,
            isTempTarget      = isTempTarget,
            bgWentLow         = bgWentLow,
            inReboundWindow   = inReboundWindow,
            cgmInWarmup       = cgmInWarmup,
            bgTimestampMs     = glucoseStatus.date
        )
        // STFT now handles TT/low internally and returns profileTargetMgdl when blocked.
        // The plugin-side TT guard is kept as a safety backstop.
        val stftTargetMgdl = if (!isTempTarget) stftAdjusted else targetBg

        // ── UAM: auto-detect unannounced meals from BG rise during fasting ────
        // Only fires in FASTING mode within configured time windows.
        // Expiry detection is handled internally by UamController via previousMealMode tracking.
        // Safety inputs (bgWentLow, inReboundWindow) prevent false triggers from rebound rises.
        val uamCurrentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        // Use reboundWindowStartMs as proxy for lastLowTimeMs — it's set when BG recovers above
        // lowGuard, so it slightly underestimates time since low (conservative = safe).
        val uamLastLowTimeMs = if (bgWentLow) reboundWindowStartMs else 0L
        // BGI = expected BG change from insulin activity alone (mg/dL per 5 min, converted to mmol)
        // Negative = insulin pulling BG down. Used by UAM to detect rises beyond insulin prediction.
        val uamBgiMmol = -((iobArray.firstOrNull()?.activity ?: 0.0) * dosingIsfMgdl * 5.0) / 18.0
        uamController.onLoopCycle(
            currentMealMode    = mealMode,
            currentBgMmol      = glucoseStatus.glucose / 18.0,
            deltaMmol          = glucoseStatus.delta / 18.0,
            shortAvgDeltaMmol  = glucoseStatus.shortAvgDelta / 18.0,
            bgiMmol            = uamBgiMmol,
            currentHour        = uamCurrentHour,
            bgWentLow          = bgWentLow,
            inReboundWindow    = inReboundWindow,
            lastLowTimeMs      = uamLastLowTimeMs,
            highTempTarget     = highTempTarget,
            cgmInWarmup        = cgmInWarmup,
            inPostMealLockout  = inPostMealLockout,
            profileTargetMmol  = profileTargetMgdl / 18.0,
            softLandingBypass  = softLandingBypass,
            bgTimestampMs      = glucoseStatus.date
        )

        // ── Re-read mealMode after UAM — reassign mealMode and dosingIsfMgdl if UAM fired ──
        // Using var reassignment so ALL downstream logic (determine_basal, learners, logging)
        // sees the correct mode and ISF immediately. The previous approach only updated sens
        // in OapsProfile but left dosingIsfMgdl stale everywhere else.
        val latestMealMode = mealOverrideManager.activeMealMode ?: MealMode.FASTING
        if (latestMealMode != mealMode) {
            val latestModeIsfMgdl = run {
                val unitVal = when (latestMealMode) {
                    MealMode.BREAKFAST     -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinBreakfastIsf.key,     UnitDoubleKey.ApsSmartInsulinBreakfastIsf.defaultValue)
                    MealMode.LUNCH         -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinLunchIsf.key,         UnitDoubleKey.ApsSmartInsulinLunchIsf.defaultValue)
                    MealMode.DINNER        -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinDinnerIsf.key,        UnitDoubleKey.ApsSmartInsulinDinnerIsf.defaultValue)
                    MealMode.LOW_CARB      -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinLowCarbIsf.key,       UnitDoubleKey.ApsSmartInsulinLowCarbIsf.defaultValue)
                    MealMode.EXTENDED      -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinExtendedIsf.key,      UnitDoubleKey.ApsSmartInsulinExtendedIsf.defaultValue)
                    MealMode.UAM_BREAKFAST -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamBreakfastIsf.key,  UnitDoubleKey.ApsSmartInsulinUamBreakfastIsf.defaultValue)
                    MealMode.UAM_LUNCH     -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamLunchIsf.key,      UnitDoubleKey.ApsSmartInsulinUamLunchIsf.defaultValue)
                    MealMode.UAM_DINNER    -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamDinnerIsf.key,     UnitDoubleKey.ApsSmartInsulinUamDinnerIsf.defaultValue)
                    MealMode.UAM_SNACK     -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamSnackIsf.key,      UnitDoubleKey.ApsSmartInsulinUamSnackIsf.defaultValue)
                    MealMode.UAM_AFTERNOON -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamAfternoonIsf.key,  UnitDoubleKey.ApsSmartInsulinUamAfternoonIsf.defaultValue)
                    MealMode.UAM_PROTEIN_FAT -> pfIsfMgdl(uamCurrentHour)
                    MealMode.FASTING       -> 0.0
                }
                if (unitVal == 0.0) 0.0 else unitVal
            }
            mealMode = latestMealMode
            if (latestModeIsfMgdl > 0.0) {
                dosingIsfMgdl = latestModeIsfMgdl
            }
            // Re-evaluate UAM entry tracking now that mealMode is correct for this cycle
            currentModeIsUam = mealMode.isUam && mealMode != MealMode.UAM_PROTEIN_FAT
            if (currentModeIsUam && uamEntryModeStartMs == 0L) {
                uamEntryModeStartMs   = now
                uamEntrySmbsDelivered = 0
                aapsLogger.debug(LTag.APS, "SmartInsulin: UAM entry tracking armed (same-cycle fire) for ${mealMode.label}")
            }
            // Recompute fraction — first-cycle SMBs should be reduced even when UAM fires this cycle
            uamSmbFraction = if (currentModeIsUam && uamEntrySmbsDelivered < entrySmbCount)
                entrySmbFraction else SMB_DELIVERY_FRACTION
            aapsLogger.debug(LTag.APS,
                             "SmartInsulin: UAM fired this cycle — using ${mealMode.label} ISF " +
                                 "${fmtIsf(dosingIsfMgdl)}$unitLabel immediately")
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
            sens                            = dosingIsfMgdl,  // reassigned above if UAM fired this cycle
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
            MealMode.UAM_PROTEIN_FAT  -> MealMode.LOW_CARB
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

        // Suppress learning during CGM warmup — noisy readings corrupt all learned models
        // CGM warmup: suppress ISF/basal/TIR adaptive learning but keep rollercoaster protection
        // Activity: suppress all learning (BG changes are exercise-driven, not insulin-driven)
        val suppressAdaptiveLearning = activityMonitor.suppressLearning || cgmState.suppressLearning || inPostMealLockout
        val suppressRollercoaster    = activityMonitor.suppressLearning  // activity only — not CGM warmup

        // Activity targets stored as mg/dL (9/18/27) — use sp.getDouble directly.
        // Do NOT use spMgdl() — these values are < 20 and would be wrongly multiplied by 18.
        val activityLightTarget    = sp.getDouble(UnitDoubleKey.ApsSmartInsulinActivityLightTarget.key,    UnitDoubleKey.ApsSmartInsulinActivityLightTarget.defaultValue)    / 18.0
        val activityModerateTarget = sp.getDouble(UnitDoubleKey.ApsSmartInsulinActivityModerateTarget.key, UnitDoubleKey.ApsSmartInsulinActivityModerateTarget.defaultValue) / 18.0
        val activityHeavyTarget    = sp.getDouble(UnitDoubleKey.ApsSmartInsulinActivityHeavyTarget.key,    UnitDoubleKey.ApsSmartInsulinActivityHeavyTarget.defaultValue)    / 18.0
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


        // ── Cache Overview state — updated here where all conditions are in scope ──
        // highTempTarget, mealMode, cgmState, activityMonitor all available now.
        val learningEnabledCache = preferences.get(BooleanKey.ApsSmartInsulinEnableLearning)
        val isMealMode = mealMode != MealMode.FASTING
        val learningStateStr = when {
            !learningEnabledCache                -> "off: Learning disabled"
            activityMonitor.suppressLearning     -> "off: Activity ${activityMonitor.level.label}"
            cgmState.suppressLearning            -> "off: CGM warmup"
            inPostMealLockout                    -> {
                val minsLeft = (learningDirtyUntilMs - now) / 60_000
                "off: Post-meal (${minsLeft}min left)"
            }
            highTempTarget                       -> "off: High temp target"
            isMealMode || mealMode.isUam         -> "limited"  // DIA/peak only — no basal/ISF learning
            else                                 -> "Learning"
        }
        val modeLineStr = mealOverrideManager.activeMealMode?.let { mode ->
            val mins = mealOverrideManager.modeTimeRemainingMs / 60_000
            if (mode.isUam) {
                // UAM modes: "Meal: UAM (Dinner) 25m", "Meal: UAM (Low Carb) 25m"
                val uamLabel = when (mode) {
                    MealMode.UAM_BREAKFAST -> "Breakfast"
                    MealMode.UAM_LUNCH     -> "Lunch"
                    MealMode.UAM_DINNER    -> "Dinner"
                    MealMode.UAM_SNACK     -> "Snack"
                    MealMode.UAM_PROTEIN_FAT  -> "Protein/Fat"
                    MealMode.UAM_AFTERNOON    -> "Afternoon"
                    else                   -> mode.label
                }
                "Meal: UAM ($uamLabel) ${mins}m"
            } else {
                // Manual modes: "Meal: Dinner 25m"
                "Meal: ${mode.label} ${mins}m"
            }
        } ?: "Meal: Fasting"
        val pb2LineStr = if (mealOverrideManager.preBolus2Pending) {
            val msRem = mealOverrideManager.preBolus2SecondsRemaining
            when {
                msRem != null && msRem > 0 -> "PB2 active: ${msRem / 60_000}m"
                else -> {
                    val bgOk    = pb2LastBgMgdl > pb2ProfileTargetMgdl
                    val iobOk   = pb2LastIobU < pb2LastMaxIobU * MealOverrideManager.MAX_IOB_HEADROOM_RATIO
                    val deltaOk = pb2LastDeltaMgdl >= MealOverrideManager.DELTA_INSTANT_BLOCK_MGDL
                    val shortOk = pb2LastShortAvgDeltaMgdl >= MealOverrideManager.SHORT_AVG_DELTA_BLOCK_MGDL
                    when {
                        !bgOk    -> "PB2: Below target"
                        !iobOk   -> "PB2: IOB too high"
                        !deltaOk -> "PB2: BG falling"
                        !shortOk -> "PB2: Trend falling"
                        else     -> "PB2: Waiting"
                    }
                }
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

        aapsLogger.debug(LTag.APS, "SmartInsulin mode=$mealMode modeISF=${if (modeIsfMgdl > 0.0) fmtIsf(modeIsfMgdl) + unitLabel else null} dosingISF=${fmtIsf(dosingIsfMgdl)}$unitLabel learnedProfile=$learnedProfile")

        // ── Rebound protection tracking ───────────────────────────────────────
        // Computed BEFORE determine_basal() so inReboundWindow is correct on the
        // exact cycle where BG first crosses back above the threshold.
        // Matches the lowGuardMmol threshold used in determine_basal's SUSPEND decision.
        val REBOUND_LOW_THRESHOLD_MGDL = spMgdl(UnitDoubleKey.ApsSmartInsulinLowGuard)
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
                // First crossing — capture conditions for soft landing evaluation
                iobAtLowTime       = iobArray.firstOrNull()?.iob ?: 0.0
                shortAvgDeltaAtLow = glucoseStatus.shortAvgDelta / 18.0
                aapsLogger.debug(LTag.APS,
                                 "SmartInsulin: BG went low (${fmtBg(currentBgMgdl)}$unitLabel) " +
                                     "iob=${String.format("%.2f", iobAtLowTime)}U " +
                                     "shortAvgΔ=${fmtDelta(shortAvgDeltaAtLow)}$unitLabel")
                if (mealMode.isUam) {
                    aapsLogger.debug(LTag.APS, "SmartInsulin: cancelling UAM mode ${mealMode.label} due to low BG")
                    mealOverrideManager.cancelOverride()
                }
            } else if (softLandingBypass && reboundWindowStartMs > 0L) {
                // BG went low again after bypass was active — second low, full lockout
                secondLowOccurred = true
                softLandingBypass = false
                aapsLogger.debug(LTag.APS, "SmartInsulin: second low — bypass revoked, full lockout")
            }
            if (currentBgMgdl < minBgDuringLow) minBgDuringLow = currentBgMgdl
            bgWentLow = true
            if (reboundWindowStartMs > 0L) {
                reboundWindowStartMs = 0L
                aapsLogger.debug(LTag.APS, "SmartInsulin: BG dropped below lowGuard during rebound window — resetting timer")
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
            bgWentLow            = false
            minBgDuringLow       = Double.MAX_VALUE
            secondLowOccurred    = false
            softLandingBypass    = false
            aapsLogger.debug(LTag.APS, "SmartInsulin: rebound window elapsed — clearing")
        }

        // ── Soft landing bypass ───────────────────────────────────────────────
        // During rebound, allow UAM detection if the low was borderline (not a genuine crash).
        // All 5 conditions must be met; if BG goes low again the bypass is revoked permanently.
        val lowGuardMmol = spMgdl(UnitDoubleKey.ApsSmartInsulinLowGuard) / 18.0
        val softLandingDepthMgdl     = (lowGuardMmol - 0.3) * 18.0  // 4.7 mmol if lowGuard=5.0
        val bypassHour               = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val bypassDayStart           = preferences.get(IntKey.ApsSmartInsulinUamDayStartHour)
        val bypassNightCutoff        = preferences.get(IntKey.ApsSmartInsulinUamNightCutoffHour)
        val inMealHoursForBypass     = if (bypassNightCutoff > bypassDayStart)
            bypassHour in bypassDayStart until bypassNightCutoff
        else
            bypassHour >= bypassDayStart || bypassHour < bypassNightCutoff

        softLandingBypass = bgWentLow &&
            !secondLowOccurred &&
            minBgDuringLow >= softLandingDepthMgdl &&
            shortAvgDeltaAtLow > -0.15 &&
            iobAtLowTime < 1.0 &&
            inMealHoursForBypass

        if (softLandingBypass) {
            aapsLogger.debug(LTag.APS,
                             "SmartInsulin: soft landing bypass ACTIVE — " +
                                 "minBG=${fmtBg(minBgDuringLow)}$unitLabel " +
                                 "(≥${fmtBg(softLandingDepthMgdl)}) " +
                                 "velAtLow=${fmtDelta(shortAvgDeltaAtLow)}$unitLabel (>-0.15) " +
                                 "iob=${String.format("%.2f", iobAtLowTime)}U (<1.0)")
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
            lowGuardMmol             = spMgdl(UnitDoubleKey.ApsSmartInsulinLowGuard)  / 18.0,
            warnGuardMmol            = spMgdl(UnitDoubleKey.ApsSmartInsulinWarnGuard) / 18.0,
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
            cgmWarmupReason          = cgmState.reason,
            uamSmbFraction           = uamSmbFraction,
            targetRespectEnabled     = true
        )

        // Increment UAM entry SMB counter if an SMB was delivered this cycle
        val fractionUsed = uamSmbFraction  // capture before increment
        if (currentModeIsUam && apsResult.smb > 0.0 && uamEntrySmbsDelivered < entrySmbCount) {
            uamEntrySmbsDelivered++
            aapsLogger.debug(LTag.APS,
                             "SmartInsulin: UAM entry SMB ${uamEntrySmbsDelivered}/$entrySmbCount " +
                                 "at ${(fractionUsed * 100).toInt()}% fraction")
        }
        // Only show UAMEntry when an SMB was actually delivered this cycle
        if (currentModeIsUam && apsResult.smb > 0.0 && uamEntrySmbsDelivered <= entrySmbCount && uamEntrySmbsDelivered > 0) {
            apsResult.reason += " | UAMEntry: SMB ${uamEntrySmbsDelivered}/$entrySmbCount @${(fractionUsed * 100).toInt()}%"
        }

        // Append STFT status to reason if active
        stftController.statusString(profileTargetMgdl)?.let { apsResult.reason += " | $it" }
        uamController.statusString()?.let  { apsResult.reason += " | $it" }
        // Post-meal lockout in loop output
        if (inPostMealLockout) {
            val minsLeft = (learningDirtyUntilMs - now) / 60_000
            apsResult.reason += " | postMeal: dirty(${minsLeft}min) UAM↑thresh"
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

        // ── BolusCurveTracker — meal modes only (peak/DIA learning from bolus curves)
        // This is intentionally NOT suppressed during high TT — a meal bolus during
        // a high TT is still a valid peak/DIA observation.
        if (learningEnabled) {
            bolusCurveTracker.onLoopCycle(glucoseStatus, mealMode, iobArray)
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
                val offsetStr = if (isMmol) "%.1f".format(activityTargetOffsetMmol)
                else        "%.0f".format(activityTargetOffsetMmol * 18.0)
                " | activity=${activityMonitor.level.label}(+$offsetStr$unitLabel" +
                    " hr=${activityMonitor.avgHrBpm.toInt()} steps=${activityMonitor.lastSteps5min}/5m)"
            }
        }
        // CGM warmup/block suffix
        val cgmSuffix = if (cgmState.reason.isNotEmpty()) " | ${cgmState.reason}" else ""

        // UKF first-day status tag
        val ukfFirstDaySuffix: String = run {
            val ukfSelected = activePlugin.activeSmoothing.javaClass.simpleName == "UnscentedKalmanFilterPlugin"
            val firstDayOn  = preferences.get(BooleanKey.ApsSmartInsulinFirstDayCgmSmoothing)
            if (ukfSelected && firstDayOn && sensorInsertTimeMs > 0L) {
                val remainingMs = 24L * 60 * 60 * 1000L - (now - sensorInsertTimeMs)
                if (remainingMs > 0L) {
                    val remainingH   = remainingMs / 3_600_000L
                    val remainingMin = (remainingMs % 3_600_000L) / 60_000L
                    " | UKF active ${remainingH}h${remainingMin}m left"
                } else ""
            } else ""
        }

        apsResult.reason += " | circ(ISF×${"%.2f".format(circIsfMult)} bas×${"%.2f".format(circBasalMult)} ceil=${"%.2f".format(circAggrCeil)})" +
            " basal×${"%.2f".format(basalMultiplier)}" +
            " aggr=${"%.2f".format(aggressiveness)}/${"%.2f".format(aggressionLearner.aggressiveness)}" +
            (if (inReboundWindow) " rebound=${msSinceLastSuspend / 60_000}min" else "") +
            activitySuffix +
            cgmSuffix +
            ukfFirstDaySuffix

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
            apsResult.reason += " | ${modeRemainingMins}min left"
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
            .put(DoubleKey.ApsSmartInsulinLearningRate, preferences)
            .put(UnitDoubleKey.ApsSmartInsulinLowGuard, preferences)
            .put(UnitDoubleKey.ApsSmartInsulinWarnGuard, preferences)

    override fun applyConfiguration(configuration: JSONObject) {
        configuration
            .store(BooleanKey.ApsSmartInsulinEnableLearning, preferences)
            .store(DoubleKey.ApsSmartInsulinLearningRate, preferences)
            .store(UnitDoubleKey.ApsSmartInsulinLowGuard, preferences)
            .store(UnitDoubleKey.ApsSmartInsulinWarnGuard, preferences)
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
        if (requiredKey != null && requiredKey !in listOf(
                "smart_insulin_settings", "si_screen_advanced",
                "si_screen_general", "si_screen_learning", "si_screen_dawn",
                "si_screen_activity", "si_screen_meal", "si_screen_stft",
                "si_screen_first_day_cgm", "si_screen_uam", "si_screen_uam_windows"
            )) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key   = "smart_insulin_settings"
            title = rh.gs(R.string.smart_insulin)
            initialExpandedChildrenCount = 0

            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmb, title = R.string.enable_smb))

            // ── General & Safety ──────────────────────────────────────────
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key   = "si_screen_general"
                title = "General & Safety"
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmb,                        title = R.string.enable_smb))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbAlways,                  title = R.string.enable_smb_always))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbWithCob,                 title = R.string.enable_smb_with_cob))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbAfterCarbs,              title = R.string.enable_smb_after_carbs))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmbMaxIob,                      title = R.string.openapssmb_max_iob_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsMaxBasal,                       title = R.string.openapsma_max_basal_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsMaxSmbFrequency,                   title = R.string.smb_interval_summary))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinMaxSmb,             title = R.string.si_max_smb_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinMaxTbr,             title = R.string.si_max_tbr_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinAggressionMax,      title = R.string.si_aggression_max_title))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinLowGuard,       title = R.string.smart_insulin_low_guard))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinWarnGuard,      title = R.string.smart_insulin_warn_guard))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsLgsThreshold, dialogMessage = R.string.lgs_threshold_summary, title = R.string.lgs_threshold_title))
            })

            // ── Learning ──────────────────────────────────────────────────
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key   = "si_screen_learning"
                title = "Learning"
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSmartInsulinEnableLearning,       title = R.string.smart_insulin_enable_learning))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinLearningRate,          title = R.string.smart_insulin_learning_rate))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSmartInsulinBasalLearningEnabled, title = R.string.si_basal_learning_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinPostModeLockoutMins,      title = R.string.si_post_mode_lockout_mins_title))
            })

            // ── Dawn Phenomenon ───────────────────────────────────────────
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key   = "si_screen_dawn"
                title = "Dawn Phenomenon"
                addPreference(AdaptiveIntPreference(   ctx = context, intKey    = IntKey.ApsSmartInsulinDawnWindowStartHour, title = R.string.si_dawn_start_hour_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey    = IntKey.ApsSmartInsulinDawnWindowEndHour,   title = R.string.si_dawn_end_hour_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsSmartInsulinDawnSmbReduction, title = R.string.si_dawn_smb_reduction_title))
            })

            // ── Activity ──────────────────────────────────────────────────
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key   = "si_screen_activity"
                title = "Activity"
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSmartInsulinActivityTargetEnabled,       title = R.string.si_activity_target_enabled_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinRestingHrBpm,                title = R.string.si_resting_hr_bpm_title))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinActivityLightTarget,      title = R.string.si_activity_light_target_title))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinActivityModerateTarget,   title = R.string.si_activity_moderate_target_title))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinActivityHeavyTarget,      title = R.string.si_activity_heavy_target_title))
            })

            // ── Meal Modes ────────────────────────────────────────────────
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key   = "si_screen_meal"
                title = "Meal Modes"
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinBreakfastIsf,        title = R.string.si_breakfast_isf_title))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinLunchIsf,            title = R.string.si_lunch_isf_title))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinDinnerIsf,           title = R.string.si_dinner_isf_title))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinLowCarbIsf,          title = R.string.si_lowcarb_isf_title))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinExtendedIsf,         title = R.string.si_extended_isf_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinBreakfastCarbsG,            title = R.string.si_breakfast_carbs_g_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinLunchCarbsG,                title = R.string.si_lunch_carbs_g_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinDinnerCarbsG,               title = R.string.si_dinner_carbs_g_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinModeWindowMins,             title = R.string.si_mode_window_mins_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinMaxPreBolus,             title = R.string.si_max_prebolus_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinPreBolus2DefaultU,       title = R.string.si_prebolus2_default_u_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinPreBolus2DefaultDelayMins,  title = R.string.si_prebolus2_default_delay_title))
            })

            // ── STFT ──────────────────────────────────────────────────────
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key   = "si_screen_stft"
                title = "STFT (Soft Target)"
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSmartInsulinStftCgmWarmupBlock, title = R.string.si_stft_cgm_warmup_block_title))
            })

            // ── First Day CGM ─────────────────────────────────────────────
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key   = "si_screen_first_day_cgm"
                title = "First Day CGM"
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSmartInsulinFirstDayCgmSmoothing, title = R.string.si_first_day_cgm_smoothing_title))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSmartInsulinCgmWarmupEnabled,     title = R.string.si_cgm_warmup_enabled_title))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSmartInsulinUamCgmWarmupBlock,    title = R.string.si_uam_cgm_warmup_block_title))
            })

            // ── UAM Auto-Detection ────────────────────────────────────────
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key   = "si_screen_uam"
                title = "UAM Auto-Detection"
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSmartInsulinUamEnabled,              title = R.string.si_uam_enabled_title))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinUamTriggerThreshold,  title = R.string.si_uam_trigger_threshold_title))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinUamRiseMinDelta,      title = R.string.si_uam_rise_min_delta_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamRiseConsecutiveReadings,  title = R.string.si_uam_rise_readings_title))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinUamBurstThreshold,   title = R.string.si_uam_burst_threshold_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamDayStartHour,            title = R.string.si_uam_day_start_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamNightCutoffHour,         title = R.string.si_uam_night_cutoff_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey  = DoubleKey.ApsSmartInsulinUamEntrySmbFraction,     title = R.string.si_uam_entry_smb_fraction_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamEntrySmbCount,           title = R.string.si_uam_entry_smb_count_title))
            })

            // ── UAM Windows ───────────────────────────────────────────────
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key   = "si_screen_uam_windows"
                title = "UAM Windows"
                // Breakfast
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSmartInsulinUamBreakfastEnabled,     title = R.string.si_uam_breakfast_enabled_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamBreakfastStartHour,       title = R.string.si_uam_breakfast_start_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamBreakfastEndHour,         title = R.string.si_uam_breakfast_end_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamBreakfastDurationMins,    title = R.string.si_uam_breakfast_duration_title))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinUamBreakfastIsf,      title = R.string.si_uam_breakfast_isf_title))
                // Lunch
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSmartInsulinUamLunchEnabled,         title = R.string.si_uam_lunch_enabled_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamLunchStartHour,           title = R.string.si_uam_lunch_start_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamLunchEndHour,             title = R.string.si_uam_lunch_end_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamLunchDurationMins,        title = R.string.si_uam_lunch_duration_title))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinUamLunchIsf,          title = R.string.si_uam_lunch_isf_title))
                // Afternoon
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSmartInsulinUamAfternoonEnabled,     title = R.string.si_uam_afternoon_enabled_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamAfternoonStartHour,       title = R.string.si_uam_afternoon_start_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamAfternoonEndHour,         title = R.string.si_uam_afternoon_end_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamAfternoonDurationMins,    title = R.string.si_uam_afternoon_duration_title))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinUamAfternoonIsf,      title = R.string.si_uam_afternoon_isf_title))
                // Dinner
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSmartInsulinUamDinnerEnabled,        title = R.string.si_uam_dinner_enabled_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamDinnerStartHour,          title = R.string.si_uam_dinner_start_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamDinnerEndHour,            title = R.string.si_uam_dinner_end_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamDinnerDurationMins,       title = R.string.si_uam_dinner_duration_title))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinUamDinnerIsf,         title = R.string.si_uam_dinner_isf_title))
                // Snack
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSmartInsulinUamSnackEnabled,         title = R.string.si_uam_snack_enabled_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamSnackStartHour,           title = R.string.si_uam_snack_start_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamSnackEndHour,             title = R.string.si_uam_snack_end_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamSnackDurationMins,        title = R.string.si_uam_snack_duration_title))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinUamSnackIsf,          title = R.string.si_uam_snack_isf_title))
                // Protein/Fat
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSmartInsulinUamProteinFatEnabled,         title = R.string.si_uam_proteinfat_enabled_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamProteinFatDurationMins,        title = R.string.si_uam_proteinfat_duration_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamProteinFatStuckReadings,       title = R.string.si_uam_proteinfat_stuck_readings_title))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinUamProteinFatThreshold,    title = R.string.si_uam_proteinfat_threshold_title))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinUamProteinFatIsf,          title = R.string.si_uam_proteinfat_isf_title))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinUamProteinFatDayIsf,       title = R.string.si_uam_proteinfat_day_isf_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamProteinFatDayStartHour,        title = R.string.si_uam_proteinfat_day_start_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamProteinFatDayEndHour,          title = R.string.si_uam_proteinfat_day_end_title))
                addPreference(AdaptiveUnitPreference(  ctx = context, unitKey    = UnitDoubleKey.ApsSmartInsulinUamProteinFatNightIsf,     title = R.string.si_uam_proteinfat_night_isf_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamProteinFatNightStartHour,      title = R.string.si_uam_proteinfat_night_start_title))
                addPreference(AdaptiveIntPreference(   ctx = context, intKey     = IntKey.ApsSmartInsulinUamProteinFatNightEndHour,        title = R.string.si_uam_proteinfat_night_end_title))
            })
        }
    }
}