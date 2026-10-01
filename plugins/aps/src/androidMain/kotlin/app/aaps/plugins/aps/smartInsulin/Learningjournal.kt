package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.StringNonKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.json.JSONArray
import org.json.JSONObject

/**
 * A running record of what SmartInsulin's learners changed and why, newest last.
 *
 * Each learner already decides things in plain words ("Lunch ISF ×0.95: spike held 40 min above 10"); until now
 * only the latest of those survived, as a learner's `lastOutcome`, so when the loop felt different after a few
 * days there was no way to see which learner had drifted. Episode learners feed every verdict in; the
 * continuous learners (circadian ISF/basal, aggressiveness) are summarised once an hour by the plugin rather
 * than per cycle, which would bury the rest in 5-minute noise.
 *
 * Kept for [MAX_AGE_MS] / [MAX_ENTRIES], persisted so it survives restarts, and every entry is also logged
 * under APS so an exported log carries it.
 */
@SingleIn(AppScope::class)
class LearningJournal @Inject constructor(
    private val sp: SP,
    private val aapsLogger: AAPSLogger
) {

    data class Entry(val timeMs: Long, val source: String, val text: String)

    private val entries = ArrayDeque<Entry>()

    init {
        restore()
    }

    /** Snapshot, newest first. */
    fun entries(): List<Entry> = synchronized(entries) { entries.reversed() }

    /**
     * Record [text] from [source]. Returns [text], so it can wrap an assignment. Blank text and an exact repeat
     * of the same source's previous entry are skipped: learners re-state an unchanged verdict, and a restart
     * restores the last one.
     */
    fun note(source: String, text: String, nowMs: Long = System.currentTimeMillis()): String {
        if (text.isBlank()) return text
        synchronized(entries) {
            if (entries.lastOrNull { it.source == source }?.text == text) return text
            entries.addLast(Entry(nowMs, source, text))
            prune(nowMs)
        }
        aapsLogger.debug(LTag.APS, "LearningJournal [$source] $text")
        persist()
        return text
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
        persist()
    }

    private fun prune(nowMs: Long) {
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        while (entries.isNotEmpty() && nowMs - entries.first().timeMs > MAX_AGE_MS) entries.removeFirst()
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            synchronized(entries) {
                entries.forEach { arr.put(JSONObject().put(K_T, it.timeMs).put(K_S, it.source).put(K_X, it.text)) }
            }
            sp.edit { putString(StringNonKey.ApsSmartInsulinLearningJournal.key, arr.toString()) }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "LearningJournal: persist failed: ${e.message}")
        }
    }

    private fun restore() {
        try {
            val raw = sp.getString(StringNonKey.ApsSmartInsulinLearningJournal.key, "")
            if (raw.isBlank()) return
            val arr = JSONArray(raw)
            synchronized(entries) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    entries.addLast(Entry(o.getLong(K_T), o.getString(K_S), o.getString(K_X)))
                }
                prune(System.currentTimeMillis())
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "LearningJournal: restore failed, starting empty: ${e.message}")
            synchronized(entries) { entries.clear() }
        }
    }

    companion object {

        const val MAX_ENTRIES = 300
        const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

        private const val K_T = "t"
        private const val K_S = "s"
        private const val K_X = "x"
    }
}
