package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Site check: notices when insulin acts much more slowly than usual on the current pod, which is what a
 * leaking or tunnelling site looks like, and asks the user to check the site. It never changes dosing.
 *
 * A leaking site works well enough when little insulin is needed. It shows when a meal needs a lot: BG
 * keeps rising while insulin goes in, the loop keeps adding more, and later the insulin that pooled acts
 * all at once. So each meal or UAM rise is judged after it has peaked, on two numbers:
 *
 *  - **Still rising while loaded**: minutes BG kept rising, before the peak, while the insulin on board
 *    was already [LOADED_EXTRA_IOB_U] or more above where the meal started.
 *  - **Landing**: how far BG fell in the 2 hours after the peak, per unit of extra insulin on board at
 *    the peak. Only judged with [NO_LANDING_MIN_EXTRA_IOB_U] or more extra.
 *
 * **What counts as unusual is learned from the user's own meals**, because it depends on their insulin,
 * meals and loop settings. From the middle (median) of the baseline meals and their normal spread (median
 * distance from the middle):
 *  - slow: still rising [SLOW_SPREADS] spreads above the middle, never under [SLOW_FLOOR_MIN] min,
 *  - very slow: [VERY_SLOW_SPREADS] spreads above it, never under [VERY_SLOW_FLOOR_MIN] min,
 *  - did not land: a fall [LANDING_SPREADS] spreads under the middle, never over [LANDING_CEILING_PER_U].
 * The median is used because a few bad meals hardly move it. The baseline only takes meals from finished
 * pods - the current pod never moves its own limits - and not from a pod that asked to have its site
 * checked unless the user said the site was fine, so a leaking pod does not teach it that leaking is
 * normal. Until it has [BASELINE_MIN_MEALS] meals from [BASELINE_MIN_PODS] pods it is learning: meals are
 * measured and logged, but nothing is called suspect. The factors were fitted to one user's month: their 8
 * clean pods (still rising 25 ± 15 min, falling 0.54 ± 0.14 mmol per unit) give 90 min, 120 min and 0.2
 * mmol per unit, which caught the two pods they knew had leaked and asked on none of the clean ones.
 *
 * A meal that is slow or did not land is "suspect". One meal is usually not enough to say anything - a
 * big meal without entered carbs looks the same - so the user is asked to check the site after two
 * suspect meals on the same pod, after one meal that is both, or after one very slow meal. Once more if
 * two further suspect meals follow, then never again on that pod. "Site is fine" silences it for the pod.
 *
 * Meals are not judged in the first [MIN_POD_AGE_HOURS] hours of a pod (a new site often runs high on its
 * own, and new pod boost changes the dose then), with suspected exercise, while the CGM is warming up, or
 * across a gap in the data. A meal that is dropped for one of these reasons is kept as [Dropped], so the
 * card and the journal can say why it was not checked.
 *
 * The meal being watched is saved on every cycle, so an app restart or update does not lose it. A restart
 * that leaves a gap in the data still drops it, like any other gap.
 *
 * **Nights.** A site can also go bad without a meal: BG stays a little high all night while the loop
 * gives far more than basal, and little of it works. So each night from 00:00 to 06:00 is judged too,
 * with fixed limits (not learned): it is suspect when the loop gave [NIGHT_MIN_EXTRA_U] or more above
 * profile basal, BG fell [NIGHT_FALL_PER_U_MAX] mmol per unit of that or less, and BG was more than
 * [NIGHT_HIGH_ABOVE_TARGET_MGDL] above target for at least [NIGHT_HIGH_SHARE] of the night. A suspect
 * night asks at once. A night with a meal mode other than P/F is not judged, because food would explain
 * it. The limits were fitted to one user's 15 nights: they caught the one night with a bleeding site
 * (6.1 U above basal, 0.15 mmol per unit, high 74 % of the night) and none of the others, the closest of
 * which fell 0.45 mmol per unit. One bad night is not much to fit to, so these are a first guess.
 *
 * **"This site was bad"** is the user's word, for the current pod or the one before it. That pod's meals
 * never join the baseline, and are taken out again if they already did.
 */
class SiteCheck(private val sp: KeyValueStore) {

    /** One judged meal, for the CSV log and the notification. */
    data class Episode(
        val startMs: Long,
        val siteChangeMs: Long,
        val podAgeHours: Double,
        val mode: String,
        val startBgMgdl: Double,
        val peakBgMgdl: Double,
        val minutesToPeak: Int,
        val risingLoadedMin: Int,
        val extraIobAtPeakU: Double,
        val fall2hMgdl: Double,
        val slow: Boolean,
        val noLanding: Boolean,
        val suspectsOnPod: Int,
        val notify: Boolean,
        /** Judged while the baseline was still being learned: measured only, never suspect. */
        val learning: Boolean = false
    ) {
        val suspect: Boolean get() = slow || noLanding
        /** mmol of fall per unit of extra insulin; null when too little insulin was on board to judge. */
        val fallPerUnitMmol: Double? get() = if (extraIobAtPeakU >= NO_LANDING_MIN_EXTRA_IOB_U) fall2hMgdl / MGDL_PER_MMOL / extraIobAtPeakU else null

        internal fun toJson(): JSONObject = JSONObject()
            .put("start", startMs).put("pod", siteChangeMs).put("age", podAgeHours).put("mode", mode)
            .put("bg0", startBgMgdl).put("peak", peakBgMgdl).put("toPeak", minutesToPeak).put("loaded", risingLoadedMin)
            .put("iob", extraIobAtPeakU).put("fall", fall2hMgdl).put("slow", slow).put("noLanding", noLanding)
            .put("suspects", suspectsOnPod).put("notify", notify).put("learning", learning)

        internal companion object {

            fun fromJson(o: JSONObject) = Episode(
                startMs = o.getLong("start"), siteChangeMs = o.getLong("pod"), podAgeHours = o.getDouble("age"),
                mode = o.getString("mode"), startBgMgdl = o.getDouble("bg0"), peakBgMgdl = o.getDouble("peak"),
                minutesToPeak = o.getInt("toPeak"), risingLoadedMin = o.getInt("loaded"), extraIobAtPeakU = o.getDouble("iob"),
                fall2hMgdl = o.getDouble("fall"), slow = o.getBoolean("slow"), noLanding = o.getBoolean("noLanding"),
                suspectsOnPod = o.getInt("suspects"), notify = o.getBoolean("notify"), learning = o.optBoolean("learning", false)
            )
        }
    }

    /** One judged night, 00:00 to 06:00. */
    data class Night(
        val startMs: Long,
        val siteChangeMs: Long,
        val podAgeHours: Double,
        val startBgMgdl: Double,
        val endBgMgdl: Double,
        /** Insulin given above profile basal, boluses included. Can be negative when the loop gave less. */
        val insulinAboveBasalU: Double,
        /** Part of the night (0 to 1) with BG more than [NIGHT_HIGH_ABOVE_TARGET_MGDL] above target. */
        val highShare: Double,
        val lowestMgdl: Double,
        val suspect: Boolean,
        val suspectsOnPod: Int,
        val notify: Boolean
    ) {
        /** mmol of fall per unit above basal; null when the loop did not give more than basal. */
        val fallPerUnitMmol: Double? get() = if (insulinAboveBasalU > 0.0) (startBgMgdl - endBgMgdl) / MGDL_PER_MMOL / insulinAboveBasalU else null

        internal fun toJson(): JSONObject = JSONObject()
            .put("start", startMs).put("pod", siteChangeMs).put("age", podAgeHours).put("bg0", startBgMgdl).put("bg1", endBgMgdl)
            .put("ins", insulinAboveBasalU).put("high", highShare).put("low", lowestMgdl).put("suspect", suspect)
            .put("suspects", suspectsOnPod).put("notify", notify)

        internal companion object {

            fun fromJson(o: JSONObject) = Night(
                startMs = o.getLong("start"), siteChangeMs = o.getLong("pod"), podAgeHours = o.getDouble("age"),
                startBgMgdl = o.getDouble("bg0"), endBgMgdl = o.getDouble("bg1"), insulinAboveBasalU = o.getDouble("ins"),
                highShare = o.getDouble("high"), lowestMgdl = o.getDouble("low"), suspect = o.getBoolean("suspect"),
                suspectsOnPod = o.getInt("suspects"), notify = o.getBoolean("notify")
            )
        }
    }

    /** The night being watched now, for the card. */
    data class LiveNight(val startMs: Long, val insulinAboveBasalU: Double, val highShare: Double, val fallMgdl: Double)

    /** Why a meal or a night was not checked. */
    enum class DropReason { DATA_GAP, ACTIVITY, EXERCISE, CGM_WARMUP, NO_POD_CHANGE, NO_RISE, STARTED_HIGH, MEAL_AT_NIGHT }

    /** A meal that was started but not judged, or a meal mode that was not watched. [gapMin] is only set for a data gap. */
    data class Dropped(val timeMs: Long, val mode: String, val startMs: Long, val reason: DropReason, val gapMin: Int = 0) {

        internal fun toJson(): JSONObject = JSONObject()
            .put("time", timeMs).put("mode", mode).put("start", startMs).put("reason", reason.name).put("gap", gapMin)

        internal companion object {

            fun fromJson(o: JSONObject) = Dropped(
                timeMs = o.getLong("time"), mode = o.getString("mode"), startMs = o.getLong("start"),
                reason = DropReason.valueOf(o.getString("reason")), gapMin = o.optInt("gap", 0)
            )
        }
    }

    /** The meal being watched now, for the card. */
    data class Live(
        val mode: String,
        val startMs: Long,
        val startBgMgdl: Double,
        val peakBgMgdl: Double,
        val peakMs: Long,
        val bgMgdl: Double,
        val risingLoadedMin: Int,
        val extraIobU: Double,
        /** The rise is big enough to be a meal: it will be judged at [peakMs] + 2 h unless BG goes higher. */
        val risen: Boolean
    )

    /**
     * The limits learned from the baseline, with the middle and spread they came from (for the card).
     * [landingPerU] is null while there are too few meals with enough insulin to judge a landing.
     */
    data class Limits(
        val slowMin: Double,
        val verySlowMin: Double,
        val landingPerU: Double?,
        val typicalRisingMin: Double,
        val risingSpreadMin: Double,
        val typicalFallPerU: Double?,
        val fallSpreadPerU: Double?
    )

    /** Everything the card shows. [meals] is this pod's judged meals, oldest first. [limits] is null while learning. */
    data class Status(
        val siteChangeMs: Long?,
        val podAgeHours: Double?,
        val meals: List<Episode>,
        val suspects: Int,
        val notified: Boolean,
        val silenced: Boolean,
        val live: Live?,
        val totalMeals: Int,
        val totalPods: Int,
        val limits: Limits?,
        val baselineMeals: Int,
        val baselinePods: Int,
        /** The last meal on this pod that was not checked, or null. */
        val lastDropped: Dropped? = null,
        /** This pod's judged nights, oldest first. */
        val nights: List<Night> = emptyList(),
        val liveNight: LiveNight? = null,
        /** The user said this pod's site was bad. */
        val bad: Boolean = false,
        /** The pod before this one, or null when it is not known. */
        val previousPodMs: Long? = null
    ) {
        val waiting: Boolean get() = podAgeHours != null && podAgeHours < MIN_POD_AGE_HOURS
    }

    private data class Sample(val timeMs: Long, val bgMgdl: Double, val iobU: Double)

    private class Open(
        val startMs: Long,
        val startBgMgdl: Double,
        val startIobU: Double,
        val mode: String,
        val siteChangeMs: Long
    ) {
        var peakBgMgdl = startBgMgdl
        var peakMs = startMs
        var risingLoadedMin = 0.0
        var risingLoadedAtPeakMin = 0.0
        var maxIobU = startIobU
        var maxIobAtPeakU = startIobU
        var lowestSincePeakMgdl = startBgMgdl

        fun toJson(): JSONObject = JSONObject()
            .put("start", startMs).put("bg0", startBgMgdl).put("iob0", startIobU).put("mode", mode).put("pod", siteChangeMs)
            .put("peak", peakBgMgdl).put("peakAt", peakMs).put("loaded", risingLoadedMin).put("loadedAtPeak", risingLoadedAtPeakMin)
            .put("maxIob", maxIobU).put("maxIobAtPeak", maxIobAtPeakU).put("lowest", lowestSincePeakMgdl)

        companion object {

            fun fromJson(o: JSONObject) = Open(o.getLong("start"), o.getDouble("bg0"), o.getDouble("iob0"), o.getString("mode"), o.getLong("pod")).apply {
                peakBgMgdl = o.getDouble("peak")
                peakMs = o.getLong("peakAt")
                risingLoadedMin = o.getDouble("loaded")
                risingLoadedAtPeakMin = o.getDouble("loadedAtPeak")
                maxIobU = o.getDouble("maxIob")
                maxIobAtPeakU = o.getDouble("maxIobAtPeak")
                lowestSincePeakMgdl = o.getDouble("lowest")
            }
        }
    }

    private class OpenNight(val startMs: Long, val siteChangeMs: Long) {

        var insulinU = 0.0
        var minutes = 0.0
        var highMinutes = 0.0
        var lowestMgdl = Double.MAX_VALUE
        // The night's start and end BG are each the average of 3 readings, so one noisy reading does not decide.
        val firstBgs = mutableListOf<Double>()
        val lastBgs = ArrayDeque<Double>()

        fun add(bgMgdl: Double) {
            if (firstBgs.size < BG_AVERAGE_READINGS) firstBgs.add(bgMgdl)
            lastBgs.addLast(bgMgdl)
            while (lastBgs.size > BG_AVERAGE_READINGS) lastBgs.removeFirst()
            if (bgMgdl < lowestMgdl) lowestMgdl = bgMgdl
        }

        fun toJson(cycleMs: Long): JSONObject = JSONObject()
            .put("start", startMs).put("pod", siteChangeMs).put("ins", insulinU).put("min", minutes).put("high", highMinutes)
            .put("low", lowestMgdl).put("first", JSONArray(firstBgs)).put("last", JSONArray(lastBgs.toList())).put("cycle", cycleMs)

        companion object {

            fun fromJson(o: JSONObject) = OpenNight(o.getLong("start"), o.getLong("pod")).apply {
                insulinU = o.getDouble("ins")
                minutes = o.getDouble("min")
                highMinutes = o.getDouble("high")
                lowestMgdl = o.getDouble("low")
                val first = o.getJSONArray("first")
                repeat(first.length()) { firstBgs.add(first.getDouble(it)) }
                val last = o.getJSONArray("last")
                repeat(last.length()) { lastBgs.addLast(last.getDouble(it)) }
            }
        }
    }

    private val recent = ArrayDeque<Sample>()
    // The meal being watched and the last cycle time, restored so a restart does not lose the meal.
    // lastCycleMs comes back with it, so a restart with a long break is still seen as a gap.
    @Volatile private var open: Open? = null
    private var lastCycleMs = 0L

    init {
        try {
            val saved = sp.getString(K_OPEN, "")
            if (saved.isNotEmpty()) {
                val o = JSONObject(saved)
                open = Open.fromJson(o.getJSONObject("meal"))
                lastCycleMs = o.getLong("cycle")
            }
        } catch (_: Exception) {
            open = null
            lastCycleMs = 0L
        }
    }

    // The night being watched, restored like the meal. Its cycle time comes back too, so a restart with a
    // long break is seen as a gap even when no meal was open.
    @Volatile private var openNight: OpenNight? = null
    private var pendingNight: Night? = null

    init {
        try {
            val saved = sp.getString(K_NIGHT, "")
            if (saved.isNotEmpty()) {
                val o = JSONObject(saved)
                openNight = OpenNight.fromJson(o)
                lastCycleMs = maxOf(lastCycleMs, o.getLong("cycle"))
            }
        } catch (_: Exception) {
            openNight = null
        }
    }

    // The last meal not checked on this pod, for the card. pendingDrop is handed to the journal once.
    @Volatile private var lastDropped: Dropped? = try {
        sp.getString(K_DROPPED, "").takeIf { it.isNotEmpty() }?.let { Dropped.fromJson(JSONObject(it)) }
    } catch (_: Exception) {
        null
    }
    @Volatile private var pendingDrop: Dropped? = null
    // So a meal mode that starts too high is reported once, not on every cycle.
    private var startedHighNoted = false
    @Volatile private var lastBgMgdl = 0.0
    @Volatile private var lastIobU = 0.0

    // Per pod, stored so a restart does not ask again or forget a "site is fine".
    private var podMs = sp.getLong(K_POD, 0L)
    private var suspects = sp.getInt(K_SUSPECTS, 0)
    private var notifications = sp.getInt(K_NOTIFIED, 0)
    private var suspectsAtLastNotification = sp.getInt(K_SUSPECTS_AT_NOTIFIED, 0)
    private var silenced = sp.getBoolean(K_SILENCED, false)
    private var bad = sp.getBoolean(K_BAD, false)
    private var previousPodMs = sp.getLong(K_PREVIOUS_POD, 0L)
    private val podMeals: MutableList<Episode> = loadMeals()
    private val podNights: MutableList<Night> = loadNights()

    /** A meal from a finished pod that the limits are learned from. [podMs] is 0 for imported meals. */
    private data class BaselineMeal(val risingMin: Double, val fallPerU: Double?, val podMs: Long)

    private val baseline: MutableList<BaselineMeal> = mutableListOf()
    private var baselinePods = 0

    init {
        loadBaseline(sp.getString(K_BASELINE, ""))
    }

    // All time, for the card's log line.
    private var totalMeals = sp.getInt(K_TOTAL_MEALS, 0)
    private var totalPods = sp.getInt(K_TOTAL_PODS, 0)

    /**
     * Call once per loop cycle. Returns a meal when it has just been judged, else null.
     *
     * [mealModeActive]: any meal or UAM mode is running; meals are only looked for then.
     * [siteChangeMs]: the last pod change; nothing is judged without one.
     * [unreliable]: why this cycle is unfit to judge insulin by (suspected exercise, CGM warm-up, an activity
     * session), or null when it is fine. An open meal is dropped.
     *
     * For the night check: [minuteOfDay] is the local time in minutes after midnight (null turns the night
     * check off), [targetMgdl] the BG target, [insulinAboveBasalU] what the loop gave above profile basal since
     * the last cycle, and [quietMode] is true with no meal mode or with P/F, which may run at night.
     * A judged night is picked up with [takeNight].
     */
    fun onCycle(
        nowMs: Long,
        bgMgdl: Double,
        deltaMgdl: Double,
        iobU: Double,
        mealModeActive: Boolean,
        modeLabel: String,
        siteChangeMs: Long?,
        unreliable: DropReason?,
        minuteOfDay: Int? = null,
        targetMgdl: Double = 0.0,
        insulinAboveBasalU: Double = 0.0,
        quietMode: Boolean = !mealModeActive
    ): Episode? {
        val result = cycle(nowMs, bgMgdl, deltaMgdl, iobU, mealModeActive, modeLabel, siteChangeMs, unreliable,
                           NightInput(minuteOfDay, targetMgdl, insulinAboveBasalU, quietMode))
        persistOpen()
        return result
    }

    /** The meal or night dropped since the last call, once, for the journal. */
    fun takeDropped(): Dropped? = pendingDrop.also { pendingDrop = null }

    /** The night judged since the last call, once. */
    fun takeNight(): Night? = pendingNight.also { pendingNight = null }

    private class NightInput(val minuteOfDay: Int?, val targetMgdl: Double, val insulinAboveBasalU: Double, val quietMode: Boolean)

    private fun cycle(
        nowMs: Long,
        bgMgdl: Double,
        deltaMgdl: Double,
        iobU: Double,
        mealModeActive: Boolean,
        modeLabel: String,
        siteChangeMs: Long?,
        unreliable: DropReason?,
        night: NightInput
    ): Episode? {
        val minutes = if (lastCycleMs == 0L) 5.0 else (nowMs - lastCycleMs) / 60_000.0
        val gap = lastCycleMs != 0L && nowMs - lastCycleMs > MAX_GAP_MS
        val gapMin = ((nowMs - lastCycleMs) / 60_000).toInt()
        lastCycleMs = nowMs
        lastBgMgdl = bgMgdl
        lastIobU = iobU

        if (siteChangeMs != null && siteChangeMs != podMs) newPod(siteChangeMs)
        if (!mealModeActive) startedHighNoted = false

        recent.addLast(Sample(nowMs, bgMgdl, iobU))
        while (recent.isNotEmpty() && nowMs - recent.first().timeMs > LOOKBACK_MS) recent.removeFirst()

        val skip = when {
            gap                  -> DropReason.DATA_GAP
            unreliable != null   -> unreliable
            siteChangeMs == null -> DropReason.NO_POD_CHANGE
            else                 -> null
        }
        nightCycle(nowMs, bgMgdl, minutes, siteChangeMs, skip, gapMin, night)
        if (skip != null) {
            drop(nowMs, skip, if (skip == DropReason.DATA_GAP) gapMin else 0)
            if (gap) recent.retainAll { it.timeMs == nowMs }
            return null
        }
        siteChangeMs!!

        val o = open
        if (o == null) {
            val podAgeHours = (nowMs - siteChangeMs) / 3_600_000.0
            if (mealModeActive && podAgeHours >= MIN_POD_AGE_HOURS) {
                if (bgMgdl < MAX_START_BG_MGDL) {
                    // Start where the rise began: a UAM mode only fires once BG is already climbing. The latest
                    // low point, so a slow rise is not timed from a flat stretch before it.
                    val lowest = recent.minOf { it.bgMgdl }
                    val start = recent.last { it.bgMgdl == lowest }
                    open = Open(start.timeMs, start.bgMgdl, start.iobU, modeLabel, siteChangeMs)
                } else if (!startedHighNoted) {
                    startedHighNoted = true
                    noteDrop(Dropped(nowMs, modeLabel, nowMs, DropReason.STARTED_HIGH))
                }
            }
            return null
        }
        if (o.siteChangeMs != siteChangeMs) {
            open = null
            return null
        }

        val sinceStart = nowMs - o.startMs
        if (iobU > o.maxIobU) o.maxIobU = iobU
        if (sinceStart <= PEAK_WINDOW_MS) {
            if (deltaMgdl > 0 && iobU - o.startIobU >= LOADED_EXTRA_IOB_U) o.risingLoadedMin += minutes.coerceAtMost(10.0)
            if (bgMgdl > o.peakBgMgdl) {
                o.peakBgMgdl = bgMgdl
                o.peakMs = nowMs
                o.risingLoadedAtPeakMin = o.risingLoadedMin
                o.maxIobAtPeakU = o.maxIobU
                o.lowestSincePeakMgdl = bgMgdl
            }
        }
        if (bgMgdl < o.lowestSincePeakMgdl) o.lowestSincePeakMgdl = bgMgdl

        val risen = o.peakBgMgdl - o.startBgMgdl >= MIN_RISE_MGDL
        if (!risen) {
            // No meal rise yet. Give up after a while; a new start is looked for on the next cycle.
            if (sinceStart > RISE_WINDOW_MS) drop(nowMs, DropReason.NO_RISE)
            return null
        }
        if (nowMs - o.peakMs < LANDING_WINDOW_MS) return null

        open = null
        return judge(o, nowMs)
    }

    /** Drops the open meal, if there is one, and remembers why. */
    private fun drop(nowMs: Long, reason: DropReason, gapMin: Int = 0) {
        val o = open ?: return
        open = null
        noteDrop(Dropped(nowMs, o.mode, o.startMs, reason, gapMin))
    }

    /** The night check, run on every cycle before the meal check. [skip] is why this cycle is unfit, if it is. */
    private fun nightCycle(nowMs: Long, bgMgdl: Double, minutes: Double, siteChangeMs: Long?, skip: DropReason?, gapMin: Int, input: NightInput) {
        val minuteOfDay = input.minuteOfDay ?: return
        val n = openNight
        if (n == null) {
            // Only started in the first minutes after midnight, so a night is always a whole one.
            if (minuteOfDay >= NIGHT_START_GRACE_MIN || skip != null || siteChangeMs == null) return
            if (nowMs - siteChangeMs < MIN_POD_AGE_HOURS * 3_600_000L) return
            if (!input.quietMode) {
                noteDrop(Dropped(nowMs, NIGHT_LABEL, nowMs, DropReason.MEAL_AT_NIGHT))
                return
            }
            openNight = OpenNight(nowMs, siteChangeMs).also { it.add(bgMgdl) }
            return
        }
        val reason = when {
            skip != null               -> skip
            !input.quietMode           -> DropReason.MEAL_AT_NIGHT
            else                       -> null
        }
        if (reason != null || n.siteChangeMs != siteChangeMs || nowMs - n.startMs > NIGHT_MAX_MS) {
            openNight = null
            // A pod change is not worth a note: the card starts again for the new pod anyway.
            reason?.let { noteDrop(Dropped(nowMs, NIGHT_LABEL, n.startMs, it, if (it == DropReason.DATA_GAP) gapMin else 0)) }
            return
        }
        val step = minutes.coerceAtMost(10.0)
        n.insulinU += input.insulinAboveBasalU
        n.minutes += step
        if (bgMgdl > input.targetMgdl + NIGHT_HIGH_ABOVE_TARGET_MGDL) n.highMinutes += step
        n.add(bgMgdl)
        if (minuteOfDay >= NIGHT_END_MIN) {
            openNight = null
            pendingNight = judgeNight(n)
        }
    }

    private fun judgeNight(n: OpenNight): Night {
        val start = n.firstBgs.average()
        val end = n.lastBgs.average()
        val highShare = if (n.minutes > 0.0) n.highMinutes / n.minutes else 0.0
        val fallPerU = if (n.insulinU > 0.0) (start - end) / MGDL_PER_MMOL / n.insulinU else null
        val suspect = n.insulinU >= NIGHT_MIN_EXTRA_U && fallPerU != null && fallPerU <= NIGHT_FALL_PER_U_MAX && highShare >= NIGHT_HIGH_SHARE
        // A whole night of insulin not working is as strong as a meal that was both slow and did not land.
        val notify = suspect && countSuspect(strong = true)
        val night = Night(
            startMs = n.startMs,
            siteChangeMs = n.siteChangeMs,
            podAgeHours = (n.startMs - n.siteChangeMs) / 3_600_000.0,
            startBgMgdl = start,
            endBgMgdl = end,
            insulinAboveBasalU = n.insulinU,
            highShare = highShare,
            lowestMgdl = n.lowestMgdl,
            suspect = suspect,
            suspectsOnPod = suspects,
            notify = notify
        )
        synchronized(podNights) {
            podNights.add(night)
            while (podNights.size > MAX_POD_NIGHTS) podNights.removeAt(0)
        }
        persistNights()
        return night
    }

    /** Counts one more suspect meal or night on this pod. Returns true when the user is to be asked now. */
    private fun countSuspect(strong: Boolean): Boolean {
        suspects++
        val notify = !silenced && !bad && when (notifications) {
            0    -> suspects >= SUSPECTS_TO_NOTIFY || strong
            1    -> suspects >= suspectsAtLastNotification + SUSPECTS_TO_NOTIFY
            else -> false
        }
        if (notify) {
            notifications++
            suspectsAtLastNotification = suspects
        }
        persistPod()
        return notify
    }

    /**
     * "This site was bad", for the current pod or the one before it ([status] gives both). That pod's meals
     * never join the baseline, and are taken out again if they already did. The current pod also stops
     * asking. Returns false for any other pod.
     */
    fun markSiteBad(podMs: Long): Boolean {
        when {
            podMs == 0L                   -> return false
            podMs == this.podMs           -> {
                bad = true
                persistPod()
            }
            podMs == previousPodMs        -> {
                val removed = synchronized(baseline) {
                    val before = baseline.size
                    baseline.removeAll { it.podMs == podMs }
                    before - baseline.size
                }
                if (removed > 0) {
                    baselinePods = (baselinePods - 1).coerceAtLeast(0)
                    persistBaseline()
                }
            }
            else                          -> return false
        }
        return true
    }

    private fun noteDrop(d: Dropped) {
        lastDropped = d
        pendingDrop = d
        sp.edit { putString(K_DROPPED, d.toJson().toString()) }
    }

    /** What the card shows, at [nowMs]. */
    fun status(nowMs: Long): Status {
        val o = open
        val pod = podMs.takeIf { it > 0L }
        return Status(
            siteChangeMs = pod,
            podAgeHours = pod?.let { (nowMs - it) / 3_600_000.0 },
            meals = synchronized(podMeals) { podMeals.toList() },
            suspects = suspects,
            notified = notifications > 0,
            silenced = silenced,
            live = o?.let {
                Live(
                    mode = it.mode, startMs = it.startMs, startBgMgdl = it.startBgMgdl, peakBgMgdl = it.peakBgMgdl,
                    peakMs = it.peakMs, bgMgdl = lastBgMgdl, risingLoadedMin = it.risingLoadedAtPeakMin.toInt(),
                    extraIobU = (lastIobU - it.startIobU).coerceAtLeast(0.0),
                    risen = it.peakBgMgdl - it.startBgMgdl >= MIN_RISE_MGDL
                )
            },
            totalMeals = totalMeals,
            totalPods = totalPods,
            limits = limits(),
            baselineMeals = synchronized(baseline) { baseline.size },
            baselinePods = baselinePods,
            lastDropped = lastDropped,
            nights = synchronized(podNights) { podNights.toList() },
            liveNight = openNight?.let { n ->
                LiveNight(
                    startMs = n.startMs,
                    insulinAboveBasalU = n.insulinU,
                    highShare = if (n.minutes > 0.0) n.highMinutes / n.minutes else 0.0,
                    fallMgdl = if (n.firstBgs.isEmpty()) 0.0 else n.firstBgs.average() - n.lastBgs.average()
                )
            },
            bad = bad,
            previousPodMs = previousPodMs.takeIf { it > 0L }
        )
    }

    /**
     * Replaces the baseline with one made elsewhere (from Nightscout history), so a user does not have to
     * wait for it to learn. Same format as stored: {"pods": n, "meals": [{"r": minutes, "f": per U or null}]}.
     * Returns false and changes nothing when the text is not a usable baseline.
     */
    fun importBaseline(json: String): Boolean {
        val meals = parseBaseline(json) ?: return false
        synchronized(baseline) {
            baseline.clear()
            baseline.addAll(meals.second.takeLast(BASELINE_MAX_MEALS))
        }
        baselinePods = meals.first
        persistBaseline()
        return true
    }

    /** "Site is fine": no more questions for this pod. */
    fun markSiteFine() {
        silenced = true
        persistPod()
    }

    private fun judge(o: Open, nowMs: Long): Episode {
        val extraIob = o.maxIobAtPeakU - o.startIobU
        val fall = o.peakBgMgdl - o.lowestSincePeakMgdl
        val limits = limits()
        val slow = limits != null && o.risingLoadedAtPeakMin >= limits.slowMin
        val noLanding = limits?.landingPerU != null && extraIob >= NO_LANDING_MIN_EXTRA_IOB_U &&
            fall / MGDL_PER_MMOL / extraIob <= limits.landingPerU

        val notify = (slow || noLanding) && countSuspect(strong = (slow && noLanding) || o.risingLoadedAtPeakMin >= limits!!.verySlowMin)
        val episode = Episode(
            startMs = o.startMs,
            siteChangeMs = o.siteChangeMs,
            podAgeHours = (o.startMs - o.siteChangeMs) / 3_600_000.0,
            mode = o.mode,
            startBgMgdl = o.startBgMgdl,
            peakBgMgdl = o.peakBgMgdl,
            minutesToPeak = ((o.peakMs - o.startMs) / 60_000).toInt(),
            risingLoadedMin = o.risingLoadedAtPeakMin.toInt(),
            extraIobAtPeakU = extraIob,
            fall2hMgdl = fall,
            slow = slow,
            noLanding = noLanding,
            suspectsOnPod = suspects,
            notify = notify,
            learning = limits == null
        )
        val first = synchronized(podMeals) {
            podMeals.add(episode)
            while (podMeals.size > MAX_POD_MEALS) podMeals.removeAt(0)
            podMeals.size == 1
        }
        totalMeals++
        if (first) totalPods++
        persistMeals()
        return episode
    }

    private fun newPod(siteChangeMs: Long) {
        // The finished pod's meals join the baseline, unless it asked to have its site checked and the
        // user did not say it was fine: those meals may be a leak, and must not become "normal".
        val finished = synchronized(podMeals) { podMeals.toList() }
        if (finished.isNotEmpty() && !bad && (notifications == 0 || silenced)) {
            synchronized(baseline) {
                finished.forEach { baseline.add(BaselineMeal(it.risingLoadedMin.toDouble(), it.fallPerUnitMmol, it.siteChangeMs)) }
                while (baseline.size > BASELINE_MAX_MEALS) baseline.removeAt(0)
            }
            baselinePods++
            persistBaseline()
        }
        if (podMs != 0L) previousPodMs = podMs
        podMs = siteChangeMs
        bad = false
        openNight = null
        synchronized(podNights) { podNights.clear() }
        persistNights()
        suspects = 0
        notifications = 0
        suspectsAtLastNotification = 0
        silenced = false
        open = null
        lastDropped = null
        pendingDrop = null
        synchronized(podMeals) { podMeals.clear() }
        sp.edit { remove(K_DROPPED) }
        persistPod()
        persistMeals()
    }

    // --- Learned limits ---

    /** The limits from the baseline, or null while it is still learning. */
    private fun limits(): Limits? {
        val meals = synchronized(baseline) { baseline.toList() }
        if (meals.size < BASELINE_MIN_MEALS || baselinePods < BASELINE_MIN_PODS) return null
        val rising = meals.map { it.risingMin }
        val risingMid = median(rising)
        val risingSpread = median(rising.map { abs(it - risingMid) })
        val falls = meals.mapNotNull { it.fallPerU }
        val fallMid = if (falls.size >= BASELINE_MIN_LANDINGS) median(falls) else null
        val fallSpread = fallMid?.let { m -> median(falls.map { abs(it - m) }) }
        return Limits(
            slowMin = maxOf(SLOW_FLOOR_MIN, risingMid + SLOW_SPREADS * risingSpread),
            verySlowMin = maxOf(VERY_SLOW_FLOOR_MIN, risingMid + VERY_SLOW_SPREADS * risingSpread),
            landingPerU = if (fallMid != null && fallSpread != null) minOf(LANDING_CEILING_PER_U, fallMid - LANDING_SPREADS * fallSpread) else null,
            typicalRisingMin = risingMid,
            risingSpreadMin = risingSpread,
            typicalFallPerU = fallMid,
            fallSpreadPerU = fallSpread
        )
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    // --- Storage ---

    private fun persistBaseline() {
        val arr = JSONArray()
        synchronized(baseline) {
            baseline.forEach { m ->
                val o = JSONObject().put("r", m.risingMin).put("f", m.fallPerU ?: JSONObject.NULL)
                if (m.podMs != 0L) o.put("p", m.podMs)
                arr.put(o)
            }
        }
        sp.edit { putString(K_BASELINE, JSONObject().put("pods", baselinePods).put("meals", arr).toString()) }
    }

    private fun loadBaseline(json: String) {
        parseBaseline(json)?.let { (pods, meals) ->
            baseline.addAll(meals.takeLast(BASELINE_MAX_MEALS))
            baselinePods = pods
        }
    }

    /** (pods, meals), or null when [json] is not a usable baseline. */
    private fun parseBaseline(json: String): Pair<Int, List<BaselineMeal>>? = try {
        val o = JSONObject(json)
        val arr = o.getJSONArray("meals")
        val meals = List(arr.length()) { i ->
            val m = arr.getJSONObject(i)
            val r = m.getDouble("r")
            val f = if (m.isNull("f")) null else m.getDouble("f")
            require(r >= 0 && r.isFinite() && (f == null || f.isFinite()))
            BaselineMeal(r, f, m.optLong("p", 0L))
        }
        val pods = o.getInt("pods")
        require(pods >= 0)
        pods to meals
    } catch (_: Exception) {
        null
    }

    private fun persistMeals() {
        val arr = JSONArray()
        synchronized(podMeals) { podMeals.forEach { arr.put(it.toJson()) } }
        sp.edit {
            putString(K_POD_MEALS, arr.toString())
            putInt(K_TOTAL_MEALS, totalMeals)
            putInt(K_TOTAL_PODS, totalPods)
        }
    }

    private fun loadMeals(): MutableList<Episode> = try {
        val arr = JSONArray(sp.getString(K_POD_MEALS, "[]"))
        MutableList(arr.length()) { Episode.fromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) {
        mutableListOf()
    }

    private fun persistOpen() {
        val o = open
        val n = openNight
        sp.edit {
            if (o == null) remove(K_OPEN)
            else putString(K_OPEN, JSONObject().put("meal", o.toJson()).put("cycle", lastCycleMs).toString())
            if (n == null) remove(K_NIGHT)
            else putString(K_NIGHT, n.toJson(lastCycleMs).toString())
        }
    }

    private fun persistNights() {
        val arr = JSONArray()
        synchronized(podNights) { podNights.forEach { arr.put(it.toJson()) } }
        sp.edit { putString(K_POD_NIGHTS, arr.toString()) }
    }

    private fun loadNights(): MutableList<Night> = try {
        val arr = JSONArray(sp.getString(K_POD_NIGHTS, "[]"))
        MutableList(arr.length()) { Night.fromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) {
        mutableListOf()
    }

    private fun persistPod() = sp.edit {
        putLong(K_POD, podMs)
        putInt(K_SUSPECTS, suspects)
        putInt(K_NOTIFIED, notifications)
        putInt(K_SUSPECTS_AT_NOTIFIED, suspectsAtLastNotification)
        putBoolean(K_SILENCED, silenced)
        putBoolean(K_BAD, bad)
        putLong(K_PREVIOUS_POD, previousPodMs)
    }

    companion object {

        private const val MGDL_PER_MMOL = 18.0

        /** Insulin on board this far above the meal's start counts as "loaded". */
        const val LOADED_EXTRA_IOB_U = 2.0
        /** Below this much extra insulin at the peak the landing is not judged. */
        const val NO_LANDING_MIN_EXTRA_IOB_U = 3.0

        // Learned limits: how many spreads from the middle of the user's meals counts as unusual.
        /** Still rising this many spreads above the middle is slow. */
        const val SLOW_SPREADS = 4.3
        /** This many spreads above the middle asks on its own, without a second meal. */
        const val VERY_SLOW_SPREADS = 6.3
        /** A fall per unit this many spreads under the middle did not land. */
        const val LANDING_SPREADS = 2.4
        /** Floors, so a user whose meals are all alike does not get limits that react to every meal. */
        const val SLOW_FLOOR_MIN = 60.0
        const val VERY_SLOW_FLOOR_MIN = 90.0
        const val LANDING_CEILING_PER_U = 0.3
        /** Learning until the baseline has this many meals from this many finished pods. */
        const val BASELINE_MIN_MEALS = 30
        const val BASELINE_MIN_PODS = 3
        /** Meals with enough insulin to judge a landing, before landings are judged. */
        const val BASELINE_MIN_LANDINGS = 15
        /** The baseline keeps the newest meals, about 4 to 6 pods. */
        const val BASELINE_MAX_MEALS = 60
        /** Suspect meals on one pod before the user is asked. */
        const val SUSPECTS_TO_NOTIFY = 2

        const val MIN_POD_AGE_HOURS = 12.0
        /** A meal rise is at least this much (2 mmol). */
        const val MIN_RISE_MGDL = 36.0
        /** A meal starting this high is not looked at (9 mmol): it began in someone else's high. */
        const val MAX_START_BG_MGDL = 162.0
        /** The rise has to come within this time of the start. Long, because a slow rise is the point. */
        const val RISE_WINDOW_MS = 2 * 60 * 60_000L
        /** The peak is looked for this long after the start. */
        const val PEAK_WINDOW_MS = 4 * 60 * 60_000L
        /** How long after the peak the landing is measured. */
        const val LANDING_WINDOW_MS = 2 * 60 * 60_000L
        /** How far back the start of the rise is looked for. */
        const val LOOKBACK_MS = 30 * 60_000L
        /** A longer break in the data drops the meal. */
        const val MAX_GAP_MS = 20 * 60_000L
        /** Meals kept for the card. A pod has 10 to 20. */
        const val MAX_POD_MEALS = 30

        // The night check, 00:00 to 06:00. Fixed limits - see the class comment.
        /** A night is only started this many minutes after midnight, so it is always a whole one. */
        const val NIGHT_START_GRACE_MIN = 15
        /** The night is judged at the first cycle from 06:00. */
        const val NIGHT_END_MIN = 6 * 60
        /** A night still open after this long is dropped (the data stopped and started again). */
        const val NIGHT_MAX_MS = 7 * 60 * 60_000L
        /** Suspect only with at least this much insulin above profile basal. */
        const val NIGHT_MIN_EXTRA_U = 4.0
        /** ...that brought BG down this many mmol per unit or less... */
        const val NIGHT_FALL_PER_U_MAX = 0.3
        /** ...while BG was more than this above target (1.5 mmol)... */
        const val NIGHT_HIGH_ABOVE_TARGET_MGDL = 27.0
        /** ...for at least this part of the night. */
        const val NIGHT_HIGH_SHARE = 0.5
        /** Nights kept for the card. */
        const val MAX_POD_NIGHTS = 7
        /** The "mode" a dropped night is reported under. */
        const val NIGHT_LABEL = "Night"
        private const val BG_AVERAGE_READINGS = 3

        private const val K_POD = "si_site_check_pod"
        private const val K_SUSPECTS = "si_site_check_suspects"
        private const val K_NOTIFIED = "si_site_check_notified"
        private const val K_SUSPECTS_AT_NOTIFIED = "si_site_check_suspects_at_notified"
        private const val K_SILENCED = "si_site_check_silenced"
        private const val K_POD_MEALS = "si_site_check_pod_meals"
        private const val K_TOTAL_MEALS = "si_site_check_total_meals"
        private const val K_TOTAL_PODS = "si_site_check_total_pods"
        private const val K_BASELINE = "si_site_check_baseline"
        private const val K_OPEN = "si_site_check_open"
        private const val K_DROPPED = "si_site_check_dropped"
        private const val K_NIGHT = "si_site_check_night"
        private const val K_POD_NIGHTS = "si_site_check_pod_nights"
        private const val K_BAD = "si_site_check_bad"
        private const val K_PREVIOUS_POD = "si_site_check_previous_pod"
    }
}
