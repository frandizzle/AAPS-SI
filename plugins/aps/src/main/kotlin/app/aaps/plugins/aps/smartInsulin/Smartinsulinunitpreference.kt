package app.aaps.plugins.aps.smartInsulin

import android.content.Context
import android.text.InputType
import androidx.annotation.StringRes
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceViewHolder
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.UnitDoublePreferenceKey
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Unit-aware preference for SmartInsulin that correctly handles values < 36 mg/dL.
 *
 * The stock AdaptiveUnitPreference uses valueInCurrentUnitsDetect() which treats
 * values < 36 as mmol — misidentifying small mg/dL values like ISF overrides
 * (18 mg/dL/U) or activity targets (9 mg/dL).
 *
 * This class uses fromMgdlToUnits() — explicit conversion, no magnitude heuristic.
 * Dependencies passed directly to avoid Dagger registration requirement.
 */
class SmartInsulinUnitPreference(
    ctx: Context,
    val unitKey: UnitDoublePreferenceKey,
    private val profileUtil: ProfileUtil,
    private val preferences: Preferences,
    @StringRes dialogMessage: Int? = null,
    @StringRes title: Int?,
) : EditTextPreference(ctx) {

    private var displayValue: BigDecimal

    init {
        key = unitKey.key
        dialogMessage?.let { setDialogMessage(it) }
        title?.let { dialogTitle = ctx.getString(it) }
        title?.let { this.title = ctx.getString(it) }

        // Explicit conversion — fromMgdlToUnits has no < 36 heuristic
        val storedMgdl = preferences.get(unitKey)
        val converted = profileUtil.fromMgdlToUnits(storedMgdl, profileUtil.units)
        val precision = if (profileUtil.units == GlucoseUnit.MGDL) 1 else 2
        displayValue = BigDecimal(converted).setScale(precision, RoundingMode.HALF_UP)
        summary = displayValue.toPlainString()

        if (preferences.simpleMode && unitKey.defaultedBySM) isVisible = false
        if (preferences.apsMode && !unitKey.showInApsMode) { isVisible = false; isEnabled = false }
        if (preferences.nsclientMode && !unitKey.showInNsClientMode) { isVisible = false; isEnabled = false }
        if (preferences.pumpControlMode && !unitKey.showInPumpControlMode) { isVisible = false; isEnabled = false }
        unitKey.dependency?.let { if (!preferences.get(it)) isVisible = false }
        unitKey.negativeDependency?.let { if (preferences.get(it)) isVisible = false }

        setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            editText.setSelection(editText.length())
        }
        setDefaultValue(unitKey.defaultValue)
    }

    override fun onAttached() {
        super.onAttached()
        if (unitKey.hideParentScreenIfHidden) {
            parent?.isVisible = isVisible
            parent?.isEnabled = isEnabled
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        text = displayValue.toPlainString()
    }

    override fun persistString(value: String?): Boolean {
        val numericValue = SafeParse.stringToDouble(value, unitKey.defaultValue)
        val storeMgdl = profileUtil.convertToMgdl(numericValue, profileUtil.units)
        val precision = if (profileUtil.units == GlucoseUnit.MGDL) 1 else 2
        summary = BigDecimal(numericValue).setScale(precision, RoundingMode.HALF_UP).toPlainString()
        return try {
            super.persistFloat(storeMgdl.toFloat())
        } catch (_: Exception) {
            super.persistString(storeMgdl.toString())
        }
    }
}