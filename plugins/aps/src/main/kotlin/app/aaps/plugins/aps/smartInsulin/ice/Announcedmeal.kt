package app.aaps.plugins.aps.smartInsulin.ice

/**
 * Description of a meal that the user has announced to the loop.
 *
 * The loop uses these inputs to build an expected ICE curve via [MealCurveBuilder].
 * That curve is then used as a prior — the loop knows in advance that BG-raising
 * counteraction is incoming, and can pre-position dosing before observed ICE
 * catches up.
 *
 * ## Fields
 *
 * @property carbsG Total carbohydrates in grams.
 * @property proteinG Total protein in grams. Triggers a late-tail contribution
 *           because protein is partially converted to glucose over 2–4h
 *           (≈ 50% conversion is a common starting estimate).
 * @property fatG Total fat in grams. Fat slows absorption and pushes the GI
 *           bucket later — it doesn't itself raise BG directly in the short term
 *           but shifts the shape of the carb response.
 * @property giBucket User-selected GI category. Drives peak/duration of the curve.
 * @property commitmentPct How sure the user is about these numbers, 0–100.
 *           Multiplies the curve magnitude — 50% commitment = the loop weights
 *           the expected curve at half strength. 100% means the user is certain
 *           (weighed meal, repeat recipe).
 * @property announceTimestampMs The system clock time when the meal was announced.
 *           This is the reference point for "now we're N minutes into the meal".
 */
data class AnnouncedMeal(
    val carbsG: Double,
    val proteinG: Double = 0.0,
    val fatG: Double = 0.0,
    val giBucket: GiBucket,
    val commitmentPct: Int,
    val announceTimestampMs: Long
) {
    init {
        require(carbsG >= 0.0)          { "carbsG must be ≥ 0, got $carbsG" }
        require(proteinG >= 0.0)        { "proteinG must be ≥ 0, got $proteinG" }
        require(fatG >= 0.0)            { "fatG must be ≥ 0, got $fatG" }
        require(commitmentPct in 0..100) { "commitmentPct must be 0..100, got $commitmentPct" }
    }

    /** Commitment as a 0.0–1.0 multiplier. */
    val commitmentFraction: Double get() = commitmentPct / 100.0

    /** Is this meal still within its absorption window at the given moment? */
    fun isActive(nowMs: Long): Boolean {
        val ageMin = (nowMs - announceTimestampMs) / 60_000.0
        return ageMin in 0.0..giBucket.totalDurationMinutes.toDouble()
    }
}