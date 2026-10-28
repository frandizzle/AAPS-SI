package app.aaps.plugins.aps.smartInsulin

import org.json.JSONArray
import org.json.JSONObject

/** A learner whose learned state can be saved and put back, so learning from a bad site can be undone. */
interface UndoableLearner {

    /** Stable name: the key of this learner's state in a saved copy. Never change it. */
    val undoName: String

    /** What has been learned, as text. */
    fun exportState(): String

    /** Puts back a state from [exportState]. Anything the learner is watching right now is dropped. */
    fun importState(state: String)
}

/**
 * Saved copies of what the learners know, so learning from a bad site can be undone.
 *
 * A copy is taken at each pod change and then every [INTERVAL_MS] while the pod is in use. Copies of the
 * current pod and the one before it are kept, so a site can still be marked bad just after it was
 * changed - which is when a bleeding or leaking site is usually found.
 *
 * The copies are kept in one file through [read] and [write], not in the preferences, because a copy
 * holds the whole circadian table and the preferences are written again on every loop cycle.
 */
class LearningSnapshots(private val read: () -> String?, private val write: (String) -> Unit) {

    /** One saved copy. [podMs] is the pod change it belongs to (0 when there is none on record). */
    data class Snapshot(val timeMs: Long, val podMs: Long, val states: Map<String, String>)

    private val copies: MutableList<Snapshot> by lazy { load() }

    /** Call once per loop cycle, before any learner runs. */
    fun onCycle(nowMs: Long, podMs: Long?, learners: List<UndoableLearner>) {
        val pod = podMs ?: 0L
        val last = synchronized(copies) { copies.lastOrNull() }
        if (last != null && last.podMs == pod && nowMs - last.timeMs < INTERVAL_MS) return
        take(nowMs, pod, learners)
    }

    /** All copies, oldest first. */
    fun list(): List<Snapshot> = synchronized(copies) { copies.toList() }

    /**
     * Puts the learners back to the copy taken at [timeMs]. Newer copies are deleted: they hold the
     * learning that is being undone. A learner the copy knows nothing about is left as it is.
     * Returns false when there is no such copy.
     */
    fun restore(timeMs: Long, learners: List<UndoableLearner>): Boolean {
        val snapshot = synchronized(copies) {
            val s = copies.firstOrNull { it.timeMs == timeMs } ?: return false
            copies.removeAll { it.timeMs > timeMs }
            s
        }
        learners.forEach { l -> snapshot.states[l.undoName]?.let { l.importState(it) } }
        save()
        return true
    }

    /**
     * Deletes every copy. Called when the learning is moved onto a new profile or reset by hand: an
     * older copy would then put back learning that no longer fits.
     */
    fun clear() {
        synchronized(copies) { copies.clear() }
        save()
    }

    private fun take(nowMs: Long, pod: Long, learners: List<UndoableLearner>) {
        val snapshot = Snapshot(nowMs, pod, learners.associate { it.undoName to it.exportState() })
        synchronized(copies) {
            copies.add(snapshot)
            // Keep the current pod and the one before it.
            val pods = copies.map { it.podMs }.distinct().takeLast(PODS_KEPT).toSet()
            copies.removeAll { it.podMs !in pods }
            while (copies.size > MAX_COPIES) copies.removeAt(0)
        }
        save()
    }

    private fun save() {
        val arr = JSONArray()
        synchronized(copies) {
            copies.forEach { s ->
                val states = JSONObject()
                s.states.forEach { (k, v) -> states.put(k, v) }
                arr.put(JSONObject().put(K_TIME, s.timeMs).put(K_POD, s.podMs).put(K_STATES, states))
            }
        }
        write(arr.toString())
    }

    private fun load(): MutableList<Snapshot> = try {
        val arr = JSONArray(read() ?: "[]")
        MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val states = o.getJSONObject(K_STATES)
            Snapshot(o.getLong(K_TIME), o.getLong(K_POD), states.keys().asSequence().associateWith { states.getString(it) })
        }
    } catch (_: Exception) {
        mutableListOf()
    }

    companion object {

        /** A copy is taken this often while a pod is in use. */
        const val INTERVAL_MS = 4 * 60 * 60_000L
        /** Copies of this many pods are kept: the current one and the one before. */
        const val PODS_KEPT = 2
        /** Two 3-day pods at one copy every 4 h, with room to spare. */
        const val MAX_COPIES = 48

        private const val K_TIME = "t"
        private const val K_POD = "p"
        private const val K_STATES = "s"
    }
}
