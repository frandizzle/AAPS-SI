package app.aaps.ui.dialogs

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.objects.extensions.formatColor
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.HtmlHelper
import app.aaps.ui.R
import app.aaps.ui.databinding.DialogSmartMealBinding
import com.google.common.base.Joiner
import java.text.DecimalFormat
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs
import app.aaps.core.interfaces.sharedPreferences.SP
import kotlin.math.roundToInt

class SmartMealDialog : DialogFragmentWithDate() {

    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var ctx: Context
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var sp: SP
    @Inject lateinit var mealOverrideManager: MealOverrideManager

    private var queryingProtection = false
    private var _binding: DialogSmartMealBinding? = null
    private val binding get() = _binding!!

    private var selectedMode: MealMode = MealMode.LUNCH

    private val modeList = listOf(
        MealMode.BREAKFAST,
        MealMode.LUNCH,
        MealMode.DINNER,
        MealMode.LOW_CARB,
        MealMode.EXTENDED
    )

    /** Returns the UnitDoubleKey for the ISF pref of the given mode (null for non-manual modes) */
    private fun isfKeyFor(mode: MealMode): UnitDoubleKey? = when (mode) {
        MealMode.BREAKFAST -> UnitDoubleKey.ApsSmartInsulinBreakfastIsf
        MealMode.LUNCH     -> UnitDoubleKey.ApsSmartInsulinLunchIsf
        MealMode.DINNER    -> UnitDoubleKey.ApsSmartInsulinDinnerIsf
        MealMode.LOW_CARB  -> UnitDoubleKey.ApsSmartInsulinLowCarbIsf
        MealMode.EXTENDED  -> UnitDoubleKey.ApsSmartInsulinExtendedIsf
        else               -> null
    }

    /** Load the stored ISF for the current mode into the picker (converts mg/dL → display units) */
    private fun loadIsfForMode(mode: MealMode) {
        val key = isfKeyFor(mode) ?: return
        // Must use sp.getDouble to bypass valueInCurrentUnitsDetect heuristic which
        // misidentifies ISF values <36 mg/dL/U as mmol (e.g. 18 mg/dL/U → 324).
        val storedMgdl = sp.getDouble(key.key, key.defaultValue)
        binding.isfAmount.value = if (storedMgdl == 0.0) 0.0
        else if (profileUtil.units == app.aaps.core.data.model.GlucoseUnit.MMOL)
            storedMgdl / 18.0
        else storedMgdl
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        onCreateViewGeneral()
        _binding = DialogSmartMealBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val maxPreBolus = preferences.get(DoubleKey.ApsSmartInsulinMaxPreBolus)
        val bolusStep   = activePlugin.activePump.pumpDescription.bolusStep

        // Default PB2 values from global settings (user can override per-activation)
        val defaultPb2U         = preferences.get(DoubleKey.ApsSmartInsulinPreBolus2DefaultU)
        val defaultPb2DelayMins = preferences.get(IntKey.ApsSmartInsulinPreBolus2DefaultDelayMins).toDouble()

        // ── Mode spinner ─────────────────────────────────────────────────────
        val modeLabels = modeList.map { it.label }
        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, modeLabels)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.modeSpinner.adapter = spinnerAdapter
        binding.modeSpinner.setSelection(modeList.indexOf(MealMode.LUNCH))
        binding.modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                selectedMode = modeList[position]
                loadIsfForMode(selectedMode)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        savedInstanceState?.getInt("modeIndex")?.let {
            binding.modeSpinner.setSelection(it)
            selectedMode = modeList[it]
        }

        // ── Duration picker ───────────────────────────────────────────────────
        binding.modeDuration.setParams(
            savedInstanceState?.getDouble("modeDuration") ?: 180.0,
            30.0, 480.0, 30.0,
            DecimalFormat("0"), false, binding.okcancel.ok, null
        )

        // ── ISF picker ────────────────────────────────────────────────────────
        // Range and step are unit-aware: mmol users see 0–20 step 0.1, mg/dL users see 0–360 step 1
        val isMmol   = profileFunction.getUnits() == app.aaps.core.data.model.GlucoseUnit.MMOL
        val isfMax   = if (isMmol) 20.0 else 360.0
        val isfStep  = if (isMmol) 0.1  else 1.0
        val isfFmt   = if (isMmol) DecimalFormat("0.0") else DecimalFormat("0")
        val isfFallbackMgdl = sp.getDouble(UnitDoubleKey.ApsSmartInsulinLunchIsf.key, UnitDoubleKey.ApsSmartInsulinLunchIsf.defaultValue)
        val isfFallback = if (isfFallbackMgdl == 0.0) 0.0
        else if (profileUtil.units == app.aaps.core.data.model.GlucoseUnit.MMOL)
            isfFallbackMgdl / 18.0
        else isfFallbackMgdl
        binding.isfAmount.setParams(
            savedInstanceState?.getDouble("isfAmount") ?: isfFallback,
            0.0, isfMax, isfStep,
            isfFmt, false, binding.okcancel.ok, null
        )
        if (savedInstanceState == null) loadIsfForMode(selectedMode)

        // ── Pre-bolus 1 toggle ────────────────────────────────────────────────
        binding.preBolusSwitch.isChecked = false
        binding.preBolusLayout.visibility = View.GONE
        binding.preBolusSwitch.setOnCheckedChangeListener { _, checked ->
            binding.preBolusLayout.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // ── Pre-bolus 1 amount picker ─────────────────────────────────────────
        binding.preBolusAmount.setParams(
            savedInstanceState?.getDouble("preBolusAmount") ?: 0.0,
            0.0, maxPreBolus, bolusStep,
            decimalFormatter.pumpSupportedBolusFormat(bolusStep),
            false, binding.okcancel.ok, null
        )
        binding.bolus1.setOnClickListener {
            binding.preBolusAmount.value = (binding.preBolusAmount.value + 1.0).coerceAtMost(maxPreBolus)
        }
        binding.bolus2.setOnClickListener {
            binding.preBolusAmount.value = (binding.preBolusAmount.value + 2.0).coerceAtMost(maxPreBolus)
        }
        binding.bolus3.setOnClickListener {
            binding.preBolusAmount.value = (binding.preBolusAmount.value + 3.0).coerceAtMost(maxPreBolus)
        }

        // ── Pre-bolus 2 toggle ────────────────────────────────────────────────
        // PB2 is a scheduled bolus fired by MealOverrideManagerImpl.onLoopCycle()
        // when BG is above target and IOB headroom allows it.
        binding.preBolus2Switch.isChecked = false
        binding.preBolus2Layout.visibility = View.GONE
        binding.preBolus2Switch.setOnCheckedChangeListener { _, checked ->
            binding.preBolus2Layout.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // ── Pre-bolus 2 delay picker (minutes after PB1 / activation) ─────────
        binding.preBolus2DelayMins.setParams(
            savedInstanceState?.getDouble("pb2DelayMins") ?: defaultPb2DelayMins,
            5.0, 120.0, 5.0,
            DecimalFormat("0"), false, binding.okcancel.ok, null
        )

        // ── Pre-bolus 2 amount picker ─────────────────────────────────────────
        binding.preBolus2Amount.setParams(
            savedInstanceState?.getDouble("pb2Amount") ?: defaultPb2U,
            0.5, maxPreBolus, bolusStep,
            decimalFormatter.pumpSupportedBolusFormat(bolusStep),
            false, binding.okcancel.ok, null
        )

        // ── Cancel active mode button ─────────────────────────────────────────
        val activeMode = mealOverrideManager.activeMealMode
        if (activeMode != null) {
            binding.cancelModeButton.visibility = View.VISIBLE

            // UAM modes show a clearer label and explain that selecting a mode will override
            val cancelLabel = if (activeMode.isUam) {
                "Cancel UAM (${activeMode.label})"
            } else {
                "Cancel ${activeMode.label} mode"
            }.let { label ->
                if (mealOverrideManager.preBolus2Pending) "$label\n(${mealOverrideManager.preBolus2StatusText})" else label
            }
            binding.cancelModeButton.text = cancelLabel

            // If UAM is active, show a banner explaining that selecting a mode will override it
            if (activeMode.isUam) {
                binding.uamActiveBanner.visibility = View.VISIBLE
                binding.uamActiveBanner.text = "⚡ ${activeMode.label} active — selecting a mode below will override it"
            } else {
                binding.uamActiveBanner.visibility = View.GONE
            }

            binding.cancelModeButton.setOnClickListener {
                activity?.let { act ->
                    val confirmMsg = if (activeMode.isUam)
                        "Cancel auto-detected ${activeMode.label}? The loop will return to fasting mode."
                    else
                        rh.gs(R.string.si_cancel_mode_confirm, activeMode.label)
                    OKDialog.showConfirmation(act,
                                              rh.gs(R.string.si_dialog_title),
                                              confirmMsg, {
                                                  mealOverrideManager.cancelOverride()
                                                  ToastUtils.okToast(ctx, rh.gs(R.string.si_mode_cancelled))
                                                  dismiss()
                                              })
                }
            }
        } else {
            binding.cancelModeButton.visibility = View.GONE
            binding.uamActiveBanner.visibility = View.GONE
        }

        // ── Cancel PB2 button (shown only when PB2 is pending, mode active) ──
        if (mealOverrideManager.preBolus2Pending) {
            binding.cancelPb2Button.visibility = View.VISIBLE
            // Show live status: countdown or block reason (e.g. "PB2 waiting: BG below target 5.5mmol")
            val pb2Status = mealOverrideManager.preBolus2StatusText
            binding.cancelPb2Button.text = "Cancel  |  $pb2Status"
            binding.cancelPb2Button.setOnClickListener {
                activity?.let { act ->
                    OKDialog.showConfirmation(act,
                                              rh.gs(R.string.si_dialog_title),
                                              "Cancel the scheduled pre-bolus 2? The meal mode will stay active.", {
                                                  mealOverrideManager.cancelPreBolus2()
                                                  ToastUtils.okToast(ctx, "Pre-bolus 2 cancelled")
                                                  dismiss()
                                              })
                }
            }
        } else {
            binding.cancelPb2Button.visibility = View.GONE
        }

        // ── OK / Cancel ───────────────────────────────────────────────────────
        binding.okcancel.ok.setOnClickListener { submit() }
        binding.okcancel.cancel.setOnClickListener { dismiss() }
    }

    override fun submit(): Boolean {
        val durationMins  = binding.modeDuration.value.toInt()
        val durationMs    = TimeUnit.MINUTES.toMillis(durationMins.toLong())
        val wantsPreBolus = binding.preBolusSwitch.isChecked
        val preBolus      = if (wantsPreBolus) binding.preBolusAmount.value else 0.0
        val wantsPb2      = binding.preBolus2Switch.isChecked
        val pb2U          = if (wantsPb2) binding.preBolus2Amount.value else 0.0
        val pb2DelayMins  = if (wantsPb2) binding.preBolus2DelayMins.value.roundToInt() else 0
        val pb2DelayMs    = TimeUnit.MINUTES.toMillis(pb2DelayMins.toLong())
        val bolusStep     = activePlugin.activePump.pumpDescription.bolusStep
        val maxPreBolus   = preferences.get(DoubleKey.ApsSmartInsulinMaxPreBolus)
        val isfValue      = binding.isfAmount.value
        val isMmol        = profileFunction.getUnits() == app.aaps.core.data.model.GlucoseUnit.MMOL

        val pb1Clamped = if (preBolus > 0.0) maxPreBolus.coerceAtMost(preBolus) else 0.0
        val pb2Clamped = if (pb2U > 0.0)     maxPreBolus.coerceAtMost(pb2U)     else 0.0

        val actions: LinkedList<String?> = LinkedList()
        actions.add(
            rh.gs(R.string.si_mode_label) + ": " +
                selectedMode.label.formatColor(context, rh, app.aaps.core.ui.R.attr.icBolusCarbsColor)
        )
        actions.add(
            rh.gs(R.string.si_duration_label) + ": " +
                rh.gs(app.aaps.core.ui.R.string.format_mins, durationMins)
                    .formatColor(context, rh, app.aaps.core.ui.R.attr.icBolusCarbsColor)
        )
        actions.add(
            "ISF override: " +
                (if (isfValue > 0.0) "$isfValue ${if (isMmol) "mmol" else "mg/dL"}" else "Profile ISF")
                    .formatColor(context, rh, app.aaps.core.ui.R.attr.icBolusCarbsColor)
        )
        if (pb1Clamped > 0.0) {
            actions.add(
                rh.gs(app.aaps.core.ui.R.string.bolus) + " (now): " +
                    decimalFormatter.toPumpSupportedBolus(pb1Clamped, bolusStep)
                        .formatColor(context, rh, app.aaps.core.ui.R.attr.bolusColor)
            )
            if (abs(pb1Clamped - preBolus) > bolusStep)
                actions.add(
                    rh.gs(app.aaps.core.ui.R.string.bolus_constraint_applied_warn, preBolus, pb1Clamped)
                        .formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor)
                )
        }
        if (pb2Clamped > 0.0) {
            actions.add(
                "Pre-bolus 2 (in ${pb2DelayMins}min): " +
                    decimalFormatter.toPumpSupportedBolus(pb2Clamped, bolusStep)
                        .formatColor(context, rh, app.aaps.core.ui.R.attr.bolusColor) +
                    " — fires automatically if BG > target &amp; IOB has headroom"
                        .formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor)
            )
        }

        activity?.let { activity ->
            OKDialog.showConfirmation(
                activity,
                rh.gs(R.string.si_dialog_title),
                HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)),
                {
                    // Save updated ISF to SharedPreferences via sp.putDouble — must match
                    // the read path: sp.getDouble(key.key) in the plugin and rawStoredMgdl()
                    // in AdaptiveUnitPreference. preferences.put(UnitDoubleKey) also calls
                    // sp.putDouble internally, but has a type inference compile error here.
                    isfKeyFor(selectedMode)?.let { key ->
                        val isfMgdl = if (isfValue == 0.0) 0.0
                        else profileUtil.convertToMgdl(isfValue, profileUtil.units)
                        sp.putDouble(key.key, isfMgdl)
                    }

                    // Activate meal mode — PB2 params passed to manager for scheduled delivery
                    mealOverrideManager.activateOverride(
                        mode             = selectedMode,
                        doseU            = null,
                        carbsG           = 0,
                        modeWindowMs     = durationMs,
                        preBolus2U       = pb2Clamped,
                        preBolus2DelayMs = pb2DelayMs
                    )

                    // Deliver pre-bolus 1 immediately if requested
                    if (pb1Clamped > 0.0) {
                        val detailedBolusInfo = DetailedBolusInfo()
                        detailedBolusInfo.insulin   = pb1Clamped
                        detailedBolusInfo.context   = context
                        detailedBolusInfo.notes     = "SmartMeal ${selectedMode.label} pre-bolus 1"
                        detailedBolusInfo.timestamp = dateUtil.now()
                        commandQueue.bolus(detailedBolusInfo, object : Callback() {
                            override fun run() {
                                if (!result.success)
                                    uiInteraction.runAlarm(
                                        result.comment,
                                        rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror),
                                        app.aaps.core.ui.R.raw.boluserror
                                    )
                            }
                        })
                    }

                    val pb2Summary = if (pb2Clamped > 0.0)
                        " + PB2 ${pb2Clamped}U in ${pb2DelayMins}min (safety-gated)" else ""
                    ToastUtils.okToast(ctx, rh.gs(R.string.si_mode_activated, selectedMode.label, durationMins) + pb2Summary)
                    dismiss()
                }
            )
        }
        return false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("modeIndex",      modeList.indexOf(selectedMode))
        outState.putDouble("modeDuration", binding.modeDuration.value)
        outState.putDouble("preBolusAmount", binding.preBolusAmount.value)
        outState.putDouble("isfAmount",    binding.isfAmount.value)
        outState.putDouble("pb2DelayMins", binding.preBolus2DelayMins.value)
        outState.putDouble("pb2Amount",    binding.preBolus2Amount.value)
    }

    override fun onResume() {
        super.onResume()
        if (!queryingProtection) {
            queryingProtection = true
            activity?.let { activity ->
                val cancelFail = {
                    queryingProtection = false
                    aapsLogger.debug(LTag.APS, "SmartMealDialog canceled on resume protection")
                    ToastUtils.warnToast(ctx, R.string.dialog_canceled)
                    dismiss()
                }
                protectionCheck.queryProtection(
                    activity,
                    ProtectionCheck.Protection.BOLUS,
                    { queryingProtection = false },
                    cancelFail, cancelFail
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}