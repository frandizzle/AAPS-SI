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
import androidx.hilt.navigation.compose.hiltViewModel
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

    if (showConfirmation) {
        OkCancelDialog(
            title = "Smart Meal",
            message = viewModel.buildSummary(),
            icon = Icons.Filled.Restaurant,
            iconTint = Color(0xFFFB8C00),
            onConfirm = {
                viewModel.confirmAndActivate(
                    onDeliveryError = onShowDeliveryError,
                    onDone = { showConfirmation = false; onNavigateBack() }
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
                Text("Activate ${viewModel.modeList[uiState.selectedModeIndex].label}")
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
                    }
                }
            }

            // ── Mode card ──────────────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth(),
                 colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Mode dropdown
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
                            modifier = Modifier.menuAnchor().fillMaxWidth()
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

                    // Duration slider + text field
                    var durationText by rememberSaveable { mutableStateOf(uiState.durationMins.toString()) }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Duration", style = MaterialTheme.typography.bodyLarge,
                             modifier = Modifier.weight(1f))
                        OutlinedTextField(
                            value = durationText,
                            onValueChange = { v ->
                                durationText = v
                                v.toIntOrNull()?.coerceIn(30, 480)?.let { viewModel.setDuration(it) }
                            },
                            suffix = { Text("min") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.width(110.dp)
                        )
                    }
                    Slider(
                        value = uiState.durationMins.toFloat(),
                        onValueChange = { v ->
                            viewModel.setDuration(v.toInt())
                            durationText = v.toInt().toString()
                        },
                        valueRange = 30f..480f,
                        steps = ((480 - 30) / 30) - 1,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // ISF slider + text field
                    val isfUnit = if (uiState.isMmol) "mmol/U" else "mg/dL/U"
                    val isfMax = if (uiState.isMmol) 20.0 else 360.0
                    val isfStep = if (uiState.isMmol) 0.1 else 1.0
                    var isfText by rememberSaveable { mutableStateOf(
                        if (uiState.isfValue > 0.0) "%.1f".format(uiState.isfValue) else "0"
                    ) }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ISF override", style = MaterialTheme.typography.bodyLarge,
                             modifier = Modifier.weight(1f))
                        OutlinedTextField(
                            value = isfText,
                            onValueChange = { v ->
                                isfText = v
                                v.toDoubleOrNull()?.coerceIn(0.0, isfMax)?.let { viewModel.setIsf(it) }
                            },
                            suffix = { Text(isfUnit) },
                            placeholder = { Text("0 = profile") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.width(130.dp)
                        )
                    }
                    Slider(
                        value = uiState.isfValue.toFloat(),
                        onValueChange = { v ->
                            val snapped = (v / isfStep).toLong() * isfStep
                            viewModel.setIsf(snapped)
                            isfText = if (snapped > 0.0) "%.1f".format(snapped) else "0"
                        },
                        valueRange = 0f..isfMax.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

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
                        var pb1Text by rememberSaveable { mutableStateOf("%.2f".format(uiState.preBolus1U)) }
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
                        var pb2Text by rememberSaveable { mutableStateOf("%.2f".format(uiState.preBolus2U)) }
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
        }
    }
}