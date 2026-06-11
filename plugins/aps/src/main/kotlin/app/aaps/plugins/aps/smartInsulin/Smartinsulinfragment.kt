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
    private var showFfDebug = false

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
        updateProfilesCard(d)
    }

    // -- Layout helpers --------------------------------------------------------

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
                text = detail; textSize = 11f; setTextColor(Color.parseColor("#FFDDDDDD"))
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

    // -- Rebound / recovery card -----------------------------------------------

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
            val windowMins   = d.totalReboundWindowMins.toDouble()  // includes rollercoaster extension
            val baseMins     = d.reboundWindowMins.toDouble()
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

            // BG currently below low guard during an active window — show a clear note so the
            // "X of Y" counter is not misread as everything-is-fine. The counter keeps ticking;
            // rollercoaster detection will extend the total via consecutiveRollercoasters.
            if (d.currentBgMgdl > 0.0 && d.currentBgMgdl < d.lowGuardMgdl) {
                addRow(c, "⚠ BG currently below low guard — recovery counter still running",
                       "The elapsed timer keeps ticking through brief re-dips. If this becomes a\n" +
                           "rollercoaster, the total window will be extended automatically.",
                       Color.parseColor("#FFE53935"))
            }

            // TBR taper
            addRow(c, "TBR capped at ${tbrPct}% of normal",
                   "Starts at 30% and ramps back to 100% over ${windowMins.toInt()} minutes.\n" +
                       "Prevents insulin stacking after a low.")

            // SMB countdown
            if (smbUnlockIn > 0) {
                addRow(c, "SMBs blocked — unlocks in ~${smbUnlockIn}min",
                       "SMBs held back for first ${smbGateMins.toInt()} minutes (75% of ${windowMins.toInt()}min window).\nAvoids over-correcting while the low is still resolving.",
                       Color.parseColor("#FFE53935"))
            } else {
                addRow(c, "SMBs restored ✓",
                       "Corrections running normally again. TBR taper still active for ${minsLeft}min.",
                       Color.parseColor("#FF43A047"))
            }

            // Rollercoaster extension rows
            if (d.consecutiveRollercoasters >= 1) {
                val extMins = (windowMins - baseMins).toInt()
                val extLabel = when (d.consecutiveRollercoasters) {
                    1 -> "Rollercoaster 1 detected — extending recovery by ${extMins}min"
                    2 -> "Rollercoaster 2 detected — extending recovery by ${extMins}min"
                    else -> "Rollercoaster ${d.consecutiveRollercoasters} detected — extending recovery by ${extMins}min"
                }
                addRow(c, extLabel,
                       "Base: ${baseMins.toInt()}min + ${extMins}min extension = ${windowMins.toInt()}min total.\n" +
                           "Extension grows with each consecutive rollercoaster (max +45min).\n" +
                           "Resets after 2h with no further rollercoasters.",
                       Color.parseColor("#FFFB8C00"))
            }

            // Meal mode active during recovery
            if (d.mealMode != "Fasting") {
                addRow(c, "Meal mode active — low recovery bypassed until window finishes",
                       "Recovery protection (TBR taper, SMB gate) continues running in the background.\n" +
                           "Meal mode ISF and dosing are applied on top. Recovery ends in ${minsLeft}min.",
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
            // bgWentLow=true but inReboundWindow=false means BG went low but hasn't yet
            // triggered the rebound window start (BG still below or just crossed threshold).
            // Check actual current BG to show correct message.
            val actuallyBelowGuard = d.currentBgMgdl > 0.0 && d.currentBgMgdl < d.lowGuardMgdl
            if (actuallyBelowGuard) {
                addRow(c, "⚠ BG is below low guard — waiting for recovery",
                       "Once BG rises above the low guard, the ${d.totalReboundWindowMins}-minute recovery window starts automatically." +
                           if (d.consecutiveRollercoasters >= 1) {
                               val extMins = d.totalReboundWindowMins - d.reboundWindowMins
                               "\nRollercoaster ${d.consecutiveRollercoasters} detected — window extended by ${extMins}min."
                           } else "",
                       Color.parseColor("#FFE53935"))
            } else {
                addRow(c, "⚡ BG recovering — rebound window starting",
                       "BG has crossed back above the low guard. The ${d.totalReboundWindowMins}-minute recovery window is activating." +
                           if (d.consecutiveRollercoasters >= 1) {
                               val extMins = d.totalReboundWindowMins - d.reboundWindowMins
                               "\nRollercoaster ${d.consecutiveRollercoasters} detected — window extended by ${extMins}min."
                           } else "",
                       Color.parseColor("#FFFB8C00"))
            }
            if (d.mealMode != "Fasting") {
                addRow(c, "✓ Low recovery bypassed — meal mode active (${d.mealMode})",
                       "Meal mode ISF and dosing running normally.\nRecovery window activates automatically when BG crosses back above the low guard.",
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

    // -- General card ----------------------------------------------------------

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

        // -- Plain-English short-term / long-term insulin summary -------------
        // Short term = circadian ceiling (what the loop is doing RIGHT NOW this hour)
        // Long term  = basal multiplier (what the profile has permanently learned)
        val shortTermPct  = ((1.0 - d.circCeil) * 100).roundToInt()      // +ve = reducing, -ve = adding
        val longTermPct   = ((1.0 - d.basalMultiplier) * 100).roundToInt()
        val shortTermAbs  = Math.abs(shortTermPct)
        val longTermAbs   = Math.abs(longTermPct)
        val shortTermDir  = if (shortTermPct > 0) "too much" else if (shortTermPct < 0) "not enough" else "balanced"
        val longTermDir   = if (longTermPct  > 0) "reduced"  else if (longTermPct  < 0) "increased"  else "unchanged"

        val aggrDetail = if (!isFasting) {
            "Aggressiveness: ${"%.3f".format(d.aggressiveness)}  Circ ceiling: ${"%.3f".format(d.circCeil)}\n" +
                "Locked at 1.0 during meal modes — not applied. Fasting value shown for reference."
        } else if (d.inReboundWindow) {
            // Low recovery active — show what the rebound window is actually doing
            val elapsedMins  = d.reboundMins
            val windowMins   = d.totalReboundWindowMins
            val minsLeft     = (windowMins - elapsedMins).coerceAtLeast(0)
            val taperFrac    = (0.3 + (0.7 * (elapsedMins.toDouble() / windowMins))).coerceIn(0.3, 1.0)
            val tbrPct       = (taperFrac * 100).roundToInt()
            val smbGateMins  = (windowMins * 0.75).roundToInt()
            val smbUnlockIn  = (smbGateMins - elapsedMins).coerceAtLeast(0)
            val rollerNote   = if (d.consecutiveRollercoasters >= 1)
                "\nRollercoaster ${d.consecutiveRollercoasters} detected — window extended to ${windowMins}min."
            else ""
            val hardLowNote  = if (d.hardLowPenaltyActive)
                "\nAggressiveness ceiling cut by 20% — recovers as BG stabilises near target."
            else ""
            val longTermLine = if (d.hardLowPenaltyActive && longTermAbs >= 1)
                "\nLong term: basal & ISF reduced by ~${longTermAbs}% at this hour — will ease back as BG stabilises"
            else
                "\nLong term: learning paused during recovery — resumes in ${minsLeft}min"
            "Aggressiveness: ${"%.3f".format(d.aggressiveness)}  Circ ceiling: ${"%.3f".format(d.circCeil)}\n" +
                "⚠ Low recovery active — ${elapsedMins}min of ${windowMins}min elapsed ($minsLeft min left)\n" +
                "Short term: TBR capped at ${tbrPct}% — holding back insulin during recovery\n" +
                "SMBs: ${if (smbUnlockIn > 0) "blocked for ~${smbUnlockIn}min more" else "restored ✓"}" +
                hardLowNote + longTermLine + rollerNote
        } else if (d.bgWentLow) {
            val actuallyBelowGuard = d.currentBgMgdl > 0.0 && d.currentBgMgdl < d.lowGuardMgdl
            val hardLowNote  = if (d.hardLowPenaltyActive)
                "\nAggressiveness ceiling cut by 20% — recovers as BG stabilises near target."
            else ""
            val longTermLine = if (d.hardLowPenaltyActive && longTermAbs >= 1)
                "\nLong term: basal & ISF reduced by ~${longTermAbs}% at this hour — will ease back as BG stabilises"
            else
                "\nLong term: learning paused — resumes once recovery window completes"
            if (actuallyBelowGuard) {
                "Aggressiveness: ${"%.3f".format(d.aggressiveness)}  Circ ceiling: ${"%.3f".format(d.circCeil)}\n" +
                    "⚠ BG is below low guard — insulin delivery limited\n" +
                    "Short term: holding insulin until BG recovers above low guard" +
                    hardLowNote + longTermLine
            } else {
                "Aggressiveness: ${"%.3f".format(d.aggressiveness)}  Circ ceiling: ${"%.3f".format(d.circCeil)}\n" +
                    "⚡ BG recovering — waiting for rebound window to activate\n" +
                    "Short term: insulin delivery resuming as BG stabilises above low guard" +
                    hardLowNote + longTermLine
            }
            hardLowNote + longTermLine
        } else if (shortTermAbs < 5 && longTermAbs < 5) {
            "Aggressiveness: ${"%.3f".format(d.aggressiveness)}  Circ ceiling: ${"%.3f".format(d.circCeil)}\n" +
                "Insulin levels look balanced at this hour.\n" +
                "Short term: within 5% — no adjustment needed\n" +
                "Long term: profile within 5% of target — dialled in"
        } else {
            val shortLine = when {
                shortTermAbs < 5  -> "Short term: balanced (within 5%)"
                shortTermPct > 0  -> "Short term: pulling out ~${shortTermAbs}% insulin right now (ceiling=${"%.0f".format(d.circCeil * 100)}%)"
                else              -> "Short term: adding ~${shortTermAbs}% extra insulin right now"
            }
            val longLine = when {
                longTermAbs < 2   -> "Long term: profile close to target — still watching"
                longTermPct > 0   -> "Long term: profile permanently ${longTermDir} by ~${longTermAbs}% at this hour"
                else              -> "Long term: profile permanently ${longTermDir} by ~${longTermAbs}% at this hour"
            }
            val statusLine = when {
                shortTermAbs >= 10 && longTermAbs < shortTermAbs ->
                    "Still learning — long term will catch up as pattern repeats"
                shortTermAbs < 5 && longTermAbs >= 5 ->
                    "Dialling in — short term nearly balanced, long term corrections holding"
                else -> "Monitoring — will continue adjusting each fasting cycle"
            }
            "Aggressiveness: ${"%.3f".format(d.aggressiveness)}  Circ ceiling: ${"%.3f".format(d.circCeil)}\n" +
                "$shortLine\n$longLine\n$statusLine"
        }
        val aggrPrimary = when {
            !isFasting          -> "Aggressiveness locked — meal mode active"
            d.inReboundWindow   -> "⚠ Recovering from low — insulin held back"
            d.bgWentLow && d.currentBgMgdl > 0.0 && d.currentBgMgdl < d.lowGuardMgdl -> "⚠ Below low guard — waiting for recovery"
            d.bgWentLow         -> "⚡ BG recovered — rebound window activating"
            else                -> aggrDesc(d.aggressiveness)
        }
        addRow(c, aggrPrimary, aggrDetail, when {
            d.inReboundWindow || d.bgWentLow -> Color.parseColor("#FFFB8C00")
            !isFasting -> Color.parseColor("#FF888888")
            else -> aggrColor
        })

        // Pre-bolus 1 — only shown when in meal mode and a dose was delivered
        if (d.mealMode != "Fasting" && d.activeDoseU != null && d.activeDoseU > 0.0) {
            addRow(c, "Pre-bolus 1 — delivered ${"%.2f".format(d.activeDoseU)}U",
                   primaryColor = Color.parseColor("#FF43A047"))
        }

        // Pre-bolus 2 — show delivered amount once fired
        if (d.mealMode != "Fasting" && d.activePb2DoseU != null && d.activePb2DoseU > 0.0) {
            addRow(c, "Pre-bolus 2 — delivered ${"%.2f".format(d.activePb2DoseU)}U",
                   "Second bolus delivered as scheduled.",
                   Color.parseColor("#FF43A047"))
        } else if (d.pb2Status.isNotEmpty() || d.pb2GateData != null) {
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

        // Pre-bolus 3 — show delivered amount once fired
        if (d.mealMode != "Fasting" && d.activePb3DoseU != null && d.activePb3DoseU > 0.0) {
            addRow(c, "Pre-bolus 3 — delivered ${"%.2f".format(d.activePb3DoseU)}U",
                   "Third bolus delivered (late-meal cover).",
                   Color.parseColor("#FF43A047"))
        } else if (d.pb3Status.isNotEmpty() || d.pb3GateData != null) {
            val gate3    = d.pb3GateData
            val isActive3 = d.pb3Status.contains("active")
            val pb3Primary = when {
                d.pb3Status.contains("waiting for PB2") -> "Pre-bolus 3 — waiting for PB2 to fire"
                d.pb3Status.contains("due")             -> "Pre-bolus 3 — ready to deliver now"
                Regex("""(\d+)m""").containsMatchIn(d.pb3Status) -> {
                    val mins = Regex("""(\d+)m""").find(d.pb3Status)?.groupValues?.get(1)
                    "Pre-bolus 3 — delivers in ${mins}m"
                }
                gate3 != null -> "Pre-bolus 3 — waiting for safety gates"
                else          -> "Pre-bolus 3"
            }
            val pb3Sub = if (d.pb3Status.contains("waiting for PB2"))
                "Timer starts when PB2 delivers. If PB2 is cancelled, PB3 cancels too."
            else null
            addRow(c, pb3Primary, pb3Sub,
                   primaryColor = if (isActive3) Color.parseColor("#FF43A047") else Color.parseColor("#FF64B5F6"))

            if (gate3 != null) {
                val isMmol3 = gate3.isMmol
                fun fmtBg3(mgdl: Double)    = if (isMmol3) "%.1f mmol".format(mgdl / 18.0) else "%.0f mg/dL".format(mgdl)
                fun fmtDelta3(mgdl: Double) = if (isMmol3) "%+.2f mmol".format(mgdl / 18.0) else "%+.1f mg/dL".format(mgdl)
                fun fmtIob3(u: Double)      = "%.2fU".format(u)

                val minBgMgdl3   = MealOverrideManager.MIN_BG_FOR_PB2_MGDL
                val targetMgdl3  = gate3.profileTargetMgdl
                val effectiveMin3 = maxOf(minBgMgdl3, targetMgdl3)
                val bgOk3        = gate3.bgMgdl > effectiveMin3
                val bgDiff3      = if (bgOk3) "(+${fmtBg3(gate3.bgMgdl - effectiveMin3)} above target)"
                else "(${fmtBg3(effectiveMin3 - gate3.bgMgdl)} below target — waiting)"
                addGateRow(c, "BG: ${fmtBg3(gate3.bgMgdl)}  $bgDiff3",
                           "Must be above profile target ${fmtBg3(targetMgdl3)}", bgOk3)

                val maxAllowedIob3 = gate3.maxIobU * MealOverrideManager.MAX_IOB_HEADROOM_RATIO
                val iobOk3         = gate3.iobU < maxAllowedIob3
                val iobDetail3     = if (iobOk3) "${fmtIob3(gate3.iobU)} / ${fmtIob3(gate3.maxIobU)}  (${fmtIob3(maxAllowedIob3 - gate3.iobU)} headroom)"
                else "${fmtIob3(gate3.iobU)} / ${fmtIob3(gate3.maxIobU)}  (IOB too high — waiting)"
                addGateRow(c, "IOB: $iobDetail3", "Must be below ${(MealOverrideManager.MAX_IOB_HEADROOM_RATIO * 100).toInt()}% of max (${fmtIob3(maxAllowedIob3)})", iobOk3)

                val deltaOk3 = gate3.deltaMgdl >= MealOverrideManager.DELTA_INSTANT_BLOCK_MGDL
                addGateRow(c, "Delta: ${fmtDelta3(gate3.deltaMgdl)}  (${if (deltaOk3) "not falling fast" else "falling — waiting"})",
                           "Blocked below ${fmtDelta3(MealOverrideManager.DELTA_INSTANT_BLOCK_MGDL)}", deltaOk3)

                val shortOk3 = gate3.shortAvgDeltaMgdl >= MealOverrideManager.SHORT_AVG_DELTA_BLOCK_MGDL
                addGateRow(c, "15min avg: ${fmtDelta3(gate3.shortAvgDeltaMgdl)}  (${if (shortOk3) "trend stable" else "sustained fall — waiting"})",
                           "Blocked below ${fmtDelta3(MealOverrideManager.SHORT_AVG_DELTA_BLOCK_MGDL)}", shortOk3)
            }
        }

        val pfIsf  = if (d.isMmol) d.profileIsfMgdl / 18.0 else d.profileIsfMgdl
        val fIsf   = if (d.isMmol) d.finalIsfMgdl   / 18.0 else d.finalIsfMgdl
        val isfUnit = if (d.isMmol) "mmol/U" else "mg/dL/U"
        addRow(c, "Insulin sensitivity: ${"%.1f".format(fIsf)} $isfUnit",
               "Profile ${"%.1f".format(pfIsf)} ÷ multiplier ${"%.3f".format(d.isfMultiplier)} = ${"%.1f".format(fIsf)} $isfUnit\n" +
                   "Lower ISF = more insulin delivered per BG gap. Multiplier >1 reduces ISF, <1 raises ISF.")

        // Convert drift signal units if user is in mmol
        val basalSignalDisplay = if (d.isMmol && d.lastBasalSignal.contains("mgdlhr")) {
            d.lastBasalSignal.replace(Regex("([\\-\\d.]+) mgdlhr")) { mr ->
                val mgdl = mr.groupValues[1].toDoubleOrNull() ?: 0.0
                "${"%.2f".format(mgdl / 18.0)} mmol/L/hr"
            }
        } else {
            d.lastBasalSignal.replace("mgdlhr", "mg/dL/hr")
        }
        addRow(c, "Basal rate: ${fmtBasal(d.finalBasalU)}",
               "Profile ${fmtBasal(d.profileBasalU)} × multiplier ${"%.3f".format(d.basalMultiplier)} = ${fmtBasal(d.finalBasalU)}\n" +
                   "Background insulin rate keeping BG stable between meals.\n" +
                   "Last basal learning: $basalSignalDisplay")
    }

    // -- TIR bars --------------------------------------------------------------

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

        // -- Estimated HbA1c (ADAG formula: HbA1c% = (avgBG_mgdl + 46.7) / 28.7) --
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

    // -- Learning card ---------------------------------------------------------

    private fun updateLearningCard(d: SmartInsulinPlugin.FragmentData) {
        val c = _binding?.learningRows ?: return; c.removeAllViews()
        val ctx = context ?: return

        addRow(c, "SmartInsulin adapts to your body over time using real BG data.", primaryColor = Color.parseColor("#FFCCCCCC"))

        val (statePrimary, stateColor) = when {
            d.learningState.startsWith("off") || d.learningState == "Not Learning" ->
                Pair("Learning paused — ${d.learningState.removePrefix("off: ").trim().ifEmpty{"unknown"}}", Color.parseColor("#FFFB8C00"))
            d.learningState == "limited" || d.learningState.startsWith("Limited") ->
                Pair("Limited — meal mode active, only learning Peak/DIA", Color.parseColor("#FFFB8C00"))
            else -> Pair("Learning active", Color.parseColor("#FF43A047"))
        }
        addRow(c, statePrimary, "State: ${d.learningState}", stateColor)

        if (d.postMealLockoutMins > 0 && d.mealMode == "Fasting")
            addRow(c, "Post-meal pause: ${d.postMealLockoutMins}min remaining",
                   "BG data after meals is excluded from basal/ISF learning.", Color.parseColor("#FFFB8C00"))

        val actColor = when (d.activityLevel) {
            "Sedentary" -> Color.parseColor("#FF888888")
            "Light"     -> Color.parseColor("#FF43A047")
            "Moderate"  -> Color.parseColor("#FFFB8C00")
            "Heavy"     -> Color.parseColor("#FFEF6C00")
            else        -> Color.parseColor("#FF888888")
        }
        addRow(c, "Activity: ${d.activityLevel}", "High activity raises target and pauses learning.", actColor)

        if (d.cgmWarmup) addRow(c, "New sensor — learning paused for first 24h", null, Color.parseColor("#FFFB8C00"))

        addDivider(c)

        // Parse Nudge Status
        val nudgeParts      = d.lastAggrNudgeStatus.split("|")
        val nudgeState      = nudgeParts.getOrNull(0) ?: "INACTIVE"
        val nudgeActive     = nudgeState == "ACTIVE_HIGH" || nudgeState == "ACTIVE_LOW"
        val nudgeTrim       = nudgeState == "TRIM"
        val nudgePaused     = nudgeState == "PAUSED"

        // Positions in status string:
        // TRIM|dir|mag% -> position 1=dir, 2=mag
        // ACTIVE_LOW|dev|day|hour|sessionIsfMult|currentIsfMult|sessionBasMult|currentBasMult|COOLDOWN|reason|ISF_APPLIED|BAS_APPLIED
        // nudgeParts indices:
        // 0: State (ACTIVE_HIGH/LOW, TRIM, PAUSED, INACTIVE)
        // 1: Deviation (e.g. "+15%") or Trim Direction
        // 2: Day or Trim Pct
        // 3: Hour
        // 4: sessionIsfMult
        // 5: currentIsfMult
        // 6: sessionBasMult
        // 7: currentBasMult

        val profIsf = d.profileIsfMgdl
        val profBas = d.profileBasalU

        fun fmtIsf(mult: Double) = if (mult <= 0.0) "?" else if (d.isMmol) "${"%.2f".format(profIsf / mult / 18.0)} mmol" else "${"%.0f".format(profIsf / mult)} mg/dL"
        fun fmtBas(mult: Double) = if (mult <= 0.0) "?" else "${"%.3f".format(profBas * mult)} U/h"

        // --- SHORT-TERM LEARNING ---
        addSectionHeader(c, "Short-term learning")

        val stHeadline: String
        val stDetail: String
        val stColor: Int

        val shortPct  = ((1.0 - d.circCeil) * 100).roundToInt()
        val stAction  = if (shortPct > 0) "Reducing insulin" else if (shortPct < 0) "Adding insulin" else "Neutral"
        val stIcon    = if (shortPct > 0) "⬇️" else if (shortPct < 0) "⬆️" else "⏺"

        when {
            d.inReboundWindow -> {
                stHeadline = "$stIcon Recovering from low — $stAction"
                stDetail   = "TBR capped at ${(d.aggressiveness * 100).roundToInt()}% of normal to prevent stacking after a low."
                stColor    = Color.parseColor("#FFFB8C00")
            }
            d.bgWentLow -> {
                stHeadline = "⚠️ BG below low guard — Reducing insulin"
                stDetail   = "Waiting for BG to recover above low guard before resuming normal corrections."
                stColor    = Color.parseColor("#FFE53935")
            }
            nudgeTrim -> {
                val trimDir = nudgeParts.getOrNull(1) ?: ""
                val isHigh = trimDir == "ACTIVE_HIGH"
                val wasIsfMult = d.nudgeSessionIsfMgdl.takeIf { it > 0 }?.let { profIsf / it } ?: 1.0
                val wasBasMult = d.nudgeSessionBasalU.takeIf { it > 0 }?.let { it / profBas } ?: 1.0
                val nowIsfMult = d.isfMultiplier
                val nowBasMult = d.basalMultiplier

                val trimAction = if (isHigh) "Adding insulin" else "Reducing insulin"
                stHeadline = if (isHigh) "⬆️ Sustained high for ${d.trimMins}m — Adding insulin"
                else "⬇️ Sustained low for ${d.trimMins}m — Reducing insulin"
                stDetail   = "BG has been off target for ${d.trimMins}m. $trimAction to correct the trend.\n" +
                    "ISF was ${fmtIsf(wasIsfMult)} → now ${fmtIsf(nowIsfMult)}\n" +
                    "Basal was ${fmtBas(wasBasMult)} → now ${fmtBas(nowBasMult)}"
                stColor    = if (isHigh) Color.parseColor("#FF43A047") else Color.parseColor("#FFFB8C00")
            }
            nudgeActive && nudgeParts.getOrNull(9)?.contains("rollercoaster") == true -> {
                stHeadline = "⬇️ Rollercoaster detected — Reducing insulin"
                stDetail   = "Detected unstable swings (Rollercoaster #${d.consecutiveRollercoasters}). Capping insulin at ${(d.circCeil * 100).roundToInt()}% to stop the rollercoaster."
                stColor    = Color.parseColor("#FFFB8C00")
            }
            nudgeActive && nudgeParts.getOrNull(9)?.contains("soft low") == true -> {
                stHeadline = "⬇️ Soft low approach — Reducing insulin"
                stDetail   = "BG is falling fast with IOB on board. Reducing insulin to prevent a crash."
                stColor    = Color.parseColor("#FFFB8C00")
            }
            nudgeActive -> {
                val isHigh = nudgeState == "ACTIVE_HIGH"
                val sIsfMult = nudgeParts.getOrNull(4)?.toDoubleOrNull() ?: 1.0
                val cIsfMult = nudgeParts.getOrNull(5)?.toDoubleOrNull() ?: 1.0
                val sBasMult = nudgeParts.getOrNull(6)?.toDoubleOrNull() ?: 1.0
                val cBasMult = nudgeParts.getOrNull(7)?.toDoubleOrNull() ?: 1.0

                val nudgeAction = if (isHigh) "Adding insulin" else "Removing insulin"
                stHeadline = if (isHigh) "⬆️ Pattern detected — $nudgeAction" else "⬇️ Pattern detected — $nudgeAction"
                stDetail   = "Historical pattern shows you need ${if (isHigh) "more" else "less"} insulin at this hour.\n" +
                    "ISF was ${fmtIsf(sIsfMult)} → now ${fmtIsf(cIsfMult)}\n" +
                    "Basal was ${fmtBas(sBasMult)} → now ${fmtBas(cBasMult)}"
                stColor    = if (isHigh) Color.parseColor("#FF43A047") else Color.parseColor("#FFFB8C00")
            }
            nudgePaused -> {
                stHeadline = "⏸ Paused — ${nudgeParts.getOrNull(1) ?: "Learning suppressed"}"
                stDetail   = "Short-term adjustments paused while not in a clean fasting state."
                stColor    = Color.parseColor("#FF64B5F6")
            }
            else -> {
                stHeadline = "⏺ Stable — No short-term adjustments"
                stDetail   = "BG is responding normally. Loop is running at profile aggressiveness."
                stColor    = Color.parseColor("#FFCCCCCC")
            }
        }
        addRow(c, stHeadline, stDetail, stColor)

        // --- LONG-TERM LEARNING ---
        addDivider(c)
        addSectionHeader(c, "Long-term learning")

        // Use multipliers from status if active, otherwise use current plugin state
        val ltIsfMult = if (nudgeActive) nudgeParts.getOrNull(5)?.toDoubleOrNull() ?: d.isfMultiplier else d.isfMultiplier
        val ltBasMult = if (nudgeActive) nudgeParts.getOrNull(7)?.toDoubleOrNull() ?: d.basalMultiplier else d.basalMultiplier

        val day = if (nudgeActive) nudgeParts.getOrNull(2) ?: d.dayLabel else d.dayLabel
        val hour = if (nudgeActive) nudgeParts.getOrNull(3)?.toIntOrNull() ?: d.hour else d.hour
        val hourStr = if (hour != null) {
            if (d.isMmol) {
                val ampm = if (hour < 12) "AM" else "PM"
                val h12  = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
                "$h12:00 $ampm"
            } else "%02d:00".format(hour)
        } else "?"

        val isfComp = if (ltIsfMult > 1.001) "Increased needs" else if (ltIsfMult < 0.999) "Decreased needs" else "At profile"
        val basComp = if (ltBasMult > 1.001) "Increased needs" else if (ltBasMult < 0.999) "Decreased needs" else "At profile"

        val longTermPct   = ((1.0 - d.basalMultiplier) * 100).roundToInt()
        val ltAction = if (longTermPct > 0) "Reduced insulin needs" else if (longTermPct < 0) "Increased insulin needs" else "Neutral"

        addRow(c, "Profile updated: $ltAction", "Historical pattern at $hourStr on ${day}s.", Color.WHITE)

        val isfColor = when {
            ltIsfMult > 1.03 -> Color.parseColor("#FFFB8C00") // Orange (stronger/aggressive)
            ltIsfMult < 0.97 -> Color.parseColor("#FF64B5F6") // Blue (weaker/conservative)
            else -> Color.parseColor("#FFCCCCCC")
        }
        addRow(c, "ISF: ${fmtIsf(ltIsfMult)} ($isfComp)", null, isfColor)

        val basColor = when {
            ltBasMult > 1.03 -> Color.parseColor("#FFFB8C00") // Orange (stronger)
            ltBasMult < 0.97 -> Color.parseColor("#FF64B5F6") // Blue (weaker)
            else -> Color.parseColor("#FFCCCCCC")
        }
        addRow(c, "Basal: ${fmtBas(ltBasMult)} ($basComp)", null, basColor)

        // -- Feed-forward debug toggle -----------------------------------------
        val ffCtx = context ?: return
        c.addView(android.widget.TextView(ffCtx).apply {
            text = if (showFfDebug) "▲ Hide feed-forward debug" else "▼ Feed-forward debug (Accel + PredTrim)"
            textSize = 12f
            setTextColor(Color.parseColor("#FFCCCCCC"))
            setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
            setOnClickListener { showFfDebug = !showFfDebug; refreshStatus() }
        })
        if (showFfDebug) {
            addRow(c, "Acceleration (2nd derivative)", d.lastAccelDebug.ifEmpty { "(no data)" })
            addRow(c, "Predictive Basal Trim (60min projection)", d.lastPredTrimDebug.ifEmpty { "(no data)" })
            addRow(c, "Last basal signal", d.lastBasalSignal.ifEmpty { "(no data)" })
        }
    }

    // -- UAM card --------------------------------------------------------------

    private fun updateUamCard(d: SmartInsulinPlugin.FragmentData) {
        val c = _binding?.uamRows ?: return; c.removeAllViews()
        val line = d.uamStatusLine ?: ""

        // -- Active UAM meal mode — shown when a UAM mode is running ----------
        val isUamModeActive = d.mealMode.contains("UAM") || d.mealMode.contains("(UAM)")
        if (isUamModeActive) {
            val minsLeft = d.modeRemMins ?: 0
            addRow(c, "${d.mealMode} active — ${minsLeft}min remaining",
                   "Meal auto-detected. ISF and dosing adjusted for ${d.mealMode}.\nUAM detection resumes when this mode expires.",
                   Color.parseColor("#FF64B5F6"))
        } else {
            // -- UAM detection status ------------------------------------------
            val uamPart = line.substringBefore(" | P/F:").trim()
            val cleanUamPart = uamPart.removePrefix("[dirty] ").trim()

            val (primary, color) = when {
                cleanUamPart.startsWith("watching") -> Pair("BG rising — building confirmation streak ?", Color.parseColor("#FFFB8C00"))
                cleanUamPart.startsWith("last")     -> Pair("Meal auto-detected recently", Color.parseColor("#FF64B5F6"))
                cleanUamPart.startsWith("armed")    -> Pair("Watching for unannounced meals", Color.parseColor("#FF43A047"))
                cleanUamPart.startsWith("off")      -> {
                    val reason = cleanUamPart.substringAfter("off").removePrefix(" (").removeSuffix(")").trim()
                    Pair("Auto-detection off — $reason", Color.parseColor("#FF888888"))
                }
                else -> Pair("UAM status", Color.WHITE)
            }
            addRow(c, primary, uamPart.ifEmpty { null }, color)
        }

        // UAM thresholds — strip leading spaces for clean alignment
        val pfPart  = if (line.contains("P/F:")) line.substringAfter("P/F:").trim() else null
        val debugClean = d.uamDebug.lines()
            .filter { !it.trimStart().startsWith("P/F") }
            .joinToString("\n") { it.trimStart() }
            .trim()
        if (debugClean.isNotEmpty()) {
            addRow(c, "Detection thresholds",
                   debugClean + "\n\nUAM fires when BG rises consistently above the trigger\nthreshold during your configured meal windows.\n\n" +
                       "Clean window = fasting, normal thresholds apply.\n" +
                       "Dirty window = post-meal lockout active — thresholds are raised (~1.5×) to avoid\ndetecting fat/protein tail rises as a new meal.")
        }

        // -- P/F subheading ------------------------------------------------
        addDivider(c)
        addSectionHeader(c, "Protein / Fat Detection (P/F)")

        val (pfPrimary, pfColor) = when {
            pfPart == null                       -> Pair("P/F detection disabled", Color.parseColor("#FFCCCCCC"))
            pfPart.contains("off")               -> Pair("P/F off — ${pfPart.substringAfter("off").trim().removePrefix("(").removeSuffix(")")}", Color.parseColor("#FFCCCCCC"))
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
            setTextColor(Color.parseColor("#FFCCCCCC"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = (8 * dp).toInt() }
        })
    }

    // -- STFT card -------------------------------------------------------------

    private fun updateStftCard(d: SmartInsulinPlugin.FragmentData) {
        val c = _binding?.stftRows ?: return; c.removeAllViews()
        if (d.stftActive && d.stftStatus != null) {
            addRow(c, "Active — gently nudging the loop to correct",
                   d.stftStatus + "\n\nSoft Target Fine-Tune temporarily lowers the loop's internal target\nwhen fasting BG stays stuck above target. Resets when BG falls.",
                   Color.parseColor("#FFFB8C00"))
        } else {
            val inactiveReason = when {
                d.stftStatus?.contains("high temp target") == true ->
                    "Inactive — high temp target set"
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
                   Color.parseColor("#FFCCCCCC"))
        }
    }

    // -- Circadian table -------------------------------------------------------

    private data class CircRow(val hour: Int, val isfVal: Float, val basVal: Float, val ceil: Float, val confPct: Int)

    private fun parseCircRows(raw: String) = Regex("""[→►\s]\s*(\d{1,2})\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+(\d+)%""")
        .findAll(raw).mapNotNull { m -> CircRow(
            m.groupValues[1].toIntOrNull()   ?: return@mapNotNull null,
            m.groupValues[2].toFloatOrNull() ?: return@mapNotNull null,
            m.groupValues[3].toFloatOrNull() ?: return@mapNotNull null,
            m.groupValues[4].toFloatOrNull() ?: return@mapNotNull null,
            m.groupValues[5].toIntOrNull()   ?: return@mapNotNull null) }.toList()

    private fun confColor(p: Int)   = when { p >= 60 -> Color.parseColor("#FF43A047"); p >= 30 -> Color.parseColor("#FFFB8C00"); else -> Color.parseColor("#FFE53935") }
    // Ceiling colour: <0.95 = blue (restricted), >1.05 = amber (boosted), else grey
    private fun ceilColor(m: Float) = when { m > 1.05f -> Color.parseColor("#FFFB8C00"); m < 0.95f -> Color.parseColor("#FF64B5F6"); else -> Color.parseColor("#FFAAAAAA") }

    private fun updateCircadianTable(ignored: String) {
        val b = _binding ?: return; val ctx = context ?: return
        val cont = b.circadianRows; cont.removeAllViews()
        val todayDow   = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1
        val currentHr  = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val dayLabels  = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

        // -- Day selector row — Mon first, Sun last ---------------------------
        val selectorRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = (8*dp).toInt() }
        }
        val displayOrder = intArrayOf(1, 2, 3, 4, 5, 6, 0) // Mon, Tue, Wed, Thu, Fri, Sat, Sun
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

        // -- Table for selected day --------------------------------------------
        val raw   = smartInsulinPlugin.circadianDataForDay(selectedCircadianDow)
        val rows  = parseCircRows(raw); if (rows.isEmpty()) return

        // Profile reference values for colour coding
        val isMmolUnit  = smartInsulinPlugin.isMmol
        val profIsfMgdl = smartInsulinPlugin.profileIsfMgdl
        val profBasalU  = smartInsulinPlugin.profileBasalU
        val profIsfDisp = if (isMmolUnit && profIsfMgdl > 0) (profIsfMgdl / 18.0).toFloat() else profIsfMgdl.toFloat()

        // Colour logic:
        // ISF: grey = profile, orange = lower ISF (more aggressive), blue = higher ISF (less aggressive)
        // Basal: grey = profile, orange = higher basal (more aggressive), blue = lower basal (less aggressive)
        fun isfColor(v: Float): Int {
            if (profIsfDisp <= 0f) return Color.parseColor("#FFDDDDDD")
            val ratio = v / profIsfDisp
            return when {
                ratio < 0.97f -> Color.parseColor("#FFFB8C00")  // lower ISF = more aggressive = orange
                ratio > 1.03f -> Color.parseColor("#FF64B5F6")  // higher ISF = less aggressive = blue
                else          -> Color.parseColor("#FF888888")  // at profile = grey
            }
        }
        fun basColor(v: Float): Int {
            if (profBasalU <= 0.0) return Color.parseColor("#FFDDDDDD")
            val ratio = v / profBasalU.toFloat()
            return when {
                ratio > 1.03f -> Color.parseColor("#FFFB8C00")  // higher basal = more aggressive = orange
                ratio < 0.97f -> Color.parseColor("#FF64B5F6")  // lower basal = less aggressive = blue
                else          -> Color.parseColor("#FF888888")  // at profile = grey
            }
        }
        cont.addView(TextView(ctx).apply {
            text = "Hourly multipliers learned from your BG patterns.\nISF and Bas = values the loop actually delivers for that hour.\nCeil = aggressiveness cap — lower = more conservative.\nConf = confidence — how much real data collected. Green =60%, amber =30%, red <30%."
            textSize = 11f; setTextColor(Color.parseColor("#FFDDDDDD"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = (8 * dp).toInt() }
        })
        val isfHeader  = if (isMmolUnit) "ISF mmol" else "ISF mg/dL"
        val basHeader  = "Basal U/h"
        val headerRow  = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = (4*dp).toInt() }
        }
        fun hcell(t: String, w: Float) = TextView(ctx).apply {
            text = t; textSize = 10f; setTextColor(Color.parseColor("#FFCCCCCC"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
        }
        headerRow.addView(hcell("Hr", 1f))
        headerRow.addView(hcell(isfHeader, 2f))
        headerRow.addView(hcell(basHeader, 2f))
        headerRow.addView(hcell("Ceil", 2f))
        headerRow.addView(hcell("Conf", 3f))
        cont.addView(headerRow)

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
            rowL.addView(cell(if (isCur) "→${row.hour}" else "  ${row.hour}", 1f, if (isCur) Color.WHITE else Color.parseColor("#FFCCCCCC"), isCur))
            rowL.addView(cell(if (isMmolUnit) "%.2f".format(row.isfVal) else "%.1f".format(row.isfVal), 2f, isfColor(row.isfVal)))
            rowL.addView(cell("%.3f".format(row.basVal), 2f, basColor(row.basVal)))
            rowL.addView(cell("%.3f".format(row.ceil),   2f, ceilColor(row.ceil)))
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

    // -- Profiles card ---------------------------------------------------------

    private fun updateProfilesCard(d: SmartInsulinPlugin.FragmentData) {
        val b = _binding ?: return; val c = b.profileRows; c.removeAllViews(); val ctx = context ?: return

        addRow(c, "Learned peak and duration per meal type. Green = learned, amber = learning, grey = using profile values.", primaryColor = Color.parseColor("#FFCCCCCC"))
        // -- Tracker status row (#27) ------------------------------------------
        val (profStatusPrimary, profStatusColor) = when {
            d.profileLearningStatus.startsWith("off") ->
                "Learning paused" to Color.parseColor("#FFFB8C00")
            d.profileLearningStatus.contains("tracker=idle") ->
                "Watching for new bolus" to Color.parseColor("#FF43A047")
            else ->
                "Tracking active bolus curve" to Color.parseColor("#FF64B5F6")
        }
        val profStatusDetail = d.profileLearningStatus
            .replace("off: ", "")
            .replace("tracker=idle", "Waiting for IOB spike (=0.3U) to begin tracking kinetics.")
            .replace("tracker=waiting_peak", "Tracking: waiting for IOB to peak...")
            .replace("tracker=tracking_nadir", "Tracking: waiting for BG nadir...")
            .replace("tracker=confirming", "Tracking: confirming recovery from nadir...")
        addRow(c, profStatusPrimary, profStatusDetail, profStatusColor)
        addDivider(c)

        d.profilesRawStatus.lines().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.trim().split(":"); if (parts.size < 2) return@forEach
            val name     = parts[0].trim(); val info = parts.drop(1).joinToString(":").trim()
            val n        = Regex("""n=(\d+)""").find(info)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val activeMealMode = d.mealMode
            val isActive = if (activeMealMode.contains("(UAM)", ignoreCase = true)) {
                name.contains("(UAM)", ignoreCase = true) &&
                    activeMealMode.contains(name.substringBefore(" ("), ignoreCase = true)
            } else {
                !name.contains("(UAM)", ignoreCase = true) &&
                    activeMealMode.contains(name, ignoreCase = true)
            }
            // Colour from learning status regardless of active state
            val col  = when { n >= 5 -> Color.parseColor("#FF43A047"); n >= 1 -> Color.parseColor("#FFFB8C00"); else -> Color.parseColor("#FFCCCCCC") }
            val note = when { n == 0 -> "  (using profile values — not enough data yet)"; n < 5 -> "  (still learning)"; else -> "" }
            val prefix = if (isActive) "→ " else "  "
            c.addView(TextView(ctx).apply {
                text = "$prefix$name"; textSize = 13f; setTextColor(col)
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.topMargin = (4*dp).toInt() }
            })
            c.addView(TextView(ctx).apply {
                text = info + note; textSize = 11f; setTextColor(Color.parseColor("#FFDDDDDD"))
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