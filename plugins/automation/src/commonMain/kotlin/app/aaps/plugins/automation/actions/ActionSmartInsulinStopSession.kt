package app.aaps.plugins.automation.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsScore
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.smartInsulin.SmartInsulinSessions
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.plugins.automation.AutomationStrings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Stops the running SmartInsulin activity session, for example when the user leaves the golf course. */
class ActionSmartInsulinStopSession(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    pumpEnactResultProvider: () -> PumpEnactResult,
    private val smartInsulinSessions: SmartInsulinSessions
) : Action(aapsLogger, rh, pumpEnactResultProvider) {

    override fun friendlyName(): TextRef = AutomationStrings.si_session_stop_action_name
    override fun shortDescription(): String = rh.gs(AutomationStrings.si_session_stop_action_short)
    override fun composeIcon() = Icons.Filled.SportsScore
    override fun elementType() = ElementType.EXERCISE

    override fun isValid(): Boolean = true
    override fun hasDialog(): Boolean = false

    override suspend fun doAction(): PumpEnactResult =
        if (smartInsulinSessions.stopSession()) pumpEnactResultProvider().success(true).comment(CoreUiStrings.ok)
        else pumpEnactResultProvider().success(false).comment(AutomationStrings.si_not_active)

    override fun toJSON(): String =
        buildJsonObject {
            put("type", this@ActionSmartInsulinStopSession::class.simpleName)
            put("data", buildJsonObject { })
        }.toString()

    override fun fromJSON(data: String): Action = this
}
