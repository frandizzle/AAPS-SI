package app.aaps.plugins.aps.smartInsulin

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Site / pod health card: what [SiteCheck] thinks of the current pod, the meal it is watching now,
 * and every meal it has judged on this pod.
 */
internal fun buildSiteCheckCard(s: SiteCheck.Status, isMmol: Boolean, nowMs: Long): SiCardContent =
    SiCardBuilder().also { it.fillSiteCheckCard(s, isMmol, nowMs) }.build()

private fun SiCardBuilder.fillSiteCheckCard(s: SiteCheck.Status, isMmol: Boolean, nowMs: Long) {
    fun bg(mgdl: Double) = if (isMmol) "%.1f".format(mgdl / 18.0) else "%.0f".format(mgdl)
    fun change(mgdl: Double) = if (isMmol) "%.1f mmol".format(mgdl / 18.0) else "%.0f mg/dL".format(mgdl)
    // Falls per unit are kept in mmol; mg/dL users see them in mg/dL.
    fun perU(mmol: Double) = if (isMmol) "%.2f mmol per U".format(mmol) else "%.0f mg/dL per U".format(mmol * 18.0)
    val age = s.podAgeHours?.let { podAge(it) }
    val suspectMeals = s.meals.count { it.suspect }

    when {
        s.siteChangeMs == null ->
            addRow("No pod change recorded", "Meals are checked once AAPS has a pod change on record.", SiTone.MUTED)
        s.silenced ->
            addRow("Site marked fine", "You said the site is fine. No more questions on this pod.", SiTone.MUTED)
        s.notified -> {
            addRow("Check the site",
                   "$suspectMeals of ${s.meals.size} meals on this pod looked like a leaking or tunnelling site: " +
                       "BG kept rising with insulin on board, or did not come down.",
                   SiTone.CRITICAL)
            addAction("Site is fine", SiAction.SITE_IS_FINE)
        }
        s.limits == null ->
            addRow("Learning your normal",
                   "${s.baselineMeals} of ${SiteCheck.BASELINE_MIN_MEALS} meals from ${s.baselinePods} of ${SiteCheck.BASELINE_MIN_PODS} finished pods. " +
                       "Meals are measured and logged, but not judged until then.",
                   SiTone.INFO)
        s.waiting ->
            addRow("Waiting", "Pod is $age old. Meals are checked from ${SiteCheck.MIN_POD_AGE_HOURS.roundToInt()} h.", SiTone.INFO)
        suspectMeals > 0 ->
            addRow("Watching",
                   "$suspectMeals of ${s.meals.size} meals on this pod looked slow. Another one and you will be asked to check the site.",
                   SiTone.WARNING)
        s.meals.isEmpty() ->
            addRow("No meals checked yet", "Pod is $age old. A meal is checked 2 h after its peak.", SiTone.INFO)
        else ->
            addRow("Normal", "Pod is $age old · ${s.meals.size} meals checked, none suspect", SiTone.GOOD)
    }

    s.live?.let { l ->
        val detail = if (l.risen && nowMs - l.peakMs >= 15 * 60_000L)
            "Peaked at ${bg(l.peakBgMgdl)} at ${time(l.peakMs, nowMs)} · checking how it comes down until ${time(l.peakMs + SiteCheck.LANDING_WINDOW_MS, nowMs)}"
        else
            "Up ${change(l.bgMgdl - l.startBgMgdl)} so far · still rising ${l.risingLoadedMin} min with insulin on board " +
                "(${"%.1f".format(l.extraIobU)} U extra)"
        addRow("Watching now: ${l.mode} since ${time(l.startMs, nowMs)}", detail, SiTone.INFO)
    }

    if (s.meals.isNotEmpty()) {
        addSectionHeader("Meals on this pod")
        s.meals.asReversed().forEach { e ->
            val verdict = when {
                e.learning            -> "measured"
                e.slow && e.noLanding -> "slow, did not come down"
                e.slow                -> "slow"
                e.noLanding           -> "did not come down"
                else                  -> "normal"
            }
            val landing = e.fallPerUnitMmol?.let { "fell ${change(e.fall2hMgdl)} in 2 h (${perU(it)})" }
                ?: "fell ${change(e.fall2hMgdl)} in 2 h (too little insulin to judge the landing)"
            addRow("${e.mode} ${time(e.startMs, nowMs)} — $verdict",
                   "Rose ${change(e.peakBgMgdl - e.startBgMgdl)} · still rising ${e.risingLoadedMin} min with " +
                       "${"%.1f".format(e.extraIobAtPeakU)} U extra · $landing",
                   when {
                       e.learning -> SiTone.MUTED
                       e.suspect  -> SiTone.WARNING
                       else       -> SiTone.GOOD
                   })
        }
    }

    val limitsText = s.limits?.let { l ->
        val landing = if (l.landingPerU != null && l.typicalFallPerU != null && l.fallSpreadPerU != null)
            "Did not come down: ${perU(l.landingPerU)} or less in 2 h (your normal ${perU(l.typicalFallPerU)} ± ${"%.2f".format(if (isMmol) l.fallSpreadPerU else l.fallSpreadPerU * 18.0)})."
        else
            "Did not come down: not judged yet, it needs more meals with ${SiteCheck.NO_LANDING_MIN_EXTRA_IOB_U.roundToInt()} U or more on board."
        "Your limits, learned from ${s.baselineMeals} meals on finished pods: " +
            "slow = still rising ${l.slowMin.roundToInt()} min or more with insulin on board " +
            "(your normal ${l.typicalRisingMin.roundToInt()} ± ${l.risingSpreadMin.roundToInt()} min), " +
            "asks at once from ${l.verySlowMin.roundToInt()} min. $landing"
    } ?: "Limits are learned from your own meals on finished pods. A pod that asked to have its site checked is left out, unless you said the site was fine."
    addNoteBlock(
        "$limitsText\n" +
            "Log: ${s.totalMeals} meals on ${s.totalPods} pods in Documents/AAPS/SmartInsulin/site_check_log.csv."
    )
}

private fun podAge(hours: Double): String {
    val h = hours.toInt()
    return if (h >= 24) "${h / 24} d ${h % 24} h" else "$h h"
}

/** "12:30" today, "Tue 12:30" on an earlier day. */
private fun time(ms: Long, nowMs: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.DAY_OF_YEAR)
    val today = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.DAY_OF_YEAR)
    val pattern = if (day == today) "HH:mm" else "EEE HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}
