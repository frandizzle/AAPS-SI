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
    onNavigateBack: () -> Unit = {}
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
                    val minsLeft = (d.totalReboundWindowMins - d.reboundMins).coerceAtLeast(0)
                    SiRow("⚠ Recovery in progress — ${d.reboundMins}min of ${d.totalReboundWindowMins}min",
                          "SMBs blocked / tapering. ${minsLeft}min remaining.",
                          primaryColor = Color(0xFFFB8C00))
                } else {
                    SiRow("⚠ BG is below low guard — waiting for recovery",
                          "Recovery window will start automatically when BG rises.",
                          primaryColor = Color(0xFFE53935))
                }
                if (d.minBgDuringLow < Double.MAX_VALUE) {
                    val lowStr = if (d.isMmol) "${"%.1f".format(d.minBgDuringLow / 18.0)} mmol"
                    else "${"%.0f".format(d.minBgDuringLow)} mg/dL"
                    SiRow("Lowest BG: $lowStr", "IOB at time of low: ${"%.2f".format(d.iobAtLowTime)}U")
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

            // Aggression nudge
            val nudgeParts = d.lastAggrNudgeStatus.split("|")
            val nudgeState = nudgeParts.getOrNull(0) ?: "INACTIVE"
            val nudgeColor = when (nudgeState) {
                "ACTIVE_HIGH" -> Color(0xFFFB8C00)
                "ACTIVE_LOW"  -> Color(0xFF4CAF50)
                "PAUSED"      -> Color(0xFF64B5F6)
                else          -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val nudgeHeadline = when (nudgeState) {
                "ACTIVE_HIGH" -> "⚡ Too much insulin — adjusting"
                "ACTIVE_LOW"  -> "⚡ Not enough insulin — adjusting"
                "PAUSED"      -> "⏸ Paused — ${nudgeParts.getOrNull(1) ?: "Learning suppressed"}"
                else          -> "Insulin levels look right for this hour"
            }
            SiRow(nudgeHeadline, null, primaryColor = nudgeColor)
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
            Spacer(Modifier.height(8.dp))
            // Table header
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Hr",  modifier = Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("ISF×", modifier = Modifier.weight(2f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Bas×", modifier = Modifier.weight(2f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Ceil", modifier = Modifier.weight(2f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Conf", modifier = Modifier.weight(3f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))
            val raw = plugin.circadianDataForDay(selectedDow)
            val currentHr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            raw.lines().filter { it.isNotBlank() }.forEach { line ->
                val m = Regex("""[►\s]\s*(\d{1,2})\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+(\d+)%""").find(line) ?: return@forEach
                val hr = m.groupValues[1].toIntOrNull() ?: return@forEach
                val isf = m.groupValues[2].toFloatOrNull() ?: return@forEach
                val bas = m.groupValues[3].toFloatOrNull() ?: return@forEach
                val ceil = m.groupValues[4].toFloatOrNull() ?: return@forEach
                val conf = m.groupValues[5].toIntOrNull() ?: return@forEach
                val isCur = selectedDow == todayDow && hr == currentHr
                val rowBg = if (isCur) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                fun multColor(v: Float) = when { v > 1.05f -> Color(0xFFFB8C00); v < 0.95f -> Color(0xFF64B5F6); else -> Color(0xFFAAAAAA) }
                fun confColor(p: Int) = when { p >= 60 -> Color(0xFF43A047); p >= 30 -> Color(0xFFFB8C00); else -> Color(0xFFE53935) }
                Row(modifier = Modifier.fillMaxWidth().background(rowBg).padding(vertical = 1.dp)) {
                    Text(if (isCur) "►$hr" else "  $hr", modifier = Modifier.weight(1f), fontSize = 11.sp,
                         color = if (isCur) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                         fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal)
                    Text("%.3f".format(isf),  modifier = Modifier.weight(2f), fontSize = 11.sp, color = multColor(isf))
                    Text("%.3f".format(bas),  modifier = Modifier.weight(2f), fontSize = 11.sp, color = multColor(bas))
                    Text("%.3f".format(ceil), modifier = Modifier.weight(2f), fontSize = 11.sp, color = multColor(ceil))
                    // Confidence bar — Box based, always fills correctly
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
                Text("$name:", fontWeight = FontWeight.Bold, color = col, fontSize = 13.sp)
                Text(info, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
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