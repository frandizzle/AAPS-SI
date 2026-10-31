package app.aaps.plugins.aps.smartInsulin

import android.content.Context
import android.os.Environment
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

/**
 * Appends one CSV row per completed meal/UAM episode, for the user to retrieve and paste back
 * for validating [MealAbsorptionTracker]'s estimates against what was actually eaten.
 * File location: Documents/AAPS/SmartInsulin/meal_absorption_log.csv — the same
 * Documents/AAPS root FileListProviderImpl already uses for its plain-File "results" location
 * (resultPath), so it doesn't depend on the SAF export-directory permission being set up.
 */
@SingleIn(AppScope::class)
class MealAbsorptionCsvLogger @Inject constructor(
    private val context:    Context,
    private val aapsLogger: AAPSLogger
) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

    private val logDir: File get() {
        val aapsDocs = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "AAPS")
        val dir = File(aapsDocs, "SmartInsulin")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private val logFile: File get() = File(logDir, "meal_absorption_log.csv")
    private val estimateFile: File get() = File(logDir, "meal_estimate_log.csv")

    fun log(episode: CompletedMealEpisode) = append(logFile, HEADER, rowToCsv(episode))

    /**
     * One row per estimated meal (photo, description or barcode) once it is judged, next to what the meal
     * tracker saw. See `MealEstimateCalibrationImpl`.
     */
    fun logEstimate(
        modeStartMs: Long, carbsG: Double, proteinG: Double, fatG: Double, carbEquivG: Double, trackerG: Double, ratio: Double, factorAfter: Double
    ) {
        val date = Date(modeStartMs)
        val row = listOf(
            dateFormat.format(date), timeFormat.format(date),
            "%.1f".format(Locale.US, carbsG), "%.1f".format(Locale.US, proteinG), "%.1f".format(Locale.US, fatG),
            "%.1f".format(Locale.US, carbEquivG), "%.1f".format(Locale.US, trackerG),
            "%.2f".format(Locale.US, ratio), "%.2f".format(Locale.US, factorAfter)
        ).joinToString(",")
        append(estimateFile, ESTIMATE_HEADER, row)
    }

    private fun append(file: File, header: String, row: String) {
        try {
            val isNew = !file.exists()
            file.appendText(buildString {
                if (isNew) appendLine(header)
                appendLine(row)
            })
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "MealAbsorptionCsvLogger write failed: ${e.message}")
        }
    }

    private fun rowToCsv(e: CompletedMealEpisode): String {
        val date = Date(e.startMs)
        val durationMins = e.durationMs / 60_000
        return listOf(
            dateFormat.format(date),
            timeFormat.format(date),
            e.mode.label,
            durationMins.toString(),
            "%.1f".format(e.estimatedGrams),
            e.riseMins.toString(),
            e.plateauMins.toString(),
            e.tailMins.toString(),
            "%.1f".format(e.riseGrams),
            "%.1f".format(e.plateauGrams),
            "%.1f".format(e.tailGrams)
        ).joinToString(",")
    }

    companion object {
        private val HEADER = listOf(
            "date", "start_time", "mode", "duration_min", "estimated_grams_carb_equiv",
            "rise_min", "plateau_min", "tail_min", "rise_g", "plateau_g", "tail_g"
        ).joinToString(",")
        private val ESTIMATE_HEADER = listOf(
            "date", "start_time", "carbs_g", "protein_g", "fat_g", "carb_equiv_g", "tracker_g", "ratio", "factor_after"
        ).joinToString(",")
    }
}
