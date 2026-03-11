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
import kotlin.math.roundToInt

class SmartMealDialog : DialogFragmentWithDate() {

    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var ctx: Context
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var decimalFormatter: DecimalFormatter
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

    /** Returns the DoubleKey for the ISF pref of the given mode (null for FASTING) */
    private fun isfKeyFor(mode: MealMode): DoubleKey? = when (mode) {
        MealMode.BREAKFAST -> DoubleKey.ApsSmartInsulinBreakfastIsf
        MealMode.LUNCH     -> DoubleKey.ApsSmartInsulinLunchIsf
        MealMode.DINNER    -> DoubleKey.ApsSmartInsulinDinnerIsf
        MealMode.LOW_CARB  -> DoubleKey.ApsSmartInsulinLowCarbIsf
        MealMode.EXTENDED  -> DoubleKey.ApsSmartInsulinExtendedIsf
        else               -> null
    }

    /** Load the stored ISF for the current mode into the picker */
    private fun loadIsfForMode(mode: MealMode) {
        val key = isfKeyFor(mode) ?: return
        binding.isfAmount.value = preferences.get(key)
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
        binding.isfAmount.setParams(
            savedInstanceState?.getDouble("isfAmount") ?: preferences.get(isfKeyFor(selectedMode) ?: DoubleKey.ApsSmartInsulinLunchIsf),
            0.0, 20.0, 0.1,
            DecimalFormat("0.0"), false, binding.okcancel.ok, null
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
            var cancelLabel = "Cancel ${activeMode.label} mode"
            if (mealOverrideManager.preBolus2Pending) {
                val minsLeft = mealOverrideManager.preBolus2MinutesRemaining ?: 0
                cancelLabel += "\n(PB2 fires in ${minsLeft}min)"
            }
            binding.cancelModeButton.text = cancelLabel
            binding.cancelModeButton.setOnClickListener {
                activity?.let { act ->
                    OKDialog.showConfirmation(act,
                                              rh.gs(R.string.si_dialog_title),
                                              rh.gs(R.string.si_cancel_mode_confirm, activeMode.label), {
                                                  mealOverrideManager.cancelOverride()
                                                  ToastUtils.okToast(ctx, rh.gs(R.string.si_mode_cancelled))
                                                  dismiss()
                                              })
                }
            }
        } else {
            binding.cancelModeButton.visibility = View.GONE
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
            rh.gs(R.string.si_isf_label) + ": " +
                (if (isfValue > 0.0) "${isfValue} mmol" else "Profile ISF")
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
                    // Save updated ISF back to preferences
                    isfKeyFor(selectedMode)?.let { key ->
                        preferences.put(key, isfValue)
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