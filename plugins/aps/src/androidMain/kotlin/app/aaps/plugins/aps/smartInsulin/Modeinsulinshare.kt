package app.aaps.plugins.aps.smartInsulin

/**
 * How much of the insulin behind a post-mode low was the MODE's, rather than the loop's own
 * corrections since.
 *
 * Both learners used to answer this with a stopwatch: a low inside 105 minutes of the mode ending
 * was the mode's, and anything later was nobody's. That gets two real cases wrong in opposite
 * directions:
 *
 *   - The mode (or DURA) leaves a lot on board, the loop then gives almost nothing because BG is
 *     already falling, and the low lands two hours later. Past the window, so nothing is learned —
 *     even though the mode's insulin is the only thing that could have caused it.
 *   - The mode ends, the loop then doses hard for an hour, and THAT takes BG low. Inside the
 *     window, so it is charged to the mode in full — teaching the mode to weaken for insulin it
 *     never gave. The more dangerous of the two.
 *
 * So the question is answered by insulin instead of by clock: what the mode left on board when it
 * ended, against what has been delivered since.
 *
 *     share = modeIobAtEnd / (modeIobAtEnd + deliveredSince)
 *
 * Left 3.0 U and 0.2 U given since → 94%, nearly all the mode's. Left 1.0 U and 2.0 U given since
 * → 33%, mostly the loop's. The rest is not lost: the circadian hard-low penalty charges the hour
 * itself on any low, whatever mode was or wasn't running.
 *
 * The share is also what makes it safe to watch for longer. With a stopwatch, extending the window
 * would mis-charge more lows; with the share, a late low after heavy post-mode dosing attributes
 * away from the mode on its own, so the watch can cover the whole tail of the mode's own insulin.
 */
internal object ModeInsulinShare {

    /** Below this the low is mostly the loop's own doing and the mode is not charged at all. */
    const val MIN_SHARE_TO_CHARGE = 0.25

    /**
     * @param modeIobAtEndU  IOB at the moment the mode ended — what it left working.
     * @param deliveredSinceU insulin the loop has given since: SMBs plus TBR above/below profile.
     */
    fun share(modeIobAtEndU: Double, deliveredSinceU: Double): Double {
        val mode = modeIobAtEndU.coerceAtLeast(0.0)
        val since = deliveredSinceU.coerceAtLeast(0.0)
        val total = mode + since
        return if (total <= 0.0) 0.0 else (mode / total).coerceIn(0.0, 1.0)
    }

    /** True when the mode owns enough of it to be worth charging. */
    fun chargeable(share: Double) = share >= MIN_SHARE_TO_CHARGE

    /** "88% of the insulin on board was Lunch's" — for the outcome line. */
    fun describe(share: Double, label: String) = "${(share * 100).toInt()}% of the insulin behind it was $label's"
}
