package app.aaps.plugins.aps.smartInsulin

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import androidx.annotation.StringRes
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceViewHolder
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.UnitDoublePreferenceKey
import dagger.android.HasAndroidInjector
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

/**
 * Unit-aware preference for SmartInsulin that correctly handles values < 36 mg/dL.
 *
 * The stock AdaptiveUnitPreference uses valueInCurrentUnitsDetect() which has a
 * broken heuristic: values < 36 are assumed to be mmol/L. This misidentifies
 * small mg/dL values like ISF overrides (e.g. 18 mg/dL/U) or activity targets
 * (9 mg/dL) as mmol, then multiplies by 18, showing 162/324 etc.
 *
 * This class uses fromMgdlToUnits() instead — explicit conversion based on the
 * actual current unit setting, no magnitude-based guessing.
 *
 * Storage: always mg/dL (same as AdaptiveUnitPreference / AdaptiveUnitPreference).
 * Display: converts using profileUtil.fromMgdlToUnits() — no < 36 heuristic.
 */
class SmartInsulinUnitPreference(
    ctx: Context,
    attrs: AttributeSet? = null,
    private val unitKey: UnitDoublePreferenceKey? = null,
    @StringRes dialogMessage: Int? = null,
    @StringRes title: Int?,
) : EditTextPreference(ctx, attrs) {

    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var preferences: Preferences

    private val preferenceKey: UnitDoublePreferenceKey
    private var displayValue: BigDecimal

    init {
        (context.applicationContext as HasAndroidInjector).androidInjector().inject(this)

        unitKey?.let { key = it.key }
        dialogMessage?.let { setDialogMessage(it) }
        title?.let { dialogTitle = context.getString(it) }
        title?.let { this.title = context.getString(it) }

        preferenceKey = unitKey ?: preferences.get(key) as UnitDoublePreferenceKey

        // Explicit conversion — no < 36 heuristic
        val storedMgdl = preferences.get(preferenceKey)
        val converted = profileUtil.fromMgdlToUnits(storedMgdl, profileUtil.units)
        val precision = if (profileUtil.units == GlucoseUnit.MGDL) 1 else 2
        displayValue = BigDecimal(converted).setScale(precision, RoundingMode.HALF_UP)
        summary = displayValue.toPlainString()

        if (preferences.simpleMode && preferenceKey.defaultedBySM) isVisible = false
        if (preferences.apsMode && !preferenceKey.showInApsMode) {
            isVisible = false; isEnabled = false
        }
        if (preferences.nsclientMode && !preferenceKey.showInNsClientMode) {
            isVisible = false; isEnabled = false
        }
        if (preferences.pumpControlMode && !preferenceKey.showInPumpControlMode) {
            isVisible = false; isEnabled = false
        }
        preferenceKey.dependency?.let { if (!preferences.get(it)) isVisible = false }
        preferenceKey.negativeDependency?.let { if (preferences.get(it)) isVisible = false }

        setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            editText.setSelection(editText.length())
        }
        setDefaultValue(preferenceKey.defaultValue)
    }

    override fun onAttached() {
        super.onAttached()
        if (preferenceKey.hideParentScreenIfHidden) {
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
        val numericValue = SafeParse.stringToDouble(value, preferenceKey.defaultValue)
        // Convert user-entered display value → mg/dL for storage
        val storeMgdl = profileUtil.convertToMgdl(numericValue, profileUtil.units)
        // Update summary with display value
        val precision = if (profileUtil.units == GlucoseUnit.MGDL) 1 else 2
        summary = BigDecimal(numericValue).setScale(precision, RoundingMode.HALF_UP).toPlainString()
        return try {
            super.persistFloat(storeMgdl.toFloat())
        } catch (_: Exception) {
            super.persistString(storeMgdl.toString())
        }
    }
}