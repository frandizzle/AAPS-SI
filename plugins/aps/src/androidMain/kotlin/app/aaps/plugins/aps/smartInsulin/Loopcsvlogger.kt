package app.aaps.plugins.aps.smartInsulin

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

/**
 * Writes one CSV row per loop cycle for offline analysis.
 * File location: <external files>/SmartInsulin/loop_YYYY-MM-DD.csv
 * Rolling 30-day retention.
 *
 * Columns:
 * timestamp, hour, bg_mmol, delta, iob, cob, meal_mode,
 * isf_used_mmol, basal_used, aggr_used,
 * circadian_isf_mult, circadian_basal_mult, circadian_aggr_ceil,
 * smb_u, tbr_rate, zone,
 * rebound_active, rebound_elapsed_min
 *
 * NOTE: this class is not currently invoked from the loop — nothing constructs a [Row]. It is
 * kept as the offline-analysis hook. The doc block previously also listed a bg_30min_ago column
 * that HEADER has never emitted; removed rather than left describing a column that does not exist.
 */
@SingleIn(AppScope::class)
class LoopCsvLogger @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    private val dateFormat  = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val tsFormat    = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    private val logDir: File get() {
        val dir = File(context.getExternalFilesDir(null), "SmartInsulin")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun todayFile(): File =
        File(logDir, "loop_${dateFormat.format(Date())}.csv")

    // ── Public API ────────────────────────────────────────────────────────────

    data class LogRow(
        val timestampMs:       Long,
        val bgMmol:            Double,
        val delta:             Double,
        val iob:               Double,
        val cob:               Double,
        val mealMode:          String,
        val isfUsedMmol:       Double,
        val basalUsed:         Double,
        val aggrUsed:          Double,
        val circIsfMult:       Double,
        val circBasalMult:     Double,
        val circAggrCeil:      Double,
        val smbU:              Double,
        val tbrRate:           Double,
        val zone:              String,
        val reboundActive:     Boolean,
        val reboundElapsedMin: Int
    )

    fun log(row: LogRow) {
        try {
            val file = todayFile()
            val isNew = !file.exists()
            file.appendText(buildString {
                if (isNew) appendLine(HEADER)
                appendLine(rowToCsv(row))
            })
            pruneOldFiles()
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "LoopCsvLogger write failed: ${e.message}")
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun rowToCsv(r: LogRow): String {
        val cal = Calendar.getInstance().apply { timeInMillis = r.timestampMs }
        return listOf(
            tsFormat.format(Date(r.timestampMs)),
            cal.get(Calendar.HOUR_OF_DAY),
            "%.2f".format(r.bgMmol),
            "%.2f".format(r.delta),
            "%.2f".format(r.iob),
            "%.1f".format(r.cob),
            r.mealMode,
            "%.2f".format(r.isfUsedMmol),
            "%.3f".format(r.basalUsed),
            "%.3f".format(r.aggrUsed),
            "%.3f".format(r.circIsfMult),
            "%.3f".format(r.circBasalMult),
            "%.3f".format(r.circAggrCeil),
            "%.2f".format(r.smbU),
            "%.3f".format(r.tbrRate),
            r.zone,
            r.reboundActive,
            r.reboundElapsedMin
        ).joinToString(",")
    }

    private fun pruneOldFiles() {
        try {
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            logDir.listFiles()
                ?.filter { it.name.startsWith("loop_") && it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "LoopCsvLogger prune failed: ${e.message}")
        }
    }

    companion object {
        private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000  // 30 days

        private val HEADER = listOf(
            "timestamp", "hour", "bg_mmol", "delta", "iob", "cob", "meal_mode",
            "isf_used_mmol", "basal_used", "aggr_used",
            "circadian_isf_mult", "circadian_basal_mult", "circadian_aggr_ceil",
            "smb_u", "tbr_rate", "zone",
            "rebound_active", "rebound_elapsed_min"
        ).joinToString(",")
    }
}