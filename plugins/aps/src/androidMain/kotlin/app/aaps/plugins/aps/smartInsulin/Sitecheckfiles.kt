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
 *  - the night log, one row per judged night, so the night limits can be checked the same way. File:
 *    Documents/AAPS/SmartInsulin/site_check_nights.csv.
 *  - a one-off baseline import: [BASELINE_IMPORT_FILE] in the "extra" folder of the AAPS directory the user
 *    chose. Android only lets the app read a file it did not write itself from that folder.
 *  - the saved copies of the learning ([LearningSnapshots]), in the app's own storage. They are only
 *    there to undo learning from a bad site, so they are not put where the user would see them.
 */
@SingleIn(AppScope::class)
class SiteCheckFiles @Inject constructor(
    private val context: Context,
    private val fileListProvider: FileListProvider,
    private val aapsLogger: AAPSLogger
) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

    private fun docsFile(name: String): File {
        val aapsDocs = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "AAPS")
        val dir = File(aapsDocs, "SmartInsulin")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, name)
    }

    private val snapshotFile: File get() = File(context.filesDir, SNAPSHOT_FILE)

    fun log(e: SiteCheck.Episode) = append("site_check_log.csv", HEADER, row(e))

    fun logNight(n: SiteCheck.Night) = append("site_check_nights.csv", NIGHT_HEADER, nightRow(n))

    private fun append(name: String, header: String, row: String) {
        try {
            val file = docsFile(name)
            val isNew = !file.exists()
            file.appendText(buildString {
                if (isNew) appendLine(header)
                appendLine(row)
            })
        } catch (ex: Exception) {
            aapsLogger.error(LTag.APS, "SiteCheckFiles write failed: ${ex.message}")
        }
    }

    fun readSnapshots(): String? = try {
        snapshotFile.takeIf { it.exists() }?.readText()
    } catch (ex: Exception) {
        aapsLogger.error(LTag.APS, "SiteCheckFiles snapshot read failed: ${ex.message}")
        null
    }

    /** Written to a new file first and then renamed, so a crash half way does not leave a broken file. */
    fun writeSnapshots(text: String) {
        try {
            val tmp = File(context.filesDir, "$SNAPSHOT_FILE.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(snapshotFile)) aapsLogger.error(LTag.APS, "SiteCheckFiles snapshot rename failed")
        } catch (ex: Exception) {
            aapsLogger.error(LTag.APS, "SiteCheckFiles snapshot write failed: ${ex.message}")
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

    internal fun nightRow(n: SiteCheck.Night): String {
        val start = Date(n.startMs)
        val pod = Date(n.siteChangeMs)
        return listOf(
            dateFormat.format(start),
            "${dateFormat.format(pod)} ${timeFormat.format(pod)}",
            "%.1f".format(Locale.US, n.podAgeHours),
            "%.1f".format(Locale.US, n.startBgMgdl / 18.0),
            "%.1f".format(Locale.US, n.endBgMgdl / 18.0),
            "%.1f".format(Locale.US, n.lowestMgdl / 18.0),
            "%.2f".format(Locale.US, n.insulinAboveBasalU),
            n.fallPerUnitMmol?.let { "%.2f".format(Locale.US, it) } ?: "",
            "%.2f".format(Locale.US, n.highShare),
            n.suspect.toString(),
            n.suspectsOnPod.toString(),
            n.notify.toString()
        ).joinToString(",")
    }

    companion object {

        private val NIGHT_HEADER = listOf(
            "date", "pod_change", "pod_age_h", "start_bg", "end_bg", "lowest_bg", "insulin_above_basal_u", "fall_per_u",
            "high_share", "suspect", "suspects_on_pod", "notified"
        ).joinToString(",")

        private const val SNAPSHOT_FILE = "si_learning_snapshots.json"

        private val HEADER = listOf(
            "date", "start_time", "pod_change", "pod_age_h", "mode", "start_bg", "peak_bg", "minutes_to_peak",
            "rising_loaded_min", "extra_iob_at_peak_u", "fall_2h", "fall_per_u",
            "slow", "no_landing", "suspects_on_pod", "notified", "learning"
        ).joinToString(",")

        const val BASELINE_IMPORT_FILE = "site_check_baseline.json"
    }
}
