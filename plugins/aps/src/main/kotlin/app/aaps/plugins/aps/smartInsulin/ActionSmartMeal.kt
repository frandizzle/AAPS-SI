package app.aaps.plugins.automation.actions

import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.aps.smartInsulin.MealMode
import app.aaps.plugins.aps.smartInsulin.MealOverrideManager
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.InputDropdownMenu
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import javax.inject.Inject

/**
 * Automation Action — SmartInsulin Meal Override
 *
 * When triggered the action:
 *   1. Shows a dialog: meal type selector + pre-bolus toggle
 *   2. If pre-bolus requested: calculates dose at trigger time from
 *      locked carbs (per-meal pref) and current BG/CR/IOB
 *   3. Passes everything to [MealOverrideManager] which queues the bolus
 *      and fires it when safety conditions are met (BG rising, IOB safe, BG above floor)
 *
 * The action registers itself in [Action.instantiate] — add the entry there after adding this file.
 */
class ActionSmartMeal(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var mealOverrideManager: MealOverrideManager
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var preferences: Preferences

    // ── Dialog inputs ─────────────────────────────────────────────────────────

    /** Dropdown: which meal type */
    var mealModeInput: InputDropdownMenu<MealMode> = InputDropdownMenu(
        rh,
        MealMode.values().toList(),
        { it.label },
        MealMode.LUNCH
    )

    /** Dropdown: pre-bolus or mode-only */
    var prebolusInput: InputDropdownOnOffMenu = InputDropdownOnOffMenu(rh, false)

    // ── Action overrides ──────────────────────────────────────────────────────

    override fun friendlyName(): Int = R.string.smart_meal_action_name
    override fun shortDescription(): String =
        rh.gs(R.string.smart_meal_action_short, mealModeInput.value.label)
    @DrawableRes override fun icon(): Int = app.aaps.core.ui.R.drawable.ic_cp_bolus_meal
    override fun isValid(): Boolean = true
    override fun hasDialog(): Boolean = true

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(LabelWithElement(rh, rh.gs(R.string.smart_meal_select_mode),  "", mealModeInput))
            .add(LabelWithElement(rh, rh.gs(R.string.smart_meal_prebolus),     "", prebolusInput))
            .build(root)
    }

    // ── doAction — called when automation rule fires ───────────────────────────

    override fun doAction(callback: Callback) {
        val mode         = mealModeInput.value
        val wantPrebolus = prebolusInput.value

        // ── Read per-meal prefs ───────────────────────────────────────────────
        val carbsG = carbsForMode(mode)
        val modeWindowMs = preferences.get(IntKey.ApsSmartInsulinModeWindowMins).toLong() * 60_000L

        // ── Calculate bolus at trigger time ───────────────────────────────────
        val doseU: Double? = if (wantPrebolus) calculateDose(mode, carbsG) else null

        if (wantPrebolus) {
            if (doseU != null && doseU > 0.0) {
                aapsLogger.debug(LTag.APS,
                    "SmartMeal action: mode=${mode.label} preBolus=${doseU}U carbs=${carbsG}g " +
                    "modeWindow=${modeWindowMs / 60_000}min — queuing")
            } else {
                aapsLogger.debug(LTag.APS,
                    "SmartMeal action: pre-bolus blocked (dose=$doseU) — mode-only activation")
            }
        }

        mealOverrideManager.activateOverride(
            mode         = mode,
            doseU        = if (doseU != null && doseU > 0.0) doseU else null,
            carbsG       = carbsG,
            modeWindowMs = modeWindowMs
        )

        callback.result(
            pumpEnactResultProvider.get()
                .success(true)
                .comment(app.aaps.core.ui.R.string.ok)
        ).run()
    }

    // ── Dose calculation ──────────────────────────────────────────────────────

    /**
     * Calculate insulin dose at trigger time.
     *
     * Formula:  dose = (carbsG / IC) + correctionBolus - currentIOB
     *
     * Where correctionBolus = (bg - target) / ISF  (only positive correction added)
     * Result is clamped to [0, maxBolus].
     *
     * Returns null if profile is unavailable or BG is below safe floor.
     */
    private fun calculateDose(mode: MealMode, carbsG: Int): Double? {
        val profile = profileFunction.getProfile() ?: run {
            aapsLogger.error(LTag.APS, "SmartMeal: no active profile — cannot calculate dose")
            return null
        }

        val glucoseStatus: GlucoseStatus = activePlugin.activeBgSource
            .let { iobCobCalculator.ads.lastBg() }
            ?: run {
                aapsLogger.error(LTag.APS, "SmartMeal: no BG reading available")
                return null
            }

        val bg     = glucoseStatus.glucose
        val target = profile.getTargetMgdl()
        val ic     = profile.getIc()
        val isf    = profile.getIsfMgdl() * isfMultiplierForMode(mode)

        // Refuse if BG is below safe minimum (e.g. already low before meal)
        if (bg < MealOverrideManager.MIN_BG_MGDL_PUBLIC) {
            aapsLogger.debug(LTag.APS,
                "SmartMeal: BG $bg < floor — no pre-bolus calculated")
            return null
        }

        val iob = iobCobCalculator.calculateIobFromBolus().iob

        val carbDose       = if (ic > 0) carbsG / ic else 0.0
        val correctionDose = if (bg > target) (bg - target) / isf else 0.0
        val rawDose        = carbDose + correctionDose - iob

        val maxBolus = preferences.get(DoubleKey.ApsSmartInsulinMaxPreBolus)
        val clamped  = rawDose.coerceIn(0.0, maxBolus)

        aapsLogger.debug(LTag.APS,
            "SmartMeal dose calc: bg=$bg target=$target ic=$ic isf=$isf iob=$iob " +
            "carbDose=$carbDose correction=$correctionDose raw=$rawDose clamped=$clamped")

        return clamped
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun carbsForMode(mode: MealMode): Int = when (mode) {
        MealMode.BREAKFAST -> preferences.get(IntKey.ApsSmartInsulinBreakfastCarbsG)
        MealMode.LUNCH     -> preferences.get(IntKey.ApsSmartInsulinLunchCarbsG)
        MealMode.DINNER    -> preferences.get(IntKey.ApsSmartInsulinDinnerCarbsG)
        MealMode.LOW_CARB  -> preferences.get(IntKey.ApsSmartInsulinLowCarbCarbsG)
        MealMode.EXTENDED  -> preferences.get(IntKey.ApsSmartInsulinExtendedCarbsG)
        MealMode.FASTING   -> 0
    }

    private fun isfMultiplierForMode(mode: MealMode): Double = when (mode) {
        MealMode.BREAKFAST -> preferences.get(DoubleKey.ApsSmartInsulinBreakfastIsfMultiplier)
        MealMode.LUNCH     -> preferences.get(DoubleKey.ApsSmartInsulinLunchIsfMultiplier)
        MealMode.DINNER    -> preferences.get(DoubleKey.ApsSmartInsulinDinnerIsfMultiplier)
        MealMode.LOW_CARB  -> preferences.get(DoubleKey.ApsSmartInsulinLowCarbIsfMultiplier)
        MealMode.EXTENDED  -> preferences.get(DoubleKey.ApsSmartInsulinExtendedIsfMultiplier)
        MealMode.FASTING   -> 1.0
    }

    // ── JSON persistence ──────────────────────────────────────────────────────

    override fun toJSON(): String {
        val data = JSONObject()
            .put("mealMode",   mealModeInput.value.name)
            .put("prebolus",   prebolusInput.value)
        return JSONObject()
            .put("type", this.javaClass.simpleName)
            .put("data", data)
            .toString()
    }

    override fun fromJSON(data: String): Action {
        val o = JSONObject(data)
        mealModeInput.value = MealMode.valueOf(
            JsonHelper.safeGetString(o, "mealMode", MealMode.LUNCH.name)
        )
        prebolusInput.value = JsonHelper.safeGetBoolean(o, "prebolus", false)
        return this
    }
}
