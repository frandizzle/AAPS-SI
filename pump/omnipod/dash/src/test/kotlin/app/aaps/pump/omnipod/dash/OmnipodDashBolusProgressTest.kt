package app.aaps.pump.omnipod.dash

import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.BlePreCheck
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.BolusProgressState
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.omnipod.common.bledriver.pod.definition.BolusType
import app.aaps.pump.omnipod.common.bledriver.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.common.bledriver.pod.definition.PodConstants
import app.aaps.pump.omnipod.common.bledriver.event.PodEvent
import app.aaps.pump.omnipod.common.bledriver.pod.state.OmnipodDashPodStateManager
import app.aaps.pump.omnipod.common.definition.OmnipodCommandType
import app.aaps.pump.omnipod.dash.driver.OmnipodDashManager
import app.aaps.pump.omnipod.dash.history.DashHistory
import app.aaps.pump.omnipod.dash.history.database.DashHistoryDatabase
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * The bolus progress that the Dash driver reports while it waits for the pod, and the Stop button.
 *
 * The bolus dialog shows Stop only while the progress is below 100 % (`PumpActivityDialog`). These tests
 * record every progress state the dialog would see, with the time it was set, and check:
 * - an SMB counts up in 0.05 U steps, like a normal bolus,
 * - the count never reaches 100 % before the pod has confirmed the end, so Stop stays available,
 * - the count is never ahead of what the pod can have delivered,
 * - Stop works while counting and while the delivery status is checked,
 * - the amount that is recorded always comes from the pod, never from the count on the screen.
 *
 * The driver waits in real time (`Thread.sleep`), so the tests use small boluses: a bolus of n pulses
 * waits 2 n + 3 seconds before the first status check.
 */
class OmnipodDashBolusProgressTest {

    private lateinit var podStateManager: OmnipodDashPodStateManager
    private lateinit var omnipodManager: OmnipodDashManager
    private lateinit var history: DashHistory
    private lateinit var bolusProgressData: BolusProgressData
    private lateinit var sut: OmnipodDashPumpPlugin

    private lateinit var collectScope: CoroutineScope
    private val frames = CopyOnWriteArrayList<Frame>()
    @Volatile private var startNanos = System.nanoTime()

    /** What the mocked pod reports. Changed by the tests and by the stubbed pod commands. */
    @Volatile private var podDeliveryStatus: DeliveryStatus = DeliveryStatus.BASAL_ACTIVE
    @Volatile private var podLastBolus: OmnipodDashPodStateManager.LastBolus? = null

    /** One recorded progress state, with the time since the wait started. */
    private data class Frame(val elapsedMs: Long, val state: BolusProgressState?)

    @BeforeEach
    fun setUp() {
        podStateManager = mock()
        omnipodManager = mock()
        history = mock()
        val pumpSync = mock<PumpSync>()
        val rh = mock<ResourceHelper>()
        val ch = mock<ConcentrationHelper>()

        whenever(rh.gs(R.string.checking_delivery_status)).thenReturn(CHECKING)
        whenever(ch.fromPump(any<PumpInsulin>(), any<Boolean>())).thenAnswer { (it.arguments[0] as PumpInsulin).cU }
        whenever(ch.bolusProgressString(any<PumpInsulin>(), any<Boolean>())).thenAnswer { "Delivering ${(it.arguments[0] as PumpInsulin).cU} U" }
        whenever(ch.bolusProgressString(any<PumpInsulin>(), any<Double>(), any<Boolean>())).thenReturn("")

        whenever(podStateManager.deliveryStatus).thenAnswer { podDeliveryStatus }
        whenever(podStateManager.lastBolus).thenAnswer { podLastBolus }
        whenever(podStateManager.updateActiveCommand()).thenReturn(Maybe.empty())
        whenever(podStateManager.createActiveCommand(any(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(Single.just(activeCommand()))
        whenever(history.updateFromState(any())).thenReturn(Completable.complete())
        whenever(
            history.createRecord(eq(OmnipodCommandType.CANCEL_BOLUS), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        ).thenReturn(Single.just(CANCEL_HISTORY_ID))
        pumpSync.stub { on { expectedPumpState() } doReturn PumpSync.PumpState(null, null, null, null, "") }
        // The pod answers a status request with the state set by the test.
        whenever(omnipodManager.getStatus(any())).thenReturn(Observable.empty())
        // By default a stop request stops the bolus where it is.
        whenever(omnipodManager.stopBolus(any())).thenAnswer {
            podStopsBolus()
            Observable.empty<PodEvent>()
        }

        bolusProgressData = BolusProgressData(ch, CoroutineScope(Dispatchers.Unconfined))
        sut = OmnipodDashPumpPlugin(
            aapsLogger = mock<AAPSLogger>(),
            rh = rh,
            preferences = mock<Preferences>(),
            commandQueue = mock<CommandQueue>(),
            omnipodManager = omnipodManager,
            podStateManager = podStateManager,
            history = history,
            pumpSync = pumpSync,
            rxBus = mock<RxBus>(),
            aapsSchedulers = mock<AapsSchedulers>(),
            uiInteraction = mock<UiInteraction>(),
            notificationManager = mock<NotificationManager>(),
            pumpEnactResultProvider = { mock<PumpEnactResult>() },
            bolusProgressData = bolusProgressData,
            dashHistoryDatabase = mock<DashHistoryDatabase>(),
            protectionCheck = mock<ProtectionCheck>(),
            blePreCheck = mock<BlePreCheck>()
        )
        // As deliverTreatment does before it waits.
        sut.bolusDeliveryInProgress = true

        // Unconfined: the collector runs on the thread that sets the state, so every change is recorded.
        collectScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        collectScope.launch {
            bolusProgressData.state.collect { frames.add(Frame(elapsedMs(), it)) }
        }
    }

    @AfterEach
    fun tearDown() {
        collectScope.cancel()
        sut.bolusCanceled = false
        sut.bolusDeliveryInProgress = false
    }

    // --- Counting ---

    @Test
    fun `an SMB counts up through every 0_05 U step without skipping one`() {
        bolusProgressData.start(insulin = 0.25, isSMB = true)
        podFinishesWithoutTrouble(0.25)

        runWait(0.25)

        assertThat(countingFrames().map { hundredths(it.delivered.cU) }.distinct())
            .containsExactly(0, 5, 10, 15, 20).inOrder()
    }

    @Test
    fun `every counted amount is a whole number of pod pulses`() {
        bolusProgressData.start(insulin = 0.25, isSMB = true)
        podFinishesWithoutTrouble(0.25)

        runWait(0.25)

        countingFrames().forEach { state ->
            val pulses = state.delivered.cU / PodConstants.POD_PULSE_BOLUS_UNITS
            assertThat(pulses).isWithin(EPSILON).of(pulses.roundToInt().toDouble())
        }
    }

    @Test
    fun `the count never goes down`() {
        bolusProgressData.start(insulin = 0.25, isSMB = true)
        podFinishesWithoutTrouble(0.25)

        runWait(0.25)

        countingFrames().map { it.delivered.cU }.zipWithNext().forEach { (before, after) ->
            assertThat(after).isAtLeast(before - EPSILON)
        }
    }

    @Test
    fun `the count is never ahead of what the pod can have delivered`() {
        // The pod gives one pulse every 2 s and starts before the wait does, so after t seconds it has
        // given at least t / 2 whole pulses. The screen must not show more.
        bolusProgressData.start(insulin = 0.25, isSMB = true)
        podFinishesWithoutTrouble(0.25)

        runWait(0.25)

        countingFrameRecords().forEach { frame ->
            val pulsesGiven = frame.elapsedMs / 2_000
            assertThat(frame.state!!.delivered.cU).isAtMost(pulsesGiven * PodConstants.POD_PULSE_BOLUS_UNITS + EPSILON)
        }
    }

    @Test
    fun `the count stops one pulse short of the full amount`() {
        bolusProgressData.start(insulin = 0.15, isSMB = true)
        podFinishesWithoutTrouble(0.15)

        runWait(0.15)

        // Without the cap the time estimate reaches 0.15 U in the last seconds of the wait.
        assertThat(countingFrames().maxOf { it.delivered.cU }).isWithin(EPSILON).of(0.10)
        countingFrames().forEach { assertThat(it.percent).isLessThan(100) }
    }

    @Test
    fun `a 0_05 U SMB shows 0_00 U until the pod confirms it`() {
        bolusProgressData.start(insulin = 0.05, isSMB = true)
        podFinishesWithoutTrouble(0.05)

        runWait(0.05)

        assertThat(countingFrames()).isNotEmpty()
        countingFrames().forEach { state ->
            assertThat(state.delivered.cU).isEqualTo(0.0)
            assertThat(state.percent).isEqualTo(0)
        }
    }

    @Test
    fun `the count keeps the SMB flag, so the SMB progress button shows it`() {
        bolusProgressData.start(insulin = 0.15, isSMB = true)
        podFinishesWithoutTrouble(0.15)

        runWait(0.15)

        val states = frames.mapNotNull { it.state }
        states.forEach { assertThat(it.isSMB).isTrue() }
        // The SMB button on the overview shows the percent while it is in 1..99.
        assertThat(states.any { it.percent in 1..99 && it.status != TextRef.Literal(CHECKING) }).isTrue()
    }

    @Test
    fun `a normal bolus counts the same way`() {
        bolusProgressData.start(insulin = 0.15, isSMB = false)
        podFinishesWithoutTrouble(0.15)

        runWait(0.15)

        assertThat(countingFrames().map { hundredths(it.delivered.cU) }.distinct())
            .containsExactly(0, 5, 10).inOrder()
        frames.mapNotNull { it.state }.forEach { assertThat(it.isSMB).isFalse() }
    }

    @Test
    fun `the count shows the delivered amount in the status line`() {
        bolusProgressData.start(insulin = 0.15, isSMB = true)
        podFinishesWithoutTrouble(0.15)

        runWait(0.15)

        countingFrames().filter { it.delivered.cU > 0 }.forEach { state ->
            assertThat(state.status).isEqualTo(TextRef.Literal("Delivering ${state.delivered.cU} U"))
        }
    }

    // --- Stop stays available ---

    @Test
    fun `Stop is available in every state before the pod confirms the end`() {
        bolusProgressData.start(insulin = 0.15, isSMB = true)
        podFinishesWithoutTrouble(0.15)

        runWait(0.15)

        val states = frames.mapNotNull { it.state }
        assertThat(states).isNotEmpty()
        states.forEach { assertThat(stopAvailable(it)).isTrue() }
    }

    @Test
    fun `checking the delivery status shows 99 percent and the checking text`() {
        bolusProgressData.start(insulin = 0.05, isSMB = true)
        podFinishesWithoutTrouble(0.05)

        runWait(0.05)

        val checking = frames.mapNotNull { it.state }.filter { it.status == TextRef.Literal(CHECKING) }
        assertThat(checking).isNotEmpty()
        checking.forEach {
            assertThat(it.percent).isEqualTo(99)
            assertThat(stopAvailable(it)).isTrue()
        }
    }

    @Test
    fun `the status check starts only after the counting wait`() {
        // 0.15 U = 3 pulses, so the driver waits 3 * 2 + 3 = 9 s before it asks the pod.
        bolusProgressData.start(insulin = 0.15, isSMB = true)
        podFinishesWithoutTrouble(0.15)

        runWait(0.15)

        val firstCheck = frames.first { it.state?.status == TextRef.Literal(CHECKING) }
        assertThat(firstCheck.elapsedMs).isAtLeast(9_000)
        assertThat(countingFrameRecords().last().elapsedMs).isAtMost(firstCheck.elapsedMs)
    }

    // --- When the pod is still delivering ---

    @Test
    fun `when the pod is still delivering, the progress comes from the pod and Stop stays`() {
        bolusProgressData.start(insulin = 0.10, isSMB = true)
        podStillDeliveringOnFirstCheck(requested = 0.10, remaining = 0.05)

        val result = runWait(0.10)

        // 0.10 U asked, 0.05 U still to give: the pod has given half.
        val fromPod = frames.mapNotNull { it.state }.filter { it.percent == 50 && hundredths(it.delivered.cU) == 5 }
        assertThat(fromPod).isNotEmpty()
        fromPod.forEach { assertThat(stopAvailable(it)).isTrue() }
        assertThat(result).isWithin(EPSILON).of(0.10)
        verify(omnipodManager, times(2)).getStatus(any())
    }

    // --- Pressing Stop ---

    @Test
    fun `Stop while counting sends the stop to the pod at once and records what the pod delivered`() {
        bolusProgressData.start(insulin = 0.25, isSMB = true)
        podFinishesWithoutTrouble(0.25)
        podStopsAt(remaining = 0.20)

        val presser = pressStopWhen { countingFrames().any { it.delivered.cU > 0 } }
        val result = runWait(0.25)
        presser.join()

        assertThat(result).isWithin(EPSILON).of(0.05)
        verify(omnipodManager).stopBolus(any())
        verify(omnipodManager, never()).getStatus(any())
        // Without Stop the wait alone would take 13 s.
        assertThat(elapsedMs()).isLessThan(8_000)
    }

    @Test
    fun `Stop while the delivery status is checked sends the stop to the pod`() {
        bolusProgressData.start(insulin = 0.10, isSMB = true)
        podStopsAt(remaining = 0.05)
        // Every status request finds the bolus still running, so only the stop can end it.
        val firstCheckDone = CountDownLatch(1)
        doAnswer {
            podStillDelivering(requested = 0.10, remaining = 0.05)
            firstCheckDone.countDown()
            Observable.empty<PodEvent>()
        }.whenever(omnipodManager).getStatus(any())

        var stopWasAvailable = false
        val presser = thread {
            firstCheckDone.await(30, TimeUnit.SECONDS)
            stopWasAvailable = bolusProgressData.state.value?.let { stopAvailable(it) } == true
            sut.stopBolusDelivering()
        }
        val result = runWait(0.10)
        presser.join()

        assertThat(stopWasAvailable).isTrue()
        assertThat(result).isWithin(EPSILON).of(0.05)
        verify(omnipodManager).getStatus(any())
        verify(omnipodManager).stopBolus(any())
    }

    @Test
    fun `Stop before the wait with a command still open reads the pod first, then stops`() {
        whenever(podStateManager.activeCommand).thenReturn(activeCommand())
        podFinishesWithoutTrouble(0.10)
        podStopsAt(remaining = 0.0)
        sut.bolusCanceled = true

        val result = runWait(0.10)

        inOrder(omnipodManager) {
            verify(omnipodManager).getStatus(any())
            verify(omnipodManager).stopBolus(any())
        }
        assertThat(result).isWithin(EPSILON).of(0.10)
    }

    @Test
    fun `Stop is ignored when no bolus is running`() {
        sut.bolusDeliveryInProgress = false

        sut.stopBolusDelivering()

        assertThat(sut.bolusCanceled).isFalse()
    }

    @Test
    fun `Stop during a bolus sets the cancel flag`() {
        sut.stopBolusDelivering()

        assertThat(sut.bolusCanceled).isTrue()
    }

    // --- The recorded amount ---

    @Test
    fun `the recorded amount is the pod's amount, not the count on the screen`() {
        // The screen stops at 0.05 U for a 0.10 U bolus; the pod says it gave all of it.
        bolusProgressData.start(insulin = 0.10, isSMB = true)
        podFinishesWithoutTrouble(0.10)

        val result = runWait(0.10)

        assertThat(countingFrames().maxOf { it.delivered.cU }).isWithin(EPSILON).of(0.05)
        assertThat(result).isWithin(EPSILON).of(0.10)
    }

    @Test
    fun `a pod that gave less than asked is recorded with the smaller amount`() {
        bolusProgressData.start(insulin = 0.10, isSMB = true)
        podFinishes(requested = 0.10, remaining = 0.05)

        val result = runWait(0.10)

        assertThat(result).isWithin(EPSILON).of(0.05)
    }

    @Test
    fun `when the pod cannot be read, the full requested amount is recorded, never less`() {
        bolusProgressData.start(insulin = 0.05, isSMB = true)
        doReturn(Observable.error<PodEvent>(RuntimeException("no connection"))).whenever(omnipodManager).getStatus(any())

        val result = runWait(0.05)

        assertThat(result).isWithin(EPSILON).of(0.05)
        verify(omnipodManager, times(5)).getStatus(any())
        frames.mapNotNull { it.state }.forEach { assertThat(stopAvailable(it)).isTrue() }
    }

    @Test
    fun `when the pod keeps delivering after Stop, the stop is sent again every 2 s and the full amount is recorded`() {
        // Also guards the retry delay: it once slept 2000 s instead of 2 s.
        sut.bolusCanceled = true
        podStillDelivering(requested = 0.05, remaining = 0.05)
        doReturn(Observable.empty<PodEvent>()).whenever(omnipodManager).stopBolus(any())

        val result = runWait(0.05)

        assertThat(result).isWithin(EPSILON).of(0.05)
        verify(omnipodManager, times(5)).stopBolus(any())
        assertThat(elapsedMs()).isLessThan(15_000)
    }

    @Test
    fun `without a progress dialog the wait still works`() {
        // A basal correction has no bolus progress state.
        podFinishesWithoutTrouble(0.05)

        val result = runWait(0.05)

        assertThat(result).isWithin(EPSILON).of(0.05)
        assertThat(frames.mapNotNull { it.state }).isEmpty()
    }

    // --- Helpers ---

    /** Runs the wait like deliverTreatment does, and returns the amount it records. */
    private fun runWait(requested: Double): Double {
        startNanos = System.nanoTime()
        return sut.waitForBolusDeliveryToComplete(requested).timeout(60, TimeUnit.SECONDS).blockingGet()
    }

    /** The same rule as the Stop button in `PumpActivityDialog`. */
    private fun stopAvailable(state: BolusProgressState): Boolean =
        !state.stalled && state.percent < 100 && state.stopDeliveryEnabled && !state.stopPressed

    /** States set while counting: after start, before the first status check. */
    private fun countingFrameRecords(): List<Frame> =
        frames.filter { it.state != null }
            .takeWhile { it.state!!.status != TextRef.Literal(CHECKING) }

    private fun countingFrames(): List<BolusProgressState> = countingFrameRecords().map { it.state!! }

    private fun pressStopWhen(condition: () -> Boolean): Thread = thread {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        sut.stopBolusDelivering()
    }

    private fun podFinishesWithoutTrouble(requested: Double) = podFinishes(requested, remaining = 0.0)

    private fun podFinishes(requested: Double, remaining: Double) {
        podDeliveryStatus = DeliveryStatus.BASAL_ACTIVE
        podLastBolus = lastBolus(requested, remaining, complete = true)
    }

    private fun podStillDelivering(requested: Double, remaining: Double) {
        podDeliveryStatus = DeliveryStatus.BOLUS_AND_BASAL_ACTIVE
        podLastBolus = lastBolus(requested, remaining, complete = false)
    }

    /** The first status request finds the bolus still running, the second one finds it done. */
    private fun podStillDeliveringOnFirstCheck(requested: Double, remaining: Double) {
        var calls = 0
        doAnswer {
            calls++
            if (calls == 1) podStillDelivering(requested, remaining) else podFinishesWithoutTrouble(requested)
            Observable.empty<PodEvent>()
        }.whenever(omnipodManager).getStatus(any())
    }

    /** A stop request ends the bolus with [remaining] units not given. */
    private fun podStopsAt(remaining: Double) {
        doAnswer {
            val requested = podLastBolus?.requestedUnits ?: 0.0
            podFinishes(requested, remaining)
            Observable.empty<PodEvent>()
        }.whenever(omnipodManager).stopBolus(any())
    }

    private fun podStopsBolus() {
        val bolus = podLastBolus ?: return
        podFinishes(bolus.requestedUnits, bolus.bolusUnitsRemaining)
    }

    private fun lastBolus(requested: Double, remaining: Double, complete: Boolean) = OmnipodDashPodStateManager.LastBolus(
        startTime = 0L, requestedUnits = requested, bolusUnitsRemaining = remaining, deliveryComplete = complete,
        historyId = BOLUS_HISTORY_ID, bolusType = BolusType.SMB
    )

    private fun activeCommand() = OmnipodDashPodStateManager.ActiveCommand(
        sequence = 1, createdRealtime = 0L, historyId = BOLUS_HISTORY_ID, sendError = null, basalProgram = null, tempBasal = null, requestedBolus = null
    )

    private fun elapsedMs(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

    private fun hundredths(units: Double): Int = (units * 100).roundToInt()

    private companion object {

        const val CHECKING = "Checking delivery status"
        const val EPSILON = 1e-6
        const val BOLUS_HISTORY_ID = 7L
        const val CANCEL_HISTORY_ID = 8L
    }
}
