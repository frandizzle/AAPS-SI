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
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Button
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    var modeMenuExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.sideEffect.collect { effect ->
            when (effect) {
                is SmartMealDialogViewModel.SideEffect.DeliveryError ->
                    onShowDeliveryError(effect.message)

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
                onClick = { showConfirmation = true },
                modifier = Modifier.fillMaxWidth().imePadding().padding(16.dp)
            ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                // ice-step27b: button label reflects what's about to happen.
                Text(when (uiState.dialogMode) {
                         DialogMode.ADD     -> "Add meal"
                         DialogMode.EDIT    -> "Save edits"
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
                            "Replaces the active meal's macros while keeping its absorption timer. " +
                                "Works only when exactly one meal is active — if you have several, " +
                                "delete the extras from the home screen first (or use Replace).",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFB8C00)   // amber — caveat
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

            // ── Mode card ──────────────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth(),
                 colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Mode dropdown — hidden in EDIT mode (edit doesn't re-activate
                    // the meal-mode override, so the dropdown is irrelevant there).
                    if (uiState.dialogMode != DialogMode.EDIT) {
                        ExposedDropdownMenuBox(
                            expanded = modeMenuExpanded,
                            onExpandedChange = { modeMenuExpanded = it }
                        ) {
                            TextField(
                                value = viewModel.modeList[uiState.selectedModeIndex].label,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Meal Mode") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeMenuExpanded) },
                                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = modeMenuExpanded,
                                onDismissRequest = { modeMenuExpanded = false }
                            ) {
                                viewModel.modeList.forEachIndexed { i, mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.label) },
                                        onClick = { viewModel.setModeIndex(i); modeMenuExpanded = false }
                                    )
                                }
                            }
                        }
                    } // /if (dialogMode != EDIT) — close mode-dropdown gate

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
                            0    -> "Fast-acting: juice, candy, soft drinks · 2h window"
                            2    -> "Slow / bimodal: pizza, fatty meals, large portions · 6h window"
                            else -> "Standard: bread, rice, pasta, most cooked meals · 4h window"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

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