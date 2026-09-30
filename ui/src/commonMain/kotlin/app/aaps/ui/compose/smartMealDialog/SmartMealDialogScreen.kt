package app.aaps.ui.compose.smartMealDialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.ui.ConfirmationLine
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.bottomBarSafeArea
import app.aaps.core.ui.compose.clearFocusOnTap
import app.aaps.core.ui.compose.consumeOverscroll
import app.aaps.core.ui.compose.dialogs.ElementConfirmationDialog
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.navigation.label
import app.aaps.core.ui.compose.stringResource
import app.aaps.core.ui.compose.stringResourceOrNull
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.components.DialogStatusBar
import app.aaps.ui.compose.overview.chips.CobUiState
import app.aaps.ui.compose.overview.chips.IobUiState
import app.aaps.ui.compose.overview.graphs.BgInfoUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

@Composable
fun SmartMealDialogScreen(
    viewModel: SmartMealDialogViewModel = metroViewModel(),
    bgInfoState: StateFlow<BgInfoUiState>,
    iobUiState: StateFlow<IobUiState>,
    cobUiState: StateFlow<CobUiState>,
    onNavigateBack: () -> Unit,
    onShowDeliveryError: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val bgInfo by bgInfoState.collectAsStateWithLifecycle()
    val iob by iobUiState.collectAsStateWithLifecycle()
    val cob by cobUiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    var confirmation by remember { mutableStateOf<Pair<Long?, List<ConfirmationLine>>?>(null) }
    var cancelPrompt by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    LaunchedEffect(Unit) {
        viewModel.sideEffect.collect { effect ->
            when (effect) {
                is SmartMealDialogViewModel.SideEffect.ShowDeliveryError -> onShowDeliveryError(effect.comment)
                is SmartMealDialogViewModel.SideEffect.ShowConfirmation  -> confirmation = effect.bolusId to effect.lines
            }
        }
    }
    // The pre-bolus 2/3 countdowns live in the manager; keep the cancel buttons current.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000L); viewModel.refreshActive()
        }
    }

    confirmation?.let { (bolusId, lines) ->
        ElementConfirmationDialog(
            elementType = ElementType.SMART_MEAL,
            lines = lines,
            onConfirm = {
                viewModel.commit(bolusId)
                confirmation = null
                onNavigateBack()
            },
            onDismiss = { confirmation = null }
        )
    }
    cancelPrompt?.let { (message, action) ->
        AlertDialog(
            onDismissRequest = { cancelPrompt = null },
            title = { Text(stringResource(CoreUiStrings.smart_meal)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { action(); cancelPrompt = null; onNavigateBack() }) { Text(stringResource(CoreUiStrings.ok)) }
            },
            dismissButton = { TextButton(onClick = { cancelPrompt = null }) { Text(stringResource(CoreUiStrings.cancel)) } }
        )
    }

    val bolusFormat = viewModel.decimalFormatter.pumpSupportedBolusFormat(state.bolusStep)
    val isfMax = if (state.isMmol) 20.0 else 360.0
    val isfStep = if (state.isMmol) 0.05 else 1.0
    val isfDecimals = if (state.isMmol) 2 else 0

    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = { Text(stringResourceOrNull(ElementType.SMART_MEAL.label()) ?: "") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(CoreUiStrings.close))
                    }
                }
            )
        },
        bottomBar = {
            Button(
                onClick = { focusManager.clearFocus(); viewModel.prepareAndConfirm() },
                modifier = Modifier
                    .fillMaxWidth()
                    .bottomBarSafeArea()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(CoreUiStrings.ok))
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeOverscroll()
                .verticalScroll(rememberScrollState())
                .clearFocusOnTap(focusManager)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DialogStatusBar(bgInfo = bgInfo, iob = iob, cob = cob)

            // --- What is running now ---
            state.activeMode?.let { active ->
                SectionCard {
                    if (active.isUam)
                        Text(
                            "⚡ " + stringResource(UiStrings.si_uam_active_banner, active.label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    val cancelLabel =
                        if (active.isUam) stringResource(UiStrings.si_cancel_uam, active.label)
                        else stringResource(UiStrings.si_cancel_mode, active.label)
                    val cancelMessage =
                        if (active.isUam) stringResource(UiStrings.si_cancel_uam_confirm, active.label)
                        else stringResource(UiStrings.si_cancel_mode_confirm, active.label)
                    OutlinedButton(onClick = { cancelPrompt = cancelMessage to viewModel::cancelMode }, modifier = Modifier.fillMaxWidth()) {
                        Text(cancelLabel)
                    }
                    if (state.pb2Pending) {
                        val msg = stringResource(UiStrings.si_cancel_pb2_confirm)
                        OutlinedButton(onClick = { cancelPrompt = msg to viewModel::cancelPb2 }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(UiStrings.si_cancel_pb, state.pb2Status))
                        }
                    }
                    if (state.pb3Pending) {
                        val msg = stringResource(UiStrings.si_cancel_pb3_confirm)
                        OutlinedButton(onClick = { cancelPrompt = msg to viewModel::cancelPb3 }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(UiStrings.si_cancel_pb, state.pb3Status))
                        }
                    }
                }
            }

            // --- Mode, duration, ISF ---
            SectionCard {
                Text(stringResource(UiStrings.si_mode_label), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    viewModel.modes.take(3).forEach { m ->
                        FilterChip(selected = state.mode == m, onClick = { viewModel.selectMode(m) }, label = { Text(m.label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    viewModel.modes.drop(3).forEach { m ->
                        FilterChip(selected = state.mode == m, onClick = { viewModel.selectMode(m) }, label = { Text(m.label) })
                    }
                }
                NumberInputRow(
                    labelRef = UiStrings.si_duration_label,
                    value = state.durationMinutes.toDouble(),
                    onValueChange = viewModel::updateDuration,
                    valueRange = 30.0..480.0,
                    step = 30.0,
                    unitLabel = CoreUiStrings.units_min
                )
                NumberInputRow(
                    labelRef = UiStrings.si_isf_label,
                    value = state.isf,
                    onValueChange = viewModel::updateIsf,
                    valueRange = 0.0..isfMax,
                    step = isfStep,
                    decimalPlaces = isfDecimals
                )
                Text(stringResource(UiStrings.si_isf_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // --- DURA ---
            SectionCard {
                SwitchRow(stringResource(UiStrings.si_dura_switch), state.duraEnabled, viewModel::updateDuraEnabled)
                if (state.duraEnabled) {
                    NumberInputRow(
                        labelRef = UiStrings.si_dura_floor,
                        value = state.duraFloor,
                        onValueChange = viewModel::updateDuraFloor,
                        valueRange = 0.0..isfMax,
                        step = isfStep,
                        decimalPlaces = isfDecimals
                    )
                    NumberInputRow(
                        labelRef = UiStrings.si_dura_strength,
                        value = state.duraStrength,
                        onValueChange = viewModel::updateDuraStrength,
                        valueRange = 0.0..5.0,
                        step = 0.1,
                        decimalPlaces = 1
                    )
                }
            }

            // --- Pre-bolus 1 ---
            SectionCard {
                SwitchRow(stringResource(UiStrings.si_prebolus1_switch), state.pb1Enabled, viewModel::updatePb1Enabled)
                if (state.pb1Enabled) {
                    NumberInputRow(
                        labelRef = UiStrings.si_prebolus_amount,
                        value = state.pb1U,
                        onValueChange = viewModel::updatePb1,
                        valueRange = 0.0..state.maxPreBolus,
                        step = state.bolusStep,
                        valueFormat = bolusFormat,
                        unitLabel = CoreUiStrings.insulin_unit_shortname
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        listOf(1.0, 2.0, 3.0).forEach { inc ->
                            FilledTonalButton(onClick = { focusManager.clearFocus(); viewModel.addPb1(inc) }) {
                                Text("+" + viewModel.decimalFormatter.toPumpSupportedBolus(inc, state.bolusStep))
                            }
                        }
                    }
                }
            }

            // --- Pre-bolus 2 ---
            SectionCard {
                SwitchRow(stringResource(UiStrings.si_prebolus2_switch), state.pb2Enabled, viewModel::updatePb2Enabled)
                if (state.pb2Enabled) {
                    NumberInputRow(
                        labelRef = UiStrings.si_prebolus2_delay,
                        value = state.pb2DelayMinutes.toDouble(),
                        onValueChange = viewModel::updatePb2Delay,
                        valueRange = 5.0..120.0,
                        step = 5.0,
                        unitLabel = CoreUiStrings.units_min
                    )
                    NumberInputRow(
                        labelRef = UiStrings.si_prebolus_amount,
                        value = state.pb2U,
                        onValueChange = viewModel::updatePb2,
                        valueRange = 0.0..state.maxPreBolus,
                        step = state.bolusStep,
                        valueFormat = bolusFormat,
                        unitLabel = CoreUiStrings.insulin_unit_shortname
                    )
                    Text(stringResource(UiStrings.si_prebolus_gate_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // --- Pre-bolus 3 ---
            SectionCard {
                SwitchRow(stringResource(UiStrings.si_prebolus3_switch), state.pb3Enabled, viewModel::updatePb3Enabled)
                if (state.pb3Enabled) {
                    NumberInputRow(
                        labelRef = UiStrings.si_prebolus3_delay,
                        value = state.pb3DelayMinutes.toDouble(),
                        onValueChange = viewModel::updatePb3Delay,
                        valueRange = 5.0..120.0,
                        step = 5.0,
                        unitLabel = CoreUiStrings.units_min
                    )
                    NumberInputRow(
                        labelRef = UiStrings.si_prebolus_amount,
                        value = state.pb3U,
                        onValueChange = viewModel::updatePb3,
                        valueRange = 0.0..state.maxPreBolus,
                        step = state.bolusStep,
                        valueFormat = bolusFormat,
                        unitLabel = CoreUiStrings.insulin_unit_shortname
                    )
                    Text(stringResource(UiStrings.si_prebolus_gate_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content
        )
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
