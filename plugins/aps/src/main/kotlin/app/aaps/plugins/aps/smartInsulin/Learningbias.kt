package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.IntKey

/**
 * One dial across the meal/UAM learners: how hard they chase highs, and how readily they call a
 * spike or a stall worth acting on.
 *
 * Why a single dial rather than three. The ratio between a learner's strengthen step and its weaken
 * step is what decides WHERE it settles, not merely how fast it gets there — mode ISF strengthening
 * 2.5% and weakening 5% converges on a place where lows are about half as common as highs. That
 * ratio is a judgement about risk appetite, and it belongs to the user. The learners also arbitrate
 * against each other (mode ISF owns the spike, DURA owns the stall, the entry fraction owns the
 * shape), so setting them apart would have them pulling in different directions on one episode.
 *
 * Three things move together, each in the direction the position name implies:
 *
 *   - [strengthenScale]: the size of every step that ADDS insulin.
 *   - [barScale]: how big and how long an excursion has to be before it counts — the spike margin,
 *     the sustained-spike window, the ended-high margin, the stall length. Reactive shrinks these
 *     bars, so smaller and shorter excursions are acted on.
 *   - [safetyScale]: the size of every step that REMOVES insulin after a low or a near-low.
 *
 * The one asymmetry that is not user-tunable: [safetyScale] never goes below 1.0. Reactive makes
 * the learners chase highs harder and notice smaller ones; it does NOT make them slower to back off
 * after a low. Conservative moves both — smaller strengthens and bigger corrections — so it is a
 * genuine safety setting, while reactive is bounded. Anything that doses insulin should only let a
 * user turn the safety response up.
 *
 * Bars are floored well short of zero, and for a reason this project has already learned the hard
 * way: UAM cannot fire until BG is climbing, so every UAM episode spikes by construction, and a
 * sustained-stall requirement is what stops a normal spike being read as under-dosing. Reactive
 * shortens that requirement; it never removes it.
 */
enum class LearningBias(val label: String, val bias: Double) {
    VERY_CONSERVATIVE("Very conservative", -1.0),
    CONSERVATIVE("Conservative", -0.5),
    NEUTRAL("Neutral", 0.0),
    REACTIVE("Reactive", 0.5),
    VERY_REACTIVE("Very reactive", 1.0);

    /** Multiplier on every insulin-adding step. 0.6× at the conservative end, 1.6× at the other. */
    val strengthenScale: Double get() = 1.0 + (if (bias >= 0) 0.6 * bias else 0.4 * bias)

    /**
     * Multiplier on "how high" and "how long" an excursion must be to count. Reactive shrinks the
     * bars to 0.7× (a 3mmol/30min spike bar becomes 2.1mmol/21min); conservative raises them to
     * 1.3×. Deliberately a gentler range than the steps: these decide WHETHER an episode is judged
     * at all, and a bar low enough to catch ordinary meal noise would feed every learner garbage.
     */
    val barScale: Double get() = 1.0 - 0.3 * bias

    /** Multiplier on every insulin-removing step. Never below 1.0 — see the class note. */
    val safetyScale: Double get() = if (bias >= 0) 1.0 else 1.0 + 0.6 * -bias

    companion object {
        fun of(ordinalValue: Int): LearningBias = entries.getOrElse(ordinalValue) { NEUTRAL }

        fun from(sp: SP): LearningBias =
            of(sp.getInt(IntKey.ApsSmartInsulinLearningBias.key, IntKey.ApsSmartInsulinLearningBias.defaultValue))
    }
}

/** Scales a step that adds insulin, given as a multiplier below 1.0 (e.g. ISF ×0.975). */
internal fun scaleStrengthenStep(step: Double, bias: LearningBias): Double =
    1.0 - (1.0 - step) * bias.strengthenScale

/** Scales a step that removes insulin, given as a multiplier above 1.0 (e.g. ISF ×1.05). */
internal fun scaleSafetyStep(step: Double, bias: LearningBias): Double =
    1.0 + (step - 1.0) * bias.safetyScale

/** Scales a step that adds insulin, given as an additive amount (e.g. entry fraction +0.03). */
internal fun scaleStrengthenAmount(amount: Double, bias: LearningBias): Double = amount * bias.strengthenScale

/** Scales a step that removes insulin, given as an additive amount (e.g. entry fraction −0.06). */
internal fun scaleSafetyAmount(amount: Double, bias: LearningBias): Double = amount * bias.safetyScale
