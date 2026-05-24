package app.aaps.ui.compose.smartMealDialog

import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.interfaces.Preferences
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for the suspend-coroutine bolus delivery pattern introduced in
 * ice-step41 to adapt to AAPS dev branch's `commandQueue.bolus()` API change
 * (callback-style → suspend function returning PumpEnactResult).
 *
 * Tests focus on the SmartMealDialogViewModel's PB1 delivery path because:
 *   1. It's the simplest dependency surface (no complex state machine).
 *   2. The PB2/PB3 paths in MealOverrideManagerImpl use the identical try/catch
 *      pattern, so logic verified here transfers structurally.
 *
 * What these tests prove:
 *   - The suspend bolus call is wired correctly (success/failure branches fire)
 *   - The try/catch hardening (ice-step41c) catches pump-driver exceptions
 *   - The meal override is NOT activated when delivery fails or throws
 *     (safety: never tell the loop a meal is active if we don't know the
 *     pre-bolus actually went in)
 *
 * What these tests don't prove:
 *   - Real pump-side bolus delivery (that's pump hardware, integration-test)
 *   - Loop-cycle concurrency (would need integration tests)
 *
 * Companion test for MealOverrideManagerImpl's PB2/PB3 paths is left as a
 * follow-up — requires injecting CoroutineScope for test-controlled
 * coroutine advancement, which is a small refactor of that class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartMealBolusDeliveryTest {

    private val testDispatcher = StandardTestDispatcher()

    // ── Dependency mocks (match the viewmodel's @Inject constructor) ──────────
    // Using relaxed = true so mockk auto-returns safe defaults for everything
    // we don't explicitly stub. The viewmodel touches many methods on these
    // collaborators during init / state setup; we don't want to stub each one
    // by hand. Only the methods we actually assert on (commandQueue.bolus,
    // mealOverrideManager.activateOverride) get explicit setup below.
    @MockK(relaxed = true) lateinit var mealOverrideManager: MealOverrideManager
    @MockK(relaxed = true) lateinit var profileFunction:     ProfileFunction
    @MockK(relaxed = true) lateinit var profileUtil:         ProfileUtil
    @MockK(relaxed = true) lateinit var activePlugin:        ActivePlugin
    @MockK(relaxed = true) lateinit var commandQueue:        CommandQueue
    @MockK(relaxed = true) lateinit var preferences:         Preferences
    @MockK(relaxed = true) lateinit var sp:                  SP
    @MockK(relaxed = true) lateinit var dateUtil:            DateUtil
    @MockK(relaxed = true) lateinit var decimalFormatter:    DecimalFormatter
    @MockK(relaxed = true) lateinit var uiInteraction:       UiInteraction
    @MockK(relaxed = true) lateinit var rh:                  ResourceHelper

    private lateinit var viewModel: SmartMealDialogViewModel

    // ── Test helpers ──────────────────────────────────────────────────────────
    private fun successResult(comment: String = "OK"): PumpEnactResult {
        // PumpEnactResult is an interface — mock it directly to avoid the
        // PumpEnactResultObject(injector) construction dance in tests.
        val r = mockk<PumpEnactResult>(relaxed = true)
        every { r.success } returns true
        every { r.comment } returns comment
        return r
    }

    private fun failureResult(comment: String = "Pump refused bolus"): PumpEnactResult {
        val r = mockk<PumpEnactResult>(relaxed = true)
        every { r.success } returns false
        every { r.comment } returns comment
        return r
    }

    @BeforeEach
    fun setup() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        Dispatchers.setMain(testDispatcher)

        // Required stubs:
        //   1. dateUtil.now()         — drives bolusInfo.timestamp
        //   2. preferences.get(...)   — relaxed default of 0.0 would coerce
        //                               preBolus1U down to 0 and take the
        //                               no-PB1 branch in confirmAndActivate.
        //                               Stub the specific key with a sensible
        //                               value (5.0U covers any preBolus1U
        //                               we set in tests).
        //   3. activePlugin.smartInsulin → null to skip the init block's
        //                               overviewStateFlow.collect chain,
        //                               which throws on a relaxed mock.
        //   4. activePlugin.activePump.pumpDescription.bolusStep — chain
        //                               touched by refresh(). Stub the
        //                               nested return values.
        every { dateUtil.now() } returns 1_000_000_000L
        every { preferences.get(app.aaps.core.keys.DoubleKey.ApsSmartInsulinMaxPreBolus) } returns 5.0
        every { activePlugin.smartInsulin } returns null
        every { activePlugin.activePump.pumpDescription.bolusStep } returns 0.05

        viewModel = SmartMealDialogViewModel(
            mealOverrideManager = mealOverrideManager,
            profileFunction     = profileFunction,
            profileUtil         = profileUtil,
            activePlugin        = activePlugin,
            commandQueue        = commandQueue,
            preferences         = preferences,
            sp                  = sp,
            dateUtil            = dateUtil,
            decimalFormatter    = decimalFormatter,
            uiInteraction       = uiInteraction,
            rh                  = rh
        )

        // Configure dialog state: ADD mode (default), PB1 enabled at 1.0U, carbs entered
        viewModel.setCarbsG(10.0)
        viewModel.setPreBolus1Enabled(true)
        viewModel.setPreBolus1U(1.0)

        // Drive the init's launch { refresh() } so maxPreBolus is loaded into state
        // before the test calls confirmAndActivate
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SUCCESS PATH
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun `PB1 success activates meal mode and fires onDone`() = runTest(testDispatcher) {
        coEvery { commandQueue.bolus(any()) } returns successResult()

        var onDoneFired = false
        var deliveryError: String? = null

        viewModel.confirmAndActivate(
            onDeliveryError = { deliveryError = it },
            onDone          = { onDoneFired = true }
        )
        advanceUntilIdle()  // run the launched coroutine to completion

        assertTrue(onDoneFired, "onDone should fire on success")
        assertNull(deliveryError, "onDeliveryError should NOT fire on success")

        // Verify bolus was sent with the correct amount and pre-bolus notes
        coVerify(exactly = 1) {
            commandQueue.bolus(match { it.insulin == 1.0 && it.notes?.contains("pre-bolus 1") == true })
        }
        // Verify meal override was activated (i.e., startMealMode ran)
        coVerify(exactly = 1) {
            mealOverrideManager.activateOverride(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // FAILURE PATH — pump returns success=false
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun `PB1 failure surfaces error and does NOT activate meal mode`() = runTest(testDispatcher) {
        coEvery { commandQueue.bolus(any()) } returns failureResult("Pump refused")

        var onDoneFired = false
        var deliveryError: String? = null

        viewModel.confirmAndActivate(
            onDeliveryError = { deliveryError = it },
            onDone          = { onDoneFired = true }
        )
        advanceUntilIdle()

        assertFalse(onDoneFired, "onDone should NOT fire on failure")
        assertNotNull(deliveryError, "onDeliveryError should fire on failure")
        assertEquals("Pump refused", deliveryError, "Error message should pass pump comment through")

        // SAFETY: meal override must NOT activate when bolus failed
        coVerify(exactly = 0) {
            mealOverrideManager.activateOverride(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // EXCEPTION PATH — pump driver throws (ice-step41c hardening)
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun `PB1 exception surfaces communication error and does NOT activate meal mode`() = runTest(testDispatcher) {
        coEvery { commandQueue.bolus(any()) } throws RuntimeException("Bluetooth timeout")

        var onDoneFired = false
        var deliveryError: String? = null

        viewModel.confirmAndActivate(
            onDeliveryError = { deliveryError = it },
            onDone          = { onDoneFired = true }
        )
        advanceUntilIdle()

        assertFalse(onDoneFired, "onDone should NOT fire on exception")
        assertNotNull(deliveryError, "onDeliveryError should fire on exception")
        assertTrue(deliveryError!!.contains("Communication error"), "Error should signal comms issue, not pump rejection")
        assertTrue(deliveryError!!.contains("Bluetooth timeout"), "Underlying exception message should be included for diagnosis")

        // SAFETY: meal override must NOT activate when we don't know whether bolus delivered
        coVerify(exactly = 0) {
            mealOverrideManager.activateOverride(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // NO PB1 PATH — verify the no-bolus branch still works (no regression)
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun `no PB1 activates meal mode immediately without bolus call`() = runTest(testDispatcher) {
        viewModel.setPreBolus1Enabled(false)
        viewModel.setPreBolus1U(0.0)

        var onDoneFired = false
        var deliveryError: String? = null

        viewModel.confirmAndActivate(
            onDeliveryError = { deliveryError = it },
            onDone          = { onDoneFired = true }
        )
        advanceUntilIdle()

        assertTrue(onDoneFired, "onDone should fire even without PB1")
        assertNull(deliveryError, "no error when no bolus was requested")

        // Bolus should NOT have been called
        coVerify(exactly = 0) { commandQueue.bolus(any()) }
        // Meal override SHOULD activate (we're just skipping the bolus, not the meal)
        coVerify(exactly = 1) {
            mealOverrideManager.activateOverride(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }
}