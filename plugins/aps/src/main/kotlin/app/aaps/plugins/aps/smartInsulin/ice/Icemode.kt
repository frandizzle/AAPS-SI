package app.aaps.plugins.aps.smartInsulin.ice

/**
 * The active mode of the ICE system for this loop cycle.
 *
 * Distinguishes an explicitly announced meal (COB) from an unannounced BG
 * rise picked up by the IceTracker (UAM). The mode selects which set of
 * tuning constants drives the blend weight, learning-confidence threshold,
 * forward-projection decay timeline, and the aggression-adjust cap.
 *
 * COB-mode values come from the existing prefs:
 *   - `DoubleKey.ApsSmartInsulinIceUserWeight`
 *   - `DoubleKey.ApsSmartInsulinIceLearningBlockThreshold`
 *   - (no aggression cap — COB uses the global 0.3–2.0 coerce on aggressiveness)
 *
 * UAM-mode values come from the dedicated prefs (ice-step28):
 *   - `DoubleKey.ApsSmartInsulinUamIceUserWeight`         (default 0.3)
 *   - `DoubleKey.ApsSmartInsulinUamIceLearningBlockThreshold` (default 0.6)
 *   - `DoubleKey.ApsSmartInsulinUamIceAggressionCap`      (default 1.3)
 *   - `IntKey.ApsSmartInsulinUamIceDecayMinutes`          (default 60)
 *
 * ## Routing rules — see `SmartInsulinPlugin.currentIceMode(...)`:
 *   - **COB** : an announced meal is active for the current cycle. Wins over
 *     UAM even if observed momentum exceeds the meal curve — the asymmetric
 *     `max(observed, expected)` happens later in `iceMgdlPerHEffective`, not
 *     at the mode level.
 *   - **UAM** : no announced meal, but the IceTracker reports
 *     `observedIceMgdlPerH > 0`, AND it's not in `iceObservationDisabled`
 *     state (warmup, exercise, etc).
 *   - **NONE**: ICE tracker disabled, observed signal absent, or a hard
 *     disable reason is firing. No ICE-driven dosing this cycle.
 *
 * ## Why split UAM out from a unified ICE blob
 * An unannounced rise is meaningfully less certain than an explicit user
 * announcement — it could be dawn phenomenon, stress hyperglycemia, a
 * post-hypo rebound, or a real meal that wasn't entered. Treating both the
 * same lets a false-positive UAM detection drive the same dosing aggression
 * as a confirmed 30g lunch. Splitting modes lets UAM be tuned more
 * conservatively (lower user weight, higher confidence floor, aggression cap)
 * without dialling down the loop's response to actual announced meals.
 */
enum class IceMode {
    /** Announced meal active — see [SmartInsulinOverview.announceMeal]. */
    COB,

    /** Unannounced rise detected from observation — no meal layer active. */
    UAM,

    /** ICE not driving dosing this cycle (disabled, no signal, or in warmup). */
    NONE
}