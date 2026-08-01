package app.aaps.plugins.aps.smartInsulin

import android.content.Context
import android.os.Environment
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Appends one CSV row per completed meal/UAM episode, for the user to retrieve and paste back
 * for validating [MealAbsorptionTracker]'s estimates against what was actually eaten.
 * File location: Documents/AAPS/SmartInsulin/meal_absorption_log.csv — the same
 * Documents/AAPS root FileListProviderImpl already uses for its plain-File "results" location
 * (resultPath), so it doesn't depend on the SAF export-directory permission being set up.
 */
@Singleton
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

    fun log(episode: CompletedMealEpisode) {
        try {
            val file = logFile
            val isNew = !file.exists()
            file.appendText(buildString {
                if (isNew) appendLine(HEADER)
                appendLine(rowToCsv(episode))
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
            "%.1f".format(e.estimatedGrams)
        ).joinToString(",")
    }

    companion object {
        private val HEADER = listOf("date", "start_time", "mode", "duration_min", "estimated_grams_carb_equiv").joinToString(",")
    }
}
