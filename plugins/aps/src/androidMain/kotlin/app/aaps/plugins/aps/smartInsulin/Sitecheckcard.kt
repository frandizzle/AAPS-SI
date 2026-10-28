package app.aaps.plugins.aps.smartInsulin

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Site / pod health card: what [SiteCheck] thinks of the current pod, the meal and night it is watching
 * now, and every meal and night it has judged on this pod.
 */
internal fun buildSiteCheckCard(s: SiteCheck.Status, isMmol: Boolean, nowMs: Long, siteBadPending: Boolean = false): SiCardContent =
    SiCardBuilder().also { it.fillSiteCheckCard(s, isMmol, nowMs, siteBadPending) }.build()

private fun SiCardBuilder.fillSiteCheckCard(s: SiteCheck.Status, isMmol: Boolean, nowMs: Long, siteBadPending: Boolean) {
    fun bg(mgdl: Double) = if (isMmol) "%.1f".format(mgdl / 18.0) else "%.0f".format(mgdl)
    fun change(mgdl: Double) = if (isMmol) "%.1f mmol".format(mgdl / 18.0) else "%.0f mg/dL".format(mgdl)
    // Falls per unit are kept in mmol; mg/dL users see them in mg/dL.
    fun perU(mmol: Double) = if (isMmol) "%.2f mmol per U".format(mmol) else "%.0f mg/dL per U".format(mmol * 18.0)
    val age = s.podAgeHours?.let { podAge(it) }
    val suspectMeals = s.meals.count { it.suspect }

    when {
        s.siteChangeMs == null ->
            addRow("No pod change recorded", "Meals are checked once AAPS has a pod change on record.", SiTone.MUTED)
        s.bad ->
            addRow("Site marked bad", "You said this site was bad. No more questions on this pod, and its meals are not used as normal.", SiTone.CRITICAL)
        s.silenced ->
            addRow("Site marked fine", "You said the site is fine. No more questions on this pod.", SiTone.MUTED)
        s.notified -> {
            addRow("Check the site",
                   "$suspectMeals of ${count(s.meals.size, "meal")} on this pod looked like a leaking or tunnelling site: " +
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
                   "$suspectMeals of ${count(s.meals.size, "meal")} on this pod looked slow. Another one and you will be asked to check the site.",
                   SiTone.WARNING)
        s.meals.isEmpty() ->
            addRow("No meals checked yet", "Pod is $age old. A meal is checked 2 h after its peak.", SiTone.INFO)
        else ->
            addRow("Normal", "Pod is $age old · ${count(s.meals.size, "meal")} checked, none suspect", SiTone.GOOD)
    }

    if (siteBadPending) addRow("Site was bad: waiting for the loop", "The learning is undone on the next loop run, within 5 min.", SiTone.INFO)

    s.live?.let { l ->
        val detail = if (l.risen && nowMs - l.peakMs >= 15 * 60_000L)
            "Peaked at ${bg(l.peakBgMgdl)} at ${time(l.peakMs, nowMs)} · checking how it comes down until ${time(l.peakMs + SiteCheck.LANDING_WINDOW_MS, nowMs)}"
        else
            "Up ${change(l.bgMgdl - l.startBgMgdl)} so far · still rising ${l.risingLoadedMin} min with insulin on board " +
                "(${"%.1f".format(l.extraIobU)} U extra)"
        addRow("Watching now: ${l.mode} since ${time(l.startMs, nowMs)}", detail, SiTone.INFO)
    }

    s.liveNight?.let { n ->
        addRow("Watching tonight since ${time(n.startMs, nowMs)}",
               "${"%.1f".format(n.insulinAboveBasalU)} U above basal so far · BG high ${pct(n.highShare)} of the time · " +
                   "${if (n.fallMgdl >= 0) "down" else "up"} ${change(abs(n.fallMgdl))}",
               SiTone.INFO)
    }

    // Only when it is newer than the last checked meal, so an old reason does not stay up.
    s.lastDropped?.takeIf { d -> s.meals.none { it.startMs >= d.startMs } }?.let { d ->
        addRow("Not checked: ${d.mode} ${time(d.startMs, nowMs)}", "${siteCheckDropText(d, isMmol)} (${time(d.timeMs, nowMs)})", SiTone.MUTED)
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

    if (s.nights.isNotEmpty()) {
        addSectionHeader("Nights on this pod")
        s.nights.asReversed().forEach { n ->
            val landing = n.fallPerUnitMmol?.let { "BG ${bg(n.startBgMgdl)} → ${bg(n.endBgMgdl)} (${perU(it)})" }
                ?: "BG ${bg(n.startBgMgdl)} → ${bg(n.endBgMgdl)}"
            addRow("Night ${time(n.startMs, nowMs)} — ${if (n.suspect) "insulin did not work as usual" else "normal"}",
                   "${"%.1f".format(n.insulinAboveBasalU)} U above basal · $landing · high ${pct(n.highShare)} of the night",
                   if (n.suspect) SiTone.WARNING else SiTone.GOOD)
        }
    }

    if (s.siteChangeMs != null || s.previousPodMs != null) addAction("This site was bad", SiAction.SITE_WAS_BAD)

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
    val nightText = "Nights from 00:00 to 06:00 are checked too: it asks when the loop gave " +
        "${"%.0f".format(SiteCheck.NIGHT_MIN_EXTRA_U)} U or more above basal, BG came down ${perU(SiteCheck.NIGHT_FALL_PER_U_MAX)} or less, " +
        "and BG was ${change(SiteCheck.NIGHT_HIGH_ABOVE_TARGET_MGDL)} or more above target for half the night. " +
        "These limits are a first guess."
    addNoteBlock(
        "$limitsText\n$nightText\n" +
            "\"This site was bad\" undoes the learning since the site went bad, and keeps that pod out of what is normal.\n" +
            "Log: ${count(s.totalMeals, "meal")} on ${count(s.totalPods, "pod")} in Documents/AAPS/SmartInsulin/site_check_log.csv, " +
            "nights in site_check_nights.csv."
    )
}

/** Why a meal was not checked, as one sentence. Also used for the journal. */
internal fun siteCheckDropText(d: SiteCheck.Dropped, isMmol: Boolean): String = when (d.reason) {
    SiteCheck.DropReason.DATA_GAP      -> "No CGM data for ${d.gapMin} min"
    SiteCheck.DropReason.ACTIVITY      -> "An activity session was running"
    SiteCheck.DropReason.EXERCISE      -> "BG fell more than insulin explains, so exercise was suspected"
    SiteCheck.DropReason.CGM_WARMUP    -> "The CGM was warming up"
    SiteCheck.DropReason.NO_POD_CHANGE -> "No pod change on record"
    SiteCheck.DropReason.NO_RISE       ->
        "BG did not rise ${if (isMmol) "%.0f mmol".format(SiteCheck.MIN_RISE_MGDL / 18.0) else "%.0f mg/dL".format(SiteCheck.MIN_RISE_MGDL)} " +
            "within ${SiteCheck.RISE_WINDOW_MS / 3_600_000} h, so it was not a meal"
    SiteCheck.DropReason.MEAL_AT_NIGHT -> "A meal mode other than P/F ran in the night"
    SiteCheck.DropReason.STARTED_HIGH  ->
        "BG was already ${if (isMmol) "%.0f mmol".format(SiteCheck.MAX_START_BG_MGDL / 18.0) else "%.0f mg/dL".format(SiteCheck.MAX_START_BG_MGDL)} " +
            "or more when it started"
}

/** "40 %". */
private fun pct(share: Double) = "${(share * 100).roundToInt()} %"

/** "1 meal", "2 meals". */
private fun count(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

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
