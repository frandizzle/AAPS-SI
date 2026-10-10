package app.aaps.plugins.automation.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.smartInsulin.SmartInsulinSessions
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.utils.lenientString
import app.aaps.plugins.automation.AutomationStrings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Starts a SmartInsulin activity session (golf, gym), for example when the user gets to the golf
 * course. Unlike the button on the SI tab it never stops a session: if the same session is already
 * running nothing happens, so a location trigger that fires twice cannot end the round.
 */
class ActionSmartInsulinStartSession(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    pumpEnactResultProvider: () -> PumpEnactResult,
    private val smartInsulinSessions: SmartInsulinSessions
) : Action(aapsLogger, rh, pumpEnactResultProvider) {

    var kind: SmartInsulinSessions.Kind = SmartInsulinSessions.Kind.GOLF

    override fun friendlyName(): TextRef = AutomationStrings.si_session_start_action_name
    override fun shortDescription(): String = rh.gs(AutomationStrings.si_session_start_action_short, rh.gs(kindName(kind)))
    override fun composeIcon() = Icons.AutoMirrored.Filled.DirectionsRun
    override fun elementType() = ElementType.EXERCISE

    override fun isValid(): Boolean = true
    override fun hasDialog(): Boolean = true

    override suspend fun doAction(): PumpEnactResult =
        if (smartInsulinSessions.startSession(kind)) pumpEnactResultProvider().success(true).comment(CoreUiStrings.ok)
        else pumpEnactResultProvider().success(false).comment(AutomationStrings.si_not_active)

    override fun toJSON(): String =
        buildJsonObject {
            put("type", this@ActionSmartInsulinStartSession::class.simpleName)
            put("data", buildJsonObject { put("session", kind.name) })
        }.toString()

    override fun fromJSON(data: String): Action {
        val name = jsonOf(data).lenientString("session", SmartInsulinSessions.Kind.GOLF.name)
        kind = SmartInsulinSessions.Kind.entries.firstOrNull { it.name == name } ?: SmartInsulinSessions.Kind.GOLF
        return this
    }

    companion object {

        /** The name shown for a session, in the action and its editor. */
        fun kindName(kind: SmartInsulinSessions.Kind): TextRef = when (kind) {
            SmartInsulinSessions.Kind.GOLF -> AutomationStrings.si_session_golf
            SmartInsulinSessions.Kind.GYM  -> AutomationStrings.si_session_gym
        }
    }
}
