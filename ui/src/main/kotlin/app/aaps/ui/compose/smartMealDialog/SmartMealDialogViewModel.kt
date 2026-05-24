package app.aaps.ui.compose.smartMealDialog

import androidx.lifecycle.ViewModel
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.interfaces.Preferences
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * Which mode the dialog is operating in (ice-step27b).
 *
 *  - [ADD]:     announce as a new layer on top of any active meals. Default behaviour;
 *               the existing announce dialog has always done this via
 *               `announceMeal(replaceExisting = false)`.
 *  - [EDIT]:    update the currently-active meal's macros in place, preserving its
 *               absorption timer. Calls `editActiveMeal(...)` which silently no-ops
 *               when zero or 2+ layers are active — the help text in the dialog
 *               explains the constraint. Hides the meal-mode dropdown and pre-bolus
 *               cards because edit is a pure model update.
 *  - [REPLACE]: cancel all active meals and announce the new one from t=0. Calls
 *               `announceMeal(replaceExisting = true)`. Re-activates the meal-mode
 *               override and supports pre-boluses just like ADD.
 */
enum class DialogMode { ADD, EDIT, REPLACE }

data class SmartMealUiState(
    val dialogMode: DialogMode = DialogMode.ADD,
    // ── ICE-driven announcement (replaces ISF + duration) ────────────────────
    val carbsG: Double = 0.0,
    val proteinG: Double = 0.0,
    val fatG: Double = 0.0,
    val giBucketIndex: Int = 1,   // 0 = High (FAST), 1 = Medium, 2 = Low (SLOW)
    // ── Pre-bolus state (unchanged) ──────────────────────────────────────────
    val preBolus1Enabled: Boolean = false,
    val preBolus1U: Double = 0.0,
    val preBolus2Enabled: Boolean = false,
    val preBolus2U: Double = 0.0,
    val preBolus2DelayMins: Int = 60,
    val preBolus3Enabled: Boolean = false,
    val preBolus3U: Double = 0.0,
    val preBolus3DelayMins: Int = 30,    // delay AFTER PB2 fires
    val isMmol: Boolean = true,
    val maxPreBolus: Double = 3.0,
    val bolusStep: Double = 0.05,
    val activeModeName: String? = null,
    val pb2Pending: Boolean = false,
    val pb2StatusText: String = "",
    val pb3Pending: Boolean = false,
    val pb3StatusText: String = "",
    // ── ICE-step27c: per-layer snapshot for EDIT mode ────────────────────────
    // Collected from SmartInsulinOverview.overviewStateFlow. Drives the
    // per-meal editable cards rendered when dialogMode == EDIT.
    val activeMealLayers: List<SmartInsulinOverview.MealLayerInfo> = emptyList()
)

@HiltViewModel
class SmartMealDialogViewModel @Inject constructor(
    private val mealOverrideManager: MealOverrideManager,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val activePlugin: ActivePlugin,
    private val commandQueue: CommandQueue,
    private val preferences: Preferences,
    private val sp: SP,
    val dateUtil: DateUtil,
    val decimalFormatter: DecimalFormatter,
    private val uiInteraction: UiInteraction,
    private val rh: ResourceHelper
) : ViewModel() {

    // ice-step37: meal-mode dropdown removed from the dialog UI. All announced
    // meals route through MealMode.LUNCH internally — a single canonical "active
    // meal" identifier. Learners now gate on COB/P/F presence rather than
    // mealMode flavour, so the per-meal-type distinction (BREAKFAST/DINNER etc.)
    // no longer drives learner behaviour and isn't user-facing anywhere.
    private val internalMealMode = MealMode.LUNCH

    private val _uiState = MutableStateFlow(SmartMealUiState())
    val uiState: StateFlow<SmartMealUiState> = _uiState.asStateFlow()

    sealed class SideEffect {
        data class DeliveryError(val message: String) : SideEffect()
    }
    private val _sideEffect = Channel<SideEffect>()
    val sideEffect = _sideEffect.receiveAsFlow()

    init {
        viewModelScope.launch {
            refresh()
        }
        // ice-step27c: keep activeMealLayers mirrored from the plugin's
        // overviewStateFlow. Updates fire each invoke() cycle (ages tick up) and
        // also immediately after any meal mutation (announce / edit / cancel)
        // because the plugin pushes the state inside those handlers.
        viewModelScope.launch {
            activePlugin.smartInsulin?.overviewStateFlow?.collect { state ->
                _uiState.update {
                    it.copy(activeMealLayers = state?.activeMealLayers ?: emptyList())
                }
            }
        }
    }

    fun refresh() {
        val isMmol = profileUtil.units == GlucoseUnit.MMOL
        val maxPb = preferences.get(DoubleKey.ApsSmartInsulinMaxPreBolus)
        val bolusStep = activePlugin.activePump.pumpDescription.bolusStep
        val activeMode = mealOverrideManager.activeMealMode
        _uiState.update {
            it.copy(
                isMmol = isMmol,
                maxPreBolus = maxPb,
                bolusStep = bolusStep,
                activeModeName = activeMode?.label,
                pb2Pending = mealOverrideManager.preBolus2Pending,
                pb2StatusText = mealOverrideManager.preBolus2StatusText,
                pb3Pending = mealOverrideManager.preBolus3Pending,
                pb3StatusText = mealOverrideManager.preBolus3StatusText
            )
        }
    }

    /** Switch the dialog between Add / Edit / Replace flows (ice-step27b). */
    fun setDialogMode(mode: DialogMode) = _uiState.update { it.copy(dialogMode = mode) }

    fun setCarbsG(v: Double) = _uiState.update { it.copy(carbsG = v.coerceAtLeast(0.0)) }
    fun setProteinG(v: Double) = _uiState.update { it.copy(proteinG = v.coerceAtLeast(0.0)) }
    fun setFatG(v: Double) = _uiState.update { it.copy(fatG = v.coerceAtLeast(0.0)) }
    fun setGiBucketIndex(v: Int) = _uiState.update { it.copy(giBucketIndex = v.coerceIn(0, 2)) }
    fun setPreBolus1Enabled(v: Boolean) = _uiState.update { it.copy(preBolus1Enabled = v) }
    fun setPreBolus1U(v: Double) = _uiState.update { it.copy(preBolus1U = v) }
    fun setPreBolus2Enabled(v: Boolean) = _uiState.update { it.copy(preBolus2Enabled = v) }
    fun setPreBolus2U(v: Double) = _uiState.update { it.copy(preBolus2U = v) }
    fun setPreBolus2DelayMins(v: Int) = _uiState.update { it.copy(preBolus2DelayMins = v) }
    fun setPreBolus3Enabled(v: Boolean) = _uiState.update { it.copy(preBolus3Enabled = v) }
    fun setPreBolus3U(v: Double) = _uiState.update { it.copy(preBolus3U = v) }
    fun setPreBolus3DelayMins(v: Int) = _uiState.update { it.copy(preBolus3DelayMins = v) }

    fun cancelMode() { mealOverrideManager.cancelOverride(); refresh() }
    fun cancelPb2() { mealOverrideManager.cancelPreBolus2(); refresh() }
    fun cancelPb3() { mealOverrideManager.cancelPreBolus3(); refresh() }

    // ── ICE-step27c: per-layer actions for EDIT mode ─────────────────────────
    // The Composable drives one row per layer. Each row holds its own draft
    // state via rememberSaveable and calls saveLayerEdits / cancelLayer on
    // its own layerId. Cancel-all is the multi-layer wipe button.

    /** Save edits to a specific layer. Preserves its absorption timer. */
    fun saveLayerEdits(layerId: Long, carbsG: Double, proteinG: Double, fatG: Double, giBucketName: String) {
        activePlugin.smartInsulin?.editMealLayer(
            layerId      = layerId,
            carbsG       = carbsG,
            proteinG     = proteinG,
            fatG         = fatG,
            giBucketName = giBucketName
        )
    }

    /** Cancel a single layer by ID. The row will disappear from activeMealLayers. */
    fun cancelLayer(layerId: Long) {
        activePlugin.smartInsulin?.clearMealLayer(layerId)
    }

    /** Cancel all active meal layers at once. */
    fun cancelAllMealLayers() {
        activePlugin.smartInsulin?.clearAnnouncedMeal()
    }

    fun confirmAndActivate(onDeliveryError: (String) -> Unit, onDone: () -> Unit) {
        val s = _uiState.value

        // ── EDIT mode: no-op-and-dismiss ────────────────────────────────────
        // In ice-step27c, EDIT mode no longer goes through confirmAndActivate —
        // each per-layer row in the dialog has its own Save button that calls
        // saveLayerEdits(layerId, ...) directly. The dialog's bottom button in
        // EDIT mode is "Done" and dismisses without confirmation. Guarding here
        // anyway so a stray call can never re-fire announceMeal or duplicate a
        // layer if a button gets re-wired carelessly later.
        if (s.dialogMode == DialogMode.EDIT) {
            onDone()
            return
        }

        // ── ADD / REPLACE mode: announce + (optional) pre-bolus + mode override ─
        val maxPb = s.maxPreBolus
        val pb1 = if (s.preBolus1Enabled) s.preBolus1U.coerceAtMost(maxPb) else 0.0
        val mode = internalMealMode

        // Announce the meal to ICE (drives the expected-curve pre-positioning).
        // Fires regardless of whether PB1 is requested — the loop benefits from
        // knowing a meal is coming even if the user is deferring the bolus.
        // `replaceExisting` is true for REPLACE (wipes all layers first), false
        // for ADD (appends as a new layer).
        if (s.carbsG > 0.0 || s.proteinG > 0.0) {
            activePlugin.smartInsulin?.announceMeal(
                carbsG          = s.carbsG,
                proteinG        = s.proteinG,
                fatG            = s.fatG,
                giBucketName    = giBucketNameFor(s.giBucketIndex),
                commitmentPct   = 100,   // dialog inputs are an explicit commitment
                replaceExisting = (s.dialogMode == DialogMode.REPLACE)
            )
        }

        if (pb1 > 0.0) {
            // PB1 requested — send bolus FIRST, only activate mode if pump accepts it
            val info = DetailedBolusInfo().apply {
                insulin = pb1
                notes = "SmartMeal ${mode.label} pre-bolus 1"
                timestamp = dateUtil.now()
            }
            viewModelScope.launch {
                // ice-step41: AAPS dev branch made commandQueue.bolus() a suspend
                // function returning PumpEnactResult. Wrapping in viewModelScope.launch
                // so the dialog doesn't block while the pump delivers.
                val result = commandQueue.bolus(info)
                // Suspend call resumes on the same dispatcher as the launch (Main).
                // startMealMode + onDone do not need explicit thread posting.
                if (result.success) {
                    startMealMode(s)
                    onDone()
                } else {
                    Handler(Looper.getMainLooper()).post { onDeliveryError(result.comment) }
                }
            }
        } else {
            // No PB1 — activate mode immediately, no pump interaction needed
            startMealMode(s)
            onDone()
        }
    }

    private fun startMealMode(s: SmartMealUiState) {
        val mode = internalMealMode
        val maxPb = s.maxPreBolus
        val pb1 = if (s.preBolus1Enabled) s.preBolus1U.coerceAtMost(maxPb) else 0.0
        val pb2 = if (s.preBolus2Enabled) s.preBolus2U.coerceAtMost(maxPb) else 0.0
        val pb3 = if (s.preBolus3Enabled) s.preBolus3U.coerceAtMost(maxPb) else 0.0
        // Mode window derived from the GI bucket's absorption duration so it matches
        // when the ICE expected curve will go quiet.
        // Mode window derived from the effective absorption duration — for fatty
        // / high-protein meals the plateau extends well past the carb window, so
        // the meal-mode gate needs to stay open the whole time.
        val durationMin = effectiveDurationMinFor(
            giBucketIndex = s.giBucketIndex,
            carbsG        = s.carbsG,
            proteinG      = s.proteinG,
            fatG          = s.fatG
        )
        mealOverrideManager.activateOverride(
            mode = mode,
            doseU = if (pb1 > 0.0) pb1 else null,
            carbsG = s.carbsG.toInt(),
            modeWindowMs = TimeUnit.MINUTES.toMillis(durationMin.toLong()),
            preBolus2U = pb2,
            preBolus2DelayMs = if (s.preBolus2Enabled && pb2 > 0.0) TimeUnit.MINUTES.toMillis(s.preBolus2DelayMins.toLong()) else 0L,
            preBolus3U = pb3,
            preBolus3DelayMs = if (s.preBolus3Enabled && pb3 > 0.0) TimeUnit.MINUTES.toMillis(s.preBolus3DelayMins.toLong()) else 0L
        )
    }

    fun buildSummary(): String {
        val s = _uiState.value
        return buildString {
            // ice-step27b: lead with what action is about to be taken so the
            // confirmation dialog can't be misread as a generic announce.
            when (s.dialogMode) {
                DialogMode.ADD     -> appendLine("Action: Add new meal layer")
                DialogMode.EDIT    -> appendLine("Action: Edit active meal (preserves timer)")
                DialogMode.REPLACE -> appendLine("Action: REPLACE all active meals")
            }
            // ice-step37: removed "Mode: Lunch" line — internal mealMode is no
            // longer user-facing. The summary now leads straight to macros.
            appendLine("Macros: ${"%.0f".format(s.carbsG)}g carbs · ${"%.0f".format(s.proteinG)}g protein · ${"%.0f".format(s.fatG)}g fat")
            appendLine("GI: ${giLabel(s.giBucketIndex)} (${effectiveDurationMinFor(s.giBucketIndex, s.carbsG, s.proteinG, s.fatG)} min window)")
            // Pre-boluses only apply to ADD / REPLACE — EDIT hides those cards.
            if (s.dialogMode != DialogMode.EDIT) {
                if (s.preBolus1Enabled && s.preBolus1U > 0.0) appendLine("Pre-bolus 1: ${"%.2f".format(s.preBolus1U)}U (now)")
                if (s.preBolus2Enabled && s.preBolus2U > 0.0) appendLine("Pre-bolus 2: ${"%.2f".format(s.preBolus2U)}U in ${s.preBolus2DelayMins}min")
                if (s.preBolus3Enabled && s.preBolus3U > 0.0) appendLine("Pre-bolus 3: ${"%.2f".format(s.preBolus3U)}U ${s.preBolus3DelayMins}min after PB2")
            }
        }.trim()
    }

    /** GI bucket name passed across the module boundary as a String. Plugin parses to enum. */
    private fun giBucketNameFor(uiIndex: Int): String = when (uiIndex) {
        0    -> "FAST"   // High GI
        2    -> "SLOW"   // Low GI
        else -> "MEDIUM"
    }

    /** Total absorption duration in minutes, mirrors GiBucket.totalDurationMinutes in the plugin. */
    private fun giDurationMinFor(uiIndex: Int): Int = when (uiIndex) {
        0    -> 120   // FAST
        2    -> 360   // SLOW
        else -> 240   // MEDIUM
    }

    /**
     * Effective absorption-window duration accounting for fat/protein plateau.
     * Mirrors [AnnouncedMeal.effectiveTotalDurationMin] on the plugin side. The
     * meal-mode window needs to stay open until the loop's expected ICE curve
     * actually settles — for fatty / high-protein meals that's well past when
     * the carbs are done absorbing.
     */
    private fun effectiveDurationMinFor(
        giBucketIndex: Int,
        carbsG: Double,
        proteinG: Double,
        fatG: Double
    ): Int {
        val carbDur = if (carbsG > 0.0) giDurationMinFor(giBucketIndex) else 0
        val fpGrams = proteinG + fatG
        val fpDur = when {
            fpGrams <= 0.0  -> 0
            fpGrams < 30.0  -> 240   // 4h
            fpGrams < 80.0  -> 300   // 5h
            else            -> 360   // 6h
        }
        return maxOf(carbDur, fpDur, 60)   // never under 1h — covers carb-free, fat-free edge case
    }

    fun giLabel(uiIndex: Int): String = when (uiIndex) {
        0    -> "High GI"
        2    -> "Low GI"
        else -> "Medium GI"
    }
}