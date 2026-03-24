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
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSmartInsulinBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnResetAggression.setOnClickListener {
            confirmReset("Reset aggressiveness score to 1.0?") {
                smartInsulinPlugin.resetAggression()
                refreshStatus()
            }
        }
        binding.btnResetBasal.setOnClickListener {
            confirmReset("Reset basal + circadian basal learners to 1.0?") {
                smartInsulinPlugin.resetBasal()
                refreshStatus()
            }
        }
        binding.btnResetCircadian.setOnClickListener {
            confirmReset("Reset all circadian (ISF/basal/aggr) hourly learning?") {
                smartInsulinPlugin.resetCircadian()
                refreshStatus()
            }
        }
        binding.btnResetProfiles.setOnClickListener {
            confirmReset("Reset all learned insulin profiles back to defaults?") {
                smartInsulinPlugin.resetProfiles()
                refreshStatus()
            }
        }
        binding.btnResetAll.setOnClickListener {
            confirmReset("Reset ALL learners? This cannot be undone.") {
                smartInsulinPlugin.resetAllLearners()
                refreshStatus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(updater)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updater)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    private fun refreshStatus() {
        if (_binding == null) return
        val status = smartInsulinPlugin.statusSummary()
        binding.tvStatus.text = status
        updateTirBars(status)
        updateCircadianTable(status)
    }

    // ── TIR bars ──────────────────────────────────────────────────────────────

    private data class TirValues(val inPct: Float, val highPct: Float, val lowPct: Float)

    private fun parseTir(status: String, prefix: String): TirValues? {
        val pattern = Regex("""$prefix:(\d+)%in/(\d+)%hi/(\d+)%lo""")
        val match   = pattern.find(status) ?: return null
        return TirValues(
            inPct   = match.groupValues[1].toFloatOrNull() ?: return null,
            highPct = match.groupValues[2].toFloatOrNull() ?: return null,
            lowPct  = match.groupValues[3].toFloatOrNull() ?: return null
        )
    }

    private fun applyTirBar(
        lowView: View, inView: View, highView: View,
        pctText: TextView, tir: TirValues?
    ) {
        if (tir == null) {
            (lowView.layoutParams  as LinearLayout.LayoutParams).weight = 0f
            (inView.layoutParams   as LinearLayout.LayoutParams).weight = 1f
            (highView.layoutParams as LinearLayout.LayoutParams).weight = 0f
            inView.setBackgroundColor(Color.parseColor("#FF9E9E9E"))
            lowView.requestLayout(); inView.requestLayout(); highView.requestLayout()
            pctText.text = "Insufficient data"
            return
        }
        inView.setBackgroundColor(Color.parseColor("#FF43A047"))
        (lowView.layoutParams  as LinearLayout.LayoutParams).weight = tir.lowPct
        (inView.layoutParams   as LinearLayout.LayoutParams).weight = tir.inPct
        (highView.layoutParams as LinearLayout.LayoutParams).weight = tir.highPct
        lowView.requestLayout(); inView.requestLayout(); highView.requestLayout()
        pctText.text = "${tir.inPct.roundToInt()}% in range  •  " +
            "${tir.highPct.roundToInt()}% high  •  " +
            "${tir.lowPct.roundToInt()}% low"
    }

    private fun updateTirBars(status: String) {
        val b = _binding ?: return
        val fasting = parseTir(status, "Fasting")
        val meal    = parseTir(status, "Meal")
        applyTirBar(b.tirFastingLow, b.tirFastingIn, b.tirFastingHigh, b.tvTirFastingPct, fasting)
        applyTirBar(b.tirMealLow,    b.tirMealIn,    b.tirMealHigh,    b.tvTirMealPct,    meal)
    }

    // ── Circadian table ───────────────────────────────────────────────────────

    private data class CircRow(
        val hour: Int, val isfMult: Float,
        val basMult: Float, val ceil: Float, val confPct: Int
    )

    private fun parseCircadianRows(status: String): List<CircRow> {
        val pattern = Regex("""[►\s]\s*(\d{1,2})\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+(\d+)%""")
        return pattern.findAll(status).mapNotNull { m ->
            CircRow(
                hour    = m.groupValues[1].toIntOrNull()   ?: return@mapNotNull null,
                isfMult = m.groupValues[2].toFloatOrNull() ?: return@mapNotNull null,
                basMult = m.groupValues[3].toFloatOrNull() ?: return@mapNotNull null,
                ceil    = m.groupValues[4].toFloatOrNull() ?: return@mapNotNull null,
                confPct = m.groupValues[5].toIntOrNull()   ?: return@mapNotNull null
            )
        }.toList()
    }

    private fun confColor(pct: Int): Int = when {
        pct >= 60 -> Color.parseColor("#FF43A047")  // green
        pct >= 30 -> Color.parseColor("#FFFB8C00")  // amber
        else      -> Color.parseColor("#FFE53935")  // red
    }

    private fun multColor(mult: Float): Int = when {
        mult > 1.05f -> Color.parseColor("#FFFB8C00")  // amber — elevated
        mult < 0.95f -> Color.parseColor("#FF64B5F6")  // blue — reduced
        else         -> Color.parseColor("#FFAAAAAA")  // grey — neutral
    }

    private fun updateCircadianTable(status: String) {
        val b   = _binding ?: return
        val ctx = context  ?: return
        val rows = parseCircadianRows(status)
        if (rows.isEmpty()) return

        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val dp          = ctx.resources.displayMetrics.density
        val container   = b.circadianRows
        container.removeAllViews()

        rows.forEach { row ->
            val isCurrent = row.hour == currentHour

            val rowLayout = LinearLayout(ctx).apply {
                orientation  = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (2 * dp).toInt() }
                if (isCurrent) {
                    setBackgroundColor(Color.parseColor("#22FFFFFF"))
                    setPadding((4 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt(), (2 * dp).toInt())
                }
            }

            fun cell(
                text: String, weight: Float,
                color: Int = Color.parseColor("#FFDDDDDD"),
                bold: Boolean = false
            ) = TextView(ctx).apply {
                this.text = text
                textSize  = 12f
                setTextColor(color)
                if (bold || isCurrent) setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, weight
                )
            }

            val hourLabel = if (isCurrent) "►${row.hour}" else "  ${row.hour}"
            rowLayout.addView(cell(hourLabel,                      1f, if (isCurrent) Color.WHITE else Color.parseColor("#FFAAAAAA"), isCurrent))
            rowLayout.addView(cell("%.3f".format(row.isfMult),    2f, multColor(row.isfMult)))
            rowLayout.addView(cell("%.3f".format(row.basMult),    2f, multColor(row.basMult)))
            rowLayout.addView(cell("%.3f".format(row.ceil),       2f, multColor(row.ceil)))

            // Confidence mini-bar + label
            val confLayout = LinearLayout(ctx).apply {
                orientation  = LinearLayout.HORIZONTAL
                gravity      = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
            }
            val barTotal  = (40 * dp).toInt()
            val barHeight = (8 * dp).toInt()
            val filledW   = ((row.confPct / 100f) * barTotal).toInt()
            confLayout.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(filledW, barHeight)
                setBackgroundColor(confColor(row.confPct))
            })
            confLayout.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(barTotal - filledW, barHeight)
                setBackgroundColor(Color.parseColor("#FF444444"))
            })
            confLayout.addView(TextView(ctx).apply {
                text = " ${row.confPct}%"
                textSize = 11f
                setTextColor(confColor(row.confPct))
                if (isCurrent) setTypeface(null, Typeface.BOLD)
            })
            rowLayout.addView(confLayout)
            container.addView(rowLayout)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun confirmReset(message: String, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirm Reset")
            .setMessage(message)
            .setPositiveButton("Reset") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val REFRESH_MS = 10_000L
    }
}