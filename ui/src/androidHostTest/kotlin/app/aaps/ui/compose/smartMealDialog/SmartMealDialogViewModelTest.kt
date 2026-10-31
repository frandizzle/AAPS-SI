package app.aaps.ui.compose.smartMealDialog

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ui.ConfirmationLine
import app.aaps.core.data.ui.ConfirmationRole
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.bolus.BatchAction
import app.aaps.core.interfaces.bolus.BatchExecutor
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import app.aaps.core.interfaces.clientcontrol.FailureReason
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.smartInsulin.FoodLog
import app.aaps.core.interfaces.smartInsulin.AfterActivity
import app.aaps.core.interfaces.smartInsulin.FoodLogEntry
import app.aaps.core.interfaces.smartInsulin.RecentActivity
import app.aaps.core.interfaces.smartInsulin.LearnedMeal
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.SavedMealLearning
import app.aaps.core.interfaces.smartInsulin.MealEstimateCalibration
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.interfaces.smartInsulin.MealOverrideState
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.DoubleNonKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.smartMealDialog.food.FoodItem
import app.aaps.ui.compose.smartMealDialog.food.FoodLookupResult
import app.aaps.ui.compose.smartMealDialog.food.FoodSource
import app.aaps.ui.compose.smartMealDialog.food.MealDosePlanner
import app.aaps.ui.compose.smartMealDialog.food.MealFoodRepository
import app.aaps.ui.compose.smartMealDialog.food.SaveResult
import app.aaps.ui.compose.smartMealDialog.food.SavedFoodStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.anyVararg
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

/**
 * The Smart Meal dialog writes per-mode settings and starts pre-boluses, so what matters here is the plumbing
 * between what the user picked and what is saved and delivered: units, the capped bolus path, and the
 * values handed to [MealOverrideManager.activateOverride].
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SmartMealDialogViewModelTest {

    private val mealOverrideManager: MealOverrideManager = mock()
    private val preferences: Preferences = mock()
    private val profileFunction: ProfileFunction = mock()
    private val profileUtil: ProfileUtil = mock()
    private val activePlugin: ActivePlugin = mock()
    private val ch: ConcentrationHelper = mock()
    private val batchExecutor: BatchExecutor = mock()
    private val rxBus: RxBus = mock()
    private val rh: TextResolver = mock()
    private val dateUtil: DateUtil = mock()
    private val decimalFormatter: DecimalFormatter = mock()
    private val pump: PumpWithConcentration = mock()
    private val pumpDescription: PumpDescription = mock()
    private val mealFoodRepository: MealFoodRepository = mock()
    private lateinit var savedFoodStore: SavedFoodStore
    private val savedMealLearning: SavedMealLearning = mock()
    private val foodLog: FoodLog = mock()
    private val recentActivity: RecentActivity = mock()
    private val mealEstimateCalibration: MealEstimateCalibration = mock()
    private val glucoseStatusProvider: GlucoseStatusProvider = mock()
    private val iobCobCalculator: IobCobCalculator = mock()
    private val constraintsChecker: ConstraintsChecker = mock()
    private val maxIob: Constraint<Double> = mock()

    private val scope = CoroutineScope(UnconfinedTestDispatcher())
    private lateinit var sut: SmartMealDialogViewModel
    private val effects = mutableListOf<SmartMealDialogViewModel.SideEffect>()

    private fun build(units: GlucoseUnit = GlucoseUnit.MMOL) {
        whenever(profileFunction.getUnits()).thenReturn(units)
        // Real conversions, so a units bug shows up as a wrong number rather than a mock's zero.
        whenever(profileUtil.fromMgdlToUnits(any(), any())).thenAnswer { i ->
            val v = i.getArgument<Double>(0); if (i.getArgument<GlucoseUnit>(1) == GlucoseUnit.MMOL) v / 18.0 else v
        }
        whenever(profileUtil.convertToMgdl(any(), any())).thenAnswer { i ->
            val v = i.getArgument<Double>(0); if (i.getArgument<GlucoseUnit>(1) == GlucoseUnit.MMOL) v * 18.0 else v
        }
        if (!::savedFoodStore.isInitialized) savedFoodStore = SavedFoodStore(preferences)
        sut = SmartMealDialogViewModel(
            mealOverrideManager, preferences, profileFunction, profileUtil, activePlugin, ch,
            batchExecutor, rxBus, rh, dateUtil, decimalFormatter, mealFoodRepository, savedFoodStore, savedMealLearning, foodLog, recentActivity, mealEstimateCalibration,
            glucoseStatusProvider, iobCobCalculator, constraintsChecker, scope
        )
        sut.sideEffect.onEach { effects += it }.launchIn(scope)
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(pump.pumpDescription).thenReturn(pumpDescription)
        whenever(pumpDescription.bolusStep).thenReturn(0.05)
        whenever(ch.bolusStep(any())).thenReturn(0.05)
        whenever(preferences.get(DoubleKey.ApsSmartInsulinMaxPreBolus)).thenReturn(4.0)
        whenever(preferences.get(DoubleKey.ApsSmartInsulinPreBolus2DefaultU)).thenReturn(2.0)
        whenever(preferences.get(IntKey.ApsSmartInsulinPreBolus2DefaultDelayMins)).thenReturn(30)
        whenever(preferences.get(DoubleNonKey.ApsSmartInsulinLunchDuraStrength)).thenReturn(1.0)
        whenever(preferences.get(StringNonKey.ApsSmartInsulinSavedFoods)).thenReturn("")
        whenever(preferences.get(StringNonKey.ApsSmartInsulinRecentFoods)).thenReturn("")
        whenever(mealOverrideManager.preBolus1RestStatusText).thenReturn("")
        whenever(mealOverrideManager.preBolus2StatusText).thenReturn("")
        whenever(mealOverrideManager.preBolus3StatusText).thenReturn("")
        whenever(rh.gs(any())).thenReturn("")
        whenever(rh.gs(any(), anyVararg())).thenReturn("")
        whenever(decimalFormatter.toPumpSupportedBolus(any(), any())).thenReturn("")
        whenever(decimalFormatter.to0Decimal(any())).thenReturn("")
        whenever(decimalFormatter.to1Decimal(any())).thenReturn("")
        whenever(decimalFormatter.to2Decimal(any())).thenReturn("")
        whenever { iobCobCalculator.calculateIobFromBolus() }.thenReturn(IobTotal(time = 0L, iob = 2.0))
        whenever { iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended() }.thenReturn(IobTotal(time = 0L, basaliob = 0.5))
        whenever { constraintsChecker.getMaxIOBAllowed() }.thenReturn(maxIob)
        whenever(maxIob.value()).thenReturn(8.0)
        whenever(dateUtil.now()).thenReturn(NOW)
        whenever(preferences.get(DoubleKey.ApsSmartInsulinMealPlanMaxTotalU)).thenReturn(10.0)
    }

    private fun bg(mgdl: Double, delta: Double = 2.0) {
        val gs: GlucoseStatus = mock()
        whenever(gs.glucose).thenReturn(mgdl)
        whenever(gs.delta).thenReturn(delta)
        whenever(gs.shortAvgDelta).thenReturn(delta)
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(gs)
    }

    /** A Dinner started 40 min ago with pre-bolus 2 still waiting, 80 min left. */
    private fun dinnerRunning(mode: MealMode = MealMode.DINNER, pb2FiredMs: Long? = null): MealOverrideState {
        val s = MealOverrideState(
            mode = mode, triggerTimeMs = NOW - 40 * 60_000L, modeExpiryMs = NOW + 80 * 60_000L, doseU = 3.0,
            preBolus2U = 1.5, preBolus2DelayMs = 60 * 60_000L, preBolus2FiredMs = pb2FiredMs
        )
        whenever(mealOverrideManager.activeState).thenReturn(s)
        whenever(mealOverrideManager.activeMealMode).thenReturn(mode)
        whenever(mealOverrideManager.modeTimeRemainingMs).thenReturn(80 * 60_000L)
        whenever(mealOverrideManager.preBolus2Pending).thenReturn(s.preBolus2Pending)
        return s
    }

    private fun dessert(carbs: Double = 30.0, protein: Double = 0.0, fat: Double = 0.0) =
        FoodItem("Dessert", baseGrams = 100.0, portion = "", carbsPer100 = carbs, fibrePer100 = 0.0,
                 proteinPer100 = protein, fatPer100 = fat, source = FoodSource.ESTIMATE)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun activateCaptured(): ActivateArgs {
        val mode = argumentCaptor<MealMode>()
        val dose = argumentCaptor<Double>()
        val window = argumentCaptor<Long>()
        val pb2 = argumentCaptor<Double>()
        val pb2Delay = argumentCaptor<Long>()
        val pb3 = argumentCaptor<Double>()
        val pb3Delay = argumentCaptor<Long>()
        val duraOn = argumentCaptor<Boolean>()
        val duraFloor = argumentCaptor<Double>()
        val duraStrength = argumentCaptor<Double>()
        verify(mealOverrideManager).activateOverride(
            mode.capture(), dose.capture(), eq(0), window.capture(), pb2.capture(), pb2Delay.capture(),
            pb3.capture(), pb3Delay.capture(), duraOn.capture(), duraFloor.capture(), duraStrength.capture()
        )
        return ActivateArgs(
            mode.firstValue, dose.allValues.firstOrNull(), window.firstValue, pb2.firstValue, pb2Delay.firstValue,
            pb3.firstValue, pb3Delay.firstValue, duraOn.firstValue, duraFloor.firstValue, duraStrength.firstValue
        )
    }

    private data class ActivateArgs(
        val mode: MealMode, val doseU: Double?, val windowMs: Long, val pb2U: Double, val pb2DelayMs: Long,
        val pb3U: Double, val pb3DelayMs: Long, val duraOn: Boolean, val duraFloorMgdl: Double, val duraStrength: Double
    )

    // ── Units ────────────────────────────────────────────────────────────────

    @Test
    fun `mode ISF is read raw in mg-dL and shown in mmol`() {
        // 18 mg/dL/U is exactly what the unit-detecting read mistakes for mmol (< 36). Read raw, it is 1.0 mmol/U.
        whenever(preferences.getRaw(UnitDoubleKey.ApsSmartInsulinLunchIsf)).thenReturn(18.0)
        build()
        assertThat(sut.uiState.value.mode).isEqualTo(MealMode.LUNCH)
        assertThat(sut.uiState.value.isf).isWithin(1e-9).of(1.0)
        verify(preferences, never()).get(UnitDoubleKey.ApsSmartInsulinLunchIsf)
    }

    @Test
    fun `switching mode loads that mode's ISF and DURA settings, with DURA off`() {
        whenever(preferences.getRaw(UnitDoubleKey.ApsSmartInsulinDinnerIsf)).thenReturn(54.0)
        whenever(preferences.get(DoubleNonKey.ApsSmartInsulinDinnerDuraFloor)).thenReturn(27.0)
        whenever(preferences.get(DoubleNonKey.ApsSmartInsulinDinnerDuraStrength)).thenReturn(1.5)
        build()
        sut.selectMode(MealMode.DINNER)
        with(sut.uiState.value) {
            assertThat(isf).isWithin(1e-9).of(3.0)
            assertThat(duraEnabled).isFalse()
            assertThat(duraFloor).isWithin(1e-9).of(1.5)
            assertThat(duraStrength).isEqualTo(1.5)
        }
    }

    @Test
    fun `confirming saves ISF and DURA floor back in mg-dL`() {
        build()
        sut.updateIsf(2.0)
        sut.updateDuraEnabled(true)
        sut.updateDuraFloor(1.5)
        sut.updateDuraStrength(1.4)
        sut.prepareAndConfirm()
        sut.commit(null)
        verify(preferences).put(UnitDoubleKey.ApsSmartInsulinLunchIsf, 36.0)
        verify(preferences).put(DoubleNonKey.ApsSmartInsulinLunchDuraFloor, 27.0)
        verify(preferences).put(DoubleNonKey.ApsSmartInsulinLunchDuraStrength, 1.4)
        with(activateCaptured()) {
            assertThat(duraOn).isTrue()
            assertThat(duraFloorMgdl).isWithin(1e-9).of(27.0)
            assertThat(duraStrength).isEqualTo(1.4)
        }
    }

    @Test
    fun `mg-dL users are saved unconverted`() {
        build(GlucoseUnit.MGDL)
        sut.updateIsf(40.0)
        sut.prepareAndConfirm()
        sut.commit(null)
        verify(preferences).put(UnitDoubleKey.ApsSmartInsulinLunchIsf, 40.0)
    }

    @Test
    fun `ISF of zero means profile ISF and is saved as zero`() {
        build()
        sut.updateIsf(0.0)
        sut.prepareAndConfirm()
        sut.commit(null)
        verify(preferences).put(UnitDoubleKey.ApsSmartInsulinLunchIsf, 0.0)
    }

    @Test
    fun `a meal without DURA keeps the remembered floor and strength`() {
        build()
        sut.updateDuraFloor(1.5)
        sut.updateDuraEnabled(false)
        sut.prepareAndConfirm()
        sut.commit(null)
        verify(preferences, never()).put(eq(DoubleNonKey.ApsSmartInsulinLunchDuraFloor), any<Double>())
        verify(preferences, never()).put(eq(DoubleNonKey.ApsSmartInsulinLunchDuraStrength), any<Double>())
        assertThat(activateCaptured().duraOn).isFalse()
    }

    // ── Pre-bolus 1 ──────────────────────────────────────────────────────────

    @Test
    fun `without pre-bolus 1 nothing is prepared and the mode starts with no dose`() {
        build()
        sut.prepareAndConfirm()
        val confirm = effects.single() as SmartMealDialogViewModel.SideEffect.ShowConfirmation
        assertThat(confirm.bolusId).isNull()
        verifyBlocking(batchExecutor, never()) { prepare(any(), any(), any()) }

        sut.commit(confirm.bolusId)
        assertThat(activateCaptured().doseU).isNull()
        verifyBlocking(batchExecutor, never()) { commit(any(), any(), any(), any()) }
    }

    @Test
    fun `pre-bolus 1 goes through the capped bolus path and is delivered only on confirm`() {
        whenever { batchExecutor.prepare(any(), any(), any()) }.thenReturn(ActionProgress.Prepared(id = 42L))
        whenever { batchExecutor.commit(any(), any(), any(), any()) }.thenReturn(ActionProgress.Applied)
        build()
        sut.updatePb1Enabled(true)
        sut.updatePb1(1.53) // floored to the 0.05 U step
        sut.prepareAndConfirm()

        val actions = argumentCaptor<List<BatchAction>>()
        verifyBlocking(batchExecutor) { prepare(actions.capture(), eq(Sources.InsulinDialog), any()) }
        val bolus = actions.firstValue.single() as BatchAction.Bolus
        assertThat(bolus.insulin).isWithin(1e-9).of(1.5)
        assertThat(bolus.recordOnly).isFalse()
        assertThat(bolus.carbs).isEqualTo(0)
        // Prepared, not delivered: nothing is committed or activated until the user confirms.
        verifyBlocking(batchExecutor, never()) { commit(any(), any(), any(), any()) }
        verify(mealOverrideManager, never()).activateOverride(any(), anyOrNull(), any(), any(), any(), any(), any(), any(), any(), any(), any())

        val confirm = effects.single() as SmartMealDialogViewModel.SideEffect.ShowConfirmation
        assertThat(confirm.bolusId).isEqualTo(42L)
        sut.commit(confirm.bolusId)
        assertThat(activateCaptured().doseU).isWithin(1e-9).of(1.5)
        verifyBlocking(batchExecutor) { commit(eq(42L), eq(Sources.InsulinDialog), any(), any()) }
    }

    @Test
    fun `pre-bolus 1 above the max pre-bolus is capped`() {
        whenever { batchExecutor.prepare(any(), any(), any()) }.thenReturn(ActionProgress.Prepared(id = 1L))
        build()
        sut.updatePb1Enabled(true)
        sut.addPb1(3.0)
        sut.addPb1(3.0) // 6 U requested, max pre-bolus is 4
        assertThat(sut.uiState.value.pb1U).isEqualTo(4.0)
        sut.prepareAndConfirm()
        val actions = argumentCaptor<List<BatchAction>>()
        verifyBlocking(batchExecutor) { prepare(actions.capture(), any(), any()) }
        assertThat((actions.firstValue.single() as BatchAction.Bolus).insulin).isWithin(1e-9).of(4.0)
    }

    @Test
    fun `a pre-bolus capped to nothing still lets the mode start, without a dose`() {
        whenever { batchExecutor.prepare(any(), any(), any()) }
            .thenReturn(ActionProgress.Rejected(FailureReason.NoAction))
        build()
        sut.updatePb1Enabled(true)
        sut.updatePb1(1.0)
        sut.prepareAndConfirm()
        val confirm = effects.single() as SmartMealDialogViewModel.SideEffect.ShowConfirmation
        assertThat(confirm.bolusId).isNull()
        sut.commit(confirm.bolusId)
        assertThat(activateCaptured().doseU).isNull()
        verifyBlocking(batchExecutor, never()) { commit(any(), any(), any(), any()) }
    }

    @Test
    fun `pre-bolus 1 switched off is ignored even with an amount entered`() {
        build()
        sut.updatePb1(2.0)
        sut.updatePb1Enabled(false)
        sut.prepareAndConfirm()
        verifyBlocking(batchExecutor, never()) { prepare(any(), any(), any()) }
    }

    @Test
    fun `pre-bolus 1 is shown first, worded like pre-bolus 2, and the note last`() {
        whenever(rh.gs(eq(UiStrings.si_confirm_pb1), anyVararg())).thenReturn("PB1")
        whenever(rh.gs(eq(UiStrings.si_confirm_pb2), anyVararg())).thenReturn("PB2")
        whenever { batchExecutor.prepare(any(), any(), any()) }.thenReturn(
            ActionProgress.Prepared(id = 1L, lines = listOf(ConfirmationLine(ConfirmationRole.BOLUS, "Bolus: 2.00 U"), ConfirmationLine(ConfirmationRole.NORMAL, "Notes")))
        )
        build()
        sut.updatePb1Enabled(true)
        sut.updatePb1(2.0)
        sut.updatePb2Enabled(true)
        sut.updatePb2(1.5)
        sut.prepareAndConfirm()
        val texts = (effects.single() as SmartMealDialogViewModel.SideEffect.ShowConfirmation).lines.map { it.text }
        assertThat(texts.indexOf("PB1")).isLessThan(texts.indexOf("PB2"))
        assertThat(texts).doesNotContain("Bolus: 2.00 U")
        assertThat(texts.last()).isEqualTo("Notes")
    }

    @Test
    fun `a dose cut by the constraints keeps the master's own line`() {
        whenever(rh.gs(eq(UiStrings.si_confirm_pb1), anyVararg())).thenReturn("PB1")
        whenever { batchExecutor.prepare(any(), any(), any()) }.thenReturn(
            ActionProgress.Prepared(
                id = 1L,
                lines = listOf(ConfirmationLine(ConfirmationRole.BOLUS, "Bolus: 1.00 U"), ConfirmationLine(ConfirmationRole.WARNING, "Cut from 2.00 to 1.00 U"))
            )
        )
        build()
        sut.updatePb1Enabled(true)
        sut.updatePb1(2.0)
        sut.prepareAndConfirm()
        val texts = (effects.single() as SmartMealDialogViewModel.SideEffect.ShowConfirmation).lines.map { it.text }
        assertThat(texts).containsAtLeast("Bolus: 1.00 U", "Cut from 2.00 to 1.00 U").inOrder()
        assertThat(texts).doesNotContain("PB1")
    }

    // ── Pre-bolus 2 / 3 and the mode itself ──────────────────────────────────

    @Test
    fun `pre-bolus 2 and 3 reach the meal mode with their delays in milliseconds`() {
        build()
        sut.updateDuration(120.0)
        sut.updatePb2Enabled(true)
        sut.updatePb2(1.5)
        sut.updatePb2Delay(25.0)
        sut.updatePb3Enabled(true)
        sut.updatePb3(1.0)
        sut.updatePb3Delay(40.0)
        sut.prepareAndConfirm()
        sut.commit(null)
        with(activateCaptured()) {
            assertThat(mode).isEqualTo(MealMode.LUNCH)
            assertThat(windowMs).isEqualTo(120 * 60_000L)
            assertThat(pb2U).isEqualTo(1.5)
            assertThat(pb2DelayMs).isEqualTo(25 * 60_000L)
            assertThat(pb3U).isEqualTo(1.0)
            assertThat(pb3DelayMs).isEqualTo(40 * 60_000L)
        }
    }

    @Test
    fun `switched-off pre-bolus 2 and 3 are sent as zero with no delay`() {
        build()
        sut.updatePb2(1.5)
        sut.updatePb3(1.0)
        sut.prepareAndConfirm()
        sut.commit(null)
        with(activateCaptured()) {
            assertThat(pb2U).isEqualTo(0.0)
            assertThat(pb2DelayMs).isEqualTo(0L)
            assertThat(pb3U).isEqualTo(0.0)
            assertThat(pb3DelayMs).isEqualTo(0L)
        }
    }

    @Test
    fun `the mode started is the one confirmed, not one changed afterwards`() {
        build()
        sut.selectMode(MealMode.BREAKFAST)
        sut.prepareAndConfirm()
        sut.selectMode(MealMode.DINNER) // after the confirmation was shown
        sut.commit(null)
        assertThat(activateCaptured().mode).isEqualTo(MealMode.BREAKFAST)
    }

    @Test
    fun `commit without a confirmation does nothing`() {
        build()
        sut.commit(null)
        verify(mealOverrideManager, never()).activateOverride(any(), anyOrNull(), any(), any(), any(), any(), any(), any(), any(), any(), any())
    }

    // ── Input limits ─────────────────────────────────────────────────────────

    @Test
    fun `inputs are clamped to their ranges`() {
        build()
        sut.updateDuration(10.0); assertThat(sut.uiState.value.durationMinutes).isEqualTo(30)
        sut.updateDuration(900.0); assertThat(sut.uiState.value.durationMinutes).isEqualTo(480)
        sut.updatePb2Delay(1.0); assertThat(sut.uiState.value.pb2DelayMinutes).isEqualTo(5)
        sut.updatePb3Delay(500.0); assertThat(sut.uiState.value.pb3DelayMinutes).isEqualTo(120)
        sut.updateDuraStrength(9.0); assertThat(sut.uiState.value.duraStrength).isEqualTo(5.0)
        sut.updateIsf(-1.0); assertThat(sut.uiState.value.isf).isEqualTo(0.0)
        sut.updatePb2(9.0); assertThat(sut.uiState.value.pb2U).isEqualTo(4.0)
    }

    @Test
    fun `pre-bolus 2 defaults come from settings`() {
        build()
        assertThat(sut.uiState.value.pb2U).isEqualTo(2.0)
        assertThat(sut.uiState.value.pb2DelayMinutes).isEqualTo(30)
        assertThat(sut.uiState.value.pb2Enabled).isFalse()
    }

    // ── Add to meal ──────────────────────────────────────────────────────────

    @Test
    fun `with a meal running the dialog adds to it`() {
        dinnerRunning()
        build()
        with(sut.uiState.value) {
            assertThat(canAdd).isTrue()
            assertThat(addMode).isTrue()
            assertThat(addMinutesLeft).isEqualTo(80)
            assertThat(runningPb2WaitingU).isEqualTo(1.5)
        }
    }

    @Test
    fun `an auto-detected UAM mode is not added to`() {
        dinnerRunning(MealMode.UAM_DINNER)
        build()
        assertThat(sut.uiState.value.canAdd).isFalse()
        assertThat(sut.uiState.value.addMode).isFalse()
    }

    @Test
    fun `a dose typed by hand is added to the meal and stops it being learned from`() {
        val running = dinnerRunning()
        whenever { batchExecutor.prepare(any(), any(), any()) }.thenReturn(ActionProgress.Prepared(id = 7L))
        whenever { batchExecutor.commit(any(), any(), any(), any()) }.thenReturn(ActionProgress.Applied)
        whenever(mealOverrideManager.addToMeal(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(true)
        bg(150.0)
        build()
        sut.updateAddNow(2.0)
        sut.updateAddPb2(0.5)
        sut.prepareAndConfirm()
        val confirm = effects.single() as SmartMealDialogViewModel.SideEffect.ShowConfirmation
        assertThat(confirm.bolusId).isEqualTo(7L)
        assertThat(confirm.lines.none { it.role == ConfirmationRole.WARNING }).isTrue()

        sut.commit(confirm.bolusId)
        verify(mealOverrideManager).addToMeal(
            eq(2.0), eq(0.5), eq(0.0), eq(NOW + 80 * 60_000L), eq(false), any(), any(), eq(false)
        )
        verify(mealOverrideManager, never()).activateOverride(any(), anyOrNull(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        verify(mealEstimateCalibration).dropEstimate(running.triggerTimeMs)
        verifyBlocking(batchExecutor) { commit(eq(7L), eq(Sources.InsulinDialog), any(), any()) }
    }

    @Test
    fun `a dose now while BG is falling and above max IOB is warned about`() {
        dinnerRunning()
        whenever { batchExecutor.prepare(any(), any(), any()) }.thenReturn(ActionProgress.Prepared(id = 1L))
        bg(150.0, delta = -9.0)
        build()
        sut.updateAddNow(4.0)          // IOB 2.5 + 4 > max IOB 4
        whenever(maxIob.value()).thenReturn(4.0)
        sut.prepareAndConfirm()
        val confirm = effects.single() as SmartMealDialogViewModel.SideEffect.ShowConfirmation
        assertThat(confirm.lines.count { it.role == ConfirmationRole.WARNING }).isEqualTo(2)
    }

    @Test
    fun `filling in an estimated dessert gives its carbs now`() {
        val running = dinnerRunning()
        val profile: EffectiveProfile = mock()
        whenever(profile.getIc()).thenReturn(5.0)
        whenever { profileFunction.getProfile() }.thenReturn(profile)
        whenever(preferences.get(IntKey.ApsSmartInsulinMealPlanCarbPct)).thenReturn(50)
        whenever(preferences.get(IntKey.ApsSmartInsulinMealPlanFatProteinPct)).thenReturn(70)
        whenever(preferences.get(IntKey.ApsSmartInsulinMealPlanPb3SharePct)).thenReturn(40)
        whenever(preferences.get(DoubleKey.ApsSmartInsulinMealPlanMaxTotalU)).thenReturn(10.0)
        whenever(mealEstimateCalibration.factor).thenReturn(1.0)
        whenever { mealFoodRepository.estimate(any(), anyOrNull()) }.thenReturn(FoodLookupResult.Success(listOf(dessert())))
        bg(150.0)
        build()
        sut.updateDescription("cake")
        sut.estimateMeal()
        sut.fillInFromFood()
        with(sut.uiState.value) {
            // 30 g at 5 g/U = 6 U, half of it now. No fat or protein, so pre-bolus 2 stays as it is.
            assertThat(addNowU).isWithin(1e-9).of(3.0)
            assertThat(addPb2U).isEqualTo(0.0)
            assertThat(addPb3U).isEqualTo(0.0)
            assertThat(addMinutesLeft).isEqualTo(120)
        }
        sut.prepareAndConfirm()
        sut.commit(null)
        verify(mealEstimateCalibration).addToEstimate(eq(running.triggerTimeMs), any(), eq(30.0), eq(30.0), eq(0.0), eq(0.0))
        verify(mealEstimateCalibration, never()).dropEstimate(any())
        // More food: the meal no longer stands for one saved meal.
        verify(savedMealLearning).dropMeal(running.triggerTimeMs)
    }

    @Test
    fun `after exercise the cut is offered, cuts the pre-boluses and the mode, and the meal is not learned from`() {
        whenever(dateUtil.now()).thenReturn(NOW)
        whenever(recentActivity.afterActivity(NOW)).thenReturn(AfterActivity(activeMin = 60, cutPct = 15))
        val profile: EffectiveProfile = mock()
        whenever(profile.getIc()).thenReturn(5.0)
        whenever { profileFunction.getProfile() }.thenReturn(profile)
        whenever(preferences.get(IntKey.ApsSmartInsulinMealPlanCarbPct)).thenReturn(100)
        whenever(mealEstimateCalibration.factor).thenReturn(1.0)
        build()
        with(sut.uiState.value) {
            assertThat(afterActivity).isEqualTo(AfterActivity(60, 15))
            assertThat(afterActivityOn).isFalse()
        }
        sut.addManualFood(dessert(carbs = 10.0).copy(source = FoodSource.MANUAL))
        sut.fillInFromFood()
        assertThat(sut.uiState.value.pb1U).isWithin(1e-9).of(2.0)
        // On: what was filled in is cut, and the next Fill in plans with the cut.
        sut.updateAfterActivity(true)
        assertThat(sut.uiState.value.pb1U).isWithin(1e-9).of(1.7)
        sut.fillInFromFood()
        assertThat(sut.uiState.value.plan!!.carbsU).isWithin(1e-9).of(10.0 * 0.85 / 5.0)
        assertThat(sut.uiState.value.pb1U).isWithin(1e-9).of(1.7)
        // Off gives it back.
        sut.updateAfterActivity(false)
        assertThat(sut.uiState.value.pb1U).isWithin(1e-9).of(2.0)
        sut.updateAfterActivity(true)

        sut.prepareAndConfirm()
        sut.commit(null)
        verify(mealOverrideManager).setAfterActivityCut(15)
        verify(savedMealLearning, never()).recordMeal(anyOrNull(), any(), any(), any(), any(), any(), any(), any())
        verify(mealEstimateCalibration, never()).recordEstimate(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `pre-bolus 1 above the max is planned as a rest given once BG rises`() {
        whenever(dateUtil.now()).thenReturn(NOW)
        val profile: EffectiveProfile = mock()
        whenever(profile.getIc()).thenReturn(5.0)
        whenever { profileFunction.getProfile() }.thenReturn(profile)
        whenever(preferences.get(IntKey.ApsSmartInsulinMealPlanCarbPct)).thenReturn(100)
        whenever(mealEstimateCalibration.factor).thenReturn(1.0)
        build()
        // 30 g at 5 g/U = 6 U; the max pre-bolus is 4 U.
        sut.addManualFood(dessert().copy(source = FoodSource.MANUAL))
        sut.fillInFromFood()
        with(sut.uiState.value) {
            assertThat(pb1U).isWithin(1e-9).of(4.0)
            assertThat(pb1RestU).isWithin(1e-9).of(2.0)
        }
        sut.prepareAndConfirm()
        sut.commit(null)
        verify(mealOverrideManager).setPreBolus1Rest(2.0)
    }

    @Test
    fun `without exercise nothing is offered and the meal is learned from`() {
        whenever(dateUtil.now()).thenReturn(NOW)
        build()
        assertThat(sut.uiState.value.afterActivity).isNull()
        sut.addManualFood(dessert().copy(source = FoodSource.MANUAL))
        sut.updateAfterActivity(true)
        assertThat(sut.uiState.value.afterActivityOn).isFalse()
        sut.prepareAndConfirm()
        sut.commit(null)
        verify(mealOverrideManager, never()).setAfterActivityCut(any())
        verify(mealOverrideManager, never()).setPreBolus1Rest(any())
        verify(savedMealLearning).recordMeal(anyOrNull(), any(), any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `food added to a meal cut after exercise is cut the same`() {
        val running = dinnerRunning().copy(afterActivityPct = 20)
        whenever(mealOverrideManager.activeState).thenReturn(running)
        val profile: EffectiveProfile = mock()
        whenever(profile.getIc()).thenReturn(5.0)
        whenever { profileFunction.getProfile() }.thenReturn(profile)
        whenever(preferences.get(IntKey.ApsSmartInsulinMealPlanCarbPct)).thenReturn(50)
        whenever(mealEstimateCalibration.factor).thenReturn(1.0)
        bg(150.0)
        build()
        sut.addManualFood(dessert().copy(source = FoodSource.MANUAL))
        sut.fillInFromFood()
        // 30 g at 5 g/U = 6 U, 20 % less, half of it now.
        assertThat(sut.uiState.value.addNowU).isWithin(1e-9).of(2.4)
    }

    @Test
    fun `starting a new meal while one runs warns that it is replaced`() {
        dinnerRunning()
        build()
        sut.updateAddMode(false)
        sut.prepareAndConfirm()
        val confirm = effects.single() as SmartMealDialogViewModel.SideEffect.ShowConfirmation
        // The mode is replaced, and its pre-bolus 2 still waits.
        assertThat(confirm.lines.count { it.role == ConfirmationRole.WARNING }).isEqualTo(2)
    }

    // --- My foods ---

    @Test
    fun `a saved meal is added in one tap, and a new estimate does not replace it`() {
        whenever(dateUtil.now()).thenReturn(NOW)
        whenever { mealFoodRepository.estimate(any(), anyOrNull()) }.thenReturn(FoodLookupResult.Success(listOf(dessert())))
        build()
        sut.addManualFood(dessert(carbs = 20.0).copy(name = "Wrap", source = FoodSource.MANUAL))
        sut.addManualFood(dessert(carbs = 0.0, protein = 25.0).copy(name = "Turkey", source = FoodSource.MANUAL))
        sut.saveMeal("Turkey wrap")
        assertThat(sut.uiState.value.lastSave).isEqualTo(SaveResult.SAVED)
        sut.clearFood()

        val wrap = sut.uiState.value.savedFoods.single()
        sut.addSavedFood(wrap)
        sut.updateDescription("cake")
        sut.estimateMeal()
        with(sut.uiState.value) {
            assertThat(foodItems.map { it.name }).containsExactly("Wrap", "Turkey", "Dessert").inOrder()
            assertThat(savedFoods.single().uses).isEqualTo(1)
            // The typed-in foods and the estimate are all in the recent list.
            assertThat(recentFoods.map { it.name }).containsExactly("Dessert", "Turkey", "Wrap").inOrder()
        }
        sut.estimateMeal()
        assertThat(sut.uiState.value.foodItems.map { it.name }).containsExactly("Wrap", "Turkey", "Dessert").inOrder()
    }

    @Test
    fun `My foods is kept in the settings`() {
        whenever(dateUtil.now()).thenReturn(NOW)
        build()
        sut.addManualFood(dessert().copy(name = "Biscoff", source = FoodSource.MANUAL))
        sut.saveFood(0, "Biscoff")
        verify(preferences).put(eq(StringNonKey.ApsSmartInsulinSavedFoods), argThat { contains("Biscoff") })
        sut.renameSavedFood("Biscoff", "Biscoff biscuit")
        assertThat(sut.uiState.value.savedFoods.single().name).isEqualTo("Biscoff biscuit")
        sut.deleteSavedFood("Biscoff biscuit")
        assertThat(sut.uiState.value.savedFoods).isEmpty()
    }

    @Test
    fun `a saved meal uses what it learned and is watched to learn more`() {
        whenever(dateUtil.now()).thenReturn(NOW)
        val profile: EffectiveProfile = mock()
        whenever(profile.getIc()).thenReturn(5.0)
        whenever { profileFunction.getProfile() }.thenReturn(profile)
        whenever(mealEstimateCalibration.factor).thenReturn(1.0)
        whenever(savedMealLearning.learned("Turkey wrap")).thenReturn(LearnedMeal(carbFactor = 1.2, waveFactor = 0.8, tailFactor = 0.8, meals = 3, fpHalfMin = 145))
        whenever(preferences.get(IntKey.ApsSmartInsulinMealPlanFatProteinPct)).thenReturn(70)
        whenever(preferences.get(DoubleKey.ApsSmartInsulinMealPlanMaxTotalU)).thenReturn(10.0)
        build()
        sut.addManualFood(dessert(carbs = 20.0, protein = 25.0, fat = 5.0).copy(name = "Wrap", source = FoodSource.MANUAL))
        sut.saveMeal("Turkey wrap")
        sut.clearFood()
        sut.addSavedFood(sut.uiState.value.savedFoods.single())
        assertThat(sut.uiState.value.sourceMeal).isEqualTo("Turkey wrap")

        sut.fillInFromFood()
        with(sut.uiState.value.plan!!) {
            assertThat(carbsU).isWithin(1e-9).of(20.0 * 1.2 / 5.0)
            // 1.45 FPU = 14.5 g carb equivalent, at its own factor.
            assertThat(fatProteinU).isWithin(1e-9).of(14.5 * 0.8 / 5.0)
            // Its fat and protein are half in by 145 min, and the insulin peaks at 55 min: pre-bolus 2 at 90 min.
            assertThat(pb2DelayMin).isEqualTo(90)
        }
        assertThat(sut.uiState.value.learnedTiming).isEqualTo(90 to MealDosePlanner.PB3_DELAY_MIN)
        sut.prepareAndConfirm()
        sut.commit(null)
        // 1.45 FPU is not heavy: all of it is the wave, and there is no pre-bolus 3.
        verify(savedMealLearning).recordMeal(eq("Turkey wrap"), eq(NOW), any(), any(), eq(0L), eq(20.0), eq(14.5), eq(0.0))
    }

    @Test
    fun `a saved meal with other food added is only learned as any meal`() {
        whenever(dateUtil.now()).thenReturn(NOW)
        build()
        sut.addManualFood(dessert().copy(name = "Wrap", source = FoodSource.MANUAL))
        sut.saveMeal("Turkey wrap")
        sut.clearFood()
        sut.addSavedFood(sut.uiState.value.savedFoods.single())
        sut.addManualFood(dessert().copy(name = "Chips", source = FoodSource.MANUAL))
        assertThat(sut.uiState.value.sourceMeal).isNull()
        sut.prepareAndConfirm()
        sut.commit(null)
        // Still watched for the general factors, but not as the saved meal.
        verify(savedMealLearning).recordMeal(eq(null), any(), any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `renaming or deleting a saved meal keeps its learning with it`() {
        whenever(dateUtil.now()).thenReturn(NOW)
        build()
        sut.addManualFood(dessert().copy(name = "Wrap", source = FoodSource.MANUAL))
        sut.saveMeal("Wrap")
        sut.renameSavedFood("Wrap", "Turkey wrap")
        verify(savedMealLearning).rename("Wrap", "Turkey wrap")
        sut.deleteSavedFood("Turkey wrap")
        verify(savedMealLearning).forget("Turkey wrap")
    }

    @Test
    fun `foods ticked together are added at once, and only one saved meal alone learns`() {
        whenever(dateUtil.now()).thenReturn(NOW)
        build()
        listOf("Wrap", "Turkey", "Cheese").forEach { n ->
            sut.addManualFood(dessert().copy(name = n, source = FoodSource.MANUAL))
            sut.saveFood(0, n)
            sut.clearFood()
        }
        val saved = sut.uiState.value.savedFoods
        val recentCheese = sut.uiState.value.recentFoods.first { it.name == "Cheese" }
        sut.addSelected(listOf(saved.first { it.name == "Wrap" }, saved.first { it.name == "Turkey" }), listOf(recentCheese))
        with(sut.uiState.value) {
            assertThat(foodItems.map { it.name }).containsExactly("Wrap", "Turkey", "Cheese").inOrder()
            assertThat(sourceMeal).isNull()
            assertThat(savedFoods.first { it.name == "Wrap" }.uses).isEqualTo(1)
        }
        sut.clearFood()
        sut.addSelected(listOf(saved.first { it.name == "Wrap" }), emptyList())
        assertThat(sut.uiState.value.sourceMeal).isEqualTo("Wrap")
    }

    @Test
    fun `changing a saved meal keeps its learning only for a small change`() {
        whenever(dateUtil.now()).thenReturn(NOW)
        build()
        sut.addManualFood(dessert(carbs = 40.0).copy(name = "Wrap", source = FoodSource.MANUAL))
        sut.saveMeal("Turkey wrap")
        sut.clearFood()

        sut.editSavedFood(sut.uiState.value.savedFoods.single())
        assertThat(sut.uiState.value.editingMeal).isEqualTo("Turkey wrap")
        sut.changeQuantity(0, 0.1)
        sut.saveChanges()
        verify(savedMealLearning, never()).forget(any())
        assertThat(sut.uiState.value.editingMeal).isNull()

        sut.editSavedFood(sut.uiState.value.savedFoods.single())
        sut.addManualFood(dessert(carbs = 60.0).copy(name = "Chips", source = FoodSource.MANUAL))
        sut.saveChanges()
        verify(savedMealLearning).forget("Turkey wrap")
        assertThat(sut.uiState.value.savedFoods.single().items.map { it.name }).containsExactly("Wrap", "Chips")
    }

    @Test
    fun `filling in a heavy meal leaves DURA and Protein-Fat off and makes the mode long enough`() {
        val profile: EffectiveProfile = mock()
        whenever(profile.getIc()).thenReturn(5.0)
        whenever { profileFunction.getProfile() }.thenReturn(profile)
        whenever(mealEstimateCalibration.factor).thenReturn(1.0)
        build()
        // 60 g protein and 40 g fat: 6 FPU.
        sut.addManualFood(dessert(carbs = 10.0, protein = 60.0, fat = 40.0).copy(name = "Steak", source = FoodSource.MANUAL))
        sut.fillInFromFood()
        with(sut.uiState.value) {
            assertThat(duraEnabled).isFalse()
            assertThat(pfAfter).isFalse()
            assertThat(durationMinutes).isEqualTo(360)
        }
    }

    // --- Food log ---

    @Test
    fun `log only counts the food without a meal or insulin`() {
        whenever(dateUtil.now()).thenReturn(NOW)
        build()
        sut.addManualFood(dessert(carbs = 20.0, protein = 5.0, fat = 10.0).copy(name = "Biscuits", source = FoodSource.MANUAL))
        sut.logOnly()
        verify(foodLog).add(FoodLogEntry(NOW, "Biscuits", 20.0, 0.0, 5.0, 10.0))
        with(sut.uiState.value) {
            assertThat(foodItems).isEmpty()
            assertThat(loggedKcal).isWithin(1e-9).of(190.0)
        }
        verify(mealOverrideManager, never()).activateOverride(any(), anyOrNull(), any(), any(), any(), any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `food that looks wrong is held until the user checks it`() {
        whenever(dateUtil.now()).thenReturn(NOW)
        build()
        // 734 g of fat: about 6600 kcal.
        sut.addManualFood(dessert(fat = 100.0).copy(name = "Typo", baseGrams = 734.0, source = FoodSource.MANUAL))
        sut.logOnly()
        verify(foodLog, never()).add(any())
        assertThat(sut.uiState.value.logCheckKcal).isNotNull()
        sut.logOnly(force = true)
        verify(foodLog).add(any())
        assertThat(sut.uiState.value.logCheckKcal).isNull()
    }

    @Test
    fun `a started meal and food added to it are logged`() {
        whenever(dateUtil.now()).thenReturn(NOW)
        build()
        sut.addManualFood(dessert().copy(name = "Pasta", source = FoodSource.MANUAL))
        sut.prepareAndConfirm()
        sut.commit(null)
        verify(foodLog).add(argThat { name == "Pasta" })
    }

    private companion object {

        const val NOW = 1_790_000_000_000L
    }
}
