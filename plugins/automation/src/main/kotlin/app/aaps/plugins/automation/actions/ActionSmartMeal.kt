package app.aaps.plugins.automation.actions

import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.InputDropdownMenuTyped
import app.aaps.plugins.automation.elements.InputDropdownOnOffMenu
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import javax.inject.Inject

class ActionSmartMeal(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var mealOverrideManager: MealOverrideManager
    @Inject lateinit var profileFunction:     ProfileFunction
    @Inject lateinit var iobCobCalculator:    IobCobCalculator
    @Inject lateinit var preferences:         Preferences

    var mealModeInput = InputDropdownMenuTyped(rh, MealMode.entries.toList(), { it.label }, MealMode.LUNCH)
    var prebolusInput = InputDropdownOnOffMenu(rh, true)  // default on; safety handled by calculateDose + onLoopCycle

    override fun friendlyName(): Int        = R.string.smart_meal_action_name
    override fun shortDescription(): String = rh.gs(R.string.smart_meal_action_short, mealModeInput.value.label)
    @DrawableRes override fun icon(): Int   = app.aaps.core.ui.R.drawable.ic_generic_icon
    override fun isValid(): Boolean         = true
    override fun hasDialog(): Boolean       = true

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(LabelWithElement(rh, rh.gs(R.string.smart_meal_select_mode), "", mealModeInput))
            .add(LabelWithElement(rh, rh.gs(R.string.smart_meal_prebolus),    "", prebolusInput))
            .build(root)
    }

    override fun doAction(callback: Callback) {
        val mode         = mealModeInput.value
        val wantPrebolus = prebolusInput.value
        val modeWindowMs = preferences.get(IntKey.ApsSmartInsulinModeWindowMins).toLong() * 60_000L
        val doseU        = if (wantPrebolus) preferences.get(DoubleKey.ApsSmartInsulinMaxPreBolus) else null

        aapsLogger.debug(LTag.APS,
                         "SmartMeal: mode=${mode.label} prebolus=$wantPrebolus dose=$doseU")

        mealOverrideManager.activateOverride(
            mode         = mode,
            doseU        = doseU,
            carbsG       = if (wantPrebolus) carbsForMode(mode) else 0,
            modeWindowMs = modeWindowMs
        )

        callback.result(pumpEnactResultProvider.get().success(true).comment(app.aaps.core.ui.R.string.ok)).run()
    }

    private fun calculateDose(mode: MealMode, carbsG: Int): Double? {
        val profile = profileFunction.getProfile() ?: run {
            aapsLogger.error(LTag.APS, "SmartMeal: no active profile"); return null
        }
        val lastBg = iobCobCalculator.ads.lastBg() ?: run {
            aapsLogger.error(LTag.APS, "SmartMeal: no BG reading"); return null
        }

        val bg     = lastBg.value
        val target = profile.getTargetMgdl()
        val ic     = profile.getIc()
        val modeIsfMmol = modeIsfForMode(mode)
        val isf    = if (modeIsfMmol > 0.0) modeIsfMmol * 18.0 else profile.getIsfMgdl(caller = "SmartMeal")
        val iob    = iobCobCalculator.calculateIobFromBolus().iob

        if (bg < MealOverrideManager.MIN_BG_MGDL) {
            aapsLogger.debug(LTag.APS, "SmartMeal: BG $bg below floor — skipping pre-bolus")
            return null
        }

        val carbDose  = if (ic > 0) carbsG / ic else 0.0
        val corr      = if (bg > target) (bg - target) / isf else 0.0
        val raw       = carbDose + corr - iob
        val maxBolus  = preferences.get(DoubleKey.ApsSmartInsulinMaxPreBolus)
        val clamped   = raw.coerceIn(0.0, maxBolus)

        aapsLogger.debug(LTag.APS,
                         "SmartMeal dose: bg=$bg tgt=$target ic=$ic isf=$isf iob=$iob " +
                             "carb=$carbDose corr=$corr raw=$raw →$clamped")
        return clamped
    }

    private fun carbsForMode(mode: MealMode): Int = when (mode) {
        MealMode.BREAKFAST -> preferences.get(IntKey.ApsSmartInsulinBreakfastCarbsG)
        MealMode.LUNCH     -> preferences.get(IntKey.ApsSmartInsulinLunchCarbsG)
        MealMode.DINNER    -> preferences.get(IntKey.ApsSmartInsulinDinnerCarbsG)
        MealMode.LOW_CARB  -> preferences.get(IntKey.ApsSmartInsulinLowCarbCarbsG)
        MealMode.EXTENDED  -> preferences.get(IntKey.ApsSmartInsulinExtendedCarbsG)
        MealMode.FASTING   -> 0
    }

    private fun modeIsfForMode(mode: MealMode): Double = when (mode) {
        MealMode.BREAKFAST -> preferences.get(DoubleKey.ApsSmartInsulinBreakfastIsf)
        MealMode.LUNCH     -> preferences.get(DoubleKey.ApsSmartInsulinLunchIsf)
        MealMode.DINNER    -> preferences.get(DoubleKey.ApsSmartInsulinDinnerIsf)
        MealMode.LOW_CARB  -> preferences.get(DoubleKey.ApsSmartInsulinLowCarbIsf)
        MealMode.EXTENDED  -> preferences.get(DoubleKey.ApsSmartInsulinExtendedIsf)
        MealMode.FASTING   -> 1.0
    }

    override fun toJSON(): String = JSONObject()
        .put("type", javaClass.simpleName)
        .put("data", JSONObject()
            .put("mealMode", mealModeInput.value.name)
            .put("prebolus", prebolusInput.value))
        .toString()

    override fun fromJSON(data: String): Action {
        val o = JSONObject(data)
        mealModeInput.value = MealMode.valueOf(JsonHelper.safeGetString(o, "mealMode", MealMode.LUNCH.name))
        prebolusInput.value = JsonHelper.safeGetBoolean(o, "prebolus", false)
        return this
    }
}