package app.aaps.ui.compose.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TT
import app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalConfig
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.ui.compose.main.TempTargetChipState
import app.aaps.ui.compose.manageSheet.ManageViewModel
import app.aaps.ui.compose.overview.aapsClient.AapsClientStatusCard
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.overview.graphs.GraphsSection
import app.aaps.ui.compose.overview.statusLights.StatusViewModel
import app.aaps.ui.compose.overview.chips.IobCobChipsRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width

@Composable
fun OverviewScreenSplit(
    profileName: String,
    isProfileModified: Boolean,
    profileProgress: Float,
    tempTargetText: String,
    tempTargetState: TempTargetChipState,
    tempTargetProgress: Float,
    tempTargetReason: TT.Reason?,
    runningMode: RM.Mode,
    runningModeText: String,
    runningModeProgress: Float,
    isSimpleMode: Boolean,
    calcProgress: Int,
    graphViewModel: GraphViewModel,
    manageViewModel: ManageViewModel,
    statusViewModel: StatusViewModel,
    statusLightsDef: PreferenceSubScreenDef,
    onNavigate: (NavigationRequest) -> Unit,
    paddingValues: PaddingValues,
    siOverviewState: SmartInsulinOverview.OverviewState? = null,
    modifier: Modifier = Modifier
) {
    val config = LocalConfig.current
    val bgInfoState by graphViewModel.bgInfoState.collectAsStateWithLifecycle()
    val sensitivityUiState by graphViewModel.sensitivityUiState.collectAsStateWithLifecycle()
    val iobUiState by graphViewModel.iobUiState.collectAsStateWithLifecycle()
    val smbUiState by graphViewModel.smbUiState.collectAsStateWithLifecycle()
    val tbrUiState by graphViewModel.tbrUiState.collectAsStateWithLifecycle()
    val statusState by statusViewModel.uiState.collectAsStateWithLifecycle()

    var statusExpanded by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        if (calcProgress < 100) {
            LinearProgressIndicator(
                progress = { calcProgress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp)
        ) {
            // Left column — BG + chips + status + NS card, own scroll
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BgInfoSection(
                            bgInfo = bgInfoState.bgInfo,
                            timeAgoText = bgInfoState.timeAgoText
                        )
                        SensitivityChipBlock(state = sensitivityUiState)
                    }

                    OverviewChipsColumn(
                        runningMode = runningMode,
                        runningModeText = runningModeText,
                        runningModeProgress = runningModeProgress,
                        isSimpleMode = isSimpleMode,
                        profileName = profileName,
                        isProfileModified = isProfileModified,
                        profileProgress = profileProgress,
                        tempTargetText = tempTargetText,
                        tempTargetState = tempTargetState,
                        tempTargetProgress = tempTargetProgress,
                        tempTargetReason = tempTargetReason,
                        onNavigate = onNavigate,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                        trailingContent = {
                            LargeClock(
                                bgTimestamp = bgInfoState.bgInfo?.timestamp,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    )
                }

                IobCobChipsRow(
                    iobUiState = iobUiState,
                    tbrUiState = tbrUiState,
                    smbUiState = smbUiState,
                    modifier = Modifier.fillMaxWidth()
                )
                siOverviewState?.let {
                    SmartInsulinStatusChip(
                        state = it,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                OverviewStatusSection(
                    sensorStatus = statusState.sensorStatus,
                    insulinStatus = statusState.insulinStatus,
                    cannulaStatus = statusState.cannulaStatus,
                    batteryStatus = statusState.batteryStatus,
                    showFill = statusState.showFill,
                    showPumpBatteryChange = statusState.showPumpBatteryChange,
                    onNavigate = onNavigate,
                    statusLightsDef = statusLightsDef,
                    onCopyFromNightscout = { manageViewModel.copyStatusLightsFromNightscout() },
                    expanded = statusExpanded,
                    onExpandedChange = { statusExpanded = it }
                )

                if (config.AAPSCLIENT) {
                    val nsClientStatus by graphViewModel.nsClientStatusFlow.collectAsStateWithLifecycle()
                    val flavorTint = when {
                        config.AAPSCLIENT3 -> AapsTheme.generalColors.flavorClient3Tint
                        config.AAPSCLIENT2 -> AapsTheme.generalColors.flavorClient2Tint
                        else               -> AapsTheme.generalColors.flavorClient1Tint
                    }
                    AapsClientStatusCard(
                        statusData = nsClientStatus,
                        flavorTint = flavorTint
                    )
                }
            }

            // Right column — graphs, own scroll
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 4.dp)
            ) {
                GraphsSection(graphViewModel = graphViewModel, isSimpleMode = isSimpleMode)
            }
        }
    }
}

@Composable
fun SmartInsulinStatusChip(
    state: SmartInsulinOverview.OverviewState,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val color = when {
            state.modeLine.contains("Recovery", ignoreCase = true) -> Color(0xFFFB8C00)
            state.learningState.contains("off", ignoreCase = true) -> Color(0xFFFB8C00)
            state.learningState.contains("active", ignoreCase = true) -> Color(0xFF4CAF50)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(
            text = "SI",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = state.modeLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        state.pb2Line?.let {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64B5F6)
            )
        }
    }
}
