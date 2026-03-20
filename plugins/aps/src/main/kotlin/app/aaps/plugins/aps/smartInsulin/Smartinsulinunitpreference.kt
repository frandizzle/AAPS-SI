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
 * Unit-aware preference for SmartInsulin.
 *
 * Stores mg/dL always. Displays using fromMgdlToUnits() — no < 36 magnitude
 * heuristic that breaks small values like ISF (18 mg/dL/U) or activity targets (9 mg/dL).
 *
 * Dependencies passed directly — no Dagger registration needed.
 */
class SmartInsulinUnitPreference(
    ctx: Context,
    val unitKey: UnitDoublePreferenceKey,
    private val profileUtil: ProfileUtil,
    private val preferences: Preferences,
    @StringRes private val dialogMessage: Int? = null,
    @StringRes title: Int?,
) : EditTextPreference(ctx) {

    init {
        key = unitKey.key
        dialogMessage?.let { setDialogMessage(it) }
        title?.let { dialogTitle = ctx.getString(it) }
        title?.let { this.title = ctx.getString(it) }

        if (preferences.simpleMode && unitKey.defaultedBySM) isVisible = false
        if (preferences.apsMode && !unitKey.showInApsMode) { isVisible = false; isEnabled = false }
        if (preferences.nsclientMode && !unitKey.showInNsClientMode) { isVisible = false; isEnabled = false }
        if (preferences.pumpControlMode && !unitKey.showInPumpControlMode) { isVisible = false; isEnabled = false }
        unitKey.dependency?.let { if (!preferences.get(it)) isVisible = false }
        unitKey.negativeDependency?.let { if (preferences.get(it)) isVisible = false }

        setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            // Show current display value in dialog input
            editText.setText(currentDisplayString())
            editText.setSelection(editText.length())
        }
        // Update summary to show current converted value
        updateSummary()
    }

    /** Converts stored mg/dL → current display units, no heuristic. */
    private fun currentDisplayValue(): Double {
        val storedMgdl = preferences.get(unitKey)
        return profileUtil.fromMgdlToUnits(storedMgdl, profileUtil.units)
    }

    private fun currentDisplayString(): String {
        val precision = if (profileUtil.units == GlucoseUnit.MGDL) 1 else 2
        return BigDecimal(currentDisplayValue())
            .setScale(precision, RoundingMode.HALF_UP)
            .toPlainString()
    }

    private fun updateSummary() {
        summary = currentDisplayString()
    }

    override fun onAttached() {
        super.onAttached()
        if (unitKey.hideParentScreenIfHidden) {
            parent?.isVisible = isVisible
            parent?.isEnabled = isEnabled
        }
        updateSummary()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false
        updateSummary()
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        // Do NOT call super or setText here — that would trigger persistString
        // with a stale display value. Summary is updated in onAttached/onBindViewHolder.
        updateSummary()
    }

    override fun persistString(value: String?): Boolean {
        // value is what the user typed in display units
        val numericValue = SafeParse.stringToDouble(value, unitKey.defaultValue)
        // Convert display units → mg/dL for storage
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