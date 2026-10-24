package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.data.model.data.Block
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Turns the all-days circadian learning into a new ISF and basal schedule for the profile.
 *
 * The all-days values are used, never one weekday, so one odd day does not go into the profile.
 * An hour only takes its learned value when enough data backs it ([MIN_CONFIDENCE]); other hours
 * keep the profile value. Neighbouring hours that are close are merged into one block, so the
 * profile stays readable instead of 24 different numbers.
 *
 * All ISF values are mg/dL per U here; the caller converts for a mmol profile.
 */
object LearnedProfilePlan {

    /** An hour takes its learned value from this confidence on. */
    const val MIN_CONFIDENCE = 0.6

    /** Shown as 100 %. Every hour needs this, for ISF and basal, before the profile is changed by itself. */
    const val AUTO_CONFIDENCE = 0.995

    /** Hours merge into one block while the largest value is at most this much above the smallest (about ± 5 %). */
    const val MERGE_SPREAD = 0.10

    /** The automatic change only runs when at least one hour moves this much or more. */
    const val MIN_AUTO_CHANGE = 0.05

    private const val HOUR_MS = 3_600_000L

    data class Hour(
        val hour: Int,
        val profileIsfMgdl: Double,
        val profileBasal: Double,
        val learnedIsfMgdl: Double,
        val learnedBasal: Double,
        val isfConfidence: Double,
        val basalConfidence: Double
    ) {

        val isfUsed: Boolean get() = isfConfidence >= MIN_CONFIDENCE
        val basalUsed: Boolean get() = basalConfidence >= MIN_CONFIDENCE
    }

    /** [hours] hours from [startHour] with one [value]. */
    data class Segment(val startHour: Int, val hours: Int, val value: Double)

    data class Plan(
        val hours: List<Hour>,
        val isfSegments: List<Segment>,
        val basalSegments: List<Segment>
    ) {

        /** The new ISF of each hour, mg/dL per U, after merging and rounding. */
        val newIsfMgdl: DoubleArray = expand(isfSegments)

        /** The new basal of each hour, U/h, after merging and rounding. */
        val newBasal: DoubleArray = expand(basalSegments)

        val isfHoursLearned: Int get() = hours.count { it.isfUsed }
        val basalHoursLearned: Int get() = hours.count { it.basalUsed }

        val profileDailyBasal: Double get() = hours.sumOf { it.profileBasal }
        val newDailyBasal: Double get() = newBasal.sum()
        val profileAverageIsfMgdl: Double get() = hours.map { it.profileIsfMgdl }.average()
        val newAverageIsfMgdl: Double get() = newIsfMgdl.average()

        /** Every hour has 100 % confidence for ISF and for basal. */
        val fullConfidence: Boolean get() = hours.all { it.isfConfidence >= AUTO_CONFIDENCE && it.basalConfidence >= AUTO_CONFIDENCE }

        /** The largest change of any hour against the profile, as a fraction (0.05 = 5 %). */
        val largestChange: Double
            get() = hours.maxOf { h ->
                maxOf(change(newIsfMgdl[h.hour], h.profileIsfMgdl), change(newBasal[h.hour], h.profileBasal))
            }

        /** ISF blocks for the profile, in the profile's unit. */
        fun isfBlocks(mgdlProfile: Boolean): List<Block> =
            isfSegments.map { Block(it.hours * HOUR_MS, if (mgdlProfile) it.value else roundTo(it.value / MGDL_PER_MMOL, MMOL_STEP)) }

        fun basalBlocks(): List<Block> = basalSegments.map { Block(it.hours * HOUR_MS, it.value) }
    }

    /**
     * @param isfStepMgdl the ISF rounding step: 0.1 mmol (1.8 mg/dL) for a mmol profile, 1 mg/dL otherwise.
     * @param basalStep   the pump's basal step, for example 0.05 U/h.
     * @param basalMin    the pump's lowest basal rate.
     */
    fun build(hours: List<Hour>, isfStepMgdl: Double, basalStep: Double, basalMin: Double): Plan {
        require(hours.size == 24) { "need 24 hours, got ${hours.size}" }
        val isf = DoubleArray(24) { h -> hours[h].let { if (it.isfUsed) it.learnedIsfMgdl else it.profileIsfMgdl } }
        val basal = DoubleArray(24) { h -> hours[h].let { if (it.basalUsed) it.learnedBasal else it.profileBasal } }
        return Plan(
            hours = hours,
            isfSegments = merge(isf) { roundTo(it, isfStepMgdl) },
            basalSegments = merge(basal) { maxOf(roundTo(it, basalStep), basalMin) }
        )
    }

    /**
     * Greedy merge: a block grows while all its hours stay within [MERGE_SPREAD]. Its value is the
     * mean, rounded. Blocks that round to the same value are joined afterwards.
     */
    fun merge(values: DoubleArray, round: (Double) -> Double): List<Segment> {
        val raw = mutableListOf<Segment>()
        var start = 0
        var lo = values[0]
        var hi = values[0]
        for (h in 1..values.size) {
            val v = if (h < values.size) values[h] else Double.NaN
            val newLo = minOf(lo, v)
            val newHi = maxOf(hi, v)
            if (h < values.size && newHi <= newLo * (1.0 + MERGE_SPREAD)) {
                lo = newLo; hi = newHi
                continue
            }
            raw += Segment(start, h - start, round(values.copyOfRange(start, h).average()))
            if (h < values.size) { start = h; lo = v; hi = v }
        }
        val joined = mutableListOf<Segment>()
        raw.forEach { s ->
            val last = joined.lastOrNull()
            if (last != null && abs(last.value - s.value) < 1e-9) joined[joined.size - 1] = last.copy(hours = last.hours + s.hours)
            else joined += s
        }
        return joined
    }

    /** The value of each of the 24 hours, read at the centre of the hour, from [blocks] in mg/dL (ISF) or U/h. */
    fun hourly(blocks: List<Block>, toMgdl: Double = 1.0): DoubleArray = DoubleArray(24) { h ->
        val at = h * HOUR_MS + HOUR_MS / 2
        var start = 0L
        var value = blocks.lastOrNull()?.amount ?: 0.0
        for (b in blocks) {
            if (at < start + b.duration) { value = b.amount; break }
            start += b.duration
        }
        value * toMgdl
    }

    /** True when every hour of [a] is within 0.5 % of [b]. */
    fun sameSchedule(a: DoubleArray, b: DoubleArray): Boolean =
        a.size == 24 && b.size == 24 && (0 until 24).all { h -> if (b[h] <= 0.0) abs(a[h]) < 1e-9 else abs(a[h] / b[h] - 1.0) <= 0.005 }

    private fun expand(segments: List<Segment>): DoubleArray {
        val out = DoubleArray(24)
        segments.forEach { s -> for (h in s.startHour until s.startHour + s.hours) out[h] = s.value }
        return out
    }

    private fun change(new: Double, old: Double): Double = if (old > 0.0) abs(new / old - 1.0) else 0.0

    /** [v] rounded to a whole number of [step], without float noise such as 2.8000000000000003. */
    private fun roundTo(v: Double, step: Double): Double {
        if (step <= 0.0) return v
        return BigDecimal((v / step).roundToLong() * step).setScale(4, RoundingMode.HALF_UP).toDouble()
    }

    const val MGDL_PER_MMOL = 18.0
    private const val MMOL_STEP = 0.1
}
