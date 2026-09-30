package app.aaps.ui.compose.smartMealDialog

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ui.ConfirmationLine
import app.aaps.core.data.ui.ConfirmationRole
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.bolus.BatchAction
import app.aaps.core.interfaces.bolus.BatchExecutor
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import app.aaps.core.interfaces.clientcontrol.FailureReason
import app.aaps.core.interfaces.clientcontrol.isNotDeliveryError
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventShowDialog
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.DoubleNonKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.clientcontrol.failText
import app.aaps.ui.UiStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class SmartMealDialogUiState(
    val mode: MealMode = MealMode.LUNCH,
    val durationMinutes: Int = 180,
    /** Display units; 0 = use the profile ISF. */
    val isf: Double = 0.0,
    val duraEnabled: Boolean = false,
    /** Display units; 0 = no floor. */
    val duraFloor: Double = 0.0,
    val duraStrength: Double = 1.0,
    val pb1Enabled: Boolean = false,
    val pb1U: Double = 0.0,
    val pb2Enabled: Boolean = false,
    val pb2U: Double = 0.0,
    val pb2DelayMinutes: Int = 30,
    val pb3Enabled: Boolean = false,
    val pb3U: Double = 0.0,
    val pb3DelayMinutes: Int = 30,

    // Config
    val units: GlucoseUnit = GlucoseUnit.MGDL,
    val maxPreBolus: Double = 0.0,
    val bolusStep: Double = 0.1,

    // What is running now
    val activeMode: MealMode? = null,
    val pb2Pending: Boolean = false,
    val pb2Status: String = "",
    val pb3Pending: Boolean = false,
    val pb3Status: String = ""
) {

    val isMmol: Boolean get() = units == GlucoseUnit.MMOL
    val unitsLabel: String get() = if (isMmol) "mmol/L" else "mg/dL"
}

/**
 * The Smart Meal dialog: pick a meal mode, its ISF and DURA settings, and up to three pre-boluses.
 *
 * Logic is the 3.4 `SmartMealDialog` unchanged. What differs is the delivery of pre-bolus 1, which goes
 * through [BatchExecutor] like every other 4.0 bolus dialog, so it is capped by the constraint checker and
 * blocked by the running-mode gate instead of going straight to the command queue. Pre-boluses 2 and 3 are
 * still scheduled and delivered by [MealOverrideManager] from the loop, behind its own safety gates.
 *
 * Per-mode ISF and DURA floor are stored in mg/dL and read raw, never through the unit-detecting read, which
 * mistakes an ISF under 36 mg/dL/U for mmol.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class SmartMealDialogViewModel(
    private val mealOverrideManager: MealOverrideManager,
    private val preferences: Preferences,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val activePlugin: ActivePlugin,
    private val ch: ConcentrationHelper,
    private val batchExecutor: BatchExecutor,
    private val rxBus: RxBus,
    val rh: TextResolver,
    val dateUtil: DateUtil,
    val decimalFormatter: DecimalFormatter,
    private val appScope: CoroutineScope
) : ViewModel() {

    private val _uiState = MutableStateFlow(SmartMealDialogUiState())
    val uiState: StateFlow<SmartMealDialogUiState> = _uiState.asStateFlow()

    sealed class SideEffect {
        data class ShowDeliveryError(val comment: String) : SideEffect()

        /** Confirmation to show. [bolusId] is the prepared pre-bolus 1, or null when there is none. */
        data class ShowConfirmation(val bolusId: Long?, val lines: List<ConfirmationLine>) : SideEffect()
    }

    private val _sideEffect = MutableSharedFlow<SideEffect>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val sideEffect: SharedFlow<SideEffect> = _sideEffect.asSharedFlow()

    val modes: List<MealMode> = listOf(MealMode.BREAKFAST, MealMode.LUNCH, MealMode.DINNER, MealMode.LOW_CARB, MealMode.EXTENDED)

    init {
        val defaultPb2 = preferences.get(DoubleKey.ApsSmartInsulinPreBolus2DefaultU)
        _uiState.update {
            it.copy(
                units = profileFunction.getUnits(),
                maxPreBolus = preferences.get(DoubleKey.ApsSmartInsulinMaxPreBolus),
                bolusStep = activePlugin.activePump.pumpDescription.bolusStep,
                pb2U = defaultPb2,
                pb2DelayMinutes = preferences.get(IntKey.ApsSmartInsulinPreBolus2DefaultDelayMins),
                pb3U = defaultPb2
            )
        }
        loadMode(MealMode.LUNCH)
        refreshActive()
    }

    fun refreshActive() {
        _uiState.update {
            it.copy(
                activeMode = mealOverrideManager.activeMealMode,
                pb2Pending = mealOverrideManager.preBolus2Pending,
                pb2Status = mealOverrideManager.preBolus2StatusText,
                pb3Pending = mealOverrideManager.preBolus3Pending,
                pb3Status = mealOverrideManager.preBolus3StatusText
            )
        }
    }

    // ── Per-mode settings ────────────────────────────────────────────────────

    private fun isfKeyFor(mode: MealMode): UnitDoubleKey? = when (mode) {
        MealMode.BREAKFAST -> UnitDoubleKey.ApsSmartInsulinBreakfastIsf
        MealMode.LUNCH     -> UnitDoubleKey.ApsSmartInsulinLunchIsf
        MealMode.DINNER    -> UnitDoubleKey.ApsSmartInsulinDinnerIsf
        MealMode.LOW_CARB  -> UnitDoubleKey.ApsSmartInsulinLowCarbIsf
        MealMode.EXTENDED  -> UnitDoubleKey.ApsSmartInsulinExtendedIsf
        else               -> null
    }

    private fun duraEnabledKeyFor(mode: MealMode): BooleanNonKey? = when (mode) {
        MealMode.BREAKFAST -> BooleanNonKey.ApsSmartInsulinBreakfastDuraEnabled
        MealMode.LUNCH     -> BooleanNonKey.ApsSmartInsulinLunchDuraEnabled
        MealMode.DINNER    -> BooleanNonKey.ApsSmartInsulinDinnerDuraEnabled
        MealMode.LOW_CARB  -> BooleanNonKey.ApsSmartInsulinLowCarbDuraEnabled
        MealMode.EXTENDED  -> BooleanNonKey.ApsSmartInsulinExtendedDuraEnabled
        else               -> null
    }

    private fun duraFloorKeyFor(mode: MealMode): DoubleNonKey? = when (mode) {
        MealMode.BREAKFAST -> DoubleNonKey.ApsSmartInsulinBreakfastDuraFloor
        MealMode.LUNCH     -> DoubleNonKey.ApsSmartInsulinLunchDuraFloor
        MealMode.DINNER    -> DoubleNonKey.ApsSmartInsulinDinnerDuraFloor
        MealMode.LOW_CARB  -> DoubleNonKey.ApsSmartInsulinLowCarbDuraFloor
        MealMode.EXTENDED  -> DoubleNonKey.ApsSmartInsulinExtendedDuraFloor
        else               -> null
    }

    private fun duraStrengthKeyFor(mode: MealMode): DoubleNonKey? = when (mode) {
        MealMode.BREAKFAST -> DoubleNonKey.ApsSmartInsulinBreakfastDuraStrength
        MealMode.LUNCH     -> DoubleNonKey.ApsSmartInsulinLunchDuraStrength
        MealMode.DINNER    -> DoubleNonKey.ApsSmartInsulinDinnerDuraStrength
        MealMode.LOW_CARB  -> DoubleNonKey.ApsSmartInsulinLowCarbDuraStrength
        MealMode.EXTENDED  -> DoubleNonKey.ApsSmartInsulinExtendedDuraStrength
        else               -> null
    }

    private fun toDisplay(mgdl: Double): Double = if (mgdl == 0.0) 0.0 else profileUtil.fromMgdlToUnits(mgdl, uiState.value.units)
    private fun toMgdl(display: Double): Double = if (display == 0.0) 0.0 else profileUtil.convertToMgdl(display, uiState.value.units)

    private fun loadMode(mode: MealMode) {
        val isf = isfKeyFor(mode)?.let { toDisplay(preferences.getRaw(it)) } ?: 0.0
        val duraEnabled = duraEnabledKeyFor(mode)?.let { preferences.get(it) } ?: false
        val duraFloor = duraFloorKeyFor(mode)?.let { toDisplay(preferences.get(it)) } ?: 0.0
        val duraStrength = duraStrengthKeyFor(mode)?.let { preferences.get(it) } ?: 1.0
        _uiState.update { it.copy(mode = mode, isf = isf, duraEnabled = duraEnabled, duraFloor = duraFloor, duraStrength = duraStrength) }
    }

    // ── Input ────────────────────────────────────────────────────────────────

    fun selectMode(mode: MealMode) = loadMode(mode)
    fun updateDuration(v: Double) = _uiState.update { it.copy(durationMinutes = v.toInt().coerceIn(30, 480)) }
    fun updateIsf(v: Double) = _uiState.update { it.copy(isf = v.coerceAtLeast(0.0)) }
    fun updateDuraEnabled(v: Boolean) = _uiState.update { it.copy(duraEnabled = v) }
    fun updateDuraFloor(v: Double) = _uiState.update { it.copy(duraFloor = v.coerceAtLeast(0.0)) }
    fun updateDuraStrength(v: Double) = _uiState.update { it.copy(duraStrength = v.coerceIn(0.0, 5.0)) }
    fun updatePb1Enabled(v: Boolean) = _uiState.update { it.copy(pb1Enabled = v) }
    fun updatePb1(v: Double) = _uiState.update { it.copy(pb1U = v.coerceIn(0.0, it.maxPreBolus)) }
    fun addPb1(increment: Double) = _uiState.update { it.copy(pb1U = (it.pb1U + increment).coerceAtMost(it.maxPreBolus)) }
    fun updatePb2Enabled(v: Boolean) = _uiState.update { it.copy(pb2Enabled = v) }
    fun updatePb2(v: Double) = _uiState.update { it.copy(pb2U = v.coerceIn(0.0, it.maxPreBolus)) }
    fun updatePb2Delay(v: Double) = _uiState.update { it.copy(pb2DelayMinutes = v.toInt().coerceIn(5, 120)) }
    fun updatePb3Enabled(v: Boolean) = _uiState.update { it.copy(pb3Enabled = v) }
    fun updatePb3(v: Double) = _uiState.update { it.copy(pb3U = v.coerceIn(0.0, it.maxPreBolus)) }
    fun updatePb3Delay(v: Double) = _uiState.update { it.copy(pb3DelayMinutes = v.toInt().coerceIn(5, 120)) }

    // ── Cancel what is running ───────────────────────────────────────────────

    fun cancelMode() = mealOverrideManager.cancelOverride()
    fun cancelPb2() = mealOverrideManager.cancelPreBolus2()
    fun cancelPb3() = mealOverrideManager.cancelPreBolus3()

    // ── Confirm and activate ─────────────────────────────────────────────────

    /** The state the user confirmed, so activation uses exactly what the confirmation showed. */
    private var confirmedState: SmartMealDialogUiState? = null

    private fun SmartMealDialogUiState.pb1(): Double =
        if (pb1Enabled && pb1U > 0.0) Round.floorTo(pb1U.coerceAtMost(maxPreBolus), ch.bolusStep(pb1U)) else 0.0

    private fun SmartMealDialogUiState.pb2(): Double = if (pb2Enabled && pb2U > 0.0) pb2U.coerceAtMost(maxPreBolus) else 0.0
    private fun SmartMealDialogUiState.pb3(): Double = if (pb3Enabled && pb3U > 0.0) pb3U.coerceAtMost(maxPreBolus) else 0.0

    private fun fmtU(u: Double, step: Double) = decimalFormatter.toPumpSupportedBolus(u, step)
    private fun fmtBg(v: Double, mmol: Boolean) = if (mmol) decimalFormatter.to2Decimal(v) else decimalFormatter.to0Decimal(v)

    private fun modeLines(state: SmartMealDialogUiState): List<ConfirmationLine> = buildList {
        add(ConfirmationLine(ConfirmationRole.PRIMARY, rh.gs(UiStrings.si_confirm_mode, state.mode.label)))
        add(ConfirmationLine(ConfirmationRole.NORMAL, rh.gs(UiStrings.si_confirm_duration, state.durationMinutes)))
        add(
            if (state.isf > 0.0) ConfirmationLine(ConfirmationRole.NORMAL, rh.gs(UiStrings.si_confirm_isf, "${fmtBg(state.isf, state.isMmol)} ${state.unitsLabel}"))
            else ConfirmationLine(ConfirmationRole.NORMAL, rh.gs(UiStrings.si_confirm_isf_profile))
        )
        if (state.duraEnabled)
            add(
                ConfirmationLine(
                    ConfirmationRole.NORMAL,
                    rh.gs(UiStrings.si_confirm_dura, "${fmtBg(state.duraFloor, state.isMmol)} ${state.unitsLabel}", decimalFormatter.to1Decimal(state.duraStrength))
                )
            )
        if (state.pb1Enabled && state.pb1U > state.maxPreBolus)
            add(ConfirmationLine(ConfirmationRole.WARNING, rh.gs(UiStrings.si_confirm_capped, fmtU(state.maxPreBolus, state.bolusStep))))
        val pb2 = state.pb2()
        if (pb2 > 0.0)
            add(ConfirmationLine(ConfirmationRole.BOLUS, rh.gs(UiStrings.si_confirm_pb2, fmtU(pb2, state.bolusStep), state.pb2DelayMinutes)))
        val pb3 = state.pb3()
        if (pb3 > 0.0)
            add(ConfirmationLine(ConfirmationRole.BOLUS, rh.gs(UiStrings.si_confirm_pb3, fmtU(pb3, state.bolusStep), state.pb3DelayMinutes)))
    }

    /**
     * Build the confirmation. With a pre-bolus 1, the bolus is PREPARED first so the confirmation carries the
     * capped amount the pump will actually get; the mode is only activated once the user confirms.
     */
    fun prepareAndConfirm() {
        appScope.launch {
            val state = uiState.value
            confirmedState = state
            val lines = modeLines(state)
            val pb1 = state.pb1()
            if (pb1 <= 0.0) {
                _sideEffect.tryEmit(SideEffect.ShowConfirmation(null, lines))
                return@launch
            }
            val bolus = BatchAction.Bolus(
                insulin = pb1, carbs = 0, carbsTimeOffsetMinutes = 0, carbsDurationHours = 0, recordOnly = false,
                notes = rh.gs(UiStrings.si_prebolus_note, state.mode.label), timestamp = dateUtil.now(), iCfg = null
            )
            when (val prepared = batchExecutor.prepare(listOf(bolus), Sources.InsulinDialog, rh.gs(InterfacesStrings.bolus))) {
                is ActionProgress.Prepared -> _sideEffect.tryEmit(SideEffect.ShowConfirmation(prepared.id, lines + prepared.lines))
                is ActionProgress.Rejected -> when {
                    // Capped to nothing: the mode can still start, just without a pre-bolus.
                    prepared.reason == FailureReason.NoAction -> _sideEffect.tryEmit(SideEffect.ShowConfirmation(null, lines))
                    prepared.reason.isNotDeliveryError()      -> rxBus.send(EventShowDialog.Ok(title = rh.gs(InterfacesStrings.bolus), message = rh.gs(prepared.reason.failText())))
                    else                                      -> prepared.detail?.let { _sideEffect.tryEmit(SideEffect.ShowDeliveryError(it)) }
                }

                else                       -> Unit
            }
        }
    }

    /** Save the per-mode settings, start the mode, then deliver the prepared pre-bolus 1 if there is one. */
    fun commit(bolusId: Long?) {
        val state = confirmedState ?: return
        appScope.launch {
            val mode = state.mode
            isfKeyFor(mode)?.let { preferences.put(it, toMgdl(state.isf)) }
            val duraFloorMgdl = if (state.duraEnabled) toMgdl(state.duraFloor) else 0.0
            duraEnabledKeyFor(mode)?.let { preferences.put(it, state.duraEnabled) }
            duraFloorKeyFor(mode)?.let { preferences.put(it, duraFloorMgdl) }
            duraStrengthKeyFor(mode)?.let { preferences.put(it, state.duraStrength) }

            val pb1 = if (bolusId != null) state.pb1() else 0.0
            mealOverrideManager.activateOverride(
                mode = mode,
                doseU = if (pb1 > 0.0) pb1 else null,
                carbsG = 0,
                modeWindowMs = state.durationMinutes * 60_000L,
                preBolus2U = state.pb2(),
                preBolus2DelayMs = if (state.pb2() > 0.0) state.pb2DelayMinutes * 60_000L else 0L,
                preBolus3U = state.pb3(),
                preBolus3DelayMs = if (state.pb3() > 0.0) state.pb3DelayMinutes * 60_000L else 0L,
                duraEnabled = state.duraEnabled,
                duraFloorMgdl = duraFloorMgdl,
                duraStrength = state.duraStrength
            )

            if (bolusId != null) {
                val result = batchExecutor.commit(bolusId, Sources.InsulinDialog, rh.gs(InterfacesStrings.bolus))
                if (result is ActionProgress.Rejected) {
                    if (result.reason.isNotDeliveryError())
                        rxBus.send(EventShowDialog.Ok(title = rh.gs(InterfacesStrings.bolus), message = rh.gs(result.reason.failText())))
                    else result.detail?.let { _sideEffect.tryEmit(SideEffect.ShowDeliveryError(it)) }
                }
            }
        }
    }
}
