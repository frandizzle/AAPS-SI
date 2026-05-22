package app.aaps.plugins.aps.smartInsulin.ice

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the set of currently-active announced meals.
 *
 * ## Layered model
 *
 * A real meal isn't one monolithic event — it's often a sequence: main course at
 * T+0, dessert at T+30, coffee+sugar at T+60. Each addition has its own
 * absorption timeline. This manager represents each such addition as an
 * [AnnouncedMeal] *layer*. The aggregate ICE rate at any moment is the sum of
 * each layer's contribution evaluated at *its own* age.
 *
 * Layers expire independently — when a layer passes its
 * [AnnouncedMeal.effectiveTotalDurationMin], it's removed. When all layers
 * expire, the active-meal state goes empty.
 *
 * Each layer has a stable identity via its [AnnouncedMeal.announceTimestampMs]
 * — used for per-layer edit/cancel from the UI.
 *
 * ## Persistence
 *
 * Layers survive app updates / reboots via SharedPreferences. The format is
 * versioned (currently v2). Old v1 single-meal storage is read transparently
 * — anything stored before this refactor restores as a single layer.
 */
@Singleton
class AnnouncedMealManager @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val sp: SP,
    private val dateUtil: DateUtil
) {

    companion object {
        private const val SP_KEY = "smartinsulin_active_announced_meal"
        private const val SERIAL_VERSION_V1 = "v1"
        private const val SERIAL_VERSION_V2 = "v2"
        private const val FIELD_DELIM = "|"
    }

    private val _activeMeals = MutableStateFlow<List<AnnouncedMeal>>(restoreFromSp())

    /** Observable handle for the UI — empty list when no meal is active. */
    val activeMeals: StateFlow<List<AnnouncedMeal>> = _activeMeals.asStateFlow()

    init {
        val restored = _activeMeals.value
        if (restored.isNotEmpty()) {
            aapsLogger.debug(LTag.APS,
                             "AnnouncedMealManager: restored ${restored.size} layer(s) from SP — " +
                                 restored.joinToString(" | ") {
                                     "[carbs=${it.carbsG}g protein=${it.proteinG}g fat=${it.fatG}g GI=${it.giBucket.label} " +
                                         "age=${((dateUtil.now() - it.announceTimestampMs) / 60_000L).toInt()}min]"
                                 })
        }
    }

    // ── Convenience aggregators ────────────────────────────────────────────────

    /** True iff any layer is non-expired at the given moment. */
    fun hasActiveMeals(nowMs: Long): Boolean = _activeMeals.value.any { it.isActive(nowMs) }

    /**
     * Maximum commitment fraction across all active layers. The loop's confidence
     * floor uses this: a 100% committed main course plus a 50% snack still gives
     * the loop full confidence to act on the announced meal as a whole.
     * Returns 0.0 when no layers are active.
     */
    fun maxCommitmentFraction(nowMs: Long): Double =
        _activeMeals.value.filter { it.isActive(nowMs) }
            .maxOfOrNull { it.commitmentFraction } ?: 0.0

    /**
     * Display string for the GI bucket(s) in the currently-active set.
     * Returns the bucket name if all layers share the same GI bucket,
     * "MIXED" if multiple buckets are active, or "" if no layers.
     */
    fun aggregatedGiBucketLabel(nowMs: Long): String {
        val active = _activeMeals.value.filter { it.isActive(nowMs) }
        val distinct = active.map { it.giBucket }.distinct()
        return when {
            distinct.isEmpty() -> ""
            distinct.size == 1 -> distinct.first().name
            else               -> "MIXED"
        }
    }

    /**
     * Age in minutes of the EARLIEST active layer — i.e. "how long has the meal
     * been running overall". For UX continuity: when a user adds dessert to a
     * 90-min-old main course, the overall meal is still 90 min old; the dessert
     * is just a 0-min-old addition within it.
     * Returns 0 when no layers are active.
     */
    fun earliestAgeMinutes(nowMs: Long): Int {
        val active = _activeMeals.value.filter { it.isActive(nowMs) }
        val earliest = active.minByOrNull { it.announceTimestampMs } ?: return 0
        return ((nowMs - earliest.announceTimestampMs) / 60_000L).toInt()
    }

    /**
     * Minutes until the LAST-expiring layer ends — i.e. "how long until the meal
     * is fully done". Returns 0 when no layers are active.
     */
    fun longestRemainingMinutes(nowMs: Long): Int {
        val active = _activeMeals.value.filter { it.isActive(nowMs) }
        if (active.isEmpty()) return 0
        return active.maxOf { meal ->
            val expiryMs = meal.announceTimestampMs + meal.effectiveTotalDurationMin * 60_000L
            ((expiryMs - nowMs) / 60_000L).toInt().coerceAtLeast(0)
        }
    }

    // ── Mutators ───────────────────────────────────────────────────────────────

    /**
     * Add a new meal layer onto whatever is already active. The new layer keeps
     * its own announce time, GI bucket, commitment, and absorption window — no
     * interference with existing layers.
     */
    fun addLayer(meal: AnnouncedMeal) {
        val cur = _activeMeals.value.toMutableList()
        // First sweep expired layers so we don't accumulate dead state
        val now = dateUtil.now()
        cur.removeAll { !it.isActive(now) }
        cur.add(meal)
        _activeMeals.value = cur.toList()
        aapsLogger.debug(LTag.APS,
                         "AnnouncedMealManager: layer added — carbs=${meal.carbsG}g protein=${meal.proteinG}g " +
                             "fat=${meal.fatG}g GI=${meal.giBucket.label} commitment=${meal.commitmentPct}% " +
                             "(total layers: ${cur.size})")
        writeToSp(_activeMeals.value)
    }

    /**
     * Backward-compat entry point. By default behaves as [addLayer] — appends
     * the new meal as a new layer. Pass `replace = true` to clear all existing
     * layers first (the "I entered wrong macros, start over" affordance).
     */
    fun announceMeal(meal: AnnouncedMeal, replace: Boolean = false) {
        if (replace) {
            aapsLogger.debug(LTag.APS,
                             "AnnouncedMealManager: replacing all layers — was ${_activeMeals.value.size}, now 1")
            _activeMeals.value = listOf(meal)
            writeToSp(_activeMeals.value)
        } else {
            addLayer(meal)
        }
    }

    /** Cancel a single layer by ID (announceTimestampMs). No-op if not found. */
    fun clearLayer(layerId: Long) {
        val before = _activeMeals.value
        val after = before.filterNot { it.announceTimestampMs == layerId }
        if (after.size != before.size) {
            _activeMeals.value = after
            writeToSp(after)
            aapsLogger.debug(LTag.APS,
                             "AnnouncedMealManager: layer cleared (id=$layerId, remaining=${after.size})")
        }
    }

    /** Discard ALL active layers. Used when the user cancels the whole meal. */
    fun clearAllMeals() {
        if (_activeMeals.value.isNotEmpty()) {
            aapsLogger.debug(LTag.APS,
                             "AnnouncedMealManager: all layers cleared (${_activeMeals.value.size} dropped)")
            _activeMeals.value = emptyList()
            writeToSp(emptyList())
        }
    }

    /** Backward-compat alias for [clearAllMeals]. */
    fun clearMeal() = clearAllMeals()

    /**
     * Edit a single layer's macros/GI bucket WITHOUT resetting its announce time.
     * No-op if the layer ID is not found.
     */
    fun editLayer(layerId: Long, carbsG: Double, proteinG: Double, fatG: Double, giBucketName: String) {
        val cur = _activeMeals.value
        val idx = cur.indexOfFirst { it.announceTimestampMs == layerId }
        if (idx < 0) return
        val existing = cur[idx]
        val newBucket = when (giBucketName.uppercase()) {
            "FAST"   -> GiBucket.FAST
            "MEDIUM" -> GiBucket.MEDIUM
            "SLOW"   -> GiBucket.SLOW
            else     -> existing.giBucket
        }
        val edited = existing.copy(
            carbsG   = carbsG.coerceAtLeast(0.0),
            proteinG = proteinG.coerceAtLeast(0.0),
            fatG     = fatG.coerceAtLeast(0.0),
            giBucket = newBucket
            // announceTimestampMs deliberately preserved
        )
        val updated = cur.toMutableList().apply { set(idx, edited) }
        _activeMeals.value = updated
        writeToSp(updated)
        aapsLogger.debug(LTag.APS,
                         "AnnouncedMealManager: layer edited (id=$layerId) — carbs=${edited.carbsG}g " +
                             "protein=${edited.proteinG}g fat=${edited.fatG}g GI=${edited.giBucket.label}")
    }

    /**
     * Convenience editor for the common "single active layer" case — edits the
     * only active layer. No-op if zero or multiple layers are active (caller
     * should use [editLayer] with a specific ID when ambiguous).
     */
    fun editActiveMeal(carbsG: Double, proteinG: Double, fatG: Double, giBucketName: String) {
        val now = dateUtil.now()
        val active = _activeMeals.value.filter { it.isActive(now) }
        if (active.size != 1) {
            aapsLogger.debug(LTag.APS,
                             "AnnouncedMealManager: editActiveMeal called with ${active.size} active layers — ambiguous, ignoring")
            return
        }
        editLayer(active.first().announceTimestampMs, carbsG, proteinG, fatG, giBucketName)
    }

    // ── Queries — aggregated across active layers ──────────────────────────────

    /**
     * Expected aggregate ICE rate (mg/dL/h) at [nowMs] — sum of each active
     * layer's contribution evaluated at its own age.
     *
     * Side effect: prunes layers that have passed their absorption window.
     * Layers whose age is negative relative to the query (a layer announced
     * after [nowMs] — happens when the loop queries with [GlucoseStatus.date]
     * which lags slightly behind wall-clock) are left in place — they're not
     * expired, just not yet eligible to contribute.
     */
    fun expectedIceMgdlPerHour(
        nowMs: Long,
        carbLoadPerG: Double = MealCurveBuilder.DEFAULT_CARB_LOAD_PER_G_MGDL
    ): Double {
        val cur = _activeMeals.value
        if (cur.isEmpty()) return 0.0

        // Sweep expired layers (real expiry — age past the window end). Don't
        // touch layers with negative age (future timestamps from CGM lag).
        val kept = cur.filter { meal ->
            val ageMin = (nowMs - meal.announceTimestampMs) / 60_000.0
            ageMin <= meal.effectiveTotalDurationMin.toDouble()
        }
        if (kept.size != cur.size) {
            val dropped = cur.size - kept.size
            aapsLogger.debug(LTag.APS,
                             "AnnouncedMealManager: $dropped layer(s) auto-cleared (expired)")
            _activeMeals.value = kept
            writeToSp(kept)
        }

        if (kept.isEmpty()) return 0.0
        return kept.sumOf { meal ->
            val ageMin = (nowMs - meal.announceTimestampMs) / 60_000.0
            if (ageMin < 0.0) 0.0
            else MealCurveBuilder.expectedIceMgdlPerHourAt(meal, nowMs, carbLoadPerG)
        }
    }

    /**
     * Remaining macros summed across all active layers. Each layer's remaining
     * is computed at its own age (so a 10-min-old layer's carbs are barely
     * absorbed, while a 90-min-old layer's are mostly gone). Returns null when
     * no layers are active.
     */
    fun remainingMacros(nowMs: Long): MealRemaining? {
        val active = _activeMeals.value.filter { it.isActive(nowMs) }
        if (active.isEmpty()) return null
        var totalC = 0.0
        var totalP = 0.0
        var totalF = 0.0
        for (meal in active) {
            val ageMin = (nowMs - meal.announceTimestampMs) / 60_000.0
            val carbAbsorbed    = MealCurveBuilder.carbAbsorbedFraction(meal, ageMin)
            val plateauAbsorbed = MealCurveBuilder.plateauAbsorbedFraction(ageMin, meal.fatProteinDurationMin)
            totalC += meal.carbsG   * (1.0 - carbAbsorbed)
            totalP += meal.proteinG * (1.0 - plateauAbsorbed)
            totalF += meal.fatG     * (1.0 - plateauAbsorbed)
        }
        return MealRemaining(totalC, totalP, totalF)
    }

    /**
     * Aggregated remaining macros formatted for the home-screen meal line.
     * Returns empty string when no layers are active so callers can
     * unconditionally concatenate.
     */
    fun macrosOverviewSuffix(nowMs: Long): String {
        val r = remainingMacros(nowMs) ?: return ""
        val parts = buildList {
            if (r.carbsG   > 0.5) add("COB ${"%.0f".format(r.carbsG)}g")
            if (r.proteinG > 0.5) add("P ${"%.0f".format(r.proteinG)}g")
            if (r.fatG     > 0.5) add("F ${"%.0f".format(r.fatG)}g")
        }
        return if (parts.isEmpty()) "" else " · " + parts.joinToString(" · ")
    }

    /**
     * Sample the aggregate remaining curve at regular intervals. Sums each
     * layer's [MealCurveBuilder.expectedIceMgdlPerHourAt] at its own age.
     */
    fun sampleRemainingCurve(
        nowMs: Long,
        sampleIntervalMin: Int = 5,
        carbLoadPerG: Double = MealCurveBuilder.DEFAULT_CARB_LOAD_PER_G_MGDL
    ): List<Pair<Int, Double>> {
        val active = _activeMeals.value.filter { it.isActive(nowMs) }
        if (active.isEmpty()) return emptyList()
        val horizonMin = longestRemainingMinutes(nowMs)
        if (horizonMin <= 0) return emptyList()
        val out = mutableListOf<Pair<Int, Double>>()
        var t = 0
        while (t <= horizonMin) {
            val sampleAt = nowMs + t * 60_000L
            val rate = active.sumOf { meal ->
                MealCurveBuilder.expectedIceMgdlPerHourAt(meal, sampleAt, carbLoadPerG)
            }
            out.add(t to rate)
            t += sampleIntervalMin
        }
        return out
    }

    // ── Persistence ────────────────────────────────────────────────────────────
    //
    // V2 format: multi-line text. First line is the version marker; each subsequent
    // line is one layer's payload using the same field layout as v1:
    //   v2
    //   carbsG|proteinG|fatG|giBucketName|commitmentPct|announceTimestampMs
    //   carbsG|proteinG|fatG|giBucketName|commitmentPct|announceTimestampMs
    //   ...
    //
    // V1 format (legacy single-line): preserved for backward-compat read only.
    //   v1|carbsG|proteinG|fatG|giBucketName|commitmentPct|announceTimestampMs
    //
    // On migration the legacy v1 entry reads as a single-layer list and the next
    // write produces v2. No explicit migration step required.

    private fun writeToSp(layers: List<AnnouncedMeal>) {
        try {
            if (layers.isEmpty()) {
                sp.remove(SP_KEY)
                aapsLogger.debug(LTag.APS, "AnnouncedMealManager: SP cleared (key=$SP_KEY)")
                return
            }
            val serialized = buildString {
                appendLine(SERIAL_VERSION_V2)
                layers.forEachIndexed { i, m ->
                    append(m.carbsG)
                    append(FIELD_DELIM); append(m.proteinG)
                    append(FIELD_DELIM); append(m.fatG)
                    append(FIELD_DELIM); append(m.giBucket.name)
                    append(FIELD_DELIM); append(m.commitmentPct)
                    append(FIELD_DELIM); append(m.announceTimestampMs)
                    if (i < layers.size - 1) appendLine()
                }
            }
            sp.putString(SP_KEY, serialized)
            aapsLogger.debug(LTag.APS,
                             "AnnouncedMealManager: SP persist OK (key=$SP_KEY, layers=${layers.size})")
        } catch (e: Throwable) {
            aapsLogger.error(LTag.APS,
                             "AnnouncedMealManager: SP write FAILED — ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Restore the active layers from SharedPreferences on startup. Handles both
     * v1 (single line: `v1|...`) and v2 (multi-line) formats. Any parse failure
     * skips the offending layer but keeps the rest. Returns empty list when:
     *   - No SP entry exists
     *   - The entry is unrecognised / corrupt
     *   - All restored layers are past their absorption windows
     */
    private fun restoreFromSp(): List<AnnouncedMeal> {
        val raw = try {
            sp.getString(SP_KEY, "")
        } catch (e: Throwable) {
            aapsLogger.error(LTag.APS,
                             "AnnouncedMealManager: SP read FAILED — ${e.javaClass.simpleName}: ${e.message}")
            return emptyList()
        }
        if (raw.isBlank()) {
            aapsLogger.debug(LTag.APS, "AnnouncedMealManager: no SP entry at startup (key=$SP_KEY)")
            return emptyList()
        }
        aapsLogger.debug(LTag.APS, "AnnouncedMealManager: SP entry found, attempting restore")
        return try {
            val lines = raw.split('\n').map { it.trim() }.filter { it.isNotBlank() }
            val parsed = when {
                lines.isEmpty()                       -> emptyList()
                lines[0].startsWith("$SERIAL_VERSION_V1$FIELD_DELIM") -> {
                    // v1 single-line; parse the remainder after "v1|"
                    val payload = lines[0].removePrefix("$SERIAL_VERSION_V1$FIELD_DELIM")
                    listOfNotNull(parseLayerPayload(payload))
                }
                lines[0] == SERIAL_VERSION_V2          -> {
                    lines.drop(1).mapNotNull { parseLayerPayload(it) }
                }
                else                                   -> emptyList()
            }
            // Drop any restored layers that are already expired
            val now = dateUtil.now()
            val active = parsed.filter { it.isActive(now) }
            if (active.size != parsed.size) {
                aapsLogger.debug(LTag.APS,
                                 "AnnouncedMealManager: ${parsed.size - active.size} restored layer(s) " +
                                     "already expired, dropped")
            }
            // If the SP entry yielded NO usable layers — corrupt blob, unknown
            // version prefix, or every layer already expired — clear the SP
            // entry so a fresh process doesn't keep re-parsing the same dead
            // data on every startup.
            if (active.isEmpty()) {
                try { sp.remove(SP_KEY) } catch (_: Throwable) { /* swallow */ }
            }
            active
        } catch (e: Throwable) {
            aapsLogger.error(LTag.APS,
                             "AnnouncedMealManager: SP parse FAILED — ${e.javaClass.simpleName}: ${e.message}")
            try { sp.remove(SP_KEY) } catch (_: Throwable) { /* swallow */ }
            emptyList()
        }
    }

    private fun parseLayerPayload(payload: String): AnnouncedMeal? {
        return try {
            val parts = payload.split(FIELD_DELIM)
            if (parts.size != 6) return null
            val carbsG     = parts[0].toDouble()
            val proteinG   = parts[1].toDouble()
            val fatG       = parts[2].toDouble()
            val giBucket   = when (parts[3].uppercase()) {
                "FAST"   -> GiBucket.FAST
                "MEDIUM" -> GiBucket.MEDIUM
                "SLOW"   -> GiBucket.SLOW
                else     -> return null
            }
            val commitment = parts[4].toInt()
            val timestamp  = parts[5].toLong()
            AnnouncedMeal(carbsG, proteinG, fatG, giBucket, commitment, timestamp)
        } catch (_: Throwable) {
            null
        }
    }
}

/** Aggregated remaining macros across all active layers. */
data class MealRemaining(
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double
)