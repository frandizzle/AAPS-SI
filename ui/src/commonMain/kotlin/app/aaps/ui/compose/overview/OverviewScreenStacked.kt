package app.aaps.ui.compose.overview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.model.ActiveSceneState
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TT
import app.aaps.core.interfaces.overview.graph.TbrState
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalConfig
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.ui.compose.main.TempTargetChipState
import app.aaps.ui.compose.manageSheet.ManageViewModel
import app.aaps.ui.compose.overview.aapsClient.AapsClientStatusCard
import app.aaps.ui.compose.overview.chips.ChipsViewModel
import app.aaps.ui.compose.overview.chips.SmartInsulinChipsRow
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.overview.graphs.GraphsSection
import app.aaps.ui.compose.overview.statusLights.StatusViewModel
import app.aaps.ui.compose.scenes.ActiveSceneBanner

@Composable
fun OverviewScreenStacked(
    profileName: String,
    isProfileModified: Boolean,
    profileProgress: Float,
    profileSceneManaged: Boolean = false,
    tempTargetText: String,
    tempTargetState: TempTargetChipState,
    tempTargetProgress: Float,
    tempTargetReason: TT.Reason?,
    tempTargetSceneManaged: Boolean = false,
    runningMode: RM.Mode?,
    runningModeText: String,
    runningModeRemaining: String,
    runningModeProgress: Float,
    runningModeSceneManaged: Boolean = false,
    tbrState: TbrState,
    smbEnabled: Boolean,
    isSimpleMode: Boolean,
    graphViewModel: GraphViewModel,
    chipsViewModel: ChipsViewModel,
    manageViewModel: ManageViewModel,
    statusViewModel: StatusViewModel,
    statusLightsDef: PreferenceSubScreenDef,
    onNavigate: (NavigationRequest) -> Unit,
    onTbrChipClick: () -> Unit,
    onIobChipClick: () -> Unit,
    paddingValues: PaddingValues,
    activeSceneState: ActiveSceneState? = null,
    sceneExpired: Boolean = false,
    onEndScene: () -> Unit = {},
    onDismissScene: () -> Unit = {},
    endSceneEnabled: Boolean = true,
    commandsAllowed: Boolean = true,
    formatDuration: (Long) -> String = { ms -> "${(ms / 60000L).toInt()}m" },
    modifier: Modifier = Modifier
) {
    val config = LocalConfig.current
    val bgInfoState by graphViewModel.bgInfoState.collectAsStateWithLifecycle()
    val sensitivityUiState by chipsViewModel.sensitivityUiState.collectAsStateWithLifecycle()
    val iobUiState by chipsViewModel.iobUiState.collectAsStateWithLifecycle()
    val cobUiState by chipsViewModel.cobUiState.collectAsStateWithLifecycle()
    val smartInsulinState by chipsViewModel.smartInsulinUiState.collectAsStateWithLifecycle()
    val basalRateText by chipsViewModel.basalRateText.collectAsStateWithLifecycle()
    // SmartInsulin: the earlier 4.0 SI layout - IOB/basal/SMB row full width under the header,
    // sensitivity under the BG circle.
    val siLayout = smartInsulinState != null
    val statusState by statusViewModel.uiState.collectAsStateWithLifecycle()

    var statusExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
    ) {
        ActiveSceneBanner(
            activeState = activeSceneState,
            expired = sceneExpired,
            onEndClick = onEndScene,
            onDismiss = onDismissScene,
            endEnabled = endSceneEnabled,
            formatDuration = formatDuration
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = if (siLayout) Alignment.Top else Alignment.Bottom
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BgInfoSection(
                    bgInfo = bgInfoState.bgInfo,
                    timeAgoText = bgInfoState.timeAgoText
                )
                if (siLayout) SensitivityChipBlock(state = sensitivityUiState)
            }

            OverviewChipsColumn(
                runningMode = runningMode,
                runningModeText = runningModeText,
                runningModeRemaining = runningModeRemaining,
                runningModeProgress = runningModeProgress,
                runningModeSceneManaged = runningModeSceneManaged,
                smbEnabled = smbEnabled,
                profileName = profileName,
                isProfileModified = isProfileModified,
                profileProgress = profileProgress,
                profileSceneManaged = profileSceneManaged,
                tempTargetText = tempTargetText,
                tempTargetState = tempTargetState,
                tempTargetProgress = tempTargetProgress,
                tempTargetReason = tempTargetReason,
                tempTargetSceneManaged = tempTargetSceneManaged,
                tbrState = tbrState,
                iobUiState = iobUiState,
                cobUiState = cobUiState,
                sensitivityUiState = sensitivityUiState,
                smartInsulinState = smartInsulinState,
                basalRateText = basalRateText,
                showIobRow = !siLayout,
                onNavigate = onNavigate,
                onTbrChipClick = onTbrChipClick,
                onIobChipClick = onIobChipClick,
                commandsAllowed = commandsAllowed,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
            )
        }

        if (siLayout)
            SmartInsulinChipsRow(
                iobUiState = iobUiState,
                tbrState = tbrState,
                basalRateText = basalRateText,
                cobUiState = cobUiState,
                onIobChipClick = onIobChipClick,
                onTbrChipClick = onTbrChipClick,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )

        OverviewStatusSection(
            sensorStatus = statusState.sensorStatus,
            insulinStatus = statusState.insulinStatus,
            cannulaStatus = statusState.cannulaStatus,
            batteryStatus = statusState.batteryStatus,
            showFill = statusState.showFill,
            showPumpBatteryChange = statusState.showPumpBatteryChange,
            commandsAllowed = commandsAllowed,
            onNavigate = onNavigate,
            statusLightsDef = statusLightsDef,
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

        GraphsSection(graphViewModel = graphViewModel, isSimpleMode = isSimpleMode, iobText = iobUiState.text, cobText = cobUiState.text)
    }
}
