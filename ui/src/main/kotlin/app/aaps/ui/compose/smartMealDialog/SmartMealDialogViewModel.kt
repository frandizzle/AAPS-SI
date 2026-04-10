package app.aaps.ui.compose.smartMealDialog

import androidx.lifecycle.ViewModel
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.interfaces.Preferences
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class SmartMealUiState(
    val selectedModeIndex: Int = 1,
    val durationMins: Int = 180,
    val isfValue: Double = 0.0,
    val preBolus1Enabled: Boolean = false,
    val preBolus1U: Double = 0.0,
    val preBolus2Enabled: Boolean = false,
    val preBolus2U: Double = 0.0,
    val preBolus2DelayMins: Int = 60,
    val isMmol: Boolean = true,
    val maxPreBolus: Double = 3.0,
    val bolusStep: Double = 0.05,
    val activeModeName: String? = null,
    val pb2Pending: Boolean = false,
    val pb2StatusText: String = ""
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

    val modeList = listOf(
        MealMode.BREAKFAST, MealMode.LUNCH, MealMode.DINNER,
        MealMode.LOW_CARB, MealMode.EXTENDED
    )

    private val _uiState = MutableStateFlow(SmartMealUiState())
    val uiState: StateFlow<SmartMealUiState> = _uiState.asStateFlow()

    sealed class SideEffect {
        data class DeliveryError(val message: String) : SideEffect()
        data object Done : SideEffect()
    }
    private val _sideEffect = Channel<SideEffect>()
    val sideEffect = _sideEffect.receiveAsFlow()

    init { refresh() }

    fun refresh() {
        val isMmol = profileUtil.units == GlucoseUnit.MMOL
        val maxPb = preferences.get(DoubleKey.ApsSmartInsulinMaxPreBolus)
        val bolusStep = activePlugin.activePump.pumpDescription.bolusStep
        val activeMode = mealOverrideManager.activeMealMode
        val defaultIsfMgdl = sp.getDouble(
            UnitDoubleKey.ApsSmartInsulinLunchIsf.key,
            UnitDoubleKey.ApsSmartInsulinLunchIsf.defaultValue
        )
        val defaultIsf = if (defaultIsfMgdl == 0.0) 0.0
        else if (isMmol) defaultIsfMgdl / 18.0 else defaultIsfMgdl
        _uiState.update {
            it.copy(
                isMmol = isMmol,
                maxPreBolus = maxPb,
                bolusStep = bolusStep,
                activeModeName = activeMode?.label,
                pb2Pending = mealOverrideManager.preBolus2Pending,
                pb2StatusText = mealOverrideManager.preBolus2StatusText,
                isfValue = defaultIsf
            )
        }
    }

    fun setModeIndex(index: Int) {
        val isMmol = _uiState.value.isMmol
        val mode = modeList.getOrElse(index) { MealMode.LUNCH }
        val key = isfKeyFor(mode)
        val isfMgdl = if (key != null) sp.getDouble(key.key, key.defaultValue) else 0.0
        val isf = if (isfMgdl == 0.0) 0.0 else if (isMmol) isfMgdl / 18.0 else isfMgdl
        _uiState.update { it.copy(selectedModeIndex = index, isfValue = isf) }
    }

    fun setDuration(mins: Int) = _uiState.update { it.copy(durationMins = mins) }
    fun setIsf(v: Double) = _uiState.update { it.copy(isfValue = v) }
    fun setPreBolus1Enabled(v: Boolean) = _uiState.update { it.copy(preBolus1Enabled = v) }
    fun setPreBolus1U(v: Double) = _uiState.update { it.copy(preBolus1U = v) }
    fun setPreBolus2Enabled(v: Boolean) = _uiState.update { it.copy(preBolus2Enabled = v) }
    fun setPreBolus2U(v: Double) = _uiState.update { it.copy(preBolus2U = v) }
    fun setPreBolus2DelayMins(v: Int) = _uiState.update { it.copy(preBolus2DelayMins = v) }

    fun cancelMode() { mealOverrideManager.cancelOverride(); refresh() }
    fun cancelPb2() { mealOverrideManager.cancelPreBolus2(); refresh() }

    fun confirmAndActivate(onDeliveryError: (String) -> Unit, onDone: () -> Unit) {
        val s = _uiState.value
        val maxPb = s.maxPreBolus
        val pb1 = if (s.preBolus1Enabled) s.preBolus1U.coerceAtMost(maxPb) else 0.0

        // Save ISF preference regardless of bolus outcome
        val mode = modeList[s.selectedModeIndex]
        val key = isfKeyFor(mode)
        if (key != null) {
            val isfMgdl = if (s.isfValue == 0.0) 0.0
            else if (s.isMmol) s.isfValue * 18.0 else s.isfValue
            sp.putDouble(key.key, isfMgdl)
        }

        if (pb1 > 0.0) {
            // PB1 requested — send bolus FIRST, only activate mode if pump accepts it
            val info = DetailedBolusInfo().apply {
                insulin = pb1
                notes = "SmartMeal ${mode.label} pre-bolus 1"
                timestamp = dateUtil.now()
            }
            commandQueue.bolus(info, object : Callback() {
                override fun run() {
                    // Callback runs on worker thread — post to main thread for nav safety
                    Handler(Looper.getMainLooper()).post {
                        if (result.success) {
                            startMealMode(s)
                            onDone()
                            viewModelScope.launch { _sideEffect.send(SideEffect.Done) }
                        } else {
                            onDeliveryError(result.comment)
                        }
                    }
                }
            })
        } else {
            // No PB1 — activate mode immediately, no pump interaction needed
            startMealMode(s)
            onDone()
        }
    }

    private fun startMealMode(s: SmartMealUiState) {
        val mode = modeList[s.selectedModeIndex]
        val maxPb = s.maxPreBolus
        val pb1 = if (s.preBolus1Enabled) s.preBolus1U.coerceAtMost(maxPb) else 0.0
        val pb2 = if (s.preBolus2Enabled) s.preBolus2U.coerceAtMost(maxPb) else 0.0
        mealOverrideManager.activateOverride(
            mode = mode,
            doseU = if (pb1 > 0.0) pb1 else null,
            carbsG = 0,
            modeWindowMs = TimeUnit.MINUTES.toMillis(s.durationMins.toLong()),
            preBolus2U = pb2,
            preBolus2DelayMs = if (s.preBolus2Enabled && pb2 > 0.0) TimeUnit.MINUTES.toMillis(s.preBolus2DelayMins.toLong()) else 0L
        )
    }

    fun buildSummary(): String {
        val s = _uiState.value
        val mode = modeList[s.selectedModeIndex]
        val isfStr = if (s.isfValue > 0.0) "${"%.1f".format(s.isfValue)} ${if (s.isMmol) "mmol/U" else "mg/dL/U"}" else "Profile ISF"
        return buildString {
            appendLine("Mode: ${mode.label}")
            appendLine("Duration: ${s.durationMins} min")
            appendLine("ISF: $isfStr")
            if (s.preBolus1Enabled && s.preBolus1U > 0.0) appendLine("Pre-bolus 1: ${"%.2f".format(s.preBolus1U)}U (now)")
            if (s.preBolus2Enabled && s.preBolus2U > 0.0) appendLine("Pre-bolus 2: ${"%.2f".format(s.preBolus2U)}U in ${s.preBolus2DelayMins}min")
        }.trim()
    }

    private fun isfKeyFor(mode: MealMode): UnitDoubleKey? = when (mode) {
        MealMode.BREAKFAST -> UnitDoubleKey.ApsSmartInsulinBreakfastIsf
        MealMode.LUNCH     -> UnitDoubleKey.ApsSmartInsulinLunchIsf
        MealMode.DINNER    -> UnitDoubleKey.ApsSmartInsulinDinnerIsf
        MealMode.LOW_CARB  -> UnitDoubleKey.ApsSmartInsulinLowCarbIsf
        MealMode.EXTENDED  -> UnitDoubleKey.ApsSmartInsulinExtendedIsf
        else               -> null
    }
}