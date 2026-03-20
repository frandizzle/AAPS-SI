package app.aaps.core.validators.preferences

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import androidx.annotation.StringRes
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceViewHolder
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
    private fun rawStoredMgdl(): Double =
        try {
            getPersistedFloat(preferenceKey.defaultValue.toFloat()).toDouble()
        } catch (_: Exception) {
            preferenceKey.defaultValue
        }

    private fun displayValue(): String {
        val storedMgdl = rawStoredMgdl()
        val display = profileUtil.fromMgdlToUnits(storedMgdl, profileUtil.units)
        // Use 1 decimal place for both units — mg/dL delta values like 2.9 must not round to integers
        return BigDecimal(display).setScale(1, RoundingMode.HALF_UP).toPlainString()
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
            // Called from onSetInitialValue — storage is already correct, write it back unchanged.
            val storedMgdl = rawStoredMgdl()
            return try {
                super.persistFloat(storedMgdl.toFloat())
            } catch (_: Exception) {
                super.persistString(storedMgdl.toString())
            }
        }
        // User entered a new value — it is in display units, convert to mg/dL for storage.
        val numericValue = SafeParse.stringToDouble(value, preferenceKey.defaultValue)
        summary = BigDecimal(numericValue).setScale(1, RoundingMode.HALF_UP).toPlainString()
        val store = profileUtil.convertToMgdl(numericValue, profileUtil.units)
        return try {
            super.persistFloat(store.toFloat())
        } catch (_: Exception) {
            super.persistString(store.toString())
        }
    }
}