package app.aaps.plugins.automation.actions

import app.aaps.core.interfaces.smartInsulin.SmartInsulinSessions
import app.aaps.plugins.automation.AutomationStrings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.skyscreamer.jsonassert.JSONAssert

class ActionSmartInsulinSessionTest : ActionsTestBase() {

    private val sessions: SmartInsulinSessions = mock()
    private lateinit var start: ActionSmartInsulinStartSession
    private lateinit var stop: ActionSmartInsulinStopSession

    @BeforeEach fun setUp() {
        start = ActionSmartInsulinStartSession(aapsLogger, text, { pumpEnactResultProvider() }, sessions)
        stop = ActionSmartInsulinStopSession(aapsLogger, text, { pumpEnactResultProvider() }, sessions)
    }

    @Test fun `start defaults to golf and says so`() {
        assertThat(start.kind).isEqualTo(SmartInsulinSessions.Kind.GOLF)
        assertThat(start.friendlyName()).isEqualTo(AutomationStrings.si_session_start_action_name)
        assertThat(start.shortDescription()).isEqualTo("Start Golf session")
        assertThat(start.hasDialog()).isTrue()
    }

    @Test fun `start asks SmartInsulin for the chosen session`() = runTest {
        start.kind = SmartInsulinSessions.Kind.GYM
        whenever(sessions.startSession(SmartInsulinSessions.Kind.GYM)).thenReturn(true)
        val result = start.doAction()
        verify(sessions).startSession(SmartInsulinSessions.Kind.GYM)
        assertThat(result.success).isTrue()
    }

    @Test fun `start fails when SmartInsulin is not the active APS`() = runTest {
        whenever(sessions.startSession(SmartInsulinSessions.Kind.GOLF)).thenReturn(false)
        val result = start.doAction()
        assertThat(result.success).isFalse()
        assertThat(result.comment).isEqualTo("SmartInsulin is not the active APS")
    }

    @Test fun `start keeps the chosen session through save and load`() {
        start.kind = SmartInsulinSessions.Kind.GYM
        JSONAssert.assertEquals("""{"data":{"session":"GYM"},"type":"ActionSmartInsulinStartSession"}""", start.toJSON(), true)
        val loaded = ActionSmartInsulinStartSession(aapsLogger, text, { pumpEnactResultProvider() }, sessions)
            .fromJSON("""{"session":"GYM"}""") as ActionSmartInsulinStartSession
        assertThat(loaded.kind).isEqualTo(SmartInsulinSessions.Kind.GYM)
    }

    @Test fun `start loads an unknown session as golf`() {
        val loaded = start.fromJSON("""{"session":"TENNIS"}""") as ActionSmartInsulinStartSession
        assertThat(loaded.kind).isEqualTo(SmartInsulinSessions.Kind.GOLF)
    }

    @Test fun `stop asks SmartInsulin to stop`() = runTest {
        whenever(sessions.stopSession()).thenReturn(true)
        val result = stop.doAction()
        verify(sessions).stopSession()
        assertThat(result.success).isTrue()
        assertThat(stop.shortDescription()).isEqualTo("Stop the running activity session")
        assertThat(stop.hasDialog()).isFalse()
    }

    @Test fun `stop fails when SmartInsulin is not the active APS`() = runTest {
        whenever(sessions.stopSession()).thenReturn(false)
        assertThat(stop.doAction().success).isFalse()
    }

    @Test fun `the factory builds both from their stored type`() {
        assertThat(actionFactory.instantiate("ActionSmartInsulinStartSession")).isInstanceOf(ActionSmartInsulinStartSession::class.java)
        assertThat(actionFactory.instantiate("ActionSmartInsulinStopSession")).isInstanceOf(ActionSmartInsulinStopSession::class.java)
    }
}
