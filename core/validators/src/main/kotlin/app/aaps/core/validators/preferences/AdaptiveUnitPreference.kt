package app.aaps.core.validators.preferences

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
import app.aaps.core.validators.DefaultEditTextValidator
import app.aaps.core.validators.EditTextValidator
import app.aaps.core.validators.R
import dagger.android.HasAndroidInjector
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

class AdaptiveUnitPreference(
    ctx: Context,
    attrs: AttributeSet? = null,
    unitKey: UnitDoublePreferenceKey? = null,
    @StringRes dialogMessage: Int? = null,
    @StringRes title: Int?,
) : EditTextPreference(ctx, attrs) {

    private val validatorParameters: DefaultEditTextValidator.Parameters
    private var validator: DefaultEditTextValidator? = null
    private val preferenceKey: UnitDoublePreferenceKey

    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var preferences: Preferences

    // Inflater constructor
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, unitKey = null, title = null)

    // Flag: true while onSetInitialValue is running so persistString does not re-convert
    private var isInitializing = false

    init {
        (context.applicationContext as HasAndroidInjector).androidInjector().inject(this)

        unitKey?.let { key = it.key }
        dialogMessage?.let { setDialogMessage(it) }
        title?.let { dialogTitle = context.getString(it) }
        title?.let { this.title = context.getString(it) }

        preferenceKey = unitKey ?: preferences.get(key) as UnitDoublePreferenceKey

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
        preferenceKey.dependency?.let {
            if (!preferences.get(it))
                isVisible = false
        }
        preferenceKey.negativeDependency?.let {
            if (preferences.get(it))
                isVisible = false
        }
        validatorParameters = obtainValidatorParameters(attrs)
        setOnBindEditTextListener { editText ->
            validator = DefaultEditTextValidator(editText, validatorParameters, context)
            editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            editText.setSelection(editText.length())
        }
        setOnPreferenceChangeListener { _, _ -> validator?.testValidity(false) != false }
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

    private fun obtainValidatorParameters(attrs: AttributeSet?): DefaultEditTextValidator.Parameters {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.FormEditText, 0, 0)
        return DefaultEditTextValidator.Parameters(
            emptyAllowed = typedArray.getBoolean(R.styleable.FormEditText_emptyAllowed, false),
            testType = EditTextValidator.TEST_BG_RANGE,
            testErrorString = typedArray.getString(R.styleable.FormEditText_testErrorString),
            classType = typedArray.getString(R.styleable.FormEditText_classType),
            customRegexp = typedArray.getString(R.styleable.FormEditText_customRegexp),
            emptyErrorStringDef = typedArray.getString(R.styleable.FormEditText_emptyErrorString),
            customFormat = typedArray.getString(R.styleable.FormEditText_customFormat)
        ).also { params ->
            params.minMgdl = preferenceKey.minMgdl
            params.maxMgdl = preferenceKey.maxMgdl
            typedArray.recycle()
        }
    }

    // Read raw mg/dL from SharedPreferences directly — same pattern as AdaptiveDoublePreference.
    // We MUST NOT use preferences.get(UnitDoubleKey) here because PreferencesImpl applies
    // valueInCurrentUnitsDetect() which uses a <36 heuristic that misidentifies small mg/dL
    // values (e.g. 9, 18, 27 mg/dL activity targets) as mmol and multiplies by 18.
    //
    // Must handle both float and string storage formats: the previous AdaptiveUnitPreference
    // stored values via super.persistString(), so existing stored values are strings.
    // getPersistedFloat throws ClassCastException on string-stored values, falling back
    // to the default — which is why values snap back to default after upgrade.
    private fun rawStoredMgdl(): Double {
        // sp.putDouble() stores as a String in Android SharedPreferences.
        // This must match the write path in persistString() below (which calls sp.putDouble)
        // and in PreferencesImpl.put(UnitDoublePreferenceKey) (which also calls sp.putDouble).
        // Try string first (current format), then float (migration from old persistFloat format).
        return try {
            getPersistedString(null)?.toDoubleOrNull()
                ?: try {
                    getPersistedFloat(Float.MIN_VALUE).let { raw ->
                        if (raw == Float.MIN_VALUE) preferenceKey.defaultValue else raw.toDouble()
                    }
                } catch (_: Exception) {
                    preferenceKey.defaultValue
                }
        } catch (_: Exception) {
            preferenceKey.defaultValue
        }
    }

    // Precision derived from the key's maxMgdl:
    // - Normal BG range keys (max > 36): mg/dL → 0 decimals (65, 97), mmol → 1 decimal (3.6)
    // - Small-range delta/threshold keys (max <= 36): mg/dL → 1 decimal (2.9), mmol → 2 decimals (0.15)
    private fun displayScale(): Int =
        if (preferenceKey.maxMgdl <= 36) {
            if (profileUtil.units == GlucoseUnit.MMOL) 2 else 1
        } else {
            if (profileUtil.units == GlucoseUnit.MMOL) 1 else 0
        }

    private fun displayValue(): String {
        val storedMgdl = rawStoredMgdl()
        val display = profileUtil.fromMgdlToUnits(storedMgdl, profileUtil.units)
        return BigDecimal(display).setScale(displayScale(), RoundingMode.HALF_UP).toPlainString()
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        // Setting text triggers EditTextPreference.setText() → persistString().
        // Guard with isInitializing so persistString writes back the unchanged raw mg/dL
        // instead of re-applying convertToMgdl on the display-unit string.
        isInitializing = true
        try {
            text = displayValue()
            summary = text
        } finally {
            isInitializing = false
        }
    }

    override fun persistString(value: String?): Boolean {
        if (isInitializing) {
            // Called from onSetInitialValue — do NOT write back to storage.
            // The value is already correctly on disk; writing here risks overwriting
            // a freshly saved correct value with a stale rawStoredMgdl() read.
            return true
        }
        // User entered a new value — it is in display units, convert to mg/dL for storage.
        val numericValue = SafeParse.stringToDouble(value, preferenceKey.defaultValue)
        summary = BigDecimal(numericValue).setScale(displayScale(), RoundingMode.HALF_UP).toPlainString()
        val store = profileUtil.convertToMgdl(numericValue, profileUtil.units)
        // preferences.put(UnitDoublePreferenceKey) calls sp.putDouble — same path as
        // PreferencesImpl.put(UnitDoublePreferenceKey), matching sp.getDouble() in the plugin.
        preferences.put(preferenceKey, store)
        return true
    }
}