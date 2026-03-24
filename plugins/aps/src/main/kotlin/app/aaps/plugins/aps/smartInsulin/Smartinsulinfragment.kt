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

    override fun onResume()      { super.onResume();  handler.post(updater) }
    override fun onPause()       { super.onPause();   handler.removeCallbacks(updater) }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    private fun refreshStatus() {
        if (_binding == null) return
        val d = smartInsulinPlugin.fragmentData()
        binding.tvStatus.text = smartInsulinPlugin.statusSummary()
        updateGeneralCard(d)
        updateTirBars(d.tirRawLine)
        updateLearningCard(d)
        updateUamCard(d)
        updateStftCard(d)
        updateCircadianTable(d.circadianRawStatus)
        updateProfilesCard(d.profilesRawStatus)
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
                fontFamily = "monospace"
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
        addRow(c, aggrDesc(d.aggressiveness),
               "Aggressiveness: ${"%.3f".format(d.aggressiveness)}  Circ ceiling: ${"%.3f".format(d.circCeil)}\n" +
                   "Based on your BG history over the last 24h.", aggrColor)

        val pfIsf = d.profileIsfMgdl / 18.0; val fIsf = d.finalIsfMgdl / 18.0
        addRow(c, "Insulin sensitivity: ${"%.1f".format(fIsf)} mmol/U",
               "Profile ${"%.1f".format(pfIsf)} × multiplier ${"%.3f".format(d.isfMultiplier)} = ${"%.1f".format(fIsf)} mmol/U\n" +
                   "How much 1U of insulin lowers your BG.")

        addRow(c, "Basal rate: ${fmtBasal(d.finalBasalU)}",
               "Profile ${fmtBasal(d.profileBasalU)} × multiplier ${"%.3f".format(d.basalMultiplier)} = ${fmtBasal(d.finalBasalU)}\n" +
                   "Background insulin rate keeping BG stable between meals.")

        if (d.inReboundWindow) {
            val note = if (d.softLandingBypass) " — meal detection still active" else " — meal detection paused"
            addRow(c, "⚠ Recovery after low — ${d.reboundMins}min elapsed$note",
                   (if (d.minBgDuringLow < Double.MAX_VALUE) "Lowest BG: ${"%.1f".format(d.minBgDuringLow/18.0)} mmol  IOB: ${"%.2f".format(d.iobAtLowTime)}U\n" else "") +
                       "SMBs are reduced for 60min after a low to prevent overcorrection.",
                   Color.parseColor("#FFE53935"))
        } else if (d.bgWentLow) {
            addRow(c, "⚠ Recent low — watching recovery",
                   if (d.secondLowOccurred) "Second low — full lockout active"
                   else "BG crossed below low guard. Rebound protection will arm when BG recovers.",
                   Color.parseColor("#FFFB8C00"))
        }

        if (d.pb2Status.isNotEmpty()) addRow(c, "Pre-bolus 2: ${d.pb2Status}")
    }

    // ── TIR bars ──────────────────────────────────────────────────────────────

    private data class TirValues(val inPct: Float, val highPct: Float, val lowPct: Float)

    private fun parseTir(s: String, prefix: String): TirValues? {
        val m = Regex("""$prefix:(\d+)%in/(\d+)%hi/(\d+)%lo""").find(s) ?: return null
        return TirValues(m.groupValues[1].toFloatOrNull() ?: return null,
                         m.groupValues[2].toFloatOrNull() ?: return null,
                         m.groupValues[3].toFloatOrNull() ?: return null)
    }

    private fun applyTirBar(lv: View, iv: View, hv: View, pt: TextView, tir: TirValues?) {
        if (tir == null) {
            (lv.layoutParams as LinearLayout.LayoutParams).weight = 0f
            (iv.layoutParams as LinearLayout.LayoutParams).weight = 1f
            (hv.layoutParams as LinearLayout.LayoutParams).weight = 0f
            iv.setBackgroundColor(Color.parseColor("#FF9E9E9E"))
            lv.requestLayout(); iv.requestLayout(); hv.requestLayout()
            pt.text = "Not enough data yet — needs ~2 hours of fasting readings"; return
        }
        iv.setBackgroundColor(Color.parseColor("#FF43A047"))
        (lv.layoutParams as LinearLayout.LayoutParams).weight = tir.lowPct
        (iv.layoutParams as LinearLayout.LayoutParams).weight = tir.inPct
        (hv.layoutParams as LinearLayout.LayoutParams).weight = tir.highPct
        lv.requestLayout(); iv.requestLayout(); hv.requestLayout()
        pt.text = "${tir.inPct.roundToInt()}% in range  •  ${tir.highPct.roundToInt()}% high  •  ${tir.lowPct.roundToInt()}% low"
    }

    private fun updateTirBars(tirLine: String) {
        val b = _binding ?: return
        applyTirBar(b.tirFastingLow, b.tirFastingIn, b.tirFastingHigh, b.tvTirFastingPct, parseTir(tirLine, "Fasting"))
        applyTirBar(b.tirMealLow,    b.tirMealIn,    b.tirMealHigh,    b.tvTirMealPct,    parseTir(tirLine, "Meal"))
    }

    // ── Learning card ─────────────────────────────────────────────────────────

    private fun updateLearningCard(d: SmartInsulinPlugin.FragmentData) {
        val c = _binding?.learningRows ?: return; c.removeAllViews()
        val (primary, color) = when {
            d.learningState.startsWith("off") || d.learningState == "Not Learning" ->
                Pair("Learning paused — ${d.learningState.removePrefix("off: ").trim().ifEmpty{"unknown"}}", Color.parseColor("#FFFB8C00"))
            d.learningState == "limited" || d.learningState.startsWith("Limited") ->
                Pair("Learning limited — meal active (insulin timing only)", Color.parseColor("#FFFB8C00"))
            else -> Pair("Learning active", Color.parseColor("#FF43A047"))
        }
        addRow(c, primary,
               "SmartInsulin continuously refines your insulin timing, basal rate, and aggressiveness.\nState: ${d.learningState}",
               color)
        if (d.postMealLockoutMins > 0) addRow(c, "Post-meal pause: ${d.postMealLockoutMins}min remaining",
                                              "BG data after meals is excluded from basal/ISF learning to avoid\nfood-related changes corrupting fasting models.", Color.parseColor("#FFFB8C00"))
        val actColor = if (d.activityLevel == "Sedentary") Color.parseColor("#FF888888") else Color.parseColor("#FF64B5F6")
        addRow(c, "Activity: ${d.activityLevel}",
               "HR: ${d.avgHrBpm} bpm avg  •  Steps: ${d.steps5min}/5min\nHigh activity raises your target and pauses learning.", actColor)
        if (d.cgmWarmup) addRow(c, "New sensor — learning paused for first 24h",
                                "Day-1 readings can be noisy. Learning resumes automatically after 24h.", Color.parseColor("#FFFB8C00"))
    }

    // ── UAM card ──────────────────────────────────────────────────────────────

    private fun updateUamCard(d: SmartInsulinPlugin.FragmentData) {
        val c = _binding?.uamRows ?: return; c.removeAllViews()
        val line = d.uamStatusLine ?: ""
        val (primary, color) = when {
            line.contains("watching") -> Pair("BG rising — building confirmation streak ↑", Color.parseColor("#FFFB8C00"))
            line.contains("last")     -> Pair("Meal auto-detected recently", Color.parseColor("#FF64B5F6"))
            line.contains("off")      -> Pair("Auto-detection off — outside hours or new sensor", Color.parseColor("#FF888888"))
            line.contains("armed")    -> Pair("Watching for unannounced meals", Color.parseColor("#FF43A047"))
            else                      -> Pair("UAM status", Color.WHITE)
        }
        addRow(c, primary, line.ifEmpty { null }, color)
        addRow(c, "Detection settings",
               d.uamDebug.trim() + "\n\nUAM fires when BG rises consistently above the trigger\nthreshold during your configured meal windows.")
    }

    // ── STFT card ─────────────────────────────────────────────────────────────

    private fun updateStftCard(d: SmartInsulinPlugin.FragmentData) {
        val c = _binding?.stftRows ?: return; c.removeAllViews()
        if (d.stftActive && d.stftStatus != null) {
            addRow(c, "Active — gently nudging the loop to correct",
                   d.stftStatus + "\n\nSoft Target Fine-Tune temporarily lowers the loop's internal target\nwhen fasting BG stays stuck above target. Resets when BG falls.",
                   Color.parseColor("#FFFB8C00"))
        } else {
            addRow(c, "Inactive — BG is responding normally",
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

    private fun updateCircadianTable(raw: String) {
        val b = _binding ?: return; val ctx = context ?: return
        val rows = parseCircRows(raw); if (rows.isEmpty()) return
        val cur = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val cont = b.circadianRows; cont.removeAllViews()
        rows.forEach { row ->
            val isCur = row.hour == cur
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

    private fun updateProfilesCard(raw: String) {
        val b = _binding ?: return; val c = b.profileRows; c.removeAllViews(); val ctx = context ?: return
        raw.lines().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.trim().split(":"); if (parts.size < 2) return@forEach
            val name = parts[0].trim(); val info = parts[1].trim()
            val n = Regex("""n=(\d+)""").find(info)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val col = when { n >= 5 -> Color.parseColor("#FF43A047"); n >= 1 -> Color.parseColor("#FFFB8C00"); else -> Color.parseColor("#FF888888") }
            val note = when { n == 0 -> "  (using defaults)"; n < 5 -> "  (still learning)"; else -> "" }
            c.addView(TextView(ctx).apply { text = name; textSize = 13f; setTextColor(col); setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = (4*dp).toInt() } })
            c.addView(TextView(ctx).apply { text = info + note; textSize = 11f; setTextColor(Color.parseColor("#FF888888")); fontFamily = "monospace"
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = (2*dp).toInt() } })
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