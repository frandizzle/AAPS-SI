package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.smartInsulin.MealMode

/**
 * Which of the Protein/Fat ISF windows an hour resolves to.
 *
 * P/F is the one mode whose configured ISF is time-of-day dependent — a base value plus separate
 * Day, Night and Overnight overrides, each with its own hour range. Every other mode has a single
 * ISF, and for those this is always [NONE].
 *
 * It exists because the per-mode learners key their state by mode, and P/F being one enum entry
 * meant one learned multiplier averaged across all three windows. P/F is detected from a
 * stuck-high plateau, which skews overnight, so a correction earned overnight was being applied
 * to daytime P/F as well. Splitting the key by window lets each converge on its own evidence.
 *
 * [BASE] is a real window, not a null case: it is the hours that fall outside every configured
 * range, or inside one whose override is unset. Those hours dose from the base P/F ISF and so
 * deserve their own correction too.
 */
enum class PfWindow(val label: String) {
    NONE(""),
    BASE("P/F"),
    DAY("P/F Day"),
    NIGHT("P/F Night"),
    OVERNIGHT("P/F Overnight");

    companion object {

        /**
         * Storage key for a learner's per-mode state.
         *
         * Plain [MealMode.name] for everything except P/F, so existing state for every other mode
         * keeps loading unchanged. The separator is one that cannot occur in an enum name, so a
         * legacy "UAM_PROTEIN_FAT" record is always distinguishable from a windowed one — which is
         * what the migration in each learner's restore() keys off.
         */
        fun stateKey(mode: MealMode, window: PfWindow): String =
            if (mode == MealMode.UAM_PROTEIN_FAT && window != NONE) "${mode.name}#${window.name}"
            else mode.name

        /** Row label for the SI tab. P/F drops the "(UAM)" suffix — it is only ever a UAM mode,
         *  so the suffix carries no information and the window needs the width. */
        fun label(mode: MealMode, window: PfWindow): String =
            if (mode == MealMode.UAM_PROTEIN_FAT && window != NONE) window.label else mode.label

        /** Every window a P/F row can be filed under, for iterating the SI tab tables. */
        val PF_WINDOWS = listOf(DAY, NIGHT, OVERNIGHT, BASE)
    }
}
