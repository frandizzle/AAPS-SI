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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        onCreateViewGeneral()
        _binding = DialogSmartMealBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val maxInsulin = constraintChecker.getMaxBolusAllowed().value()
        val bolusStep  = activePlugin.activePump.pumpDescription.bolusStep

        // ── Mode spinner ─────────────────────────────────────────────────────
        val modeLabels = modeList.map { it.label }
        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, modeLabels)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.modeSpinner.adapter = spinnerAdapter
        binding.modeSpinner.setSelection(modeList.indexOf(MealMode.LUNCH))
        binding.modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                selectedMode = modeList[position]
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

        // ── Pre-bolus toggle ──────────────────────────────────────────────────
        binding.preBolusSwitch.isChecked = false
        binding.preBolusLayout.visibility = View.GONE
        binding.preBolusSwitch.setOnCheckedChangeListener { _, checked ->
            binding.preBolusLayout.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // ── Pre-bolus amount picker ───────────────────────────────────────────
        binding.preBolusAmount.setParams(
            savedInstanceState?.getDouble("preBolusAmount") ?: 0.0,
            0.0, maxInsulin, bolusStep,
            decimalFormatter.pumpSupportedBolusFormat(bolusStep),
            false, binding.okcancel.ok, null
        )

        // Quick-add buttons
        binding.bolus1.setOnClickListener {
            binding.preBolusAmount.value = (binding.preBolusAmount.value + 1.0).coerceAtMost(maxInsulin)
        }
        binding.bolus2.setOnClickListener {
            binding.preBolusAmount.value = (binding.preBolusAmount.value + 2.0).coerceAtMost(maxInsulin)
        }
        binding.bolus3.setOnClickListener {
            binding.preBolusAmount.value = (binding.preBolusAmount.value + 3.0).coerceAtMost(maxInsulin)
        }

        // ── Cancel active mode button ─────────────────────────────────────────
        val activeMode = mealOverrideManager.activeMealMode
        if (activeMode != null) {
            binding.cancelModeButton.visibility = View.VISIBLE
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
        binding.okcancel.ok.setOnClickListener { if (submit()) dismiss() }
        binding.okcancel.cancel.setOnClickListener { dismiss() }
    }

    override fun submit(): Boolean {
        val durationMins  = binding.modeDuration.value.toInt()
        val durationMs    = TimeUnit.MINUTES.toMillis(durationMins.toLong())
        val wantsPreBolus = binding.preBolusSwitch.isChecked
        val preBolus      = if (wantsPreBolus) binding.preBolusAmount.value else 0.0
        val bolusStep     = activePlugin.activePump.pumpDescription.bolusStep

        val prebolusAfterConstraints = if (preBolus > 0.0)
            constraintChecker.getMaxBolusAllowed().value().coerceAtMost(preBolus)
        else 0.0

        val actions: LinkedList<String?> = LinkedList()
        actions.add(
            rh.gs(R.string.si_mode_label) + ": " +
                selectedMode.label.formatColor(context, rh, app.aaps.core.ui.R.attr.colorPrimary)
        )
        actions.add(
            rh.gs(R.string.si_duration_label) + ": " +
                rh.gs(app.aaps.core.ui.R.string.format_mins, durationMins)
                    .formatColor(context, rh, app.aaps.core.ui.R.attr.colorPrimary)
        )
        if (prebolusAfterConstraints > 0.0) {
            actions.add(
                rh.gs(app.aaps.core.ui.R.string.bolus) + ": " +
                    decimalFormatter.toPumpSupportedBolus(prebolusAfterConstraints, bolusStep)
                        .formatColor(context, rh, app.aaps.core.ui.R.attr.bolusColor)
            )
            if (abs(prebolusAfterConstraints - preBolus) > bolusStep)
                actions.add(
                    rh.gs(app.aaps.core.ui.R.string.bolus_constraint_applied_warn, preBolus, prebolusAfterConstraints)
                        .formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor)
                )
        }

        activity?.let { activity ->
            OKDialog.showConfirmation(
                activity,
                rh.gs(R.string.si_dialog_title),
                HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)),
                {
                    mealOverrideManager.activateOverride(
                        mode         = selectedMode,
                        doseU        = if (prebolusAfterConstraints > 0.0) prebolusAfterConstraints else null,
                        carbsG       = 0,
                        modeWindowMs = durationMs
                    )
                    if (prebolusAfterConstraints > 0.0) {
                        val detailedBolusInfo = DetailedBolusInfo()
                        detailedBolusInfo.insulin   = prebolusAfterConstraints
                        detailedBolusInfo.context   = context
                        detailedBolusInfo.notes     = "SmartMeal ${selectedMode.label} pre-bolus"
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
                    ToastUtils.okToast(ctx, rh.gs(R.string.si_mode_activated, selectedMode.label, durationMins))
                }
            )
        }
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("modeIndex", modeList.indexOf(selectedMode))
        outState.putDouble("modeDuration", binding.modeDuration.value)
        outState.putDouble("preBolusAmount", binding.preBolusAmount.value)
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