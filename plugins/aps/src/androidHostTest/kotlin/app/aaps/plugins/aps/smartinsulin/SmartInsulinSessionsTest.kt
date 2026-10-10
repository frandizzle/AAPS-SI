package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.interfaces.smartInsulin.SmartInsulinSessions
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import app.aaps.plugins.aps.smartInsulin.testutil.FakePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever

/** [SmartInsulinPlugin] as [SmartInsulinSessions]: what the automation start and stop actions call. */
class SmartInsulinSessionsTest {

    private val sp = FakePreferences()
    private val dateUtil: DateUtil = mock()
    private val mealOverrideManager: MealOverrideManager = mock()
    private val manager = ActivitySessionManager(sp, FakeAAPSLogger(collect = false), ActivitySessionLearner(sp, FakeAAPSLogger(collect = false)))
    private lateinit var sut: SmartInsulinPlugin

    private val T0 = 1_700_000_000_000L

    @BeforeEach
    fun setup() {
        whenever(dateUtil.now()).thenReturn(T0)
        val plugin = SmartInsulinPlugin(
            aapsLogger            = mock(),
            rh                    = mock(),
            rxBus                 = mock(),
            config                = mock(),
            profileFunction       = mock(),
            profileUtil           = mock(),
            iobCobCalculator      = mock(),
            mealOverrideManager   = mealOverrideManager,
            glucoseStatusProvider = mock(),
            glucoseStatusCalculatorSMB = mock(),
            persistenceLayer      = mock(),
            processedTbrEbData    = mock(),
            hardLimits            = mock(),
            sp                    = sp,
            constraintsChecker    = mock(),
            activePlugin          = mock(),
            dateUtil              = dateUtil,
            determineBasalSmartInsulin = mock(),
            stftController        = mock(),
            uamController         = mock(),
            profileLearner        = mock(),
            bolusCurveTracker     = mock(),
            aggressionLearner     = mock(),
            basalLearner          = mock(),
            circadianLearner      = mock(),
            activityMonitor       = mock(),
            cgmWarmupGuard        = mock(),
            duraIsfTracker        = mock(),
            mealAbsorptionTracker = mock(),
            mealAbsorptionCsvLogger = mock(),
            modeIsfLearner        = mock(),
            uamEntryFractionLearner = mock(),
            unexplainedDropTracker  = mock(),
            duraStrengthLearner     = mock(),
            secondWaveDetector      = mock(),
            carbEpisodeManager      = mock(),
            activitySessionManager  = manager,
            activitySessionLearner  = mock(),
            phoneStepCounter        = mock(),
            preferences             = mock(),
            notificationManager     = mock(),
            ch                      = mock(),
            learningJournal         = mock()
        )
        sut = spy(plugin)
        doReturn(true).whenever(sut).isEnabled()
    }

    @Test
    fun `every session kind is a SmartInsulin session label`() {
        SmartInsulinSessions.Kind.entries.forEach { assertEquals(it.name, SessionLabel.of(it.name)?.name) }
    }

    @Test
    fun `start begins the chosen session`() {
        assertTrue(sut.startSession(SmartInsulinSessions.Kind.GOLF))
        assertEquals(SessionLabel.GOLF, manager.active?.label)
    }

    @Test
    fun `starting golf again leaves the round running from its first start`() {
        sut.startSession(SmartInsulinSessions.Kind.GOLF)
        whenever(dateUtil.now()).thenReturn(T0 + 30 * 60_000L)
        assertTrue(sut.startSession(SmartInsulinSessions.Kind.GOLF))
        assertEquals(SessionLabel.GOLF, manager.active?.label)
        assertEquals(T0, manager.active?.startMs)
    }

    @Test
    fun `starting golf while gym runs switches to golf`() {
        sut.startSession(SmartInsulinSessions.Kind.GYM)
        sut.startSession(SmartInsulinSessions.Kind.GOLF)
        assertEquals(SessionLabel.GOLF, manager.active?.label)
    }

    @Test
    fun `stop ends the running session, and is fine with none running`() {
        sut.startSession(SmartInsulinSessions.Kind.GOLF)
        assertTrue(sut.stopSession())
        assertNull(manager.active)
        assertTrue(sut.stopSession())
    }

    @Test
    fun `nothing happens when SmartInsulin is not the active APS`() {
        doReturn(false).whenever(sut).isEnabled()
        assertFalse(sut.startSession(SmartInsulinSessions.Kind.GOLF))
        assertNull(manager.active)
        assertFalse(sut.stopSession())
    }
}
