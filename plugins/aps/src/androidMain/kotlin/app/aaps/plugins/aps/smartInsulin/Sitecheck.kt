package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.sharedPreferences.SP
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
 * across a gap in the data.
 */
class SiteCheck(private val sp: SP) {

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
        val baselinePods: Int
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
    }

    private val recent = ArrayDeque<Sample>()
    @Volatile private var open: Open? = null
    private var lastCycleMs = 0L
    @Volatile private var lastBgMgdl = 0.0
    @Volatile private var lastIobU = 0.0

    // Per pod, stored so a restart does not ask again or forget a "site is fine".
    private var podMs = sp.getLong(K_POD, 0L)
    private var suspects = sp.getInt(K_SUSPECTS, 0)
    private var notifications = sp.getInt(K_NOTIFIED, 0)
    private var suspectsAtLastNotification = sp.getInt(K_SUSPECTS_AT_NOTIFIED, 0)
    private var silenced = sp.getBoolean(K_SILENCED, false)
    private val podMeals: MutableList<Episode> = loadMeals()

    // Meals from finished pods that the limits are learned from: (still rising min, fall per U or null).
    private val baseline: MutableList<Pair<Double, Double?>> = mutableListOf()
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
     * [unreliable]: suspected exercise, CGM warm-up or anything else that makes this cycle unfit to judge
     * insulin by. An open meal is dropped.
     */
    fun onCycle(
        nowMs: Long,
        bgMgdl: Double,
        deltaMgdl: Double,
        iobU: Double,
        mealModeActive: Boolean,
        modeLabel: String,
        siteChangeMs: Long?,
        unreliable: Boolean
    ): Episode? {
        val minutes = if (lastCycleMs == 0L) 5.0 else (nowMs - lastCycleMs) / 60_000.0
        val gap = lastCycleMs != 0L && nowMs - lastCycleMs > MAX_GAP_MS
        lastCycleMs = nowMs
        lastBgMgdl = bgMgdl
        lastIobU = iobU

        if (siteChangeMs != null && siteChangeMs != podMs) newPod(siteChangeMs)

        recent.addLast(Sample(nowMs, bgMgdl, iobU))
        while (recent.isNotEmpty() && nowMs - recent.first().timeMs > LOOKBACK_MS) recent.removeFirst()

        if (gap || unreliable || siteChangeMs == null) {
            open = null
            if (gap) recent.retainAll { it.timeMs == nowMs }
            return null
        }

        val o = open
        if (o == null) {
            val podAgeHours = (nowMs - siteChangeMs) / 3_600_000.0
            if (mealModeActive && podAgeHours >= MIN_POD_AGE_HOURS && bgMgdl < MAX_START_BG_MGDL) {
                // Start where the rise began: a UAM mode only fires once BG is already climbing. The latest
                // low point, so a slow rise is not timed from a flat stretch before it.
                val lowest = recent.minOf { it.bgMgdl }
                val start = recent.last { it.bgMgdl == lowest }
                open = Open(start.timeMs, start.bgMgdl, start.iobU, modeLabel, siteChangeMs)
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
            if (sinceStart > RISE_WINDOW_MS) open = null
            return null
        }
        if (nowMs - o.peakMs < LANDING_WINDOW_MS) return null

        open = null
        return judge(o, nowMs)
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
            baselinePods = baselinePods
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

        var notify = false
        if (slow || noLanding) {
            suspects++
            val strong = (slow && noLanding) || o.risingLoadedAtPeakMin >= limits!!.verySlowMin
            notify = !silenced && when (notifications) {
                0    -> suspects >= SUSPECTS_TO_NOTIFY || strong
                1    -> suspects >= suspectsAtLastNotification + SUSPECTS_TO_NOTIFY
                else -> false
            }
            if (notify) {
                notifications++
                suspectsAtLastNotification = suspects
            }
            persistPod()
        }
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
        if (finished.isNotEmpty() && (notifications == 0 || silenced)) {
            synchronized(baseline) {
                finished.forEach { baseline.add(it.risingLoadedMin.toDouble() to it.fallPerUnitMmol) }
                while (baseline.size > BASELINE_MAX_MEALS) baseline.removeAt(0)
            }
            baselinePods++
            persistBaseline()
        }
        podMs = siteChangeMs
        suspects = 0
        notifications = 0
        suspectsAtLastNotification = 0
        silenced = false
        open = null
        synchronized(podMeals) { podMeals.clear() }
        persistPod()
        persistMeals()
    }

    // --- Learned limits ---

    /** The limits from the baseline, or null while it is still learning. */
    private fun limits(): Limits? {
        val meals = synchronized(baseline) { baseline.toList() }
        if (meals.size < BASELINE_MIN_MEALS || baselinePods < BASELINE_MIN_PODS) return null
        val rising = meals.map { it.first }
        val risingMid = median(rising)
        val risingSpread = median(rising.map { abs(it - risingMid) })
        val falls = meals.mapNotNull { it.second }
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
        synchronized(baseline) { baseline.forEach { (r, f) -> arr.put(JSONObject().put("r", r).put("f", f ?: JSONObject.NULL)) } }
        sp.edit { putString(K_BASELINE, JSONObject().put("pods", baselinePods).put("meals", arr).toString()) }
    }

    private fun loadBaseline(json: String) {
        parseBaseline(json)?.let { (pods, meals) ->
            baseline.addAll(meals.takeLast(BASELINE_MAX_MEALS))
            baselinePods = pods
        }
    }

    /** (pods, meals), or null when [json] is not a usable baseline. */
    private fun parseBaseline(json: String): Pair<Int, List<Pair<Double, Double?>>>? = try {
        val o = JSONObject(json)
        val arr = o.getJSONArray("meals")
        val meals = List(arr.length()) { i ->
            val m = arr.getJSONObject(i)
            val r = m.getDouble("r")
            val f = if (m.isNull("f")) null else m.getDouble("f")
            require(r >= 0 && r.isFinite() && (f == null || f.isFinite()))
            r to f
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

    private fun persistPod() = sp.edit {
        putLong(K_POD, podMs)
        putInt(K_SUSPECTS, suspects)
        putInt(K_NOTIFIED, notifications)
        putInt(K_SUSPECTS_AT_NOTIFIED, suspectsAtLastNotification)
        putBoolean(K_SILENCED, silenced)
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

        private const val K_POD = "si_site_check_pod"
        private const val K_SUSPECTS = "si_site_check_suspects"
        private const val K_NOTIFIED = "si_site_check_notified"
        private const val K_SUSPECTS_AT_NOTIFIED = "si_site_check_suspects_at_notified"
        private const val K_SILENCED = "si_site_check_silenced"
        private const val K_POD_MEALS = "si_site_check_pod_meals"
        private const val K_TOTAL_MEALS = "si_site_check_total_meals"
        private const val K_TOTAL_PODS = "si_site_check_total_pods"
        private const val K_BASELINE = "si_site_check_baseline"
    }
}
