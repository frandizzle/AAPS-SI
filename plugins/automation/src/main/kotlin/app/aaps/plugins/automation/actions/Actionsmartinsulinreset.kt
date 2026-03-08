package app.aaps.plugins.automation.actions

import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import app.aaps.core.interfaces.queue.Callback
import app.aaps.plugins.aps.smartInsulin.ProfileLearner
import app.aaps.plugins.automation.R
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import javax.inject.Inject

class ActionSmartInsulinReset(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var profileLearner: ProfileLearner

    override fun friendlyName(): Int        = R.string.si_reset_action_name
    override fun shortDescription(): String = rh.gs(R.string.si_reset_action_short)
    @DrawableRes override fun icon(): Int   = app.aaps.core.ui.R.drawable.ic_generic_icon
    override fun isValid(): Boolean         = true
    override fun hasDialog(): Boolean       = false

    override fun generateDialog(root: LinearLayout) = Unit

    override fun doAction(callback: Callback) {
        profileLearner.resetProfiles()
        callback.result(pumpEnactResultProvider.get().success(true).comment(app.aaps.core.ui.R.string.ok)).run()
    }

    override fun toJSON(): String          = JSONObject().put("type", javaClass.simpleName).toString()
    override fun fromJSON(data: String): Action = this
}