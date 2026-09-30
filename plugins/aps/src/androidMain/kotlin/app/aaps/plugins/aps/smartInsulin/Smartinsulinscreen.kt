package app.aaps.plugins.aps.smartInsulin

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.ui.compose.ToolbarConfig
import kotlinx.coroutines.delay

/**
 * The SmartInsulin tab.
 *
 * Deliberately thin: everything the tab SAYS is decided in [SmartInsulinTabCards], ported from the 3.4
 * fragment unchanged. This file only lays the cards out and wires the few things that need the UI —
 * the Golf/Gym buttons, the reset confirmations, the feed-forward expander and the pedometer permission.
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
    val stepPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

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
        while (true) { delay(REFRESH_MS); tick++ }
    }

    val onAction: (SiAction) -> Unit = { a ->
        when (a) {
            SiAction.REQUEST_STEP_PERMISSION -> stepPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            SiAction.TOGGLE_FF_DEBUG         -> { cards.showFfDebug = !cards.showFfDebug; tick++ }
        }
    }

    // Read on every tick; `tick` is here so recomposition re-runs the builders.
    @Suppress("UNUSED_EXPRESSION") tick
    val d = plugin.fragmentData()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(plugin.statusSummary(), fontSize = 14.sp, color = Color(0xFFCCCCCC))

        SiCard("General", cards.buildGeneralCard(d), onAction)

        val session = cards.buildSessionCard()
        SiCard("Activity / Stress Session", session, onAction) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { plugin.toggleActivitySession(SessionLabel.GOLF); tick++ },
                               modifier = Modifier.weight(1f)) { Text(session.labels["golf"] ?: "Golf") }
                OutlinedButton(onClick = { plugin.toggleActivitySession(SessionLabel.GYM); tick++ },
                               modifier = Modifier.weight(1f)) { Text(session.labels["gym"] ?: "Gym") }
            }
        }

        cards.buildReboundCard(d).takeIf { it.visible }?.let { SiCard("Low Recovery", it, onAction, accent = Color(0xFFFB8C00)) }
        SiCard("Learning", cards.buildLearningCard(d), onAction)
        SiCard("Meal Auto-Detection (UAM)", cards.buildUamCard(d), onAction)
        SiCard("Soft Target Fine-Tune", cards.buildStftCard(d), onAction)
        SiCard("Learned Insulin Profiles", cards.buildProfilesCard(d), onAction)
        if (d.circadianRawStatus.isNotBlank())
            SiCard("24h Circadian", SiCardContent(listOf(SiItem.Mono(d.circadianRawStatus, 0xFFDDDDDD.toInt(), false))), onAction)

        SiCard("Reset Learners", SiCardContent(emptyList()), onAction) {
            ResetRow("Aggressiveness score") { confirm = "Reset aggressiveness score to 1.0?" to { plugin.resetAggression() } }
            ResetRow("ISF circadian learning") { confirm = "Reset ISF circadian learning to 1.0? Basal and aggression learning kept." to { plugin.resetIsf() } }
            ResetRow("Basal learning") { confirm = "Reset basal + circadian basal learners to 1.0?" to { plugin.resetBasal() } }
            ResetRow("All circadian learning") { confirm = "Reset all circadian (ISF/basal/aggr) hourly learning?" to { plugin.resetCircadian() } }
            ResetRow("Insulin profiles") { confirm = "Reset all learned insulin profiles back to defaults?" to { plugin.resetProfiles() } }
            ResetRow("Meal/UAM learners") { confirm = "Reset learned per-meal ISF adjustments and UAM entry fractions back to your configured values?" to { plugin.resetModeLearners() } }
            ResetRow("Everything") { confirm = "Reset ALL learners? This cannot be undone." to { plugin.resetAllLearners() } }
        }
    }

    confirm?.let { (message, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { action(); confirm = null; tick++ }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
}

private const val REFRESH_MS = 5_000L

@Composable
private fun SiCard(
    title: String,
    content: SiCardContent,
    onAction: (SiAction) -> Unit,
    accent: Color = Color.Unspecified,
    footer: (@Composable () -> Unit)? = null
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = accent)
            Box(Modifier.height(8.dp))
            content.items.forEach { SiItemView(it, onAction) }
            footer?.invoke()
        }
    }
}

@Composable
private fun SiItemView(item: SiItem, onAction: (SiAction) -> Unit) {
    when (item) {
        is SiItem.Row -> {
            Text(item.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(item.color),
                 modifier = Modifier.padding(bottom = if (item.detail != null) 2.dp else 10.dp))
            item.detail?.let {
                Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFDDDDDD),
                     modifier = Modifier.padding(bottom = 10.dp))
            }
        }
        SiItem.Divider   -> HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Color(0xFF444444))
        is SiItem.Mono   -> Text(item.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                 fontWeight = if (item.bold) FontWeight.Bold else FontWeight.Normal,
                                 color = Color(item.color), modifier = Modifier.padding(bottom = 8.dp))
        is SiItem.Note   -> Text(item.text, fontSize = 11.sp, color = Color(0xFF999999), modifier = Modifier.padding(bottom = 8.dp))
        is SiItem.Header -> Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFCCCCCC),
                                 modifier = Modifier.padding(bottom = 8.dp))
        is SiItem.Action -> Text(item.text, fontSize = 12.sp, color = Color(item.color),
                                 modifier = Modifier.clickable { onAction(item.action) }.padding(vertical = 8.dp))
    }
}

@Composable
private fun ResetRow(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(top = 10.dp))
        OutlinedButton(onClick = onClick) { Text("Reset") }
    }
}
