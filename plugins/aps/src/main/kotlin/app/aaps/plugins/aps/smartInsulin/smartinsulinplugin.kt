package app.aaps.plugins.aps.smartInsulin

import android.content.Context
import android.content.Intent
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
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.ui.compose.icons.IcPluginInsulin
import app.aaps.plugins.aps.smartInsulin.SmartInsulinScreen
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.ToolbarConfig
import androidx.compose.runtime.Composable
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.utils.MidnightUtils
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.getPassedDurationToTimeInMinutes
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.objects.extensions.target
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
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
    preferences: Preferences,
    private val sp: SP,
    private val constraintsChecker: ConstraintsChecker,
    private val activePlugin: ActivePlugin,
    private val dateUtil: DateUtil,
    private val determineBasalSmartInsulin: DetermineBasalSmartInsulin,
    private val stftController: StftController,
    private val uamController: UamController,
    private val profileLearner: ProfileLearner,
    private val bolusCurveTracker: BolusCurveTracker,
    private val mealPhaseTracker: MealPhaseTracker,
    private val mealPhaseProfileLearner: MealPhaseProfileLearner,
    private val aggressionLearner: AggressionLearner,
    private val basalLearner: BasalLearner,
    private val circadianLearner: CircadianLearner,
    private val activityMonitor:  ActivityMonitor,
    private val cgmWarmupGuard:   CgmWarmupGuard,
    private val aapsSchedulers:   app.aaps.core.interfaces.rx.AapsSchedulers,
    private val overviewData: OverviewData,
    private val ch: ConcentrationHelper
) : PluginBaseWithPreferences(
    PluginDescription()
        .mainType(PluginType.APS)
        .icon(IcPluginInsulin)
        .pluginName(R.string.smart_insulin)
        .shortName(R.string.smart_insulin_short)
        .preferencesVisibleInSimpleMode(false)
        .showInList { config.APS }
        .description(R.string.smart_insulin_description)
        .composeContent { plugin: app.aaps.core.interfaces.plugin.PluginBase ->
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
    aapsLogger, rh, preferences
), APS, PluginConstraints, app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview {

    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.SI
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
    // Session-start snapshots for nudge "was" display — captured on transition from
    // INACTIVE/PAUSED → any active state (ACTIVE_HIGH, ACTIVE_LOW, TRIM). Captures the
    // pre-nudge baseline so "was" reflects what the loop was delivering BEFORE the
    // current nudge started applying corrections. Uses full composite values so "was"
    // matches what the loop was actually delivering.
    // Previous implementation keyed on hour change, which clobbered the baseline at
    // hour boundaries during long-running nudges — producing stale/misleading "was" values.
    private var lastSeenNudgeState: String = "INACTIVE"
    private var nudgeDisplaySessionIsfMgdl: Double = 0.0   // full profileISF / isfMult at session start
    private var nudgeDisplaySessionBasalU: Double = 0.0    // full profileBasal * basalMult at session start
    // Cached profile values — updated each invoke() so fragmentData() can read without runBlocking
    @Volatile private var cachedProfileIsf: Double = 0.0
    @Volatile private var cachedLearningEnabled: Boolean = true
    @Volatile private var cachedCgmSuppressLearning: Boolean = false
    @Volatile private var cachedProfileBasal: Double = 0.0
    @Volatile private var cachedProfileTarget: Double = 99.0  // 5.5 mmol default
    // Cached sensor insert time — queried from DB at most once per SENSOR_CACHE_REFRESH_MS.
    // Sensor changes are infrequent (every 10–14 days); a 30-min cache eliminates a 30-day
    // DB scan on every 5-min loop cycle while still detecting a fresh sensor within 30 min.
    @Volatile private var cachedSensorInsertTimeMs: Long = 0L
    @Volatile private var sensorCacheRefreshedAtMs: Long = 0L
    // Cached HbA1c estimate — computed in invoke() (background thread) from suspend DB call
    @Volatile private var cachedHba1cAvgMgdl: Double = 0.0
    @Volatile private var cachedHba1cEstimate: Double = 0.0
    @Volatile private var cachedHba1cWindowHours: Int = 0
    // Timestamp of last HbA1c DB query — refreshed at most once per HBA1C_CACHE_REFRESH_MS.
    // Today's CGM readings change slowly; querying all of them every 5-min cycle is wasteful.
    @Volatile private var cachedHba1cRefreshedAtMs: Long = 0L
    var previousMealModeForLockout: MealMode = MealMode.FASTING  // tracks transitions
    private var lockoutTrackerInitialized: Boolean = false        // prevents fake transition on first loop
    var reboundWindowStartMs: Long = 0L          // set ONLY when BG crosses back above lowGuard — NOT during suspend
    @Volatile var reboundGuardMs: Long = REBOUND_GUARD_MS  // updated each invoke() from preferences
    val msSinceLastSuspend: Long get() = if (reboundWindowStartMs > 0L) System.currentTimeMillis() - reboundWindowStartMs else Long.MAX_VALUE
    val inReboundWindow: Boolean get() = reboundWindowStartMs > 0L &&
        bgWentLow &&
        msSinceLastSuspend < reboundGuardMs

    // ── PB2 gate snapshot — updated each invoke() for fragment display ────────
    @Volatile var pb2LastBgMgdl:            Double = 0.0
    @Volatile var pb2LastDeltaMgdl:         Double = 0.0
    @Volatile var pb2LastShortAvgDeltaMgdl: Double = 0.0
    @Volatile var pb2LastIobU:              Double = 0.0
    @Volatile var pb2LastMaxIobU:           Double = 0.0
    @Volatile var pb2ProfileTargetMgdl:     Double = 0.0
    // ── PB3 gate snapshot — identical fields, updated separately each invoke() ─
    @Volatile var pb3LastBgMgdl:            Double = 0.0
    @Volatile var pb3LastDeltaMgdl:         Double = 0.0
    @Volatile var pb3LastShortAvgDeltaMgdl: Double = 0.0
    @Volatile var pb3LastIobU:              Double = 0.0
    @Volatile var pb3LastMaxIobU:           Double = 0.0
    @Volatile var pb3ProfileTargetMgdl:     Double = 0.0

    // ── HbA1c estimation — computed fresh each fragmentData() call from DB ───
    // Formula: (mean_mgdl + 46.7) / 28.7
    // Queries today's readings (midnight to now) via persistenceLayer.

    companion object {
        const val REBOUND_GUARD_MS              = 60 * 60 * 1000L
        const val ROLLER_REBOUND_EXTENSION_MS   = 15 * 60 * 1000L   // +15 min per consecutive rollercoaster
        const val ROLLER_REBOUND_EXTENSION_MAX_MS = 45 * 60 * 1000L // cap at +45 min total extension
        const val UAM_EXIT_MAX_DELTA_MMOL       = 0.5                // max rising delta (mmol/5min) to allow auto-cancel at target
        const val SMB_DELIVERY_FRACTION = 0.5
        // Sensor insert time is cached for this long — avoids a 30-day DB scan every 5-min loop cycle.
        // Sensor changes happen every 10-14 days; 6h staleness is inconsequential for warmup detection.
        private const val SENSOR_CACHE_REFRESH_MS = 6 * 60 * 60 * 1000L
        // HbA1c estimate is derived from today's CGM readings (up to 288 rows by end of day).
        // It's a display-only metric approximating a 3-month average — 2h refresh is more than enough.
        private const val HBA1C_CACHE_REFRESH_MS  = 2 * 60 * 60 * 1000L
    }

    // ── Unit-aware display helpers ────────────────────────────────────────────
    // Internal BG values are always mg/dL; deltas from glucoseStatus are mg/dL.
    // shortAvgDeltaAtLow is stored in mmol (converted at capture site).
    // Use these for all user-visible strings.
    val isMmol: Boolean get() =
        profileUtil.units == GlucoseUnit.MMOL
    /** Profile ISF in mg/dL — for circadian table colour comparison. Reads from invoke() cache, never blocks. */
    val profileIsfMgdl: Double get() = cachedProfileIsf
    /** Profile basal U/h — for circadian table colour comparison. Reads from invoke() cache, never blocks. */
    val profileBasalU: Double get() = cachedProfileBasal
    private val unitLabel: String get() = if (isMmol) "mmol" else "mg/dL"
    /** Format a BG value in mg/dL to user units */
    private fun fmtBg(mgdl: Double): String =
        if (isMmol) String.format(java.util.Locale.ROOT, "%.1f", mgdl / 18.0)
        else        String.format(java.util.Locale.ROOT, "%.0f", mgdl)
    /** Format a delta value (internal mmol) to user units */
    private fun fmtDelta(mmol: Double): String =
        if (isMmol) String.format(java.util.Locale.ROOT, "%+.2f", mmol)
        else        String.format(java.util.Locale.ROOT, "%+.1f", mmol * 18.0)
    /** Format an ISF value in mg/dL to user units */
    private fun fmtIsf(mgdl: Double): String =
        if (isMmol) String.format(java.util.Locale.ROOT, "%.1f", mgdl / 18.0)
        else        String.format(java.util.Locale.ROOT, "%.0f", mgdl)

    // ── Cached Overview state ────────────────────────────────────────────────
    // Updated each invoke() so overviewState() can be called any time from UI threads.
    private val _overviewStateFlow = MutableStateFlow<app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview.OverviewState>(
        app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview.OverviewState(
            modeLine      = "Meal: Fasting",
            pb2Line       = null,
            pb3Line       = null,
            learningState = "Learning",
            isFasting     = true,
            isLearning    = true
        )
    )

    override val overviewStateFlow: StateFlow<app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview.OverviewState>
        get() = _overviewStateFlow

    private var cachedOverviewState: app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview.OverviewState
        get() = _overviewStateFlow.value
        set(value) { _overviewStateFlow.value = value }

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

    fun resetIsf() {
        circadianLearner.resetIsf()
        aapsLogger.debug(LTag.APS, "SmartInsulinPlugin: ISF circadian state reset")
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
        val profIsf   = cachedProfileIsf
        val profBasal = cachedProfileBasal
        val isMmolUnit = isMmol
        return buildString {
            appendLine()

            // ── Active cycle values ───────────────────────────────────────────
            appendLine("── Active (Hour=${hour}:00, Day=$day) ─────────────────")
            val effectiveAggr = aggressionLearner.aggressiveness.coerceAtMost(circadianLearner.aggrCeiling(hour))
            appendLine("  Aggressiveness: ${"%.3f".format(effectiveAggr)}")
            appendLine("  TIR score: ${"%.3f".format(aggressionLearner.aggressiveness)} (>1.0=more aggressive, <1.0=backing off)")
            appendLine("  Circ ceiling: ${"%.3f".format(circadianLearner.aggrCeiling(hour))} (clamps score downward if < score)")
            appendLine("  Meal mode: aggressiveness locked to 1.0 during any non-fasting mode")
            val isfMult = circadianLearner.isfMultiplier(hour)
            val learnedIsf = if (profIsf > 0 && isfMult > 0)
                if (isMmolUnit) "${"%.2f".format(profIsf / isfMult / 18.0)} mmol/U"
                else "${"%.1f".format(profIsf / isfMult)} mg/dL/U"
            else "—"
            appendLine("  ISF: $learnedIsf (×${"%.3f".format(isfMult)})")
            val basalMult = basalLearner.multiplierClamped * circadianLearner.basalMultiplier(hour)
            val learnedBas = if (profBasal > 0) "${"%.3f".format(profBasal * basalMult)} U/h" else "—"
            appendLine("  Basal: $learnedBas (×${"%.3f".format(basalMult)} flat=×${"%.3f".format(basalLearner.multiplierClamped)} circ=×${"%.3f".format(circadianLearner.basalMultiplier(hour))})")
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
            val pb3Status = mealOverrideManager.preBolus3StatusText
            if (pb3Status.isNotEmpty()) {
                appendLine("  ${pb3Status.replace("PB3 waiting:", "PB3:").replace("PB3 active:", "PB3:")}")
            }

            // ── STFT / UAM debug ──────────────────────────────────────────────
            appendLine()
            appendLine("── STFT / UAM ────────────────────────")
            // STFT
            val stftStatus = stftController.statusString(cachedProfileTarget.takeIf { it > 0.0 } ?: (5.5 * 18.0))
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
                               "iobAtLow=${String.format(java.util.Locale.ROOT, "%.2f", iob)}U " +
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
            val isfUnit  = if (isMmolUnit) "mmol/U" else "mg/dL/U"
            appendLine("── Circadian 24h ─────────────────────")
            appendLine("  Hr  ISF ($isfUnit)   Basal (U/h)  Ceil   Conf")
            for (h in 0..23) {
                val marker    = if (h == hour) "▶" else " "
                val hIsfMult  = circadianLearner.isfMultiplier(h)
                val hBasMult  = basalLearner.multiplierClamped * circadianLearner.basalMultiplier(h)
                val hIsf = if (profIsf > 0 && hIsfMult > 0)
                    if (isMmolUnit) "${"%.2f".format(profIsf / hIsfMult / 18.0)}"
                    else "${"%.1f".format(profIsf / hIsfMult)}"
                else "—"
                val hBas = if (profBasal > 0) "${"%.3f".format(profBasal * hBasMult)}" else "—"
                appendLine("$marker ${h.toString().padStart(2)}  $hIsf  $hBas  " +
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

    data class Pb3GateData(
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
        val nudgeSessionIsfMgdl: Double,   // full composite ISF mg/dL at session start (matches loop delivery)
        val nudgeSessionBasalU: Double,    // full composite basal U/h at session start (matches loop delivery)
        val profileIsfMgdl:     Double,
        val finalIsfMgdl:       Double,
        val basalMultiplier:    Double,
        val profileBasalU:      Double,
        val finalBasalU:        Double,
        val currentBgMgdl:      Double,    // current BG in mg/dL — for UI defensive-state detection
        val profileTargetMgdl:  Double,    // current profile target in mg/dL — for UI defensive-state detection
        val lastBasalSignal:    String,
        val lastAggrNudgeStatus: String,
        val lastAccelDebug:     String,
        val lastPredTrimDebug:  String,
        val inReboundWindow:    Boolean,
        val reboundMins:        Long,
        val reboundWindowMins:  Int,
        val totalReboundWindowMins: Int,          // base + rollercoaster extension
        val consecutiveRollercoasters: Int,       // for escalating extension display
        val hardLowPenaltyActive: Boolean,        // true if hard low penalty fired in last 90 min
        val softLandingBypass:  Boolean,
        val bgWentLow:          Boolean,
        val secondLowOccurred:  Boolean,
        val minBgDuringLow:     Double,
        val iobAtLowTime:       Double,
        val pb2Status:          String,
        val lastIsfEpisodeDebug: String,
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
        val fuelTrimStrength:   Double,
        val profileLearningStatus: String,
        val circadianRawStatus: String,
        val profilesRawStatus:  String,
        val tirRawLine:         String,
        val avgBgMgdl24h:       Double,
        val estimatedHba1c:     Double,
        val bgWindowHours:      Int,
        val pb2GateData:        Pb2GateData?,
        val pb3GateData:        Pb3GateData?,
        val activeDoseU:        Double?,
        val activePb2DoseU:     Double?,
        val activePb3DoseU:     Double?,
        val pb3Status:          String,
        // ── Meal Phase Tracker ────────────────────────────────────────────────
        val mealPhaseActive:       Boolean,
        val mealPhaseLabel:        String,
        val mealPhaseDebug:        String,
        val mealPhaseTransitionDebug: String,
        val mealPhaseSessionMode:  String,
        val mealPhaseElapsedMins:  Long,
        val mealPhaseCarb:         MealPhaseTracker.PhaseStatus,
        val mealPhasePF:           MealPhaseTracker.PhaseStatus,
        val mealPhaseTail:         MealPhaseTracker.PhaseStatus,
    )

    fun fragmentData(): FragmentData {
        val cal     = java.util.Calendar.getInstance()
        val hour    = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val dow     = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1
        val day     = DayOfWeekCircadianState.DAY_LABELS[dow.coerceIn(0, 6)]
        // Use cached profile values — written each invoke() on background thread, safe to read here
        val profileIsf   = cachedProfileIsf
        val profileBasal = cachedProfileBasal
        val isfMult      = circadianLearner.isfMultiplier(hour)
        val basalMult    = basalLearner.multiplierClamped * circadianLearner.basalMultiplier(hour)
        val activeMode   = mealOverrideManager.activeMealMode
        val currentMealMode = activeMode ?: MealMode.FASTING
        val isLearningEnabled = preferences.get(BooleanKey.ApsSmartInsulinEnableLearning)
        val nowMs        = System.currentTimeMillis()

        val tbrStep           = activePlugin.activePump.pumpDescription.tempAbsoluteStep.takeIf { it > 0.0 } ?: 0.05
        val rawFinalBasal     = profileBasal * basalMult
        val roundedFinalBasal = Math.round(rawFinalBasal / tbrStep) * tbrStep

        // nudgeDisplaySessionIsfMgdl and nudgeDisplaySessionBasalU are written exclusively
        // by invoke() — captured before circadianLearner.update() so "was" reflects the
        // true pre-nudge baseline. fragmentData() just reads them; no capture logic here.

        val circRaw = buildString {
            val isfUnit  = if (isMmol) "mmol/U" else "mg/dL/U"
            val basUnit  = "U/h"
            appendLine("  Hr  ISF ($isfUnit)   Basal ($basUnit)  Ceil   Conf")
            for (h in 0..23) {
                val marker    = if (h == hour) "▶" else " "
                val isfMult   = circadianLearner.isfMultiplier(h)
                val basalMult = basalLearner.multiplierClamped * circadianLearner.basalMultiplier(h)
                val learnedIsf = if (profileIsf > 0 && isfMult > 0)
                    if (isMmol) "${"%.2f".format(profileIsf / isfMult / 18.0)}"
                    else "${"%.1f".format(profileIsf / isfMult)}"
                else "—"
                val learnedBas = if (profileBasal > 0)
                    "${"%.3f".format(profileBasal * basalMult)}"
                else "—"
                appendLine("$marker ${h.toString().padStart(2)}  $learnedIsf  $learnedBas  " +
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

        // HbA1c — read from cache (computed each invoke() on background thread)
        val hba1cAvgMgdl      = cachedHba1cAvgMgdl
        val hba1cEstimate     = cachedHba1cEstimate
        val hba1cWindowHours  = cachedHba1cWindowHours

        return FragmentData(
            hour               = hour,
            dayLabel           = day,
            mealMode           = activeMode?.label ?: "Fasting",
            modeRemMins        = if (activeMode != null) (mealOverrideManager.modeTimeRemainingMs / 60_000).toInt() else null,
            aggressiveness     = aggressionLearner.aggressiveness.coerceAtMost(circadianLearner.aggrCeiling(hour)),
            circCeil           = circadianLearner.aggrCeiling(hour),
            isfMultiplier      = isfMult,
            nudgeSessionIsfMgdl = nudgeDisplaySessionIsfMgdl,
            nudgeSessionBasalU  = nudgeDisplaySessionBasalU,
            profileIsfMgdl     = profileIsf,
            lastIsfEpisodeDebug = circadianLearner.lastIsfEpisodeDebug,
            // finalIsfMgdl = profileISF / isfMult — matches OapsProfile.sens exactly.
            // aggrCeiling acts on the aggressiveness score (SMB sizing), not on sens/ISF.
            // Including ceiling here would show numbers that don't match actual delivery.
            finalIsfMgdl       = if (isfMult > 0) profileIsf / isfMult else 0.0,
            basalMultiplier    = basalMult,
            profileBasalU      = profileBasal,
            finalBasalU        = roundedFinalBasal,
            // Current BG and target — read from glucose provider at render time, not cached,
            // so the UI always sees the latest reading. Falls back to 0.0 if CGM offline,
            // which the UI treats as "data unavailable, skip defensive-state detection".
            currentBgMgdl      = glucoseStatusProvider.glucoseStatusData?.glucose ?: 0.0,
            profileTargetMgdl  = cachedProfileTarget,
            lastBasalSignal    = circadianLearner.lastBasalSignal,
            lastAggrNudgeStatus = circadianLearner.lastAggrNudgeStatus,
            lastAccelDebug     = circadianLearner.lastAccelDebug,
            lastPredTrimDebug  = circadianLearner.lastPredTrimDebug,
            inReboundWindow    = inReboundWindow,
            reboundMins        = msSinceLastSuspend / 60_000,
            reboundWindowMins  = preferences.get(IntKey.ApsSmartInsulinReboundWindowMins),
            totalReboundWindowMins = (reboundGuardMs / 60_000).toInt(),
            consecutiveRollercoasters = circadianLearner.consecutiveRollercoasters,
            hardLowPenaltyActive = circadianLearner.lastHardLowPenaltyMs > 0L &&
                (System.currentTimeMillis() - circadianLearner.lastHardLowPenaltyMs) < 90 * 60_000L,
            softLandingBypass  = softLandingBypass,
            bgWentLow          = bgWentLow,
            secondLowOccurred  = secondLowOccurred,
            minBgDuringLow     = minBgDuringLow,
            iobAtLowTime       = iobAtLowTime,
            pb2Status          = cachedOverviewState.pb2Line ?: "",
            isMmol             = profileUtil.units == app.aaps.core.data.model.GlucoseUnit.MMOL,
            learningState      = cachedOverviewState.learningState,
            activityLevel      = activityMonitor.level.label,
            avgHrBpm           = activityMonitor.avgHrBpm.toInt(),
            steps5min          = activityMonitor.lastSteps5min,
            restingHrBpm       = preferences.get(DoubleKey.ApsSmartInsulinRestingHrBpm),
            postMealLockoutMins = postMealLeft,
            cgmWarmup          = cachedOverviewState.learningState.contains("CGM"),
            stftStatus         = stftController.statusString(cachedProfileTarget.takeIf { it > 0.0 } ?: (5.5 * 18.0)),
            stftActive         = stftController.isActive,
            uamStatusLine      = uamController.statusString(),
            uamDebug           = uamController.debugSummary(),
            profileLearningStatus = when {
                !isLearningEnabled -> "off: Learning disabled"
                activityMonitor.level != ActivityMonitor.ActivityLevel.SEDENTARY -> "off: Activity ${activityMonitor.level.label}"
                (glucoseStatusProvider.glucoseStatusData?.noise ?: 0.0) > 1.5 -> "off: High noise"
                else -> bolusCurveTracker.statusSummary(currentMealMode)
            },
            circadianRawStatus = circRaw,
            profilesRawStatus  = profRaw,
            tirRawLine         = aggressionLearner.tirSummary,
            avgBgMgdl24h       = hba1cAvgMgdl,
            estimatedHba1c     = hba1cEstimate,
            bgWindowHours      = hba1cWindowHours,
            pb2GateData        = if (mealOverrideManager.preBolus2Pending) Pb2GateData(
                bgMgdl            = pb2LastBgMgdl,
                deltaMgdl         = pb2LastDeltaMgdl,
                shortAvgDeltaMgdl = pb2LastShortAvgDeltaMgdl,
                iobU              = pb2LastIobU,
                maxIobU           = pb2LastMaxIobU,
                profileTargetMgdl = pb2ProfileTargetMgdl,
                isMmol            = profileUtil.units == app.aaps.core.data.model.GlucoseUnit.MMOL
            ) else null,
            pb3GateData        = if (mealOverrideManager.preBolus3Pending) Pb3GateData(
                bgMgdl            = pb3LastBgMgdl,
                deltaMgdl         = pb3LastDeltaMgdl,
                shortAvgDeltaMgdl = pb3LastShortAvgDeltaMgdl,
                iobU              = pb3LastIobU,
                maxIobU           = pb3LastMaxIobU,
                profileTargetMgdl = pb3ProfileTargetMgdl,
                isMmol            = profileUtil.units == app.aaps.core.data.model.GlucoseUnit.MMOL
            ) else null,
            activeDoseU        = mealOverrideManager.activeDoseU,
            activePb2DoseU     = mealOverrideManager.activePb2DoseU,
            activePb3DoseU     = mealOverrideManager.activePb3DoseU,
            pb3Status          = cachedOverviewState.pb3Line ?: "",
            // ── Meal Phase Tracker fields ─────────────────────────────────────
            mealPhaseActive      = mealPhaseTracker.sessionActive,
            mealPhaseLabel       = mealPhaseTracker.phaseLabel,
            mealPhaseDebug       = mealPhaseTracker.lastPhaseDebug,
            mealPhaseTransitionDebug = mealPhaseTracker.lastTransitionDebug,
            mealPhaseSessionMode = mealPhaseTracker.sessionMode.label,
            mealPhaseElapsedMins = if (mealPhaseTracker.sessionActive) (System.currentTimeMillis() - mealPhaseTracker.sessionStartMs) / 60_000L else 0L,
            mealPhaseCarb        = mealPhaseTracker.carbPhaseStatus(),
            mealPhasePF          = mealPhaseTracker.pfPhaseStatus(),
            mealPhaseTail        = mealPhaseTracker.tailPhaseStatus(),
            fuelTrimStrength   = circadianLearner.trimStrength
        )
    }

    /** Build circadian table string for a specific day-of-week (0=Sun..6=Sat) */
    fun circadianDataForDay(dow: Int): String {
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val currentDow  = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1
        val profIsf     = cachedProfileIsf
        val profBasal   = cachedProfileBasal
        val isMmolUnit  = isMmol
        return buildString {
            for (h in 0..23) {
                val marker   = if (dow == currentDow && h == currentHour) "▶" else " "
                val hIsfMult = circadianLearner.isfMultiplier(h, dow)
                val hBasMult = basalLearner.multiplierClamped * circadianLearner.basalMultiplier(h, dow)
                val hIsf = if (profIsf > 0 && hIsfMult > 0)
                    if (isMmolUnit) "${"%.2f".format(profIsf / hIsfMult / 18.0)}"
                    else "${"%.1f".format(profIsf / hIsfMult)}"
                else "—"
                val hBas = if (profBasal > 0) "${"%.3f".format(profBasal * hBasMult)}" else "—"
                appendLine("$marker ${h.toString().padStart(2)}  $hIsf  $hBas  " +
                               "${"%.3f".format(circadianLearner.aggrCeiling(h, dow))}  " +
                               "${"%.0f".format(circadianLearner.confidencePct(h, dow))}%")
            }
        }
    }

    // ── Public state accessors for Overview display ─────────────────────────

    /**
     * Returns a one-line suppression reason for the Overview "State" cell,
     * or null if learning is currently active.
     * Called from OverviewFragment.updateIobCob() each loop cycle.
     */
    override fun overviewState(): app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview.OverviewState {
        // Recompute modeLine and learningState live so they're always current.
        // Avoids stale display between loop cycles (e.g. mode expired but state still shows P/F).
        val activeMode = mealOverrideManager.activeMealMode
        val now        = System.currentTimeMillis()

        val liveModeLine = activeMode?.let { mode ->
            val mins = mealOverrideManager.modeTimeRemainingMs / 60_000
            if (mode.isUam) {
                val uamLabel = when (mode) {
                    MealMode.UAM_BREAKFAST    -> "Breakfast"
                    MealMode.UAM_LUNCH        -> "Lunch"
                    MealMode.UAM_DINNER       -> "Dinner"
                    MealMode.UAM_SNACK        -> "Snack"
                    MealMode.UAM_PROTEIN_FAT  -> "Protein/Fat"
                    MealMode.UAM_AFTERNOON    -> "Afternoon"
                    else                      -> mode.label
                }
                "Meal: UAM ($uamLabel) ${mins}m left"
            } else {
                "Meal: ${mode.label} ${mins}m left"
            }
        } ?: "Meal: Fasting"

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

        return cachedOverviewState.copy(modeLine = liveModeLine, learningState = liveLearningState)
    }

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
        val dayStart       = preferences.get(IntKey.ApsSmartInsulinUamProteinFatDayStartHour)
        val dayEnd         = preferences.get(IntKey.ApsSmartInsulinUamProteinFatDayEndHour)
        val nightStart     = preferences.get(IntKey.ApsSmartInsulinUamProteinFatNightStartHour)
        val nightEnd       = preferences.get(IntKey.ApsSmartInsulinUamProteinFatNightEndHour)
        val overnightStart = preferences.get(IntKey.ApsSmartInsulinUamProteinFatOvernightStartHour)
        val overnightEnd   = preferences.get(IntKey.ApsSmartInsulinUamProteinFatOvernightEndHour)

        // Exclusive end hour — start=10, end=16 means hours 10,11,12,13,14,15 are in window.
        // Supports midnight crossing (start > end).
        fun inWindow(start: Int, end: Int): Boolean =
            if (start <= end) hour >= start && hour < end
            else hour >= start || hour < end

        val inDay       = inWindow(dayStart,       dayEnd)
        val inNight     = inWindow(nightStart,     nightEnd)
        val inOvernight = inWindow(overnightStart, overnightEnd)

        val dayIsf       = sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamProteinFatDayIsf.key,       UnitDoubleKey.ApsSmartInsulinUamProteinFatDayIsf.defaultValue)
        val nightIsf     = sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamProteinFatNightIsf.key,     UnitDoubleKey.ApsSmartInsulinUamProteinFatNightIsf.defaultValue)
        val overnightIsf = sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamProteinFatOvernightIsf.key, UnitDoubleKey.ApsSmartInsulinUamProteinFatOvernightIsf.defaultValue)
        val fallback     = sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamProteinFatIsf.key,          UnitDoubleKey.ApsSmartInsulinUamProteinFatIsf.defaultValue)

        // Priority: overnight > night > day > fallback
        // Overnight is checked first — it's the most specific window (deep sleep hours)
        // and should always win if it overlaps with the night window edges.
        return when {
            inOvernight && overnightIsf > 0.0 -> overnightIsf
            inNight     && nightIsf     > 0.0 -> nightIsf
            inDay       && dayIsf       > 0.0 -> dayIsf
            else                              -> fallback
        }
    }

    /**
     * Resolves per-mode ISF override in mg/dL.
     * Returns 0.0 for FASTING (caller uses profile ISF / circadian multiplier).
     * ISF values stored as mg/dL by sp.putDouble — do NOT use spMgdl() here.
     */
    private fun modeIsfMgdl(mode: MealMode, hour: Int): Double = when (mode) {
        MealMode.BREAKFAST       -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinBreakfastIsf.key,     UnitDoubleKey.ApsSmartInsulinBreakfastIsf.defaultValue)
        MealMode.LUNCH           -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinLunchIsf.key,         UnitDoubleKey.ApsSmartInsulinLunchIsf.defaultValue)
        MealMode.DINNER          -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinDinnerIsf.key,        UnitDoubleKey.ApsSmartInsulinDinnerIsf.defaultValue)
        MealMode.LOW_CARB        -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinLowCarbIsf.key,       UnitDoubleKey.ApsSmartInsulinLowCarbIsf.defaultValue)
        MealMode.EXTENDED        -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinExtendedIsf.key,      UnitDoubleKey.ApsSmartInsulinExtendedIsf.defaultValue)
        MealMode.UAM_BREAKFAST   -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamBreakfastIsf.key,  UnitDoubleKey.ApsSmartInsulinUamBreakfastIsf.defaultValue)
        MealMode.UAM_LUNCH       -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamLunchIsf.key,      UnitDoubleKey.ApsSmartInsulinUamLunchIsf.defaultValue)
        MealMode.UAM_DINNER      -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamDinnerIsf.key,     UnitDoubleKey.ApsSmartInsulinUamDinnerIsf.defaultValue)
        MealMode.UAM_SNACK       -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamSnackIsf.key,      UnitDoubleKey.ApsSmartInsulinUamSnackIsf.defaultValue)
        MealMode.UAM_AFTERNOON   -> sp.getDouble(UnitDoubleKey.ApsSmartInsulinUamAfternoonIsf.key,  UnitDoubleKey.ApsSmartInsulinUamAfternoonIsf.defaultValue)
        MealMode.UAM_PROTEIN_FAT -> pfIsfMgdl(hour)
        MealMode.FASTING         -> 0.0
    }

    override suspend fun invoke(initiator: String, tempBasalFallback: Boolean) {
        aapsLogger.debug(LTag.APS, "SmartInsulin invoke from $initiator")
        val previousAPSResult = lastAPSResult   // save before nulling — used for rebound tracking
        lastAPSResult = null

        val profile = profileFunction.getProfile() ?: run {
            rxBus.send(EventResetOpenAPSGui(rh.gs(app.aaps.core.ui.R.string.no_profile_set)))
            return
        }
        // Cache profile values for fragmentData() — avoids any blocking calls on UI thread
        cachedProfileIsf   = profile.getIsfMgdl("SmartInsulinPlugin")
        cachedProfileBasal = profile.getBasal()
        cachedProfileTarget = profile.getTargetMgdl()
        // Cache HbA1c estimate — suspend DB call must stay on background thread.
        // Gated to once per HBA1C_CACHE_REFRESH_MS — today's readings grow by one row every
        // 5 min, so querying up to 288 rows every cycle is wasteful for a display-only metric.
        val nowForCache = System.currentTimeMillis()
        if (nowForCache - cachedHba1cRefreshedAtMs >= HBA1C_CACHE_REFRESH_MS) {
            try {
                val todayStart = dateUtil.beginOfDay(nowForCache)
                val bgs = persistenceLayer.getBgReadingsDataFromTimeToTime(todayStart, nowForCache, true)
                if (bgs.size >= 24) {
                    cachedHba1cAvgMgdl      = bgs.map { it.value }.average()
                    cachedHba1cEstimate     = (cachedHba1cAvgMgdl + 46.7) / 28.7
                    cachedHba1cWindowHours  = if (bgs.size >= 2)
                        ((bgs.maxOf { it.timestamp } - bgs.minOf { it.timestamp }) / (60 * 60 * 1000L)).toInt().coerceAtLeast(1)
                    else 0
                } else {
                    cachedHba1cAvgMgdl     = 0.0
                    cachedHba1cEstimate    = 0.0
                    cachedHba1cWindowHours = 0
                }
                cachedHba1cRefreshedAtMs = nowForCache
            } catch (e: Exception) {
                aapsLogger.debug(LTag.APS, "SmartInsulinPlugin: HbA1c query failed: ${e.message}")
            }
        }
        if (!isEnabled()) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_disabled)))
            return
        }
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData ?: run {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_no_glucose_data)))
            return
        }

        if (!hardLimits.checkHardLimits(profile.iCfg.dia, app.aaps.core.ui.R.string.profile_dia, hardLimits.minDia(), hardLimits.maxDia())) return
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
        // Single Calendar allocation for the whole invoke() — reused by all 4 hour-of-day sites below.
        // Based on `now` (dateUtil.now()) so all time-based decisions use the same instant.
        val currentHour = java.util.Calendar.getInstance().also { it.timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
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
        val previousWasPf = previousMealModeForLockout == MealMode.UAM_PROTEIN_FAT
        if (previousWasRealMeal && mealMode == MealMode.FASTING) {
            mealPhaseTracker.onMealModeExpired(
                now          = now,
                bgMmol       = glucoseStatus.glucose / 18.0,
                targetBgMmol = profile.getTargetMgdl() / 18.0
            )
            val lockoutMins = preferences.get(IntKey.ApsSmartInsulinPostModeLockoutMins)
            if (lockoutMins > 0) {
                learningDirtyUntilMs = maxOf(learningDirtyUntilMs, now + lockoutMins * 60_000L)
                preferences.put(StringKey.ApsSmartInsulinLearningDirtyUntil, learningDirtyUntilMs.toString())
                aapsLogger.debug(LTag.APS,
                                 "SmartInsulin: ${previousMealModeForLockout.label} ended — " +
                                     "learning dirty for ${lockoutMins}min (until ${learningDirtyUntilMs})")
            }
        } else if (previousWasPf && mealMode == MealMode.FASTING) {
            // P/F gets half the normal lockout (minimum 30 min) — enough to avoid learning
            // from IOB-driven crashes after P/F stacking, but short enough that UAM can
            // still fire normally if a real meal rise follows.
            // Guard: if user disabled post-meal lockout (lockoutMins=0), respect that for P/F too.
            val lockoutMins = preferences.get(IntKey.ApsSmartInsulinPostModeLockoutMins)
            if (lockoutMins > 0) {
                val pfLockoutMins = (lockoutMins / 2).coerceAtLeast(30)
                learningDirtyUntilMs = maxOf(learningDirtyUntilMs, now + pfLockoutMins * 60_000L)
                preferences.put(StringKey.ApsSmartInsulinLearningDirtyUntil, learningDirtyUntilMs.toString())
                aapsLogger.debug(LTag.APS,
                                 "SmartInsulin: P/F ended — learning dirty for ${pfLockoutMins}min " +
                                     "(half of ${lockoutMins}min meal lockout)")
            }
        }
        previousMealModeForLockout = mealMode
        val lockoutRemainingMs = if (learningDirtyUntilMs > 0L) learningDirtyUntilMs - now else 0L
        // Clear persisted dirty flag once window has passed
        if (learningDirtyUntilMs > 0L && now >= learningDirtyUntilMs) {
            learningDirtyUntilMs = 0L
            preferences.put(StringKey.ApsSmartInsulinLearningDirtyUntil, "0")
        }
        // If user has disabled post-meal lockout (set to 0 mins), honour it immediately —
        // clear any persisted lockout timestamp from previous setting so it doesn't linger.
        val lockoutSettingMins = preferences.get(IntKey.ApsSmartInsulinPostModeLockoutMins)
        if (lockoutSettingMins == 0 && learningDirtyUntilMs > 0L) {
            learningDirtyUntilMs = 0L
            preferences.put(StringKey.ApsSmartInsulinLearningDirtyUntil, "0")
        }
        val inPostMealLockout = mealMode == MealMode.FASTING && now < learningDirtyUntilMs

        // ISF overrides: resolved by modeIsfMgdl() — stored as mg/dL, do NOT use spMgdl().
        val modeIsfMgdl = modeIsfMgdl(mealMode, currentHour)
        val trueIsfMgdl   = profile.getIsfMgdl("SmartInsulinPlugin")

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
        // Sensor insert time — cached for SENSOR_CACHE_REFRESH_MS to avoid a 30-day DB scan
        // every 5-min loop cycle. Sensor changes happen every 10–14 days; 30-min staleness
        // is inconsequential for CGM warmup detection (which has a 24-hour window).
        val sensorInsertTimeMs: Long = if (now - sensorCacheRefreshedAtMs < SENSOR_CACHE_REFRESH_MS) {
            cachedSensorInsertTimeMs
        } else {
            try {
                val sensorEvents = persistenceLayer.getTherapyEventDataFromTime(
                    now - 30 * 24 * 60 * 60 * 1000L,
                    TE.Type.SENSOR_CHANGE,
                    true
                )
                val found = sensorEvents.maxByOrNull { it.timestamp }?.timestamp ?: 0L
                cachedSensorInsertTimeMs = found
                sensorCacheRefreshedAtMs = now
                found
            } catch (e: Exception) {
                aapsLogger.debug(LTag.APS, "SmartInsulinPlugin: sensorChange query failed: ${e.message}")
                cachedSensorInsertTimeMs  // keep using the last known value on failure
            }
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

        // ── Circadian learner — fasting + no high TT only ─────────────────────
        // ISF/basal/aggr circadian learning is only valid during clean fasting windows.
        // The circadian learner itself also gates on mealMode==FASTING internally,
        // but we gate highTempTarget here before the call to avoid polluting bgHistory.
        // Circadian learner:
        //   - Always call during normal conditions
        //   - During CGM warmup: call with suppressAdaptiveLearning=true so rollercoaster still fires
        //   - During activity or high TT: skip entirely (BG movement isn't insulin-driven)
        // CircadianLearner gets its own suppress flag WITHOUT inPostMealLockout.
        // Drift-based basal learning should fire during lockout — it's measuring real BG physics.
        // Only the negIOB signal needs lockout gating (IOB shape could be meal bolus tail).
        // ISF learning also runs during lockout — activity-based deviation is independent of meals.
        // The negIOB gate is handled inside CircadianLearner via inPostMealLockout parameter.
        val isfMultBefore = circadianLearner.isfMultiplier()
        val totalBasalMultBefore = basalLearner.multiplierClamped * circadianLearner.basalMultiplier()
        val lastDirection = run {
            val parts = lastSeenNudgeState.split("|")
            val p = parts.getOrNull(0) ?: "INACTIVE"
            if (p == "TRIM") parts.getOrNull(1) else if (p == "ACTIVE_HIGH" || p == "ACTIVE_LOW") p else null
        }

        if (!highTempTarget) {
            val suppressAdaptiveLearningUpdate = activityMonitor.suppressLearning || cgmState.suppressLearning
            circadianLearner.update(
                glucoseStatus            = glucoseStatus,
                iobArray                 = iobArray,
                mealMode                 = mealMode,
                cobG                     = mealData.mealCOB,
                profileIsfMgdl           = trueIsfMgdl,
                targetMgdl               = targetBg,
                lowGuardMgdl             = spMgdl(UnitDoubleKey.ApsSmartInsulinLowGuard),
                inPostMealLockout        = inPostMealLockout,
                aggressiveness           = aggressionLearner.aggressiveness.coerceAtMost(circadianLearner.aggrCeiling()), // actual effective aggressiveness — ceiling is a cap not the score
                suppressAdaptiveLearning = suppressAdaptiveLearningUpdate,
                fastingPeakMins          = profileLearner.getProfile(app.aaps.core.interfaces.smartInsulin.MealMode.FASTING).peakMinutes
            )
            // If nudge is suppressed within update() (activity/CGM warmup), mark paused
            if (suppressAdaptiveLearningUpdate) {
                val pauseReason = when {
                    activityMonitor.suppressLearning -> "Activity detected (${activityMonitor.level.label})"
                    cgmState.suppressLearning        -> "New sensor — CGM warmup"
                    else                             -> "Learning suppressed"
                }
                circadianLearner.pauseNudgeStatus(pauseReason)
            }
        } else {
            val pauseReason = when {
                highTempTarget           -> "Temp target active"
                activityMonitor.suppressLearning -> "Activity detected (${activityMonitor.level.label})"
                else                     -> "Learning suppressed"
            }
            circadianLearner.pauseNudgeStatus(pauseReason)
            aapsLogger.debug(LTag.APS, "CircadianLearner skipped: highTT=$highTempTarget activity=${activityMonitor.level}")
        }
        // Also pause nudge during meal modes and post-meal lockout
        if (mealMode != MealMode.FASTING) {
            val mealReason = when (mealMode) {
                MealMode.UAM_PROTEIN_FAT -> "P/F mode active"
                else                     -> "Meal mode active (${mealMode.label})"
            }
            circadianLearner.pauseNudgeStatus(mealReason)
        } else if (inPostMealLockout) {
            circadianLearner.pauseNudgeStatus("Post-meal lockout active")
        } else if (inReboundWindow) {
            // Paused during rebound window — BG is recovering from a low.
            // Once the window expires, nudge resumes regardless of bgWentLow.
            circadianLearner.pauseNudgeStatus("Post-low recovery — waiting for BG to stabilise")
        }

        // Capture session-start multipliers on first nudge of this session.
        // Uses the multipliers from BEFORE the update() call to ensure "was" reflects the baseline.
        val currentAggrNudgeStatus = circadianLearner.lastAggrNudgeStatus
        val currentDirection = run {
            val parts = currentAggrNudgeStatus.split("|")
            val p = parts.getOrNull(0) ?: "INACTIVE"
            if (p == "TRIM") parts.getOrNull(1) else if (p == "ACTIVE_HIGH" || p == "ACTIVE_LOW") p else null
        }
        if (currentDirection != null && currentDirection != lastDirection) {
            nudgeDisplaySessionIsfMgdl = if (isfMultBefore > 0) trueIsfMgdl / isfMultBefore else 0.0
            nudgeDisplaySessionBasalU  = cachedProfileBasal * totalBasalMultBefore
            aapsLogger.debug(LTag.APS,
                             "SmartInsulinPlugin: nudge baseline captured — dir=$currentDirection " +
                                 "isf=${"%.1f".format(nudgeDisplaySessionIsfMgdl)} basal=${"%.3f".format(nudgeDisplaySessionBasalU)}")
        }
        lastSeenNudgeState = currentAggrNudgeStatus

        // Refresh circadian per-hour multipliers after learner update
        val circIsfMult   = circadianLearner.isfMultiplier()
        val circBasalMult = circadianLearner.basalMultiplier()
        val circAggrCeil  = circadianLearner.aggrCeiling()
        // Apply circadian ISF multiplier during fasting (>1 = higher ISF = less aggressive)
        // Meal mode ISF overrides are user-set — don't touch them
        // circIsfMult > 1.0 → divide → dosingISF goes DOWN → more aggressive → more insulin
        // circIsfMult < 1.0 → divide → dosingISF goes UP   → more insulin (insulin stronger than profile)
        // This is correct: circIsfMult is a sensitivity multiplier, not a direct ISF scalar.
        var dosingIsfMgdl = when {
            modeIsfMgdl > 0.0 -> modeIsfMgdl                    // user meal-mode override — already mg/dL
            else              -> trueIsfMgdl / circIsfMult        // divide: mult>1 → lower dosingISF → more aggressive → more insulin
        }



        // ── Tick the override manager — fires queued bolus when safe ──────────
        val pb2MaxIob = constraintsChecker.getMaxIOBAllowed().value()
        mealOverrideManager.onLoopCycle(
            glucoseStatus = glucoseStatus,
            iobArray      = iobArray,
            maxIobU       = pb2MaxIob,
            profile       = profile
        )
        // PB2 cache — refreshed every cycle regardless of whether PB2 is pending, so that
        // post-fire UI still shows what the gate state was on the last tick.
        pb2LastBgMgdl            = glucoseStatus.glucose
        pb2LastDeltaMgdl         = glucoseStatus.delta
        pb2LastShortAvgDeltaMgdl = glucoseStatus.shortAvgDelta
        pb2LastIobU              = iobArray.firstOrNull()?.iob ?: 0.0
        pb2LastMaxIobU           = pb2MaxIob
        pb2ProfileTargetMgdl     = profile.getTargetMgdl()
        // PB3 cache — same values, identical gates. Refreshed every cycle so UI reflects
        // current state accurately while PB3 is waiting on PB2 or on its own delay.
        pb3LastBgMgdl            = glucoseStatus.glucose
        pb3LastDeltaMgdl         = glucoseStatus.delta
        pb3LastShortAvgDeltaMgdl = glucoseStatus.shortAvgDelta
        pb3LastIobU              = iobArray.firstOrNull()?.iob ?: 0.0
        pb3LastMaxIobU           = pb2MaxIob
        pb3ProfileTargetMgdl     = profile.getTargetMgdl()



        // ── UAM: auto-detect unannounced meals from BG rise during fasting ────
        // Only fires in FASTING mode within configured time windows.
        // Expiry detection is handled internally by UamController via previousMealMode tracking.
        // Safety inputs (bgWentLow, inReboundWindow) prevent false triggers from rebound rises.
        val uamCurrentHour = currentHour
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
        // Use justFiredThisCycle as the authoritative UAM fire signal — more reliable than
        // reading activeMealMode immediately after activateOverride, which may not have
        // propagated yet depending on MealOverrideManager implementation timing.
        val justFiredMode = uamController.justFiredThisCycle
        val latestMealMode = justFiredMode ?: mealOverrideManager.activeMealMode ?: MealMode.FASTING
        if (latestMealMode != mealMode) {
            val latestModeIsfMgdl = modeIsfMgdl(latestMealMode, uamCurrentHour)
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

        // ── STFT: run after UAM so it sees the correct mealMode this cycle ────
        // If UAM just fired, mealMode is now non-FASTING and STFT will reset cleanly
        // rather than sneaking through one cycle with a lowered target.
        val stftAdjusted = stftController.onLoopCycle(
            profileTargetMgdl = profileTargetMgdl,
            currentBgMgdl     = glucoseStatus.glucose,
            delta             = glucoseStatus.delta,
            shortAvgDeltaMgdl = glucoseStatus.shortAvgDelta,
            mealMode          = mealMode,
            isTempTarget      = isTempTarget,
            bgWentLow         = bgWentLow,
            inReboundWindow   = inReboundWindow,
            cgmInWarmup       = cgmInWarmup,
            bgTimestampMs     = glucoseStatus.date
        )
        // STFT handles TT/low/meal internally and returns profileTargetMgdl when blocked.
        val stftTargetMgdl = if (!isTempTarget) stftAdjusted else targetBg

        // ── Build OapsProfile — apply per-meal ISF multiplier to sens ─────────
        val pump       = activePlugin.activePump
        val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
        val oapsProfile = OapsProfile(
            dia                              = profile.iCfg.dia,
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
            current_basal                   = ch.fromPump(pump.baseBasalRate),
            temptargetSet                   = isTempTarget,
            autosens_max                    = preferences.get(DoubleKey.AutosensMax),
            out_units                       = if (profileUtil.units == GlucoseUnit.MMOL) "mmol/L" else "mg/dl",
            lgsThreshold                    = profileUtil.convertToMgdl(preferences.get(UnitDoubleKey.ApsLgsThreshold), profileUtil.units).toInt(),
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

        // Update configurable rebound window — inReboundWindow uses this
        // Extend dynamically if rollercoasters are happening in the same episode:
        //   Rollercoaster 1 → +15 min
        //   Rollercoaster 2+ → +15 min additional per count (capped at +45 min total)
        // Rollercoaster counter resets after 2h gap (new episode) in CircadianLearner.
        val baseReboundMs = preferences.get(IntKey.ApsSmartInsulinReboundWindowMins).toLong() * 60_000L
        val rollerCount   = circadianLearner.consecutiveRollercoasters
        val rollerExtMs   = if (rollerCount >= 1)
            (rollerCount * ROLLER_REBOUND_EXTENSION_MS).coerceAtMost(ROLLER_REBOUND_EXTENSION_MAX_MS)
        else 0L
        reboundGuardMs = baseReboundMs + rollerExtMs
        if (rollerExtMs > 0L) {
            aapsLogger.debug(LTag.APS,
                             "SmartInsulin: rebound window extended by ${rollerExtMs / 60_000}min " +
                                 "(rollercoaster #$rollerCount) → total ${reboundGuardMs / 60_000}min")
        }

        // ── CGM warmup guard ─────────────────────────────────────────────────

        // Suppress learning during CGM warmup — noisy readings corrupt all learned models
        // CGM warmup: suppress ISF/basal/TIR adaptive learning but keep rollercoaster protection
        // Activity: suppress all learning (BG changes are exercise-driven, not insulin-driven)
        val suppressAdaptiveLearningGlobal = activityMonitor.suppressLearning || cgmState.suppressLearning || inPostMealLockout
        val suppressRollercoasterGlobal    = activityMonitor.suppressLearning  // activity only — not CGM warmup


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

        if (suppressAdaptiveLearningGlobal) {
            aapsLogger.debug(LTag.APS, "SmartInsulin: learning suppressed " +
                "(activity=${activityMonitor.level} cgmWarmup=${cgmState.inWarmup})")
        }

        // Always record BG zone for TIR display — skipping would give false metrics in the SI tab.
        // suppressScoring prevents activity-induced lows from penalising aggressiveness, since
        // those lows are caused by exercise sensitivity, not over-aggressive insulin delivery.
        val learnerHighThresh = (cachedProfileTarget + 45.0).coerceIn(135.0, 180.0)
        aggressionLearner.recordBg(
            bgMgdl          = glucoseStatus.glucose,
            lowThreshMgdl   = 70.0,   // 3.9 mmol — clinical TIR low threshold
            highThreshMgdl  = learnerHighThresh,
            mealMode        = mealMode,
            suppressScoring = suppressAdaptiveLearningGlobal
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
        cachedLearningEnabled = learningEnabledCache
        cachedCgmSuppressLearning = cgmState.suppressLearning
        val isMealMode = mealMode != MealMode.FASTING
        // Re-evaluate inPostMealLockout — mealMode may have changed this cycle
        // (e.g. P/F just fired). If mealMode is no longer FASTING, lockout is irrelevant.
        val effectivePostMealLockout = inPostMealLockout && mealMode == MealMode.FASTING
        val learningStateStr = when {
            !learningEnabledCache                -> "off: Learning disabled"
            activityMonitor.suppressLearning     -> "off: Activity ${activityMonitor.level.label}"
            cgmState.suppressLearning            -> "off: CGM warmup"
            effectivePostMealLockout             -> {
                val minsLeft = ((learningDirtyUntilMs - now) / 60_000).coerceAtLeast(1)
                "off: Post-meal ${minsLeft}m left"
            }
            highTempTarget                       -> "off: High temp target"
            mealMode == MealMode.UAM_PROTEIN_FAT -> "limited: P/F mode"
            isMealMode || mealMode.isUam         -> "limited: meal mode"
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
                "Meal: UAM ($uamLabel) ${mins}m left"
            } else {
                // Manual modes: "Meal: Dinner 25m left"
                "Meal: ${mode.label} ${mins}m left"
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
        val pb3LineStr = if (mealOverrideManager.preBolus3Pending) {
            val msRem = mealOverrideManager.preBolus3SecondsRemaining
            when {
                // PB2 not yet fired — PB3 timer hasn't started
                msRem == null -> "PB3: waiting for PB2"
                // Counting down
                msRem > 0 -> "PB3 active: ${msRem / 60_000}m"
                // Delay elapsed — show the blocking gate
                else -> {
                    val bgOk    = pb3LastBgMgdl > pb3ProfileTargetMgdl
                    val iobOk   = pb3LastIobU < pb3LastMaxIobU * MealOverrideManager.MAX_IOB_HEADROOM_RATIO
                    val deltaOk = pb3LastDeltaMgdl >= MealOverrideManager.DELTA_INSTANT_BLOCK_MGDL
                    val shortOk = pb3LastShortAvgDeltaMgdl >= MealOverrideManager.SHORT_AVG_DELTA_BLOCK_MGDL
                    when {
                        !bgOk    -> "PB3: Below target"
                        !iobOk   -> "PB3: IOB too high"
                        !deltaOk -> "PB3: BG falling"
                        !shortOk -> "PB3: Trend falling"
                        else     -> "PB3: Waiting"
                    }
                }
            }
        } else null
        cachedOverviewState = app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview.OverviewState(
            modeLine      = modeLineStr,
            pb2Line       = pb2LineStr,
            pb3Line       = pb3LineStr,
            learningState = learningStateStr,
            isFasting     = mealMode == MealMode.FASTING,
            isLearning    = learningStateStr == "Learning"
        )

        val minsLastBolus = iobArray.firstOrNull()?.lastBolusTime
            ?.let { if (it > 0) (System.currentTimeMillis() - it) / 60_000.0 else Double.MAX_VALUE }
            ?: Double.MAX_VALUE
        if (basalLearningEnabled && mealMode == MealMode.FASTING && !highTempTarget && !suppressAdaptiveLearningGlobal) {
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
        // Blend flat BasalLearner with circadian per-hour learning.
        // Circadian takes over proportionally as its confidence grows.
        //
        // SAFETY NOTE on multiplicative stacking:
        //   flatBasalMult is clamped to [0.5, 1.5] by BasalLearner (MIN/MAX_MULTIPLIER)
        //   circBasalMult is clamped to [0.5, 1.5] by CircadianLearner (BASAL_MULT_MIN/MAX)
        //   Worst case: 1.5 * 1.5 = 2.25x profile basal.
        // This is acceptable because the final TBR rate is independently capped downstream by:
        //   - oapsProfile.max_basal (preferences.ApsMaxBasal)
        //   - max_daily_safety_multiplier * max_daily_basal
        //   - current_basal_safety_multiplier * current_basal
        // These caps are applied in DetermineBasalSmartInsulin.setTempBasal() before any
        // TBR is issued to the pump. So even a compounded 2.25x learner multiplier cannot
        // exceed the user's configured max_basal ceiling.
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
                                     "iob=${String.format(java.util.Locale.ROOT, "%.2f", iobAtLowTime)}U " +
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

        // ── UAM / P/F auto-cancel when BG returns to target or below ────────────────────────
        // Insulin did its job — no need to keep the elevated ISF/target active.
        // Gate: BG at or below profile target AND not rising fast (shortAvgDelta < 0.5 mmol/5min)
        // so we don't cancel mid-spike just because a noisy reading dips to target briefly.
        //
        // Extra safety guards — do NOT cancel if:
        //   1. BG is below low guard — user manually entered meal mode during a low (food to treat it).
        //      Hold the mode until BG actually recovers above target, not the first cycle at target.
        //   2. Mode age < modeWindowMins — pre-bolus or early-meal window: food hasn't arrived yet,
        //      BG is still at target because insulin hasn't been overwhelmed by carbs yet.
        if (mealMode != MealMode.FASTING && mealMode != MealMode.EXTENDED) {
            val shortAvgMmol      = glucoseStatus.shortAvgDelta / 18.0
            val bgAtOrBelowTarget = currentBgMgdl <= profileTargetMgdl
            val notStillRising    = shortAvgMmol < UAM_EXIT_MAX_DELTA_MMOL
            val bgBelowLowGuard   = currentBgMgdl < REBOUND_LOW_THRESHOLD_MGDL
            val modeWindowMins    = preferences.get(IntKey.ApsSmartInsulinModeWindowMins)
            val modeAgeMs         = if (mealOverrideManager.modeStartMs > 0L)
                now - mealOverrideManager.modeStartMs else Long.MAX_VALUE
            val inEarlyWindow     = modeAgeMs < modeWindowMins * 60_000L
            if (bgAtOrBelowTarget && notStillRising && !bgBelowLowGuard && !inEarlyWindow) {
                aapsLogger.debug(LTag.APS,
                                 "SmartInsulin: BG ${fmtBg(currentBgMgdl)}$unitLabel at/below target " +
                                     "${fmtBg(profileTargetMgdl)}$unitLabel and not rising (Δ=${String.format("%.2f", shortAvgMmol)} mmol) " +
                                     "— auto-cancelling ${mealMode.label}")
                mealOverrideManager.cancelOverride()
            } else if (bgAtOrBelowTarget && notStillRising) {
                aapsLogger.debug(LTag.APS,
                                 "SmartInsulin: auto-cancel suppressed — " +
                                     "bgBelowLowGuard=$bgBelowLowGuard (${fmtBg(currentBgMgdl)} < ${fmtBg(REBOUND_LOW_THRESHOLD_MGDL)}$unitLabel) " +
                                     "inEarlyWindow=$inEarlyWindow (${modeAgeMs / 60_000}min < ${modeWindowMins}min)")
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
        val softLandingDepthMgdl     = (lowGuardMmol + 0.3) * 18.0 
        val bypassHour               = currentHour
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
                                 "iob=${String.format(java.util.Locale.ROOT, "%.2f", iobAtLowTime)}U (<1.0)")
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
            targetRespectEnabled     = true,
            reboundWindowMins        = preferences.get(IntKey.ApsSmartInsulinReboundWindowMins).toDouble(),
            circCeil                 = circAggrCeil,
            fuelTrimStrength         = circadianLearner.trimStrength,
            isMmol                   = isMmol
        )

        // Increment UAM entry SMB counter if an SMB was delivered this cycle
        val fractionUsed = uamSmbFraction  // capture before increment
        val wasEntrySmb = currentModeIsUam && apsResult.smb > 0.0 && uamEntrySmbsDelivered < entrySmbCount
        if (wasEntrySmb) {
            uamEntrySmbsDelivered++
            aapsLogger.debug(LTag.APS,
                             "SmartInsulin: UAM entry SMB ${uamEntrySmbsDelivered}/$entrySmbCount " +
                                 "at ${(fractionUsed * 100).toInt()}% fraction")
        }
        // Only show UAMEntry when this cycle actually delivered an entry-fraction SMB
        if (wasEntrySmb) {
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
        // Skip if activity is detected (insulin acts faster) or sensor is noisy.
        val trackerNoiseBlocked = glucoseStatus.noise > 1.5
        val trackerActivityBlocked = activityMonitor.level != ActivityMonitor.ActivityLevel.SEDENTARY
        if (learningEnabled && !trackerNoiseBlocked && !trackerActivityBlocked) {
            bolusCurveTracker.onLoopCycle(glucoseStatus, mealMode, iobArray)
        } else if (learningEnabled) {
            val trackerPauseReason = when {
                trackerNoiseBlocked -> "High noise (>1.5)"
                trackerActivityBlocked -> "Activity (${activityMonitor.level.label})"
                else -> "Blocked"
            }
            aapsLogger.debug(LTag.APS, "BolusCurveTracker: paused ($trackerPauseReason)")
        }

        // ── Meal phase tracker — detection only, no dosing influence yet ──────
        // Runs every cycle regardless of noise/activity gates — we want to track
        // the full meal shape even if BolusCurveTracker is paused.
        // Only skips when sensor is completely unreliable (warmup).
        if (!cgmInWarmup) {
            mealPhaseTracker.onLoopCycle(
                now           = now,
                mealMode      = mealMode,
                bgMmol        = glucoseStatus.glucose / 18.0,
                shortAvgDelta = glucoseStatus.shortAvgDelta / 18.0,
                delta         = glucoseStatus.delta / 18.0,
                targetBgMmol  = profile.getTargetMgdl() / 18.0,
                lowGuardMmol  = spMgdl(UnitDoubleKey.ApsSmartInsulinLowGuard) / 18.0
            )
        }



        // Append per-cycle learner summary to reason — visible in Loop tab
        // Format: circ(ISF×1.00 bas×1.00 ceil=0.85) basal×1.02 aggr=0.92/1.10
        val circHour = currentHour
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

        // Append mode time remaining if an override is active
        val modeRemainingMs = mealOverrideManager.modeTimeRemainingMs
        if (modeRemainingMs > 0L) {
            val modeRemainingMins = modeRemainingMs / 60_000
            apsResult.reason += " | ${modeRemainingMins}min left"
        }

        aapsLogger.debug(LTag.APS, "SmartInsulin result: $apsResult")

        _overviewStateFlow.value = cachedOverviewState
        rxBus.send(EventOpenAPSUpdateGui())
    }

    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? =
        glucoseStatusCalculatorSMB.getGlucoseStatusData(allowOldData)

    override fun configuration(): JsonObject =
        JsonObject(emptyMap())
            .put(BooleanKey.ApsSmartInsulinEnableLearning, preferences)
            .put(UnitDoubleKey.ApsSmartInsulinLowGuard, preferences)
            .put(UnitDoubleKey.ApsSmartInsulinWarnGuard, preferences)

    override fun applyConfiguration(configuration: JsonObject) {
        configuration
            .store(BooleanKey.ApsSmartInsulinEnableLearning, preferences)
            .store(UnitDoubleKey.ApsSmartInsulinLowGuard, preferences)
            .store(UnitDoubleKey.ApsSmartInsulinWarnGuard, preferences)
    }

    override suspend fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
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

    override suspend fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (!preferences.get(BooleanKey.ApsUseSmb))
            value.set(false, rh.gs(R.string.smb_disabled_in_preferences), this)
        return value
    }

    override fun isUAMEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (!preferences.get(BooleanKey.ApsUseUam))
            value.set(false, rh.gs(R.string.uam_disabled_in_preferences), this)
        return value
    }

    /** Opens SmartInsulin preferences directly via PreferencesActivity,
     *  bypassing PluginPreferencesScreen which requires PreferenceSubScreenDef. */
    fun openPreferences(context: Context) {
        val intent = Intent()
            .setClassName(context, "app.aaps.activities.PreferencesActivity")
            .setAction("info.nightscout.androidaps.MainActivity")
            .putExtra("PluginName", javaClass.simpleName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "smart_insulin_settings",
        titleResId = R.string.smart_insulin,
        items = listOf(
            BooleanKey.ApsUseSmb,
            PreferenceSubScreenDef(
                key = "si_screen_general",
                titleResId = R.string.si_screen_general_title,
                items = listOf(
                    BooleanKey.ApsUseSmb,
                    BooleanKey.ApsUseSmbAlways,
                    BooleanKey.ApsUseSmbWithCob,
                    BooleanKey.ApsUseSmbAfterCarbs,
                    DoubleKey.ApsSmbMaxIob,
                    DoubleKey.ApsMaxBasal,
                    IntKey.ApsMaxSmbFrequency,
                    DoubleKey.ApsSmartInsulinMaxSmb,
                    DoubleKey.ApsSmartInsulinMaxTbr,
                    DoubleKey.ApsSmartInsulinAggressionMax,
                    UnitDoubleKey.ApsLgsThreshold,
                    UnitDoubleKey.ApsSmartInsulinLowGuard,
                    UnitDoubleKey.ApsSmartInsulinWarnGuard,
                    IntKey.ApsSmartInsulinReboundWindowMins
                )
            ),
            PreferenceSubScreenDef(
                key = "si_screen_learning",
                titleResId = R.string.si_screen_learning_title,
                items = listOf(
                    BooleanKey.ApsSmartInsulinEnableLearning,
                    DoubleKey.ApsSmartInsulinIsfAlpha,
                    DoubleKey.ApsSmartInsulinBasalAlpha,
                    BooleanKey.ApsSmartInsulinBasalLearningEnabled,
                    IntKey.ApsSmartInsulinPostModeLockoutMins
                )
            ),
            PreferenceSubScreenDef(
                key = "si_screen_dawn",
                titleResId = R.string.si_screen_dawn_title,
                items = listOf(
                    IntKey.ApsSmartInsulinDawnWindowStartHour,
                    IntKey.ApsSmartInsulinDawnWindowEndHour,
                    DoubleKey.ApsSmartInsulinDawnSmbReduction
                )
            ),
            PreferenceSubScreenDef(
                key = "si_screen_activity",
                titleResId = R.string.si_screen_activity_title,
                items = listOf(
                    BooleanKey.ApsSmartInsulinActivityTargetEnabled,
                    DoubleKey.ApsSmartInsulinRestingHrBpm,
                    UnitDoubleKey.ApsSmartInsulinActivityLightTarget,
                    UnitDoubleKey.ApsSmartInsulinActivityModerateTarget,
                    UnitDoubleKey.ApsSmartInsulinActivityHeavyTarget
                )
            ),
            PreferenceSubScreenDef(
                key = "si_screen_meal",
                titleResId = R.string.si_screen_meal_title,
                items = listOf(
                    UnitDoubleKey.ApsSmartInsulinBreakfastIsf,
                    UnitDoubleKey.ApsSmartInsulinLunchIsf,
                    UnitDoubleKey.ApsSmartInsulinDinnerIsf,
                    UnitDoubleKey.ApsSmartInsulinLowCarbIsf,
                    UnitDoubleKey.ApsSmartInsulinExtendedIsf,
                    IntKey.ApsSmartInsulinBreakfastCarbsG,
                    IntKey.ApsSmartInsulinLunchCarbsG,
                    IntKey.ApsSmartInsulinDinnerCarbsG,
                    IntKey.ApsSmartInsulinModeWindowMins,
                    DoubleKey.ApsSmartInsulinMaxPreBolus,
                    DoubleKey.ApsSmartInsulinPreBolus2DefaultU,
                    IntKey.ApsSmartInsulinPreBolus2DefaultDelayMins
                )
            ),
            PreferenceSubScreenDef(
                key = "si_screen_stft",
                titleResId = R.string.si_screen_stft_title,
                items = listOf(
                    BooleanKey.ApsSmartInsulinStftCgmWarmupBlock
                )
            ),
            PreferenceSubScreenDef(
                key = "si_screen_first_day_cgm",
                titleResId = R.string.si_screen_first_day_cgm_title,
                items = listOf(
                    BooleanKey.ApsSmartInsulinFirstDayCgmSmoothing,
                    BooleanKey.ApsSmartInsulinCgmWarmupEnabled,
                    BooleanKey.ApsSmartInsulinUamCgmWarmupBlock
                )
            ),
            PreferenceSubScreenDef(
                key = "si_screen_uam",
                titleResId = R.string.si_screen_uam_title,
                items = listOf(
                    BooleanKey.ApsSmartInsulinUamEnabled,
                    BooleanKey.ApsSmartInsulinUamWobbleTolerance,
                    UnitDoubleKey.ApsSmartInsulinUamTriggerThreshold,
                    UnitDoubleKey.ApsSmartInsulinUamRiseMinDelta,
                    IntKey.ApsSmartInsulinUamRiseConsecutiveReadings,
                    UnitDoubleKey.ApsSmartInsulinUamBurstThreshold,
                    IntKey.ApsSmartInsulinUamDayStartHour,
                    IntKey.ApsSmartInsulinUamNightCutoffHour,
                    DoubleKey.ApsSmartInsulinUamEntrySmbFraction,
                    IntKey.ApsSmartInsulinUamEntrySmbCount
                )
            ),
            PreferenceSubScreenDef(
                key = "si_screen_uam_windows",
                titleResId = R.string.si_screen_uam_windows_title,
                items = listOf(
                    BooleanKey.ApsSmartInsulinUamBreakfastEnabled,
                    IntKey.ApsSmartInsulinUamBreakfastStartHour,
                    IntKey.ApsSmartInsulinUamBreakfastEndHour,
                    IntKey.ApsSmartInsulinUamBreakfastDurationMins,
                    UnitDoubleKey.ApsSmartInsulinUamBreakfastIsf,
                    BooleanKey.ApsSmartInsulinUamLunchEnabled,
                    IntKey.ApsSmartInsulinUamLunchStartHour,
                    IntKey.ApsSmartInsulinUamLunchEndHour,
                    IntKey.ApsSmartInsulinUamLunchDurationMins,
                    UnitDoubleKey.ApsSmartInsulinUamLunchIsf,
                    BooleanKey.ApsSmartInsulinUamAfternoonEnabled,
                    IntKey.ApsSmartInsulinUamAfternoonStartHour,
                    IntKey.ApsSmartInsulinUamAfternoonEndHour,
                    IntKey.ApsSmartInsulinUamAfternoonDurationMins,
                    UnitDoubleKey.ApsSmartInsulinUamAfternoonIsf,
                    BooleanKey.ApsSmartInsulinUamDinnerEnabled,
                    IntKey.ApsSmartInsulinUamDinnerStartHour,
                    IntKey.ApsSmartInsulinUamDinnerEndHour,
                    IntKey.ApsSmartInsulinUamDinnerDurationMins,
                    UnitDoubleKey.ApsSmartInsulinUamDinnerIsf,
                    BooleanKey.ApsSmartInsulinUamSnackEnabled,
                    IntKey.ApsSmartInsulinUamSnackStartHour,
                    IntKey.ApsSmartInsulinUamSnackEndHour,
                    IntKey.ApsSmartInsulinUamSnackDurationMins,
                    UnitDoubleKey.ApsSmartInsulinUamSnackIsf,
                    BooleanKey.ApsSmartInsulinUamProteinFatEnabled,
                    IntKey.ApsSmartInsulinUamProteinFatDurationMins,
                    IntKey.ApsSmartInsulinUamProteinFatStuckReadings,
                    UnitDoubleKey.ApsSmartInsulinUamProteinFatThreshold,
                    UnitDoubleKey.ApsSmartInsulinUamProteinFatIsf,
                    UnitDoubleKey.ApsSmartInsulinUamProteinFatDayIsf,
                    IntKey.ApsSmartInsulinUamProteinFatDayStartHour,
                    IntKey.ApsSmartInsulinUamProteinFatDayEndHour,
                    UnitDoubleKey.ApsSmartInsulinUamProteinFatNightIsf,
                    IntKey.ApsSmartInsulinUamProteinFatNightStartHour,
                    IntKey.ApsSmartInsulinUamProteinFatNightEndHour,
                    UnitDoubleKey.ApsSmartInsulinUamProteinFatOvernightIsf,
                    IntKey.ApsSmartInsulinUamProteinFatOvernightStartHour,
                    IntKey.ApsSmartInsulinUamProteinFatOvernightEndHour                )
            )
        ),
        icon = pluginDescription.icon
    )
}