package app.aaps.plugins.aps.smartInsulin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.AutoMirrored
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalContext
import app.aaps.core.ui.compose.ToolbarConfig
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Compose screen for the SmartInsulin plugin tab.
 * Replicates the card layout of SmartInsulinFragment using Compose + MaterialTheme
 * so it renders correctly in the new AAPS 4.0 dark Compose UI.
 */
@Composable
fun SmartInsulinScreen(
    plugin: SmartInsulinPlugin,
    onNavigateBack: () -> Unit = {},
    onSettings: (() -> Unit)? = null,
    setToolbarConfig: ((ToolbarConfig) -> Unit)? = null
) {
    var data by remember { mutableStateOf<SmartInsulinPlugin.FragmentData?>(null) }
    var selectedDow by remember { mutableStateOf(java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1) }

    // Refresh every 10s
    LaunchedEffect(Unit) {
        while (true) {
            data = plugin.fragmentData()
            delay(10_000)
        }
    }

    // Override the toolbar settings icon to launch PreferencesActivity directly.
    // PluginContentRoute's default gear navigates to PluginPreferencesScreen which
    // can't render addPreferenceScreen()-based prefs — this bypasses that broken path.
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        setToolbarConfig?.invoke(
            ToolbarConfig(
                title = plugin.name,
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        plugin.openPreferences(context)
                    }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        )
    }

    val d = data
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading…", color = MaterialTheme.colorScheme.onBackground)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Overview card ──────────────────────────────────────────────
        SiCard(title = "Overview") {
            SiRow(
                "${d.dayLabel}  ${d.hour}:00",
                "Current hour used for circadian adjustments"
            )
            val modeColor = if (d.mealMode == "Fasting") MaterialTheme.colorScheme.onSurface
            else Color(0xFF64B5F6)
            SiRow("Mode: ${d.mealMode}",
                  d.modeRemMins?.let { "${it}min remaining" } ?: "No active meal — fasting rules apply",
                  primaryColor = modeColor)

            val aggrLocked = d.mealMode != "Fasting"
            SiRow(
                if (aggrLocked) "Aggressiveness locked — meal mode active" else aggrDesc(d.aggressiveness),
                "Aggressiveness: ${"%.3f".format(d.aggressiveness)}  Circ ceiling: ${"%.3f".format(d.circCeil)}\n" +
                    if (aggrLocked) "Locked at 1.0 during meal modes — not applied. Fasting value shown for reference."
                    else "Short/long term adjustment summary.",
                primaryColor = if (aggrLocked) MaterialTheme.colorScheme.onSurfaceVariant
                else aggrColor(d.aggressiveness)
            )

            // Pre-bolus 1 delivered
            if (d.mealMode != "Fasting" && (d.activeDoseU ?: 0.0) > 0.0)
                SiRow("Pre-bolus 1 — delivered ${"%.2f".format(d.activeDoseU)}U", null,
                      primaryColor = Color(0xFF43A047))

            // Pre-bolus 2 delivered / pending gates
            if (d.mealMode != "Fasting" && (d.activePb2DoseU ?: 0.0) > 0.0)
                SiRow("Pre-bolus 2 — delivered ${"%.2f".format(d.activePb2DoseU)}U",
                      "Second bolus delivered as scheduled.", primaryColor = Color(0xFF43A047))

            if (d.pb2Status.isNotEmpty() || d.pb2GateData != null) {
                val gate = d.pb2GateData
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
                SiRow(pb2Primary, null, primaryColor = if (isActive) Color(0xFF43A047) else Color(0xFF64B5F6))

                if (gate != null) {
                    val isMmolG = gate.isMmol
                    fun fmtBg(mgdl: Double)    = if (isMmolG) "${"%.1f".format(mgdl / 18.0)} mmol" else "${"%.0f".format(mgdl)} mg/dL"
                    fun fmtDelta(mgdl: Double) = if (isMmolG) "%+.2f mmol".format(mgdl / 18.0) else "%+.1f mg/dL".format(mgdl)
                    val effectiveMin = maxOf(MealOverrideManager.MIN_BG_FOR_PB2_MGDL, gate.profileTargetMgdl)
                    val bgOk  = gate.bgMgdl > effectiveMin
                    val iobOk = gate.iobU < gate.maxIobU * MealOverrideManager.MAX_IOB_HEADROOM_RATIO
                    val maxAllowedIob = gate.maxIobU * MealOverrideManager.MAX_IOB_HEADROOM_RATIO
                    val deltaOk = gate.deltaMgdl >= MealOverrideManager.DELTA_INSTANT_BLOCK_MGDL
                    val shortOk = gate.shortAvgDeltaMgdl >= MealOverrideManager.SHORT_AVG_DELTA_BLOCK_MGDL
                    SiRow("${if (bgOk) "✓" else "✗"} BG: ${fmtBg(gate.bgMgdl)} (${if (bgOk) "above target ✓" else "${fmtBg(effectiveMin - gate.bgMgdl)} below target — waiting"})",
                          "Must be above profile target ${fmtBg(gate.profileTargetMgdl)}",
                          primaryColor = if (bgOk) Color(0xFF43A047) else Color(0xFFE53935))
                    SiRow("${if (iobOk) "✓" else "✗"} IOB: ${"%.2f".format(gate.iobU)}U / ${"%.2f".format(gate.maxIobU)}U (${if (iobOk) "${"%.2f".format(maxAllowedIob - gate.iobU)}U headroom" else "IOB too high — waiting"})",
                          "Must be below ${(MealOverrideManager.MAX_IOB_HEADROOM_RATIO * 100).toInt()}% of max (${"%.2f".format(maxAllowedIob)}U)",
                          primaryColor = if (iobOk) Color(0xFF43A047) else Color(0xFFE53935))
                    SiRow("${if (deltaOk) "✓" else "✗"} Delta: ${fmtDelta(gate.deltaMgdl)} (${if (deltaOk) "not falling fast" else "falling — waiting"})",
                          "Blocked below ${fmtDelta(MealOverrideManager.DELTA_INSTANT_BLOCK_MGDL)}",
                          primaryColor = if (deltaOk) Color(0xFF43A047) else Color(0xFFE53935))
                    SiRow("${if (shortOk) "✓" else "✗"} 15min avg: ${fmtDelta(gate.shortAvgDeltaMgdl)} (${if (shortOk) "trend stable" else "sustained fall — waiting"})",
                          "Blocked below ${fmtDelta(MealOverrideManager.SHORT_AVG_DELTA_BLOCK_MGDL)}",
                          primaryColor = if (shortOk) Color(0xFF43A047) else Color(0xFFE53935))
                }
            }

            val isfUnit = if (d.isMmol) "mmol/U" else "mg/dL/U"
            val pfIsf = if (d.isMmol) d.profileIsfMgdl / 18.0 else d.profileIsfMgdl
            val fIsf  = if (d.isMmol) d.finalIsfMgdl   / 18.0 else d.finalIsfMgdl
            SiRow("Insulin sensitivity: ${"%.1f".format(fIsf)} $isfUnit",
                  "Profile ${"%.1f".format(pfIsf)} ÷ multiplier ${"%.3f".format(d.isfMultiplier)} = ${"%.1f".format(fIsf)} $isfUnit\n" +
                      "Higher multiplier = higher ISF = less aggressive.")
            SiRow("Basal rate: ${"%.3f".format(d.finalBasalU)} U/h",
                  "Profile ${"%.3f".format(d.profileBasalU)} × multiplier ${"%.3f".format(d.basalMultiplier)} = ${"%.3f".format(d.finalBasalU)} U/h\n" +
                      "Last basal learning: ${d.lastBasalSignal}")
        }

        // ── Rebound card ───────────────────────────────────────────────
        if (d.inReboundWindow || d.bgWentLow) {
            SiCard(title = "Low Recovery", titleColor = Color(0xFFFB8C00)) {
                if (d.inReboundWindow) {
                    val elapsedMins = d.reboundMins.toDouble()
                    val windowMins  = d.totalReboundWindowMins.toDouble()
                    val baseMins    = d.reboundWindowMins.toDouble()
                    val minsLeft    = (windowMins - elapsedMins).coerceAtLeast(0.0).toInt()
                    val elapsedInt  = elapsedMins.toInt().coerceAtMost(windowMins.toInt())
                    val taperFrac   = (0.3 + (0.7 * (elapsedMins / windowMins))).coerceIn(0.3, 1.0)
                    val tbrPct      = (taperFrac * 100).toInt()
                    val smbGateMins = windowMins * 0.75
                    val smbUnlockIn = (smbGateMins - elapsedMins).coerceAtLeast(0.0).toInt()
                    val headline    = if (smbUnlockIn > 0)
                        "⚠ Recovery in progress — ${elapsedInt}min of ${windowMins.toInt()}min"
                    else
                        "⚠ Recovery in progress — SMBs restored, tapering off in ${minsLeft}min"
                    SiRow(headline, null, primaryColor = Color(0xFFFB8C00))
                    SiRow("TBR capped at ${tbrPct}% of normal",
                          "Starts at 30% and ramps back to 100% over ${windowMins.toInt()} minutes.\nPrevents insulin stacking after a low.")
                    if (smbUnlockIn > 0) {
                        SiRow("SMBs blocked — unlocks in ~${smbUnlockIn}min",
                              "SMBs held back for first ${smbGateMins.toInt()} minutes (75% of ${windowMins.toInt()}min window).\nAvoids over-correcting while the low is still resolving.",
                              primaryColor = Color(0xFFE53935))
                    } else {
                        SiRow("SMBs restored ✓",
                              "Corrections running normally again. TBR taper still active for ${minsLeft}min.",
                              primaryColor = Color(0xFF43A047))
                    }
                    if (d.consecutiveRollercoasters >= 1) {
                        val extMins = (windowMins - baseMins).toInt()
                        SiRow("Rollercoaster ${d.consecutiveRollercoasters} detected — extending recovery by ${extMins}min",
                              "Base: ${baseMins.toInt()}min + ${extMins}min extension = ${windowMins.toInt()}min total.\nExtension grows with each consecutive rollercoaster (max +45min).\nResets after 2h with no further rollercoasters.",
                              primaryColor = Color(0xFFFB8C00))
                    }
                    if (d.mealMode != "Fasting") {
                        SiRow("Meal mode active — low recovery bypassed until window finishes",
                              "Recovery protection (TBR taper, SMB gate) continues in background.\nMeal mode ISF and dosing applied on top. Recovery ends in ${minsLeft}min.",
                              primaryColor = Color(0xFF64B5F6))
                    }
                    if (d.softLandingBypass) {
                        SiRow("Soft landing — meal detection still active",
                              "The low was borderline (not a crash). UAM can still fire during recovery in case you eat.",
                              primaryColor = Color(0xFF64B5F6))
                    }
                    if (d.minBgDuringLow < Double.MAX_VALUE) {
                        val lowStr = if (d.isMmol) "${"%.1f".format(d.minBgDuringLow / 18.0)} mmol" else "${"%.0f".format(d.minBgDuringLow)} mg/dL"
                        val secNote = if (d.secondLowOccurred) "\n⚠ Second low occurred — full lockout, UAM blocked." else ""
                        SiRow("Lowest BG: $lowStr", "IOB at time of low: ${"%.2f".format(d.iobAtLowTime)}U$secNote")
                    }
                } else {
                    val extNote = if (d.consecutiveRollercoasters >= 1) {
                        val extMins = d.totalReboundWindowMins - d.reboundWindowMins
                        "\nRollercoaster ${d.consecutiveRollercoasters} detected — window extended by ${extMins}min."
                    } else ""
                    SiRow("⚠ BG is below low guard — waiting for recovery",
                          "Once BG rises above the low guard, the ${d.totalReboundWindowMins}-minute recovery window starts automatically.$extNote",
                          primaryColor = Color(0xFFE53935))
                    if (d.mealMode != "Fasting") {
                        SiRow("✓ Low recovery bypassed — meal mode active (${d.mealMode})",
                              "Meal mode ISF and dosing running normally.\nRecovery window activates automatically when BG crosses back above the low guard.",
                              primaryColor = Color(0xFF43A047))
                    }
                    if (d.minBgDuringLow < Double.MAX_VALUE) {
                        val lowStr2 = if (d.isMmol) "${"%.1f".format(d.minBgDuringLow / 18.0)} mmol" else "${"%.0f".format(d.minBgDuringLow)} mg/dL"
                        SiRow("Lowest BG: $lowStr2", "IOB at time of low: ${"%.2f".format(d.iobAtLowTime)}U")
                    }
                }
            }
        }

        // ── TIR card ───────────────────────────────────────────────────
        SiCard(title = "Time in Range") {
            Text("How much time BG has spent in the healthy range over the last 24h.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            TirSection("Fasting", d.tirRawLine, "Fasting")
            Spacer(Modifier.height(8.dp))
            TirSection("Meal", d.tirRawLine, "Meal")
            if (d.estimatedHba1c > 0.0) {
                val avgStr = if (d.isMmol) "${"%.1f".format(d.avgBgMgdl24h / 18.0)} mmol/L"
                else "${"%.0f".format(d.avgBgMgdl24h)} mg/dL"
                val windowNote = if (d.bgWindowHours < 24) " (${d.bgWindowHours}h data)" else ""
                val hba1cColor = when {
                    d.estimatedHba1c < 6.5 -> Color(0xFF43A047)
                    d.estimatedHba1c < 7.5 -> Color(0xFFFB8C00)
                    else -> Color(0xFFE53935)
                }
                Spacer(Modifier.height(8.dp))
                Text("Est. HbA1c: ${"%.1f".format(d.estimatedHba1c)}%  •  avg: $avgStr$windowNote",
                     color = hba1cColor, fontSize = 13.sp)
            }
        }

        // ── Learning card ──────────────────────────────────────────────
        SiCard(title = "Learning") {
            Text("SmartInsulin adapts to your body over time using real BG data.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val (learningPrimary, learningColor) = when {
                d.learningState.startsWith("off") ->
                    "Learning paused — ${d.learningState.removePrefix("off: ").trim()}" to Color(0xFFFB8C00)
                d.learningState == "limited" ->
                    "Limited — meal mode active, only learning Peak/DIA" to Color(0xFFFB8C00)
                else -> "Learning active" to Color(0xFF43A047)
            }
            SiRow(learningPrimary,
                  "SmartInsulin continuously refines your insulin timing, basal rate, and aggressiveness.\nState: ${d.learningState}",
                  primaryColor = learningColor)
            if (d.postMealLockoutMins > 0 && d.mealMode == "Fasting")
                SiRow("Post-meal pause: ${d.postMealLockoutMins}min remaining",
                      "BG data after meals is excluded from basal/ISF learning.", primaryColor = Color(0xFFFB8C00))
            SiRow("Activity: ${d.activityLevel}",
                  "HR: ${d.avgHrBpm} bpm avg  •  Steps: ${d.steps5min}/5min")
            if (d.cgmWarmup)
                SiRow("New sensor — learning paused for first 24h",
                      "Resumes automatically after 24h.", primaryColor = Color(0xFFFB8C00))

            // Aggression nudge — full detail matching old fragment
            val nudgeParts      = d.lastAggrNudgeStatus.split("|")
            val nudgeState      = nudgeParts.getOrNull(0) ?: "INACTIVE"
            val nudgeActiveHigh = nudgeState == "ACTIVE_HIGH"
            val nudgeActiveLow  = nudgeState == "ACTIVE_LOW"
            val nudgeActive     = nudgeActiveHigh || nudgeActiveLow
            val nudgePaused     = nudgeState == "PAUSED"
            val nudgeColor = when {
                nudgeActiveHigh                  -> Color(0xFFFB8C00)
                nudgeActiveLow                   -> Color(0xFF4CAF50)
                nudgePaused                      -> Color(0xFF64B5F6)
                d.inReboundWindow || d.bgWentLow -> Color(0xFFFB8C00)
                else                             -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val nudgeHeadline: String
            val nudgeDetail: String
            when {
                nudgeActive -> {
                    val deviation  = nudgeParts.getOrNull(1) ?: "?"
                    val day        = nudgeParts.getOrNull(2) ?: "?"
                    val hour2      = nudgeParts.getOrNull(3)?.toIntOrNull()
                    val cooldown   = nudgeParts.getOrNull(8) == "COOLDOWN"
                    val penaltyR   = nudgeParts.getOrNull(9) ?: "recent penalty"
                    val coolNote   = if (cooldown) " (attenuated — $penaltyR)" else ""
                    val hourStr    = hour2?.let { h ->
                        if (d.isMmol) { val ap = if (h < 12) "AM" else "PM"; val h12 = if (h == 0) 12 else if (h > 12) h - 12 else h; "$h12:00 $ap" }
                        else "%02d:00".format(h)
                    } ?: "?"
                    val wasIsf     = if (d.nudgeSessionIsfMgdl > 0) if (d.isMmol) "${"%.2f".format(d.nudgeSessionIsfMgdl / 18.0)} mmol/U" else "${"%.1f".format(d.nudgeSessionIsfMgdl)} mg/dL/U" else "?"
                    val nowIsf     = if (d.finalIsfMgdl > 0) if (d.isMmol) "${"%.2f".format(d.finalIsfMgdl / 18.0)} mmol/U" else "${"%.1f".format(d.finalIsfMgdl)} mg/dL/U" else "?"
                    val wasBas     = if (d.nudgeSessionBasalU > 0) "${"%.3f".format(d.nudgeSessionBasalU)} U/h" else "?"
                    val nowBas     = if (d.finalBasalU > 0) "${"%.3f".format(d.finalBasalU)} U/h" else "?"
                    val shortPct   = kotlin.math.abs(((1.0 - d.circCeil) * 100).roundToInt())
                    val longPct    = kotlin.math.abs(((1.0 - d.basalMultiplier) * 100).roundToInt())
                    val shortLine  = if (nudgeActiveHigh)
                        "Short term: pulling out ~${shortPct}% insulin right now (ceiling ${(d.circCeil * 100).roundToInt()}%)"
                    else
                        "Short term: adding ~${shortPct}% extra insulin right now (ceiling ${(d.circCeil * 100).roundToInt()}%)"
                    val longLine   = when {
                        longPct < 2     -> "Long term: still building — less than 2% change so far"
                        nudgeActiveHigh -> "Long term: permanently reduced by ~${longPct}% at this hour${if (longPct < shortPct) " (still learning)" else " (dialling in)"}"
                        else            -> "Long term: permanently increased by ~${longPct}% at this hour${if (longPct < shortPct) " (still learning)" else " (dialling in)"}"
                    }
                    val statusLine = if (cooldown)
                        "Adjusting cautiously — $penaltyR may have contributed. Full strength resumes after 2h."
                    else
                        "Updating every 5 min while fasting continues. If BG settles near target, this hour is dialling in."
                    nudgeHeadline = "⚡ ${if (nudgeActiveHigh) "Too much insulin — adjusting" else "Not enough insulin — adjusting"}$coolNote"
                    nudgeDetail   = "$deviation detected at $hourStr on ${day}s\nISF was $wasIsf → now $nowIsf\nBasal was $wasBas → now $nowBas\n$shortLine\n$longLine\n$statusLine"
                }
                nudgePaused -> {
                    val reason    = nudgeParts.getOrNull(1) ?: "Learning suppressed"
                    nudgeHeadline = "⏸ Paused — $reason"
                    nudgeDetail   = "Adjustments paused while not in clean fasting state.\nWill resume nudging ISF and basal once fasting resumes."
                }
                d.inReboundWindow -> {
                    val minsLeft   = (d.totalReboundWindowMins - d.reboundMins).coerceAtLeast(0)
                    val taperPct   = ((0.3 + 0.7 * (d.reboundMins.toDouble() / d.totalReboundWindowMins)) * 100).roundToInt()
                    val smbUnlock  = ((d.totalReboundWindowMins * 0.75) - d.reboundMins).coerceAtLeast(0.0).roundToInt()
                    val hardLow1   = if (d.hardLowPenaltyActive) "\nShort term: aggressiveness ceiling cut by 20% — resets as BG stabilises near target." else ""
                    val hardLow2   = if (d.hardLowPenaltyActive) "\nLong term: basal & ISF reduced by ~10% at this hour — will dial back in as BG stabilises."
                    else "\nLong term: learning paused during recovery — resumes when window expires ($minsLeft min left)."
                    val rollerN    = if (d.consecutiveRollercoasters >= 1) { val ext = d.totalReboundWindowMins - d.reboundWindowMins; "\nRollercoaster ${d.consecutiveRollercoasters} detected — window extended by ${ext}min." } else ""
                    nudgeHeadline  = "⚠ BG is below low guard — reducing insulin"
                    nudgeDetail    = "BG crossed below low guard — holding back to avoid stacking.\nShort term: TBR at ${taperPct}% of normal — ramps up over ${d.totalReboundWindowMins}min window\nShort term: SMBs ${if (smbUnlock > 0) "blocked for ~${smbUnlock}min more" else "restored ✓"}$hardLow1$hardLow2$rollerN"
                }
                d.bgWentLow -> {
                    val hardLow1   = if (d.hardLowPenaltyActive) "\nShort term: aggressiveness ceiling cut by 20% — resets as BG stabilises near target." else ""
                    val hardLow2   = if (d.hardLowPenaltyActive) "\nLong term: basal & ISF reduced by ~10% at this hour — will dial back in as BG stabilises."
                    else "\nLong term: learning paused — will resume once ${d.totalReboundWindowMins}min recovery window completes."
                    nudgeHeadline  = "⚠ BG is below low guard — waiting for recovery"
                    nudgeDetail    = "BG is below the low guard threshold. Insulin delivery limited.\nShort term: insulin being held back until BG recovers above low guard$hardLow1$hardLow2"
                }
                else -> {
                    nudgeHeadline  = "Insulin levels look right for this hour"
                    nudgeDetail    = "No consistent over- or under-delivery detected.\nISF and basal learning running on observed BG patterns."
                }
            }
            SiRow(nudgeHeadline, nudgeDetail, primaryColor = nudgeColor)
        }

        // ── UAM card ───────────────────────────────────────────────────
        SiCard(title = "UAM Auto-Detection") {
            val uamLine = d.uamStatusLine ?: ""
            val uamPart = uamLine.substringBefore(" | P/F:").trim()
            val (uamPrimary, uamColor) = when {
                uamPart.contains("watching") -> "BG rising — building confirmation streak ↑" to Color(0xFFFB8C00)
                uamPart.contains("last")     -> "Meal auto-detected recently" to Color(0xFF64B5F6)
                uamPart.contains("armed")    -> "Watching for unannounced meals" to Color(0xFF43A047)
                uamPart.contains("off")      -> "Auto-detection off — outside hours" to MaterialTheme.colorScheme.onSurfaceVariant
                else                         -> "UAM status" to MaterialTheme.colorScheme.onSurface
            }
            SiRow(uamPrimary, uamPart.ifEmpty { null }, primaryColor = uamColor)

            val pfPart = if (uamLine.contains("P/F:")) uamLine.substringAfter("P/F:").trim() else null

            // Detection thresholds from uamDebug (non-P/F lines)
            val debugClean = d.uamDebug.lines()
                .filter { !it.trimStart().startsWith("P/F") }
                .joinToString("\n") { it.trimStart() }
                .trim()
            if (debugClean.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SiRow("Detection thresholds",
                      debugClean + "\n\nUAM fires when BG rises consistently above the trigger threshold during your configured meal windows.\n\nClean window = fasting, normal thresholds apply.\nDirty window = post-meal lockout active — thresholds raised (~1.5×) to avoid detecting fat/protein tail rises as a new meal.")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Protein / Fat Detection (P/F)",
                 style = MaterialTheme.typography.labelLarge,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            val pfDebug = d.uamDebug.lines()
                .filter { it.trimStart().startsWith("P/F") }
                .joinToString("\n") { it.trimStart() }
                .trim()
            val (pfPrimary, pfColor) = when {
                pfPart == null                                        -> "P/F detection disabled" to MaterialTheme.colorScheme.onSurfaceVariant
                pfPart.contains("off")                               -> "P/F off — ${pfPart.substringAfter("off").trim().removePrefix("(").removeSuffix(")")}" to MaterialTheme.colorScheme.onSurfaceVariant
                pfPart.contains("armed")                             -> "Armed — will activate after meal expires" to Color(0xFF43A047)
                pfPart.contains("/") && pfPart.contains("stuck")     -> {
                    val count = Regex("""(\d+/\d+)""").find(pfPart)?.groupValues?.get(1)
                    "BG stuck high — counting readings ($count)" to Color(0xFFFB8C00)
                }
                else                                                  -> "P/F: $pfPart" to MaterialTheme.colorScheme.onSurface
            }
            SiRow(pfPrimary, pfDebug.takeIf { it.isNotEmpty() }, primaryColor = pfColor)
        }

        // ── Soft Target Fine-Tune card ────────────────────────────────
        SiCard(title = "Soft Target Fine-Tune") {
            if (d.stftActive && d.stftStatus != null) {
                SiRow("Active — gently nudging the loop to correct",
                      d.stftStatus + "\n\nSoft Target Fine-Tune temporarily lowers the loop's internal target\nwhen fasting BG stays stuck above target. Resets when BG falls.",
                      primaryColor = Color(0xFFFB8C00))
            } else {
                val inactiveReason = when {
                    d.stftStatus?.contains("high temp target") == true -> "Inactive — high temp target set"
                    d.mealMode.contains("Protein") || d.mealMode.contains("P/F") -> "Inactive — P/F running"
                    d.mealMode.startsWith("UAM") || d.mealMode.contains("(UAM)") -> "Inactive — UAM running"
                    d.mealMode != "Fasting" -> "Inactive — meal mode running (${d.mealMode})"
                    else -> "Inactive — BG is responding normally"
                }
                SiRow(inactiveReason,
                      "STFT activates when fasting BG stays above target for 3+ readings (~15min).\nLowers the loop's target slightly without changing your profile.")
            }
        }

        // ── Circadian table ────────────────────────────────────────────
        SiCard(title = "Circadian 24h") {
            // Day selector
            val dayLabels = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            val displayOrder = intArrayOf(1, 2, 3, 4, 5, 6, 0)
            val todayDow = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                displayOrder.forEach { d2 ->
                    val label = if (d2 == todayDow) "Today" else dayLabels[d2]
                    val selected = d2 == selectedDow
                    Button(
                        onClick = { selectedDow = d2 },
                        modifier = Modifier.weight(1f).height(32.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) Color(0xFF43A047) else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selected) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        Text(label, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Hourly multipliers learned from your BG patterns.\n" +
                     "ISF and Bas = values the loop actually delivers for that hour.\n" +
                     "Ceil = aggressiveness cap — lower = more conservative.\n" +
                     "Conf = confidence — how much real data collected. Green ≥60%, amber ≥30%, red <30%.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            // Table header
            val isfHeader = if (d.isMmol) "ISF mmol" else "ISF mg/dL"
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Hr",       modifier = Modifier.weight(1.5f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(isfHeader,  modifier = Modifier.weight(2.5f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Basal U/h",modifier = Modifier.weight(2.5f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Ceil",     modifier = Modifier.weight(2f),   fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Conf",     modifier = Modifier.weight(3f),   fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))
            val raw = plugin.circadianDataForDay(selectedDow)
            val currentHr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            // Compute profile values for conversion: finalISF = profileISF / isfMult, finalBasal = profileBasal * basMult
            val profileIsfMgdl = d.profileIsfMgdl.toFloat()
            val profileBasalU  = d.profileBasalU.toFloat()
            raw.lines().filter { it.isNotBlank() }.forEach { line ->
                val m = Regex("""[\u25ba\s]\s*(\d{1,2})\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+(\d+)%""").find(line) ?: return@forEach
                val hr      = m.groupValues[1].toIntOrNull() ?: return@forEach
                val isfMult = m.groupValues[2].toFloatOrNull() ?: return@forEach
                val basMult = m.groupValues[3].toFloatOrNull() ?: return@forEach
                val ceil    = m.groupValues[4].toFloatOrNull() ?: return@forEach
                val conf    = m.groupValues[5].toIntOrNull() ?: return@forEach
                // Convert multipliers to actual values
                val finalIsf   = if (isfMult > 0f && profileIsfMgdl > 0f) profileIsfMgdl / isfMult else 0f
                val finalBasal = profileBasalU * basMult
                // ISF display: mg/dL or mmol
                val isfStr  = if (d.isMmol) "%.2f".format(finalIsf / 18.0f) else "%.1f".format(finalIsf)
                val basStr  = "%.3f".format(finalBasal)
                // Color: lower ISF = more sensitive = blue, higher = less sensitive = orange
                fun isfColor(mult: Float) = when { mult < 0.95f -> Color(0xFF64B5F6); mult > 1.05f -> Color(0xFFFB8C00); else -> Color(0xFFAAAAAA) }
                fun basColor(mult: Float) = when { mult < 0.95f -> Color(0xFF64B5F6); mult > 1.05f -> Color(0xFFFB8C00); else -> Color(0xFFAAAAAA) }
                fun confColor(p: Int) = when { p >= 60 -> Color(0xFF43A047); p >= 30 -> Color(0xFFFB8C00); else -> Color(0xFFE53935) }
                val isCur  = selectedDow == todayDow && hr == currentHr
                val rowBg  = if (isCur) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                Row(modifier = Modifier.fillMaxWidth().background(rowBg).padding(vertical = 1.dp)) {
                    Text(if (isCur) "►$hr" else "  $hr", modifier = Modifier.weight(1.5f), fontSize = 11.sp,
                         color = if (isCur) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                         fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal)
                    Text(isfStr,  modifier = Modifier.weight(2.5f), fontSize = 11.sp, color = isfColor(isfMult))
                    Text(basStr,  modifier = Modifier.weight(2.5f), fontSize = 11.sp, color = basColor(basMult))
                    Text("%.3f".format(ceil), modifier = Modifier.weight(2f), fontSize = 11.sp,
                         color = when { ceil < 0.95f -> Color(0xFF64B5F6); ceil > 1.05f -> Color(0xFFFB8C00); else -> Color(0xFFAAAAAA) })
                    Row(modifier = Modifier.weight(3f), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(modifier = Modifier.width(40.dp).height(6.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .background(Color(0xFF333333))) {
                            Box(modifier = Modifier.fillMaxHeight()
                                .width(40.dp * (conf / 100f))
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                                .background(confColor(conf)))
                        }
                        Text("$conf%", fontSize = 10.sp, color = confColor(conf),
                             fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // ── Insulin profiles card ──────────────────────────────────────
        SiCard(title = "Insulin Profiles") {
            Text("Learned peak and duration per meal type. Green = learned, amber = learning, grey = using profile values.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            d.profilesRawStatus.lines().filter { it.isNotBlank() }.forEach { line ->
                val parts = line.trim().split(":"); if (parts.size < 2) return@forEach
                val name = parts[0].trim(); val info = parts.drop(1).joinToString(":").trim()
                val n = Regex("""n=(\d+)""").find(info)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val col = when { n >= 5 -> Color(0xFF43A047); n >= 1 -> Color(0xFFFB8C00); else -> Color(0xFF888888) }
                // Highlight the active insulin profile — match mode label against profile name
                val isActive = d.mealMode.contains(name, ignoreCase = true) ||
                    name.contains(d.mealMode.substringBefore(" ("), ignoreCase = true)
                val prefix = if (isActive) "► " else "  "
                val note = when {
                    n == 0 -> "  (using profile values — not enough data yet)"
                    n < 5  -> "  (still learning)"
                    else   -> ""
                }
                val rowBg = if (isActive) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .background(rowBg)
                        .padding(vertical = 4.dp, horizontal = 4.dp)
                ) {
                    Text("$prefix$name", fontWeight = FontWeight.Bold, color = col, fontSize = 13.sp)
                    Text(info + note, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(2.dp))
            }
        }

        // ── Raw status log ─────────────────────────────────────────────
        SiCard(title = "Raw Status Log") {
            Text(plugin.statusSummary(),
                 fontFamily = FontFamily.Monospace,
                 fontSize = 11.sp,
                 lineHeight = 15.sp,
                 color = MaterialTheme.colorScheme.onSurface)
        }

        // ── Reset card ─────────────────────────────────────────────────
        SiCard(title = "Reset Learners") {
            ResetRow("Aggressiveness score") { plugin.resetAggression() }
            ResetRow("Basal multiplier") { plugin.resetBasal() }
            ResetRow("Circadian hourly learning") { plugin.resetCircadian() }
            ResetRow("Insulin profiles (peak/DIA)") { plugin.resetProfiles() }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Button(
                onClick = { plugin.resetAllLearners() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("Reset ALL Learners", fontSize = 18.sp) }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun SiCard(title: String, titleColor: Color = Color.Unspecified, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                 color = if (titleColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else titleColor)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SiRow(primary: String, detail: String?, primaryColor: Color = Color.Unspecified) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(primary, fontWeight = FontWeight.Bold, fontSize = 14.sp,
             color = if (primaryColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else primaryColor)
        if (detail != null)
            Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                 fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
    }
}

@Composable
private fun ResetRow(label: String, onClick: () -> Unit) {
    var confirmed by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 16.sp,
             color = MaterialTheme.colorScheme.onSurface)
        OutlinedButton(onClick = {
            if (confirmed) { onClick(); confirmed = false } else confirmed = true
        }) {
            Text(if (confirmed) "Confirm?" else "Reset")
        }
    }
}

@Composable
private fun TirSection(label: String, tirRaw: String, prefix: String) {
    val m = Regex("""$prefix:(\d+)%in/(\d+)%hi/(\d+)%lo""").find(tirRaw)
    val inPct   = m?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
    val highPct = m?.groupValues?.get(2)?.toFloatOrNull() ?: 0f
    val lowPct  = m?.groupValues?.get(3)?.toFloatOrNull() ?: 0f
    Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(6.dp))
    val total = lowPct + inPct + highPct
    if (total > 0f) {
        // Stacked bar — use fillMaxWidth with proportional widths via BoxWithConstraints
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().height(14.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
        ) {
            val totalWidth = maxWidth
            Row(modifier = Modifier.fillMaxSize()) {
                if (lowPct  > 0f) Box(Modifier.width(totalWidth * (lowPct  / total)).fillMaxHeight().background(Color(0xFFE53935)))
                if (inPct   > 0f) Box(Modifier.width(totalWidth * (inPct   / total)).fillMaxHeight().background(Color(0xFF43A047)))
                if (highPct > 0f) Box(Modifier.width(totalWidth * (highPct / total)).fillMaxHeight().background(Color(0xFFFB8C00)))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (inPct   > 0f) Text("${inPct.toInt()}% in range",  fontSize = 11.sp, color = Color(0xFF43A047))
            if (highPct > 0f) Text("${highPct.toInt()}% high",    fontSize = 11.sp, color = Color(0xFFFB8C00))
            if (lowPct  > 0f) Text("${lowPct.toInt()}% low",      fontSize = 11.sp, color = Color(0xFFE53935))
        }
    } else {
        Box(modifier = Modifier.fillMaxWidth().height(14.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant))
        Spacer(Modifier.height(4.dp))
        Text("Not enough data yet — needs ~2 hours of ${label.lowercase()} readings",
             fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun aggrDesc(a: Double) = when {
    a > 1.15 -> "Delivering more insulin than usual"
    a > 1.05 -> "Slightly more aggressive than normal"
    a < 0.85 -> "Being cautious — reducing insulin"
    a < 0.95 -> "Slightly conservative"
    else     -> "Normal aggressiveness"
}

@Composable
private fun aggrColor(a: Double): Color = when {
    a > 1.05 -> Color(0xFFFB8C00)
    a < 0.95 -> Color(0xFF64B5F6)
    else     -> MaterialTheme.colorScheme.onSurface
}