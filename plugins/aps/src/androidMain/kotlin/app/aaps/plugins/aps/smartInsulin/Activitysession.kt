package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.keys.StringNonKey

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.StringKey
import org.json.JSONObject
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import kotlin.math.abs

/**
 * A user-declared stretch of adrenaline/stress with no food in it — a round of golf, a gym
 * session. The loop cannot tell a hormonal high from a meal, but the user can, and these repeat
 * with the same shape week after week, so they are worth learning rather than merely blocking.
 *
 * The shape being learned, from the user's own description of it:
 *   - RESISTANT phase: BG runs high from the start, hormones fighting the insulin. Needs more
 *     insulin than the hour of day would normally ask for.
 *   - WASHOUT phase: the hormones clear near the end and sensitivity comes back all at once,
 *     which is where the late low lands. Insulin has to be backed off BEFORE that, not after.
 *
 * Two numbers per label carry that: how much extra the resistant phase needs, and how long
 * before the end the washout starts.
 */
enum class SessionLabel(val label: String, val defaultMins: Int) {
    GOLF("Golf", 240),
    GYM("Gym", 90);

    companion object {
        fun of(name: String?): SessionLabel? = entries.firstOrNull { it.name == name }
    }
}

/** The session running right now. */
data class ActivitySession(
    val label:     SessionLabel,
    val startMs:   Long,
    val plannedMs: Long
) {
    fun elapsedMs(nowMs: Long) = nowMs - startMs
    /** 0.0 at the first tee, 1.0 at the planned finish; can exceed 1.0 on a slow round. */
    fun progress(nowMs: Long) = if (plannedMs <= 0L) 0.0 else elapsedMs(nowMs).toDouble() / plannedMs
}

/**
 * Holds the active session and applies what [ActivitySessionLearner] has learned about it.
 *
 * Dosing effects, all of them read by the plugin:
 *   - UAM entry and P/F takeover are blocked outright. A flat high inside a declared no-food
 *     window is hormones; letting a meal mode fire on it is the cart-dump this exists to stop.
 *   - In the resistant phase, the mode's ISF is strengthened by the learned multiplier.
 *   - In the washout phase, insulin is tapered off toward the end — the same pre-emptive shape as
 *     the low-guard taper, but arriving before the low instead of after it.
 */
@SingleIn(AppScope::class)
class ActivitySessionManager @Inject constructor(
    private val sp:         SP,
    private val aapsLogger: AAPSLogger,
    private val learner:    ActivitySessionLearner
) {

    var active: ActivitySession? = null
        private set

    // Evidence gathered from the running session
    private var resistantSum  = 0.0
    private var resistantN    = 0
    private var lowAtMins: Int? = null
    private var startBg       = 0.0
    private var scoreEndBg    = 0.0

    // A finished session still being watched for the late low
    private var endedSession:   ActivitySession? = null
    private var endedActualMins = 0
    private var watchUntilMs    = 0L

    init { restore() }

    companion object {
        /** A session is dropped this long past its planned end, in case it is never stopped. */
        private const val OVERRUN_GRACE_MS = 90 * 60_000L
        /** How long after a session ends a low still belongs to it — the hormones clearing don't
         *  stop at the last hole, and the drive home is where some of these land. */
        private const val TAIL_WATCH_MS    = 60 * 60_000L
        private const val K_LABEL   = "label"
        private const val K_START   = "start"
        private const val K_PLANNED = "planned"
    }

    fun start(label: SessionLabel, nowMs: Long, plannedMins: Int = label.defaultMins) {
        active = ActivitySession(label, nowMs, plannedMins * 60_000L)
        resistantSum = 0.0; resistantN = 0; lowAtMins = null
        startBg = 0.0; scoreEndBg = 0.0
        endedSession = null; watchUntilMs = 0L
        persist()
        aapsLogger.debug(LTag.APS, "ActivitySession: ${label.label} started, planned ${plannedMins}min")
    }

    /**
     * Ends the session. It isn't judged yet — the late low this whole feature exists for often
     * arrives after the last hole, so the verdict waits out [TAIL_WATCH_MS].
     */
    fun stop(nowMs: Long): ActivitySession? {
        val ending = active ?: return null
        active = null
        endedSession    = ending
        endedActualMins = (ending.elapsedMs(nowMs) / 60_000).toInt()
        watchUntilMs    = nowMs + TAIL_WATCH_MS
        persist()
        aapsLogger.debug(LTag.APS, "ActivitySession: ${ending.label.label} ended after ${endedActualMins}min — watching the tail")
        return ending
    }

    /** True once the session has run so far past its planned end that it was clearly forgotten. */
    fun overrun(nowMs: Long): Boolean =
        active?.let { it.elapsedMs(nowMs) > it.plannedMs + OVERRUN_GRACE_MS } ?: false

    /**
     * Call once per loop cycle. Gathers what the session did, auto-stops one left running, and
     * hands the finished session to the learner once its tail is clear.
     */
    fun onCycle(nowMs: Long, bgMgdl: Double, targetMgdl: Double, lowActive: Boolean) {
        active?.let { s ->
            val elapsedMins = (s.elapsedMs(nowMs) / 60_000).toInt()
            if (lowActive && lowAtMins == null) lowAtMins = elapsedMins
            // Only the front of the resistant phase is scored: the back half is already tapering,
            // so including it would read the taper's own high BG as "needs more insulin".
            if (s.progress(nowMs) <= ActivitySessionLearner.RESISTANT_SCORE_FRACTION) {
                if (resistantN == 0) startBg = bgMgdl
                resistantSum += bgMgdl; resistantN++
                scoreEndBg = bgMgdl
            }
            if (overrun(nowMs)) {
                aapsLogger.debug(LTag.APS, "ActivitySession: ${s.label.label} ran past its planned end — auto-stopped")
                stop(nowMs)
            }
            return
        }

        val ended = endedSession ?: return
        val elapsedMins = ((nowMs - ended.startMs) / 60_000).toInt()
        if (lowActive && lowAtMins == null) lowAtMins = elapsedMins
        if (nowMs < watchUntilMs) return
        learner.onSessionEnd(
            label              = ended.label,
            plannedMins        = (ended.plannedMs / 60_000).toInt(),
            actualMins         = endedActualMins,
            lowAtMinsFromStart = lowAtMins,
            resistantAvgBgMgdl = if (resistantN > 0) resistantSum / resistantN else 0.0,
            targetMgdl         = targetMgdl,
            startBgMgdl        = startBg,
            scoreEndBgMgdl     = scoreEndBg
        )
        endedSession = null; watchUntilMs = 0L
        resistantSum = 0.0; resistantN = 0; lowAtMins = null
        startBg = 0.0; scoreEndBg = 0.0
    }

    /** Session state for the SI tab, or null when nothing is running. */
    fun statusLine(nowMs: Long): String? {
        val s = active ?: return null
        val d = sessionDosing(s, nowMs, learner.isfMultiplier(s.label), learner.washoutMins(s.label))
        val phase = if (d.inWashout) "washout — insulin at ${(d.insulinFraction * 100).toInt()}%"
                    else "resistant phase — ISF ×${"%.2f".format(d.isfMultiplier)}"
        return "${s.label.label}: ${s.elapsedMs(nowMs) / 60_000}min in, ${d.minsLeft}min left, $phase"
    }

    /** What this cycle's dose should look like. */
    fun dosing(nowMs: Long): SessionDosing =
        active?.let { sessionDosing(it, nowMs, learner.isfMultiplier(it.label), learner.washoutMins(it.label)) }
            ?: SessionDosing.NONE

    private fun persist() {
        try {
            val json = active?.let {
                JSONObject()
                    .put(K_LABEL, it.label.name)
                    .put(K_START, it.startMs)
                    .put(K_PLANNED, it.plannedMs)
                    .toString()
            } ?: ""
            sp.edit { putString(StringNonKey.ApsSmartInsulinActivitySessionState.key, json) }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "ActivitySession: persist failed: ${e.message}")
        }
    }

    private fun restore() {
        val raw = sp.getString(StringNonKey.ApsSmartInsulinActivitySessionState.key, StringNonKey.ApsSmartInsulinActivitySessionState.defaultValue)
        if (raw.isBlank()) return
        try {
            val json  = JSONObject(raw)
            val label = SessionLabel.of(json.optString(K_LABEL)) ?: return
            active = ActivitySession(label, json.optLong(K_START), json.optLong(K_PLANNED))
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "ActivitySession: restore failed: ${e.message}")
        }
    }
}

/**
 * Learns the shape of each [SessionLabel] from how its sessions actually went.
 *
 * Per label:
 *   - [LabelState.isfMult] multiplies the mode ISF during the resistant phase. Below 1.0 is
 *     stronger (dosing ISF = ISF × mult), matching every other learned multiplier here.
 *   - [LabelState.washoutMins] is how long before the planned end insulin starts backing off.
 *
 * Updates, judged once per session:
 *   - A low (or a dip toward the guard) during the session or its tail → the washout was too late
 *     or too shallow: move it earlier by the margin it missed by, and ease the resistant phase.
 *     Safety direction, so it moves further than a strengthen does.
 *   - No low, and BG ran meaningfully above target through the resistant phase → the phase needs
 *     more insulin: strengthen a step.
 *   - No low and it tracked target → nothing to learn, leave both alone.
 *
 * The asymmetry is the usual one: lows move things more than highs do, and a low always wins over
 * a high earlier in the same session.
 */
@SingleIn(AppScope::class)
class ActivitySessionLearner @Inject constructor(
    private val sp:         SP,
    private val aapsLogger: AAPSLogger
) {

    private data class LabelState(
        var isfMult:     Double = 1.0,
        var washoutMins: Int    = 0,      // 0 = not learned yet; falls back to DEFAULT_WASHOUT_MINS
        var sessions:    Int    = 0
    )

    private val states = mutableMapOf<SessionLabel, LabelState>()

    /** Called with each new verdict; the plugin points it at [LearningJournal]. */
    var onOutcome: ((String) -> Unit)? = null

    var lastOutcome = ""
        private set(value) {
            field = value
            if (value.isNotEmpty()) onOutcome?.invoke(value)
        }

    init { restore() }

    companion object {
        /** Where the washout starts before a label has learned its own timing. */
        const val DEFAULT_WASHOUT_MINS = 60
        private const val ISF_STRENGTHEN_STEP = 0.97   // −3% ISF = more insulin in the resistant phase
        private const val ISF_EASE_STEP       = 1.06   // +6% after a low — twice the step, safety side
        private const val ISF_MULT_MIN        = 0.7
        private const val ISF_MULT_MAX        = 1.3
        /** Washout can never start later than this before the end, nor earlier than half the session. */
        private const val WASHOUT_MIN_MINS    = 20
        private const val WASHOUT_MAX_MINS    = 150
        /** A low moves the washout to this long before the low itself happened. */
        private const val WASHOUT_LEAD_MINS   = 45
        /** Average BG this far over target through the resistant phase counts as under-dosed. */
        const val RESISTANT_HIGH_MARGIN_MGDL  = 18.0
        /**
         * BG that came down at least this much across the scored phase was already resolving, so
         * the phase is not charged for where it started. Teeing off at 9 after breakfast and
         * gliding to 6.5 averages above target the whole way without needing a drop more insulin.
         */
        const val RESOLVING_DROP_MGDL         = 27.0   // ~1.5 mmol
        /** The resistant phase is everything before the washout; only its first part is scored,
         *  so the tail of a session already backing off doesn't read as "ran high". */
        const val RESISTANT_SCORE_FRACTION    = 0.6

        private const val K_ISF     = "isfMult"
        private const val K_WASHOUT = "washoutMins"
        private const val K_N       = "n"
    }

    fun isfMultiplier(label: SessionLabel): Double = states[label]?.isfMult ?: 1.0

    fun washoutMins(label: SessionLabel): Int =
        states[label]?.washoutMins?.takeIf { it > 0 } ?: DEFAULT_WASHOUT_MINS

    fun sessionCount(label: SessionLabel): Int = states[label]?.sessions ?: 0

    /**
     * Judge one finished session.
     *
     * @param lowAtMinsFromStart minutes into the session when a low (or near-low) first landed,
     *        or null if none. A low in the tail after the session ended counts too — the hormones
     *        clearing don't stop at the last hole — and arrives as a value past the session length.
     * @param resistantAvgBgMgdl mean BG over the scored part of the resistant phase.
     */
    fun onSessionEnd(
        label:              SessionLabel,
        plannedMins:        Int,
        actualMins:         Int,
        lowAtMinsFromStart: Int?,
        resistantAvgBgMgdl: Double,
        targetMgdl:         Double,
        startBgMgdl:        Double = 0.0,   // BG when the session opened
        scoreEndBgMgdl:     Double = 0.0    // BG at the end of the scored resistant phase
    ) {
        val s = states.getOrPut(label) { LabelState() }
        s.sessions++
        val washoutBefore = washoutMins(label)
        when {
            lowAtMinsFromStart != null -> {
                // Start backing off WASHOUT_LEAD_MINS before the low actually arrived, measured
                // against the planned end so the number stays meaningful for the next session.
                val fromEnd = plannedMins - lowAtMinsFromStart + WASHOUT_LEAD_MINS
                s.washoutMins = fromEnd.coerceIn(WASHOUT_MIN_MINS, WASHOUT_MAX_MINS)
                    .coerceAtMost(plannedMins / 2)
                    .coerceAtLeast(WASHOUT_MIN_MINS)
                s.isfMult = (s.isfMult * ISF_EASE_STEP).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
                lastOutcome = "${label.label}: low ${lowAtMinsFromStart}min in — washout moved to " +
                    "${s.washoutMins}min before the end (was $washoutBefore), resistant phase eased to ×${"%.2f".format(s.isfMult)}"
            }

            // Already on its way down when the session opened — the average sits above target
            // because of where it started, not because this phase was under-dosed. Bounded the
            // same way the mode learners are: it only escapes while BG is actually resolving, and
            // a phase that stalls above target is charged whatever it inherited.
            startBgMgdl > 0.0 && scoreEndBgMgdl > 0.0 &&
                scoreEndBgMgdl <= startBgMgdl - RESOLVING_DROP_MGDL &&
                resistantAvgBgMgdl > targetMgdl + RESISTANT_HIGH_MARGIN_MGDL ->
                lastOutcome = "${label.label}: started ${BgText.bg(startBgMgdl)} and came down " +
                    "${BgText.bg(startBgMgdl - scoreEndBgMgdl)} through the resistant phase — " +
                    "working through an inherited high, no change"

            resistantAvgBgMgdl > targetMgdl + RESISTANT_HIGH_MARGIN_MGDL -> {
                s.isfMult = (s.isfMult * ISF_STRENGTHEN_STEP).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX)
                lastOutcome = "${label.label}: ran ${BgText.bg(resistantAvgBgMgdl - targetMgdl)} over target " +
                    "with no low — resistant phase strengthened to ×${"%.2f".format(s.isfMult)}"
            }

            else ->
                lastOutcome = "${label.label}: tracked target with no low — no change"
        }
        lastOutcome += " (n=${s.sessions}, ${actualMins}min)"
        persist()
        aapsLogger.debug(LTag.APS, "ActivitySessionLearner: $lastOutcome")
    }

    fun reset() {
        states.clear()
        lastOutcome = ""
        sp.edit { putString(StringNonKey.ApsSmartInsulinActivitySessionLearnerState.key, "") }
    }

    /** Rows for the SI tab: label, learned ISF multiplier, washout, session count. */
    fun rows(): List<Triple<SessionLabel, Pair<Double, Int>, Int>> =
        SessionLabel.entries.mapNotNull { l ->
            val s = states[l] ?: return@mapNotNull null
            Triple(l, s.isfMult to washoutMins(l), s.sessions)
        }

    private fun persist() {
        try {
            val json = JSONObject()
            states.forEach { (label, s) ->
                json.put(label.name, JSONObject()
                    .put(K_ISF, s.isfMult)
                    .put(K_WASHOUT, s.washoutMins)
                    .put(K_N, s.sessions))
            }
            sp.edit { putString(StringNonKey.ApsSmartInsulinActivitySessionLearnerState.key, json.toString()) }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "ActivitySessionLearner: persist failed: ${e.message}")
        }
    }

    private fun restore() {
        val raw = sp.getString(StringNonKey.ApsSmartInsulinActivitySessionLearnerState.key, StringNonKey.ApsSmartInsulinActivitySessionLearnerState.defaultValue)
        if (raw.isBlank()) return
        try {
            val json = JSONObject(raw)
            json.keys().forEach { key ->
                val label = SessionLabel.of(key) ?: return@forEach
                val obj   = json.optJSONObject(key) ?: return@forEach
                states[label] = LabelState(
                    isfMult     = obj.optDouble(K_ISF, 1.0).coerceIn(ISF_MULT_MIN, ISF_MULT_MAX),
                    washoutMins = obj.optInt(K_WASHOUT, 0),
                    sessions    = obj.optInt(K_N, 0)
                )
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "ActivitySessionLearner: restore failed: ${e.message}")
        }
    }
}

/** What the session is doing to dosing this cycle. */
data class SessionDosing(
    val isfMultiplier: Double,
    /** 1.0 = full insulin, 0.0 = none. Ramps down through the washout phase. */
    val insulinFraction: Double,
    val inWashout: Boolean,
    val minsLeft: Long
) {
    companion object {
        val NONE = SessionDosing(1.0, 1.0, inWashout = false, minsLeft = 0)
    }
}

/**
 * Where a running session sits on its own curve, and what that means for this cycle's dose.
 *
 * The washout tapers insulin linearly from full to [WASHOUT_FLOOR_FRACTION] between its start and
 * the planned end, then holds at the floor for an overrun. Tapering rather than cutting, because
 * the hormones clear over some tens of minutes rather than at a stroke, and because a hard stop
 * at a fixed clock time is exactly the kind of edge a slow round would fall off.
 */
const val WASHOUT_FLOOR_FRACTION = 0.3

fun sessionDosing(session: ActivitySession?, nowMs: Long, isfMult: Double, washoutMins: Int): SessionDosing {
    if (session == null) return SessionDosing.NONE
    val elapsedMins  = session.elapsedMs(nowMs) / 60_000.0
    val plannedMins  = session.plannedMs / 60_000.0
    val washoutStart = (plannedMins - washoutMins).coerceAtLeast(0.0)
    val minsLeft     = (plannedMins - elapsedMins).coerceAtLeast(0.0).toLong()
    if (elapsedMins < washoutStart)
        return SessionDosing(isfMult, 1.0, inWashout = false, minsLeft = minsLeft)
    val through  = if (washoutMins <= 0) 1.0 else ((elapsedMins - washoutStart) / washoutMins).coerceIn(0.0, 1.0)
    val fraction = 1.0 - (1.0 - WASHOUT_FLOOR_FRACTION) * through
    // The resistant-phase strengthening is let go as the taper comes in — carrying "you are
    // resistant right now" into the washout is the contradiction that causes the late low.
    val isfNow   = isfMult + (1.0 - isfMult) * through
    return SessionDosing(isfNow, fraction, inWashout = true, minsLeft = minsLeft)
}

/** True when two multipliers differ enough to be worth reporting. */
internal fun multChanged(a: Double, b: Double) = abs(a - b) > 1e-9
