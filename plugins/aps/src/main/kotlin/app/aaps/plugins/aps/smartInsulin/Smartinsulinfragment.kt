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

    // Circadian day selector — defaults to today, resets when fragment resumes
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

    private fun fmtBasal(u: Double) = "%.3f U/h".format(u)

    private fun aggrDesc(a: Double) = when {
        a > 1.15 -> "Delivering more insulin than usual"
        a > 1.05 -> "Slightly more aggressive than normal"
        a < 0.85 -> "Being cautious — reducing insulin"
        a < 0.95 -> "Slightly conservative"
        else     -> "Normal aggressiveness"
    }

    // ── Rebound / recovery card ───────────────────────────────────────────────

    private fun updateReboundCard(d: SmartInsulinPlugin.FragmentData) {
        val b = _binding ?: return
        val c = b.reboundRows
        c.removeAllViews()

        if (!d.inReboundWindow && !d.bgWentLow) {
            // No rebound — hide card
            b.reboundCard.visibility = View.GONE
            return
        }
        b.reboundCard.visibility = View.VISIBLE

        if (d.inReboundWindow) {
            val elapsedMins  = d.reboundMins.toDouble()
            val windowMins   = d.reboundWindowMins.toDouble()
            val minsLeft     = (windowMins - elapsedMins).coerceAtLeast(0.0).roundToInt()
            val elapsedInt   = elapsedMins.toInt().coerceAtMost(windowMins.toInt())
            val windowInt    = windowMins.toInt()
            val taperFrac    = (0.3 + (0.7 * (elapsedMins / windowMins))).coerceIn(0.3, 1.0)
            val tbrPct       = (taperFrac * 100).roundToInt()
            val smbGateMins  = windowMins * 0.75  // SMBs unlock at 75% of window (matches REBOUND_SMB_GATE=0.825)
            val smbUnlockIn  = (smbGateMins - elapsedMins).coerceAtLeast(0.0).roundToInt()

            // Status headline
            val headline = if (smbUnlockIn > 0)
                "⚠ Recovery in progress — ${elapsedInt}min of ${windowInt}min"
            else
                "⚠ Recovery in progress — SMBs restored, tapering off in ${minsLeft}min"
            addRow(c, headline, primaryColor = Color.parseColor("#FFFB8C00"))

            // TBR taper
            addRow(c, "TBR capped at ${tbrPct}% of normal",
                   "Starts at 30% and ramps back to 100% over ${windowMins.toInt()} minutes.\n" +
                       "Prevents insulin stacking after a low.")

            // SMB countdown
            if (smbUnlockIn > 0) {
                addRow(c, "SMBs blocked — unlocks in ~${smbUnlockIn}min",
                       "SMBs are held back for the first ${smbGateMins.toInt()} minutes of recovery (75% of ${windowMins.toInt()}min window)\n" +
                           "to avoid over-correcting while the low is still resolving.",
                       Color.parseColor("#FFE53935"))
            } else {
                addRow(c, "SMBs restored ✓",
                       "Corrections are running normally again. TBR taper still active for ${minsLeft}min.",
                       Color.parseColor("#FF43A047"))
            }

            // Meal mode active during recovery
            if (d.mealMode != "Fasting") {
                addRow(c, "Meal mode active — low recovery bypassed until window finishes",
                       "Recovery protection (TBR taper, SMB gate) continues running in the background.\n" +
                           "Meal mode ISF and dosing are applied on top. Recovery ends at ${minsLeft}min.",
                       Color.parseColor("#FF64B5F6"))
            }

            // Bypass status
            if (d.softLandingBypass) {
                addRow(c, "Soft landing — meal detection still active",
                       "The low was borderline (not a crash). UAM is allowed to fire\n" +
                           "during recovery in case you eat.",
                       Color.parseColor("#FF64B5F6"))
            }

            // How low it went
            if (d.minBgDuringLow < Double.MAX_VALUE) {
                val lowBgStr = if (d.isMmol) "${"%.1f".format(d.minBgDuringLow / 18.0)} mmol"
                else "${"%.0f".format(d.minBgDuringLow)} mg/dL"
                addRow(c, "Lowest BG: $lowBgStr",
                       "IOB at time of low: ${"%.2f".format(d.iobAtLowTime)}U\n" +
                           if (d.secondLowOccurred) "⚠ Second low occurred — full lockout, UAM blocked." else "")
            }

        } else if (d.bgWentLow) {
            // Was low but not yet in rebound window (BG still below guard, or just crossed back)
            addRow(c, "⚠ BG went low — waiting for recovery",
                   "Once BG rises back above the low guard, the ${d.reboundWindowMins}-minute\n" +
                       "recovery window will start automatically.",
                   Color.parseColor("#FFE53935"))
            if (d.mealMode != "Fasting") {
                addRow(c, "✓ Low recovery bypassed — meal mode active (${d.mealMode})",
                       "Meal mode ISF and dosing are running normally.\nRecovery window will activate automatically when BG crosses back above the low guard.",
                       Color.parseColor("#FF43A047"))
            }
            if (d.minBgDuringLow < Double.MAX_VALUE) {
                val lowBgStr2 = if (d.isMmol) "${"%.1f".format(d.minBgDuringLow / 18.0)} mmol"
                else "${"%.0f".format(d.minBgDuringLow)} mg/dL"
                addRow(c, "Lowest BG: $lowBgStr2",
                       "IOB at time of low: ${"%.2f".format(d.iobAtLowTime)}U")
            }
        }
    }

    // ── General card ──────────────────────────────────────────────────────────

    private fun updateGeneralCard(d: SmartInsulinPlugin.FragmentData) {
        val c = _binding?.generalRows ?: return; c.removeAllViews()

        addRow(c, "${d.dayLabel}  ${d.hour}:00",
               "Current hour used for circadian adjustments")

        val modeColor = if (d.mealMode == "Fasting") Color.WHITE else Color.parseColor("#FF64B5F6")
        addRow(c, "Mode: ${d.mealMode}",
               d.modeRemMins?.let { "${it}min remaining" } ?: "No active meal — fasting rules apply",
               modeColor)

        val aggrColor = when { d.aggressiveness > 1.05 -> Color.parseColor("#FFFB8C00"); d.aggressiveness < 0.95 -> Color.parseColor("#FF64B5F6"); else -> Color.WHITE }
        val isFasting = d.mealMode == "Fasting"
        val aggrDetail = if (!isFasting) {
            "Aggressiveness: ${"%.3f".format(d.aggressiveness)}  Circ ceiling: ${"%.3f".format(d.circCeil)}\n" +
                "Locked at 1.0 during meal modes — not applied. Fasting value shown for reference."
        } else when {
            d.aggressiveness < 0.95 -> {
                val reductionPct = ((1.0 - d.aggressiveness) * 100).roundToInt()
                "Aggressiveness: ${"%.3f".format(d.aggressiveness)}  Circ ceiling: ${"%.3f".format(d.circCeil)}\n" +
                    "Insulin delivery reduced by ~${reductionPct}% on corrections and TBRs."
            }
            d.aggressiveness > 1.05 -> {
                val boostPct = ((d.aggressiveness - 1.0) * 100).roundToInt()
                "Aggressiveness: ${"%.3f".format(d.aggressiveness)}  Circ ceiling: ${"%.3f".format(d.circCeil)}\n" +
                    "Insulin delivery increased by ~${boostPct}% on corrections and TBRs."
            }
            else ->
                "Aggressiveness: ${"%.3f".format(d.aggressiveness)}  Circ ceiling: ${"%.3f".format(d.circCeil)}\n" +
                    "Based on your BG history over the last 24h."
        }
        val aggrPrimary = if (!isFasting) "Aggressiveness locked — meal mode active" else aggrDesc(d.aggressiveness)
        addRow(c, aggrPrimary, aggrDetail, if (!isFasting) Color.parseColor("#FF888888") else aggrColor)

        val pfIsf  = if (d.isMmol) d.profileIsfMgdl / 18.0 else d.profileIsfMgdl
        val fIsf   = if (d.isMmol) d.finalIsfMgdl   / 18.0 else d.finalIsfMgdl
        val isfUnit = if (d.isMmol) "mmol/U" else "mg/dL/U"
        addRow(c, "Insulin sensitivity: ${"%.1f".format(fIsf)} $isfUnit",
               "Profile ${"%.1f".format(pfIsf)} × multiplier ${"%.3f".format(d.isfMultiplier)} = ${"%.1f".format(fIsf)} $isfUnit\n" +
                   "How much 1U of insulin lowers your BG.")

        addRow(c, "Basal rate: ${fmtBasal(d.finalBasalU)}",
               "Profile ${fmtBasal(d.profileBasalU)} × multiplier ${"%.3f".format(d.basalMultiplier)} = ${fmtBasal(d.finalBasalU)}\n" +
                   "Background insulin rate keeping BG stable between meals.")

        // Pre-bolus 1 — only shown when in meal mode and a dose was delivered
        if (d.mealMode != "Fasting" && d.activeDoseU != null && d.activeDoseU > 0.0) {
            addRow(c, "Pre-bolus — delivered ${"%.2f".format(d.activeDoseU)}U",
                   primaryColor = Color.parseColor("#FF43A047"))
        }

        if (d.pb2Status.isNotEmpty() || d.pb2GateData != null) {
            val gate     = d.pb2GateData
            val isActive = d.pb2Status.contains("active")
            val pb2Primary = when {
                d.pb2Status.contains("due") -> "Pre-bolus 2 — ready to deliver now"
                Regex("""(\d+)m""").containsMatchIn(d.pb2Status) -> {
                    val mins = Regex("""(\d+)m""").find(d.pb2Status)?.groupValues?.get(1)
                    "Pre-bolus 2 — delivers in ${mins}m"
                }
                gate != null -> "Pre-bolus 2 — waiting for safety gates"
                else         -> "Pre-bolus 2"
            }
            addRow(c, pb2Primary, primaryColor = if (isActive) Color.parseColor("#FF43A047") else Color.parseColor("#FF64B5F6"))

            if (gate != null) {
                val isMmol = gate.isMmol
                fun fmtBg(mgdl: Double)    = if (isMmol) "%.1f mmol".format(mgdl / 18.0) else "%.0f mg/dL".format(mgdl)
                fun fmtDelta(mgdl: Double) = if (isMmol) "%+.2f mmol".format(mgdl / 18.0) else "%+.1f mg/dL".format(mgdl)
                fun fmtIob(u: Double)      = "%.2fU".format(u)

                val minBgMgdl   = MealOverrideManager.MIN_BG_FOR_PB2_MGDL
                val targetMgdl  = gate.profileTargetMgdl
                // BG must be above profile target AND above the hard floor
                val effectiveMin = maxOf(minBgMgdl, targetMgdl)
                val bgOk        = gate.bgMgdl > effectiveMin
                val bgDiff      = if (bgOk) "(+${fmtBg(gate.bgMgdl - effectiveMin)} above target)"
                else "(${fmtBg(effectiveMin - gate.bgMgdl)} below target — waiting)"
                addGateRow(c, "BG: ${fmtBg(gate.bgMgdl)}  $bgDiff",
                           "Must be above profile target ${fmtBg(targetMgdl)}", bgOk)

                val maxAllowedIob = gate.maxIobU * MealOverrideManager.MAX_IOB_HEADROOM_RATIO
                val iobOk         = gate.iobU < maxAllowedIob
                val iobDetail     = if (iobOk) "${fmtIob(gate.iobU)} / ${fmtIob(gate.maxIobU)}  (${fmtIob(maxAllowedIob - gate.iobU)} headroom)"
                else "${fmtIob(gate.iobU)} / ${fmtIob(gate.maxIobU)}  (IOB too high — waiting)"
                addGateRow(c, "IOB: $iobDetail", "Must be below ${(MealOverrideManager.MAX_IOB_HEADROOM_RATIO * 100).toInt()}% of max (${fmtIob(maxAllowedIob)})", iobOk)

                val deltaOk   = gate.deltaMgdl >= MealOverrideManager.DELTA_INSTANT_BLOCK_MGDL
                addGateRow(c, "Delta: ${fmtDelta(gate.deltaMgdl)}  (${if (deltaOk) "not falling fast" else "falling — waiting"})",
                           "Blocked below ${fmtDelta(MealOverrideManager.DELTA_INSTANT_BLOCK_MGDL)}", deltaOk)

                val shortOk   = gate.shortAvgDeltaMgdl >= MealOverrideManager.SHORT_AVG_DELTA_BLOCK_MGDL
                addGateRow(c, "15min avg: ${fmtDelta(gate.shortAvgDeltaMgdl)}  (${if (shortOk) "trend stable" else "sustained fall — waiting"})",
                           "Blocked below ${fmtDelta(MealOverrideManager.SHORT_AVG_DELTA_BLOCK_MGDL)}", shortOk)
            }
        }
    }

    // ── TIR bars ──────────────────────────────────────────────────────────────

    private data class TirValues(val inPct: Float, val highPct: Float, val lowPct: Float)

    private fun parseTir(s: String, prefix: String): TirValues? {
        val m = Regex("""$prefix:(\d+)%in/(\d+)%hi/(\d+)%lo""").find(s) ?: return null
        return TirValues(m.groupValues[1].toFloatOrNull() ?: return null,
                         m.groupValues[2].toFloatOrNull() ?: return null,
                         m.groupValues[3].toFloatOrNull() ?: return null)
    }

    private fun applyTirBar(lv: View, iv: View, hv: View, pt: TextView, tir: TirValues?, label: String) {
        if (tir == null) {
            (lv.layoutParams as LinearLayout.LayoutParams).weight = 0f
            (iv.layoutParams as LinearLayout.LayoutParams).weight = 1f
            (hv.layoutParams as LinearLayout.LayoutParams).weight = 0f
            iv.setBackgroundColor(Color.parseColor("#FF9E9E9E"))
            lv.requestLayout(); iv.requestLayout(); hv.requestLayout()
            pt.text = "Not enough data yet — needs ~2 hours of $label readings"; return
        }
        iv.setBackgroundColor(Color.parseColor("#FF43A047"))
        (lv.layoutParams as LinearLayout.LayoutParams).weight = tir.lowPct
        (iv.layoutParams as LinearLayout.LayoutParams).weight = tir.inPct
        (hv.layoutParams as LinearLayout.LayoutParams).weight = tir.highPct
        lv.requestLayout(); iv.requestLayout(); hv.requestLayout()
        pt.text = "${tir.inPct.roundToInt()}% in range  •  ${tir.highPct.roundToInt()}% high  •  ${tir.lowPct.roundToInt()}% low"
    }

    private fun updateTirBars(d: SmartInsulinPlugin.FragmentData) {
        val b = _binding ?: return
        applyTirBar(b.tirFastingLow, b.tirFastingIn, b.tirFastingHigh, b.tvTirFastingPct, parseTir(d.tirRawLine, "Fasting"), "fasting")
        applyTirBar(b.tirMealLow,    b.tirMealIn,    b.tirMealHigh,    b.tvTirMealPct,    parseTir(d.tirRawLine, "Meal"),    "meal")

        // ── Estimated HbA1c (ADAG formula: HbA1c% = (avgBG_mgdl + 46.7) / 28.7) ──
        if (d.estimatedHba1c > 0.0) {
            val avgStr = if (d.isMmol) "${"%.1f".format(d.avgBgMgdl24h / 18.0)} mmol/L"
            else         "${"%.0f".format(d.avgBgMgdl24h)} mg/dL"
            val windowNote = if (d.bgWindowHours < 24) "  (${d.bgWindowHours}h data)" else ""
            val color = when {
                d.estimatedHba1c < 6.5 -> "#FF43A047"  // green
                d.estimatedHba1c < 7.5 -> "#FFFB8C00"  // amber
                else                   -> "#FFE53935"  // red
            }
            b.tvTirHba1c.text = "Est. HbA1c: ${"%.1f".format(d.estimatedHba1c)}%  •  avg: $avgStr$windowNote"
            b.tvTirHba1c.setTextColor(Color.parseColor(color))
        } else {
            b.tvTirHba1c.text = "Est. HbA1c: building… (~2h needed)"
            b.tvTirHba1c.setTextColor(Color.parseColor("#FF888888"))
        }
    }

    // ── Learning card ─────────────────────────────────────────────────────────

    private fun updateLearningCard(d: SmartInsulinPlugin.FragmentData) {
        val c = _binding?.learningRows ?: return; c.removeAllViews()
        val (primary, color) = when {
            d.learningState.startsWith("off") || d.learningState == "Not Learning" ->
                Pair("Learning paused — ${d.learningState.removePrefix("off: ").trim().ifEmpty{"unknown"}}", Color.parseColor("#FFFB8C00"))
            d.learningState == "limited" || d.learningState.startsWith("Limited") ->
                Pair("State: Limited — meal mode active, only learning Peak/DIA", Color.parseColor("#FFFB8C00"))
            else -> Pair("Learning active", Color.parseColor("#FF43A047"))
        }
        addRow(c, primary,
               "SmartInsulin continuously refines your insulin timing, basal rate, and aggressiveness.\nState: ${d.learningState}",
               color)
        if (d.postMealLockoutMins > 0) addRow(c, "Post-meal pause: ${d.postMealLockoutMins}min remaining",
                                              "BG data after meals is excluded from basal/ISF learning to avoid\nfood-related changes corrupting fasting models.", Color.parseColor("#FFFB8C00"))
        val actColor = when (d.activityLevel) {
            "Sedentary" -> Color.parseColor("#FF888888")
            "Light"     -> Color.parseColor("#FF43A047")
            "Moderate"  -> Color.parseColor("#FFFB8C00")
            "Heavy"     -> Color.parseColor("#FFEF6C00")
            else        -> Color.parseColor("#FF888888")
        }

        // Build threshold description — relative if resting HR is set, absolute otherwise
        val hrThresholdLine = if (d.restingHrBpm > 0.0) {
            val lightBpm    = (d.restingHrBpm + 20).toInt()
            val moderateBpm = (d.restingHrBpm + 35).toInt()
            val heavyBpm    = (d.restingHrBpm + 60).toInt()
            "HR thresholds (resting ${d.restingHrBpm.toInt()} bpm):\n" +
                "  Light ≥ ${lightBpm} bpm  •  Moderate ≥ ${moderateBpm} bpm  •  Heavy ≥ ${heavyBpm} bpm"
        } else {
            "HR thresholds (no resting HR set — using absolute):\n" +
                "  Light ≥ 90 bpm  •  Moderate ≥ 110 bpm  •  Heavy ≥ 140 bpm\n" +
                "  Set your resting HR in Activity settings for personalised thresholds."
        }
        val stepsLine = "Steps thresholds:  Light ≥ 200  •  Moderate ≥ 500  •  Heavy ≥ 900 per 5min"

        addRow(c, "Activity: ${d.activityLevel}",
               "HR: ${d.avgHrBpm} bpm avg  •  Steps: ${d.steps5min}/5min\n" +
                   "$hrThresholdLine\n$stepsLine\n" +
                   "High activity raises your target and pauses learning.", actColor)
        if (d.cgmWarmup) addRow(c, "New sensor — learning paused for first 24h",
                                "Day-1 readings can be noisy. Learning resumes automatically after 24h.", Color.parseColor("#FFFB8C00"))
    }

    // ── UAM card ──────────────────────────────────────────────────────────────

    private fun updateUamCard(d: SmartInsulinPlugin.FragmentData) {
        val c = _binding?.uamRows ?: return; c.removeAllViews()
        val line = d.uamStatusLine ?: ""

        // ── Active UAM meal mode — shown when a UAM mode is running ──────────
        val isUamModeActive = d.mealMode.contains("UAM") || d.mealMode.contains("(UAM)")
        if (isUamModeActive) {
            val minsLeft = d.modeRemMins ?: 0
            addRow(c, "${d.mealMode} active — ${minsLeft}min remaining",
                   "Meal auto-detected. ISF and dosing adjusted for ${d.mealMode}.\nUAM detection resumes when this mode expires.",
                   Color.parseColor("#FF64B5F6"))
        } else {
            // ── UAM detection status ──────────────────────────────────────────
            val uamPart = line.substringBefore(" | P/F:").trim()

            val (primary, color) = when {
                uamPart.contains("watching") -> Pair("BG rising — building confirmation streak ↑", Color.parseColor("#FFFB8C00"))
                uamPart.contains("last")     -> Pair("Meal auto-detected recently", Color.parseColor("#FF64B5F6"))
                uamPart.contains("off")      -> Pair("Auto-detection off — outside hours or new sensor", Color.parseColor("#FF888888"))
                uamPart.contains("armed")    -> Pair("Watching for unannounced meals", Color.parseColor("#FF43A047"))
                else                         -> Pair("UAM status", Color.WHITE)
            }
            addRow(c, primary, uamPart.ifEmpty { null }, color)
        }

        // UAM thresholds — strip leading spaces for clean alignment
        val pfPart  = if (line.contains("P/F:")) line.substringAfter("P/F:").trim() else null
        val debugClean = d.uamDebug.lines()
            .filter { !it.trimStart().startsWith("P/F") }
            .joinToString("\n") { it.trimStart() }
            .trim()
        addRow(c, "Detection thresholds",
               debugClean + "\n\nUAM fires when BG rises consistently above the trigger\nthreshold during your configured meal windows.\n\n" +
                   "Clean window = fasting, normal thresholds apply.\n" +
                   "Dirty window = post-meal lockout active — thresholds are raised (~1.5×) to avoid\ndetecting fat/protein tail rises as a new meal.")

        // ── P/F subheading ────────────────────────────────────────────────
        addDivider(c)
        addSectionHeader(c, "Protein / Fat Detection (P/F)")

        val (pfPrimary, pfColor) = when {
            pfPart == null                       -> Pair("P/F detection disabled", Color.parseColor("#FF888888"))
            pfPart.contains("off")               -> Pair("P/F off — ${pfPart.substringAfter("off").trim().removePrefix("(").removeSuffix(")")}", Color.parseColor("#FF888888"))
            pfPart.contains("armed")             -> Pair("Armed — will activate after meal expires", Color.parseColor("#FF43A047"))
            pfPart.contains("/") && pfPart.contains("stuck") -> {
                val count = Regex("""(\d+/\d+)""").find(pfPart)?.groupValues?.get(1)
                Pair("BG stuck high — counting readings ($count)", Color.parseColor("#FFFB8C00"))
            }
            else                                 -> Pair("P/F: $pfPart", Color.WHITE)
        }
        val pfDebug = d.uamDebug.lines()
            .filter { it.trimStart().startsWith("P/F") }
            .joinToString("\n") { it.trimStart() }
            .trim()
        addRow(c, pfPrimary, pfDebug.ifEmpty { null }, pfColor)
    }

    private fun addDivider(container: LinearLayout) {
        val ctx = context ?: return
        val v = android.view.View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt())
                .also { it.topMargin = (8 * dp).toInt(); it.bottomMargin = (8 * dp).toInt() }
            setBackgroundColor(Color.parseColor("#FF444444"))
        }
        container.addView(v)
    }

    private fun addGateRow(container: LinearLayout, primary: String, detail: String, passed: Boolean) {
        val color = if (passed) Color.parseColor("#FF43A047") else Color.parseColor("#FFE53935")
        val prefix = if (passed) "✓ " else "✗ "
        addRow(container, "$prefix$primary", detail, color)
    }

    private fun addSectionHeader(container: LinearLayout, title: String) {
        val ctx = context ?: return
        container.addView(TextView(ctx).apply {
            text = title; textSize = 13f
            setTextColor(Color.parseColor("#FFAAAAAA"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = (8 * dp).toInt() }
        })
    }

    // ── STFT card ─────────────────────────────────────────────────────────────

    private fun updateStftCard(d: SmartInsulinPlugin.FragmentData) {
        val c = _binding?.stftRows ?: return; c.removeAllViews()
        if (d.stftActive && d.stftStatus != null) {
            addRow(c, "Active — gently nudging the loop to correct",
                   d.stftStatus + "\n\nSoft Target Fine-Tune temporarily lowers the loop's internal target\nwhen fasting BG stays stuck above target. Resets when BG falls.",
                   Color.parseColor("#FFFB8C00"))
        } else {
            val inactiveReason = when {
                d.mealMode.contains("Protein") || d.mealMode.contains("P/F") ->
                    "Inactive — P/F running"
                d.mealMode.startsWith("UAM") || d.mealMode.contains("(UAM)") ->
                    "Inactive — UAM running"
                d.mealMode != "Fasting" ->
                    "Inactive — meal mode running (${d.mealMode})"
                else ->
                    "Inactive — BG is responding normally"
            }
            addRow(c, inactiveReason,
                   "STFT activates when fasting BG stays above target for 3+ readings (~15min).\nLowers the loop's target slightly without changing your profile.",
                   Color.parseColor("#FF888888"))
        }
    }

    // ── Circadian table ───────────────────────────────────────────────────────

    private data class CircRow(val hour: Int, val isfMult: Float, val basMult: Float, val ceil: Float, val confPct: Int)

    private fun parseCircRows(raw: String) = Regex("""[►\s]\s*(\d{1,2})\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+(\d+)%""")
        .findAll(raw).mapNotNull { m -> CircRow(
            m.groupValues[1].toIntOrNull()   ?: return@mapNotNull null,
            m.groupValues[2].toFloatOrNull() ?: return@mapNotNull null,
            m.groupValues[3].toFloatOrNull() ?: return@mapNotNull null,
            m.groupValues[4].toFloatOrNull() ?: return@mapNotNull null,
            m.groupValues[5].toIntOrNull()   ?: return@mapNotNull null) }.toList()

    private fun confColor(p: Int)   = when { p >= 60 -> Color.parseColor("#FF43A047"); p >= 30 -> Color.parseColor("#FFFB8C00"); else -> Color.parseColor("#FFE53935") }
    private fun multColor(m: Float) = when { m > 1.05f -> Color.parseColor("#FFFB8C00"); m < 0.95f -> Color.parseColor("#FF64B5F6"); else -> Color.parseColor("#FFAAAAAA") }

    private fun updateCircadianTable(ignored: String) {
        val b = _binding ?: return; val ctx = context ?: return
        val cont = b.circadianRows; cont.removeAllViews()
        val todayDow   = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1
        val currentHr  = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)

        // ── Day selector row — Mon first, Sun last ───────────────────────────
        val selectorRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = (8*dp).toInt() }
        }
        // Display order Mon(2)..Sat(7)->Sun(1), mapped to Java DAY_OF_WEEK-1 indices (0=Sun..6=Sat)
        val displayOrder = intArrayOf(1, 2, 3, 4, 5, 6, 0) // Mon, Tue, Wed, Thu, Fri, Sat, Sun
        val dayLabels    = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        for (d in displayOrder) {
            val label = if (d == todayDow) "Today" else dayLabels[d]
            val isSelected = d == selectedCircadianDow
            val btn = TextView(ctx).apply {
                text = label; textSize = 11f; gravity = Gravity.CENTER
                setPadding((6*dp).toInt(), (4*dp).toInt(), (6*dp).toInt(), (4*dp).toInt())
                setTextColor(if (isSelected) Color.BLACK else Color.parseColor("#FFAAAAAA"))
                setBackgroundColor(if (isSelected) Color.parseColor("#FF43A047") else Color.parseColor("#FF333333"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .also { it.marginEnd = (2*dp).toInt() }
                setOnClickListener {
                    selectedCircadianDow = d
                    refreshStatus()
                }
            }
            selectorRow.addView(btn)
        }
        cont.addView(selectorRow)

        // ── Table for selected day ────────────────────────────────────────────
        val raw   = smartInsulinPlugin.circadianDataForDay(selectedCircadianDow)
        val rows  = parseCircRows(raw); if (rows.isEmpty()) return

        rows.forEach { row ->
            val isCur = selectedCircadianDow == todayDow && row.hour == currentHr
            val rowL = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = (2*dp).toInt() }
                if (isCur) { setBackgroundColor(Color.parseColor("#22FFFFFF")); setPadding((4*dp).toInt(),(2*dp).toInt(),(4*dp).toInt(),(2*dp).toInt()) }
            }
            fun cell(t: String, w: Float, col: Int = Color.parseColor("#FFDDDDDD"), bold: Boolean = false) = TextView(ctx).apply {
                text = t; textSize = 12f; setTextColor(col)
                if (bold || isCur) setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
            }
            rowL.addView(cell(if (isCur) "►${row.hour}" else "  ${row.hour}", 1f, if (isCur) Color.WHITE else Color.parseColor("#FFAAAAAA"), isCur))
            rowL.addView(cell("%.3f".format(row.isfMult), 2f, multColor(row.isfMult)))
            rowL.addView(cell("%.3f".format(row.basMult), 2f, multColor(row.basMult)))
            rowL.addView(cell("%.3f".format(row.ceil),    2f, multColor(row.ceil)))
            val confL = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
            }
            val bt = (40*dp).toInt(); val bh = (8*dp).toInt(); val fw = ((row.confPct/100f)*bt).toInt()
            confL.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(fw, bh); setBackgroundColor(confColor(row.confPct)) })
            confL.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(bt-fw, bh); setBackgroundColor(Color.parseColor("#FF444444")) })
            confL.addView(TextView(ctx).apply { text = " ${row.confPct}%"; textSize = 11f; setTextColor(confColor(row.confPct)); if (isCur) setTypeface(null, Typeface.BOLD) })
            rowL.addView(confL); cont.addView(rowL)
        }
    }

    // ── Profiles card ─────────────────────────────────────────────────────────

    private fun updateProfilesCard(raw: String, activeMealMode: String) {
        val b = _binding ?: return; val c = b.profileRows; c.removeAllViews(); val ctx = context ?: return
        raw.lines().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.trim().split(":"); if (parts.size < 2) return@forEach
            val name     = parts[0].trim(); val info = parts[1].trim()
            val n        = Regex("""n=(\d+)""").find(info)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val isActive = name.trim().equals(activeMealMode.trim(), ignoreCase = true)
            // Colour from learning status regardless of active state
            val col  = when { n >= 5 -> Color.parseColor("#FF43A047"); n >= 1 -> Color.parseColor("#FFFB8C00"); else -> Color.parseColor("#FF888888") }
            val note = when { n == 0 -> "  (using profile values — not enough data yet)"; n < 5 -> "  (still learning)"; else -> "" }
            val prefix = if (isActive) "► " else "  "
            c.addView(TextView(ctx).apply {
                text = "$prefix$name"; textSize = 13f; setTextColor(col)
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.topMargin = (4*dp).toInt() }
            })
            c.addView(TextView(ctx).apply {
                text = info + note; textSize = 11f; setTextColor(Color.parseColor("#FF888888"))
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.bottomMargin = (2*dp).toInt() }
            })
        }
    }

    private fun confirmReset(message: String, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirm Reset").setMessage(message)
            .setPositiveButton("Reset") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null).show()
    }

    companion object { private const val REFRESH_MS = 10_000L }
}