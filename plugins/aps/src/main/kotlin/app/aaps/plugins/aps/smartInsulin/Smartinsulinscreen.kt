package app.aaps.plugins.aps.smartInsulin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventLoopUpdateGui
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.plugins.aps.smartInsulin.MealPhaseTracker
import app.aaps.core.ui.compose.ToolbarConfig
import io.reactivex.rxjava3.disposables.Disposable
import kotlin.math.roundToInt
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

    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner, plugin) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // 1. INSTANT UPDATE: Fires the millisecond you open the tab
            data = plugin.fragmentData()

            // 2. EVENT-DRIVEN UPDATE: Native Flow collection
            // Note: This assumes your SmartInsulinPlugin class has the injected RxBus
            // exposed as a public property.
            plugin.rxBus.toFlow(EventLoopUpdateGui::class.java)
                .collect {
                    // The loop just finished! Grab the freshest data.
                    data = plugin.fragmentData()
                }
        }
    }

    // Use the provided onSettings callback to navigate to plugin preferences.
    LaunchedEffect(Unit) {
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
                        onSettings?.invoke()
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
            else StatusInfo
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
                      primaryColor = StatusGood)

            // Pre-bolus 2 delivered / pending gates
            if (d.mealMode != "Fasting" && (d.activePb2DoseU ?: 0.0) > 0.0)
                SiRow("Pre-bolus 2 — delivered ${"%.2f".format(d.activePb2DoseU)}U",
                      "Second bolus delivered as scheduled.", primaryColor = StatusGood)

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
                SiRow(pb2Primary, null, primaryColor = if (isActive) StatusGood else StatusInfo)

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
                          primaryColor = if (bgOk) StatusGood else StatusBad)
                    SiRow("${if (iobOk) "✓" else "✗"} IOB: ${"%.2f".format(gate.iobU)}U / ${"%.2f".format(gate.maxIobU)}U (${if (iobOk) "${"%.2f".format(maxAllowedIob - gate.iobU)}U headroom" else "IOB too high — waiting"})",
                          "Must be below ${(MealOverrideManager.MAX_IOB_HEADROOM_RATIO * 100).toInt()}% of max (${"%.2f".format(maxAllowedIob)}U)",
                          primaryColor = if (iobOk) StatusGood else StatusBad)
                    SiRow("${if (deltaOk) "✓" else "✗"} Delta: ${fmtDelta(gate.deltaMgdl)} (${if (deltaOk) "not falling fast" else "falling — waiting"})",
                          "Blocked below ${fmtDelta(MealOverrideManager.DELTA_INSTANT_BLOCK_MGDL)}",
                          primaryColor = if (deltaOk) StatusGood else StatusBad)
                    SiRow("${if (shortOk) "✓" else "✗"} 15min avg: ${fmtDelta(gate.shortAvgDeltaMgdl)} (${if (shortOk) "trend stable" else "sustained fall — waiting"})",
                          "Blocked below ${fmtDelta(MealOverrideManager.SHORT_AVG_DELTA_BLOCK_MGDL)}",
                          primaryColor = if (shortOk) StatusGood else StatusBad)
                }
            }

            // Pre-bolus 3 delivered / pending gates
            if (d.mealMode != "Fasting" && (d.activePb3DoseU ?: 0.0) > 0.0)
                SiRow("Pre-bolus 3 — delivered ${"%.2f".format(d.activePb3DoseU)}U",
                      "Third bolus delivered as scheduled (late-meal cover).", primaryColor = StatusGood)

            if (d.pb3Status.isNotEmpty() || d.pb3GateData != null) {
                val gate3 = d.pb3GateData
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
                SiRow(pb3Primary,
                      if (d.pb3Status.contains("waiting for PB2"))
                          "Timer starts when PB2 delivers. If PB2 is cancelled, PB3 cancels too."
                      else null,
                      primaryColor = if (isActive3) StatusGood else StatusInfo)

                if (gate3 != null) {
                    val isMmolG3 = gate3.isMmol
                    fun fmtBg3(mgdl: Double)    = if (isMmolG3) "${"%.1f".format(mgdl / 18.0)} mmol" else "${"%.0f".format(mgdl)} mg/dL"
                    fun fmtDelta3(mgdl: Double) = if (isMmolG3) "%+.2f mmol".format(mgdl / 18.0) else "%+.1f mg/dL".format(mgdl)
                    val effectiveMin3 = maxOf(MealOverrideManager.MIN_BG_FOR_PB2_MGDL, gate3.profileTargetMgdl)
                    val bgOk3  = gate3.bgMgdl > effectiveMin3
                    val iobOk3 = gate3.iobU < gate3.maxIobU * MealOverrideManager.MAX_IOB_HEADROOM_RATIO
                    val maxAllowedIob3 = gate3.maxIobU * MealOverrideManager.MAX_IOB_HEADROOM_RATIO
                    val deltaOk3 = gate3.deltaMgdl >= MealOverrideManager.DELTA_INSTANT_BLOCK_MGDL
                    val shortOk3 = gate3.shortAvgDeltaMgdl >= MealOverrideManager.SHORT_AVG_DELTA_BLOCK_MGDL
                    SiRow("${if (bgOk3) "✓" else "✗"} BG: ${fmtBg3(gate3.bgMgdl)} (${if (bgOk3) "above target ✓" else "${fmtBg3(effectiveMin3 - gate3.bgMgdl)} below target — waiting"})",
                          "Must be above profile target ${fmtBg3(gate3.profileTargetMgdl)}",
                          primaryColor = if (bgOk3) StatusGood else StatusBad)
                    SiRow("${if (iobOk3) "✓" else "✗"} IOB: ${"%.2f".format(gate3.iobU)}U / ${"%.2f".format(gate3.maxIobU)}U (${if (iobOk3) "${"%.2f".format(maxAllowedIob3 - gate3.iobU)}U headroom" else "IOB too high — waiting"})",
                          "Must be below ${(MealOverrideManager.MAX_IOB_HEADROOM_RATIO * 100).toInt()}% of max (${"%.2f".format(maxAllowedIob3)}U)",
                          primaryColor = if (iobOk3) StatusGood else StatusBad)
                    SiRow("${if (deltaOk3) "✓" else "✗"} Delta: ${fmtDelta3(gate3.deltaMgdl)} (${if (deltaOk3) "not falling fast" else "falling — waiting"})",
                          "Blocked below ${fmtDelta3(MealOverrideManager.DELTA_INSTANT_BLOCK_MGDL)}",
                          primaryColor = if (deltaOk3) StatusGood else StatusBad)
                    SiRow("${if (shortOk3) "✓" else "✗"} 15min avg: ${fmtDelta3(gate3.shortAvgDeltaMgdl)} (${if (shortOk3) "trend stable" else "sustained fall — waiting"})",
                          "Blocked below ${fmtDelta3(MealOverrideManager.SHORT_AVG_DELTA_BLOCK_MGDL)}",
                          primaryColor = if (shortOk3) StatusGood else StatusBad)
                }
            }

            val isfUnit = if (d.isMmol) "mmol/U" else "mg/dL/U"
            val pfIsf = if (d.isMmol) d.profileIsfMgdl / 18.0 else d.profileIsfMgdl
            val fIsf  = if (d.isMmol) d.finalIsfMgdl   / 18.0 else d.finalIsfMgdl
            SiRow("Insulin sensitivity: ${"%.1f".format(fIsf)} $isfUnit",
                  "Profile ${"%.1f".format(pfIsf)} ÷ multiplier ${"%.3f".format(d.isfMultiplier)} = ${"%.1f".format(fIsf)} $isfUnit\n" +
                      "Lower ISF = more insulin delivered per BG gap. Multiplier >1 reduces ISF, <1 raises ISF.")
            SiRow("Basal rate: ${"%.3f".format(d.finalBasalU)} U/h",
                  "Profile ${"%.3f".format(d.profileBasalU)} × multiplier ${"%.3f".format(d.basalMultiplier)} = ${"%.3f".format(d.finalBasalU)} U/h\n" +
                      "Last basal learning: ${d.lastBasalSignal}")
        }

        // ── Rebound card ───────────────────────────────────────────────
        if (d.inReboundWindow || d.bgWentLow) {
            SiCard(title = "Low Recovery", titleColor = StatusWarn) {
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
                    SiRow(headline, null, primaryColor = StatusWarn)
                    SiRow("TBR capped at ${tbrPct}% of normal",
                          "Starts at 30% and ramps back to 100% over ${windowMins.toInt()} minutes.\nPrevents insulin stacking after a low.")
                    if (smbUnlockIn > 0) {
                        SiRow("SMBs blocked — unlocks in ~${smbUnlockIn}min",
                              "SMBs held back for first ${smbGateMins.toInt()} minutes (75% of ${windowMins.toInt()}min window).\nAvoids over-correcting while the low is still resolving.",
                              primaryColor = StatusBad)
                    } else {
                        SiRow("SMBs restored ✓",
                              "Corrections running normally again. TBR taper still active for ${minsLeft}min.",
                              primaryColor = StatusGood)
                    }
                    if (d.consecutiveRollercoasters >= 1) {
                        val extMins = (windowMins - baseMins).toInt()
                        SiRow("Rollercoaster ${d.consecutiveRollercoasters} detected — extending recovery by ${extMins}min",
                              "Base: ${baseMins.toInt()}min + ${extMins}min extension = ${windowMins.toInt()}min total.\nExtension grows with each consecutive rollercoaster (max +45min).\nResets after 2h with no further rollercoasters.",
                              primaryColor = StatusWarn)
                    }
                    if (d.mealMode != "Fasting") {
                        SiRow("Meal mode active — low recovery bypassed until window finishes",
                              "Recovery protection (TBR taper, SMB gate) continues in background.\nMeal mode ISF and dosing applied on top. Recovery ends in ${minsLeft}min.",
                              primaryColor = StatusInfo)
                    }
                    if (d.softLandingBypass) {
                        SiRow("Soft landing — meal detection still active",
                              "The low was borderline (not a crash). UAM can still fire during recovery in case you eat.",
                              primaryColor = StatusInfo)
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
                          primaryColor = StatusBad)
                    if (d.mealMode != "Fasting") {
                        SiRow("✓ Low recovery bypassed — meal mode active (${d.mealMode})",
                              "Meal mode ISF and dosing running normally.\nRecovery window activates automatically when BG crosses back above the low guard.",
                              primaryColor = StatusGood)
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
                    d.estimatedHba1c < 6.5 -> StatusGood
                    d.estimatedHba1c < 7.5 -> StatusWarn
                    else -> StatusBad
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
                    "Learning paused — ${d.learningState.removePrefix("off: ").trim()}" to StatusWarn
                d.learningState == "limited" ->
                    "Limited — meal mode active, only learning Peak/DIA" to StatusWarn
                else -> "Learning active" to StatusGood
            }
            SiRow(learningPrimary,
                  "SmartInsulin continuously refines your insulin timing, basal rate, and aggressiveness.\nState: ${d.learningState}",
                  primaryColor = learningColor)
            if (d.postMealLockoutMins > 0 && d.mealMode == "Fasting")
                SiRow("Post-meal pause: ${d.postMealLockoutMins}min remaining",
                      "BG data after meals is excluded from basal/ISF learning.", primaryColor = StatusWarn)
            SiRow("Activity: ${d.activityLevel}",
                  "HR: ${d.avgHrBpm} bpm avg  •  Steps: ${d.steps5min}/5min")
            if (d.cgmWarmup)
                SiRow("New sensor — learning paused for first 24h",
                      "Resumes automatically after 24h.", primaryColor = StatusWarn)

            // Aggression nudge — full detail matching old fragment
            val nudgeParts      = d.lastAggrNudgeStatus.split("|")
            val nudgeState      = nudgeParts.getOrNull(0) ?: "INACTIVE"
            val nudgeActiveHigh = nudgeState == "ACTIVE_HIGH"
            val nudgeActiveLow  = nudgeState == "ACTIVE_LOW"
            val nudgeActive     = nudgeActiveHigh || nudgeActiveLow
            val nudgePaused     = nudgeState == "PAUSED"
            val nudgeTrim       = nudgeState == "TRIM"
            val trimDirection   = nudgeParts.getOrNull(1) ?: ""
            val trimPct         = nudgeParts.getOrNull(2) ?: "0%"
            // Detect whether ISF and basal are learned above or below profile at this hour.
            // Used for both the color accent and the fallback "learning state" message below.
            //
            // ISF direction: dosingISF = profileISF / isfMultiplier
            //   multiplier > 1 → ISF value LOWER than profile → MORE insulin per BG gap
            //   multiplier < 1 → ISF value HIGHER than profile → LESS insulin per BG gap
            // Basal direction:
            //   finalBasal > profile → MORE continuous insulin
            //   finalBasal < profile → LESS continuous insulin
            //
            // Possible states:
            //   • All more     — ISF lower AND/OR basal higher (both pulling toward more insulin)
            //   • All less     — ISF higher AND/OR basal lower (both pulling toward less insulin)
            //   • Mixed        — ISF and basal pulling opposite directions (no net editorial)
            //   • At profile   — both within ±2–3% of profile
            val bgBelowTarget         = d.currentBgMgdl > 0.0 && d.profileTargetMgdl > 0.0 &&
                d.currentBgMgdl < d.profileTargetMgdl
            val basalLessThanProfile  = d.finalBasalU > 0.0 && d.profileBasalU > 0.0 &&
                d.finalBasalU < d.profileBasalU * 0.98       // basal >2% below profile
            val basalMoreThanProfile  = d.finalBasalU > 0.0 && d.profileBasalU > 0.0 &&
                d.finalBasalU > d.profileBasalU * 1.02       // basal >2% above profile
            val isfHigherThanProfile  = d.isfMultiplier < 0.97     // ISF > profile → less insulin per gap
            val isfLowerThanProfile   = d.isfMultiplier > 1.03     // ISF < profile → more insulin per gap
            val allMoreInsulin = (isfLowerThanProfile && !basalLessThanProfile) ||
                (basalMoreThanProfile && !isfHigherThanProfile)
            val allLessInsulin = (isfHigherThanProfile && !basalMoreThanProfile) ||
                (basalLessThanProfile && !isfLowerThanProfile)
            val mixed          = (isfLowerThanProfile && basalLessThanProfile) ||
                (isfHigherThanProfile && basalMoreThanProfile)
            val predTrimActive = d.lastBasalSignal.contains("PredTrim") &&
                d.lastBasalSignal.contains("proj=-")
            // Amber accent when PredTrim is pulling insulin back, or everything is below profile
            val showingDefensive = !nudgeActive && !nudgePaused && !nudgeTrim &&
                !(d.inReboundWindow || d.bgWentLow) &&
                (predTrimActive || allLessInsulin)

            val nudgeColor = when {
                nudgeActiveHigh                              -> StatusGood
                nudgeActiveLow                               -> StatusWarn
                nudgePaused                                  -> StatusInfo
                nudgeTrim && trimDirection == "ACTIVE_HIGH"  -> StatusGood
                nudgeTrim && trimDirection == "ACTIVE_LOW"   -> StatusWarn
                d.inReboundWindow || d.bgWentLow             -> StatusWarn
                showingDefensive                             -> StatusWarn  // delivering less insulin than profile
                else                                         -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val nudgeHeadline: String
            val nudgeDetail: String
            when {
                nudgeTrim -> {
                    val adding = trimDirection == "ACTIVE_HIGH"
                    val trimPctVal = trimPct.removeSuffix("%").toFloatOrNull() ?: 0f
                    val shortTerm = if (adding) "adding ~${"%.0f".format(trimPctVal)}% insulin" else "removing ~${"%.0f".format(trimPctVal)}% insulin"
                    val longTerm  = if (adding) "feeding +${"%.0f".format(trimPctVal * 0.5f)}% long-term" else "feeding -${"%.0f".format(trimPctVal * 0.5f)}% long-term"
                    val wasIsf = if (d.nudgeSessionIsfMgdl > 0) if (d.isMmol) "${"%.2f".format(d.nudgeSessionIsfMgdl / 18.0)} mmol/U" else "${"%.1f".format(d.nudgeSessionIsfMgdl)} mg/dL/U" else "?"
                    val nowIsf = if (d.finalIsfMgdl > 0) if (d.isMmol) "${"%.2f".format(d.finalIsfMgdl / 18.0)} mmol/U" else "${"%.1f".format(d.finalIsfMgdl)} mg/dL/U" else "?"
                    val wasBas = if (d.nudgeSessionBasalU > 0) "${"%.3f".format(d.nudgeSessionBasalU)} U/h" else "?"
                    val nowBas = if (d.finalBasalU > 0) "${"%.3f".format(d.finalBasalU)} U/h" else "?"
                    nudgeHeadline = "⚡ Fuel trim: $shortTerm (BG off target for full peak window)"
                    nudgeDetail   = "ISF was $wasIsf → now $nowIsf\nBasal was $wasBas → now $nowBas\n$shortTerm short-term (ceiling moved)\n$longTerm into ISF & basal at this hour\nDecays automatically once BG returns to target."
                }
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
                        "Short term: adding ~${shortPct}% extra insulin right now (ceiling ${(d.circCeil * 100).roundToInt()}%)"
                    else
                        "Short term: pulling out ~${shortPct}% insulin right now (ceiling ${(d.circCeil * 100).roundToInt()}%)"
                    val longLine   = when {
                        longPct < 2     -> "Long term: still building — less than 2% change so far"
                        nudgeActiveHigh -> "Long term: permanently increased by ~${longPct}% at this hour${if (longPct < shortPct) " (still learning)" else " (dialling in)"}"
                        else            -> "Long term: permanently reduced by ~${longPct}% at this hour${if (longPct < shortPct) " (still learning)" else " (dialling in)"}"
                    }
                    val statusLine = if (cooldown)
                        "Adjusting cautiously — $penaltyR may have contributed. Full strength resumes after 2h."
                    else
                        "Updating every 5 min while fasting continues. If BG settles near target, this hour is dialling in."
                    nudgeHeadline = "⚡ ${if (nudgeActiveHigh) "Not enough insulin — adjusting" else "Too much insulin — adjusting"}$coolNote"
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
                    val hardLow2 = if (d.hardLowPenaltyActive) {
                        "\nLong term: basal & ISF reduced by ~10% at this hour — will dial back in as BG stabilises."
                    } else {
                        "\nLong term: learning paused during recovery — resumes when window expires ($minsLeft min left)."
                    }
                    val rollerN    = if (d.consecutiveRollercoasters >= 1) { val ext = d.totalReboundWindowMins - d.reboundWindowMins; "\nRollercoaster ${d.consecutiveRollercoasters} detected — window extended by ${ext}min." } else ""
                    nudgeHeadline  = "⚠ BG is below low guard — reducing insulin"
                    nudgeDetail    = "BG crossed below low guard — holding back to avoid stacking.\nShort term: TBR at ${taperPct}% of normal — ramps up over ${d.totalReboundWindowMins}min window\nShort term: SMBs ${if (smbUnlock > 0) "blocked for ~${smbUnlock}min more" else "restored ✓"}$hardLow1$hardLow2$rollerN"
                }
                d.bgWentLow -> {
                    val hardLow1   = if (d.hardLowPenaltyActive) "\nShort term: aggressiveness ceiling cut by 20% — resets as BG stabilises near target." else ""
                    val hardLow2 = if (d.hardLowPenaltyActive) {
                        "\nLong term: basal & ISF reduced by ~10% at this hour — will dial back in as BG stabilises."
                    } else {
                        "\nLong term: learning paused — will resume once ${d.totalReboundWindowMins}min recovery window completes."
                    }
                    nudgeHeadline  = "⚠ BG is below low guard — waiting for recovery"
                    nudgeDetail    = "BG is below the low guard threshold. Insulin delivery limited.\nShort term: insulin being held back until BG recovers above low guard$hardLow1$hardLow2"
                }
                else -> {
                    // Formatting helpers — match Overview card style (actual values, not multipliers)
                    val curIsf  = if (d.isMmol) "${"%.1f".format(d.finalIsfMgdl / 18.0)} mmol/U" else "${"%.0f".format(d.finalIsfMgdl)} mg/dL/U"
                    val profIsf = if (d.isMmol) "${"%.1f".format(d.profileIsfMgdl / 18.0)} mmol/U" else "${"%.0f".format(d.profileIsfMgdl)} mg/dL/U"
                    val curBas  = "${"%.3f".format(d.finalBasalU)} U/h"
                    val profBas = "${"%.3f".format(d.profileBasalU)} U/h"

                    // Individual lines — each states direction in plain English
                    val isfLine = when {
                        isfLowerThanProfile  -> "ISF: $curIsf (profile $profIsf) — lower than profile → more insulin per BG gap"
                        isfHigherThanProfile -> "ISF: $curIsf (profile $profIsf) — higher than profile → less insulin per BG gap"
                        else                 -> "ISF: $curIsf (profile $profIsf) — at profile"
                    }
                    val basalLine = when {
                        basalMoreThanProfile -> "Basal: $curBas (profile $profBas) — higher than profile → more continuous insulin"
                        basalLessThanProfile -> "Basal: $curBas (profile $profBas) — lower than profile → less continuous insulin"
                        else                 -> "Basal: $curBas (profile $profBas) — at profile"
                    }

                    when {
                        // Most informative case: PredTrim firing right now AND BG confirms the concern
                        predTrimActive && bgBelowTarget -> {
                            nudgeHeadline = "⬇ Cutting basal — BG below target"
                            nudgeDetail   = "Immediate defensive action (PredTrim) is running:\n" +
                                "• $basalLine\n" +
                                "• $isfLine\n" +
                                "• Signal: ${d.lastBasalSignal}\n\n" +
                                "Long-term aggression learner is neutral for this hour (${"%.3f".format(d.aggressiveness)}) — it tracks 24h patterns, not single readings. Short-term trim is handling the current dip."
                        }
                        // PredTrim active but BG hasn't dropped below target yet (anticipatory)
                        predTrimActive -> {
                            nudgeHeadline = "⬇ Pre-emptive basal cut — projected drop detected"
                            nudgeDetail   = "PredTrim is cutting basal based on projected BG trend:\n" +
                                "• $basalLine\n" +
                                "• $isfLine\n" +
                                "• Signal: ${d.lastBasalSignal}"
                        }
                        // Everything leans toward more insulin than profile
                        allMoreInsulin -> {
                            nudgeHeadline = "Delivering more insulin than profile this hour"
                            nudgeDetail   = "Learned values at this hour call for more insulin than the base profile:\n" +
                                "• $isfLine\n" +
                                "• $basalLine\n\n" +
                                "Pattern detected — BG typically needs more correction at this hour. Aggression learner neutral — this has matched the long-term pattern."
                        }
                        // Everything leans toward less insulin than profile
                        allLessInsulin -> {
                            nudgeHeadline = "Delivering less insulin than profile this hour"
                            nudgeDetail   = "Learned values at this hour call for less insulin than the base profile:\n" +
                                "• $isfLine\n" +
                                "• $basalLine\n\n" +
                                "Aggression learner neutral — delivery has matched this hour's long-term pattern."
                        }
                        // Mixed: ISF and basal pulling opposite directions — report neutrally
                        mixed -> {
                            val note = when {
                                isfLowerThanProfile && basalLessThanProfile ->
                                    "ISF is pulling for more insulin per BG gap, but basal is running below profile. " +
                                        "Net effect at this hour: more correction when BG drifts up, less continuous insulin when BG is steady."
                                isfHigherThanProfile && basalMoreThanProfile ->
                                    "ISF is pulling for less insulin per BG gap, but basal is running above profile. " +
                                        "Net effect at this hour: less correction when BG drifts up, more continuous insulin when BG is steady."
                                else -> ""
                            }
                            nudgeHeadline = "Mixed learning at this hour"
                            nudgeDetail   = "• $isfLine\n• $basalLine\n\n$note"
                        }
                        // Everything at baseline
                        else -> {
                            nudgeHeadline = "Insulin levels look right for this hour"
                            nudgeDetail   = "Current values at profile:\n" +
                                "• $isfLine\n" +
                                "• $basalLine\n\n" +
                                "No consistent over- or under-delivery detected."
                        }
                    }
                }
            }
            SiRow(nudgeHeadline, nudgeDetail, primaryColor = nudgeColor)

// ── Feed-forward debug section ────────────────────────────
            var showFfDebug by rememberSaveable { mutableStateOf(false) }

            TextButton(
                onClick = { showFfDebug = !showFfDebug },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (showFfDebug) "▲ Hide feed-forward debug" else "▼ Feed-forward debug (Accel + PredTrim + ISF Episode)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (showFfDebug) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Acceleration
                    Text("Acceleration (2nd derivative)",
                         style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.primary)
                    Text(d.lastAccelDebug,
                         style = MaterialTheme.typography.bodySmall,
                         fontFamily = FontFamily.Monospace,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))

                    // Predictive Basal Trim
                    Text("Predictive Basal Trim (60min projection)",
                         style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.primary)
                    Text(d.lastPredTrimDebug,
                         style = MaterialTheme.typography.bodySmall,
                         fontFamily = FontFamily.Monospace,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))

                    // ISF Episode learner
                    Text("ISF Episode Learner (slow-path)",
                         style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.primary)
                    Text(d.lastIsfEpisodeDebug,
                         style = MaterialTheme.typography.bodySmall,
                         fontFamily = FontFamily.Monospace,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))

                    // Last basal signal
                    Text("Last basal signal",
                         style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.primary)
                    Text(d.lastBasalSignal,
                         style = MaterialTheme.typography.bodySmall,
                         fontFamily = FontFamily.Monospace,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ── UAM card ───────────────────────────────────────────────────
        SiCard(title = "UAM Auto-Detection") {
            val uamLine = d.uamStatusLine ?: ""
            val uamPart = uamLine.substringBefore(" | P/F:").trim()

            // Remove the optional "[dirty] " prefix so we can strictly check the first word
            val cleanUamPart = uamPart.removePrefix("[dirty] ").trim()

            val (uamPrimary, uamColor) = when {
                cleanUamPart.startsWith("watching") -> "BG rising — building confirmation streak ↑" to StatusWarn
                cleanUamPart.startsWith("last")     -> "Meal auto-detected recently" to StatusInfo
                cleanUamPart.startsWith("armed")    -> "Watching for unannounced meals" to StatusGood
                cleanUamPart.startsWith("off")      -> {
                    // Extract the specific reason it's off (e.g., "outside hours" or "new sensor")
                    val reason = cleanUamPart.substringAfter("off").removePrefix(" (").removeSuffix(")").trim()
                    "Auto-detection off — $reason" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                else -> "UAM status" to MaterialTheme.colorScheme.onSurface
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
                pfPart.contains("armed")                             -> "Armed — will activate after meal expires" to StatusGood
                pfPart.contains("/") && pfPart.contains("stuck")     -> {
                    val count = Regex("""(\d+/\d+)""").find(pfPart)?.groupValues?.get(1)
                    "BG stuck high — counting readings ($count)" to StatusWarn
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
                      primaryColor = StatusWarn)
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

            val todayDow = remember(data) { java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1 }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                displayOrder.forEach { d2 ->
                    val label = if (d2 == todayDow) "Today" else dayLabels[d2]
                    val selected = d2 == selectedDow
                    Button(
                        onClick = { selectedDow = d2 },
                        modifier = Modifier.weight(1f).height(32.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) StatusGood else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selected) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        contentPadding = PaddingValues(0.dp)
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
            val currentHr = remember(data) { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
            // Compute profile values for conversion: finalISF = profileISF / isfMult, finalBasal = profileBasal * basMult
            val profileIsfMgdl = d.profileIsfMgdl.toFloat()
            val profileBasalU  = d.profileBasalU.toFloat()
            raw.lines().filter { it.isNotBlank() }.forEach { line ->
                val m = Regex("""[▶►\s]\s*(\d{1,2})\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+(\d+)%""").find(line) ?: return@forEach
                val hr      = m.groupValues[1].toIntOrNull() ?: return@forEach
                val valIsf  = m.groupValues[2].toFloatOrNull() ?: return@forEach
                val valBas  = m.groupValues[3].toFloatOrNull() ?: return@forEach
                val ceil    = m.groupValues[4].toFloatOrNull() ?: return@forEach
                val conf    = m.groupValues[5].toIntOrNull() ?: return@forEach

                // Values are already calculated and unit-converted by circadianDataForDay.
                // We use them directly for display.
                val isfStr = if (d.isMmol) "%.2f".format(valIsf) else "%.1f".format(valIsf)
                val basStr = "%.3f".format(valBas)

                // For color coding, we need to compare against the profile to determine if it's more/less aggressive.
                // ISF: lower value = more sensitive = orange (StatusWarn), higher value = less sensitive = blue (StatusInfo)
                // Basal: higher value = more aggressive = orange (StatusWarn), lower value = less aggressive = blue (StatusInfo)
                val profIsfDisp = if (d.isMmol) profileIsfMgdl / 18.0f else profileIsfMgdl
                val isfMultForColor = if (valIsf > 0 && profIsfDisp > 0) profIsfDisp / valIsf else 1f
                val basMultForColor = if (profileBasalU > 0) valBas / profileBasalU else 1f

                fun isfColor(mult: Float) = when { mult > 1.03f -> StatusWarn; mult < 0.97f -> StatusInfo; else -> Color(0xFFAAAAAA) }
                fun basColor(mult: Float) = when { mult > 1.03f -> StatusWarn; mult < 0.97f -> StatusInfo; else -> Color(0xFFAAAAAA) }
                fun confColor(p: Int) = when { p >= 60 -> StatusGood; p >= 30 -> StatusWarn; else -> StatusBad }
                val isCur  = selectedDow == todayDow && hr == currentHr
                val rowBg  = if (isCur) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                Row(modifier = Modifier.fillMaxWidth().background(rowBg).padding(vertical = 1.dp)) {
                    Text(if (isCur) "►$hr" else "  $hr", modifier = Modifier.weight(1.5f), fontSize = 11.sp,
                         color = if (isCur) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                         fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal)
                    Text(isfStr,  modifier = Modifier.weight(2.5f), fontSize = 11.sp, color = isfColor(isfMultForColor))
                    Text(basStr,  modifier = Modifier.weight(2.5f), fontSize = 11.sp, color = basColor(basMultForColor))
                    Text("%.3f".format(ceil), modifier = Modifier.weight(2f), fontSize = 11.sp,
                         color = when { ceil < 0.95f -> StatusInfo; ceil > 1.05f -> StatusWarn; else -> Color(0xFFAAAAAA) })
                    Row(modifier = Modifier.weight(3f), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(modifier = Modifier.width(40.dp).height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF333333))) {
                            Box(modifier = Modifier.fillMaxHeight()
                                .width(40.dp * (conf / 100f))
                                .clip(RoundedCornerShape(3.dp))
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

            val (profStatusPrimary, profStatusColor) = when {
                d.profileLearningStatus.startsWith("off") -> "Learning paused" to StatusWarn
                d.profileLearningStatus.contains("tracker=idle") -> "Watching for new bolus" to StatusGood
                else -> "Tracking active bolus curve" to StatusInfo
            }
            val profStatusDetail = d.profileLearningStatus
                .replace("off: ", "")
                .replace("tracker=idle", "Waiting for IOB spike (≥0.3U) to begin tracking kinetics.")
                .replace("tracker=waiting_peak", "Tracking: waiting for IOB to peak...")
                .replace("tracker=tracking_nadir", "Tracking: waiting for BG nadir...")
                .replace("tracker=confirming", "Tracking: confirming recovery from nadir...")

            SiRow(profStatusPrimary, profStatusDetail, primaryColor = profStatusColor)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            d.profilesRawStatus.lines().filter { it.isNotBlank() }.forEach { line ->
                val parts = line.trim().split(":"); if (parts.size < 2) return@forEach
                val name = parts[0].trim(); val info = parts.drop(1).joinToString(":").trim()
                val n = Regex("""n=(\d+)""").find(info)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val col = when { n >= 5 -> StatusGood; n >= 1 -> StatusWarn; else -> Color(0xFF888888) }
                // Highlight the active insulin profile — match mode label against profile name
                // UAM modes are separate learners, but should only be highlighted if we're actually in UAM
                val isActive = if (d.mealMode.contains("(UAM)", ignoreCase = true)) {
                    name.contains("(UAM)", ignoreCase = true) && d.mealMode.contains(name.substringBefore(" ("), ignoreCase = true)
                } else {
                    !name.contains("(UAM)", ignoreCase = true) && d.mealMode.contains(name, ignoreCase = true)
                }
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
                        .clip(RoundedCornerShape(4.dp))
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
        // ── Meal Phase Tracker card ────────────────────────────────────
        MealPhaseTrackerCard(d)

        // ── PDP card ─────────────────────────────────────────────────────────────
        if (d.pdpEnabled) { PdpCard(d) }

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

// ── PDP card ─────────────────────────────────────────────────────────────────

@Composable
private fun PdpCard(d: SmartInsulinPlugin.FragmentData) {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val isMmol = d.isMmol

    fun fmtCi(mgdl: Double): String =
        if (isMmol) "${"%.2f".format(mgdl / 18.0)} mmol/5min"
        else "${"%.1f".format(mgdl)} mg/dL/5min"

    SiCard(title = "Persistent Deviation Prediction") {

        // ── Status header ────────────────────────────────────────────────
        val blending = d.pdpBlendWeight > 0.01
        val ciBuilding   = d.pdpConsecutiveReadings > 0
        val stuckBuilding = d.pdpStuckHighReadings > 0
        val (statusText, statusColor) = when {
            blending && d.pdpActivePathway == "stuck" ->
                "Active [stuck-high] — BG plateaued above target, blending secondary" to StatusWarn
            blending ->
                "Active [rising deviation] — unexplained rise detected, blending secondary" to StatusWarn
            stuckBuilding ->
                "Watching [stuck-high] — ${d.pdpStuckHighReadings}/${d.pdpMinReadings} flat cycles above target" to StatusInfo
            ciBuilding ->
                "Watching [rising] — ${d.pdpConsecutiveReadings}/${d.pdpMinReadings} rising readings" to StatusInfo
            else ->
                "Inactive — BG on track, using primary IOB prediction" to androidx.compose.material3.MaterialTheme.colorScheme.onSurface
        }
        SiRow(statusText, null, primaryColor = statusColor)

        // ── Blend weight bar ─────────────────────────────────────────────
        if (blending || d.pdpConsecutiveReadings > 0) {
            Spacer(Modifier.height(4.dp))
            val blendPct = (d.pdpBlendWeight * 100).toInt()
            val iobPct   = 100 - blendPct
            Text("Prediction blend", fontSize = 12.sp,
                 color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().height(16.dp)
                    .clip(RoundedCornerShape(4.dp))
            ) {
                val totalWidth = maxWidth
                Row(modifier = Modifier.fillMaxSize()) {
                    // IOB (primary) slice — blue
                    Box(
                        Modifier
                            .width(totalWidth * (iobPct.toFloat() / 100f))
                            .fillMaxHeight()
                            .background(StatusInfo)
                    )
                    // PDP (secondary) slice — orange
                    if (blendPct > 0) {
                        Box(
                            Modifier
                                .width(totalWidth * (blendPct.toFloat() / 100f))
                                .fillMaxHeight()
                                .background(StatusWarn)
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${iobPct}% IOB (primary)", fontSize = 11.sp, color = StatusInfo)
                if (blendPct > 0)
                    Text("${blendPct}% PDP (secondary)", fontSize = 11.sp, color = StatusWarn)
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── Current cycle detail ─────────────────────────────────────────
        SiRow(
            "Deviation (ci): ${fmtCi(d.pdpCiMgdl)}",
            "How much BG is rising beyond what IOB alone predicts.\n" +
                "Positive = unexplained rise (food, stress, dawn, illness). Negative = IOB working well.\n" +
                "Rising pathway: ${d.pdpConsecutiveReadings}/${d.pdpMinReadings} consecutive qualifying readings."
        )
        SiRow(
            "Stuck-high: ${d.pdpStuckHighReadings}/${d.pdpMinReadings} flat cycles above target",
            "Counts cycles where BG is >1.5 mmol above target and delta is flat (±0.15 mmol/5min).\n" +
                "Catches overnight plateaus where IOB is low so ci ≈ 0 but correction isn't happening.\n" +
                "Whichever pathway (rising or stuck) builds faster drives the blend weight."
        )
        SiRow(
            "ci strength: ${"%.2f".format(d.pdpCiStrength)}  •  Fade: ${d.pdpFadeMins}min",
            "Strength scales the deviation signal. 1.0 = as observed, 2.0 = assume twice as persistent.\n" +
                "Fade: how long ci persists in the secondary curve (vs 60min in primary).\n" +
                "Both are tuned by the per-hour learner as it accumulates data."
        )
        if (d.pdpFastingMaxIob > 0.0) {
            SiRow(
                "Fasting max IOB: ${"%.1f".format(d.pdpFastingMaxIob)}U",
                "Safety cap on IOB during fasting to prevent over-stacking when PDP drives more aggressive dosing.\n" +
                    "0.0 = disabled (use global max IOB setting).",
                primaryColor = StatusInfo
            )
        }

        // ── Learning state ───────────────────────────────────────────────
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        val totalSamples = d.pdpHourlySamples.sum()
        val learnColor = when {
            !d.pdpLearningEnabled -> androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            totalSamples < 24    -> StatusWarn
            else                 -> StatusGood
        }
        SiRow(
            if (!d.pdpLearningEnabled) "Learning: disabled"
            else "Learning: active  •  $totalSamples total observations",
            "PDP compares its t+5min prediction against actual BG each fasting cycle.\n" +
                "If the secondary curve was more accurate → strengthen this hour's ci.\n" +
                "If the primary IOB curve was more accurate → nudge back toward neutral.\n" +
                "Needs ~${24 - minOf(24, totalSamples)} more observations before confident.",
            primaryColor = learnColor
        )

        // ── Per-hour 24h table ────────────────────────────────────────────
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text("Per-hour learned ci strength (24h)",
             fontSize = 13.sp, fontWeight = FontWeight.Bold,
             color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Text("  Hr   Strength  Confidence  n",
             fontSize = 10.sp, fontFamily = FontFamily.Monospace,
             color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)

        for (h in 0..23) {
            val marker     = if (h == hour) "▶" else " "
            val strength   = d.pdpHourlyStrengths.getOrElse(h) { 1.0 }
            val confidence = d.pdpHourlyConfidences.getOrElse(h) { 0.0 }
            val samples    = d.pdpHourlySamples.getOrElse(h) { 0 }

            // Color-code: high strength + high confidence = warm (PDP strongly learned here)
            //             low confidence = muted
            val rowColor = when {
                confidence < 0.2 -> androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                strength > 1.5   -> StatusWarn
                strength > 1.1   -> StatusInfo
                else             -> androidx.compose.material3.MaterialTheme.colorScheme.onSurface
            }

            val confBar = buildString {
                val filled = (confidence * 10).toInt().coerceIn(0, 10)
                append("▓".repeat(filled))
                append("░".repeat(10 - filled))
            }

            Text(
                "$marker ${h.toString().padStart(2)}   " +
                    "${"%.3f".format(strength).padStart(8)}  " +
                    "$confBar  " +
                    "$samples",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = if (h == hour) StatusWarn else rowColor
            )
        }

        // ── Interpretation footer ─────────────────────────────────────────
        Spacer(Modifier.height(8.dp))
        Text(
            "Strength >1.0 = this hour's deviations tend to persist (stress/dawn/food). " +
                "Confidence bar shows how many observations back this up. " +
                "Low confidence hours blend toward neutral automatically.",
            fontSize = 10.sp,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 14.sp
        )
    }
}

// ── Meal Phase Tracker card ───────────────────────────────────────────────────

@Composable
private fun MealPhaseTrackerCard(d: SmartInsulinPlugin.FragmentData) {
    // Status colours reused from main screen
    val ColorPending    = Color(0xFF555555)
    val ColorInProgress = Color(0xFF2196F3)   // blue
    val ColorComplete   = StatusGood          // green

    SiCard(title = "Meal Phase Tracker") {
        Text("Learns carb/protein-fat/tail phases of each meal to tune ISF and SMB fraction per phase.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        // ── Session status header ──────────────────────────────────────
        if (!d.mealPhaseActive) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("⏸", fontSize = 16.sp, color = ColorPending)
                Text("No active meal session",
                     fontWeight = FontWeight.Bold,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Tracker arms when meal mode activates or UAM fires.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("🍽", fontSize = 16.sp)
                Text("${d.mealPhaseSessionMode} — ${d.mealPhaseLabel}",
                     fontWeight = FontWeight.Bold,
                     color = ColorInProgress)
                Spacer(Modifier.weight(1f))
                Text("${d.mealPhaseElapsedMins}min elapsed",
                     fontSize = 11.sp,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))

        // ── Phase progress rows ────────────────────────────────────────
        Text("Phase Progress", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))

        PhaseRow(
            label       = "① Carb absorption",
            minRequired = 40,
            status      = d.mealPhaseCarb,
            peakLabel   = "peak BG",
            isMmol      = d.isMmol,
            inProgressColor = ColorInProgress,
            completeColor   = ColorComplete,
            pendingColor    = ColorPending
        )
        Spacer(Modifier.height(6.dp))
        PhaseRow(
            label       = "② Protein / Fat plateau",
            minRequired = 60,
            status      = d.mealPhasePF,
            peakLabel   = "plateau BG",
            isMmol      = d.isMmol,
            inProgressColor = ColorInProgress,
            completeColor   = ColorComplete,
            pendingColor    = ColorPending
        )
        Spacer(Modifier.height(6.dp))
        PhaseRow(
            label       = "③ Tail — returning to target",
            minRequired = 30,
            status      = d.mealPhaseTail,
            peakLabel   = "nadir BG",
            isMmol      = d.isMmol,
            inProgressColor = Color(0xFFFF9800),  // orange — crash risk window
            completeColor   = ColorComplete,
            pendingColor    = ColorPending
        )

        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        // ── What the learner would change ─────────────────────────────
        Text("Learned adjustments (once validated)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Text("After enough clean sessions, the learner will adjust:",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))

        val adjColor = MaterialTheme.colorScheme.onSurfaceVariant
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            LearnerAdjRow("① Carb phase ISF",    "More aggressive (lower ISF) to handle rapid BG rise", adjColor)
            LearnerAdjRow("① Carb phase SMB",    "Higher fraction — carbs need fast delivery", adjColor)
            LearnerAdjRow("② P/F phase ISF",     "Moderate — plateau needs steady correction", adjColor)
            LearnerAdjRow("② P/F phase SMB",     "Lower fraction — fat slows absorption", adjColor)
            LearnerAdjRow("③ Tail ISF",          "Raised (less aggressive) to prevent crash", adjColor)
            LearnerAdjRow("③ Tail SMB",          "Reduced/blocked — BG is already falling", adjColor)
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        // ── Session validity flags ─────────────────────────────────────
        Text("Session quality", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))

        if (d.mealPhaseActive) {
            val debugLines = d.mealPhaseDebug.split(" | ")
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                debugLines.forEach { segment ->
                    Text(segment.trim(),
                         fontFamily = FontFamily.Monospace,
                         fontSize   = 11.sp,
                         color      = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Text(d.mealPhaseDebug,
                 fontFamily = FontFamily.Monospace,
                 fontSize   = 11.sp,
                 color      = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ── Detection thresholds (debug reference) ─────────────────────
        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text("Detection thresholds", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            ThreshRow("Carb → P/F",  "min 40 min  |  delta must slow below 0.15 mmol/5min for 3 readings")
            ThreshRow("P/F → Tail",  "min 60 min  |  delta < −0.10 mmol/5min for 3 readings, BG above target")
            ThreshRow("Tail done",   "min 30 min  |  BG ≤ target + 0.5 mmol")
            ThreshRow("UAM cancel",  "If mode drops to fasting with BG near target → counts as success")
            ThreshRow("Invalidated", "BG still elevated when fasting resumes  |  meal stacking detected (Δ > 0.40)")
            ThreshRow("Flagged",     "Manual bolus detected — SMB fraction may have been too low")
            ThreshRow("Confirm",     "3 consecutive readings must agree before any transition fires")
        }
    }
}

@Composable
private fun PhaseRow(
    label:           String,
    minRequired:     Int,
    status:          MealPhaseTracker.PhaseStatus,
    peakLabel:       String,
    isMmol:          Boolean,
    inProgressColor: Color,
    completeColor:   Color,
    pendingColor:    Color
) {
    val (icon, stateText, stateColor) = when (status.state) {
        MealPhaseTracker.PhaseStatus.PhaseState.PENDING ->
            Triple("○", "Pending — min ${minRequired}min required", pendingColor)
        MealPhaseTracker.PhaseStatus.PhaseState.IN_PROGRESS ->
            Triple("◉", "In progress…", inProgressColor)
        MealPhaseTracker.PhaseStatus.PhaseState.COMPLETE ->
            Triple("✓", "Complete — ${status.durationMins}min", completeColor)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(icon, fontSize = 14.sp, color = stateColor, fontWeight = FontWeight.Bold)
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                 color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Text(stateText, fontSize = 11.sp, color = stateColor)
        }
        // Show peak/nadir BG when in progress or complete
        if (status.state != MealPhaseTracker.PhaseStatus.PhaseState.PENDING &&
            status.peakOrNadirMmol > 0.0) {
            val bgStr = if (isMmol) "${"%.1f".format(status.peakOrNadirMmol)} mmol/L"
            else "${"%.0f".format(status.peakOrNadirMmol * 18.0)} mg/dL"
            Text("   $peakLabel: $bgStr",
                 fontSize = 11.sp,
                 color    = MaterialTheme.colorScheme.onSurfaceVariant,
                 fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun LearnerAdjRow(phase: String, description: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("•", fontSize = 11.sp, color = color)
        Column {
            Text(phase, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                 color = MaterialTheme.colorScheme.onSurface)
            Text(description, fontSize = 10.sp, color = color)
        }
    }
}

@Composable
private fun ThreshRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label.padEnd(12), fontSize = 10.sp, fontWeight = FontWeight.Bold,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             fontFamily = FontFamily.Monospace)
        Text(value, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
             fontFamily = FontFamily.Monospace)
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

    // Auto-clear confirm state after 5 seconds to prevent accidental taps later
    if (confirmed) {
        LaunchedEffect(Unit) {
            delay(5_000)
            confirmed = false
        }
    }

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
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().height(14.dp)
                .clip(RoundedCornerShape(4.dp))
        ) {
            val totalWidth = maxWidth
            Row(modifier = Modifier.fillMaxSize()) {
                if (lowPct  > 0f) Box(Modifier.width(totalWidth * (lowPct  / total)).fillMaxHeight().background(StatusBad))
                if (inPct   > 0f) Box(Modifier.width(totalWidth * (inPct   / total)).fillMaxHeight().background(StatusGood))
                if (highPct > 0f) Box(Modifier.width(totalWidth * (highPct / total)).fillMaxHeight().background(StatusWarn))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (inPct   > 0f) Text("${inPct.toInt()}% in range",  fontSize = 11.sp, color = StatusGood)
            if (highPct > 0f) Text("${highPct.toInt()}% high",    fontSize = 11.sp, color = StatusWarn)
            if (lowPct  > 0f) Text("${lowPct.toInt()}% low",      fontSize = 11.sp, color = StatusBad)
        }
    } else {
        Box(modifier = Modifier.fillMaxWidth().height(14.dp)
            .clip(RoundedCornerShape(4.dp))
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
    a > 1.05 -> StatusWarn
    a < 0.95 -> StatusInfo
    else     -> MaterialTheme.colorScheme.onSurface
}

// ── Global UI Constants & Helpers ──────────────────────────────────────────────

private val StatusGood   = Color(0xFF43A047)  // success, learning healthy, armed
private val StatusWarn   = Color(0xFFFB8C00)  // attention needed, recovery, building streak
private val StatusBad    = Color(0xFFE53935)  // blocked, failed, low
private val StatusInfo   = Color(0xFF64B5F6)  // informational, meal mode, recent UAM

private const val MMOL_TO_MGDL = 18.0
private fun Double.mgdlToMmol() = this / MMOL_TO_MGDL
private fun Double.mmolToMgdl() = this * MMOL_TO_MGDL