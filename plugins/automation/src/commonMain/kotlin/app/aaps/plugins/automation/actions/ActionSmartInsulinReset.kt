package app.aaps.plugins.automation.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.smartInsulin.SmartInsulinLearner
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.plugins.automation.AutomationStrings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Resets SmartInsulin's learned insulin DIA and peak back to the configured insulin's defaults. */
class ActionSmartInsulinReset(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    pumpEnactResultProvider: () -> PumpEnactResult,
    private val smartInsulinLearner: SmartInsulinLearner
) : Action(aapsLogger, rh, pumpEnactResultProvider) {

    override fun friendlyName(): TextRef = AutomationStrings.si_reset_action_name
    override fun shortDescription(): String = rh.gs(AutomationStrings.si_reset_action_short)
    override fun composeIcon() = Icons.Filled.Refresh
    override fun elementType() = ElementType.PROFILE_MANAGEMENT

    override fun isValid(): Boolean = true
    override fun hasDialog(): Boolean = false

    override suspend fun doAction(): PumpEnactResult {
        smartInsulinLearner.resetProfiles()
        return pumpEnactResultProvider().success(true).comment(CoreUiStrings.ok)
    }

    override fun toJSON(): String =
        buildJsonObject {
            put("type", this@ActionSmartInsulinReset::class.simpleName)
            put("data", buildJsonObject { })
        }.toString()

    override fun fromJSON(data: String): Action = this
}
