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
import app.aaps.plugins.aps.smartInsulin.ice.IceTracker
import app.aaps.plugins.aps.smartInsulin.ice.IceDisableReason
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
    private val pdpLearner: PdpLearner,
    private val aggressionLearner: AggressionLearner,
    private val basalLearner: BasalLearner,
    private val circadianLearner: CircadianLearner,
    private val activityMonitor:  ActivityMonitor,
    private val cgmWarmupGuard:   CgmWarmupGuard,
    private val iceTracker:       IceTracker,
    private val announcedMealManager: app.aaps.plugins.aps.smartInsulin.ice.AnnouncedMealManager,
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

    // -- Suspend / rebound tracking --------------------------------------------
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
    // INACTIVE/PAUSED ? any active state (ACTIVE_HIGH, ACTIVE_LOW, TRIM). Captures the
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
    // Cached ICE aggression adjust — set during invoke(), read during fragmentData().
    // Defaults to 1.0 (no influence) until the first cycle has run.
    @Volatile private var lastIceAggrAdjust: Double = 1.0
    var previousMealModeForLockout: MealMode = MealMode.FASTING  // tracks transitions
    private var lockoutTrackerInitialized: Boolean = false        // prevents fake transition on first loop
    var reboundWindowStartMs: Long = 0L          // set ONLY when BG crosses back above lowGuard — NOT during suspend
    @Volatile var reboundGuardMs: Long = REBOUND_GUARD_MS  // updated each invoke() from preferences
    val msSinceLastSuspend: Long get() = if (reboundWindowStartMs > 0L) System.currentTimeMillis() - reboundWindowStartMs else Long.MAX_VALUE
    val inReboundWindow: Boolean get() = reboundWindowStartMs > 0L &&
        bgWentLow &&
        msSinceLastSuspend < reboundGuardMs

    // -- PDP (Persistent Deviation Prediction) state -----------------------------
    // Tracks consecutive cycles where ci (unexplained BG deviation) is positive and above
    // threshold — used to compute blend weight toward the secondary prediction curve.
    private var consecutivePosCiReadings: Int    = 0
    private var pdpStuckHighReadings:     Int    = 0   // consecutive cycles BG stuck above target
    private var lastCiMgdl:              Double  = 0.0   // last ci seen (mg/dL per 5min)
    // Snapshot of last cycle's t+5min predictions for accuracy scoring on next cycle.
    // Set after each determine_basal() call; compared against actual BG next cycle.
    private var lastCycleHour:           Int     = 0
    private var lastPdpBlendActive:      Boolean = false
    @Volatile private var cachedPdpBlendWeight:    Double  = 0.0   // last computed blend weight — for statusSummary display
    @Volatile private var cachedPdpSyntheticCi:    Double  = 0.0   // last synthetic ci mg/dL — for display

    // -- PDP meal-stuck state --------------------------------------------------
    // When in meal/UAM mode and BG is stuck high (not correcting), meal-PDP progressively
    // strengthens the effective ISF — starting at the meal mode's configured ISF and
    // pulling stronger the longer BG remains stuck.
    // Counts consecutive qualifying readings during meal mode (flat+high, not yet falling).
    private var mealPdpStuckReadings:    Int    = 0
    // The effective ISF (mg/dL) currently being applied by meal-PDP. 0.0 = inactive.
    // Updated each invoke() when meal-PDP is active; reset to 0.0 when mode exits or BG improves.
    @Volatile private var cachedMealPdpIsf:      Double  = 0.0
    // Last meal mode label active when meal-PDP was computing — for display
    @Volatile private var cachedMealPdpModeLabel: String = ""
    // Tracks which meal mode was active last cycle — resets counter on mode switch
    private var lastMealPdpMode:         MealMode = MealMode.FASTING
    // Rolling 3-reading delta history for decline detection (mg/dL/5min, oldest?newest)
    // Reset alongside mealPdpStuckReadings whenever the counter resets.
    private val mealPdpDeltaHistory:     ArrayDeque<Double> = ArrayDeque(3)

    // -- PDP episode tracking --------------------------------------------------
    // An episode opens when pdpBlendWeight goes from 0 ? >0.
    // Tracks outcome metrics (nadir BG, duration) until episode closes.
    private data class PdpEpisode(
        val startTimeMs:     Long,
        val startHour:       Int,
        val startDow:        Int,    // day-of-week when episode opened (0=Sun..6=Sat)
        var pathway:         String,
        var nadirBgMmol:     Double,   // lowest BG seen during episode
        var peakBlendWeight: Double,   // highest blend weight used
        val openedClean:     Boolean = true  // was pdpCleanForBlending true when episode opened?
        // Episodes that opened clean are always allowed to close and score, even if
        // pdpCleanForBlending later drops transiently (e.g. lockout flag flip at episode edge).
        // Prevents silent discard of real stuck-high episodes near post-meal windows.
    )
    private var activeEpisode: PdpEpisode? = null

    // -- PB2 gate snapshot — updated each invoke() for fragment display --------
    @Volatile var pb2LastBgMgdl:            Double = 0.0
    @Volatile var pb2LastDeltaMgdl:         Double = 0.0
    @Volatile var pb2LastShortAvgDeltaMgdl: Double = 0.0
    @Volatile var pb2LastIobU:              Double = 0.0
    @Volatile var pb2LastMaxIobU:           Double = 0.0
    @Volatile var pb2ProfileTargetMgdl:     Double = 0.0
    @Volatile private var pb1Notified: Boolean = false
    @Volatile private var pb2Notified: Boolean = false
    @Volatile private var pb3Notified: Boolean = false
    // -- PB3 gate snapshot — identical fields, updated separately each invoke() -
    @Volatile var pb3LastBgMgdl:            Double = 0.0
    @Volatile var pb3LastDeltaMgdl:         Double = 0.0
    @Volatile var pb3LastShortAvgDeltaMgdl: Double = 0.0
    @Volatile var pb3LastIobU:              Double = 0.0
    @Volatile var pb3LastMaxIobU:           Double = 0.0
    @Volatile var pb3ProfileTargetMgdl:     Double = 0.0

    // -- HbA1c estimation — computed fresh each fragmentData() call from DB ---
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

    // -- Unit-aware display helpers --------------------------------------------
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

    // -- Cached Overview state ------------------------------------------------
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

    // -- Reset all learners ----------------------------------------------------

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
        pdpLearner.reset()
        consecutivePosCiReadings = 0
        pdpStuckHighReadings     = 0
        lastCiMgdl               = 0.0
        cachedPdpSyntheticCi     = 0.0
        activeEpisode             = null
        lastPdpBlendActive       = false
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

    /**
     * Reset PDP learner and seed strengthMult.
     * Also resets the live PDP tracking state (streak counters, episode) so
     * the system starts fresh without any stale stuck-high or rising state.
     *
     * [seedStrengthMult] seeds the per-hour strengthMult after reset.
     * 1.0 = neutral start (effectiveCiStrength = baseCiStrength as confidence builds).
     * 0.5 = conservative start (effectiveCiStrength = baseCiStrength × 0.5 at full confidence).
     */
    fun resetPdp(seedStrengthMult: Double = 1.0) {
        pdpLearner.resetWithSeed(seedStrengthMult)
        consecutivePosCiReadings = 0
        pdpStuckHighReadings     = 0
        lastCiMgdl               = 0.0
        cachedPdpSyntheticCi     = 0.0
        cachedPdpBlendWeight     = 0.0
        activeEpisode            = null
        lastPdpBlendActive       = false
        mealPdpStuckReadings     = 0
        cachedMealPdpIsf         = 0.0
        cachedMealPdpModeLabel   = ""
        lastMealPdpMode          = MealMode.FASTING
        mealPdpDeltaHistory.clear()
        aapsLogger.debug(LTag.APS, "SmartInsulinPlugin: PDP learner reset (seed=${"%.2f".format(seedStrengthMult)})")
    }

    // ── Announced meal API ────────────────────────────────────────────────────
    // Public entry points for SmartMealDialog (or test/debug UI) to declare meals.
    // The plugin reads from announcedMealManager.expectedIceMgdlPerHour() each cycle
    // and blends with observed ICE — see the ICE block in invoke() for the blending rule.

    /**
     * Announce a meal to the loop. Replaces any active announced meal.
     *
     * Interface signature uses String for giBucketName so the UI module (and tests)
     * don't have to depend on the plugin module's GiBucket enum. Accepted values:
     * "FAST" (high GI / juice/candy), "MEDIUM" (default / bread/rice/pasta),
     * "SLOW" (low GI / pizza/fatty/large). Anything else falls back to MEDIUM.
     */
    override fun announceMeal(
        carbsG: Double,
        proteinG: Double,
        fatG: Double,
        giBucketName: String,
        commitmentPct: Int,
        replaceExisting: Boolean
    ) {
        val bucket = when (giBucketName.uppercase()) {
            "FAST" -> app.aaps.plugins.aps.smartInsulin.ice.GiBucket.FAST
            "SLOW" -> app.aaps.plugins.aps.smartInsulin.ice.GiBucket.SLOW
            else   -> app.aaps.plugins.aps.smartInsulin.ice.GiBucket.MEDIUM
        }
        // Generate a unique announce timestamp — bump by +1ms per existing
        // layer to guarantee no two layers ever share the same ID even if the
        // user taps rapidly. The cost (timestamps off by a few ms) is far
        // smaller than CGM cadence (5min) and doesn't affect any calculation.
        val baseTs = dateUtil.now()
        val existingTs = announcedMealManager.activeMeals.value.map { it.announceTimestampMs }.toSet()
        var ts = baseTs
        while (ts in existingTs) ts++
        val meal = app.aaps.plugins.aps.smartInsulin.ice.AnnouncedMeal(
            carbsG              = carbsG,
            proteinG            = proteinG,
            fatG                = fatG,
            giBucket            = bucket,
            commitmentPct       = commitmentPct.coerceIn(0, 100),
            announceTimestampMs = ts
        )
        announcedMealManager.announceMeal(meal, replace = replaceExisting)
        pushOverviewMealLayerUpdate()
    }

    /** Clear all active announced meal layers. */
    override fun clearAnnouncedMeal() {
        announcedMealManager.clearAllMeals()
        pushOverviewMealLayerUpdate()
    }

    /** Clear a single layer by its layer ID (announce timestamp ms). */
    override fun clearMealLayer(layerId: Long) {
        announcedMealManager.clearLayer(layerId)
        pushOverviewMealLayerUpdate()
    }

    /**
     * Edit the macros / GI of the currently-active announced meal in place.
     * Preserves the announce timestamp so absorption progress is not reset.
     * No-op when zero or multiple layers are active.
     */
    override fun editActiveMeal(
        carbsG: Double,
        proteinG: Double,
        fatG: Double,
        giBucketName: String
    ) {
        announcedMealManager.editActiveMeal(carbsG, proteinG, fatG, giBucketName)
        pushOverviewMealLayerUpdate()
    }

    /**
     * Edit a specific layer by its announce-timestamp ID. Preserves the
     * announce timestamp so absorption progress continues uninterrupted.
     * Used by SmartMealDialog's EDIT mode where the user picks a specific
     * layer to edit even when multiple are active.
     */
    override fun editMealLayer(
        layerId: Long,
        carbsG: Double,
        proteinG: Double,
        fatG: Double,
        giBucketName: String
    ) {
        announcedMealManager.editLayer(layerId, carbsG, proteinG, fatG, giBucketName)
        pushOverviewMealLayerUpdate()
    }

    // ── Active meal snapshot for OverviewState (ice-step27c) ─────────────────
    // Same per-layer info the home-screen card consumes via FragmentData,
    // returned in the interface-module type so SmartMealDialog (which can't
    // import the plugin type) can render and edit the list of active meals
    // via overviewStateFlow.
    //
    // Recomputed each invoke() cycle (so ages tick up) and also immediately
    // after every meal mutation (so the dialog sees changes without waiting
    // for the next 5-min loop cycle).

    private fun buildMealLayerInfoList(): List<app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview.MealLayerInfo> {
        val nowMs = dateUtil.now()
        return announcedMealManager.activeMeals.value
            .filter { it.isActive(nowMs) }
            .sortedBy { it.announceTimestampMs }
            .map { meal ->
                val ageMin = ((nowMs - meal.announceTimestampMs) / 60_000.0).coerceAtLeast(0.0)
                val totalDur = meal.effectiveTotalDurationMin
                val carbFracRem    = (1.0 - app.aaps.plugins.aps.smartInsulin.ice.MealCurveBuilder
                    .carbAbsorbedFraction(meal, ageMin)).coerceIn(0.0, 1.0)
                val plateauFracRem = (1.0 - app.aaps.plugins.aps.smartInsulin.ice.MealCurveBuilder
                    .plateauAbsorbedFraction(ageMin, meal.fatProteinDurationMin)).coerceIn(0.0, 1.0)
                app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview.MealLayerInfo(
                    layerId           = meal.announceTimestampMs,
                    giBucketName      = meal.giBucket.name,
                    giLabel           = when (meal.giBucket) {
                        app.aaps.plugins.aps.smartInsulin.ice.GiBucket.FAST   -> "High GI"
                        app.aaps.plugins.aps.smartInsulin.ice.GiBucket.MEDIUM -> "Medium GI"
                        app.aaps.plugins.aps.smartInsulin.ice.GiBucket.SLOW   -> "Low GI"
                    },
                    ageMin            = ageMin.toInt(),
                    remainingMin      = (totalDur - ageMin.toInt()).coerceAtLeast(0),
                    totalDurationMin  = totalDur,
                    totalCarbsG       = meal.carbsG,
                    totalProteinG     = meal.proteinG,
                    totalFatG         = meal.fatG,
                    remainingCarbsG   = meal.carbsG   * carbFracRem,
                    remainingProteinG = meal.proteinG * plateauFracRem,
                    remainingFatG     = meal.fatG     * plateauFracRem,
                    commitmentPct     = meal.commitmentPct
                )
            }
    }

    /**
     * Push the current active-meal snapshot into the cached OverviewState so
     * overviewStateFlow subscribers (the dialog) see meal mutations immediately
     * without waiting for the next invoke() cycle. The setter on
     * cachedOverviewState also pushes to the StateFlow.
     */
    private fun pushOverviewMealLayerUpdate() {
        cachedOverviewState = cachedOverviewState.copy(activeMealLayers = buildMealLayerInfoList())
    }




    // -- Status summary for tab UI ---------------------------------------------

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

            // -- Active cycle values -------------------------------------------
            appendLine("-- Active (Hour=${hour}:00, Day=$day) -----------------")
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
            val flatBasalMult427 = basalLearner.multiplierClamped
            val circBasalMult427 = circadianLearner.basalMultiplier(hour)
            val circConf427      = (circadianLearner.confidencePct(hour) / 100.0).coerceIn(0.0, 1.0)
            val basalMult        = (1.0 - circConf427) * flatBasalMult427 + circConf427 * circBasalMult427
            val learnedBas = if (profBasal > 0) "${"%.3f".format(profBasal * basalMult)} U/h" else "—"
            appendLine("  Basal: $learnedBas (×${"%.3f".format(basalMult)} flat=×${"%.3f".format(flatBasalMult427)} circ=×${"%.3f".format(circBasalMult427)} conf=${"%.0f".format(circConf427 * 100)}%)")
            appendLine("  ${aggressionLearner.tirSummary}")
            if (inReboundWindow) appendLine("  ?? REBOUND ACTIVE ${msSinceLastSuspend / 60_000}min elapsed")

            // -- Meal override / pre-bolus 2 status ---------------------------
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

            // -- STFT / UAM debug ----------------------------------------------
            appendLine()
            appendLine("-- STFT / UAM ------------------------")
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
                appendLine("  Post-meal lockout: ${minsLeft}min left — UAM? thresholds ON")
            } else {
                appendLine("  Post-meal lockout: none")
            }
            // Safety state
            if (inReboundWindow) {
                val bypassNote = if (softLandingBypass) " — SOFT LANDING BYPASS ACTIVE (UAM allowed)" else " — full lockout"
                appendLine("  ? Rebound: ${msSinceLastSuspend / 60_000}min elapsed$bypassNote")
            }
            if (bgWentLow && !inReboundWindow) appendLine("  ? Recent low: watching for recovery")
            if (bgWentLow && minBgDuringLow < Double.MAX_VALUE) {
                val iob = iobAtLowTime
                appendLine("  Low detail: minBG=${fmtBg(minBgDuringLow)}$unitLabel " +
                               "velAtLow=${fmtDelta(shortAvgDeltaAtLow)}$unitLabel " +
                               "iobAtLow=${String.format(java.util.Locale.ROOT, "%.2f", iob)}U " +
                               if (secondLowOccurred) "? SECOND LOW — full lockout" else "")
            }

            // -- Learning state ------------------------------------------------
            appendLine()
            appendLine("-- Learning --------------------------")
            val state = cachedOverviewState
            val learningDisplay = when (state.learningState) {
                "limited" -> "Limited due to meal mode - DIA/Peak only"
                else      -> state.learningState
            }
            appendLine("  Learning: $learningDisplay")

            // -- Activity -----------------------------------------------------
            val actLevel = activityMonitor.level
            appendLine("  Activity: ${actLevel.label} " +
                           "hr=${activityMonitor.avgHrBpm.toInt()}avg " +
                           "steps=${activityMonitor.lastSteps5min}/5m")
            appendLine()

            // -- PDP table ---------------------------------
            val pdpEnabledStatus = preferences.get(BooleanKey.ApsSmartInsulinPdpEnabled)
            if (pdpEnabledStatus) {
                val pdpBase = preferences.get(DoubleKey.ApsSmartInsulinPdpCiStrength)
                appendLine(pdpLearner.summaryTable(hour, pdpBase, preferences.get(IntKey.ApsSmartInsulinPdpFadeMinutes).toDouble()))
                appendLine("  PDP: blend=${"%.2f".format(cachedPdpBlendWeight)} ci=${"%.1f".format(lastCiMgdl)}mg/dL ci-readings=$consecutivePosCiReadings stuck-readings=$pdpStuckHighReadings")
                val ep = activeEpisode
                if (ep != null) {
                    val epMins = ((System.currentTimeMillis() - ep.startTimeMs) / 60_000.0).toInt()
                    appendLine("  Episode[${ep.pathway}]: ${epMins}min active, nadir=${"%.1f".format(ep.nadirBgMmol)}mmol, peakBlend=${"%.2f".format(ep.peakBlendWeight)}")
                }
                appendLine()
            }

            // -- Circadian tables ----------------------------------------------
            val isfUnit  = if (isMmolUnit) "mmol/U" else "mg/dL/U"
            appendLine("-- Circadian 24h ---------------------")
            appendLine("  Hr  ISF ($isfUnit)   Basal (U/h)  Ceil   Conf")
            for (h in 0..23) {
                val marker    = if (h == hour) "?" else " "
                val hIsfMult  = circadianLearner.isfMultiplier(h)
                val hFlatMult = basalLearner.multiplierClamped
                val hCircMult = circadianLearner.basalMultiplier(h)
                val hConf     = (circadianLearner.confidencePct(h) / 100.0).coerceIn(0.0, 1.0)
                val hBasMult  = (1.0 - hConf) * hFlatMult + hConf * hCircMult
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

            // -- Profiles ------------------------------------------------------
            // Use getEffectiveProfile: peak per-mode (learned), DIA always from FASTING.
            // This matches what dosing actually uses, so the display never disagrees with
            // the loop's behaviour.
            appendLine("-- Insulin Profiles ------------------")
            app.aaps.core.interfaces.smartInsulin.MealMode.entries.forEach { mode ->
                val p = profileLearner.getEffectiveProfile(mode)
                appendLine("  ${mode.label.padEnd(10)}: peak=${p.peakMinutes.toInt()}m  dia=${p.diaMinutes.toInt()}m  n=${p.sampleCount}")
            }
        }.trimEnd()
    }

    // -- Structured data for fragment cards -----------------------------------

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

    /**
     * Per-layer snapshot of an announced meal. Used by the home-screen
     * ActiveMealCard to render one row per active meal, with remaining macros
     * computed from the absorption-fraction helpers on [MealCurveBuilder].
     *
     * @property layerId           announceTimestampMs — pass to [clearMealLayer] /
     *                             [editMealLayer] to identify which layer to act on
     * @property giBucketName      "FAST" / "MEDIUM" / "SLOW" — raw enum name
     * @property giLabel           User-facing GI label, e.g. "Medium GI"
     * @property ageMin            Minutes since this layer was announced
     * @property remainingMin      Minutes left in the absorption window
     * @property totalDurationMin  Full absorption window from announce to expiry
     * @property totalCarbsG       Carbs originally entered (or after edit) for this layer
     * @property totalProteinG     Protein originally entered (or after edit)
     * @property totalFatG         Fat originally entered (or after edit)
     * @property remainingCarbsG   Carbs not yet absorbed (Gaussian CDF over carb window)
     * @property remainingProteinG Protein not yet "absorbed" (cosine-smoothed plateau)
     * @property remainingFatG     Fat not yet "absorbed" (same plateau shape, separate grams)
     * @property commitmentPct     0–100, user's stated confidence in the macros
     */
    data class ActiveMealLayer(
        val layerId:           Long,
        val giBucketName:      String,
        val giLabel:           String,
        val ageMin:            Int,
        val remainingMin:      Int,
        val totalDurationMin:  Int,
        val totalCarbsG:       Double,
        val totalProteinG:     Double,
        val totalFatG:         Double,
        val remainingCarbsG:   Double,
        val remainingProteinG: Double,
        val remainingFatG:     Double,
        val commitmentPct:     Int
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
        val pdpEnabled:            Boolean,
        val pdpBlendWeight:        Double,
        val pdpCiMgdl:             Double,
        val pdpConsecutiveReadings: Int,    // ci-based pathway counter
        val pdpStuckHighReadings:   Int,    // stuck-high pathway counter
        val pdpActivePathway:       String, // "rising", "stuck", or "none"
        val pdpSyntheticCiMmol:     Double, // synthetic ci in mmol (0 when rising pathway or inactive)
        val pdpMinReadings:        Int,    // stuck-high min readings threshold
        val pdpFadeMins:           Int,
        val pdpEffectiveFadeMins:  Double, // learned effective fade for current hour
        val pdpCiStrength:         Double,
        val pdpRisingStrength:     Double, // rising pathway ci scaling (0.5-1.5)
        val pdpMaxBlend:           Double,
        val pdpFastingMaxIob:      Double,
        val pdpHourlyStrengths:    List<Double>,
        val pdpHourlyConfidences:  List<Double>,
        val pdpHourlySamples:      List<Int>,
        val pdpHourlyBlendMults:   List<Double>,   // learned blend mult per hour (24)
        val pdpHourlyFadeMults:    List<Double>,   // learned fade mult per hour (24)
        val pdpLearningEnabled:    Boolean,
        // -- Meal-PDP stuck-high state -----------------------------------------
        val pdpMealStuckEnabled:   Boolean,  // user setting: meal-PDP feature on/off
        val pdpMealStuckReadings:  Int,      // consecutive qualifying readings in meal mode
        val pdpMealStuckMinReadings: Int,    // threshold before ISF starts ramping
        val pdpMealCurrentIsfMgdl: Double,  // active meal-PDP ISF (0.0 = inactive)
        val pdpMealBaseIsfMgdl:    Double,  // starting ISF (= mode ISF) when meal-PDP activated
        val pdpMealMaxStrength:    Double,  // max strength multiplier (e.g. 2.0 = up to 2× more aggressive)
        val pdpMealRampMins:       Int,     // minutes to reach max strength from base ISF
        val pdpMealModeLabel:      String,  // meal mode label when meal-PDP is active
        val pdpIsFasting:          Boolean, // true when loop is in fasting mode (for mode display)
        // -- ICE (Insulin Counteraction Effect) ---------------------------------
        val iceEnabled:            Boolean,     // user pref toggle on/off
        val iceMmolPerHour:        Double?,     // current ICE in mmol/h; null if no signal
        val iceConfidenceScore:    Double,      // composite 0.0–1.0
        val iceMagnitude:          Double,      // sub-score: signal strength relative to floor/strong
        val icePersistence:        Double,      // sub-score: how many consecutive cycles agree
        val iceConsistency:        Double,      // sub-score: inverse of recent variance
        val iceCgmQuality:         Double,      // sub-score: 1.0 normally, 0.0 during warmup/quality issues
        val iceDisableReason:      String,      // empty when ICE is active; reason label when disabled
        val iceIsDriving:          Boolean,     // confidence ≥ learning-block threshold AND not disabled
        val iceAggrAdjust:         Double,      // current aggression multiplier from ICE (1.0 = neutral)
        val iceLearningBlocked:    Boolean,     // ICE is causing ISF/basal learners to pause
        val icePdpOverridden:      Boolean,     // PDP is forced off because ICE is driving
        val iceRecentMmol:         List<Double?>,  // last ~3h of ICE values (mmol/h) for sparkline
        // -- Active announced meal (for the ActiveMealCard edit/cancel UI) -----
        val isMealActive:          Boolean,        // true when an announced meal is in progress
        val activeMealCarbsTotalG: Double,         // total grams as currently announced (post-edits)
        val activeMealProteinTotalG: Double,
        val activeMealFatTotalG:   Double,
        val activeMealGiBucketName: String,        // "FAST" / "MEDIUM" / "SLOW", empty when none
        val activeMealAgeMinutes:  Int,            // minutes since announcement
        val activeMealTotalDurationMin: Int,       // expected total absorption window
        // Per-layer snapshot for the multi-meal home-screen list (ice-step27a).
        // Empty when no meals are active.
        val activeMealLayers:      List<ActiveMealLayer> = emptyList(),
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
        // Match the production blend formula (see ~line 1709). The screen's "current basal"
        // reading must equal what's actually delivered, or the user can't reconcile dosing.
        val flatBasalMult672 = basalLearner.multiplierClamped
        val circBasalMult672 = circadianLearner.basalMultiplier(hour)
        val circConf672      = (circadianLearner.confidencePct(hour) / 100.0).coerceIn(0.0, 1.0)
        val basalMult        = (1.0 - circConf672) * flatBasalMult672 + circConf672 * circBasalMult672
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
                val marker    = if (h == hour) "?" else " "
                val isfMult   = circadianLearner.isfMultiplier(h)
                val hFlatMult = basalLearner.multiplierClamped
                val hCircMult = circadianLearner.basalMultiplier(h)
                val hConf     = (circadianLearner.confidencePct(h) / 100.0).coerceIn(0.0, 1.0)
                val basalMult = (1.0 - hConf) * hFlatMult + hConf * hCircMult
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
            // Use getEffectiveProfile so the displayed DIA matches what dosing actually uses
            // (FASTING-sourced) — see ProfileLearner.getEffectiveProfile doc.
            app.aaps.core.interfaces.smartInsulin.MealMode.entries.forEach { mode ->
                val p = profileLearner.getEffectiveProfile(mode)
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
            fuelTrimStrength     = circadianLearner.trimStrength,
            // -- PDP -----------------------------------------------------------
            pdpEnabled           = preferences.get(BooleanKey.ApsSmartInsulinPdpEnabled),
            pdpBlendWeight       = cachedPdpBlendWeight,
            pdpCiMgdl            = lastCiMgdl,
            pdpConsecutiveReadings = consecutivePosCiReadings,
            pdpStuckHighReadings   = pdpStuckHighReadings,
            pdpActivePathway       = if (cachedPdpBlendWeight > 0.0) (if (pdpStuckHighReadings >= consecutivePosCiReadings) "stuck" else "rising") else "none",
            pdpSyntheticCiMmol     = cachedPdpSyntheticCi / 18.0,
            pdpMinReadings       = preferences.get(IntKey.ApsSmartInsulinPdpMinReadings), // stuck threshold only
            pdpFadeMins          = preferences.get(IntKey.ApsSmartInsulinPdpFadeMinutes),
            pdpEffectiveFadeMins = pdpLearner.effectiveFadeMins(hour, preferences.get(IntKey.ApsSmartInsulinPdpFadeMinutes).toDouble()),
            pdpCiStrength        = preferences.get(DoubleKey.ApsSmartInsulinPdpCiStrength),
            pdpRisingStrength    = preferences.get(DoubleKey.ApsSmartInsulinPdpRisingStrength),
            pdpMaxBlend          = preferences.get(DoubleKey.ApsSmartInsulinPdpMaxBlendWeight),
            pdpFastingMaxIob     = preferences.get(DoubleKey.ApsSmartInsulinFastingMaxIob),
            pdpHourlyStrengths   = (0..23).map { h -> pdpLearner.strengthMultAt(h) },
            pdpHourlyConfidences = (0..23).map { h -> pdpLearner.confidenceAt(h) },
            pdpHourlySamples     = (0..23).map { h -> pdpLearner.samplesAt(h) },
            pdpHourlyBlendMults  = (0..23).map { h -> pdpLearner.effectiveBlendMult(h) },
            pdpHourlyFadeMults   = (0..23).map { h -> pdpLearner.fadeMultAt(h) },
            pdpLearningEnabled   = preferences.get(BooleanKey.ApsSmartInsulinPdpLearningEnabled),
            // -- Meal-PDP ------------------------------------------------------
            pdpMealStuckEnabled   = preferences.get(BooleanKey.ApsSmartInsulinPdpMealStuckEnabled),
            pdpMealStuckReadings  = mealPdpStuckReadings,
            pdpMealStuckMinReadings = preferences.get(IntKey.ApsSmartInsulinPdpMealStuckMinReadings),
            pdpMealCurrentIsfMgdl = cachedMealPdpIsf,
            pdpMealBaseIsfMgdl    = 0.0,  // base ISF is the mode ISF at activation; not separately cached (display-only)
            pdpMealMaxStrength    = preferences.get(DoubleKey.ApsSmartInsulinPdpMealMaxStrength),
            pdpMealRampMins       = preferences.get(IntKey.ApsSmartInsulinPdpMealRampMins),
            pdpMealModeLabel      = cachedMealPdpModeLabel,
            pdpIsFasting          = currentMealMode == MealMode.FASTING,
            // -- ICE (read from singleton tracker; populated by the loop cycle) ----
            iceEnabled            = preferences.get(BooleanKey.ApsSmartInsulinIceEnabled),
            iceMmolPerHour        = iceTracker.snapshot.value?.currentIceMmolH,
            iceConfidenceScore    = iceTracker.snapshot.value?.confidence?.score ?: 0.0,
            iceMagnitude          = iceTracker.snapshot.value?.confidence?.magnitude ?: 0.0,
            icePersistence        = iceTracker.snapshot.value?.confidence?.persistence ?: 0.0,
            iceConsistency        = iceTracker.snapshot.value?.confidence?.consistency ?: 0.0,
            iceCgmQuality         = iceTracker.snapshot.value?.confidence?.cgmQuality ?: 1.0,
            iceDisableReason      = iceTracker.snapshot.value?.confidence?.disabled?.displayLabel ?: "",
            iceIsDriving          = run {
                val snap = iceTracker.snapshot.value
                val threshold = preferences.get(DoubleKey.ApsSmartInsulinIceLearningBlockThreshold)
                preferences.get(BooleanKey.ApsSmartInsulinIceEnabled)
                    && snap?.confidence?.disabled == null
                    && (snap?.confidence?.score ?: 0.0) >= threshold
            },
            iceAggrAdjust         = lastIceAggrAdjust,
            iceLearningBlocked    = run {
                val snap = iceTracker.snapshot.value
                val threshold = preferences.get(DoubleKey.ApsSmartInsulinIceLearningBlockThreshold)
                preferences.get(BooleanKey.ApsSmartInsulinIceEnabled)
                    && snap?.confidence?.disabled == null
                    && (snap?.confidence?.score ?: 0.0) >= threshold
            },
            icePdpOverridden      = run {
                val snap = iceTracker.snapshot.value
                val threshold = preferences.get(DoubleKey.ApsSmartInsulinIceLearningBlockThreshold)
                val driving = preferences.get(BooleanKey.ApsSmartInsulinIceEnabled)
                    && snap?.confidence?.disabled == null
                    && (snap?.confidence?.score ?: 0.0) >= threshold
                driving && preferences.get(BooleanKey.ApsSmartInsulinPdpEnabled)
            },
            iceRecentMmol         = iceTracker.snapshot.value?.recentHistory?.map { it.iceMmolPerHour } ?: emptyList(),
            // Meal data — aggregated across all active layers.
            // Macros are summed over the announced TOTALS (not remaining) for the
            // UI status card. The home-screen "COB / P / F" widget shows remaining
            // via macrosOverviewSuffix; these fields are the announced totals.
            isMealActive             = announcedMealManager.hasActiveMeals(dateUtil.now()),
            activeMealCarbsTotalG    = announcedMealManager.activeMeals.value.sumOf { it.carbsG },
            activeMealProteinTotalG  = announcedMealManager.activeMeals.value.sumOf { it.proteinG },
            activeMealFatTotalG      = announcedMealManager.activeMeals.value.sumOf { it.fatG },
            activeMealGiBucketName   = announcedMealManager.aggregatedGiBucketLabel(dateUtil.now()),
            activeMealAgeMinutes     = announcedMealManager.earliestAgeMinutes(dateUtil.now()),
            activeMealTotalDurationMin = announcedMealManager.longestRemainingMinutes(dateUtil.now()) +
                announcedMealManager.earliestAgeMinutes(dateUtil.now()),
            // Per-layer snapshot for the multi-meal home-screen list. Each entry is
            // self-contained: layerId for delete/edit dispatch, plus remaining grams
            // computed from MealCurveBuilder's absorption-fraction helpers (Gaussian
            // CDF for carbs, cosine-smoothed plateau for protein/fat).
            activeMealLayers = run {
                val nowMs = dateUtil.now()
                announcedMealManager.activeMeals.value
                    .filter { it.isActive(nowMs) }
                    .sortedBy { it.announceTimestampMs }
                    .map { meal ->
                        val ageMin = ((nowMs - meal.announceTimestampMs) / 60_000.0).coerceAtLeast(0.0)
                        val totalDur = meal.effectiveTotalDurationMin
                        val carbFracRem    = (1.0 - app.aaps.plugins.aps.smartInsulin.ice.MealCurveBuilder
                            .carbAbsorbedFraction(meal, ageMin)).coerceIn(0.0, 1.0)
                        val plateauFracRem = (1.0 - app.aaps.plugins.aps.smartInsulin.ice.MealCurveBuilder
                            .plateauAbsorbedFraction(ageMin, meal.fatProteinDurationMin)).coerceIn(0.0, 1.0)
                        ActiveMealLayer(
                            layerId           = meal.announceTimestampMs,
                            giBucketName      = meal.giBucket.name,
                            giLabel           = when (meal.giBucket) {
                                app.aaps.plugins.aps.smartInsulin.ice.GiBucket.FAST   -> "High GI"
                                app.aaps.plugins.aps.smartInsulin.ice.GiBucket.MEDIUM -> "Medium GI"
                                app.aaps.plugins.aps.smartInsulin.ice.GiBucket.SLOW   -> "Low GI"
                            },
                            ageMin            = ageMin.toInt(),
                            remainingMin      = (totalDur - ageMin.toInt()).coerceAtLeast(0),
                            totalDurationMin  = totalDur,
                            totalCarbsG       = meal.carbsG,
                            totalProteinG     = meal.proteinG,
                            totalFatG         = meal.fatG,
                            remainingCarbsG   = meal.carbsG   * carbFracRem,
                            remainingProteinG = meal.proteinG * plateauFracRem,
                            remainingFatG     = meal.fatG     * plateauFracRem,
                            commitmentPct     = meal.commitmentPct
                        )
                    }
            },
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
                val marker   = if (dow == currentDow && h == currentHour) "?" else " "
                val hIsfMult = circadianLearner.isfMultiplier(h, dow)
                val hFlatMult = basalLearner.multiplierClamped
                val hCircMult = circadianLearner.basalMultiplier(h, dow)
                val hConf     = (circadianLearner.confidencePct(h, dow) / 100.0).coerceIn(0.0, 1.0)
                val hBasMult = (1.0 - hConf) * hFlatMult + hConf * hCircMult
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

    // -- Public state accessors for Overview display -------------------------

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

        val baseLiveModeLine = activeMode?.let { mode ->
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
        // Live append — macros suffix recomputed each call against current time so
        // the main screen shows fresh remaining-grams between loop cycles.
        val liveModeLine = baseLiveModeLine + announcedMealManager.macrosOverviewSuffix(now)

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

    // -- RxBus subscriptions for HR and steps from wear -----------------------
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
     * Once AdaptiveUnitPreference has written the correct mg/dL value, raw will be = threshold
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

        // -- Meal mode — check override first, fall back to auto-detect --------
        var mealMode = MealModeDetector.detect(overrideManager = mealOverrideManager)

        // -- UAM entry SMB fraction tracking ----------------------------------
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

        // -- Post-meal learning lockout ---------------------------------------
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

        // -- STFT: short-term target reduction for stuck-high fasting BG ------
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

        // ════════════════════════════════════════════════════════════════════
        // ICE (Insulin Counteraction Effect) — Step 2: live dosing influence
        // ════════════════════════════════════════════════════════════════════
        //
        // What ICE does:
        //   ICE = (observed ΔBG) − (modeled insulin ΔBG)
        //   ICE > 0 ⇒ unmodeled BG-raising force (food, stress, dawn, protein)
        //   ICE < 0 ⇒ unmodeled BG-lowering force (exercise, late insulin)
        //
        // Live effects (when enabled and confidence > 0):
        //   1. `aggressiveness` is multiplied by an ICE-derived factor — system
        //      pushes harder on rising ICE, backs off (asymmetrically) on falling.
        //   2. PDP is forced off when ICE is meaningfully driving — they solve
        //      the same problem from opposite directions and stacking causes
        //      over-correction (per design decision in earlier conversation).
        //   3. ISF/basal learners are paused above the configured threshold so
        //      they don't train on ICE-modified dosing outcomes.
        //
        // Disable conditions (force confidence=0, no influence on dosing):
        //   - User preference toggle off (kill switch)
        //   - CGM warmup (first 24h, sensor model untrustworthy)
        //   - Exercise high temp target (sensitivity shifted from baseline)
        //   - Activity monitor reports active (HR/steps elevated)
        //
        // Uses trueIsfMgdl (profile ISF) as the modeled-insulin reference. This
        // gives ICE a stable baseline to measure against rather than chasing a
        // moving target (dosingIsfMgdl shifts each cycle as learners adjust).
        val iceTrackerEnabled = preferences.get(BooleanKey.ApsSmartInsulinIceEnabled)
        val iceDisableReason: IceDisableReason? = when {
            !iceTrackerEnabled                                                 -> IceDisableReason.PREFERENCE_DISABLED
            cgmInWarmup                                                        -> IceDisableReason.CGM_WARMUP
            highTempTarget                                                     -> IceDisableReason.EXERCISE_TEMP_TARGET
            activityMonitor.level != ActivityMonitor.ActivityLevel.SEDENTARY   -> IceDisableReason.ACTIVITY_DETECTED
            else                                                               -> null
        }
        val iceActivityUperMin = iobArray.firstOrNull()?.activity ?: 0.0
        iceTracker.recordCycle(
            timestampMs     = glucoseStatus.date,
            bgMgdl          = glucoseStatus.glucose,
            activityUperMin = iceActivityUperMin,
            isfMgdlPerU     = trueIsfMgdl,
            disableReason   = iceDisableReason,
            params          = app.aaps.plugins.aps.smartInsulin.ice.IceConfidenceParams(
                magnitudeFloorMgdlH      = preferences.get(DoubleKey.ApsSmartInsulinIceFloorMgdlH),
                magnitudeStrongMgdlH     = preferences.get(DoubleKey.ApsSmartInsulinIceStrongMgdlH),
                persistenceCyclesForFull = preferences.get(IntKey.ApsSmartInsulinIcePersistCycles),
                consistencyWindowCycles  = preferences.get(IntKey.ApsSmartInsulinIceConsistWindow)
            )
        )

        // Derived state for downstream use. Snapshot was just published by recordCycle.
        val iceSnapshot          = iceTracker.snapshot.value
        val observedIceMgdlPerH  = iceSnapshot?.currentIceMgdlH
        val observedConfidence   = iceSnapshot?.confidence?.score ?: 0.0
        // ── Disable layering ──────────────────────────────────────────────────
        // Two independent disable axes:
        //   - HARD disable: CGM warmup, exercise temp target, activity detected.
        //     These block ALL ICE engagement — even announced meals — because the
        //     underlying physiology is wrong (BG move is not from insulin).
        //   - OBSERVATION disable: IceTracker can't compute observed ICE — e.g.,
        //     INSUFFICIENT_HISTORY, CGM_QUALITY. Blocks UNANNOUNCED ICE but does
        //     NOT block announced meals, which have their own signal source
        //     (the meal curve) independent of observation.
        val iceObservationDisabled = iceSnapshot?.confidence?.disabled != null
        val iceLearningThreshold = preferences.get(DoubleKey.ApsSmartInsulinIceLearningBlockThreshold)

        // ── Announced meal layer ──────────────────────────────────────────────
        // If the user has announced one or more meals (layers), blend their
        // aggregated expected curve with observed ICE. Strategy: take whichever
        // is stronger (max), since the expected curve provides pre-positioning
        // before observed ICE catches up, but observed wins once a real meal
        // materializes harder than predicted. Asymmetric — only kicks in when
        // the meal would push more than reality currently shows, never reduces
        // below observed.
        val hasMeal             = announcedMealManager.hasActiveMeals(glucoseStatus.date)
        val maxCommitmentFrac   = announcedMealManager.maxCommitmentFraction(glucoseStatus.date)
        // Carb glycemic load per gram, derived from the user's current profile.
        // CR = grams of carb covered by 1 U insulin; ISF = mg/dL drop per 1 U insulin.
        // → 1 g carb produces (ISF / CR) mg/dL rise. Using current profile values means
        // ICE responsiveness automatically tracks the user's tuning AND any circadian
        // CR variation — tighten CR → ICE doses more aggressively, loosen → less.
        // Guarded against zero/missing CR with the population-average default.
        val profileCrG          = profile.getIc().coerceAtLeast(1.0)
        val carbLoadPerG        = (trueIsfMgdl / profileCrG).takeIf { it.isFinite() && it > 0.0 }
            ?: app.aaps.plugins.aps.smartInsulin.ice.MealCurveBuilder.DEFAULT_CARB_LOAD_PER_G_MGDL
        val expectedIceMgdlPerH = announcedMealManager.expectedIceMgdlPerHour(glucoseStatus.date, carbLoadPerG)
        val mealOverridesObserved = hasMeal && expectedIceMgdlPerH > (observedIceMgdlPerH ?: 0.0)
        val iceMgdlPerHEffective: Double? = if (mealOverridesObserved) expectedIceMgdlPerH else observedIceMgdlPerH
        // Confidence — when an announcement exists, the user's commitment is the FLOOR
        // (not a fallback only when expected > observed). The announcement justifies
        // trusting any concurrent ICE signal as real rather than noise, which solves
        // the cold-start problem where observed ICE is already huge but the
        // persistence/consistency scorers need several cycles to build confidence.
        // Observed confidence can boost above the commitment but never below it.
        // With multiple layers active, use the MAX commitment — if any layer is 100%
        // committed, the loop should treat the whole meal at full confidence.
        val iceConfidenceScore  = if (hasMeal)
            maxOf(maxCommitmentFrac, observedConfidence).coerceIn(0.0, 1.0)
        else
            observedConfidence

        // ── ICE mode (ice-step28) ──────────────────────────────────────────
        // Distinguish announced meal (COB) from observed-but-unannounced rise
        // (UAM) so each can have its own tuning. See [IceMode] for routing rules.
        // UAM-mode tuning lives in the dedicated prefs:
        // ApsSmartInsulinUamIceUserWeight / LearningBlockThreshold /
        // AggressionCap (DoubleKey) and UamIceDecayMinutes (IntKey).
        // Once computed, the mode drives four downstream selections: userWeight
        // source, learning-confidence threshold, forward-projection decay timeline,
        // and the aggression-cap. COB still wins when both signals coexist — the
        // asymmetric max(observed, expected) happens in iceMgdlPerHEffective above.
        val iceMode: app.aaps.plugins.aps.smartInsulin.ice.IceMode = when {
            !iceTrackerEnabled                  -> app.aaps.plugins.aps.smartInsulin.ice.IceMode.NONE
            hasMeal                             -> app.aaps.plugins.aps.smartInsulin.ice.IceMode.COB
            iceObservationDisabled              -> app.aaps.plugins.aps.smartInsulin.ice.IceMode.NONE
            (observedIceMgdlPerH ?: 0.0) > 0.0  -> app.aaps.plugins.aps.smartInsulin.ice.IceMode.UAM
            else                                -> app.aaps.plugins.aps.smartInsulin.ice.IceMode.NONE
        }

        // ── Effective user weight (the actual blend strength) ───────────────────────
        // The userWeight slider in ICE prefs controls how much trust to put in ICE.
        // BUT — when the user has explicitly announced a meal, that announcement is
        // consent to act on the meal regardless of the slider. So we floor the
        // effective weight at 0.5 × commitment fraction when an announced meal exists.
        // Effect:
        //   slider=0.0, no meal              → effective=0.0 (slider honoured)
        //   slider=0.0, 100% committed meal  → effective=0.5 (announcement floors)
        //   slider=0.8, 100% committed meal  → effective=0.8 (slider above floor wins)
        //   slider=0.8, 50%  committed meal  → effective=0.8 (slider above floor wins)
        // This means a meal can never silently fail to be dosed because the slider
        // was left at zero — but the slider still controls strength when set higher.
        //
        // ice-step28: userWeight source now switches on mode. COB reads the
        // existing pref; UAM reads ApsSmartInsulinUamIceUserWeight (default
        // 0.3 — lower than typical COB because unannounced rises have higher
        // false-positive risk). The announcedMealFloor only applies in COB —
        // UAM has no announcement to floor against, so the configured weight
        // goes through raw.
        val configuredUserWeight: Double = when (iceMode) {
            app.aaps.plugins.aps.smartInsulin.ice.IceMode.COB ->
                preferences.get(DoubleKey.ApsSmartInsulinIceUserWeight)
            app.aaps.plugins.aps.smartInsulin.ice.IceMode.UAM ->
                preferences.get(DoubleKey.ApsSmartInsulinUamIceUserWeight)
            app.aaps.plugins.aps.smartInsulin.ice.IceMode.NONE ->
                0.0
        }
        val announcedMealFloor   = if (iceMode == app.aaps.plugins.aps.smartInsulin.ice.IceMode.COB) 0.5 * maxCommitmentFrac else 0.0
        val effectiveUserWeight  = maxOf(configuredUserWeight, announcedMealFloor).coerceIn(0.0, 1.0)

        // ── Effective disable flag ───────────────────────────────────────────────
        // Hard disable (warmup/exercise/activity) always blocks. Observation
        // disable (INSUFFICIENT_HISTORY etc) blocks only when there's NO announced
        // meal — an announced meal carries its own confidence floor and shouldn't
        // be silenced just because the IceTracker can't compute observed ICE.
        // This is what `iceIsDisabled` should have meant all along.
        val iceIsDisabled = iceDisableReason != null ||
            (!hasMeal && iceObservationDisabled)
        // ── Effective learning threshold (ice-step28) ──────────────────────
        // COB uses the existing pref; UAM uses a higher floor (0.6 default vs
        // a typical COB threshold around 0.4–0.5). Higher because UAM hasn't
        // been confirmed by the user, so we need stronger persistence /
        // consistency before driving dose.
        val effectiveLearningThreshold: Double = when (iceMode) {
            app.aaps.plugins.aps.smartInsulin.ice.IceMode.COB ->
                iceLearningThreshold
            app.aaps.plugins.aps.smartInsulin.ice.IceMode.UAM ->
                preferences.get(DoubleKey.ApsSmartInsulinUamIceLearningBlockThreshold)
            app.aaps.plugins.aps.smartInsulin.ice.IceMode.NONE ->
                Double.MAX_VALUE   // never drives
        }
        // iceIsDriving: ICE is meaningfully influencing dosing (used for PDP / learner gating).
        // Threshold-gated rather than smooth — these are on/off decisions.
        val iceIsDriving         = iceTrackerEnabled && !iceIsDisabled && iceConfidenceScore >= effectiveLearningThreshold
        // iceAggrAdjust: smooth multiplier on aggressiveness (used for dose scaling).
        // Returns 1.0 when ICE is disabled / no signal / no confidence — safe identity.
        // ice-step28: in UAM mode, cap at ApsSmartInsulinUamIceAggressionCap pref
        // (default 1.3) — limits dose escalation when chasing an unconfirmed rise.
        // COB mode gets no extra cap (the global 0.3–2.0 coerce on `aggressiveness`
        // downstream still applies).
        val iceAggrAdjustRaw     = app.aaps.plugins.aps.smartInsulin.ice.computeIceAggressionAdjust(
            iceMgdlPerH     = iceMgdlPerHEffective,
            confidence      = iceConfidenceScore,
            disabled        = iceIsDisabled,
            userWeight      = effectiveUserWeight,
            saturationMgdlH = preferences.get(DoubleKey.ApsSmartInsulinIceStrongMgdlH)
        )
        val iceAggrAdjust        = if (iceMode == app.aaps.plugins.aps.smartInsulin.ice.IceMode.UAM)
            iceAggrAdjustRaw.coerceAtMost(preferences.get(DoubleKey.ApsSmartInsulinUamIceAggressionCap))
        else iceAggrAdjustRaw
        lastIceAggrAdjust = iceAggrAdjust  // cache for fragmentData() / UI status card
        if (iceIsDriving || iceAggrAdjust != 1.0 || hasMeal) {
            val layerCount = announcedMealManager.activeMeals.value.count { it.isActive(glucoseStatus.date) }
            val mealNote = if (hasMeal)
                " meal=${announcedMealManager.aggregatedGiBucketLabel(glucoseStatus.date)}×$layerCount " +
                    "maxCommit=${(maxCommitmentFrac * 100).toInt()}% expected=${"%.1f".format(expectedIceMgdlPerH)}mg/dL/h"
            else ""
            aapsLogger.debug(LTag.APS,
                             "ICE: mode=$iceMode snapshot=${iceSnapshot?.summaryText} driving=$iceIsDriving aggrAdjust=${"%.2f".format(iceAggrAdjust)}$mealNote")
        }


        // -- Circadian learner — fasting + no high TT only ---------------------
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
        // Match the blend formula used in production dosing (see ~line 1709).
        // Display capture: at this point we're BEFORE circadianLearner.update(), so all reads
        // here reflect the pre-update state. The "was basal" shown on the SI screen must use
        // the same formula as the basal that was actually being delivered.
        // basalLearningEnabled is defined later in this function (~line 1607); read it locally
        // here so the "was" capture respects the same gate as production dosing.
        val basalLearningEnabledForCapture = preferences.get(BooleanKey.ApsSmartInsulinBasalLearningEnabled)
        val flatBasalMultBefore = if (basalLearningEnabledForCapture) basalLearner.multiplierClamped else 1.0
        val circConfBefore       = (circadianLearner.confidencePct(currentHour) / 100.0).coerceIn(0.0, 1.0)
        val totalBasalMultBefore = (1.0 - circConfBefore) * flatBasalMultBefore + circConfBefore * circadianLearner.basalMultiplier(currentHour)
        val lastDirection = run {
            val parts = lastSeenNudgeState.split("|")
            val p = parts.getOrNull(0) ?: "INACTIVE"
            if (p == "TRIM") parts.getOrNull(1) else if (p == "ACTIVE_HIGH" || p == "ACTIVE_LOW") p else null
        }

        if (!highTempTarget) {
            val suppressAdaptiveLearningUpdate = activityMonitor.suppressLearning || cgmState.suppressLearning || iceIsDriving
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
                fastingPeakMins          = profileLearner.getProfile(app.aaps.core.interfaces.smartInsulin.MealMode.FASTING).peakMinutes,
                // Suppress fast-path ISF nudge when PDP is actively blending — avoids double-nudging
                // ISF toward more-aggressive on every cycle. Episode outcome learning handles the
                // ground-truth feedback when the PDP episode closes.
                pdpBlendActive           = cachedPdpBlendWeight > 0.1
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
            val tbrStep = activePlugin.activePump.pumpDescription.tempAbsoluteStep.takeIf { it > 0.0 } ?: 0.05
            val rawSessionBasal = cachedProfileBasal * totalBasalMultBefore
            nudgeDisplaySessionBasalU = Math.round(rawSessionBasal / tbrStep) * tbrStep
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
        // circIsfMult > 1.0 ? divide ? dosingISF goes DOWN ? more aggressive ? more insulin
        // circIsfMult < 1.0 ? divide ? dosingISF goes UP   ? more insulin (insulin stronger than profile)
        // This is correct: circIsfMult is a sensitivity multiplier, not a direct ISF scalar.
        var dosingIsfMgdl = when {
            modeIsfMgdl > 0.0 -> modeIsfMgdl                    // user meal-mode override — already mg/dL
            else              -> trueIsfMgdl / circIsfMult        // divide: mult>1 ? lower dosingISF ? more aggressive ? more insulin
        }



        // -- Tick the override manager — fires queued bolus when safe ----------
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



        // -- UAM: auto-detect unannounced meals from BG rise during fasting ----
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

        // -- Re-read mealMode after UAM — reassign mealMode and dosingIsfMgdl if UAM fired --
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

        // -- STFT: run after UAM so it sees the correct mealMode this cycle ----
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

        // -- Build OapsProfile — apply per-meal ISF multiplier to sens ---------
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
            // Force enableUAM=true when PDP is active so the UAM prediction slot
            // renders as orange on the overview graph (distinct from cyan IOB line).
            enableUAM                       = preferences.get(BooleanKey.ApsSmartInsulinPdpEnabled) || constraintsChecker.isUAMEnabled().also { inputConstraints.copyReasons(it) }.value(),
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
        // getEffectiveProfile returns peak from `learnedProfileMode` but DIA universally
        // from FASTING — DIA is a pharmacokinetic property of NovoRapid that doesn't shift
        // by meal context, only by sensor confounding. One global DIA, learned only from
        // clean fasting observations, applies across all modes. See ProfileLearner doc.
        val learnedProfile    = profileLearner.getEffectiveProfile(learnedProfileMode)

        // -- Activity monitor — recompute from fed HR/steps data -------------
        // ActivityMonitor queries persistenceLayer directly — no feed calls needed.
        // See WiringNotes.md for the subscription setup.
        // If no data has been fed (no wear device, watch not worn), defaults to SEDENTARY.
        val restingHrBpm = preferences.get(DoubleKey.ApsSmartInsulinRestingHrBpm)
        activityMonitor.recompute(nowMs = now, restingHrBpm = restingHrBpm)

        // Update configurable rebound window — inReboundWindow uses this
        // Extend dynamically if rollercoasters are happening in the same episode:
        //   Rollercoaster 1 ? +15 min
        //   Rollercoaster 2+ ? +15 min additional per count (capped at +45 min total)
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
                                 "(rollercoaster #$rollerCount) ? total ${reboundGuardMs / 60_000}min")
        }

        // -- CGM warmup guard -------------------------------------------------

        // Suppress learning during CGM warmup — noisy readings corrupt all learned models
        // CGM warmup: suppress ISF/basal/TIR adaptive learning but keep rollercoaster protection
        // Activity: suppress all learning (BG changes are exercise-driven, not insulin-driven)
        val suppressAdaptiveLearningGlobal = activityMonitor.suppressLearning || cgmState.suppressLearning || inPostMealLockout || iceIsDriving
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
        aggressionLearner.recordBg(
            bgMgdl          = glucoseStatus.glucose,
            lowThreshMgdl   = 70.0,    // 3.9 mmol — ADA TIR lower bound
            highThreshMgdl  = 180.0,   // 10.0 mmol — ADA TIR upper bound (clinical standard)
            mealMode        = mealMode,
            suppressScoring = suppressAdaptiveLearningGlobal
        )
        // During meal modes: aggressiveness = 1.0, loop uses profile ISF/basal + learned peak/DIA only
        // Fasting: apply circadian ceiling (which can only reduce aggressiveness, never inflate)
        val baseAggressiveness = if (mealMode != MealMode.FASTING) 1.0
        else aggressionLearner.aggressiveness.coerceAtMost(circAggrCeil)
        // ICE adjusts aggressiveness in real time based on observed BG vs insulin model.
        // Clamp combined value to [0.3, 2.0] — a final safety bound on top of the per-component clamps.
        val aggressiveness = (baseAggressiveness * iceAggrAdjust).coerceIn(0.3, 2.0)
        val tirSummary     = aggressionLearner.tirSummary

        // Feed basal learner — fasting only, no high temp target
        // High TT = deliberate conservative mode (exercise/illness) — don't learn from it
        // Meal modes = COB active, loop reacting to carbs — basal signal is meaningless
        val basalLearningEnabled = preferences.get(BooleanKey.ApsSmartInsulinBasalLearningEnabled)


        // -- Cache Overview state — updated here where all conditions are in scope --
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
        val baseModeLineStr = mealOverrideManager.activeMealMode?.let { mode ->
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
        // Append remaining macros for the announced meal (if any). Visible on the
        // main AAPS overview as part of the SI mode line — gives the user a quick
        // glance at carbs/protein/fat still absorbing without opening the SI tab.
        val modeLineStr = baseModeLineStr + announcedMealManager.macrosOverviewSuffix(now)
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
            modeLine         = modeLineStr,
            pb2Line          = pb2LineStr,
            pb3Line          = pb3LineStr,
            learningState    = learningStateStr,
            isFasting        = mealMode == MealMode.FASTING,
            isLearning       = learningStateStr == "Learning",
            activeMealLayers = buildMealLayerInfoList()   // ice-step27c: per-cycle refresh of layer ages
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
        // Blend flat BasalLearner with circadian per-hour learning, weighted by
        // CircadianLearner's confidence at this hour:
        //   conf = 0 → use BasalLearner's global multiplier (cold start, sparse hour)
        //   conf = 1 → use CircadianLearner's per-hour multiplier (mature bucket)
        //   in between → linear blend
        //
        // PREVIOUS BEHAVIOUR (pure multiplication: flatBasalMult * circBasalMult)
        // produced two stacked integral controllers writing the same actuator.
        // When circadian penalised a particular hour DOWN, BG drifted UP, BasalLearner
        // interpreted that drift as profile-basal-too-low and pulled UP — fighting the
        // circadian correction every cycle. Net result depended on relative speeds of
        // the two controllers rather than on what the user actually needed.
        // The screenshot diagnosis at h=9 Weds confirmed this: circadian 0.939 × flat
        // 1.118 = 1.05 — controllers cancelling rather than converging.
        //
        // SAFETY NOTE on the new blend:
        //   Both inputs are in [0.5, 1.5]. A weighted average of values in [0.5, 1.5]
        //   is also in [0.5, 1.5] — STRICTLY tighter than the previous multiplicative
        //   range of [0.25, 2.25]. The downstream max_basal cap still applies.
        val flatBasalMult  = if (basalLearningEnabled) basalLearner.multiplierClamped else 1.0
        val circConf       = (circadianLearner.confidencePct(currentHour) / 100.0).coerceIn(0.0, 1.0)
        val basalMultiplier = (1.0 - circConf) * flatBasalMult + circConf * circBasalMult

        val maxSmbU           = preferences.get(DoubleKey.ApsSmartInsulinMaxSmb)
        val dawnWindowStart   = preferences.get(IntKey.ApsSmartInsulinDawnWindowStartHour)
        val dawnWindowEnd     = preferences.get(IntKey.ApsSmartInsulinDawnWindowEndHour)
        val dawnSmbReduction  = preferences.get(DoubleKey.ApsSmartInsulinDawnSmbReduction)

        // -- PDP: accuracy scoring from previous cycle -------------------------
        // Compare last cycle's t+5min predictions against actual BG now.
        // Only score when: PDP was active last cycle, we're still fasting, CGM is fresh.
        // ICE override: when ICE is actively driving dosing, force PDP off — they solve
        // the same problem (unexplained BG elevation correction) from opposite directions
        // and stacking them produces over-correction. ICE is the newer, observation-driven
        // approach; PDP is the older, threshold-based one.
        val pdpEnabled         = preferences.get(BooleanKey.ApsSmartInsulinPdpEnabled) && !iceIsDriving
        val pdpLearningEnabled = pdpEnabled && preferences.get(BooleanKey.ApsSmartInsulinPdpLearningEnabled)
        // PDP accuracy scoring — only during clean fasting, no lows, no post-meal dirty window.
        // Rebound rises (counter-regulatory glucagon) and post-meal tails look like persistent
        // deviation but aren't — learning from them would corrupt per-hour ci strength.
        val pdpCleanForLearning = mealMode == MealMode.FASTING
            && !bgWentLow
            && !inReboundWindow
            && !inPostMealLockout
        if (pdpLearningEnabled && lastPdpBlendActive && pdpCleanForLearning) {
            val iobPred = determineBasalSmartInsulin.lastIobPredAt5Mgdl
            val pdpPred = determineBasalSmartInsulin.lastPdpPredAt5Mgdl
            val actual  = glucoseStatus.glucose
            if (iobPred > 0.0 && pdpPred > 0.0) {
                val lastPathway = if (cachedPdpSyntheticCi > 0.0) "stuck" else "rising"
                // Stuck-high: secondary curve uses ISF/ciStrength as a dosing model,
                // not a BG predictor. Scoring its t+5 prediction against actual BG
                // would always show large error ? learner drives strengthMult to minimum
                // ? PDP disabled. Episode outcome (recordEpisodeOutcome) provides the
                // correct feedback for stuck-high: did the dose land BG well?
                // Rising pathway still uses curve prediction accuracy — ci extension
                // is genuinely trying to predict where BG will go.
                if (lastPathway != "stuck") {
                    val iobErr = kotlin.math.abs(actual - iobPred)
                    val pdpErr = kotlin.math.abs(actual - pdpPred)
                    pdpLearner.recordAccuracy(lastCycleHour, iobErr, pdpErr, lastPathway)
                }
            }
        } else if (pdpLearningEnabled) {
            // Learning enabled but PDP wasn't blending or conditions weren't clean —
            // tick confidence decay so stale hours don't hold onto high confidence forever.
            pdpLearner.tickAllDecay(currentHour)
        }
        // If pdpLearningEnabled == false: do nothing. Confidence is frozen until user re-enables
        // learning — avoids surprising the user by silently draining learned state while disabled.

        // -- PDP: compute blend weight for this cycle --------------------------
        // ci = observed delta minus expected BGI (same formula as DetermineBasalSmartInsulin)
        // Positive ci means BG is rising more than IOB alone predicts.
        val bgiMgdlForPdp = -((iobArray.firstOrNull()?.activity ?: 0.0) * dosingIsfMgdl * 5.0)
        val ciMgdl = minOf(glucoseStatus.shortAvgDelta, glucoseStatus.delta) - bgiMgdlForPdp

        // PDP constants — inline mg/dL values, no dependency on DetermineBasalSmartInsulin constants
        val PDP_CI_THRESHOLD_MGDL   = 5.4   // ~0.3 mmol/5min — filters ci noise
        val PDP_STUCK_OFFSET_MGDL   = 9.0   // 0.5 mmol above target — BG only needs to be modestly above target
        val PDP_STUCK_DELTA_MGDL    = 5.4   // ±0.3 mmol/5min — flat enough (matches ci threshold)

        // All PDP tracking gated on clean fasting — no lows, rebound, or post-meal dirty window.
        // Hard reset on dirty conditions so PDP can't carry momentum across low/recovery events.
        val pdpCleanForBlending = mealMode == MealMode.FASTING
            && !bgWentLow
            && !inReboundWindow
            && !inPostMealLockout

        // -- Pathway 1: ci-based (unexplained rising deviation) ----------------
        if (pdpEnabled && pdpCleanForBlending && ciMgdl > PDP_CI_THRESHOLD_MGDL) {
            consecutivePosCiReadings++
        } else {
            when {
                !pdpCleanForBlending       -> consecutivePosCiReadings = 0  // dirty — hard reset
                ciMgdl < -PDP_CI_THRESHOLD_MGDL -> consecutivePosCiReadings = 0  // IOB clearly winning — hard reset
                else                       -> consecutivePosCiReadings = (consecutivePosCiReadings - 1).coerceAtLeast(0)
            }
        }

        // -- Pathway 2: stuck-high (BG persistently above target, flat, not correcting) --
        // Catches the overnight 8.5 mmol plateau where IOB is low so ci ˜ 0
        // but BG has been stuck above target for many cycles.
        // No IOB gate — fastingMaxIob handles over-stacking. The signal is purely
        // "BG is above target + offset and not moving" regardless of IOB level.
        val stuckHighBg = glucoseStatus.glucose > (profileTargetMgdl + PDP_STUCK_OFFSET_MGDL)
        val stuckFlat   = kotlin.math.abs(glucoseStatus.shortAvgDelta) < PDP_STUCK_DELTA_MGDL
        if (pdpEnabled && pdpCleanForBlending && stuckHighBg && stuckFlat) {
            pdpStuckHighReadings++
        } else {
            // Hard reset when:
            //   - dirty conditions (low, rebound, post-meal) — can't carry state across
            //   - BG is back below the stuck threshold — episode is resolved, reset immediately
            //     so PDP stops blending as soon as BG returns to acceptable range
            // Soft decay only when stuckFlat fails but BG is still above threshold
            // (e.g. BG is still high but starting to move — give it a moment)
            when {
                !pdpCleanForBlending -> pdpStuckHighReadings = 0  // dirty — hard reset
                !stuckHighBg         -> pdpStuckHighReadings = 0  // BG back at target — hard reset
                !stuckFlat           -> pdpStuckHighReadings = (pdpStuckHighReadings - 1).coerceAtLeast(0)  // moving but still high — soft decay
            }
        }

        lastCiMgdl = ciMgdl

        val pdpMinReadingsStuck = preferences.get(IntKey.ApsSmartInsulinPdpMinReadings)
        val pdpMaxBlend         = preferences.get(DoubleKey.ApsSmartInsulinPdpMaxBlendWeight)
        val pdpBaseCiStrength   = preferences.get(DoubleKey.ApsSmartInsulinPdpCiStrength)
        val pdpRisingStrength   = preferences.get(DoubleKey.ApsSmartInsulinPdpRisingStrength)
        val pdpFadeMins         = preferences.get(IntKey.ApsSmartInsulinPdpFadeMinutes).toDouble()
        val fastingMaxIob       = preferences.get(DoubleKey.ApsSmartInsulinFastingMaxIob)

        // -- Active pathway determination --------------------------------------
        // Stuck pathway: requires pdpMinReadingsStuck consecutive qualifying readings
        // Rising pathway: NO minimum readings gate — activates immediately but starts
        //   at very low blend weight so it barely does anything until it's earned trust.
        //   This lets it learn from the first qualifying reading, building slowly.
        val stuckActive  = pdpStuckHighReadings >= pdpMinReadingsStuck && pdpStuckHighReadings > 0
        val risingActive = consecutivePosCiReadings > 0  // no gate — always active when rising
        val pdpActivePathway = when {
            stuckActive && risingActive ->
                if (pdpStuckHighReadings >= consecutivePosCiReadings) "stuck" else "rising"
            stuckActive  -> "stuck"
            risingActive -> "rising"
            else         -> "none"
        }

        // -- Blend weight ------------------------------------------------------
        // Stuck: ramps from 0 ? pdpMaxBlend over pdpMinReadingsStuck cycles, then scales
        //   by learned blendMult and confidence. Full blend after 2× minReadings.
        // Rising: starts at 5% of max blend per reading, very slowly building up.
        //   No sudden jump — the learner adjusts blendMult over time to find the right level.
        //   Morning rises that need no PDP: blendMult learns down ? near-zero blend ? no effect.
        //   Morning rises that need more: blendMult learns up ? meaningful blend.
        // effectiveBlendMult is the single confidence gate — no separate blendWeightConfidenceScale
        // (Deepseek review fix: double-applying confidence was keeping blend at 35% despite 0.7 max)
        val learnedBlendScale = pdpLearner.effectiveBlendMult(currentHour)
        val rawBlendWeight = if (!pdpEnabled || !pdpCleanForBlending) 0.0
        else when (pdpActivePathway) {
            "stuck" -> {
                val readingsBeyondMin = (pdpStuckHighReadings - pdpMinReadingsStuck).coerceAtLeast(0)
                val rampFraction = minOf(1.0, readingsBeyondMin.toDouble() / pdpMinReadingsStuck + 1.0)
                pdpMaxBlend * rampFraction * learnedBlendScale
            }
            "rising" -> {
                // 15% of max blend per reading — reaches full blend in ~7 consecutive ci readings (~35 min).
                // Previous 5%/reading required 20 readings (100 min) — effectively neutered the pathway.
                val risingFraction = minOf(1.0, consecutivePosCiReadings * 0.15)
                pdpMaxBlend * risingFraction * learnedBlendScale
            }
            else -> 0.0
        }

        val pdpEffectiveCiStrength      = if (pdpEnabled) pdpLearner.effectiveCiStrength(currentHour, pdpBaseCiStrength) else 1.0
        val pdpEffectiveRisingStrength  = if (pdpEnabled) pdpLearner.effectiveRisingStrength(currentHour, pdpRisingStrength) else 1.0
        val pdpEffectiveFadeMins        = if (pdpEnabled) pdpLearner.effectiveFadeMins(currentHour, pdpFadeMins) else pdpFadeMins
        val pdpBlendWeight = rawBlendWeight.coerceIn(0.0, pdpMaxBlend)

        // -- Stuck-high pathway flag for DetermineBasalSmartInsulin -------------
        // When stuck pathway is dominant, pass a non-zero value so DetermineBasal
        // uses the resistance model (counteract IOB) instead of ci extension.
        // The actual resistance strength is derived from pdpCiStrength inside DetermineBasal.
        // pdpSyntheticCi > 0 = stuck pathway active; 0 = rising pathway or inactive.
        val pdpSyntheticCi: Double =
            if (pdpEnabled && pdpBlendWeight > 0.0 && pdpActivePathway == "stuck") 1.0 else 0.0

        // -- PDP episode tracking ----------------------------------------------
        val currentBgMmolForEpisode = glucoseStatus.glucose / 18.0
        val warnGuardMmol     = spMgdl(UnitDoubleKey.ApsSmartInsulinWarnGuard) / 18.0
        val lowGuardMmolEp    = spMgdl(UnitDoubleKey.ApsSmartInsulinLowGuard) / 18.0
        val profileTargetMmol = profileTargetMgdl / 18.0

        if (pdpEnabled && pdpBlendWeight > 0.0) {
            val ep = activeEpisode
            if (ep == null) {
                // Episode opening — PDP just became active
                val openDow = java.util.Calendar.getInstance().also { it.timeInMillis = System.currentTimeMillis() }
                    .get(java.util.Calendar.DAY_OF_WEEK) - 1
                activeEpisode = PdpEpisode(
                    startTimeMs     = System.currentTimeMillis(),
                    startHour       = currentHour,
                    startDow        = openDow,
                    pathway         = pdpActivePathway,
                    nadirBgMmol     = currentBgMmolForEpisode,
                    peakBlendWeight = pdpBlendWeight,
                    openedClean     = pdpCleanForBlending
                )
                aapsLogger.debug(LTag.APS,
                                 "PDP episode OPEN [$pdpActivePathway] BG=${String.format("%.1f", currentBgMmolForEpisode)} blend=${String.format("%.2f", pdpBlendWeight)}")
            } else {
                // Episode ongoing — update nadir, peak blend, and active pathway.
                // Pathway can shift mid-episode (e.g. stuck wins over rising as readings accumulate),
                // so we track the CURRENT dominant pathway rather than freezing the one at open time.
                // This ensures recordEpisodeOutcome() and nudgeIsfFromPdpEpisode() receive the
                // pathway that was actually dominant when the episode closed, not when it opened.
                ep.nadirBgMmol     = minOf(ep.nadirBgMmol, currentBgMmolForEpisode)
                ep.peakBlendWeight = maxOf(ep.peakBlendWeight, pdpBlendWeight)
                ep.pathway         = pdpActivePathway
                // Force-close if episode has been open too long (4h safety timeout)
                val durationMins = (System.currentTimeMillis() - ep.startTimeMs) / 60_000.0
                if (durationMins > 240.0 && pdpLearningEnabled) {
                    aapsLogger.debug(LTag.APS, "PDP episode TIMEOUT after ${durationMins.toInt()}min — force closing")
                    pdpLearner.recordEpisodeOutcome(
                        hour          = ep.startHour,
                        pathway       = ep.pathway,
                        landingBgMmol = currentBgMmolForEpisode,
                        nadirBgMmol   = ep.nadirBgMmol,
                        durationMins  = durationMins,
                        warnGuardMmol = warnGuardMmol,
                        lowGuardMmol  = lowGuardMmolEp,
                        targetMmol    = profileTargetMmol
                    )
                    // Timeout with BG still above target = missed correction
                    circadianLearner.nudgeIsfFromPdpEpisode(
                        hour    = ep.startHour,
                        dow     = ep.startDow,
                        outcome = if (currentBgMmolForEpisode > profileTargetMmol + 1.0) "MISSED" else "PARTIAL"
                    )
                    activeEpisode = null
                }
            }
        } else if (activeEpisode != null) {
            // Episode closing — blend dropped to 0, BG pathway resolved
            val ep = activeEpisode!!
            val durationMins = (System.currentTimeMillis() - ep.startTimeMs) / 60_000.0
            // Only score if episode lasted at least 15min (avoid micro-episodes from noise)
            // Only score if episode lasted at least 15min and was opened during clean conditions.
            // Using ep.openedClean (not pdpCleanForBlending) so episodes that opened clean
            // are always scored even if the clean flag drops transiently at close time
            // (e.g. a brief lockout flag flip at the end of a post-meal window).
            if (durationMins >= 15.0 && pdpLearningEnabled && ep.openedClean) {
                aapsLogger.debug(LTag.APS,
                                 "PDP episode CLOSE [${ep.pathway}] " +
                                     "landing=${String.format("%.1f", currentBgMmolForEpisode)}mmol " +
                                     "nadir=${String.format("%.1f", ep.nadirBgMmol)}mmol " +
                                     "dur=${durationMins.toInt()}min")
                // Score PDP learner (blend/strength/fade)
                pdpLearner.recordEpisodeOutcome(
                    hour          = ep.startHour,
                    pathway       = ep.pathway,
                    landingBgMmol = currentBgMmolForEpisode,
                    nadirBgMmol   = ep.nadirBgMmol,
                    durationMins  = durationMins,
                    warnGuardMmol = warnGuardMmol,
                    lowGuardMmol  = lowGuardMmolEp,
                    targetMmol    = profileTargetMmol
                )

                // Determine outcome label for CircadianLearner
                val severeOvershot = ep.nadirBgMmol < lowGuardMmolEp
                val overshot       = ep.nadirBgMmol < warnGuardMmol
                val perfectLanding = !overshot
                    && currentBgMmolForEpisode >= (profileTargetMmol - 0.3)
                    && currentBgMmolForEpisode <= (profileTargetMmol + 0.3)
                val missed = !overshot && currentBgMmolForEpisode > (profileTargetMmol + 1.0)
                val circOutcome = when {
                    severeOvershot -> "SEVERE_LOW"
                    overshot       -> "OVERSHOT"
                    perfectLanding -> "PERFECT"
                    missed         -> "MISSED"
                    else           -> "PARTIAL"
                }

                // Feed episode outcome into CircadianLearner — long-term ISF/basal calibration
                // This replaces what fuel trim aboveBand was doing, but using real outcome data
                // rather than a proxy "BG has been above target for X minutes" signal.
                circadianLearner.nudgeIsfFromPdpEpisode(
                    hour    = ep.startHour,
                    dow     = ep.startDow,
                    outcome = circOutcome
                )
            } else if (durationMins < 15.0) {
                aapsLogger.debug(LTag.APS, "PDP episode DISCARDED — too short (${durationMins.toInt()}min < 15min)")
            } else if (!ep.openedClean) {
                aapsLogger.debug(LTag.APS, "PDP episode DISCARDED — opened during dirty conditions (rebound/post-meal lockout)")
            }
            activeEpisode = null
        }

        // Capture for next-cycle accuracy scoring and display
        lastCycleHour        = currentHour
        lastPdpBlendActive   = pdpEnabled && pdpBlendWeight > 0.0
        cachedPdpBlendWeight = pdpBlendWeight
        cachedPdpSyntheticCi = pdpSyntheticCi

        if (pdpEnabled && pdpBlendWeight > 0.0) {
            aapsLogger.debug(LTag.APS,
                             "SmartInsulin PDP[$pdpActivePathway]: blend=${"%.2f".format(pdpBlendWeight)} " +
                                 "ciStr=${"%.2f".format(pdpEffectiveCiStrength)} risingStr=${"%.2f".format(pdpEffectiveRisingStrength)} " +
                                 "fade=${pdpEffectiveFadeMins.toInt()}m " +
                                 "ci=${"%.1f".format(ciMgdl)}mg/dL " +
                                 "readings: ci=$consecutivePosCiReadings stuck=$pdpStuckHighReadings minStuck=$pdpMinReadingsStuck blendScale=${"%.2f".format(learnedBlendScale)}")
        }

        aapsLogger.debug(LTag.APS, "SmartInsulin mode=$mealMode modeISF=${if (modeIsfMgdl > 0.0) fmtIsf(modeIsfMgdl) + unitLabel else null} dosingISF=${fmtIsf(dosingIsfMgdl)}$unitLabel learnedProfile=$learnedProfile")

        // -- Rebound protection tracking ---------------------------------------
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
                                     "shortAvg?=${fmtDelta(shortAvgDeltaAtLow)}$unitLabel")
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

        // -- UAM / P/F auto-cancel when BG returns to target or below ------------------------
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
                                     "${fmtBg(profileTargetMgdl)}$unitLabel and not rising (?=${String.format("%.2f", shortAvgMmol)} mmol) " +
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

        // -- Soft landing bypass -----------------------------------------------
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
                                 "(=${fmtBg(softLandingDepthMgdl)}) " +
                                 "velAtLow=${fmtDelta(shortAvgDeltaAtLow)}$unitLabel (>-0.15) " +
                                 "iob=${String.format(java.util.Locale.ROOT, "%.2f", iobAtLowTime)}U (<1.0)")
        }

        // -- Meal-PDP: stuck-high ISF strengthening during meal/UAM modes ------
        // When BG is stuck high in a meal/UAM mode (rising has stalled but hasn't come down),
        // progressively strengthen the effective ISF — starting at the mode's own ISF and
        // getting more aggressive the longer it stays stuck.
        //
        // Gate: only active when pdpEnabled AND pdpMealStuckEnabled AND in a meal/UAM mode
        // (not fasting, not extended). BG must be above target and flat/rising (not already
        // falling — if it's falling, ISF is working and we leave it alone).
        // No COB requirement — this system doesn't use COB; meal modes time-bound the session.
        //
        // Counter dynamics:
        //   - Qualifying cycle (above-target, not-falling, in-meal-mode): counter increments
        //   - Falling cycle (sustained 15-min drop ≥ 0.3 mmol AND all 3 deltas negative):
        //       counter SOFT-DECAYS by 1 per cycle (not hard reset). A real sustained fall
        //       drains it fully; a single noisy dip costs you one cycle.
        //   - Mode change: hard reset (different meal, different meaning)
        //   - Mode exit (back to FASTING): hard reset
        //   - Below-threshold or non-meal mode: counter holds; history preserved so reset
        //     logic has continuous context if BG bounces back up.
        //
        // End-of-session taper:
        //   As the active meal mode's remaining time approaches zero, scale the meal-PDP
        //   divisor back toward 1.0 over the final SESSION_TAPER_FRACTION of the session.
        //   Example: 4h session, taper fraction 0.25 → start tapering with 1h remaining.
        //   This mirrors how COB-driven systems naturally relax as carbs run out.
        val pdpMealStuckEnabled = pdpEnabled && preferences.get(BooleanKey.ApsSmartInsulinPdpMealStuckEnabled)
        val inMealModeForMealPdp = mealMode != MealMode.FASTING && mealMode != MealMode.EXTENDED

        val MEAL_PDP_STUCK_OFFSET_MGDL    = 9.0    // 0.5 mmol above target — needs to be meaningfully high
        // Falling-detection: require sustained decline, not just slope at the noise floor.
        // 3 readings must all be negative AND cumulative drop ≥ 0.3 mmol over the 15-min window.
        // Catches obvious falls like 7.7→7.2 (0.5 mmol in 20 min) without firing on CGM jitter
        // around the previous -0.10 mmol/5min slope threshold that sat exactly at noise level.
        val MEAL_PDP_CUMULATIVE_DROP_MGDL = 5.4    // 0.3 mmol over 15-min window (3 deltas)
        // Fraction of session duration at which end-of-session taper begins.
        // 0.25 → final 25% of session ramps the meal-PDP boost back down to none.
        val MEAL_PDP_SESSION_TAPER_FRACTION = 0.25

        val mealPdpBgAboveTarget = glucoseStatus.glucose > (profileTargetMgdl + MEAL_PDP_STUCK_OFFSET_MGDL)

        // Update rolling 3-reading delta history (mg/dL/5min) every cycle.
        // History persists across non-qualifying cycles so we can detect a sustained fall
        // even if it dips below the above-target gate momentarily.
        val currentDelta = glucoseStatus.shortAvgDelta
        if (mealPdpDeltaHistory.size >= 3) mealPdpDeltaHistory.removeFirst()
        mealPdpDeltaHistory.addLast(currentDelta)

        // Declining if: 3 consecutive negative deltas AND cumulative drop ≥ 0.3 mmol/15min.
        // Single-threshold approach replaces the prior fast/slow split — the slow path was
        // at the CGM noise floor and missed clear falls like 7.7→7.2; the fast path was
        // redundant with the cumulative-drop check now in place.
        val hist = mealPdpDeltaHistory
        val mealPdpDeclining = hist.size >= 3
            && hist.takeLast(3).all { it < 0.0 }
            && hist.takeLast(3).sum() <= -MEAL_PDP_CUMULATIVE_DROP_MGDL

        // ── Counter management ─────────────────────────────────────────────────────
        // Three outcomes per cycle: hard reset (mode boundary), soft decay (declining),
        // increment (qualifying), or hold (non-qualifying but not falling — e.g. BG below
        // threshold momentarily, or briefly at target on the way back up).
        val modeChanged = mealMode != lastMealPdpMode
        when {
            // Hard reset: mode change OR exited meal modes entirely. Counter and history
            // are stale data once mode boundary is crossed.
            modeChanged || !inMealModeForMealPdp -> {
                if (mealPdpStuckReadings > 0 || mealPdpDeltaHistory.isNotEmpty()) {
                    aapsLogger.debug(LTag.APS,
                                     "SmartInsulin MealPDP: hard-reset (modeChanged=$modeChanged " +
                                         "inMealMode=$inMealModeForMealPdp)")
                }
                mealPdpStuckReadings = 0
                mealPdpDeltaHistory.clear()
            }
            // Soft decay: BG falling meaningfully. Drain the counter gradually so a transient
            // noise dip doesn't wipe a hard-earned ramp, but a sustained fall (this is the
            // 7.7→7.2 case) still drains to zero over a few cycles.
            mealPdpDeclining -> {
                val prev = mealPdpStuckReadings
                mealPdpStuckReadings = (mealPdpStuckReadings - 1).coerceAtLeast(0)
                aapsLogger.debug(LTag.APS,
                                 "SmartInsulin MealPDP: declining BG (cum=${"%.1f".format(hist.takeLast(3).sum())}mg/dL " +
                                     "over 15min) — soft decay $prev→$mealPdpStuckReadings")
            }
            // Qualifying: above target, not falling, in a meal mode. Tick the counter.
            pdpMealStuckEnabled && mealPdpBgAboveTarget -> {
                mealPdpStuckReadings++
            }
            // Hold: nothing else applies. Counter and history preserved.
            else -> { /* no-op */ }
        }
        lastMealPdpMode = mealMode

        val pdpMealStuckMinReadings = preferences.get(IntKey.ApsSmartInsulinPdpMealStuckMinReadings)
        val pdpMealMaxStrength      = preferences.get(DoubleKey.ApsSmartInsulinPdpMealMaxStrength)
        val pdpMealRampMins         = preferences.get(IntKey.ApsSmartInsulinPdpMealRampMins).toDouble()
        val pdpMealRampReadings     = (pdpMealRampMins / 5.0).coerceAtLeast(1.0)

        // ── End-of-session taper fraction ──────────────────────────────────────────
        // 1.0 = no taper (full strength); 0.0 = fully tapered (no boost).
        // Linear ramp-down over the final SESSION_TAPER_FRACTION of the session.
        // If session timing isn't available (override manager not active or expired),
        // taper = 1.0 (no effect). This is the COB-analogue you described.
        val sessionTaper: Double = run {
            val startMs = mealOverrideManager.modeStartMs
            val remMs   = mealOverrideManager.modeTimeRemainingMs
            if (!inMealModeForMealPdp || startMs <= 0L || remMs <= 0L) return@run 1.0
            val elapsedMs    = now - startMs
            val totalMs      = elapsedMs + remMs
            if (totalMs <= 0L) return@run 1.0
            val remFraction  = (remMs.toDouble() / totalMs.toDouble()).coerceIn(0.0, 1.0)
            // remFraction > taperFraction → still in main phase, no taper.
            // remFraction == 0 → session over, taper = 0.
            // Linear between.
            if (remFraction >= MEAL_PDP_SESSION_TAPER_FRACTION) 1.0
            else (remFraction / MEAL_PDP_SESSION_TAPER_FRACTION).coerceIn(0.0, 1.0)
        }

        // Meal-PDP ISF: starts at modeISF once min readings hit, then ramps down (stronger)
        // toward modeISF / maxStrength over rampMins. ISF going DOWN = more aggressive.
        // e.g. modeISF=36 (2.0 mmol/U), maxStrength=2.0 → floor at 18 (1.0 mmol/U)
        // sessionTaper scales the effective ramp fraction so end-of-session relaxes the boost.
        val mealPdpActive = pdpMealStuckEnabled && inMealModeForMealPdp
            && mealPdpStuckReadings >= pdpMealStuckMinReadings
        if (mealPdpActive && dosingIsfMgdl > 0.0) {
            val readingsBeyondMin = (mealPdpStuckReadings - pdpMealStuckMinReadings).coerceAtLeast(0)
            // Ramp fraction: 0.0 at min, 1.0 at minReadings + rampReadings cycles
            val rawRampFraction    = minOf(1.0, readingsBeyondMin.toDouble() / pdpMealRampReadings)
            // Apply session taper — multiplicatively reduces the effective ramp as session winds down.
            val rampFraction       = rawRampFraction * sessionTaper
            // ISF ramps from modeISF (rampFraction=0) down to modeISF / maxStrength (rampFraction=1)
            // Dividing by a factor > 1 makes ISF smaller → more aggressive
            val divisor   = 1.0 + rampFraction * (pdpMealMaxStrength - 1.0)
            val baseIsf   = dosingIsfMgdl
            val mealPdpIsf = baseIsf / divisor
            dosingIsfMgdl = mealPdpIsf
            cachedMealPdpIsf      = mealPdpIsf
            cachedMealPdpModeLabel = mealMode.label
            val taperNote = if (sessionTaper < 1.0) " taper=${"%.2f".format(sessionTaper)}" else ""
            aapsLogger.debug(LTag.APS,
                             "SmartInsulin MealPDP[${mealMode.label}]: stuck=${mealPdpStuckReadings}/${pdpMealStuckMinReadings} " +
                                 "rawRamp=${"%.2f".format(rawRampFraction)}$taperNote ramp=${"%.2f".format(rampFraction)} " +
                                 "div=${"%.2f".format(divisor)} ISF ${fmtIsf(mealPdpIsf)}$unitLabel " +
                                 "(base ${fmtIsf(baseIsf)}$unitLabel)")
        } else {
            if (!mealPdpActive || !inMealModeForMealPdp) {
                cachedMealPdpIsf       = 0.0
                cachedMealPdpModeLabel = ""
            }
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
            // Fuel trim aboveBand path is disabled — PDP handles above-target correction.
            // Only pass trimStrength when it's negative (belowBand — BG running too low)
            // and PDP is not actively blending. When PDP is active, both are zeroed to
            // prevent any compounding of ISF adjustments.
            fuelTrimStrength         = if (cachedPdpBlendWeight > 0.05) 0.0
            else circadianLearner.trimStrength.coerceAtMost(0.0),
            isMmol                   = isMmol,
            // PDP params
            pdpEnabled               = pdpEnabled,
            pdpCiStrength            = pdpEffectiveCiStrength,
            pdpFadeMins              = pdpEffectiveFadeMins,
            pdpRisingStrength        = pdpEffectiveRisingStrength,
            pdpBlendWeight           = pdpBlendWeight,
            fastingMaxIobU           = fastingMaxIob,
            pdpSyntheticCi           = pdpSyntheticCi,
            // ── ICE-driven prediction blending ────────────────────────────────────
            // Pulls effective ICE (observed blended with announced-meal expected) — the
            // expected curve provides pre-positioning before observed catches up.
            // iceBlendWeight = confidence × user-weight, gated off when ICE is disabled.
            iceMgdlPerH              = if (iceTrackerEnabled && !iceIsDisabled) iceMgdlPerHEffective else null,
            iceBlendWeight           = if (iceTrackerEnabled && !iceIsDisabled)
                (iceConfidenceScore * effectiveUserWeight).coerceIn(0.0, 1.0)
            else 0.0,
            // ── ICE forward prediction (drives the new dedicated chart line) ──────
            // For announced meals: sample the expected curve forward at 5-min ticks.
            // For unannounced (observed ICE only): persist current observed value with
            // linear decay to zero over 60 min — represents "this momentum will fade
            // unless something keeps driving it" rather than the unbounded-extrapolation
            // alternative.
            // Gated off when ICE is disabled or in warmup so the line disappears cleanly.
            iceFutureMgdlPerH        = if (iceTrackerEnabled && !iceIsDisabled) {
                // 96 ticks × 5 min = 8h — covers the full prediction horizon used in
                // determineBasal (predictionTicks = safeDiaMinutes/5, capped 72-96).
                // Long enough for the 5-6h plateau of fatty/high-protein meals.
                val predictionTicks = 96
                val activeMealsList = announcedMealManager.activeMeals.value.filter { it.isActive(glucoseStatus.date) }
                if (activeMealsList.isNotEmpty()) {
                    // Sample the aggregate meal curve forward — sum each active layer's
                    // contribution at its own future age. Use MealCurveBuilder DIRECTLY
                    // (not the manager wrapper) because the wrapper has side effects
                    // (auto-clear of expired layers) that would fire mid-iteration when
                    // sampling past the window. MealCurveBuilder is pure — returns 0.0
                    // past expiry without state changes.
                    // Pass carbLoadPerG so the prediction line matches the dosing
                    // decisions made above (both use the user's current CR).
                    (1..predictionTicks).map { tick ->
                        val futureMs = now + tick * 5 * 60_000L
                        activeMealsList.sumOf { meal ->
                            app.aaps.plugins.aps.smartInsulin.ice.MealCurveBuilder
                                .expectedIceMgdlPerHourAt(meal, futureMs, carbLoadPerG)
                        }
                    }
                } else if ((observedIceMgdlPerH ?: 0.0) > 0.0) {
                    // No announcement, but observed ICE is positive → decay linearly
                    // to zero over ApsSmartInsulinUamIceDecayMinutes (default 60 min).
                    // Represents "this momentum will fade unless something keeps
                    // driving it". ice-step28: was hardcoded `tick / 12.0` (= 60 min
                    // over 12 ticks); now tunable via the pref — default value
                    // preserves pre-step28 behaviour identically.
                    val startRate = observedIceMgdlPerH ?: 0.0
                    val decayMinutes = preferences.get(IntKey.ApsSmartInsulinUamIceDecayMinutes)
                    val decayTicks = decayMinutes / 5.0
                    (1..predictionTicks).map { tick ->
                        val decayFraction = (1.0 - tick / decayTicks).coerceAtLeast(0.0)
                        startRate * decayFraction
                    }
                } else {
                    emptyList()
                }
            } else emptyList(),
            // ── ICE mode for chart-slot routing (ice-step28) ────────────────────
            // determine_basal uses this to route the prediction line to the COB
            // slot (orange) for announced meals or the UAM slot (yellow) for
            // unannounced rises, so the user sees a visually distinct line per
            // mode. NONE = no ICE projection written to either slot.
            iceMode                  = iceMode
        )
        // Diagnostic — verify iceFutureMgdlPerH was generated. Visible in AAPS log,
        // helps confirm whether the prediction line ought to be appearing.
        if (iceTrackerEnabled && !iceIsDisabled) {
            val layerCount = announcedMealManager.activeMeals.value.count { it.isActive(glucoseStatus.date) }
            aapsLogger.debug(LTag.APS,
                             "ICE prediction: activeLayers=$layerCount " +
                                 "observedNonZero=${(observedIceMgdlPerH ?: 0.0) > 0.0} " +
                                 "expectedAt0=${"%.2f".format(expectedIceMgdlPerH)}mg/dL/h")
        }

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
            apsResult.reason += " | postMeal: dirty(${minsLeft}min) UAM?thresh"
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

        // -- BolusCurveTracker — meal modes only (peak/DIA learning from bolus curves)
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

        // Runs every cycle regardless of noise/activity gates — we want to track
        // the full meal shape even if BolusCurveTracker is paused.
        // Only skips when sensor is completely unreliable (warmup).
        if (!cgmInWarmup) {

            // PB1 — fires at session start; activeDoseU is non-null once delivered
            if (!pb1Notified && mealOverrideManager.activeDoseU != null) {
                pb1Notified = true
            }
            // PB2 — activePb2DoseU is non-null only after successful delivery
            if (!pb2Notified && mealOverrideManager.activePb2DoseU != null) {
                pb2Notified = true
            }
            // PB3 — same pattern
            if (!pb3Notified && mealOverrideManager.activePb3DoseU != null) {
                pb3Notified = true
            }
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

        // ── ICE diagnostic suffix ────────────────────────────────────────────
        // Emit ICE status into the user-visible reason string whenever the feature
        // is enabled — even when ICE is currently disabled by warmup/exercise/etc,
        // so the user can see WHY ICE isn't engaging.
        if (iceTrackerEnabled) {
            val obsMmolPerH = (observedIceMgdlPerH ?: 0.0) / 18.0
            val expMmolPerH = expectedIceMgdlPerH / 18.0
            val effMmolPerH = (iceMgdlPerHEffective ?: 0.0) / 18.0
            val srcLabel    = if (mealOverridesObserved) "exp" else "obs"
            // Disable tag now shows BOTH axes — hard disable wins, but observation
            // disable is shown too when meal isn't overriding it. If the meal IS
            // overriding observation disable, append "(meal_override)" so the user
            // can see ICE is still engaging despite the underlying observation issue.
            val displayDisableReason = iceDisableReason
                ?: iceSnapshot?.confidence?.disabled
            val disableTag = when {
                iceDisableReason != null ->
                    " disabled=${iceDisableReason.name}"
                iceObservationDisabled && hasMeal ->
                    " obs_disabled=${iceSnapshot!!.confidence.disabled!!.name}(meal_override)"
                iceObservationDisabled ->
                    " disabled=${iceSnapshot!!.confidence.disabled!!.name}"
                else -> ""
            }
            val blendWeight = if (!iceIsDisabled)
                (iceConfidenceScore * effectiveUserWeight).coerceIn(0.0, 1.0)
            else 0.0
            // Show effective weight separately when it differs from configured —
            // makes the announcement-floor override visible.
            val weightNote = if (effectiveUserWeight > configuredUserWeight)
                " uw=${"%.2f".format(configuredUserWeight)}→${"%.2f".format(effectiveUserWeight)}(meal_floor)"
            else
                " uw=${"%.2f".format(effectiveUserWeight)}"
            // Per-component breakdown — only shown when there's an announced meal,
            // since the carb/protein/fat split only makes sense in that context.
            // Reveals when "high ICE rate" is driven by carb peak vs PF plateau —
            // useful because COB display shows only carb absorption, so the ICE
            // rate can look surprisingly high when carbs are nearly absorbed but
            // the multi-hour PF plateau is still at full strength.
            // With multiple active layers: sum the breakdown across them.
            val componentNote = if (hasMeal) {
                val nowForDiag = dateUtil.now()
                val activeLayers = announcedMealManager.activeMeals.value.filter { it.isActive(nowForDiag) }
                var carbSum = 0.0
                var protSum = 0.0
                var fatSum  = 0.0
                for (m in activeLayers) {
                    val b = app.aaps.plugins.aps.smartInsulin.ice.MealCurveBuilder
                        .componentRatesAt(m, nowForDiag, carbLoadPerG)
                    carbSum += b.carbMgdlH
                    protSum += b.proteinMgdlH
                    fatSum  += b.fatMgdlH
                }
                " [carb=${"%.2f".format(carbSum / 18.0)}" +
                    " prot=${"%.2f".format(protSum / 18.0)}" +
                    " fat=${"%.2f".format(fatSum / 18.0)}]mmol/h"
            } else ""
            apsResult.reason += " | ICE: $iceMode $srcLabel=${"%.2f".format(effMmolPerH)}mmol/h" +
                componentNote +
                " (obs=${"%.2f".format(obsMmolPerH)} exp=${"%.2f".format(expMmolPerH)})" +
                " carbLoad=${"%.1f".format(carbLoadPerG)}mg/dL/g" +
                " conf=${"%.2f".format(iceConfidenceScore)}" +
                weightNote +
                " blend=${"%.2f".format(blendWeight)}" +
                " aggr×${"%.2f".format(iceAggrAdjust)}" +
                (if (iceIsDriving) " DRIVING" else "") +
                disableTag
        }
        // Announced-meal diagnostic — shows aggregated totals plus per-layer
        // entries so each layer's announce time, GI bucket, and macros are
        // visible. When there's only one layer, this reads exactly like before.
        if (hasMeal) {
            val nowMs = dateUtil.now()
            val active = announcedMealManager.activeMeals.value.filter { it.isActive(nowMs) }
            val remaining = announcedMealManager.remainingMacros(nowMs)
            val totalC = active.sumOf { it.carbsG }
            val totalP = active.sumOf { it.proteinG }
            val totalF = active.sumOf { it.fatG }
            val aggLabel = announcedMealManager.aggregatedGiBucketLabel(nowMs)
            val maxAge   = announcedMealManager.earliestAgeMinutes(nowMs)
            val maxDur   = announcedMealManager.longestRemainingMinutes(nowMs) + maxAge
            val carbStr = if (remaining != null)
                "carbs=${"%.0f".format(remaining.carbsG)}g/${"%.0f".format(totalC)}g"
            else "carbs=${"%.0f".format(totalC)}g"
            val protStr = if (remaining != null)
                "P=${"%.0f".format(remaining.proteinG)}g/${"%.0f".format(totalP)}g"
            else "P=${"%.0f".format(totalP)}g"
            val fatStr = if (remaining != null)
                "F=${"%.0f".format(remaining.fatG)}g/${"%.0f".format(totalF)}g"
            else "F=${"%.0f".format(totalF)}g"
            apsResult.reason += " | meal=$aggLabel×${active.size}" +
                " age=${maxAge}m/${maxDur}m" +
                " $carbStr $protStr $fatStr"
            // If multiple layers, append a compact per-layer list so user can
            // verify each addition. Single-layer case: omit since headline
            // line already shows it.
            if (active.size > 1) {
                val layerSummaries = active.joinToString(",") { m ->
                    val a = ((nowMs - m.announceTimestampMs) / 60_000L).toInt()
                    "${m.giBucket.name}(${a}m,c${"%.0f".format(m.carbsG)}p${"%.0f".format(m.proteinG)}f${"%.0f".format(m.fatG)}@${m.commitmentPct}%)"
                }
                apsResult.reason += " layers=[$layerSummaries]"
            }
        }

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
                    UnitDoubleKey.ApsSmartInsulinActivityHeavyTarget,
                    IntKey.ApsSmartInsulinActivityStepsLightMin,
                    IntKey.ApsSmartInsulinActivityStepsModerateMin,
                    IntKey.ApsSmartInsulinActivityStepsHeavyMin
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
            ),
            PreferenceSubScreenDef(
                key = "si_screen_pdp",
                titleResId = R.string.si_screen_pdp_title,
                items = listOf(
                    BooleanKey.ApsSmartInsulinPdpEnabled,
                    BooleanKey.ApsSmartInsulinPdpLearningEnabled,
                    DoubleKey.ApsSmartInsulinPdpCiStrength,
                    DoubleKey.ApsSmartInsulinPdpRisingStrength,
                    IntKey.ApsSmartInsulinPdpFadeMinutes,
                    IntKey.ApsSmartInsulinPdpMinReadings,
                    DoubleKey.ApsSmartInsulinPdpMaxBlendWeight,
                    DoubleKey.ApsSmartInsulinFastingMaxIob,
                    BooleanKey.ApsSmartInsulinPdpMealStuckEnabled,
                    IntKey.ApsSmartInsulinPdpMealStuckMinReadings,
                    IntKey.ApsSmartInsulinPdpMealRampMins,
                    DoubleKey.ApsSmartInsulinPdpMealMaxStrength
                )
            ),
            PreferenceSubScreenDef(
                key = "si_screen_ice",
                titleResId = R.string.si_screen_ice_title,
                items = listOf(
                    // Master toggle — all other ICE prefs depend on this via the
                    // dependency declared on each key.
                    BooleanKey.ApsSmartInsulinIceEnabled,
                    // Primary user knob — blends ICE influence into dosing
                    // (0.0 = off, 1.0 = full). Default 0.5 gives moderate influence.
                    DoubleKey.ApsSmartInsulinIceUserWeight,
                    // Magnitude thresholds — what counts as "real" ICE vs noise,
                    // and what counts as a "strong" signal worth acting aggressively on.
                    DoubleKey.ApsSmartInsulinIceFloorMgdlH,
                    DoubleKey.ApsSmartInsulinIceStrongMgdlH,
                    // When ICE confidence exceeds this threshold the loop suspends
                    // ISF/basal learners and overrides PDP to avoid double-counting.
                    DoubleKey.ApsSmartInsulinIceLearningBlockThreshold,
                    // How many consecutive cycles ICE must persist before influencing
                    // dose decisions — guards against single-reading spikes.
                    IntKey.ApsSmartInsulinIcePersistCycles,
                    // How many recent samples to score for consistency. Higher =
                    // smoother but slower-reacting confidence signal.
                    IntKey.ApsSmartInsulinIceConsistWindow,
                    // ── UAM-mode variant (ice-step28) ─────────────────────────
                    // Separate trust + threshold + cap + decay for the case
                    // where the loop detects a meal-like rise without an
                    // announcement. Lower defaults than COB because mis-detection
                    // costs hypos. All depend on the master ICE toggle above.
                    DoubleKey.ApsSmartInsulinUamIceUserWeight,
                    DoubleKey.ApsSmartInsulinUamIceLearningBlockThreshold,
                    DoubleKey.ApsSmartInsulinUamIceAggressionCap,
                    IntKey.ApsSmartInsulinUamIceDecayMinutes
                )
            )
        ),
        icon = pluginDescription.icon
    )
}