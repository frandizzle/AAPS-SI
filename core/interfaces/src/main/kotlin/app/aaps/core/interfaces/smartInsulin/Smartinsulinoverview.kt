package app.aaps.core.interfaces.smartInsulin

/**
 * Exposes SmartInsulin state to the Overview screen.
 *
 * Lives in core/interfaces so plugins/main (OverviewFragment) can inject it
 * without a direct compile dependency on plugins/aps.
 * Implementation: SmartInsulinPlugin in plugins/aps.
 */
interface SmartInsulinOverview {

    /**
     * Per-layer snapshot of an announced meal — exposed on [OverviewState] so UI
     * surfaces outside the plugin module (e.g. SmartMealDialog) can render and
     * edit the list of active meals without compile-time dependency on
     * plugins/aps. Mirrors [SmartInsulinPlugin.ActiveMealLayer] in shape but
     * lives in core/interfaces because the dialog can't import the plugin type.
     *
     * @property layerId           Layer identifier — equals the meal's announce
     *                             timestamp in ms. Pass to [clearMealLayer] /
     *                             [editMealLayer] to identify which layer to act on.
     * @property giBucketName      "FAST" / "MEDIUM" / "SLOW" — raw enum name
     * @property giLabel           User-facing GI label, e.g. "Medium GI"
     * @property ageMin            Minutes since this layer was announced
     * @property remainingMin      Minutes left in the absorption window
     * @property totalDurationMin  Full absorption window from announce to expiry
     * @property totalCarbsG       Carbs originally entered (or after edit) for this layer
     * @property totalProteinG     Protein originally entered (or after edit)
     * @property totalFatG         Fat originally entered (or after edit)
     * @property remainingCarbsG   Carbs not yet absorbed (per MealCurveBuilder Gaussian CDF)
     * @property remainingProteinG Protein not yet "absorbed" (per cosine-smoothed plateau)
     * @property remainingFatG     Fat not yet "absorbed" (same plateau shape, separate grams)
     * @property commitmentPct     0–100, user's stated confidence in the macros
     */
    data class MealLayerInfo(
        val layerId:           Long,
        val giBucketName:      String,
        val giLabel:           String,
        val ageMin:            Int,
        val remainingMin:      Int,
        val totalDurationMin:  Int,
        val totalCarbsG:       Double,
        val totalProteinG:     Double,
        val totalFatG:         Double,
        val remainingCarbsG:   Double,
        val remainingProteinG: Double,
        val remainingFatG:     Double,
        val commitmentPct:     Int
    )

    /**
     * Snapshot of all state needed to render the Overview info cell.
     * Computed once per invoke() cycle and cached — safe to read from UI thread.
     */
    data class OverviewState(
        /** "Meal: Lunch 177m" or "Meal: Fasting" */
        val modeLine: String,
        /** "PB2 active: 30m" or null if no PB2 pending */
        val pb2Line: String?,
        /** "PB3 active: 25m" or null if no PB3 pending. PB3 will show "waiting for PB2"
         *  until PB2 fires, then count down its own delay relative to PB2's fire time. */
        val pb3Line: String?,
        /** null = full learning active
         *  "limited" = meal mode (only DIA/peak learning)
         *  "off: <reason>" = fully suppressed */
        val learningState: String,
        /** True when in FASTING mode (no meal overrides active) */
        val isFasting: Boolean = true,
        /** True when the algorithm is actively learning (Fasting + no other blocks) */
        val isLearning: Boolean = true,
        /**
         * Per-layer snapshot of all currently-active announced meals. Empty when
         * no meals are active. Pushed each invoke() cycle (for fresh age values)
         * and also pushed immediately after any meal mutation ([announceMeal],
         * [clearAnnouncedMeal], [clearMealLayer], [editActiveMeal],
         * [editMealLayer]) so the dialog sees changes without waiting for the
         * next loop cycle. Defaulted to emptyList() for source compatibility
         * with any existing OverviewState construction call sites.
         */
        val activeMealLayers: List<MealLayerInfo> = emptyList()
    )

    fun overviewState(): OverviewState

    val overviewStateFlow: kotlinx.coroutines.flow.StateFlow<OverviewState?>

    /**
     * Announce a meal to the loop. Replaces any active announced meal.
     *
     * The loop uses the macros and GI bucket to build an expected ICE absorption curve,
     * which pre-positions dosing before observed ICE catches up to reality. Lives on this
     * interface (rather than a separate one) so the SmartMealDialog ViewModel — which only
     * sees [SmartInsulinOverview] via `activePlugin.smartInsulin` — can call it without a
     * compile-time dependency on the plugins/aps module.
     *
     * @param carbsG total carbohydrates in grams (≥ 0).
     * @param proteinG total protein in grams (≥ 0). Contributes a late hump (~3h) because
     *                 a fraction of protein converts to glucose.
     * @param fatG total fat in grams (≥ 0). Currently used only for the user's record —
     *             its delaying effect on absorption is captured by GI bucket selection.
     * @param giBucketName one of "FAST" / "MEDIUM" / "SLOW" (case-insensitive). FAST = high
     *                     GI (juice, candy, fast carbs). MEDIUM = standard (bread, rice,
     *                     pasta — default). SLOW = low GI (pizza, fatty / large meals,
     *                     bimodal absorption). String rather than enum to avoid a
     *                     core/interfaces dependency on the plugin module.
     * @param commitmentPct user's confidence in these numbers, 0–100. Scales the expected
     *                      curve linearly. 100 = weighed meal / certain; lower values
     *                      reduce the loop's reliance on the prediction.
     *
     * Default behaviour is ADDITIVE — the new meal becomes a layer on top of any
     * existing active meal, with its own independent absorption timeline. Pass
     * `replaceExisting = true` to clear all existing layers first (for fixing
     * mistakes — "I entered the wrong macros, start over").
     */
    fun announceMeal(
        carbsG: Double,
        proteinG: Double,
        fatG: Double,
        giBucketName: String,
        commitmentPct: Int,
        replaceExisting: Boolean = false
    )

    /** Clear ALL active meal layers. The loop reverts to observed-only ICE behaviour. */
    fun clearAnnouncedMeal()

    /**
     * Clear a single layer by its ID (the layer's announce timestamp in ms).
     * No-op if no layer with that ID is active.
     */
    fun clearMealLayer(layerId: Long)

    /**
     * Edit the macros and/or GI bucket of the currently-active announced meal,
     * preserving its announce timestamp so absorption tracking continues from
     * where it was. Behaves as a no-op when zero or multiple layers are active
     * — for the multi-layer case, the UI should target a specific layer ID via
     * an editLayer API (TBD).
     *
     * Use this when the user discovers mid-meal that their initial macro
     * estimate was wrong. A cancel + fresh announce would reset the absorption
     * timer and double-count the early portion of the meal.
     *
     * @param carbsG new total carbohydrates in grams
     * @param proteinG new total protein in grams
     * @param fatG new total fat in grams
     * @param giBucketName "FAST" / "MEDIUM" / "SLOW" (case-insensitive); unrecognised
     *                     values keep the current bucket
     */
    fun editActiveMeal(
        carbsG: Double,
        proteinG: Double,
        fatG: Double,
        giBucketName: String
    )

    /**
     * Edit a specific announced meal layer by ID, preserving its absorption
     * timer (announceTimestampMs is NOT reset). The layer ID is the value
     * returned in [MealLayerInfo.layerId] (which equals the meal's announce
     * timestamp in ms). No-op if no layer with that ID is active.
     *
     * Unlike [editActiveMeal] this works even when multiple layers are active,
     * because the caller picks which one to edit via the explicit ID. UI
     * surfaces that show a per-layer editable list (e.g. the dialog's EDIT
     * mode) should use this method rather than [editActiveMeal].
     *
     * Default no-op so any existing implementations stay source-compatible
     * without changes.
     *
     * @param layerId      the layer's [MealLayerInfo.layerId]
     * @param carbsG       new total carbohydrates in grams (≥ 0)
     * @param proteinG     new total protein in grams (≥ 0)
     * @param fatG         new total fat in grams (≥ 0)
     * @param giBucketName "FAST" / "MEDIUM" / "SLOW" (case-insensitive); unrecognised
     *                     values keep the layer's current bucket
     */
    fun editMealLayer(
        layerId: Long,
        carbsG: Double,
        proteinG: Double,
        fatG: Double,
        giBucketName: String
    ) {}

    // ── ice-step43: bolus calculator breakdowns ─────────────────────────────
    // Surfaces for the SmartMeal dialog's [Calc] buttons. Methods return a
    // structured breakdown so the UI can render both the final number AND a
    // long-press tooltip explaining the math (carb cover, correction, trend,
    // IOB, etc). Returns null when the plugin can't produce a meaningful
    // answer — loop data stale, profile incomplete, or zero macros. The VM is
    // expected to surface "data not available" to the user and leave the
    // manual entry box untouched.

    /**
     * Breakdown of a PB1 (carb-driven) pre-bolus calculation.
     *
     * The final `resultU` is the raw intent — it has been floored at 0.0 but
     * is NOT clamped to the user's max-pre-bolus preference. The VM clamps and
     * snaps to bolus step before writing to the entry field; the tooltip
     * shows the pre-clamp value so the user can see when a cap was hit.
     *
     * @property resultU       Final pre-clamp dose in U (≥ 0).
     * @property carbBolusU    Full meal cover (carbsG / IC) in U.
     * @property correctionU   max(0, BG − target) / ISF in U.
     * @property trendNudgeU   (3 × shortAvgDelta) / ISF in U. Can be negative
     *                         when BG is falling — pulls the total down.
     * @property iobU          Current IOB in U, subtracted from the total so
     *                         existing insulin isn't double-counted.
     * @property carbFraction  Fraction of `carbBolusU` delivered up front
     *                         (currently 0.8 — flat across GI buckets).
     */
    data class PreBolus1Breakdown(
        val resultU:      Double,
        val carbBolusU:   Double,
        val correctionU:  Double,
        val trendNudgeU:  Double,
        val iobU:         Double,
        val carbFraction: Double
    )

    /**
     * Breakdown of a PB2 or PB3 (protein/fat-driven) pre-bolus calculation.
     *
     * Deliberately does NOT subtract IOB — PB2 fires 60–120 min after the
     * meal and PB3 1.5–4 h after, so present-moment IOB isn't predictive of
     * IOB at fire-time. The normal SMB cycle handles correction-of-the-moment
     * when these actually deliver; this calc just sizes the
     * glucose-equivalent load.
     *
     * @property resultU          Final pre-clamp dose in U (≥ 0).
     * @property proteinGEg       Glucose-equivalent grams from protein (proteinG × 0.5).
     * @property fatGEg           Glucose-equivalent grams from fat (fatG × 0.1).
     * @property totalPfBolusU    (proteinGEg + fatGEg) / IC in U — the total
     *                            P/F load split across PB2 + PB3.
     * @property pfSplitFraction  0.6 for PB2, 0.4 for PB3.
     */
    data class PreBolusPfBreakdown(
        val resultU:         Double,
        val proteinGEg:      Double,
        val fatGEg:          Double,
        val totalPfBolusU:   Double,
        val pfSplitFraction: Double
    )

    /**
     * Calculate a PB1 (carb-driven) pre-bolus from current loop state +
     * the user's announced carb load. Returns null when:
     *   - loop data is stale (no invoke() in the last ~6 min)
     *   - profile values (ISF, IC, target) are missing or zero
     *   - `carbsG` is 0 — there's no meal to pre-bolus for
     *
     * `giBucketName` is currently unused in the formula (carbFraction is a
     * flat 0.8) but the parameter stays in the contract for future use —
     * e.g. per-bucket fractions exposed as a preference.
     *
     * Default no-op (returns null) so existing implementations stay
     * source-compatible.
     */
    fun calculatePreBolus1(carbsG: Double, giBucketName: String): PreBolus1Breakdown? = null

    /**
     * Calculate a PB2 (protein/fat) pre-bolus. Returns null when loop data
     * is stale, IC is missing, or protein+fat is 0.
     */
    fun calculatePreBolus2(proteinG: Double, fatG: Double): PreBolusPfBreakdown? = null

    /**
     * Calculate a PB3 (late-meal protein/fat) pre-bolus. Returns null on the
     * same conditions as [calculatePreBolus2]. Split is 0.4 of total P/F
     * load (vs. 0.6 for PB2) so the pair sums to one full P/F cover.
     */
    fun calculatePreBolus3(proteinG: Double, fatG: Double): PreBolusPfBreakdown? = null
}