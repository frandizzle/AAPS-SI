package app.aaps.plugins.aps.smartInsulin

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The top of the Circadian 24h card: the profile next to what was learned, and the buttons that
 * move the learning into the profile.
 */
internal fun buildLearnedProfileCard(s: SmartInsulinPlugin.LearnedProfileStatus, isMmol: Boolean): SiCardContent =
    SiCardBuilder().also { it.fillLearnedProfileCard(s, isMmol) }.build()

private fun SiCardBuilder.fillLearnedProfileCard(s: SmartInsulinPlugin.LearnedProfileStatus, isMmol: Boolean) {
    val p = s.plan
    val isfUnit = if (isMmol) "mmol/U" else "mg/dL/U"

    addRow(
        "ISF: profile ${isf(p.profileAverageIsfMgdl, isMmol)} → learned ${isf(p.newAverageIsfMgdl, isMmol)} $isfUnit",
        "Average of the day. ${p.isfHoursLearned} of 24 hours have enough data; the others keep the profile value.",
        tone(p.newAverageIsfMgdl / p.profileAverageIsfMgdl, isf = true)
    )
    addRow(
        "Basal: profile ${"%.1f".format(p.profileDailyBasal)} → learned ${"%.1f".format(p.newDailyBasal)} U a day",
        "${p.basalHoursLearned} of 24 hours have enough data; the others keep the profile value.",
        tone(p.newDailyBasal / p.profileDailyBasal, isf = false)
    )
    if (s.isfHoursAtLimit > 0 || s.basalHoursAtLimit > 0)
        addRow(
            "Learning is at its limit",
            "ISF in ${s.isfHoursAtLimit} hours and basal in ${s.basalHoursAtLimit} hours cannot get weaker. " +
                "Your profile is too strong there. Applying the learned profile gives the learning room again.",
            SiTone.WARNING
        )

    when {
        s.switchPending ->
            addRow("Waiting for the pump", "The profile change has not reached the pump yet. The learning moves when it has.", SiTone.INFO)
        s.appliedName != null ->
            addRow(
                "In use: ${s.appliedName}",
                "Changed ${SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date(s.appliedAtMs))}." +
                    (s.previousName?.let { " Undo goes back to \"$it\"." } ?: ""),
                SiTone.GOOD
            )
    }

    val full = p.hours.count { it.isfConfidence >= LearnedProfilePlan.AUTO_CONFIDENCE && it.basalConfidence >= LearnedProfilePlan.AUTO_CONFIDENCE }
    addNoteBlock(
        (if (s.autoOn) "The profile is changed by itself when every hour is at 100 % for ISF and basal, at most once a week " +
            "(now $full of 24 hours). "
        else "Learning is off, so the profile is not changed by itself. ") +
            "Applying saves a new local profile and switches to it at 100 %. Your old profile is kept. " +
            "The learning moves with it, so the loop doses the same right after the switch. " +
            "To go back, use Undo here, not a normal profile switch with a changed profile."
    )
    addAction("Apply learned profile", SiAction.APPLY_LEARNED_PROFILE)
    if (s.previousName != null) addAction("Undo: back to \"${s.previousName}\"", SiAction.UNDO_LEARNED_PROFILE)
}

/** The new schedule, one line per block, for the confirm dialog. */
internal fun learnedProfilePreview(p: LearnedProfilePlan.Plan, isMmol: Boolean): String = buildString {
    val isfUnit = if (isMmol) "mmol/U" else "mg/dL/U"
    appendLine("ISF ($isfUnit)")
    p.isfSegments.forEach { appendLine("  ${span(it)}  ${isf(it.value, isMmol)}") }
    appendLine("Basal (U/h)")
    p.basalSegments.forEach { appendLine("  ${span(it)}  ${"%.2f".format(it.value)}") }
    append("Basal a day: ${"%.1f".format(p.profileDailyBasal)} → ${"%.1f".format(p.newDailyBasal)} U")
}

private fun span(s: LearnedProfilePlan.Segment) = "%02d–%02d h".format(s.startHour, s.startHour + s.hours)

private fun isf(mgdl: Double, isMmol: Boolean) = if (isMmol) "%.2f".format(mgdl / LearnedProfilePlan.MGDL_PER_MMOL) else "%.0f".format(mgdl)

/** Orange when the learned value gives more insulin than the profile, blue when less. A higher ISF is less insulin. */
private fun tone(ratio: Double, isf: Boolean): SiTone {
    val more = if (isf) ratio < 0.97 else ratio > 1.03
    val less = if (isf) ratio > 1.03 else ratio < 0.97
    return when {
        more -> SiTone.WARNING
        less -> SiTone.INFO
        else -> SiTone.MUTED
    }
}
