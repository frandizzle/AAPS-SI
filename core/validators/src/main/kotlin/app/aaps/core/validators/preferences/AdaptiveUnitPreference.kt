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

    private var converted: BigDecimal

    init {
        (context.applicationContext as HasAndroidInjector).androidInjector().inject(this)

        unitKey?.let { key = it.key }
        dialogMessage?.let { setDialogMessage(it) }
        title?.let { dialogTitle = context.getString(it) }
        title?.let { this.title = context.getString(it) }

        preferenceKey = unitKey ?: preferences.get(key) as UnitDoublePreferenceKey

        // FIX: stored value is always mg/dL (written by persistString below).
        // Use fromMgdlToUnits() which converts from known mg/dL — no heuristic guessing.
        // The original valueInCurrentUnitsDetect() uses a < 36 threshold to detect units,
        // which misidentifies small mg/dL values (e.g. 9, 18, 27) as mmol and multiplies by 18.
        val storedMgdl = preferences.get(preferenceKey)
        val displayValue = profileUtil.fromMgdlToUnits(storedMgdl, profileUtil.units)
        val precision = if (profileUtil.units == GlucoseUnit.MGDL) 0 else 1
        converted = BigDecimal(displayValue).setScale(precision, RoundingMode.HALF_UP)
        summary = converted.toPlainString()

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
            editText.inputType = InputType.TYPE_NUMBER_FLAG_DECIMAL
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

    override fun onSetInitialValue(defaultValue: Any?) {
        text = converted.toPlainString()
    }

    override fun persistString(value: String?): Boolean {
        val numericValue = SafeParse.stringToDouble(value, preferenceKey.defaultValue)
        summary = numericValue.toString()
        // User entered value is in display units — convert to mg/dL for storage
        val store = profileUtil.convertToMgdl(numericValue, profileUtil.units)
        return try {
            super.persistFloat(store.toFloat())
        } catch (_: Exception) {
            super.persistString(store.toString())
        }
    }
}