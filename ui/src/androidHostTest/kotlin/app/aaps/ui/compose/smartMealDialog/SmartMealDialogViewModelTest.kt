package app.aaps.ui.compose.smartMealDialog

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.bolus.BatchAction
import app.aaps.core.interfaces.bolus.BatchExecutor
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import app.aaps.core.interfaces.clientcontrol.FailureReason
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.DoubleNonKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
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
        sut = SmartMealDialogViewModel(
            mealOverrideManager, preferences, profileFunction, profileUtil, activePlugin, ch,
            batchExecutor, rxBus, rh, dateUtil, decimalFormatter, scope
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
        whenever(mealOverrideManager.preBolus2StatusText).thenReturn("")
        whenever(mealOverrideManager.preBolus3StatusText).thenReturn("")
        whenever(rh.gs(any())).thenReturn("")
        whenever(rh.gs(any(), anyVararg())).thenReturn("")
        whenever(decimalFormatter.toPumpSupportedBolus(any(), any())).thenReturn("")
        whenever(decimalFormatter.to0Decimal(any())).thenReturn("")
        whenever(decimalFormatter.to1Decimal(any())).thenReturn("")
        whenever(decimalFormatter.to2Decimal(any())).thenReturn("")
    }

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
    fun `switching mode loads that mode's ISF and DURA settings`() {
        whenever(preferences.getRaw(UnitDoubleKey.ApsSmartInsulinDinnerIsf)).thenReturn(54.0)
        whenever(preferences.get(BooleanNonKey.ApsSmartInsulinDinnerDuraEnabled)).thenReturn(true)
        whenever(preferences.get(DoubleNonKey.ApsSmartInsulinDinnerDuraFloor)).thenReturn(27.0)
        whenever(preferences.get(DoubleNonKey.ApsSmartInsulinDinnerDuraStrength)).thenReturn(1.5)
        build()
        sut.selectMode(MealMode.DINNER)
        with(sut.uiState.value) {
            assertThat(isf).isWithin(1e-9).of(3.0)
            assertThat(duraEnabled).isTrue()
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
        verify(preferences).put(BooleanNonKey.ApsSmartInsulinLunchDuraEnabled, true)
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
    fun `DURA off saves no floor even if one was entered`() {
        build()
        sut.updateDuraFloor(1.5)
        sut.updateDuraEnabled(false)
        sut.prepareAndConfirm()
        sut.commit(null)
        verify(preferences).put(BooleanNonKey.ApsSmartInsulinLunchDuraEnabled, false)
        verify(preferences).put(DoubleNonKey.ApsSmartInsulinLunchDuraFloor, 0.0)
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
}
