package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.smartInsulin.FoodLog
import app.aaps.core.interfaces.smartInsulin.FoodLogEntry
import app.aaps.core.interfaces.smartInsulin.FoodLogItem
import app.aaps.core.keys.StringNonKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.json.JSONArray
import org.json.JSONObject

/** The food log, kept as JSON in the settings. Entries older than [FoodLog.DAYS_KEPT] days are removed when one is added. */
@SingleIn(AppScope::class)
class FoodLogImpl @Inject constructor(
    private val sp:         KeyValueStore,
    private val aapsLogger: AAPSLogger
) : FoodLog {

    private val log = mutableListOf<FoodLogEntry>()

    init {
        load()
    }

    override fun add(entry: FoodLogEntry) {
        synchronized(this) {
            // Two entries in the same millisecond would delete together; move the new one on.
            var e = entry
            while (log.any { it.timeMs == e.timeMs }) e = e.copy(timeMs = e.timeMs + 1)
            log.add(e)
            log.sortBy { it.timeMs }
            val oldest = e.timeMs - (FoodLog.DAYS_KEPT + 1) * DAY_MS
            log.removeAll { it.timeMs < oldest }
        }
        save()
        aapsLogger.debug(LTag.APS, "FoodLog: ${entry.name}, ${"%.0f".format(entry.kcal)} kcal")
    }

    override fun entries(fromMs: Long, toMs: Long): List<FoodLogEntry> = synchronized(this) { log.filter { it.timeMs in fromMs until toMs } }

    override fun update(entry: FoodLogEntry) {
        val found = synchronized(this) {
            val i = log.indexOfFirst { it.timeMs == entry.timeMs }
            if (i >= 0) log[i] = entry
            i >= 0
        }
        if (found) save()
    }

    override fun delete(timeMs: Long) {
        val removed = synchronized(this) { log.removeAll { it.timeMs == timeMs } }
        if (removed) save()
    }

    private fun save() {
        val json = synchronized(this) {
            JSONArray().apply {
                log.forEach {
                    val o = JSONObject().put("t", it.timeMs).put("n", it.name).put("c", it.carbsG).put("fi", it.fibreG).put("p", it.proteinG).put("f", it.fatG)
                    if (it.mealSnack) o.put("ms", true)
                    if (it.forLowG > 0.0) o.put("lo", it.forLowG)
                    if (it.items.isNotEmpty()) o.put("i", JSONArray().apply {
                        it.items.forEach { f -> put(JSONObject().put("n", f.name).put("g", f.grams).put("c", f.carbsG).put("fi", f.fibreG).put("p", f.proteinG).put("f", f.fatG)) }
                    })
                    put(o)
                }
            }
        }
        sp.edit { putString(StringNonKey.ApsSmartInsulinFoodLog.key, json.toString()) }
    }

    private fun load() {
        val raw = sp.getString(StringNonKey.ApsSmartInsulinFoodLog.key, "")
        if (raw.isBlank()) return
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val items = o.optJSONArray("i")?.let { a2 ->
                    (0 until a2.length()).map { j ->
                        val f = a2.getJSONObject(j)
                        FoodLogItem(f.optString("n", ""), f.optDouble("g", 0.0), f.optDouble("c", 0.0), f.optDouble("fi", 0.0), f.optDouble("p", 0.0), f.optDouble("f", 0.0))
                    }
                }.orEmpty()
                log.add(
                    FoodLogEntry(
                        o.getLong("t"), o.optString("n", ""), o.optDouble("c", 0.0), o.optDouble("fi", 0.0), o.optDouble("p", 0.0), o.optDouble("f", 0.0), items,
                        mealSnack = o.optBoolean("ms", false), forLowG = o.optDouble("lo", 0.0)
                    )
                )
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "FoodLog: could not read saved log: ${e.message}")
            log.clear()
        }
    }

    private companion object {

        const val DAY_MS = 24 * 60 * 60_000L
    }
}
