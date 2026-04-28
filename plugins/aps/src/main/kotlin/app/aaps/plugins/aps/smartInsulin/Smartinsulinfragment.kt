package app.aaps.plugins.aps.smartInsulin

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.plugins.aps.databinding.FragmentSmartInsulinBinding
import dagger.android.support.DaggerFragment
import java.util.Calendar
import javax.inject.Inject
import kotlin.math.roundToInt

class SmartInsulinFragment : DaggerFragment() {

    @Inject lateinit var smartInsulinPlugin: SmartInsulinPlugin
    @Inject lateinit var aapsLogger: AAPSLogger

    private var _binding: FragmentSmartInsulinBinding? = null
    private val binding get() = _binding!!

    private var selectedCircadianDow: Int = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1

    private val handler = Handler(Looper.getMainLooper())
    private val updater = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, REFRESH_MS) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSmartInsulinBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnResetAggression.setOnClickListener {
            confirmReset("Reset aggressiveness score to 1.0?") { smartInsulinPlugin.resetAggression(); refreshStatus() }
        }
        binding.btnResetIsf.setOnClickListener {
            confirmReset("Reset ISF circadian learning to 1.0? Basal and aggression learning kept.") { smartInsulinPlugin.resetIsf(); refreshStatus() }
        }
        binding.btnResetBasal.setOnClickListener {
            confirmReset("Reset basal + circadian basal learners to 1.0?") { smartInsulinPlugin.resetBasal(); refreshStatus() }
        }
        binding.btnResetCircadian.setOnClickListener {
            confirmReset("Reset all circadian (ISF/basal/aggr) hourly learning?") { smartInsulinPlugin.resetCircadian(); refreshStatus() }
        }
        binding.btnResetProfiles.setOnClickListener {
            confirmReset("Reset all learned insulin profiles back to defaults?") { smartInsulinPlugin.resetProfiles(); refreshStatus() }
        }
        binding.btnResetAll.setOnClickListener {
            confirmReset("Reset ALL learners? This cannot be undone.") { smartInsulinPlugin.resetAllLearners(); refreshStatus() }
        }
    }

    override fun onResume() {
        super.onResume()
        selectedCircadianDow = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1
        handler.post(updater)
    }
    override fun onPause()       { super.onPause();   handler.removeCallbacks(updater) }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    private fun refreshStatus() {
        if (_binding == null) return
        val d = smartInsulinPlugin.fragmentData()
        binding.tvStatus.text = smartInsulinPlugin.statusSummary()
        updateGeneralCard(d)
        updateReboundCard(d)
        updateTirBars(d)
        updateLearningCard(d)
        updateUamCard(d)
        updateStftCard(d)
        updateCircadianTable("")
        updateProfilesCard(d.profilesRawStatus, d.mealMode)
    }

    // ── Layout helpers ────────────────────────────────────────────────────────

    private val dp get() = context?.resources?.displayMetrics?.density ?: 1f

    private fun addRow(container: LinearLayout, primary: String, detail: String? = null,
                       primaryColor: Int = Color.WHITE) {
        val ctx = context ?: return
        container.addView(TextView(ctx).apply {
            text = primary; textSize = 14f; setTextColor(primaryColor)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = if (detail != null) (2 * dp).toInt() else (10 * dp).toInt() }
        })
        if (detail != null) {
            container.addView(TextView(ctx).apply {
                text = detail; textSize = 11f; setTextColor(Color.parseColor("#FF888888"))
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.bottomMargin = (10 * dp).toInt() }
            })
        }
    }

    private fun addGateRow(container: LinearLayout, primary: String, detail: String, passed: Boolean) {
        val color = if (passed) Color.parseColor("#FF43A047") else Color.parseColor("#FFE53935")
        val prefix = if (passed) "✓ " else "✗ "
        addRow(container, "$prefix$primary", detail, color)
    }

    private fun fmtBasal(u: Double) = "%.3f U/h".format(u)

    private fun aggrDesc(a: Double) = when {
        a > 1.15 -> "Delivering more insulin than usual"
        a > 1.05 -> "Slightly more aggressive than normal"
        a < 0.85 -> "Being cautious — reducing insulin"
        a < 0.95 -> "Slightly conservative"
        else     -> "Normal aggressiveness"
    }

    // ── General card (Restored Pre-Bolus & Gates) ─────────────────────────────

    private fun updateGeneralCard(d: SmartInsulinPlugin.FragmentData) {
        val c = _binding?.generalRows ?: return; c.removeAllViews()

        addRow(c, "${d.dayLabel}  ${d.hour}:00", "Current hour used for circadian adjustments")

        val modeColor = if (d.mealMode == "Fasting") Color.WHITE else Color.parseColor("#FF64B5F6")
        addRow(c, "Mode: ${d.mealMode}",
               d.modeRemMins?.let { "${it}min remaining" } ?: "No active meal — fasting rules apply",
               modeColor)

        // Aggressiveness Logic
        val aggrColor = when { d.aggressiveness > 1.05 -> Color.parseColor("#FFFB8C00"); d.aggressiveness < 0.95 -> Color.parseColor("#FF64B5F6"); else -> Color.WHITE }
        val isFasting = d.mealMode == "Fasting"
        val longTermAbs = Math.abs(((1.0 - d.basalMultiplier) * 100).roundToInt())

        val aggrDetail = if (!isFasting) {
            "Aggressiveness: ${"%.3f".format(d.aggressiveness)}  Circ ceiling: ${"%.3f".format(d.circCeil)}\nLocked at 1.0 during meal modes."
        } else if (d.inReboundWindow || d.bgWentLow) {
            "Aggressiveness: ${"%.3f".format(d.aggressiveness)}  Circ ceiling: ${"%.3f".format(d.circCeil)}\n⚠ Low recovery active — holding back insulin."
        } else {
            "Aggressiveness: ${"%.3f".format(d.aggressiveness)}  Circ ceiling: ${"%.3f".format(d.circCeil)}\nLong term: profile changed by ~${longTermAbs}% at this hour."
        }

        addRow(c, if (!isFasting) "Aggressiveness locked — meal mode active" else aggrDesc(d.aggressiveness), aggrDetail,
               if (d.inReboundWindow || d.bgWentLow) Color.parseColor("#FFFB8C00") else aggrColor)

        // ISF & Basal
        val isfUnit = if (d.isMmol) "mmol/U" else "mg/dL/U"
        val fIsf = if (d.isMmol) d.finalIsfMgdl / 18.0 else d.finalIsfMgdl
        addRow(c, "Insulin sensitivity: ${"%.1f".format(fIsf)} $isfUnit", "Profile ISF ÷ multiplier = final ISF")
        addRow(c, "Basal rate: ${fmtBasal(d.finalBasalU)}", "Profile Basal × multiplier = final Basal")

        // ── PRE-BOLUS 1 ──
        if (d.mealMode != "Fasting" && d.activeDoseU != null && d.activeDoseU > 0.0) {
            addRow(c, "Pre-bolus 1 — delivered ${"%.2f".format(d.activeDoseU)}U", null, Color.parseColor("#FF43A047"))
        }

        // ── PRE-BOLUS 2 & GATES ──
        if (d.mealMode != "Fasting" && d.activePb2DoseU != null && d.activePb2DoseU > 0.0) {
            addRow(c, "Pre-bolus 2 — delivered ${"%.2f".format(d.activePb2DoseU)}U", "Second bolus delivered.", Color.parseColor("#FF43A047"))
        } else if (d.pb2Status.isNotEmpty() || d.pb2GateData != null) {
            val gate = d.pb2GateData
            val isActive = d.pb2Status.contains("active")
            val pb2Primary = when {
                d.pb2Status.contains("due") -> "Pre-bolus 2 — ready to deliver now"
                Regex("""(\d+)m""").containsMatchIn(d.pb2Status) -> "Pre-bolus 2 — delivers in ${Regex("""(\d+)m""").find(d.pb2Status)?.groupValues?.get(1)}m"
                gate != null -> "Pre-bolus 2 — waiting for safety gates"
                else -> "Pre-bolus 2"
            }
            addRow(c, pb2Primary, null, if (isActive) Color.parseColor("#FF43A047") else Color.parseColor("#FF64B5F6"))

            if (gate != null) {
                val isM = gate.isMmol
                fun fBg(m: Double) = if (isM) "%.1f mmol".format(m / 18.0) else "%.0f mg/dL".format(m)
                fun fD(m: Double) = if (isM) "%+.2f mmol".format(m / 18.0) else "%+.1f mg/dL".format(m)

                val effMin = maxOf(MealOverrideManager.MIN_BG_FOR_PB2_MGDL, gate.profileTargetMgdl)
                val bgOk = gate.bgMgdl > effMin
                addGateRow(c, "BG: ${fBg(gate.bgMgdl)}", "Must be above target ${fBg(gate.profileTargetMgdl)}", bgOk)

                val maxIob = gate.maxIobU * MealOverrideManager.MAX_IOB_HEADROOM_RATIO
                val iobOk = gate.iobU < maxIob
                addGateRow(c, "IOB: %.2fU".format(gate.iobU), "Must be below %.2fU".format(maxIob), iobOk)

                val deltaOk = gate.deltaMgdl >= MealOverrideManager.DELTA_INSTANT_BLOCK_MGDL
                addGateRow(c, "Delta: ${fD(gate.deltaMgdl)}", "Blocked below ${fD(MealOverrideManager.DELTA_INSTANT_BLOCK_MGDL)}", deltaOk)

                val shortOk = gate.shortAvgDeltaMgdl >= MealOverrideManager.SHORT_AVG_DELTA_BLOCK_MGDL
                addGateRow(c, "15min avg: ${fD(gate.shortAvgDeltaMgdl)}", "Blocked below ${fD(MealOverrideManager.SHORT_AVG_DELTA_BLOCK_MGDL)}", shortOk)
            }
        }

        // ── PRE-BOLUS 3 & GATES ──
        if (d.mealMode != "Fasting" && d.activePb3DoseU != null && d.activePb3DoseU > 0.0) {
            addRow(c, "Pre-bolus 3 — delivered ${"%.2f".format(d.activePb3DoseU)}U", "Third bolus delivered.", Color.parseColor("#FF43A047"))
        } else if (d.pb3Status.isNotEmpty() || d.pb3GateData != null) {
            val gate3 = d.pb3GateData
            addRow(c, "Pre-bolus 3 status: ${d.pb3Status}", null, Color.parseColor("#FF64B5F6"))
            if (gate3 != null) {
                val isM3 = gate3.isMmol
                fun fBg3(m: Double) = if (isM3) "%.1f mmol".format(m / 18.0) else "%.0f mg/dL".format(m)
                val bgOk3 = gate3.bgMgdl > maxOf(MealOverrideManager.MIN_BG_FOR_PB2_MGDL, gate3.profileTargetMgdl)
                addGateRow(c, "BG: ${fBg3(gate3.bgMgdl)}", "Gate safety check", bgOk3)
            }
        }
    }

    // ── Learning card (Ported Fuel Trim & PredTrim) ───────────────────────────

    private fun updateLearningCard(d: SmartInsulinPlugin.FragmentData) {
        val c = _binding?.learningRows ?: return; c.removeAllViews()

        val (statePrimary, stateColor) = when {
            d.learningState.startsWith("off") -> Pair("Learning paused — ${d.learningState.removePrefix("off: ")}", Color.parseColor("#FFFB8C00"))
            d.learningState == "limited" -> Pair("State: Limited — only learning Peak/DIA", Color.parseColor("#FFFB8C00"))
            else -> Pair("Learning active", Color.parseColor("#FF43A047"))
        }
        addRow(c, statePrimary, "SmartInsulin refines timing and basal over time.", stateColor)

        // Aggression Nudge / Fuel Trim logic
        val nudgeParts = d.lastAggrNudgeStatus.split("|")
        val nudgeState = nudgeParts.getOrNull(0) ?: "INACTIVE"
        val nudgeTrim = nudgeState == "TRIM"
        val predTrimActive = d.lastBasalSignal.contains("PredTrim") && d.lastBasalSignal.contains("proj=-")

        val nudgeColor = when {
            nudgeTrim || nudgeState.startsWith("ACTIVE") -> Color.parseColor("#FFFB8C00")
            predTrimActive -> Color.parseColor("#FFFB8C00")
            else -> Color.parseColor("#FF888888")
        }

        when {
            nudgeTrim -> {
                val adding = nudgeParts.getOrNull(1) == "ACTIVE_HIGH"
                addRow(c, "⚡ Fuel trim: ${if (adding) "adding" else "removing"} ${nudgeParts.getOrNull(2)} insulin",
                       "Short-term ceiling adjusted based on peak deviation.", nudgeColor)
            }
            predTrimActive -> {
                addRow(c, "⬇ Defensive basal cut (PredTrim)", "Projected drop detected: ${d.lastBasalSignal}", nudgeColor)
            }
            nudgeState.startsWith("ACTIVE") -> {
                addRow(c, "⚡ Aggression adjusting", "Pattern detected at hour ${nudgeParts.getOrNull(3)}.", nudgeColor)
            }
            else -> {
                addRow(c, "Insulin levels look balanced", "No consistent deviations detected this hour.", nudgeColor)
            }
        }

        // Debug Footer
        addDivider(c)
        addRow(c, "Accel: ${d.lastAccelDebug}", null, Color.parseColor("#FFAAAAAA"))
        addRow(c, "PredTrim: ${d.lastPredTrimDebug}", null, Color.parseColor("#FFAAAAAA"))
    }

    // ── STFT Card ─────────────────────────────────────────────────────────────

    private fun updateStftCard(d: SmartInsulinPlugin.FragmentData) {
        val c = _binding?.stftRows ?: return; c.removeAllViews()
        if (d.stftActive && d.stftStatus != null) {
            addRow(c, "Active — gently nudging loop", d.stftStatus, Color.parseColor("#FFFB8C00"))
        } else {
            val reason = when {
                d.stftStatus?.contains("high temp target") == true -> "Inactive — high temp target"
                d.mealMode != "Fasting" -> "Inactive — meal mode running"
                else -> "Inactive — BG stable"
            }
            addRow(c, reason, "STFT lowers target when BG is stuck high.", Color.parseColor("#FF888888"))
        }
    }

    // ── Standard UI Helpers ──────────────────────────────────────────────────

    private fun updateReboundCard(d: SmartInsulinPlugin.FragmentData) {
        val b = _binding ?: return; val c = b.reboundRows; c.removeAllViews()
        if (!d.inReboundWindow && !d.bgWentLow) { b.reboundCard.visibility = View.GONE; return }
        b.reboundCard.visibility = View.VISIBLE
        addRow(c, if (d.inReboundWindow) "⚠ Recovery in progress" else "⚠ BG below low guard",
               "${d.reboundMins}m elapsed of ${d.totalReboundWindowMins}m window.", Color.parseColor("#FFFB8C00"))
    }

    private fun updateUamCard(d: SmartInsulinPlugin.FragmentData) {
        val c = _binding?.uamRows ?: return; c.removeAllViews()
        addRow(c, "UAM Status", d.uamStatusLine ?: "Armed", Color.WHITE)
        addDivider(c)
        addRow(c, "P/F Detection", d.uamDebug.lines().find { it.contains("P/F") } ?: "Ready", Color.parseColor("#FFAAAAAA"))
    }

    private fun updateTirBars(d: SmartInsulinPlugin.FragmentData) {
        val b = _binding ?: return
        val fast = parseTir(d.tirRawLine, "Fasting")
        applyTirBar(b.tirFastingLow, b.tirFastingIn, b.tirFastingHigh, b.tvTirFastingPct, fast, "fasting")
        val meal = parseTir(d.tirRawLine, "Meal")
        applyTirBar(b.tirMealLow, b.tirMealIn, b.tirMealHigh, b.tvTirMealPct, meal, "meal")
        if (d.estimatedHba1c > 0.0) b.tvTirHba1c.text = "Est. HbA1c: ${"%.1f".format(d.estimatedHba1c)}%"
    }

    private fun updateCircadianTable(ignored: String) {
        val b = _binding ?: return; val cont = b.circadianRows; cont.removeAllViews()
        val raw = smartInsulinPlugin.circadianDataForDay(selectedCircadianDow)
        Regex("""[►\s]\s*(\d{1,2})\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+(\d+)%""").findAll(raw).forEach { m ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            fun cell(t: String, w: Float) = TextView(context).apply { text = t; textSize = 11f; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, -2, w) }
            row.addView(cell(m.groupValues[1], 1f))
            row.addView(cell(m.groupValues[2], 2f))
            row.addView(cell(m.groupValues[3], 2f))
            row.addView(cell(m.groupValues[4], 2f))
            row.addView(cell(m.groupValues[5] + "%", 3f))
            cont.addView(row)
        }
    }

    private fun updateProfilesCard(raw: String, activeMealMode: String) {
        val c = _binding?.profileRows ?: return; c.removeAllViews()
        raw.lines().filter { it.isNotBlank() }.forEach { line ->
            val name = line.substringBefore(":"); val info = line.substringAfter(":")
            addRow(c, (if(activeMealMode.contains(name.trim(), true)) "► " else "  ") + name, info,
                   if(activeMealMode.contains(name.trim(), true)) Color.parseColor("#FF64B5F6") else Color.WHITE)
        }
    }

    private fun addDivider(container: LinearLayout) {
        container.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, (1 * dp).toInt()).also { it.setMargins(0, (8*dp).toInt(), 0, (8*dp).toInt()) }
            setBackgroundColor(Color.parseColor("#FF444444"))
        })
    }

    private fun addSectionHeader(container: LinearLayout, title: String) {
        container.addView(TextView(context).apply { text = title; textSize = 12f; setTextColor(Color.GRAY); setTypeface(null, Typeface.BOLD) })
    }

    private fun confirmReset(m: String, on: () -> Unit) = MaterialAlertDialogBuilder(requireContext()).setMessage(m).setPositiveButton("Reset") { _, _ -> on() }.show()
    private fun parseTir(s: String, p: String) = Regex("""$p:(\d+)%in/(\d+)%hi/(\d+)%lo""").find(s)?.let { TirValues(it.groupValues[1].toFloat(), it.groupValues[2].toFloat(), it.groupValues[3].toFloat()) }
    private fun applyTirBar(lv: View, iv: View, hv: View, pt: TextView, t: TirValues?, label: String) {
        val paramsI = iv.layoutParams as LinearLayout.LayoutParams
        if (t == null) { paramsI.weight = 1f; pt.text = "Loading..."; return }
        (lv.layoutParams as LinearLayout.LayoutParams).weight = t.lowPct
        (iv.layoutParams as LinearLayout.LayoutParams).weight = t.inPct
        (hv.layoutParams as LinearLayout.LayoutParams).weight = t.highPct
        pt.text = "${t.inPct.toInt()}% in range"
    }

    private data class TirValues(val inPct: Float, val highPct: Float, val lowPct: Float)
    companion object { private const val REFRESH_MS = 10_000L }
}