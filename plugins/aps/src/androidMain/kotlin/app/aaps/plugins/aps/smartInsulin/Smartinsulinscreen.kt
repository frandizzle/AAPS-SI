package app.aaps.plugins.aps.smartInsulin

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.GolfCourse
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.ToolbarConfig
import kotlinx.coroutines.delay

/**
 * The SmartInsulin tab.
 *
 * Everything the tab SAYS is decided in [SmartInsulinTabCards], ported from the 3.4 fragment unchanged.
 * This file decides how it looks: a summary header, then foldable 4.0-style sections.
 */
@Composable
fun SmartInsulinScreen(
    plugin: SmartInsulinPlugin,
    onNavigateBack: () -> Unit,
    onSettings: (() -> Unit)?,
    setToolbarConfig: (ToolbarConfig) -> Unit
) {
    val cards = remember(plugin) { SmartInsulinTabCards(plugin) }
    // Ticks to force a re-read; the plugin's data is plain state, not observable.
    var tick by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    // Circadian day shown; starts on today each time the tab opens, as in 3.4.
    var circadianDow by remember { mutableIntStateOf(todayDow()) }
    // Start counting straight away on a grant; otherwise it would wait for the next app start.
    val stepPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) plugin.startPhoneStepCounter()
        tick++
    }

    LaunchedEffect(Unit) {
        setToolbarConfig(
            ToolbarConfig(
                title = plugin.name,
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    onSettings?.let { IconButton(onClick = it) { Icon(Icons.Filled.Settings, contentDescription = null) } }
                }
            )
        )
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(REFRESH_MS); tick++
        }
    }

    val onAction: (SiAction) -> Unit = { a ->
        when (a) {
            SiAction.REQUEST_STEP_PERMISSION -> stepPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            SiAction.TOGGLE_FF_DEBUG         -> {
                cards.showFfDebug = !cards.showFfDebug; tick++
            }
        }
    }

    // Read on every tick; `tick` is here so recomposition re-runs the builders.
    @Suppress("UNUSED_EXPRESSION") tick
    val d = plugin.fragmentData()
    val colors = AapsTheme.generalColors

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryCard(d)

        SiSection("General", Icons.Filled.Tune, subtitle = "What the loop is doing this hour") {
            SiItems(cards.buildGeneralCard(d).items, onAction)
        }

        val session = cards.buildSessionCard()
        SiSection("Activity / Stress Session", Icons.AutoMirrored.Filled.DirectionsRun, accent = AapsTheme.elementColors.insulin) {
            SiItems(session.items, onAction)
            SiSpacer(14)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SessionButton(session.labels["golf"] ?: "Golf", Icons.Filled.GolfCourse, Modifier.weight(1f)) {
                    plugin.toggleActivitySession(SessionLabel.GOLF); tick++
                }
                SessionButton(session.labels["gym"] ?: "Gym", Icons.Filled.FitnessCenter, Modifier.weight(1f)) {
                    plugin.toggleActivitySession(SessionLabel.GYM); tick++
                }
            }
        }

        cards.buildReboundCard(d).takeIf { it.visible }?.let {
            SiSection("Low Recovery", Icons.AutoMirrored.Filled.TrendingDown, accent = colors.statusWarning, subtitle = "Protection window after a low") {
                SiItems(it.items, onAction)
            }
        }

        SiSection("Time in Range", Icons.Filled.DonutLarge, accent = colors.bgInRange, subtitle = "Last 24 hours") { SiTirSection(d) }

        SiSection("Learning", Icons.Filled.Psychology) {
            SiItems(cards.buildLearningCard(d).items, onAction)
        }
        SiSection("Meal Auto-Detection (UAM)", Icons.Filled.Restaurant, accent = AapsTheme.elementColors.carbs,
                  subtitle = "Detects unannounced meals from BG rises") {
            SiItems(cards.buildUamCard(d).items, onAction)
        }
        SiSection("Soft Target Fine-Tune", Icons.Filled.GpsFixed, accent = colors.adjusted,
                  subtitle = if (d.stftActive) "Active" else "Nudges the target for stubborn highs") {
            SiItems(cards.buildStftCard(d).items, onAction)
        }
        SiSection("Circadian 24h", Icons.Filled.Schedule, subtitle = "Hour-by-hour learned ISF and basal") {
            SiCircadianSection(plugin, circadianDow) { circadianDow = it; tick++ }
        }
        val journal = plugin.learningJournalEntries()
        SiSection(
            "Learning Journal", Icons.Filled.History,
            subtitle = if (journal.isEmpty()) "No learning changes recorded yet" else "${journal.size} changes in the last 14 days",
            initiallyExpanded = false
        ) {
            LearningJournalList(journal)
        }
        SiSection("Learned Insulin Profiles", Icons.Filled.Timeline, subtitle = "Learned peak; DIA fixed at your insulin's", initiallyExpanded = false) {
            SiItems(cards.buildProfilesCard(d).items, onAction)
        }
        SiSection("Raw Status Log", Icons.Filled.Code, accent = MaterialTheme.colorScheme.onSurfaceVariant, initiallyExpanded = false) {
            SiItems(listOf(SiItem.Mono(plugin.statusSummary(), SiTone.STRONG, false)), onAction)
        }

        SiSection("Reset Learners", Icons.Filled.RestartAlt, accent = colors.statusCritical, subtitle = "Undo what SmartInsulin has learned",
                  initiallyExpanded = false) {
            ResetRow("Aggressiveness score") { confirm = "Reset aggressiveness score to 1.0?" to { plugin.resetAggression() } }
            ResetRow("ISF circadian learning") { confirm = "Reset ISF circadian learning to 1.0? Basal and aggression learning kept." to { plugin.resetIsf() } }
            ResetRow("Basal learning") { confirm = "Reset basal + circadian basal learners to 1.0?" to { plugin.resetBasal() } }
            ResetRow("All circadian learning") { confirm = "Reset all circadian (ISF/basal/aggr) hourly learning?" to { plugin.resetCircadian() } }
            ResetRow("Insulin profiles") { confirm = "Reset all learned insulin profiles back to defaults?" to { plugin.resetProfiles() } }
            ResetRow("Meal/UAM learners") {
                confirm = "Reset learned per-meal ISF adjustments and UAM entry fractions back to your configured values?" to { plugin.resetModeLearners() }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Button(
                onClick = { confirm = "Reset ALL learners? This cannot be undone." to { plugin.resetAllLearners() } },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reset everything")
            }
        }
    }

    confirm?.let { (message, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Filled.RestartAlt, contentDescription = null) },
            title = { Text("Confirm reset") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { action(); confirm = null; tick++ }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
}

private const val REFRESH_MS = 5_000L

/** At-a-glance header: what mode the loop is in, whether it is learning, and the numbers it is dosing with. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryCard(d: SmartInsulinPlugin.FragmentData) {
    val colors = AapsTheme.generalColors
    val fasting = d.modeRemMins == null
    val modeColor = when {
        fasting                                                                           -> MaterialTheme.colorScheme.onSurfaceVariant
        d.mealMode.contains("Protein", true) || d.mealMode.contains("P/F", true)          -> MaterialTheme.colorScheme.tertiary
        else                                                                              -> AapsTheme.elementColors.carbs
    }
    val (learnText, learnColor) = when {
        d.learningState.equals("learning", true)  -> "Learning" to colors.statusNormal
        d.learningState.startsWith("limited")    -> "Learning limited" to colors.statusWarning
        d.learningState.startsWith("off")        -> "Paused · ${d.learningState.removePrefix("off:").trim()}" to colors.statusCritical
        else                                      -> d.learningState to colors.statusNormal
    }
    val isfUnits = if (d.isMmol) "mmol/U" else "mg/dL/U"
    val isfShown = if (d.isMmol) "%.2f".format(d.finalIsfMgdl / 18.0) else "%.0f".format(d.finalIsfMgdl)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SiPill(if (fasting) "Fasting" else "${d.mealMode} · ${d.modeRemMins}m left", modeColor)
                SiPill(learnText, learnColor)
                if (d.activityLevel != "Sedentary") SiPill(d.activityLevel, AapsTheme.elementColors.insulin)
                if (d.inReboundWindow) SiPill("Low recovery ${d.reboundMins}m", colors.statusWarning)
            }
            SiSpacer(14)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SiStat("ISF", isfShown, isfUnits, Modifier.weight(1f))
                SiStat("Basal", "%.2f".format(d.finalBasalU), "U/h", Modifier.weight(1f))
                SiStat(
                    "Aggression", "%.2f".format(d.aggressiveness), "ceiling %.2f".format(d.circCeil), Modifier.weight(1f),
                    valueColor = when {
                        d.aggressiveness > 1.05 -> colors.statusWarning
                        d.aggressiveness < 0.95 -> AapsTheme.elementColors.insulin
                        else                    -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }
}

@Composable
private fun SessionButton(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val running = label.startsWith("Stop")
    val content: @Composable () -> Unit = {
        Icon(if (running) Icons.Filled.Stop else icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
    if (running)
        Button(onClick = onClick, modifier = modifier) { content() }
    else
        FilledTonalButton(onClick = onClick, modifier = modifier) { content() }
}

@Composable
private fun ResetRow(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onClick) { Text("Reset") }
    }
}

/**
 * The learning journal, newest first: when, which learner (tag coloured by what it touches), and what it
 * decided. Long histories show the latest [JOURNAL_PAGE] with a toggle for the rest.
 */
@Composable
private fun LearningJournalList(entries: List<LearningJournal.Entry>) {
    if (entries.isEmpty()) {
        Text(
            "Each learner adds a line here when it changes something or decides not to, so drift can be traced " +
                "back to a learner and a day. Circadian ISF/basal are summarised once an hour.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    var showAll by remember { mutableStateOf(false) }
    val shown = if (showAll) entries else entries.take(JOURNAL_PAGE)
    val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
    val dayFmt = remember { java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()) }
    val timeFmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        shown.forEach { e ->
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = e.timeMs }
            val day = if (cal.get(java.util.Calendar.DAY_OF_YEAR) == today) "Today" else dayFmt.format(java.util.Date(e.timeMs))
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$day ${timeFmt.format(java.util.Date(e.timeMs))}", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    SiPill(e.source, journalSourceTone(e.source).color())
                }
                Text(e.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (entries.size > JOURNAL_PAGE)
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "Show latest $JOURNAL_PAGE" else "Show all ${entries.size}")
            }
    }
}

private fun journalSourceTone(source: String): SiTone = when (source) {
    "Reset"                     -> SiTone.CRITICAL
    "DURA", "FuelTrim"          -> SiTone.WARNING
    "Meal ISF", "UAM entry"     -> SiTone.GOOD
    "Circadian", "Insulin profile", "Aggressiveness", "Activity" -> SiTone.INFO
    else                        -> SiTone.MUTED
}

private const val JOURNAL_PAGE = 40
