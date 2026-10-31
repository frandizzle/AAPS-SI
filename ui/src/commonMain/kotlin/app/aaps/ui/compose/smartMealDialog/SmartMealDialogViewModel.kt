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
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
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
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.DoubleNonKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.clientcontrol.failText
import app.aaps.core.interfaces.smartInsulin.MealEstimateCalibration
import app.aaps.core.interfaces.smartInsulin.MealOverrideState
import app.aaps.core.interfaces.smartInsulin.FoodLog
import app.aaps.core.interfaces.smartInsulin.FoodLogEntry
import app.aaps.core.interfaces.smartInsulin.LearnedMeal
import app.aaps.core.interfaces.smartInsulin.SavedMealLearning
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.smartMealDialog.food.FoodItem
import app.aaps.ui.compose.smartMealDialog.food.FoodLookupResult
import app.aaps.ui.compose.smartMealDialog.food.FoodSource
import app.aaps.ui.compose.smartMealDialog.food.MealAddition
import app.aaps.ui.compose.smartMealDialog.food.MealDosePlanner
import app.aaps.ui.compose.smartMealDialog.food.MealFoodRepository
import app.aaps.ui.compose.smartMealDialog.food.MealPlan
import app.aaps.ui.compose.smartMealDialog.food.MealPlanSettings
import app.aaps.ui.compose.smartMealDialog.food.MealTotals
import app.aaps.ui.compose.smartMealDialog.food.RunningMeal
import app.aaps.ui.compose.smartMealDialog.food.SaveResult
import app.aaps.ui.compose.smartMealDialog.food.SavedFood
import app.aaps.ui.compose.smartMealDialog.food.SavedFoodList
import app.aaps.ui.compose.smartMealDialog.food.SavedFoodStore
import app.aaps.ui.compose.smartMealDialog.food.totals
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
    val activeRemainingMin: Int = 0,
    val pb2Pending: Boolean = false,
    val pb2Status: String = "",
    val pb3Pending: Boolean = false,
    val pb3Status: String = "",
    /** Pre-bolus 2 and 3 of the running mode that still wait to fire, 0 when none. */
    val runningPb2WaitingU: Double = 0.0,
    val runningPb3WaitingU: Double = 0.0,
    val runningDura: Boolean = false,
    val runningPfAfter: Boolean = false,

    /** Start Protein/Fat when this mode runs out. */
    val pfAfter: Boolean = false,

    // Add to meal: more food for the meal mode that is running
    /** A meal mode started from this dialog is running, so food can be added to it. */
    val canAdd: Boolean = false,
    /** True: the dialog adds to the running meal. False: it starts a new one, which replaces the running one. */
    val addMode: Boolean = false,
    val addNowU: Double = 0.0,
    val addPb2U: Double = 0.0,
    val addPb3U: Double = 0.0,
    /** The mode runs at least this many more minutes. */
    val addMinutesLeft: Int = 0,
    val addDura: Boolean = false,
    /** The plan from the last "Fill in" in add mode. Null before. */
    val addition: MealAddition? = null,

    // Meal estimate (photo, description, barcode)
    val estimatesEnabled: Boolean = false,
    val description: String = "",
    val hasPhoto: Boolean = false,
    val foodItems: List<FoodItem> = emptyList(),
    val lookupBusy: Boolean = false,
    val lookupError: FoodLookupResult.Reason? = null,
    val lookupDetail: String = "",
    /** The plan from the last "Fill in", shown as a summary. Null before. */
    val plan: MealPlan? = null,
    /** True when "Fill in" found no carb ratio in the profile. */
    val planNoRatio: Boolean = false,
    val calibrationFactor: Double = 1.0,
    val calibrationMeals: Int = 0,

    // My foods
    /** Saved foods and meals, most used first. */
    val savedFoods: List<SavedFood> = emptyList(),
    /** The last foods looked up or typed in, newest first. */
    val recentFoods: List<FoodItem> = emptyList(),
    /** What the last save did, shown until the next one. Null before. */
    val lastSave: SaveResult? = null,
    /** The food list is one saved meal, so the meal learns about itself. Null for any other list. */
    val sourceMeal: String? = null,
    /** What the saved meals have learned, by name. */
    val learnedMeals: Map<String, LearnedMeal> = emptyMap(),
    /** What all meals together have learned about each part, or null before the first meal. */
    val generalLearned: LearnedMeal? = null,

    /** A saved meal opened for editing: its foods are in the list, and "Save changes" saves them back. */
    val editingMeal: String? = null,
    /** The food list was logged without a meal, this many kcal. Shown until the list changes. */
    val loggedKcal: Double? = null,
    /** Log only was asked for a list that looks wrong: its kcal, while the user is asked to check. */
    val logCheckKcal: Double? = null
) {

    /** What [sourceMeal] has learned, or null. */
    val sourceLearned: LearnedMeal? get() = sourceMeal?.let { learnedMeals[it] }


    val foodTotals: MealTotals get() = foodItems.totals()

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
    private val mealFoodRepository: MealFoodRepository,
    private val savedFoodStore: SavedFoodStore,
    private val savedMealLearning: SavedMealLearning,
    private val foodLog: FoodLog,
    private val mealEstimateCalibration: MealEstimateCalibration,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val iobCobCalculator: IobCobCalculator,
    private val constraintsChecker: ConstraintsChecker,
    private val appScope: CoroutineScope
) : ViewModel() {

    /** The meal photo to send with the next estimate. Kept out of the UI state: it is only bytes to send. */
    private var photoJpeg: ByteArray? = null

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
                pb3U = defaultPb2,
                estimatesEnabled = mealFoodRepository.estimatesEnabled,
                calibrationFactor = mealEstimateCalibration.factor,
                calibrationMeals = mealEstimateCalibration.mealsUsed,
                savedFoods = savedFoodStore.foods,
                recentFoods = savedFoodStore.recent,
                learnedMeals = learnedMeals(),
                generalLearned = savedMealLearning.general()
            )
        }
        loadMode(MealMode.LUNCH)
        refreshActive()
        // A meal is running: the most likely wish is to add to it, not to replace it.
        if (uiState.value.canAdd) updateAddMode(true)
    }

    fun refreshActive() {
        val running = mealOverrideManager.activeState
        _uiState.update {
            val canAdd = running != null && running.mode in modes
            it.copy(
                activeMode = mealOverrideManager.activeMealMode,
                activeRemainingMin = (mealOverrideManager.modeTimeRemainingMs / 60_000L).toInt(),
                pb2Pending = mealOverrideManager.preBolus2Pending,
                pb2Status = mealOverrideManager.preBolus2StatusText,
                pb3Pending = mealOverrideManager.preBolus3Pending,
                pb3Status = mealOverrideManager.preBolus3StatusText,
                runningPb2WaitingU = running?.takeIf { r -> r.preBolus2Pending }?.preBolus2U ?: 0.0,
                runningPb3WaitingU = running?.takeIf { r -> r.preBolus3Pending }?.preBolus3U ?: 0.0,
                runningDura = running?.duraEnabled == true,
                runningPfAfter = running?.pfAfter == true,
                canAdd = canAdd,
                addMode = it.addMode && canAdd
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
        val duraFloor = duraFloorKeyFor(mode)?.let { toDisplay(preferences.get(it)) } ?: 0.0
        val duraStrength = duraStrengthKeyFor(mode)?.let { preferences.get(it) } ?: 1.0
        // DURA starts off for every meal: the pre-boluses and the mode length cover it. Its floor and strength
        // are remembered, so switching it on is one tap.
        _uiState.update { it.copy(mode = mode, isf = isf, duraEnabled = false, duraFloor = duraFloor, duraStrength = duraStrength) }
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

    fun updatePfAfter(v: Boolean) = _uiState.update { it.copy(pfAfter = v) }

    // ── Add to meal ──────────────────────────────────────────────────────────

    /** Switches between adding to the running meal and starting a new one. Adding starts from nothing extra. */
    fun updateAddMode(v: Boolean) = _uiState.update {
        if (v && !it.canAdd) it
        else it.copy(
            addMode = v, addNowU = 0.0, addPb2U = 0.0, addPb3U = 0.0, addMinutesLeft = it.activeRemainingMin,
            addDura = false, addition = null, plan = null, pfAfter = false
        )
    }

    fun updateAddNow(v: Double) = _uiState.update { it.copy(addNowU = v.coerceIn(0.0, it.maxPreBolus)) }
    fun addAddNow(increment: Double) = _uiState.update { it.copy(addNowU = (it.addNowU + increment).coerceAtMost(it.maxPreBolus)) }
    fun updateAddPb2(v: Double) = _uiState.update { it.copy(addPb2U = v.coerceIn(0.0, (it.maxPreBolus - it.runningPb2WaitingU).coerceAtLeast(0.0))) }
    fun updateAddPb3(v: Double) = _uiState.update { it.copy(addPb3U = v.coerceIn(0.0, (it.maxPreBolus - it.runningPb3WaitingU).coerceAtLeast(0.0))) }
    fun updateAddMinutesLeft(v: Double) = _uiState.update { it.copy(addMinutesLeft = v.toInt().coerceIn(0, 480)) }
    fun updateAddDura(v: Boolean) = _uiState.update { it.copy(addDura = v) }

    // ── Meal estimate ────────────────────────────────────────────────────────

    fun updateDescription(v: String) = _uiState.update { it.copy(description = v) }

    fun setPhoto(jpeg: ByteArray) {
        photoJpeg = jpeg
        _uiState.update { it.copy(hasPhoto = true) }
    }

    fun clearPhoto() {
        photoJpeg = null
        _uiState.update { it.copy(hasPhoto = false) }
    }

    /** Asks for an estimate from the description and photo. A new estimate replaces the last one; scanned items stay. */
    fun estimateMeal() {
        val state = uiState.value
        if (state.lookupBusy || (state.description.isBlank() && photoJpeg == null)) return
        lookup { mealFoodRepository.estimate(state.description, photoJpeg) to true }
    }

    /** Looks up a scanned barcode and adds the product, one serving, to the list. */
    fun barcodeScanned(code: String) {
        if (uiState.value.lookupBusy) return
        lookup { mealFoodRepository.lookupBarcode(code) to false }
    }

    private fun lookup(request: suspend () -> Pair<FoodLookupResult, Boolean>) {
        _uiState.update { it.copy(lookupBusy = true, lookupError = null, lookupDetail = "") }
        appScope.launch {
            val (result, replacesEstimate) = request()
            _uiState.update { s ->
                when (result) {
                    is FoodLookupResult.Success -> {
                        savedFoodStore.remember(result.items)
                        val kept = if (replacesEstimate) s.foodItems.filter { it.source != FoodSource.ESTIMATE } else s.foodItems
                        s.copy(lookupBusy = false, foodItems = kept + result.items, plan = null, addition = null, recentFoods = savedFoodStore.recent, sourceMeal = null, loggedKcal = null)
                    }

                    is FoodLookupResult.Failure -> s.copy(lookupBusy = false, lookupError = result.reason, lookupDetail = result.detail)
                }
            }
        }
    }

    fun changeQuantity(index: Int, delta: Double) = _uiState.update { s ->
        val item = s.foodItems.getOrNull(index) ?: return@update s
        val q = (item.quantity + delta).coerceIn(MIN_QUANTITY, MAX_QUANTITY)
        s.copy(foodItems = s.foodItems.toMutableList().also { it[index] = item.copy(quantity = q) }, plan = null, addition = null)
    }

    fun removeFood(index: Int) = _uiState.update { s ->
        if (index !in s.foodItems.indices) s
        else s.copy(foodItems = s.foodItems.toMutableList().also { it.removeAt(index) }, plan = null, addition = null, sourceMeal = null)
    }

    fun clearFood() {
        photoJpeg = null
        _uiState.update { it.copy(foodItems = emptyList(), description = "", hasPhoto = false, plan = null, addition = null, lookupError = null, lookupDetail = "", lastSave = null, sourceMeal = null, loggedKcal = null, editingMeal = null) }
    }

    // ── Food log ─────────────────────────────────────────────────────────────

    /**
     * Logs the food list for the calorie count without starting a meal or giving insulin, for a snack left to
     * the loop. A list that looks wrong is held until the user checks it ([force] then logs it anyway).
     */
    fun logOnly(force: Boolean = false) {
        val state = uiState.value
        val entry = logEntry(state) ?: return
        if (!force && looksWrong(state, entry)) {
            _uiState.update { it.copy(logCheckKcal = entry.kcal) }
            return
        }
        foodLog.add(entry)
        clearFood()
        _uiState.update { it.copy(loggedKcal = entry.kcal, logCheckKcal = null) }
    }

    fun cancelLogCheck() = _uiState.update { it.copy(logCheckKcal = null) }

    private fun logEntry(state: SmartMealDialogUiState): FoodLogEntry? {
        if (state.foodItems.isEmpty()) return null
        val t = state.foodTotals
        val name = (state.sourceMeal ?: state.foodItems.joinToString(", ") { it.name }).take(MAX_LOG_NAME)
        return FoodLogEntry(dateUtil.now(), name, t.carbsG, t.fibreG, t.proteinG, t.fatG)
    }

    /** More than [FoodLog.CHECK_KCAL], or a food with more carbs, protein and fat than its own weight (bad label data). */
    private fun looksWrong(state: SmartMealDialogUiState, entry: FoodLogEntry): Boolean =
        entry.kcal > FoodLog.CHECK_KCAL || state.foodItems.any { it.carbsG + it.proteinG + it.fatG > it.grams * 1.05 }

    private fun checkLine(state: SmartMealDialogUiState): ConfirmationLine? =
        logEntry(state)?.takeIf { looksWrong(state, it) }?.let { ConfirmationLine(ConfirmationRole.WARNING, rh.gs(UiStrings.si_food_log_check, decimalFormatter.to0Decimal(it.kcal))) }

    // ── My foods ─────────────────────────────────────────────────────────────

    /** Adds a food typed in by hand. It goes to the recent list too, so it can be saved later. */
    fun addManualFood(item: FoodItem) {
        savedFoodStore.remember(listOf(item))
        addItems(listOf(item), null)
    }

    /**
     * Adds every food of a saved entry, with the amounts it was saved with. Added to an empty list, the meal
     * is that saved meal and learns about itself.
     */
    fun addSavedFood(food: SavedFood) = addSelected(listOf(food), emptyList())

    /**
     * Adds everything ticked on the My foods page at once: saved entries with their amounts, then recent
     * foods. Only one saved entry on its own, added to an empty list, makes the meal that saved meal.
     */
    fun addSelected(foods: List<SavedFood>, recent: List<FoodItem>) {
        if (foods.isEmpty() && recent.isEmpty()) return
        val now = dateUtil.now()
        foods.forEach { savedFoodStore.markUsed(it.name, now) }
        val items = foods.flatMap { f -> f.items.map { SavedFoodList.forMeal(it) } } + recent.map { SavedFoodList.forMeal(it) }
        val single = foods.singleOrNull()?.takeIf { recent.isEmpty() && uiState.value.foodItems.isEmpty() }
        addItems(items, single?.name)
    }

    /** Saves the whole food list as one entry, for example a wrap with all that is in it. */
    fun saveMeal(name: String) = save(name, uiState.value.foodItems)

    /** Saves one food of the list on its own. */
    fun saveFood(index: Int, name: String) {
        val item = uiState.value.foodItems.getOrNull(index) ?: return
        save(name, listOf(item))
    }

    fun saveRecentFood(item: FoodItem, name: String) = save(name, listOf(item))

    fun deleteSavedFood(name: String) {
        savedFoodStore.delete(name)
        savedMealLearning.forget(name)
        refreshSaved()
    }

    fun renameSavedFood(name: String, newName: String) {
        savedFoodStore.rename(name, newName)
        // Only when the store took the new name: it refuses one that another entry has.
        if (savedFoodStore.foods.any { it.name == newName.trim() }) savedMealLearning.rename(name, newName.trim())
        refreshSaved()
    }

    /** Forgets what a saved meal has learned, and starts again. */
    fun forgetMealLearning(name: String) {
        savedMealLearning.forget(name)
        refreshSaved()
    }

    /** Puts a saved meal's foods in the list to change them; "Save changes" then saves them back under its name. */
    fun editSavedFood(food: SavedFood) {
        clearFood()
        addItems(food.items.map { SavedFoodList.forMeal(it) }, food.name)
        _uiState.update { it.copy(editingMeal = food.name) }
    }

    fun saveChanges() {
        val name = uiState.value.editingMeal ?: return
        save(name, uiState.value.foodItems)
        _uiState.update { it.copy(editingMeal = null) }
    }

    private fun save(name: String, items: List<FoodItem>) {
        val old = savedFoodStore.foods.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        val result = savedFoodStore.save(name, items, dateUtil.now())
        // New contents under an old name: what the old meal learned only still fits a small change.
        if (result == SaveResult.REPLACED && old != null && !SavedFoodList.similar(old.items, items)) savedMealLearning.forget(old.name)
        refreshSaved()
        _uiState.update { it.copy(lastSave = result) }
    }

    private fun refreshSaved() = _uiState.update { it.copy(savedFoods = savedFoodStore.foods, learnedMeals = learnedMeals(), generalLearned = savedMealLearning.general()) }

    private fun learnedMeals(): Map<String, LearnedMeal> =
        savedFoodStore.foods.mapNotNull { f -> savedMealLearning.learned(f.name)?.let { f.name to it } }.toMap()

    /** @param sourceMeal the saved meal the list now is, or null when it is no longer one saved meal */
    private fun addItems(items: List<FoodItem>, sourceMeal: String?) = _uiState.update {
        it.copy(
            foodItems = it.foodItems + items, plan = null, addition = null, lastSave = null, sourceMeal = sourceMeal, loggedKcal = null,
            savedFoods = savedFoodStore.foods, recentFoods = savedFoodStore.recent, learnedMeals = learnedMeals()
        )
    }

    /**
     * Fills the pre-boluses, length, DURA and Protein/Fat from the food list. Nothing is given here: the
     * user still checks the fields and confirms. See [MealDosePlanner].
     */
    fun fillInFromFood() {
        appScope.launch {
            val state = uiState.value
            if (state.foodItems.isEmpty()) return@launch
            val carbRatio = profileFunction.getProfile()?.getIc() ?: 0.0
            if (carbRatio <= 0.0) {
                _uiState.update { it.copy(planNoRatio = true, plan = null, addition = null) }
                return@launch
            }
            // What a saved meal learned about itself comes first, then what all meals learned about each part,
            // then the general estimate factor.
            val learned = state.sourceLearned?.takeIf { it.inUse } ?: savedMealLearning.general()?.takeIf { it.inUse }
            val factor = learned?.carbFactor ?: mealEstimateCalibration.factor
            val settings = MealPlanSettings(
                carbPct = preferences.get(IntKey.ApsSmartInsulinMealPlanCarbPct),
                fatProteinPct = preferences.get(IntKey.ApsSmartInsulinMealPlanFatProteinPct),
                pb3SharePct = preferences.get(IntKey.ApsSmartInsulinMealPlanPb3SharePct),
                maxPreBolusU = state.maxPreBolus,
                maxTotalU = preferences.get(DoubleKey.ApsSmartInsulinMealPlanMaxTotalU),
                carbRatio = carbRatio,
                factor = factor,
                waveFactor = learned?.waveFactor,
                tailFactor = learned?.tailFactor,
                pb2DelayMin = preferences.get(IntKey.ApsSmartInsulinMealPlanPb2DelayMins),
                pb3DelayMin = preferences.get(IntKey.ApsSmartInsulinMealPlanPb3DelayMins),
                bolusStep = state.bolusStep
            )
            if (state.addMode) {
                val running = mealOverrideManager.activeState ?: return@launch
                val now = readNow()
                val addition = MealDosePlanner.planAddition(
                    state.foodTotals, settings,
                    RunningMeal(
                        plannedTotalU = running.plannedTotalU,
                        pb2WaitingU = if (running.preBolus2Pending) running.preBolus2U else 0.0,
                        pb3WaitingU = if (running.preBolus3Pending) running.preBolus3U else 0.0,
                        minutesLeft = state.activeRemainingMin,
                        iobRoomU = now.maxIobU - now.iobU,
                        holdNow = now.holdNow
                    )
                )
                _uiState.update {
                    it.copy(
                        addition = addition,
                        planNoRatio = false,
                        calibrationFactor = factor,
                        calibrationMeals = mealEstimateCalibration.mealsUsed,
                        addNowU = addition.nowU, addPb2U = addition.pb2AddU, addPb3U = addition.pb3AddU,
                        addMinutesLeft = addition.minutesLeft
                    )
                }
                return@launch
            }
            val plan = MealDosePlanner.plan(state.foodTotals, settings)
            _uiState.update {
                it.copy(
                    plan = plan,
                    planNoRatio = false,
                    calibrationFactor = factor,
                    calibrationMeals = mealEstimateCalibration.mealsUsed,
                    durationMinutes = plan.durationMin,
                    pb1Enabled = plan.pb1U > 0.0, pb1U = plan.pb1U,
                    pb2Enabled = plan.pb2U > 0.0, pb2U = plan.pb2U, pb2DelayMinutes = plan.pb2DelayMin,
                    pb3Enabled = plan.pb3U > 0.0, pb3U = plan.pb3U, pb3DelayMinutes = plan.pb3DelayMin
                    // DURA and Protein/Fat are left as the user set them.
                )
            }
        }
    }

    /** BG and IOB right now, as limits for a dose given during a running meal. */
    private data class NowValues(val iobU: Double, val maxIobU: Double, val holdNow: Boolean)

    /**
     * BG low or falling holds the dose now, with the same limits pre-bolus 2 waits for, except that BG
     * between that low limit and the target does not hold it: the food is being eaten now. No BG reading
     * holds it too.
     */
    private suspend fun readNow(): NowValues {
        val bolusIob = iobCobCalculator.calculateIobFromBolus().iob
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().basaliob
        val gs = glucoseStatusProvider.glucoseStatusData
        val hold = gs == null ||
            gs.glucose < MealOverrideManager.MIN_BG_FOR_PB2_MGDL ||
            gs.delta < MealOverrideManager.DELTA_INSTANT_BLOCK_MGDL ||
            gs.shortAvgDelta < MealOverrideManager.SHORT_AVG_DELTA_BLOCK_MGDL
        return NowValues(bolusIob + basalIob, constraintsChecker.getMaxIOBAllowed().value(), hold)
    }

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

    /** @param doseLines pre-bolus 1 as prepared, shown before pre-bolus 2 and 3 */
    private fun modeLines(state: SmartMealDialogUiState, doseLines: List<ConfirmationLine>): List<ConfirmationLine> = buildList {
        add(ConfirmationLine(ConfirmationRole.PRIMARY, rh.gs(UiStrings.si_confirm_mode, state.mode.label)))
        state.activeMode?.let { running ->
            add(ConfirmationLine(ConfirmationRole.WARNING, rh.gs(UiStrings.si_confirm_replaces, running.label)))
            if (state.pb2Pending || state.pb3Pending) add(ConfirmationLine(ConfirmationRole.WARNING, rh.gs(UiStrings.si_confirm_replaces_pb)))
        }
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
        addAll(doseLines)
        val pb2 = state.pb2()
        if (pb2 > 0.0)
            add(ConfirmationLine(ConfirmationRole.BOLUS, rh.gs(UiStrings.si_confirm_pb2, fmtU(pb2, state.bolusStep), state.pb2DelayMinutes)))
        val pb3 = state.pb3()
        if (pb3 > 0.0)
            add(ConfirmationLine(ConfirmationRole.BOLUS, rh.gs(UiStrings.si_confirm_pb3, fmtU(pb3, state.bolusStep), state.pb3DelayMinutes)))
        if (state.pfAfter)
            add(ConfirmationLine(ConfirmationRole.NORMAL, rh.gs(UiStrings.si_confirm_pf_after)))
        foodLine(state)?.let { add(it) }
        checkLine(state)?.let { add(it) }
    }

    private fun foodLine(state: SmartMealDialogUiState): ConfirmationLine? {
        if (state.foodItems.isEmpty()) return null
        val t = state.foodTotals
        return ConfirmationLine(
            ConfirmationRole.NORMAL,
            rh.gs(UiStrings.si_confirm_food, decimalFormatter.to0Decimal(t.carbsG), decimalFormatter.to0Decimal(t.proteinG), decimalFormatter.to0Decimal(t.fatG))
        )
    }

    private fun SmartMealDialogUiState.addNow(): Double =
        if (addNowU > 0.0) Round.floorTo(addNowU.coerceAtMost(maxPreBolus), ch.bolusStep(addNowU)) else 0.0

    /** The running mode's own DURA floor in mg/dL and strength, used when adding food turns DURA on. */
    private fun duraSettingsOf(mode: MealMode): Pair<Double, Double> =
        (duraFloorKeyFor(mode)?.let { preferences.get(it) } ?: 0.0) to (duraStrengthKeyFor(mode)?.let { preferences.get(it) } ?: 1.0)

    /** @param doseLines the dose now as prepared, shown before what is added to pre-bolus 2 and 3 */
    private suspend fun addLines(state: SmartMealDialogUiState, running: MealOverrideState, doseLines: List<ConfirmationLine>): List<ConfirmationLine> = buildList {
        val fmt = { u: Double -> fmtU(u, state.bolusStep) }
        add(ConfirmationLine(ConfirmationRole.PRIMARY, rh.gs(UiStrings.si_confirm_add, running.mode.label)))
        addAll(doseLines)
        if (state.addPb2U > 0.0)
            add(ConfirmationLine(ConfirmationRole.BOLUS, rh.gs(UiStrings.si_confirm_add_pb2, fmt(state.addPb2U), fmt(state.runningPb2WaitingU + state.addPb2U))))
        if (state.addPb3U > 0.0)
            add(ConfirmationLine(ConfirmationRole.BOLUS, rh.gs(UiStrings.si_confirm_add_pb3, fmt(state.addPb3U), fmt(state.runningPb3WaitingU + state.addPb3U))))
        if (state.addMinutesLeft > state.activeRemainingMin)
            add(ConfirmationLine(ConfirmationRole.NORMAL, rh.gs(UiStrings.si_confirm_add_longer, state.addMinutesLeft)))
        if (state.addDura && !state.runningDura) {
            val (floorMgdl, strength) = duraSettingsOf(running.mode)
            add(ConfirmationLine(ConfirmationRole.NORMAL, rh.gs(UiStrings.si_confirm_dura, "${fmtBg(toDisplay(floorMgdl), state.isMmol)} ${state.unitsLabel}", decimalFormatter.to1Decimal(strength))))
        }
        if (state.pfAfter && !state.runningPfAfter)
            add(ConfirmationLine(ConfirmationRole.NORMAL, rh.gs(UiStrings.si_confirm_pf_after)))
        foodLine(state)?.let { add(it) }
        checkLine(state)?.let { add(it) }

        // The limits are checked again here, because the amounts can be typed by hand.
        val now = readNow()
        val nowU = state.addNow()
        add(ConfirmationLine(ConfirmationRole.NORMAL, rh.gs(UiStrings.si_confirm_add_iob, fmt(now.iobU), fmt(now.maxIobU))))
        if (nowU > 0.0 && now.holdNow)
            add(ConfirmationLine(ConfirmationRole.WARNING, rh.gs(UiStrings.si_confirm_add_bg_low)))
        if (nowU > 0.0 && now.iobU + nowU > now.maxIobU)
            add(ConfirmationLine(ConfirmationRole.WARNING, rh.gs(UiStrings.si_confirm_add_iob_high)))
        val maxTotal = preferences.get(DoubleKey.ApsSmartInsulinMealPlanMaxTotalU)
        val total = running.plannedTotalU + nowU + state.addPb2U + state.addPb3U
        if (total > maxTotal + state.bolusStep / 2)
            add(ConfirmationLine(ConfirmationRole.WARNING, rh.gs(UiStrings.si_confirm_add_total_high, fmt(total), fmt(maxTotal))))
        if (state.foodItems.isEmpty())
            add(ConfirmationLine(ConfirmationRole.NORMAL, rh.gs(UiStrings.si_confirm_add_not_learned)))
    }

    /**
     * Build the confirmation. With a pre-bolus 1, the bolus is PREPARED first so the confirmation carries the
     * capped amount the pump will actually get; the mode is only activated once the user confirms.
     */
    fun prepareAndConfirm() {
        appScope.launch {
            val state = uiState.value
            confirmedState = state
            val running = if (state.addMode) mealOverrideManager.activeState else null
            if (state.addMode && running == null) {
                // The meal ended while the dialog was open.
                refreshActive()
                return@launch
            }
            suspend fun lines(prepared: List<ConfirmationLine>): List<ConfirmationLine> {
                val doseText = if (running != null) rh.gs(UiStrings.si_confirm_add_now, fmtU(state.addNow(), state.bolusStep))
                else rh.gs(UiStrings.si_confirm_pb1, fmtU(state.pb1(), state.bolusStep))
                val (dose, rest) = placePrepared(prepared, doseText)
                return (if (running != null) addLines(state, running, dose) else modeLines(state, dose)) + rest
            }

            val pb1 = if (running != null) state.addNow() else state.pb1()
            if (pb1 <= 0.0) {
                _sideEffect.tryEmit(SideEffect.ShowConfirmation(null, lines(emptyList())))
                return@launch
            }
            val note = if (running != null) rh.gs(UiStrings.si_add_note, running.mode.label) else rh.gs(UiStrings.si_prebolus_note, state.mode.label)
            val bolus = BatchAction.Bolus(
                insulin = pb1, carbs = 0, carbsTimeOffsetMinutes = 0, carbsDurationHours = 0, recordOnly = false,
                notes = note, timestamp = dateUtil.now(), iCfg = null
            )
            when (val prepared = batchExecutor.prepare(listOf(bolus), Sources.InsulinDialog, rh.gs(InterfacesStrings.bolus))) {
                is ActionProgress.Prepared -> _sideEffect.tryEmit(SideEffect.ShowConfirmation(prepared.id, lines(prepared.lines)))
                is ActionProgress.Rejected -> when {
                    // Capped to nothing: the mode can still start, just without a pre-bolus.
                    prepared.reason == FailureReason.NoAction -> _sideEffect.tryEmit(SideEffect.ShowConfirmation(null, lines(emptyList())))
                    prepared.reason.isNotDeliveryError()      -> rxBus.send(EventShowDialog.Ok(title = rh.gs(InterfacesStrings.bolus), message = rh.gs(prepared.reason.failText())))
                    else                                      -> prepared.detail?.let { _sideEffect.tryEmit(SideEffect.ShowDeliveryError(it)) }
                }

                else                       -> Unit
            }
        }
    }

    /**
     * Splits the prepared bolus lines: the dose and any warning about it go first, worded like pre-bolus 2 and
     * 3, the rest (the note) goes last. When the master cut the dose, its own line is kept as it is, so the
     * confirmation always shows the amount the pump will get.
     */
    private fun placePrepared(prepared: List<ConfirmationLine>, doseText: String): Pair<List<ConfirmationLine>, List<ConfirmationLine>> {
        val (dose, rest) = prepared.partition { it.role == ConfirmationRole.BOLUS || it.role == ConfirmationRole.WARNING }
        val cut = dose.any { it.role == ConfirmationRole.WARNING }
        return (if (cut) dose else dose.map { if (it.role == ConfirmationRole.BOLUS) it.copy(text = doseText) else it }) to rest
    }

    /**
     * Remembers what the food list said, so the meal tracker can later tell how this user's meals compare
     * with estimates. The watch window covers a following Protein/Fat too.
     */
    private fun recordEstimate(state: SmartMealDialogUiState) {
        if (state.foodItems.isEmpty()) return
        val startMs = mealOverrideManager.modeStartMs.takeIf { it > 0L } ?: dateUtil.now()
        val pfMin = if (state.pfAfter) preferences.get(IntKey.ApsSmartInsulinUamProteinFatDurationMins) else 0
        val t = state.foodTotals
        mealEstimateCalibration.recordEstimate(
            modeStartMs = startMs,
            windowEndMs = startMs + (state.durationMinutes + pfMin) * 60_000L,
            carbEquivG = t.carbEquivG, carbsG = t.carbsG, proteinG = t.proteinG, fatG = t.fatG
        )
    }

    /**
     * Every meal started with food is watched, so each part of it (carbs, wave, tail) can be learned. A meal
     * that is one saved meal also learns for that meal.
     */
    private fun recordMealLearning(state: SmartMealDialogUiState) {
        if (state.foodItems.isEmpty()) return
        val startMs = mealOverrideManager.modeStartMs.takeIf { it > 0L } ?: dateUtil.now()
        val modeEndMs = startMs + state.durationMinutes * 60_000L
        val pfMin = if (state.pfAfter) preferences.get(IntKey.ApsSmartInsulinUamProteinFatDurationMins) else 0
        val pb3DueMs = if (state.pb2() > 0.0 && state.pb3() > 0.0) startMs + (state.pb2DelayMinutes + state.pb3DelayMinutes) * 60_000L else 0L
        val t = state.foodTotals
        val (waveG, tailG) = MealDosePlanner.fatProteinGrams(t.fpu, preferences.get(IntKey.ApsSmartInsulinMealPlanPb3SharePct))
        savedMealLearning.recordMeal(state.sourceMeal, startMs, modeEndMs, modeEndMs + pfMin * 60_000L, pb3DueMs, t.carbsG, waveG, tailG)
    }

    /**
     * Adds the confirmed food to the running meal. The estimate is added to the meal's estimate; food
     * without one means this meal can no longer be compared, so it is not used to learn.
     */
    private fun commitAddition(state: SmartMealDialogUiState, bolusId: Long?) {
        val running = mealOverrideManager.activeState
        val nowU = if (bolusId != null) state.addNow() else 0.0
        val endMs = dateUtil.now() + state.addMinutesLeft * 60_000L
        if (running != null) {
            val (floorMgdl, strength) = duraSettingsOf(running.mode)
            mealOverrideManager.addToMeal(
                addedU = nowU, preBolus2AddU = state.addPb2U, preBolus3AddU = state.addPb3U, modeEndMs = endMs,
                duraEnabled = state.addDura, duraFloorMgdl = floorMgdl, duraStrength = strength, pfAfter = state.pfAfter
            )
            val startMs = running.triggerTimeMs
            // More food: the meal is no longer the saved meal it was started from.
            savedMealLearning.dropMeal(startMs)
            if (state.foodItems.isEmpty()) mealEstimateCalibration.dropEstimate(startMs)
            else {
                val modeEndMs = maxOf(running.modeExpiryMs, endMs)
                val pfMin = if (state.pfAfter || running.pfAfter) preferences.get(IntKey.ApsSmartInsulinUamProteinFatDurationMins) else 0
                val t = state.foodTotals
                mealEstimateCalibration.addToEstimate(startMs, modeEndMs + pfMin * 60_000L, t.carbEquivG, t.carbsG, t.proteinG, t.fatG)
            }
        }
    }

    /** Save the per-mode settings, start the mode, then deliver the prepared pre-bolus 1 if there is one. */
    fun commit(bolusId: Long?) {
        val state = confirmedState ?: return
        appScope.launch {
            if (state.addMode) {
                commitAddition(state, bolusId)
                logEntry(state)?.let { foodLog.add(it) }
                // The bolus is for food that is being eaten, so it is given even if the meal ended meanwhile.
                deliver(bolusId)
                return@launch
            }
            val mode = state.mode
            isfKeyFor(mode)?.let { preferences.put(it, toMgdl(state.isf)) }
            val duraFloorMgdl = if (state.duraEnabled) toMgdl(state.duraFloor) else 0.0
            // Remembered only when DURA is used, so a meal without it keeps the floor and strength for next time.
            if (state.duraEnabled) {
                duraFloorKeyFor(mode)?.let { preferences.put(it, duraFloorMgdl) }
                duraStrengthKeyFor(mode)?.let { preferences.put(it, state.duraStrength) }
            }

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
            if (state.pfAfter) mealOverrideManager.followWithProteinFat()
            recordEstimate(state)
            recordMealLearning(state)
            logEntry(state)?.let { foodLog.add(it) }
            deliver(bolusId)
        }
    }

    private suspend fun deliver(bolusId: Long?) {
        if (bolusId == null) return
        val result = batchExecutor.commit(bolusId, Sources.InsulinDialog, rh.gs(InterfacesStrings.bolus))
        if (result is ActionProgress.Rejected) {
            if (result.reason.isNotDeliveryError())
                rxBus.send(EventShowDialog.Ok(title = rh.gs(InterfacesStrings.bolus), message = rh.gs(result.reason.failText())))
            else result.detail?.let { _sideEffect.tryEmit(SideEffect.ShowDeliveryError(it)) }
        }
    }

    companion object {

        const val QUANTITY_STEP = 0.25
        private const val MAX_LOG_NAME = 120
        private const val MIN_QUANTITY = 0.25
        private const val MAX_QUANTITY = 20.0
    }
}
