package app.aaps.ui.compose.smartMealDialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberTooltipState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.dialogs.OkCancelDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartMealDialogScreen(
    viewModel: SmartMealDialogViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onShowDeliveryError: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showConfirmation by rememberSaveable { mutableStateOf(false) }
    // ice-step37: modeMenuExpanded removed — meal-mode dropdown deleted.
    // ice-step43: context for Toast'ing InfoMessage side-effects (e.g. when
    // the bolus calculator can't run because loop data is stale).
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.sideEffect.collect { effect ->
            when (effect) {
                is SmartMealDialogViewModel.SideEffect.DeliveryError ->
                    onShowDeliveryError(effect.message)
                is SmartMealDialogViewModel.SideEffect.InfoMessage    ->
                    Toast.makeText(context, effect.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Guard predictive back gesture — prevents "not in back stack" crash.
    // When confirmation is showing, dismiss it instead of popping nav.
    // Always intercept to use safePopBackStack via onNavigateBack.
    BackHandler(enabled = showConfirmation) {
        showConfirmation = false
    }

    if (showConfirmation) {
        OkCancelDialog(
            title = "Smart Meal",
            message = viewModel.buildSummary(),
            icon = Icons.Filled.Restaurant,
            iconTint = Color(0xFFFB8C00),
            onConfirm = {
                showConfirmation = false  // dismiss immediately on OK tap
                viewModel.confirmAndActivate(
                    onDeliveryError = onShowDeliveryError,
                    onDone = {}
                )
            },
            onDismiss = { showConfirmation = false }
        )
    }

    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Restaurant,
                            contentDescription = null,
                            tint = Color(0xFFFB8C00),
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Smart Meal")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    // ice-step27c: EDIT mode commits per-row via the Save buttons
                    // inside each MealLayerEditCard, so the bottom button here is
                    // a dismiss action — no confirmation modal, just close the
                    // dialog. ADD / REPLACE keep the existing confirm-then-fire flow.
                    if (uiState.dialogMode == DialogMode.EDIT) {
                        onNavigateBack()
                    } else {
                        showConfirmation = true
                    }
                },
                modifier = Modifier.fillMaxWidth().imePadding().padding(16.dp)
            ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                // ice-step27b/c: button label reflects what's about to happen.
                Text(when (uiState.dialogMode) {
                         DialogMode.ADD     -> "Add meal"
                         DialogMode.EDIT    -> "Done"
                         DialogMode.REPLACE -> "Replace all meals"
                     })
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Active mode banner
            if (uiState.activeModeName != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Active: ${uiState.activeModeName}",
                             color = MaterialTheme.colorScheme.onErrorContainer,
                             style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = { viewModel.cancelMode() },
                                          modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel Active Mode")
                        }
                        if (uiState.pb2Pending) {
                            Spacer(Modifier.height(4.dp))
                            Text(uiState.pb2StatusText,
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onErrorContainer)
                            OutlinedButton(onClick = { viewModel.cancelPb2() },
                                           modifier = Modifier.fillMaxWidth()) {
                                Text("Cancel Pre-bolus 2")
                            }
                        }
                        if (uiState.pb3Pending) {
                            Spacer(Modifier.height(4.dp))
                            Text(uiState.pb3StatusText,
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onErrorContainer)
                            OutlinedButton(onClick = { viewModel.cancelPb3() },
                                           modifier = Modifier.fillMaxWidth()) {
                                Text("Cancel Pre-bolus 3")
                            }
                        }
                    }
                }
            }

            // ── Mode picker (ice-step27b) ──────────────────────────────────
            // Switches the dialog between three flows:
            //   ADD     → announce as a new layer on top of any active meals
            //   EDIT    → update the active meal's macros in place (preserves timer)
            //   REPLACE → cancel all active meals and start fresh
            Card(modifier = Modifier.fillMaxWidth(),
                 colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("What do you want to do?", style = MaterialTheme.typography.titleMedium)
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val modes = listOf(
                            Triple("Add",     DialogMode.ADD,     "New meal layer"),
                            Triple("Edit",    DialogMode.EDIT,    "Update active meal"),
                            Triple("Replace", DialogMode.REPLACE, "Cancel & start over")
                        )
                        modes.forEach { (label, mode, _) ->
                            val isSelected = uiState.dialogMode == mode
                            if (isSelected) {
                                FilledTonalButton(
                                    onClick = { viewModel.setDialogMode(mode) },
                                    modifier = Modifier.weight(1f)
                                ) { Text(label) }
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.setDialogMode(mode) },
                                    modifier = Modifier.weight(1f)
                                ) { Text(label) }
                            }
                        }
                    }
                    // Per-mode help text — colour-coded to match severity.
                    when (uiState.dialogMode) {
                        DialogMode.ADD -> Text(
                            "Adds these macros as a new meal layer with its own absorption timer. " +
                                "If a meal is already active, this one starts alongside it (separate t=0).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        DialogMode.EDIT -> Text(
                            "Edit each active meal's macros below. Per-row Save preserves the meal's " +
                                "absorption timer. Tap × on a row to cancel a single meal.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        DialogMode.REPLACE -> Text(
                            "⚠ Cancels ALL active meals and restarts from these macros at t=0. " +
                                "Use when the original entry was wrong or you want a clean slate.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // ── EDIT-mode per-layer editor (ice-step27c) ───────────────────
            // When the user selects EDIT, replace the single macros/GI form with
            // a card per active layer. Each card has editable inputs pre-filled
            // from the layer's current values, a Save Changes button that calls
            // editMealLayer(layerId, …) (preserves the layer's absorption
            // timer), and a × delete button. Empty state when no meals exist.
            if (uiState.dialogMode == DialogMode.EDIT) {
                if (uiState.activeMealLayers.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth(),
                         colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("No active meals to edit",
                                 style = MaterialTheme.typography.titleMedium,
                                 color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(4.dp))
                            Text("Switch to Add to announce a new meal, or use the home screen to view past meals.",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    uiState.activeMealLayers.forEach { layer ->
                        MealLayerEditCard(
                            layer    = layer,
                            onSave   = { c, p, f, gi -> viewModel.saveLayerEdits(layer.layerId, c, p, f, gi) },
                            onCancel = { viewModel.cancelLayer(layer.layerId) }
                        )
                    }
                    // Cancel-all only useful when 2+ layers — single-layer case
                    // is already covered by the per-row × button.
                    if (uiState.activeMealLayers.size > 1) {
                        Button(
                            onClick = { viewModel.cancelAllMealLayers() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor   = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) { Text("Cancel all meals") }
                    }
                }
            }

            // ── Mode card ──────────────────────────────────────────────────
            // Hidden in EDIT mode (replaced by the per-layer cards above).
            // ice-step37: meal-mode dropdown removed. All announced meals route
            // through MealMode.LUNCH internally — learner gating now depends on
            // COB/P/F presence rather than meal-mode flavour.
            if (uiState.dialogMode != DialogMode.EDIT) {
                Card(modifier = Modifier.fillMaxWidth(),
                     colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                        // ── Macros: carbs / protein / fat ──────────────────────────
                        var carbsText by rememberSaveable {
                            mutableStateOf(if (uiState.carbsG > 0.0) "%.0f".format(uiState.carbsG) else "")
                        }
                        androidx.compose.runtime.LaunchedEffect(uiState.carbsG) {
                            carbsText = if (uiState.carbsG > 0.0) "%.0f".format(uiState.carbsG) else ""
                        }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Carbs", style = MaterialTheme.typography.bodyLarge,
                                 modifier = Modifier.weight(1f))
                            OutlinedTextField(
                                value = carbsText,
                                onValueChange = { v ->
                                    carbsText = v
                                    viewModel.setCarbsG(v.toDoubleOrNull()?.coerceIn(0.0, 300.0) ?: 0.0)
                                },
                                suffix = { Text("g") },
                                placeholder = { Text("0") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.width(110.dp)
                            )
                        }

                        var proteinText by rememberSaveable {
                            mutableStateOf(if (uiState.proteinG > 0.0) "%.0f".format(uiState.proteinG) else "")
                        }
                        androidx.compose.runtime.LaunchedEffect(uiState.proteinG) {
                            proteinText = if (uiState.proteinG > 0.0) "%.0f".format(uiState.proteinG) else ""
                        }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Protein", style = MaterialTheme.typography.bodyLarge,
                                 modifier = Modifier.weight(1f))
                            OutlinedTextField(
                                value = proteinText,
                                onValueChange = { v ->
                                    proteinText = v
                                    viewModel.setProteinG(v.toDoubleOrNull()?.coerceIn(0.0, 300.0) ?: 0.0)
                                },
                                suffix = { Text("g") },
                                placeholder = { Text("0") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.width(110.dp)
                            )
                        }

                        var fatText by rememberSaveable {
                            mutableStateOf(if (uiState.fatG > 0.0) "%.0f".format(uiState.fatG) else "")
                        }
                        androidx.compose.runtime.LaunchedEffect(uiState.fatG) {
                            fatText = if (uiState.fatG > 0.0) "%.0f".format(uiState.fatG) else ""
                        }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Fat", style = MaterialTheme.typography.bodyLarge,
                                 modifier = Modifier.weight(1f))
                            OutlinedTextField(
                                value = fatText,
                                onValueChange = { v ->
                                    fatText = v
                                    viewModel.setFatG(v.toDoubleOrNull()?.coerceIn(0.0, 300.0) ?: 0.0)
                                },
                                suffix = { Text("g") },
                                placeholder = { Text("0") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.width(110.dp)
                            )
                        }

                        // ── GI bucket: High / Medium / Low ─────────────────────────
                        // Drives the expected ICE absorption window. High = juice/candy (fast peak),
                        // Medium = bread/rice/pasta (default), Low = pizza/fatty/large meals (long tail).
                        Spacer(Modifier.height(4.dp))
                        Text("Glycemic Index", style = MaterialTheme.typography.bodyLarge)
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("High", "Medium", "Low").forEachIndexed { idx, label ->
                                val isSelected = uiState.giBucketIndex == idx
                                if (isSelected) {
                                    FilledTonalButton(
                                        onClick = { viewModel.setGiBucketIndex(idx) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text(label) }
                                } else {
                                    OutlinedButton(
                                        onClick = { viewModel.setGiBucketIndex(idx) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text(label) }
                                }
                            }
                        }
                        Text(
                            text = when (uiState.giBucketIndex) {
                                0    -> "Fast-acting: juice, candy, soft drinks · 1h window"
                                2    -> "Slow / bimodal: pizza, fatty meals, large portions · 6h window"
                                else -> "Standard: bread, rice, pasta, most cooked meals · 4h window"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } // /if (dialogMode != EDIT) — close Mode-card gate (ice-step27c)

            // ── Pre-bolus cards (hidden in EDIT mode — ice-step27b) ─────────
            // Edit is a pure model update — it doesn't deliver insulin and doesn't
            // touch the meal-mode override or PB2/PB3 schedules. Hiding the cards
            // here keeps the EDIT flow focused on macros + GI only.
            if (uiState.dialogMode != DialogMode.EDIT) {

                // ── Pre-bolus 1 card ───────────────────────────────────────────
                Card(modifier = Modifier.fillMaxWidth(),
                     colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Pre-bolus 1", style = MaterialTheme.typography.titleMedium)
                            Switch(checked = uiState.preBolus1Enabled,
                                   onCheckedChange = { viewModel.setPreBolus1Enabled(it) })
                        }
                        if (uiState.preBolus1Enabled) {
                            var pb1Text by rememberSaveable { mutableStateOf(if (uiState.preBolus1U > 0.0) "%.2f".format(uiState.preBolus1U) else "") }
                            androidx.compose.runtime.LaunchedEffect(uiState.preBolus1U) {
                                pb1Text = if (uiState.preBolus1U > 0.0) "%.2f".format(uiState.preBolus1U) else ""
                            }
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Amount", style = MaterialTheme.typography.bodyLarge,
                                     modifier = Modifier.weight(1f))
                                OutlinedTextField(
                                    value = pb1Text,
                                    onValueChange = { v ->
                                        pb1Text = v
                                        v.toDoubleOrNull()?.coerceIn(0.0, uiState.maxPreBolus)
                                            ?.let { viewModel.setPreBolus1U(it) }
                                    },
                                    suffix = { Text("U") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier.width(100.dp)
                                )
                                // ice-step43: Calc button — computes a recommended PB1
                                // from current IOB, BG, trend, target and the entered carbs.
                                // Long-press shows the math breakdown.
                                PreBolusCalcButton(
                                    tooltipText = uiState.pb1Breakdown?.let {
                                        formatPb1Breakdown(it, uiState.maxPreBolus)
                                    },
                                    onClick = { viewModel.calculatePreBolus1() }
                                )
                            }
                            Slider(
                                value = uiState.preBolus1U.toFloat(),
                                onValueChange = { v ->
                                    val snapped = (v / uiState.bolusStep).toLong() * uiState.bolusStep
                                    viewModel.setPreBolus1U(snapped)
                                    pb1Text = "%.2f".format(snapped)
                                },
                                valueRange = 0f..uiState.maxPreBolus.toFloat(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(0.5, 1.0, 2.0).forEach { inc ->
                                    FilledTonalButton(
                                        onClick = { viewModel.setPreBolus1U(
                                            (uiState.preBolus1U + inc).coerceAtMost(uiState.maxPreBolus)) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("+${inc}U", fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }

                // ── Pre-bolus 2 card ───────────────────────────────────────────
                Card(modifier = Modifier.fillMaxWidth(),
                     colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("Pre-bolus 2 (scheduled)", style = MaterialTheme.typography.titleMedium)
                                Text("Auto-fires after delay if BG > target",
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = uiState.preBolus2Enabled,
                                   onCheckedChange = { viewModel.setPreBolus2Enabled(it) })
                        }
                        if (uiState.preBolus2Enabled) {
                            HorizontalDivider()
                            var pb2Text by rememberSaveable { mutableStateOf(if (uiState.preBolus2U > 0.0) "%.2f".format(uiState.preBolus2U) else "") }
                            androidx.compose.runtime.LaunchedEffect(uiState.preBolus2U) {
                                pb2Text = if (uiState.preBolus2U > 0.0) "%.2f".format(uiState.preBolus2U) else ""
                            }
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Amount", style = MaterialTheme.typography.bodyLarge,
                                     modifier = Modifier.weight(1f))
                                OutlinedTextField(
                                    value = pb2Text,
                                    onValueChange = { v ->
                                        pb2Text = v
                                        v.toDoubleOrNull()?.coerceIn(0.0, uiState.maxPreBolus)
                                            ?.let { viewModel.setPreBolus2U(it) }
                                    },
                                    suffix = { Text("U") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier.width(100.dp)
                                )
                                // ice-step43: Calc button — computes PB2 from
                                // protein/fat glucose-equivalent × 0.6 split.
                                PreBolusCalcButton(
                                    tooltipText = uiState.pb2Breakdown?.let {
                                        formatPbPfBreakdown(it, uiState.maxPreBolus, "PB2")
                                    },
                                    onClick = { viewModel.calculatePreBolus2() }
                                )
                            }
                            Slider(
                                value = uiState.preBolus2U.toFloat(),
                                onValueChange = { v ->
                                    val snapped = (v / uiState.bolusStep).toLong() * uiState.bolusStep
                                    viewModel.setPreBolus2U(snapped)
                                    pb2Text = "%.2f".format(snapped)
                                },
                                valueRange = 0f..uiState.maxPreBolus.toFloat(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            var delayText by rememberSaveable { mutableStateOf(uiState.preBolus2DelayMins.toString()) }
                            androidx.compose.runtime.LaunchedEffect(uiState.preBolus2DelayMins) {
                                delayText = uiState.preBolus2DelayMins.toString()
                            }
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Delay", style = MaterialTheme.typography.bodyLarge,
                                     modifier = Modifier.weight(1f))
                                OutlinedTextField(
                                    value = delayText,
                                    onValueChange = { v ->
                                        delayText = v
                                        v.toIntOrNull()?.coerceIn(15, 120)?.let { viewModel.setPreBolus2DelayMins(it) }
                                    },
                                    suffix = { Text("min") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.width(100.dp)
                                )
                            }
                            Slider(
                                value = uiState.preBolus2DelayMins.toFloat(),
                                onValueChange = { v ->
                                    viewModel.setPreBolus2DelayMins(v.toInt())
                                    delayText = v.toInt().toString()
                                },
                                valueRange = 15f..120f,
                                steps = ((120 - 15) / 15) - 1,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "Pre-bolus 2 fires automatically when all safety gates pass:\n" +
                                    "• BG above profile target (not falling)\n" +
                                    "• Delta ≥ -0.11 mmol/min (not dropping fast)\n" +
                                    "• 15min avg delta not in sustained fall\n" +
                                    "• IOB below 75% of max IOB\n" +
                                    "Gates are checked every 5min until all pass or mode expires.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // ── Pre-bolus 3 card ───────────────────────────────────────────
                Card(modifier = Modifier.fillMaxWidth(),
                     colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("Pre-bolus 3 (late-meal)", style = MaterialTheme.typography.titleMedium)
                                Text("Auto-fires delay-minutes AFTER pre-bolus 2 fires. Cancels if PB2 cancels.",
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = uiState.preBolus3Enabled,
                                   onCheckedChange = { viewModel.setPreBolus3Enabled(it) },
                                   enabled = uiState.preBolus2Enabled)  // PB3 requires PB2
                        }
                        if (uiState.preBolus3Enabled && uiState.preBolus2Enabled) {
                            HorizontalDivider()
                            var pb3Text by rememberSaveable { mutableStateOf(if (uiState.preBolus3U > 0.0) "%.2f".format(uiState.preBolus3U) else "") }
                            androidx.compose.runtime.LaunchedEffect(uiState.preBolus3U) {
                                pb3Text = if (uiState.preBolus3U > 0.0) "%.2f".format(uiState.preBolus3U) else ""
                            }
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Amount", style = MaterialTheme.typography.bodyLarge,
                                     modifier = Modifier.weight(1f))
                                OutlinedTextField(
                                    value = pb3Text,
                                    onValueChange = { v ->
                                        pb3Text = v
                                        v.toDoubleOrNull()?.coerceIn(0.0, uiState.maxPreBolus)
                                            ?.let { viewModel.setPreBolus3U(it) }
                                    },
                                    suffix = { Text("U") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier.width(100.dp)
                                )
                                // ice-step43: Calc button — computes PB3 from
                                // protein/fat glucose-equivalent × 0.4 split.
                                PreBolusCalcButton(
                                    tooltipText = uiState.pb3Breakdown?.let {
                                        formatPbPfBreakdown(it, uiState.maxPreBolus, "PB3")
                                    },
                                    onClick = { viewModel.calculatePreBolus3() }
                                )
                            }
                            Slider(
                                value = uiState.preBolus3U.toFloat(),
                                onValueChange = { v ->
                                    val snapped = (v / uiState.bolusStep).toLong() * uiState.bolusStep
                                    viewModel.setPreBolus3U(snapped)
                                    pb3Text = "%.2f".format(snapped)
                                },
                                valueRange = 0f..uiState.maxPreBolus.toFloat(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            var pb3DelayText by rememberSaveable { mutableStateOf(uiState.preBolus3DelayMins.toString()) }
                            androidx.compose.runtime.LaunchedEffect(uiState.preBolus3DelayMins) {
                                pb3DelayText = uiState.preBolus3DelayMins.toString()
                            }
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Delay after PB2", style = MaterialTheme.typography.bodyLarge,
                                     modifier = Modifier.weight(1f))
                                OutlinedTextField(
                                    value = pb3DelayText,
                                    onValueChange = { v ->
                                        pb3DelayText = v
                                        v.toIntOrNull()?.coerceIn(15, 120)?.let { viewModel.setPreBolus3DelayMins(it) }
                                    },
                                    suffix = { Text("min") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.width(100.dp)
                                )
                            }
                            Slider(
                                value = uiState.preBolus3DelayMins.toFloat(),
                                onValueChange = { v ->
                                    viewModel.setPreBolus3DelayMins(v.toInt())
                                    pb3DelayText = v.toInt().toString()
                                },
                                valueRange = 15f..120f,
                                steps = ((120 - 15) / 15) - 1,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "Pre-bolus 3 timer starts when PB2 delivers successfully. Same safety gates as PB2:\n" +
                                    "• BG above profile target (not falling)\n" +
                                    "• Delta ≥ -0.11 mmol/min (not dropping fast)\n" +
                                    "• 15min avg delta not in sustained fall\n" +
                                    "• IOB below 75% of max IOB\n" +
                                    "Cancels automatically if PB2 is cancelled, fails, or the meal ends.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (uiState.preBolus3Enabled && !uiState.preBolus2Enabled) {
                            Text(
                                "Pre-bolus 3 requires pre-bolus 2 to be enabled — its timer starts when PB2 fires.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            } // /if (dialogMode != EDIT) — close pre-bolus gate
        }
    }
}

// ── Per-layer edit card (ice-step27c) ────────────────────────────────────────
// Renders one card per active announced meal in EDIT mode. Mirrors the
// home-screen ActiveMealCard row but with editable inputs:
//
//   - Header: "Medium GI · 4m in · 296m remaining" + × delete button
//   - Carbs / Protein / Fat numeric inputs (pre-filled, in grams)
//   - High / Medium / Low GI selector (pre-set from the layer's bucket)
//   - "Save changes" button → calls editMealLayer(layerId, ...) on the plugin,
//     which preserves the layer's announceTimestampMs (absorption timer
//     continues uninterrupted).
//
// Local draft state for each input uses rememberSaveable keyed on the layer
// ID. A LaunchedEffect resyncs the draft when the upstream value changes
// (i.e. after the loop pushes a new snapshot reflecting our save) — this is
// the same pattern the old single-layer ActiveMealCard used, and it does
// NOT stomp on the user's typing as long as the upstream values are stable.
// Within a single layer, ageMin changes each cycle (the new snapshots aren't
// value-equal), but the macros / GI fields stay constant between mutations,
// so the LaunchedEffect on each field's value won't fire just because age
// ticked up by one minute.

@Composable
private fun MealLayerEditCard(
    layer: SmartInsulinOverview.MealLayerInfo,
    onSave: (carbsG: Double, proteinG: Double, fatG: Double, giBucketName: String) -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

            // Header row: GI + age/remaining + × delete
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${layer.giLabel} · ${layer.ageMin}m in · ${layer.remainingMin}m remaining",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "(${layer.totalDurationMin}m total window)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Cancel this meal",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            // ── Carbs field ────────────────────────────────────────────────
            var carbsText by rememberSaveable(layer.layerId) {
                mutableStateOf("%.0f".format(layer.totalCarbsG))
            }
            LaunchedEffect(layer.totalCarbsG) {
                carbsText = "%.0f".format(layer.totalCarbsG)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Carbs", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    value = carbsText,
                    onValueChange = { carbsText = it },
                    suffix = { Text("g") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(110.dp)
                )
            }

            // ── Protein field ──────────────────────────────────────────────
            var proteinText by rememberSaveable(layer.layerId) {
                mutableStateOf("%.0f".format(layer.totalProteinG))
            }
            LaunchedEffect(layer.totalProteinG) {
                proteinText = "%.0f".format(layer.totalProteinG)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Protein", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    value = proteinText,
                    onValueChange = { proteinText = it },
                    suffix = { Text("g") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(110.dp)
                )
            }

            // ── Fat field ──────────────────────────────────────────────────
            var fatText by rememberSaveable(layer.layerId) {
                mutableStateOf("%.0f".format(layer.totalFatG))
            }
            LaunchedEffect(layer.totalFatG) {
                fatText = "%.0f".format(layer.totalFatG)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Fat", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    value = fatText,
                    onValueChange = { fatText = it },
                    suffix = { Text("g") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(110.dp)
                )
            }

            // ── GI selector ───────────────────────────────────────────────
            val initialGiIndex = when (layer.giBucketName) {
                "FAST" -> 0
                "SLOW" -> 2
                else   -> 1
            }
            var giIndex by rememberSaveable(layer.layerId) { mutableStateOf(initialGiIndex) }
            LaunchedEffect(layer.giBucketName) {
                giIndex = when (layer.giBucketName) {
                    "FAST" -> 0
                    "SLOW" -> 2
                    else   -> 1
                }
            }
            Text("Glycemic Index", style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("High", "Medium", "Low").forEachIndexed { idx, label ->
                    if (giIndex == idx) {
                        FilledTonalButton(
                            onClick = { giIndex = idx },
                            modifier = Modifier.weight(1f)
                        ) { Text(label) }
                    } else {
                        OutlinedButton(
                            onClick = { giIndex = idx },
                            modifier = Modifier.weight(1f)
                        ) { Text(label) }
                    }
                }
            }

            // ── Save button ───────────────────────────────────────────────
            Button(
                onClick = {
                    val giName = when (giIndex) { 0 -> "FAST"; 2 -> "SLOW"; else -> "MEDIUM" }
                    onSave(
                        carbsText.toDoubleOrNull()?.coerceIn(0.0, 300.0) ?: layer.totalCarbsG,
                        proteinText.toDoubleOrNull()?.coerceIn(0.0, 300.0) ?: layer.totalProteinG,
                        fatText.toDoubleOrNull()?.coerceIn(0.0, 300.0) ?: layer.totalFatG,
                        giName
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save changes") }
        }
    }
}

// ── ice-step43: bolus calculator helpers ─────────────────────────────────────
// The [Calc] button next to each pre-bolus amount field calls into the VM,
// which asks the plugin for a breakdown of the calculation. The breakdown is
// rendered as a long-press tooltip via Material 3's TooltipBox so the user
// can see the math behind the number that just landed in the entry box.

/**
 * Compact button + long-press tooltip pair for the three Calc actions.
 *
 * Before the user has tapped Calc, [tooltipText] is null and the tooltip
 * just nudges them with instructions. After a successful calc the text is a
 * multi-line breakdown of the math.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreBolusCalcButton(
    tooltipText: String?,
    onClick: () -> Unit
) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(tooltipText ?: "Tap Calc to compute from IOB / BG / trend / macros.\nLong-press here after to see the math.")
            }
        },
        state = tooltipState
    ) {
        FilledTonalButton(
            onClick = onClick,
            modifier = Modifier.width(76.dp)
        ) { Text("Calc", fontSize = 12.sp) }
    }
}

/**
 * Format a PB1 breakdown for the tooltip. Numbers in U, two decimal places.
 * Trend nudge sign is rendered explicitly so the user can see at a glance
 * whether the trend pulled the dose up or down.
 *
 * ice-step44: when [SmartInsulinOverview.PreBolus1Breakdown.safetyRefused]
 * is true, leads with the safety reason rather than the math. The math
 * still follows so the user can see what would have been recommended
 * absent the safety floor.
 */
private fun formatPb1Breakdown(
    b: SmartInsulinOverview.PreBolus1Breakdown,
    maxPreBolus: Double
): String = buildString {
    if (b.safetyRefused) {
        append("PB1 DECLINED — safety floor\n")
        append("Projected low: %.1f mmol\n".format(b.projectedLowMgdl / 18.0))
        append("Safety floor: %.1f mmol (lowGuard + 0.5)\n".format(b.safetyFloorMgdl / 18.0))
        append("Reason: IOB + trend would push BG below safe range over the next 6h.\n")
        append("Manual entry still works if you have meal-timing context the\n")
        append("algorithm doesn't (e.g. fast carbs starting absorption now).\n")
        append("\nWhat the math would have said:\n")
    }
    val upfrontU = b.carbBolusU * b.carbFraction
    append("Meal cover: %.2fU\n".format(b.carbBolusU))
    append("× %.2f upfront: %.2fU\n".format(b.carbFraction, upfrontU))
    if (b.correctionU > 0.0) {
        append("+ correction: %.2fU\n".format(b.correctionU))
    }
    val trendSign = if (b.trendNudgeU >= 0.0) "+" else "−"
    append("$trendSign trend (15min): %.2fU\n".format(kotlin.math.abs(b.trendNudgeU)))
    append("− IOB: %.2fU\n".format(b.iobU))
    append("= %.2fU".format(b.resultU))
    if (!b.safetyRefused && b.resultU > maxPreBolus) {
        append("  (capped at %.2fU max)".format(maxPreBolus))
    }
    if (!b.safetyRefused) {
        // Safety floor still shown on success so the user knows the check
        // happened and what value the projection landed on.
        append("\n\nSafety floor: %.1f mmol  •  projected low %.1f mmol  ✓".format(
            b.safetyFloorMgdl / 18.0, b.projectedLowMgdl / 18.0
        ))
    }
}

/**
 * Format a PB2/PB3 breakdown. Shows the glucose-equivalent conversion for
 * protein and fat, the combined P/F bolus, and the split fraction applied.
 */
private fun formatPbPfBreakdown(
    b: SmartInsulinOverview.PreBolusPfBreakdown,
    maxPreBolus: Double,
    label: String
): String = buildString {
    append("Protein → %.1fgE\n".format(b.proteinGEg))
    append("Fat → %.1fgE\n".format(b.fatGEg))
    append("Total P/F bolus: %.2fU\n".format(b.totalPfBolusU))
    append("× %.1f split: %.2fU $label".format(b.pfSplitFraction, b.resultU))
    if (b.resultU > maxPreBolus) {
        append("\n(capped at %.2fU max)".format(maxPreBolus))
    }
}