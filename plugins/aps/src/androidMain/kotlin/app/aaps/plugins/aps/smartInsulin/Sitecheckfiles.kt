package app.aaps.plugins.aps.smartInsulin

import android.content.Context
import android.os.Environment
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.FileListProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The files of [SiteCheck]:
 *  - the CSV log, one row per judged meal, so its limits can be checked against pods the user knows were
 *    good or bad. File: Documents/AAPS/SmartInsulin/site_check_log.csv, next to the meal absorption log.
 *    BG and falls are in mmol/L.
 *  - a one-off baseline import: [BASELINE_IMPORT_FILE] in the "extra" folder of the AAPS directory the user
 *    chose. Android only lets the app read a file it did not write itself from that folder.
 */
@SingleIn(AppScope::class)
class SiteCheckFiles @Inject constructor(
    private val context: Context,
    private val fileListProvider: FileListProvider,
    private val aapsLogger: AAPSLogger
) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

    private val logFile: File get() {
        val aapsDocs = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "AAPS")
        val dir = File(aapsDocs, "SmartInsulin")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "site_check_log.csv")
    }

    fun log(e: SiteCheck.Episode) {
        try {
            val file = logFile
            val isNew = !file.exists()
            file.appendText(buildString {
                if (isNew) appendLine(HEADER)
                appendLine(row(e))
            })
        } catch (ex: Exception) {
            aapsLogger.error(LTag.APS, "SiteCheckFiles write failed: ${ex.message}")
        }
    }

    /** The text of the baseline import file, or null when there is none. The file is deleted once read. */
    fun takeBaselineImport(): String? = try {
        val doc = fileListProvider.ensureExtraDirExists()?.findFile(BASELINE_IMPORT_FILE)
        doc?.let {
            val text = context.contentResolver.openInputStream(it.uri)?.use { s -> s.readBytes().decodeToString() }
            it.delete()
            text
        }
    } catch (ex: Exception) {
        aapsLogger.error(LTag.APS, "SiteCheckFiles baseline import failed: ${ex.message}")
        null
    }

    internal fun row(e: SiteCheck.Episode): String {
        val start = Date(e.startMs)
        val pod = Date(e.siteChangeMs)
        return listOf(
            dateFormat.format(start),
            timeFormat.format(start),
            "${dateFormat.format(pod)} ${timeFormat.format(pod)}",
            "%.1f".format(Locale.US, e.podAgeHours),
            e.mode.replace(",", " "),
            "%.1f".format(Locale.US, e.startBgMgdl / 18.0),
            "%.1f".format(Locale.US, e.peakBgMgdl / 18.0),
            e.minutesToPeak.toString(),
            e.risingLoadedMin.toString(),
            "%.2f".format(Locale.US, e.extraIobAtPeakU),
            "%.1f".format(Locale.US, e.fall2hMgdl / 18.0),
            e.fallPerUnitMmol?.let { "%.2f".format(Locale.US, it) } ?: "",
            e.slow.toString(),
            e.noLanding.toString(),
            e.suspectsOnPod.toString(),
            e.notify.toString(),
            e.learning.toString()
        ).joinToString(",")
    }

    companion object {

        private val HEADER = listOf(
            "date", "start_time", "pod_change", "pod_age_h", "mode", "start_bg", "peak_bg", "minutes_to_peak",
            "rising_loaded_min", "extra_iob_at_peak_u", "fall_2h", "fall_per_u",
            "slow", "no_landing", "suspects_on_pod", "notified", "learning"
        ).joinToString(",")

        const val BASELINE_IMPORT_FILE = "site_check_baseline.json"
    }
}
