package app.aaps.ui.compose.smartMealDialog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DinnerDining
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.FreeBreakfast
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.LunchDining
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.ui.ConfirmationLine
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.bottomBarSafeArea
import app.aaps.core.ui.compose.clearFocusOnTap
import app.aaps.core.ui.compose.consumeOverscroll
import app.aaps.core.ui.compose.dialogs.ElementConfirmationDialog
import app.aaps.core.ui.compose.icons.IcBolus
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
    // The pre-bolus 2/3 countdowns live in the manager; keep the active card current.
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
                TextButton(onClick = { action(); cancelPrompt = null; onNavigateBack() }) {
                    Text(stringResource(CoreUiStrings.ok), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { cancelPrompt = null }) { Text(stringResource(CoreUiStrings.cancel)) } }
        )
    }

    val bolusFormat = viewModel.decimalFormatter.pumpSupportedBolusFormat(state.bolusStep)
    val fmtU: (Double) -> String = { viewModel.decimalFormatter.toPumpSupportedBolus(it, state.bolusStep) }
    val isfMax = if (state.isMmol) 20.0 else 360.0
    val isfStep = if (state.isMmol) 0.05 else 1.0
    val isfDecimals = if (state.isMmol) 2 else 0
    val carbsColor = AapsTheme.elementColors.carbs
    val insulinColor = AapsTheme.elementColors.insulin

    // Planned insulin the mode will give up front (PB1 now + scheduled PB2/PB3), capped as the VM caps it.
    val plannedU = listOf(
        if (state.pb1Enabled) state.pb1U else 0.0,
        if (state.pb2Enabled) state.pb2U else 0.0,
        if (state.pb3Enabled) state.pb3U else 0.0
    ).sumOf { it.coerceAtMost(state.maxPreBolus) }

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
                    .height(52.dp)
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (plannedU > 0.0) stringResource(UiStrings.si_start_mode_units, state.mode.label, fmtU(plannedU))
                    else stringResource(UiStrings.si_start_mode, state.mode.label),
                    style = MaterialTheme.typography.titleSmall
                )
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DialogStatusBar(bgInfo = bgInfo, iob = iob, cob = cob)

            // --- What is running now ---
            state.activeMode?.let { active ->
                val endMessage =
                    if (active.isUam) stringResource(UiStrings.si_cancel_uam_confirm, active.label)
                    else stringResource(UiStrings.si_cancel_mode_confirm, active.label)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = carbsColor.copy(alpha = 0.10f)),
                    border = BorderStroke(1.dp, carbsColor.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(modeIcon(active), carbsColor)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(stringResource(UiStrings.si_active_title), style = MaterialTheme.typography.labelMedium,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(UiStrings.si_active_mode, active.label, state.activeRemainingMin),
                                     style = MaterialTheme.typography.titleMedium)
                            }
                            OutlinedButton(
                                onClick = { cancelPrompt = endMessage to viewModel::cancelMode },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                            ) { Text(stringResource(UiStrings.si_end_mode)) }
                        }
                        if (active.isUam)
                            Text(stringResource(UiStrings.si_uam_active_banner, active.label), style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.pb2Pending) {
                            val msg = stringResource(UiStrings.si_cancel_pb2_confirm)
                            PendingRow(state.pb2Status) { cancelPrompt = msg to viewModel::cancelPb2 }
                        }
                        if (state.pb3Pending) {
                            val msg = stringResource(UiStrings.si_cancel_pb3_confirm)
                            PendingRow(state.pb3Status) { cancelPrompt = msg to viewModel::cancelPb3 }
                        }
                    }
                }
            }

            // --- Mode picker ---
            Text(stringResource(UiStrings.si_mode_label), style = MaterialTheme.typography.titleSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                viewModel.modes.take(3).forEach { m ->
                    ModeTile(m, selected = state.mode == m, color = carbsColor, modifier = Modifier.weight(1f)) { viewModel.selectMode(m) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                viewModel.modes.drop(3).forEach { m ->
                    ModeTile(m, selected = state.mode == m, color = carbsColor, modifier = Modifier.weight(1f)) { viewModel.selectMode(m) }
                }
                Spacer(Modifier.weight(1f))
            }

            // --- Duration and ISF ---
            Section(
                title = stringResource(UiStrings.si_settings_title),
                subtitle = stringResource(UiStrings.si_settings_subtitle),
                icon = Icons.Filled.Tune,
                accent = MaterialTheme.colorScheme.primary
            ) {
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
                Hint(stringResource(UiStrings.si_isf_hint))
            }

            // --- DURA ---
            val df = viewModel.decimalFormatter
            val floorText = "${if (state.isMmol) df.to2Decimal(state.duraFloor) else df.to0Decimal(state.duraFloor)} ${state.unitsLabel}"
            Section(
                title = stringResource(UiStrings.si_dura_title),
                subtitle = if (state.duraEnabled && state.duraFloor <= 0.0) stringResource(UiStrings.si_dura_summary_nofloor, df.to1Decimal(state.duraStrength))
                else if (state.duraEnabled) stringResource(UiStrings.si_dura_summary_on, floorText, df.to1Decimal(state.duraStrength))
                else stringResource(UiStrings.si_dura_summary_off),
                icon = Icons.AutoMirrored.Filled.TrendingUp,
                accent = AapsTheme.generalColors.statusWarning,
                checked = state.duraEnabled,
                onCheckedChange = viewModel::updateDuraEnabled
            ) {
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

            // --- Pre-boluses ---
            Section(
                title = stringResource(UiStrings.si_pb1_title),
                subtitle = if (state.pb1Enabled && state.pb1U > 0.0) stringResource(UiStrings.si_pb_summary_now, fmtU(state.pb1U))
                else stringResource(UiStrings.si_pb_off),
                icon = IcBolus,
                accent = insulinColor,
                checked = state.pb1Enabled,
                onCheckedChange = viewModel::updatePb1Enabled
            ) {
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
                            Text("+" + fmtU(inc))
                        }
                    }
                }
            }

            Section(
                title = stringResource(UiStrings.si_pb2_title),
                subtitle = if (state.pb2Enabled) stringResource(UiStrings.si_pb2_summary, fmtU(state.pb2U), state.pb2DelayMinutes)
                else stringResource(UiStrings.si_pb_off),
                icon = Icons.Filled.Schedule,
                accent = insulinColor,
                checked = state.pb2Enabled,
                onCheckedChange = viewModel::updatePb2Enabled
            ) {
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
                Hint(stringResource(UiStrings.si_prebolus_gate_hint))
            }

            Section(
                title = stringResource(UiStrings.si_pb3_title),
                subtitle = if (state.pb3Enabled) stringResource(UiStrings.si_pb3_summary, fmtU(state.pb3U), state.pb3DelayMinutes)
                else stringResource(UiStrings.si_pb_off),
                icon = Icons.Filled.HourglassBottom,
                accent = insulinColor,
                checked = state.pb3Enabled,
                onCheckedChange = viewModel::updatePb3Enabled
            ) {
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
                Hint(stringResource(UiStrings.si_prebolus_gate_hint))
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun modeIcon(mode: MealMode): ImageVector = when (mode) {
    MealMode.BREAKFAST, MealMode.UAM_BREAKFAST -> Icons.Filled.FreeBreakfast
    MealMode.LUNCH, MealMode.UAM_LUNCH         -> Icons.Filled.LunchDining
    MealMode.DINNER, MealMode.UAM_DINNER       -> Icons.Filled.DinnerDining
    MealMode.LOW_CARB                          -> Icons.Filled.Eco
    MealMode.EXTENDED                          -> Icons.Filled.HourglassBottom
    else                                       -> Icons.Filled.LunchDining
}

@Composable
private fun IconBadge(icon: ImageVector, color: Color) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f))
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
    }
}

/** Selectable meal-mode tile: icon over label, filled and outlined in [color] when chosen. */
@Composable
private fun ModeTile(mode: MealMode, selected: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) color.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) BorderStroke(1.5.dp, color) else null,
        modifier = modifier.height(76.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Icon(modeIcon(mode), contentDescription = null, tint = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                mode.label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A card with an icon badge, title and one-line summary. With [checked] set the header carries the
 * on/off switch and the body only shows while it is on.
 */
@Composable
private fun Section(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    accent: Color,
    checked: Boolean? = null,
    onCheckedChange: (Boolean) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (checked != null) Modifier.clickable { onCheckedChange(!checked) } else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            IconBadge(icon, if (checked == false) MaterialTheme.colorScheme.onSurfaceVariant else accent)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                         maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (checked != null) Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        AnimatedVisibility(visible = checked != false) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                HorizontalDivider(Modifier.padding(bottom = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                content()
            }
        }
    }
}

/** A pending pre-bolus: clock icon, its live status, and a compact cancel. */
@Composable
private fun PendingRow(status: String, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Icon(Icons.Filled.Schedule, contentDescription = null, tint = AapsTheme.elementColors.insulin, modifier = Modifier.size(18.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 10.dp))
            TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(UiStrings.si_cancel_short))
            }
        }
    }
}

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
