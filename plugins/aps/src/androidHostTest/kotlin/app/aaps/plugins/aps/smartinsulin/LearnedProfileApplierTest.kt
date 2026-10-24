package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.PS
import app.aaps.core.data.model.data.Block
import app.aaps.core.data.model.data.TargetBlock
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.core.interfaces.profile.ProfileStore
import app.aaps.core.interfaces.profile.PureProfile
import app.aaps.core.interfaces.profile.SingleProfile
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Saving the learned profile, switching to it, and moving the learning only when a profile this class
 * made (or the one before it) comes into force.
 */
class LearnedProfileApplierTest {

    private val day = 24 * 3_600_000L
    private var now = 1_790_000_000_000L

    private val sp = FakePreferences()
    private val profileFunction: ProfileFunction = mock()
    private val profileRepository: ProfileRepository = mock()
    private val store: ProfileStore = mock()
    private val dateUtil: DateUtil = mock()
    private val running: PS = mock()

    // The profile in force now: ISF 2.0 mmol/U (36 mg/dL), basal 1.1 U/h.
    private val mainIsf = DoubleArray(24) { 36.0 }
    private val mainBasal = DoubleArray(24) { 1.1 }

    private lateinit var applier: LearnedProfileApplier

    private fun newApplier() = LearnedProfileApplier(FakeAAPSLogger(collect = false), sp, profileFunction, profileRepository, dateUtil, CoroutineScope(Dispatchers.Unconfined))

    @BeforeEach
    fun setUp() = runBlocking {
        whenever(dateUtil.now()).thenAnswer { now }
        whenever(running.profileName).thenReturn("Main")
        whenever(running.percentage).thenReturn(100)
        whenever(running.timeshift).thenReturn(0L)
        whenever(running.duration).thenReturn(0L)
        whenever(profileFunction.getRequestedProfile()).thenReturn(running)
        whenever(profileFunction.getRunningOrRequestedICfg()).thenReturn(mock<ICfg>())
        whenever(profileRepository.profile).thenReturn(MutableStateFlow(store))
        val base: PureProfile = mock()
        whenever(store.getSpecificProfile("Main")).thenReturn(base)
        whenever(profileRepository.copyFrom(any(), any())).thenAnswer { inv ->
            SingleProfile(
                name = inv.getArgument(1), mgdl = false, ic = listOf(Block(day, 10.0)), isf = listOf(Block(day, 2.0)),
                basal = listOf(Block(day, 1.1)), target = listOf(TargetBlock(day, 5.5, 5.5))
            )
        }
        whenever(profileRepository.validateStructured(any())).thenReturn(emptyList())
        whenever(profileRepository.validatePumpCompatibility(any(), eq(100))).thenReturn(emptyList())
        whenever(profileRepository.add(any())).thenReturn(Result.success(Unit))
        whenever(
            profileFunction.createProfileSwitch(
                any(), any(), any(), any(), any(), any(), any(), any(), anyOrNull(), any(), any()
            )
        ).thenReturn(running)
        applier = newApplier()
    }

    private fun plan(isfMult: Double = 0.7, basalMult: Double = 0.55) = LearnedProfilePlan.build(
        (0 until 24).map { h -> LearnedProfilePlan.Hour(h, 36.0, 1.1, 36.0 / isfMult, 1.1 * basalMult, 1.0, 1.0) },
        isfStepMgdl = 1.8, basalStep = 0.05, basalMin = 0.05
    )

    @Test
    fun `nothing moves before the first apply`() {
        assertNull(applier.onProfileInForce(DoubleArray(24) { 50.0 }, DoubleArray(24) { 0.6 }))
    }

    @Test
    fun `apply saves a mmol profile with the learned values and switches to it`() = runBlocking {
        assertNull(applier.apply(plan(), mainIsf, mainBasal))
        verify(profileRepository).add(any())
        assertTrue(applier.appliedName!!.startsWith("SI learned"))
        assertEquals("Main", applier.previousName)
        // The pump has not taken it yet.
        assertTrue(applier.switchPending(mainIsf, mainBasal))
    }

    @Test
    fun `the learning moves when the new profile is in force, and dosing stays the same`() = runBlocking {
        applier.apply(plan(), mainIsf, mainBasal)
        // 2.9 mmol/U = 52.2 mg/dL; basal 0.6 U/h.
        val newIsf = DoubleArray(24) { 52.2 }
        val newBasal = DoubleArray(24) { 0.6 }
        val r = applier.onProfileInForce(newIsf, newBasal)
        assertNotNull(r)
        assertEquals(52.2 / 36.0, r!!.isfRatio[0], 1e-9)
        assertEquals(0.6 / 1.1, r.basalRatio[0], 1e-9)
        assertFalse(applier.switchPending(newIsf, newBasal))
        // Only once.
        assertNull(applier.onProfileInForce(newIsf, newBasal))
    }

    @Test
    fun `a percentage switch on top does not move the learning`() = runBlocking {
        applier.apply(plan(), mainIsf, mainBasal)
        applier.onProfileInForce(DoubleArray(24) { 52.2 }, DoubleArray(24) { 0.6 })
        // The user runs 80 % for exercise: the change is meant to be felt.
        assertNull(applier.onProfileInForce(DoubleArray(24) { 52.2 / 0.8 }, DoubleArray(24) { 0.48 }))
        // And back to the learned profile: nothing to move, the learning never left it.
        assertNull(applier.onProfileInForce(DoubleArray(24) { 52.2 }, DoubleArray(24) { 0.6 }))
    }

    @Test
    fun `switching back to the old profile by hand moves the learning back`() = runBlocking {
        applier.apply(plan(), mainIsf, mainBasal)
        applier.onProfileInForce(DoubleArray(24) { 52.2 }, DoubleArray(24) { 0.6 })
        val r = applier.onProfileInForce(mainIsf, mainBasal)
        assertNotNull(r)
        assertEquals(36.0 / 52.2, r!!.isfRatio[0], 1e-9)
        assertEquals(1.1 / 0.6, r.basalRatio[0], 1e-9)
    }

    @Test
    fun `a percentage or temporary switch is refused`() = runBlocking<Unit> {
        whenever(running.percentage).thenReturn(80)
        assertNotNull(applier.apply(plan(), mainIsf, mainBasal))
        whenever(running.percentage).thenReturn(100)
        whenever(running.duration).thenReturn(60 * 60_000L)
        assertNotNull(applier.apply(plan(), mainIsf, mainBasal))
        verify(profileRepository, never()).add(any())
    }

    @Test
    fun `a second apply waits until the first has reached the pump, but not for ever`() = runBlocking {
        applier.apply(plan(), mainIsf, mainBasal)
        assertNotNull(applier.apply(plan(), mainIsf, mainBasal))
        now += 2 * 3_600_000L
        assertFalse(applier.switchPending(mainIsf, mainBasal))
    }

    @Test
    fun `undo goes back and the learning follows`() = runBlocking {
        applier.apply(plan(), mainIsf, mainBasal)
        val newIsf = DoubleArray(24) { 52.2 }
        val newBasal = DoubleArray(24) { 0.6 }
        applier.onProfileInForce(newIsf, newBasal)
        whenever(running.profileName).thenReturn(applier.appliedName)

        assertNull(applier.undo(newIsf, newBasal))
        assertNull(applier.previousName)
        val r = applier.onProfileInForce(mainIsf, mainBasal)
        assertEquals(36.0 / 52.2, r!!.isfRatio[3], 1e-9)
    }

    @Test
    fun `the state survives a restart`() = runBlocking {
        applier.apply(plan(), mainIsf, mainBasal)
        val name = applier.appliedName
        val again = newApplier()
        assertEquals(name, again.appliedName)
        assertNotNull(again.onProfileInForce(DoubleArray(24) { 52.2 }, DoubleArray(24) { 0.6 }))
    }

    @Test
    fun `the automatic change runs at most once a week and is tried at most once a day`() = runBlocking {
        assertTrue(applier.autoAllowed(now))
        applier.apply(plan(), mainIsf, mainBasal)
        assertFalse(applier.autoAllowed(now + 6 * day))
        assertTrue(applier.autoAllowed(now + 7 * day))
    }
}
