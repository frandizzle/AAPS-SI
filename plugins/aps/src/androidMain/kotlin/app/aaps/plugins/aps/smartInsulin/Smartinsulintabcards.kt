package app.aaps.plugins.aps.smartInsulin

import android.os.Handler
import android.os.Looper
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * What the SmartInsulin tab shows, as data.
 *
 * The 3.4 tab built Android Views directly inside each `updateXCard()`: every row was a TextView
 * added to a LinearLayout. 4.0 has no fragment-based plugin UI at all, only Compose, so that had to
 * change — but the logic deciding WHAT each card says is the valuable part, and it was ported here
 * verbatim. Each card is an extension on [SiCardBuilder], which offers the same `addRow`/`addDivider`/
 * `addSectionHeader`/… calls the fragment had, so the original bodies — including their early
 * `return`s — run unchanged. Only the output moved: from Views to a list of [SiItem]s that
 * `SmartInsulinScreen` renders.
 *
 * Keeping it as data also makes the tab testable without a device, which the View version never was.
 */
sealed interface SiItem {
    data class Row(val primary: String, val detail: String?, val tone: SiTone) : SiItem
    data object Divider : SiItem
    data class Mono(val text: String, val tone: SiTone, val bold: Boolean) : SiItem
    data class Note(val text: String) : SiItem
    data class Header(val title: String) : SiItem
    /** Tappable text — the screen decides what [action] does. */
    data class Action(val text: String, val action: SiAction, val tone: SiTone) : SiItem
}

enum class SiAction { REQUEST_STEP_PERMISSION, TOGGLE_FF_DEBUG }

data class SiCardContent(
    val items: List<SiItem>,
    /** False when the card has nothing to say and should be hidden, as the old `View.GONE` did. */
    val visible: Boolean = true,
    /** Button captions a card decided on (session buttons: "Golf" / "Stop Golf"). */
    val labels: Map<String, String> = emptyMap()
)

class SiCardBuilder {
    private val items = mutableListOf<SiItem>()
    var visible = true
    val labels = mutableMapOf<String, String>()

    fun addRow(primary: String, detail: String? = null, tone: SiTone = SiTone.STRONG) {
        items += SiItem.Row(primary, detail, tone)
    }
    fun addDivider() { items += SiItem.Divider }
    fun addGateRow(primary: String, detail: String, passed: Boolean) {
        val color = if (passed) SiTone.GOOD else SiTone.CRITICAL
        addRow((if (passed) "✓ " else "✗ ") + primary, detail, color)
    }
    fun addMonospaceBlock(text: String, tone: SiTone = SiTone.STRONG, bold: Boolean = false) {
        if (text.isNotBlank()) items += SiItem.Mono(text, tone, bold)
    }
    fun addNoteBlock(text: String) { if (text.isNotBlank()) items += SiItem.Note(text) }
    fun addSectionHeader(title: String) { items += SiItem.Header(title) }
    fun addAction(text: String, action: SiAction, tone: SiTone = SiTone.INFO) {
        items += SiItem.Action(text, action, tone)
    }
    fun build() = SiCardContent(items.toList(), visible, labels.toMap())
}

class SmartInsulinTabCards(private val plugin: SmartInsulinPlugin) {

    /** The tab's own expander state; lives here so the cards can read it like the fragment did. */
    var showFfDebug = false

    private fun fmtBasal(u: Double) = "%.3f U/h".format(u)

    private fun aggrDesc(a: Double) = when {
        a > 1.15 -> "Delivering more insulin than usual"
        a > 1.05 -> "Slightly more aggressive than normal"
        a < 0.85 -> "Being cautious — reducing insulin"
        a < 0.95 -> "Slightly conservative"
        else     -> "Normal aggressiveness"
    }

    fun buildSessionCard(): SiCardContent =
        SiCardBuilder().also { it.fillSessionCard() }.build()

    private fun SiCardBuilder.fillSessionCard() {
        val running = plugin.activitySessionLabel()
        labels["golf"] = if (running == SessionLabel.GOLF) "Stop Golf" else "Golf"
        labels["gym"] = if (running == SessionLabel.GYM)  "Stop Gym"  else "Gym"

        plugin.carbEpisodeStatus()?.let {
            addRow(it, "Carbs were entered, so the COB curve doses this meal (profile ISF ÷ CR) and\n" +
                "UAM/P-F stand down. When COB reaches zero the post-meal lockout starts.",
                   SiTone.INFO)
        }
        plugin.secondWaveNote()?.let {
            addRow("⚠ Second wave detected — episode not scored", it +
                ".\nMore food went in during the mode's own window, so nothing after it says\n" +
                "anything about the dose this mode was given. Lows are still learned from.",
                   SiTone.WARNING)
        }
        plugin.activitySessionStatus()?.let {
            addRow(it, "UAM and P/F blocked — a flat high here is hormones, not food.",
                   SiTone.WARNING)
        } ?: addRow("No session running",
                    "Start one for a round of golf or a gym session: no food, hormones running the show.",
                    SiTone.MUTED)

        val learned = plugin.activitySessionLearned()
        if (learned.isEmpty()) {
            addRow("Nothing learned yet",
                   "Each finished session teaches it two things: how much extra insulin the\n" +
                       "resistant phase needs, and how early to back off before the late low.")
        } else {
            learned.forEach { (label, values, n) ->
                val (isfMult, washout) = values
                val isfTxt = if (isfMult < 1.0) "ISF ×${"%.2f".format(isfMult)} (stronger)"
                             else if (isfMult > 1.0) "ISF ×${"%.2f".format(isfMult)} (easier)"
                             else "ISF unchanged"
                addRow("${label.label}: $isfTxt, washout ${washout}min before the end",
                       "Learned from $n session${if (n == 1) "" else "s"}.")
            }
        }
        plugin.activitySessionLastOutcome().takeIf { it.isNotEmpty() }?.let {
            addRow("Last: $it", null, SiTone.MUTED)
        }
    }

    fun buildReboundCard(d: SmartInsulinPlugin.FragmentData): SiCardContent =
        SiCardBuilder().also { it.fillReboundCard(d) }.build()

    private fun SiCardBuilder.fillReboundCard(d: SmartInsulinPlugin.FragmentData) {
        if (!d.inReboundWindow && !d.bgWentLow) {
            // No rebound — hide card
            visible = false
            return
        }
        visible = true

        if (d.inReboundWindow) {
            val elapsedMins  = d.reboundMins.toDouble()
            val windowMins   = d.totalReboundWindowMins.toDouble()  // includes rollercoaster extension
            val baseMins     = d.reboundWindowMins.toDouble()
            val minsLeft     = (windowMins - elapsedMins).coerceAtLeast(0.0).roundToInt()
            val elapsedInt   = elapsedMins.toInt().coerceAtMost(windowMins.toInt())
            val windowInt    = windowMins.toInt()
            val taperFrac    = (0.3 + (0.7 * (elapsedMins / windowMins))).coerceIn(0.3, 1.0)
            val tbrPct       = (taperFrac * 100).roundToInt()
            val smbGateMins  = windowMins * 0.75  // SMBs unlock at 75% of window (matches REBOUND_SMB_GATE=0.825)
            val smbUnlockIn  = (smbGateMins - elapsedMins).coerceAtLeast(0.0).roundToInt()

            // Status headline
            val headline = if (smbUnlockIn > 0)
                "⚠ Recovery in progress — ${elapsedInt}min of ${windowInt}min"
            else
                "⚠ Recovery in progress — SMBs restored, tapering off in ${minsLeft}min"
            addRow(headline, tone = SiTone.WARNING)

            // BG a hair under the guard (inside the CGM match tolerance) — not far enough to count
            // as a low, so the window keeps running. Say so, so the counter isn't misread.
            if (d.currentBgMgdl > 0.0 && d.currentBgMgdl < d.lowGuardMgdl) {
                addRow("⚠ BG right on the low guard — recovery counter still running",
                       "Within 1 mg/dL of the guard, so it isn't treated as a new low. A proper dip below\n" +
                           "restarts the window, and doubles it if BG had genuinely recovered first.",
                       SiTone.CRITICAL)
            }

            // Re-low extension row
            if (d.relowCount >= 1) {
                val relowMins = (baseMins * d.relowCount).toInt()
                addRow("Went low again — recovery window ×${d.relowCount + 1}",
                       "Base: ${baseMins.toInt()}min + ${relowMins}min for ${if (d.relowCount == 1) "the second low" else "${d.relowCount} re-lows"}.\n" +
                           "Restarted from 30% when BG came back above the guard. Max ×${RecoveryRelowTracker.RELOW_MAX_EXTENSIONS + 1}.",
                       SiTone.WARNING)
            }

            // TBR taper
            addRow("TBR capped at ${tbrPct}% of normal",
                   "Starts at 30% and ramps back to 100% over ${windowMins.toInt()} minutes.\n" +
                       "Prevents insulin stacking after a low.")

            // SMB countdown
            if (smbUnlockIn > 0) {
                addRow("SMBs blocked — unlocks in ~${smbUnlockIn}min",
                       "SMBs held back for first ${smbGateMins.toInt()} minutes (75% of ${windowMins.toInt()}min window).\nAvoids over-correcting while the low is still resolving.",
                       SiTone.CRITICAL)
            } else {
                addRow("SMBs restored ✓",
                       "Corrections running normally again. TBR taper still active for ${minsLeft}min.",
                       SiTone.GOOD)
            }

            // Rollercoaster extension rows
            if (d.consecutiveRollercoasters >= 1) {
                val extMins = (windowMins - baseMins * (1 + d.relowCount)).toInt()
                val extLabel = when (d.consecutiveRollercoasters) {
                    1 -> "Rollercoaster 1 detected — extending recovery by ${extMins}min"
                    2 -> "Rollercoaster 2 detected — extending recovery by ${extMins}min"
                    else -> "Rollercoaster ${d.consecutiveRollercoasters} detected — extending recovery by ${extMins}min"
                }
                addRow(extLabel,
                       "Base: ${(baseMins * (1 + d.relowCount)).toInt()}min + ${extMins}min extension = ${windowMins.toInt()}min total.\n" +
                           "Extension grows with each consecutive rollercoaster (max +45min).\n" +
                           "Resets after 2h with no further rollercoasters.",
                       SiTone.WARNING)
            }

            // Meal mode active during recovery
            if (d.mealMode != "Fasting") {
                addRow("Meal mode active — low recovery bypassed until window finishes",
                       "Recovery protection (TBR taper, SMB gate) continues running in the background.\n" +
                           "Meal mode ISF and dosing are applied on top. Recovery ends in ${minsLeft}min.",
                       SiTone.INFO)
            }

            // Bypass status
            if (d.softLandingBypass) {
                addRow("Soft landing — meal detection still active",
                       "The low was borderline (not a crash). UAM is allowed to fire\n" +
                           "during recovery in case you eat.",
                       SiTone.INFO)
            }

            // How low it went
            if (d.minBgDuringLow < Double.MAX_VALUE) {
                val lowBgStr = if (d.isMmol) "${"%.1f".format(d.minBgDuringLow / 18.0)} mmol"
                else "${"%.0f".format(d.minBgDuringLow)} mg/dL"
                addRow("Lowest BG: $lowBgStr",
                       "IOB at time of low: ${"%.2f".format(d.iobAtLowTime)}U\n" +
                           if (d.secondLowOccurred) "⚠ Second low occurred — recovery window extended." else "")
            }

        } else if (d.bgWentLow) {
            // bgWentLow=true but inReboundWindow=false means BG went low but hasn't yet
            // triggered the rebound window start (BG still below or just crossed threshold).
            // Check actual current BG to show correct message.
            val actuallyBelowGuard = d.currentBgMgdl > 0.0 && d.currentBgMgdl < d.lowGuardMgdl
            if (actuallyBelowGuard) {
                addRow("⚠ BG is below low guard — waiting for recovery",
                       "Once BG rises above the low guard, the ${d.totalReboundWindowMins}-minute recovery window starts automatically." +
                           (if (d.relowCount >= 1) "\nWent low again — window extended ×${d.relowCount + 1} (base ${d.reboundWindowMins}min)." else "") +
                           if (d.consecutiveRollercoasters >= 1) {
                               val extMins = d.totalReboundWindowMins - d.reboundWindowMins * (1 + d.relowCount)
                               "\nRollercoaster ${d.consecutiveRollercoasters} detected — window extended by ${extMins}min."
                           } else "",
                       SiTone.CRITICAL)
            } else {
                addRow("⚡ BG recovering — rebound window starting",
                       "BG has crossed back above the low guard. The ${d.totalReboundWindowMins}-minute recovery window is activating." +
                           if (d.consecutiveRollercoasters >= 1) {
                               val extMins = d.totalReboundWindowMins - d.reboundWindowMins * (1 + d.relowCount)
                               "\nRollercoaster ${d.consecutiveRollercoasters} detected — window extended by ${extMins}min."
                           } else "",
                       SiTone.WARNING)
            }
            if (d.mealMode != "Fasting") {
                addRow("✓ Low recovery bypassed — meal mode active (${d.mealMode})",
                       "Meal mode ISF and dosing running normally.\nRecovery window activates automatically when BG crosses back above the low guard.",
                       SiTone.GOOD)
            }
            if (d.minBgDuringLow < Double.MAX_VALUE) {
                val lowBgStr2 = if (d.isMmol) "${"%.1f".format(d.minBgDuringLow / 18.0)} mmol"
                else "${"%.0f".format(d.minBgDuringLow)} mg/dL"
                addRow("Lowest BG: $lowBgStr2",
                       "IOB at time of low: ${"%.2f".format(d.iobAtLowTime)}U")
            }
        }
    }

    fun buildGeneralCard(d: SmartInsulinPlugin.FragmentData): SiCardContent =
        SiCardBuilder().also { it.fillGeneralCard(d) }.build()

    private fun SiCardBuilder.fillGeneralCard(d: SmartInsulinPlugin.FragmentData) {
        addRow("${d.dayLabel}  ${d.hour}:00",
               "Current hour used for circadian adjustments")

        val modeColor = if (d.mealMode == "Fasting") SiTone.STRONG else SiTone.INFO
        addRow("Mode: ${d.mealMode}",
               d.modeRemMins?.let { "${it}min remaining" } ?: "No active meal — fasting rules apply",
               modeColor)

        val aggrColor = when { d.aggressiveness > 1.05 -> SiTone.WARNING; d.aggressiveness < 0.95 -> SiTone.INFO; else -> SiTone.STRONG }
        val isFasting = d.mealMode == "Fasting"

        // -- Plain-English short-term / long-term insulin summary -------------
        // Short term = circadian ceiling (what the loop is doing RIGHT NOW this hour)
        // Long term  = basal multiplier (what the profile has permanently learned)
        val shortTermPct  = ((1.0 - d.circCeil) * 100).roundToInt()      // +ve = reducing, -ve = adding
        val longTermPct   = ((1.0 - d.basalMultiplier) * 100).roundToInt()
        val shortTermAbs  = Math.abs(shortTermPct)
        val longTermAbs   = Math.abs(longTermPct)
        val shortTermDir  = if (shortTermPct > 0) "too much" else if (shortTermPct < 0) "not enough" else "balanced"
        val longTermDir   = if (longTermPct  > 0) "reduced"  else if (longTermPct  < 0) "increased"  else "unchanged"

        val aggrDetail = if (!isFasting) {
            "Aggressiveness: ${"%.3f".format(d.aggressiveness)}  Circ ceiling: ${"%.3f".format(d.circCeil)}\n" +
                "Locked at 1.0 during meal modes — not applied. Fasting value shown for reference."
        } else if (d.inReboundWindow) {
            // Low recovery active — show what the rebound window is actually doing
            val elapsedMins  = d.reboundMins
            val windowMins   = d.totalReboundWindowMins
            val minsLeft     = (windowMins - elapsedMins).coerceAtLeast(0)
            val taperFrac    = (0.3 + (0.7 * (elapsedMins.toDouble() / windowMins))).coerceIn(0.3, 1.0)
            val tbrPct       = (taperFrac * 100).roundToInt()
            val smbGateMins  = (windowMins * 0.75).roundToInt()
            val smbUnlockIn  = (smbGateMins - elapsedMins).coerceAtLeast(0)
            val relowNote    = if (d.relowCount >= 1)
                "\nWent low again — window extended ×${d.relowCount + 1} to ${windowMins}min."
            else ""
            val rollerNote   = if (d.consecutiveRollercoasters >= 1)
                "\nRollercoaster ${d.consecutiveRollercoasters} detected — window extended to ${windowMins}min."
            else ""
            val hardLowNote  = if (d.hardLowPenaltyActive)
                "\nAggressiveness ceiling cut by 20% — recovers as BG stabilises near target."
            else ""
            val longTermLine = if (d.hardLowPenaltyActive && longTermAbs >= 1)
                "\nLong term: basal & ISF reduced by ~${longTermAbs}% at this hour — will ease back as BG stabilises"
            else
                "\nLong term: learning paused during recovery — resumes in ${minsLeft}min"
            "Aggressiveness: ${"%.3f".format(d.aggressiveness)}  Circ ceiling: ${"%.3f".format(d.circCeil)}\n" +
                "⚠ Low recovery active — ${elapsedMins}min of ${windowMins}min elapsed ($minsLeft min left)\n" +
                "Short term: TBR capped at ${tbrPct}% — holding back insulin during recovery\n" +
                "SMBs: ${if (smbUnlockIn > 0) "blocked for ~${smbUnlockIn}min more" else "restored ✓"}" +
                hardLowNote + longTermLine + relowNote + rollerNote
        } else if (d.bgWentLow) {
            val actuallyBelowGuard = d.currentBgMgdl > 0.0 && d.currentBgMgdl < d.lowGuardMgdl
            val hardLowNote  = if (d.hardLowPenaltyActive)
                "\nAggressiveness ceiling cut by 20% — recovers as BG stabilises near target."
            else ""
            val longTermLine = if (d.hardLowPenaltyActive && longTermAbs >= 1)
                "\nLong term: basal & ISF reduced by ~${longTermAbs}% at this hour — will ease back as BG stabilises"
            else
                "\nLong term: learning paused — resumes once recovery window completes"
            if (actuallyBelowGuard) {
                "Aggressiveness: ${"%.3f".format(d.aggressiveness)}  Circ ceiling: ${"%.3f".format(d.circCeil)}\n" +
                    "⚠ BG is below low guard — insulin delivery limited\n" +
                    "Short term: holding insulin until BG recovers above low guard" +
                    hardLowNote + longTermLine
            } else {
                "Aggressiveness: ${"%.3f".format(d.aggressiveness)}  Circ ceiling: ${"%.3f".format(d.circCeil)}\n" +
                    "⚡ BG recovering — waiting for rebound window to activate\n" +
                    "Short term: insulin delivery resuming as BG stabilises above low guard" +
                    hardLowNote + longTermLine
            }
            hardLowNote + longTermLine
        } else if (shortTermAbs < 5 && longTermAbs < 5) {
            "Aggressiveness: ${"%.3f".format(d.aggressiveness)}  Circ ceiling: ${"%.3f".format(d.circCeil)}\n" +
                "Insulin levels look balanced at this hour.\n" +
                "Short term: within 5% — no adjustment needed\n" +
                "Long term: profile within 5% of target — dialled in"
        } else {
            val shortLine = when {
                shortTermAbs < 5  -> "Short term: balanced (within 5%)"
                shortTermPct > 0  -> "Short term: pulling out ~${shortTermAbs}% insulin right now (ceiling=${"%.0f".format(d.circCeil * 100)}%)"
                else              -> "Short term: adding ~${shortTermAbs}% extra insulin right now"
            }
            val longLine = when {
                longTermAbs < 2   -> "Long term: profile close to target — still watching"
                longTermPct > 0   -> "Long term: profile permanently ${longTermDir} by ~${longTermAbs}% at this hour"
                else              -> "Long term: profile permanently ${longTermDir} by ~${longTermAbs}% at this hour"
            }
            val statusLine = when {
                shortTermAbs >= 10 && longTermAbs < shortTermAbs ->
                    "Still learning — long term will catch up as pattern repeats"
                shortTermAbs < 5 && longTermAbs >= 5 ->
                    "Dialling in — short term nearly balanced, long term corrections holding"
                else -> "Monitoring — will continue adjusting each fasting cycle"
            }
            "Aggressiveness: ${"%.3f".format(d.aggressiveness)}  Circ ceiling: ${"%.3f".format(d.circCeil)}\n" +
                "$shortLine\n$longLine\n$statusLine"
        }
        val aggrPrimary = when {
            !isFasting          -> "Aggressiveness locked — meal mode active"
            d.inReboundWindow   -> "⚠ Recovering from low — insulin held back"
            d.bgWentLow && d.currentBgMgdl > 0.0 && d.currentBgMgdl < d.lowGuardMgdl -> "⚠ Below low guard — waiting for recovery"
            d.bgWentLow         -> "⚡ BG recovered — rebound window activating"
            else                -> aggrDesc(d.aggressiveness)
        }
        addRow(aggrPrimary, aggrDetail, when {
            d.inReboundWindow || d.bgWentLow -> SiTone.WARNING
            !isFasting -> SiTone.MUTED
            else -> aggrColor
        })

        // Pre-bolus 1 — only shown when in meal mode and a dose was delivered
        if (d.mealMode != "Fasting" && d.activeDoseU != null && d.activeDoseU > 0.0) {
            addRow("Pre-bolus 1 — delivered ${"%.2f".format(d.activeDoseU)}U",
                   tone = SiTone.GOOD)
        }

        // Pre-bolus 2 — show delivered amount once fired
        if (d.mealMode != "Fasting" && d.activePb2DoseU != null && d.activePb2DoseU > 0.0) {
            addRow("Pre-bolus 2 — delivered ${"%.2f".format(d.activePb2DoseU)}U",
                   "Second bolus delivered as scheduled.",
                   SiTone.GOOD)
        } else if (d.pb2Status.isNotEmpty() || d.pb2GateData != null) {
            val gate     = d.pb2GateData
            val isActive = d.pb2Status.contains("active")
            val pb2Primary = when {
                d.pb2Status.contains("due") -> "Pre-bolus 2 — ready to deliver now"
                Regex("""(\d+)m""").containsMatchIn(d.pb2Status) -> {
                    val mins = Regex("""(\d+)m""").find(d.pb2Status)?.groupValues?.get(1)
                    "Pre-bolus 2 — delivers in ${mins}m"
                }
                gate != null -> "Pre-bolus 2 — waiting for safety gates"
                else         -> "Pre-bolus 2"
            }
            addRow(pb2Primary, tone = if (isActive) SiTone.GOOD else SiTone.INFO)

            if (gate != null) {
                val isMmol = gate.isMmol
                fun fmtBg(mgdl: Double)    = if (isMmol) "%.1f mmol".format(mgdl / 18.0) else "%.0f mg/dL".format(mgdl)
                fun fmtDelta(mgdl: Double) = if (isMmol) "%+.2f mmol".format(mgdl / 18.0) else "%+.1f mg/dL".format(mgdl)
                fun fmtIob(u: Double)      = "%.2fU".format(u)

                val minBgMgdl   = MealOverrideManager.MIN_BG_FOR_PB2_MGDL
                val targetMgdl  = gate.profileTargetMgdl
                // BG must be above profile target AND above the hard floor
                val effectiveMin = maxOf(minBgMgdl, targetMgdl)
                val bgOk        = gate.bgMgdl > effectiveMin
                val bgDiff      = if (bgOk) "(+${fmtBg(gate.bgMgdl - effectiveMin)} above target)"
                else "(${fmtBg(effectiveMin - gate.bgMgdl)} below target — waiting)"
                addGateRow("BG: ${fmtBg(gate.bgMgdl)}  $bgDiff",
                           "Must be above profile target ${fmtBg(targetMgdl)}", bgOk)

                val maxAllowedIob = gate.maxIobU * MealOverrideManager.MAX_IOB_HEADROOM_RATIO
                val iobOk         = gate.iobU < maxAllowedIob
                val iobDetail     = if (iobOk) "${fmtIob(gate.iobU)} / ${fmtIob(gate.maxIobU)}  (${fmtIob(maxAllowedIob - gate.iobU)} headroom)"
                else "${fmtIob(gate.iobU)} / ${fmtIob(gate.maxIobU)}  (IOB too high — waiting)"
                addGateRow("IOB: $iobDetail", "Must be below ${(MealOverrideManager.MAX_IOB_HEADROOM_RATIO * 100).toInt()}% of max (${fmtIob(maxAllowedIob)})", iobOk)

                val deltaOk   = gate.deltaMgdl >= MealOverrideManager.DELTA_INSTANT_BLOCK_MGDL
                addGateRow("Delta: ${fmtDelta(gate.deltaMgdl)}  (${if (deltaOk) "not falling fast" else "falling — waiting"})",
                           "Blocked below ${fmtDelta(MealOverrideManager.DELTA_INSTANT_BLOCK_MGDL)}", deltaOk)

                val shortOk   = gate.shortAvgDeltaMgdl >= MealOverrideManager.SHORT_AVG_DELTA_BLOCK_MGDL
                addGateRow("15min avg: ${fmtDelta(gate.shortAvgDeltaMgdl)}  (${if (shortOk) "trend stable" else "sustained fall — waiting"})",
                           "Blocked below ${fmtDelta(MealOverrideManager.SHORT_AVG_DELTA_BLOCK_MGDL)}", shortOk)
            }
        }

        // Pre-bolus 3 — show delivered amount once fired
        if (d.mealMode != "Fasting" && d.activePb3DoseU != null && d.activePb3DoseU > 0.0) {
            addRow("Pre-bolus 3 — delivered ${"%.2f".format(d.activePb3DoseU)}U",
                   "Third bolus delivered (late-meal cover).",
                   SiTone.GOOD)
        } else if (d.pb3Status.isNotEmpty() || d.pb3GateData != null) {
            val gate3    = d.pb3GateData
            val isActive3 = d.pb3Status.contains("active")
            val pb3Primary = when {
                d.pb3Status.contains("waiting for PB2") -> "Pre-bolus 3 — waiting for PB2 to fire"
                d.pb3Status.contains("due")             -> "Pre-bolus 3 — ready to deliver now"
                Regex("""(\d+)m""").containsMatchIn(d.pb3Status) -> {
                    val mins = Regex("""(\d+)m""").find(d.pb3Status)?.groupValues?.get(1)
                    "Pre-bolus 3 — delivers in ${mins}m"
                }
                gate3 != null -> "Pre-bolus 3 — waiting for safety gates"
                else          -> "Pre-bolus 3"
            }
            val pb3Sub = if (d.pb3Status.contains("waiting for PB2"))
                "Timer starts when PB2 delivers. If PB2 is cancelled, PB3 cancels too."
            else null
            addRow(pb3Primary, pb3Sub,
                   tone = if (isActive3) SiTone.GOOD else SiTone.INFO)

            if (gate3 != null) {
                val isMmol3 = gate3.isMmol
                fun fmtBg3(mgdl: Double)    = if (isMmol3) "%.1f mmol".format(mgdl / 18.0) else "%.0f mg/dL".format(mgdl)
                fun fmtDelta3(mgdl: Double) = if (isMmol3) "%+.2f mmol".format(mgdl / 18.0) else "%+.1f mg/dL".format(mgdl)
                fun fmtIob3(u: Double)      = "%.2fU".format(u)

                val minBgMgdl3   = MealOverrideManager.MIN_BG_FOR_PB2_MGDL
                val targetMgdl3  = gate3.profileTargetMgdl
                val effectiveMin3 = maxOf(minBgMgdl3, targetMgdl3)
                val bgOk3        = gate3.bgMgdl > effectiveMin3
                val bgDiff3      = if (bgOk3) "(+${fmtBg3(gate3.bgMgdl - effectiveMin3)} above target)"
                else "(${fmtBg3(effectiveMin3 - gate3.bgMgdl)} below target — waiting)"
                addGateRow("BG: ${fmtBg3(gate3.bgMgdl)}  $bgDiff3",
                           "Must be above profile target ${fmtBg3(targetMgdl3)}", bgOk3)

                val maxAllowedIob3 = gate3.maxIobU * MealOverrideManager.MAX_IOB_HEADROOM_RATIO
                val iobOk3         = gate3.iobU < maxAllowedIob3
                val iobDetail3     = if (iobOk3) "${fmtIob3(gate3.iobU)} / ${fmtIob3(gate3.maxIobU)}  (${fmtIob3(maxAllowedIob3 - gate3.iobU)} headroom)"
                else "${fmtIob3(gate3.iobU)} / ${fmtIob3(gate3.maxIobU)}  (IOB too high — waiting)"
                addGateRow("IOB: $iobDetail3", "Must be below ${(MealOverrideManager.MAX_IOB_HEADROOM_RATIO * 100).toInt()}% of max (${fmtIob3(maxAllowedIob3)})", iobOk3)

                val deltaOk3 = gate3.deltaMgdl >= MealOverrideManager.DELTA_INSTANT_BLOCK_MGDL
                addGateRow("Delta: ${fmtDelta3(gate3.deltaMgdl)}  (${if (deltaOk3) "not falling fast" else "falling — waiting"})",
                           "Blocked below ${fmtDelta3(MealOverrideManager.DELTA_INSTANT_BLOCK_MGDL)}", deltaOk3)

                val shortOk3 = gate3.shortAvgDeltaMgdl >= MealOverrideManager.SHORT_AVG_DELTA_BLOCK_MGDL
                addGateRow("15min avg: ${fmtDelta3(gate3.shortAvgDeltaMgdl)}  (${if (shortOk3) "trend stable" else "sustained fall — waiting"})",
                           "Blocked below ${fmtDelta3(MealOverrideManager.SHORT_AVG_DELTA_BLOCK_MGDL)}", shortOk3)
            }
        }

        val pfIsf  = if (d.isMmol) d.profileIsfMgdl / 18.0 else d.profileIsfMgdl
        val fIsf   = if (d.isMmol) d.finalIsfMgdl   / 18.0 else d.finalIsfMgdl
        val isfUnit = if (d.isMmol) "mmol/U" else "mg/dL/U"
        val isfFmt = if (d.isMmol) "%.2f" else "%.1f"
        addRow("Insulin sensitivity: ${isfFmt.format(fIsf)} $isfUnit",
               "Profile ${isfFmt.format(pfIsf)} ÷ multiplier ${"%.3f".format(d.isfMultiplier)} = ${isfFmt.format(fIsf)} $isfUnit\n" +
                   "Lower ISF = more insulin delivered per BG gap. Multiplier >1 reduces ISF, <1 raises ISF.")

        // Convert drift signal units if user is in mmol
        val basalSignalDisplay = if (d.isMmol && d.lastBasalSignal.contains("mgdlhr")) {
            d.lastBasalSignal.replace(Regex("([\\-\\d.]+) mgdlhr")) { mr ->
                val mgdl = mr.groupValues[1].toDoubleOrNull() ?: 0.0
                "${"%.2f".format(mgdl / 18.0)} mmol/L/hr"
            }
        } else {
            d.lastBasalSignal.replace("mgdlhr", "mg/dL/hr")
        }
        addRow("Basal rate: ${fmtBasal(d.finalBasalU)}",
               "Profile ${fmtBasal(d.profileBasalU)} × multiplier ${"%.3f".format(d.basalMultiplier)} = ${fmtBasal(d.finalBasalU)}\n" +
                   "Background insulin rate keeping BG stable between meals.\n" +
                   "Last basal learning: $basalSignalDisplay")
    }

    fun buildLearningCard(d: SmartInsulinPlugin.FragmentData): SiCardContent =
        SiCardBuilder().also { it.fillLearningCard(d) }.build()

    private fun SiCardBuilder.fillLearningCard(d: SmartInsulinPlugin.FragmentData) {

        addRow("SmartInsulin adapts to your body over time using real BG data.", tone = SiTone.MUTED)

        val (statePrimary, stateColor) = when {
            d.learningState.startsWith("off") || d.learningState == "Not Learning" ->
                Pair("Learning paused — ${d.learningState.removePrefix("off: ").trim().ifEmpty{"unknown"}}", SiTone.WARNING)
            d.learningState == "limited" || d.learningState.startsWith("Limited") ->
                Pair("Limited — meal mode active, only learning Peak/DIA", SiTone.WARNING)
            else -> Pair("Learning active", SiTone.GOOD)
        }
        addRow(statePrimary, "State: ${d.learningState}", stateColor)

        if (d.postMealLockoutMins > 0 && d.mealMode == "Fasting")
            addRow("Post-meal pause: ${d.postMealLockoutMins}min remaining",
                   "BG data after meals is excluded from basal/ISF learning.", SiTone.WARNING)

        val actColor = when (d.activityLevel) {
            "Sedentary" -> SiTone.MUTED
            "Light"     -> SiTone.GOOD
            "Moderate"  -> SiTone.WARNING
            "Heavy"     -> SiTone.WARNING
            else        -> SiTone.MUTED
        }
        val activityDetail = buildString {
            append("Activity: ${d.activityLevel}")
            if (d.avgHrBpm > 0)  append("  \u2665 ${d.avgHrBpm}bpm")
            if (d.steps5min > 0) append("  \ud83d\udc63 ${d.steps5min}/5m${if (d.stepsFromPhone) " (phone)" else " (watch)"}")
            // Record age, not just the count. Without it a watch delivering late and a watch
            // seeing no steps look identical — both just show nothing — and that is exactly the
            // ambiguity that made the step lag impossible to place. Only shown when the watch is
            // the number in use; the phone counter has no age, it is a live rolling window.
            if (!d.stepsFromPhone) d.stepsAgeMs?.let { age ->
                val mins = age / 60_000
                if (mins >= 1) append("  (watch rec ${mins}m old)")
            }
            if (d.avgHrBpm == 0 && d.steps5min == 0) {
                append(if (d.stepsAgeMs == null) "  (no HR/steps data)" else "  (no recent steps)")
            }
            when (d.phoneStepState) {
                PhoneStepCounter.State.NO_SENSOR -> append("\nPhone pedometer: this device has no step sensor")
                PhoneStepCounter.State.RUNNING   -> if (d.phoneSteps5min > 0 && !d.stepsFromPhone)
                    append("\nPhone pedometer: ${d.phoneSteps5min}/5m")
                else -> Unit
            }
        }
        addRow(activityDetail, "High activity raises target and pauses learning.", actColor)

        // Its own tappable row rather than text inside the one above, so the affordance is real.
        if (d.phoneStepState == PhoneStepCounter.State.NEEDS_PERMISSION)
            addAction("▶ Enable phone pedometer — steps with no watch delay", SiAction.REQUEST_STEP_PERMISSION,
                      SiTone.INFO)

        if (d.cgmWarmup) addRow("New sensor — learning paused for first 24h", null, SiTone.WARNING)

        addDivider()

        // Parse Nudge Status
        val nudgeParts      = d.lastAggrNudgeStatus.split("|")
        val nudgeState      = nudgeParts.getOrNull(0) ?: "INACTIVE"
        val nudgeActive     = nudgeState == "ACTIVE_HIGH" || nudgeState == "ACTIVE_LOW"
        val nudgeTrim       = nudgeState == "TRIM"
        val nudgePaused     = nudgeState == "PAUSED"
        val nudgeDecay      = nudgeState == "DECAY"

        // Positions in status string:
        // TRIM|dir|mag% -> position 1=dir, 2=mag
        // ACTIVE_LOW|dev|day|hour|sessionIsfMult|currentIsfMult|sessionBasMult|currentBasMult|COOLDOWN|reason|ISF_APPLIED|BAS_APPLIED
        // nudgeParts indices:
        // 0: State (ACTIVE_HIGH/LOW, TRIM, PAUSED, INACTIVE)
        // 1: Deviation (e.g. "+15%") or Trim Direction
        // 2: Day or Trim Pct
        // 3: Hour
        // 4: sessionIsfMult
        // 5: currentIsfMult
        // 6: sessionBasMult
        // 7: currentBasMult
        // 10: ISF_APPLIED | ISF_SKIPPED   — whether the nudge actually wrote ISF this cycle
        // 11: BAS_APPLIED | BAS_SKIPPED   — same for basal
        // NOTE 4-7 are RAW day-bucket multipliers and are no longer read here: the nudge card uses
        // the bucket-centre pair from FragmentData instead, which includes the day/global blend and
        // so matches the 24h table row for this hour.

        val profIsf = d.profileIsfMgdl
        val profBas = d.profileBasalU

        fun fmtIsf(mult: Double) = if (mult <= 0.0) "?" else if (d.isMmol) "${"%.2f".format(profIsf / mult / 18.0)} mmol" else "${"%.0f".format(profIsf / mult)} mg/dL"
        fun fmtBas(mult: Double) = if (mult <= 0.0) "?" else "${"%.3f".format(profBas * mult)} U/h"

        // --- SHORT-TERM LEARNING ---
        addSectionHeader("Short-term learning")

        val stHeadline: String
        val stDetail: String
        val stColor: SiTone

        val shortPct  = ((1.0 - d.circCeil) * 100).roundToInt()
        val stAction  = if (shortPct > 0) "Reducing insulin" else if (shortPct < 0) "Adding insulin" else "Neutral"
        val stIcon    = if (shortPct > 0) "⬇️" else if (shortPct < 0) "⬆️" else "⏺"

        // Determine learning pause context so every short-term branch can append it
        val isMealOrUamActive = d.mealMode != "Fasting" && d.postMealLockoutMins <= 0L
        val isPostMealLockout = d.mealMode == "Fasting" && d.postMealLockoutMins > 0L
        val isUamModeActive   = d.mealMode.contains("UAM", ignoreCase = true)
        val learningPauseNote: String = when {
            isPostMealLockout ->
                "\n⏸ ISF/basal learning paused — recent meal (${d.postMealLockoutMins}min left). Only learning DIA/peak."
            isUamModeActive ->
                "\n⏸ ISF/basal learning paused — UAM meal mode active (${d.mealMode}). Only learning DIA/peak."
            isMealOrUamActive ->
                "\n⏸ ISF/basal learning paused — meal mode active (${d.mealMode}). Only learning DIA/peak."
            else -> ""
        }

        when {
            d.inReboundWindow -> {
                stHeadline = "$stIcon Recovering from low — $stAction"
                stDetail   = "TBR capped at ${(d.aggressiveness * 100).roundToInt()}% of normal to prevent stacking after a low." +
                    learningPauseNote
                stColor    = SiTone.WARNING
            }
            d.bgWentLow -> {
                stHeadline = "⚠️ BG below low guard — Reducing insulin"
                stDetail   = "Waiting for BG to recover above low guard before resuming normal corrections." +
                    learningPauseNote
                stColor    = SiTone.CRITICAL
            }
            nudgeTrim -> {
                val trimDir = nudgeParts.getOrNull(1) ?: ""
                val isHigh = trimDir == "ACTIVE_HIGH"
                val isOvershoot = trimDir == "OVERSHOOT"
                // FuelTrim moves this hour's aggressiveness CEILING, not ISF or basal. The card used to
                // print an ISF/basal "was -> now" here, pairing a value captured when the hour began
                // with the live, minute-interpolated multiplier - so crossing into an hour whose
                // learned values sat stronger showed ISF/basal strengthening directly under
                // "Reducing insulin". Show what the trim actually changed, and ISF/basal as this
                // hour's bucket-centre values (the same reads as the 24h table), labelled as context.
                val trimPct = nudgeParts.getOrNull(2) ?: ""

                val trimAction = if (isHigh) "Adding insulin" else "Reducing insulin"
                stHeadline = when {
                    isHigh      -> "⬆️ Sustained high for ${d.trimMins}m — Adding insulin"
                    isOvershoot -> "⬇️ Overshoot after high — Reducing insulin"
                    else        -> "⬇️ Sustained low for ${d.trimMins}m — Reducing insulin"
                }
                stDetail   = (if (isOvershoot)
                    "BG spiked then fell back through target — too much insulin on board. Pulling insulin to stop the swing.\n"
                else
                    "BG has been off target for ${d.trimMins}m. $trimAction to correct the trend.\n") +
                    "Aggressiveness cap this hour: ×${"%.2f".format(d.circCeil)}" +
                    (if (trimPct.isNotEmpty()) " (trim ${if (isHigh) "+" else "−"}$trimPct)" else "") + "\n" +
                    "Unchanged by the trim — ISF ${fmtIsf(d.bucketIsfMultiplier)}, basal ${fmtBas(d.bucketBasalMultiplier)} this hour" +
                    learningPauseNote
                stColor    = if (isHigh) SiTone.GOOD else SiTone.WARNING
            }
            nudgeActive && nudgeParts.getOrNull(9)?.contains("rollercoaster") == true -> {
                stHeadline = "⬇️ Rollercoaster detected — Reducing insulin"
                stDetail   = "Detected unstable swings (Rollercoaster #${d.consecutiveRollercoasters}). Capping insulin at ${(d.circCeil * 100).roundToInt()}% to stop the rollercoaster." +
                    learningPauseNote
                stColor    = SiTone.WARNING
            }
            nudgeActive && nudgeParts.getOrNull(9)?.contains("soft low") == true -> {
                stHeadline = "⬇️ Soft low approach — Reducing insulin"
                stDetail   = "BG is falling fast with IOB on board. Reducing insulin to prevent a crash." +
                    learningPauseNote
                stColor    = SiTone.WARNING
            }
            nudgeActive -> {
                val isHigh = nudgeState == "ACTIVE_HIGH"
                // Both ends of both pairs are bucket-centre reads for THIS hour, so the only thing
                // that can move them is the nudge.
                //
                // The basal pair used to mix two different quantities: a raw day-bucket "was" from
                // nudgeParts[6] against a live, minute-interpolated "now" from d.basalMultiplier.
                // Their difference was mostly the clock crossing an hour boundary — at :00 the live
                // read is an exact 50/50 blend of the two hours — so an hour whose neighbour sat
                // higher displayed basal RISING directly under "Removing insulin". ISF never showed
                // it only because both its ends happened to come from the same place.
                val sIsfMult = d.nudgeSessionIsfBucketMult.takeIf { it > 0 } ?: 1.0
                val cIsfMult = d.bucketIsfMultiplier
                val sBasMult = d.nudgeSessionBasBucketMult.takeIf { it > 0 } ?: 1.0
                val cBasMult = d.bucketBasalMultiplier

                // A nudge is skipped for the cycle when a physics learner or FuelTrim already wrote
                // that quantity — same-cycle mutual exclusion in applyAggrNudge. Without saying so,
                // a card that reports a was→now pair reads as though the nudge made the move.
                val isfHeld = nudgeParts.getOrNull(10) == "ISF_SKIPPED"
                val basHeld = nudgeParts.getOrNull(11) == "BAS_SKIPPED"
                val heldNote = " — held this cycle, a direct measurement took precedence"

                val nudgeAction = if (isHigh) "Adding insulin" else "Removing insulin"
                stHeadline = if (isHigh) "⬆️ Pattern detected — $nudgeAction" else "⬇️ Pattern detected — $nudgeAction"
                stDetail   = "Historical pattern shows you need ${if (isHigh) "more" else "less"} insulin at this hour.\n" +
                    "ISF was ${fmtIsf(sIsfMult)} → now ${fmtIsf(cIsfMult)}${if (isfHeld) heldNote else ""}\n" +
                    "Basal was ${fmtBas(sBasMult)} → now ${fmtBas(cBasMult)}${if (basHeld) heldNote else ""}" +
                    learningPauseNote
                stColor    = if (isHigh) SiTone.GOOD else SiTone.WARNING
            }
            nudgePaused -> {
                stHeadline = "⏸ Paused — ${nudgeParts.getOrNull(1) ?: "Learning suppressed"}"
                stDetail   = "Short-term adjustments paused while not in a clean fasting state." +
                    learningPauseNote
                stColor    = SiTone.INFO
            }
            isMealOrUamActive || isPostMealLockout -> {
                // No active nudge/trim but learning is paused — show a dedicated paused state
                stHeadline = when {
                    isPostMealLockout -> "⏸ Post-meal pause — ${d.postMealLockoutMins}min remaining"
                    isUamModeActive   -> "⏸ UAM meal mode active — ISF/basal learning paused"
                    else              -> "⏸ Meal mode active — ISF/basal learning paused"
                }
                stDetail   = when {
                    isPostMealLockout ->
                        "ISF/basal learning excluded during post-meal window. Only DIA/peak learning active.\nResumes when window clears."
                    isUamModeActive   ->
                        "ISF and basal learning paused while ${d.mealMode} is active.\nOnly DIA/peak learning active. Corrections still running normally."
                    else              ->
                        "ISF and basal learning paused while ${d.mealMode} is active.\nOnly DIA/peak learning active. Corrections still running normally."
                }
                stColor    = SiTone.INFO
            }
            nudgeDecay -> {
                stHeadline = "↩ Unwinding an old correction"
                stDetail   = "This hour is behaving normally again, so a past adjustment that is no longer " +
                    "supported by evidence is being released back toward the cross-day average. " +
                    "A one-off bad night fades out over about three days if it does not repeat.\n" +
                    "ISF ${fmtIsf(d.isfMultiplier)} · Basal ${fmtBas(d.basalMultiplier)}" +
                    learningPauseNote
                stColor    = SiTone.INFO
            }
            else -> {
                stHeadline = "⏺ Stable — No short-term adjustments"
                stDetail   = "BG is responding normally. Loop is running at profile aggressiveness."
                stColor    = SiTone.MUTED
            }
        }
        addRow(stHeadline, stDetail, stColor)
        d.lastRunError?.let {
            addRow("⚠️ Last loop cycle failed", it, SiTone.CRITICAL)
        }

        // --- LONG-TERM LEARNING ---
        addDivider()
        addSectionHeader("Long-term learning")

        // Use multipliers from status if active, otherwise use current plugin state.
        // ISF has no separate flat-multiplier layer to combine, so the raw nudge value is fine there.
        // Basal DOES have a separate flat BasalLearner multiplier stacked on top of the circadian
        // one (see combinedBasalMultiplier in the plugin) — nudgeParts[7] only ever carries the
        // circadian-only piece, never the combined value. Always use d.basalMultiplier (already
        // combined) so this card agrees with the per-hour table instead of showing a different number.
        val ltIsfMult = if (nudgeActive) nudgeParts.getOrNull(5)?.toDoubleOrNull() ?: d.isfMultiplier else d.isfMultiplier
        val ltBasMult = d.basalMultiplier

        val day = if (nudgeActive) nudgeParts.getOrNull(2) ?: d.dayLabel else d.dayLabel
        val hour = if (nudgeActive) nudgeParts.getOrNull(3)?.toIntOrNull() ?: d.hour else d.hour
        val hourStr = if (hour != null) {
            if (d.isMmol) {
                val ampm = if (hour < 12) "AM" else "PM"
                val h12  = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
                "$h12:00 $ampm"
            } else "%02d:00".format(hour)
        } else "?"

        val isfComp = if (ltIsfMult > 1.001) "Increased needs" else if (ltIsfMult < 0.999) "Decreased needs" else "At profile"
        val basComp = if (ltBasMult > 1.001) "Increased needs" else if (ltBasMult < 0.999) "Decreased needs" else "At profile"

        val longTermPct   = ((1.0 - d.basalMultiplier) * 100).roundToInt()
        val ltAction = if (longTermPct > 0) "Reduced insulin needs" else if (longTermPct < 0) "Increased insulin needs" else "Neutral"

        addRow("Profile updated: $ltAction", "Historical pattern at $hourStr on ${day}s.", SiTone.STRONG)

        val isfColor = when {
            ltIsfMult > 1.03 -> SiTone.WARNING // Orange (stronger/aggressive)
            ltIsfMult < 0.97 -> SiTone.INFO // Blue (weaker/conservative)
            else -> SiTone.MUTED
        }
        addRow("ISF: ${fmtIsf(ltIsfMult)} ($isfComp)", null, isfColor)

        val basColor = when {
            ltBasMult > 1.03 -> SiTone.WARNING // Orange (stronger)
            ltBasMult < 0.97 -> SiTone.INFO // Blue (weaker)
            else -> SiTone.MUTED
        }
        addRow("Basal: ${fmtBas(ltBasMult)} ($basComp)", null, basColor)

        // -- Feed-forward debug toggle -----------------------------------------
        addAction(if (showFfDebug) "▲ Hide feed-forward debug" else "▼ Feed-forward debug (Accel + PredTrim)",
                  SiAction.TOGGLE_FF_DEBUG, SiTone.MUTED)
        if (showFfDebug) {
            addRow("Acceleration (2nd derivative)", d.lastAccelDebug.ifEmpty { "(no data)" })
            addRow("Predictive Basal Trim (60min projection)", d.lastPredTrimDebug.ifEmpty { "(no data)" })
            addRow("Last basal signal", d.lastBasalSignal.ifEmpty { "(no data)" })
            addRow("Cycle summary (live)", d.lastCycleSummary.ifEmpty { "(no data)" })
            // The hours a low was charged back to are otherwise invisible: they are hours the
            // clock has already left, so nothing on the live line ever mentions them.
            addRow("Last low charged back to", d.lastRetroAttribution.ifEmpty { "(no data)" })
        }
    }

    fun buildUamCard(d: SmartInsulinPlugin.FragmentData): SiCardContent =
        SiCardBuilder().also { it.fillUamCard(d) }.build()

    private fun SiCardBuilder.fillUamCard(d: SmartInsulinPlugin.FragmentData) {
        val line = d.uamStatusLine ?: ""

        // -- Active UAM meal mode — shown when a UAM mode is running ----------
        val isUamModeActive = d.mealMode.contains("UAM") || d.mealMode.contains("(UAM)")
        if (isUamModeActive) {
            val minsLeft = d.modeRemMins ?: 0
            addRow("${d.mealMode} active — ${minsLeft}min remaining",
                   "Meal auto-detected. ISF and dosing adjusted for ${d.mealMode}.\nUAM detection resumes when this mode expires.",
                   SiTone.INFO)
        } else {
            // -- UAM detection status ------------------------------------------
            val uamPart = line.substringBefore(" | P/F:").trim()
            val cleanUamPart = uamPart.removePrefix("[dirty] ").trim()

            val (primary, color) = when {
                cleanUamPart.startsWith("watching") -> Pair("BG rising — building confirmation streak ?", SiTone.WARNING)
                cleanUamPart.startsWith("last")     -> Pair("Meal auto-detected recently", SiTone.INFO)
                cleanUamPart.startsWith("armed")    -> Pair("Watching for unannounced meals", SiTone.GOOD)
                cleanUamPart.startsWith("off")      -> {
                    val reason = cleanUamPart.substringAfter("off").removePrefix(" (").removeSuffix(")").trim()
                    Pair("Auto-detection off — $reason", SiTone.MUTED)
                }
                else -> Pair("UAM status", SiTone.STRONG)
            }
            addRow(primary, uamPart.ifEmpty { null }, color)
        }

        // UAM thresholds — strip leading spaces for clean alignment
        val pfPart  = if (line.contains("P/F:")) line.substringAfter("P/F:").trim() else null
        val debugClean = d.uamDebug.lines()
            .filter { !it.trimStart().startsWith("P/F") }
            .joinToString("\n") { it.trimStart() }
            .trim()
        if (debugClean.isNotEmpty()) {
            addRow("Detection thresholds",
                   debugClean + "\n\nUAM fires when BG rises consistently above the trigger\nthreshold during your configured meal windows.\n\n" +
                       "Clean window = fasting, normal thresholds apply.\n" +
                       "Dirty window = post-meal lockout active — thresholds are raised (~1.5×) to avoid\ndetecting fat/protein tail rises as a new meal.")
        }

        // -- P/F subheading ------------------------------------------------
        addDivider()
        addSectionHeader("Protein / Fat Detection (P/F)")

        val (pfPrimary, pfColor) = when {
            pfPart == null                       -> Pair("P/F detection disabled", SiTone.MUTED)
            pfPart.contains("off")               -> Pair("P/F off — ${pfPart.substringAfter("off").trim().removePrefix("(").removeSuffix(")")}", SiTone.MUTED)
            pfPart.contains("armed")             -> Pair("Armed — will activate after meal expires", SiTone.GOOD)
            pfPart.contains("/") && pfPart.contains("stuck") -> {
                val count = Regex("""(\d+/\d+)""").find(pfPart)?.groupValues?.get(1)
                Pair("BG stuck high — counting readings ($count)", SiTone.WARNING)
            }
            else                                 -> Pair("P/F: $pfPart", SiTone.STRONG)
        }
        val pfDebug = d.uamDebug.lines()
            .filter { it.trimStart().startsWith("P/F") }
            .joinToString("\n") { it.trimStart() }
            .trim()
        addRow(pfPrimary, pfDebug.ifEmpty { null }, pfColor)

        // -- Meal absorption log (observation-only) ----------------------------
        // Estimated carb-equivalent grams per completed meal/UAM activation, from BG
        // residual after subtracting insulin's expected effect. Not a carbs/protein/fat
        // breakdown (can't be distinguished from BG alone) — doesn't feed dosing, purely
        // for comparing against what was actually eaten. Full history also written to
        // Documents/AAPS/SmartInsulin/meal_absorption_log.csv.
        addDivider()
        addSectionHeader("Meal Absorption Log (estimated, observation-only)")
        if (d.mealAbsorptionInProgress.isNotBlank()) {
            addRow("In progress: ${d.mealAbsorptionInProgress}",
                   "Updates live each loop cycle — only finalized into the log below once this mode ends.",
                   SiTone.INFO)
        }
        addDivider()
        addSectionHeader("Learned Mode ISF (per meal / UAM mode)")
        addMonospaceBlock(d.modeIsfLearnerStatus)
        addNoteBlock("Judged ~75min after each episode ends: ended low → ISF weakens (mostly " +
            "charged to DURA if DURA was pushing). Spike held ≥3mmol over target for 30min in the " +
            "first 75min with no front-loading left, or ended high without DURA → strengthens. " +
            "Ended high with DURA working the tail → no change, that stall is DURA's. Ate again " +
            "during the tail → skipped. Changing a mode's ISF override resets it.")

        addDivider()
        addSectionHeader("Learned DURA Strength (per mode)")
        addMonospaceBlock(d.duraStrengthStatus)
        addNoteBlock("Mode ISF handles the spike; DURA handles the stall after it. " +
            "Floor = the strongest ISF DURA may take this mode to (approximate — shown against the " +
            "mode's learned ISF, which circadian moves through the day). Your floor in settings is " +
            "the hard limit. Went low with DURA engaged → floor raised from where DURA actually got " +
            "to, strength trimmed 5%. Stuck ≥1mmol over target and flat for an unbroken hour, " +
            "nothing low after → DURA wasn't enough: if it was held at the learned floor that " +
            "floor is lowered, if it was still climbing strength goes up 3%. Held at your settings " +
            "floor, or already at full strength → no change, and it says so here. A low always wins.")

        addDivider()
        addSectionHeader("Learned UAM Entry Fraction (per UAM mode)")
        addMonospaceBlock(d.uamEntryFractionStatus)
        addNoteBlock("Shape knob — how front-loaded the first SMBs after a UAM entry are. " +
            "Low soon after entry → lowered. Big spike peaking 40min+ after entry but still ending " +
            "on target → raised. Peaked sooner than that → fast carbs, left alone, since no amount " +
            "of front-loading could have beaten it. Ended high, or went low late → left alone, " +
            "that is mode ISF's job. Entry SMB count stays manual.")
        if (d.mealAbsorptionLog.isBlank()) {
            addRow("No completed meal/UAM episodes logged yet", null, SiTone.MUTED)
        } else {
            addMonospaceBlock("Date      Meal                 Duration   Est. grams", SiTone.MUTED, bold = true)
            addMonospaceBlock(d.mealAbsorptionLog.trimEnd())
        }
    }

    fun buildStftCard(d: SmartInsulinPlugin.FragmentData): SiCardContent =
        SiCardBuilder().also { it.fillStftCard(d) }.build()

    private fun SiCardBuilder.fillStftCard(d: SmartInsulinPlugin.FragmentData) {
        if (d.stftActive && d.stftStatus != null) {
            addRow("Active — gently nudging the loop to correct",
                   d.stftStatus + "\n\nSoft Target Fine-Tune temporarily lowers the loop's internal target\nwhen fasting BG stays stuck above target. Resets when BG falls.",
                   SiTone.WARNING)
        } else {
            val inactiveReason = when {
                d.stftStatus?.contains("high temp target") == true ->
                    "Inactive — high temp target set"
                d.mealMode.contains("Protein") || d.mealMode.contains("P/F") ->
                    "Inactive — P/F running"
                d.mealMode.startsWith("UAM") || d.mealMode.contains("(UAM)") ->
                    "Inactive — UAM running"
                d.mealMode != "Fasting" ->
                    "Inactive — meal mode running (${d.mealMode})"
                else ->
                    "Inactive — BG is responding normally"
            }
            addRow(inactiveReason,
                   "STFT activates when fasting BG stays above target for 3+ readings (~15min).\nLowers the loop's target slightly without changing your profile.",
                   SiTone.MUTED)
        }
    }

    fun buildProfilesCard(d: SmartInsulinPlugin.FragmentData): SiCardContent =
        SiCardBuilder().also { it.fillProfilesCard(d) }.build()

    private fun SiCardBuilder.fillProfilesCard(d: SmartInsulinPlugin.FragmentData) {
        // (profile rows)

        addRow("Peak is learned from fasting and low-carb corrections. DIA is your insulin's configured value and is not learned: in this curve it sets the length of the insulin tail, and learning it from BG pulls it short (late lows with IOB near zero). Green = learned, amber = learning, grey = using profile values.", tone = SiTone.MUTED)
        // -- Tracker status row (#27) ------------------------------------------
        val (profStatusPrimary, profStatusColor) = when {
            d.profileLearningStatus.startsWith("off") ->
                "Learning paused" to SiTone.WARNING
            d.profileLearningStatus.contains("tracker=idle") ->
                "Watching for new bolus" to SiTone.GOOD
            else ->
                "Tracking active bolus curve" to SiTone.INFO
        }
        val profStatusDetail = d.profileLearningStatus
            .replace("off: ", "")
            .replace("tracker=idle", "Waiting for IOB spike (=0.3U) to begin tracking kinetics.")
            .replace("tracker=waiting_peak", "Tracking: waiting for IOB to peak...")
            .replace("tracker=tracking_nadir", "Tracking: waiting for BG nadir...")
            .replace("tracker=confirming", "Tracking: confirming recovery from nadir...")
        addRow(profStatusPrimary, profStatusDetail, profStatusColor)
        addDivider()

        d.profilesRawStatus.lines().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.trim().split(":"); if (parts.size < 2) return@forEach
            val name     = parts[0].trim(); val info = parts.drop(1).joinToString(":").trim()
            val n        = Regex("""n=(\d+)""").find(info)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val activeMealMode = d.mealMode
            val isActive = if (activeMealMode.contains("(UAM)", ignoreCase = true)) {
                name.contains("(UAM)", ignoreCase = true) &&
                    activeMealMode.contains(name.substringBefore(" ("), ignoreCase = true)
            } else {
                !name.contains("(UAM)", ignoreCase = true) &&
                    activeMealMode.contains(name, ignoreCase = true)
            }
            // Colour from learning status regardless of active state
            val col  = when { n >= 5 -> SiTone.GOOD; n >= 1 -> SiTone.WARNING; else -> SiTone.MUTED }
            val note = when { n == 0 -> "  (using profile values — not enough data yet)"; n < 5 -> "  (still learning)"; else -> "" }
            val prefix = if (isActive) "→ " else "  "
            addRow("$prefix$name", info + note, col)
        }
    }
}
