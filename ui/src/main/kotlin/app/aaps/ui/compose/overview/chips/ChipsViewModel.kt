package app.aaps.ui.compose.overview.chips

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.displayText
import app.aaps.core.objects.extensions.round
import app.aaps.core.ui.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventLoopUpdateGui
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import java.util.Locale
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn

@Stable
class ChipsViewModel @AssistedInject constructor(
    @Assisted cache: OverviewDataCache,
    private val iobCobCalculator: IobCobCalculator,
    private val loop: Loop,
    private val config: Config,
    private val persistenceLayer: PersistenceLayer,
    private val constraintChecker: ConstraintsChecker,
    private val profileFunction: ProfileFunction,
    private val processedDeviceStatusData: ProcessedDeviceStatusData,
    private val profileUtil: ProfileUtil,
    private val activePlugin: ActivePlugin,
    private val rh: ResourceHelper,
    private val decimalFormatter: DecimalFormatter,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val rxBus: RxBus
) : ViewModel() {

    @AssistedFactory
    interface Factory {

        fun create(cache: OverviewDataCache): ChipsViewModel
    }

    private val ticker30s = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000L)
        }
    }

    private val refreshFlow = merge(
        ticker30s,
        rxBus.toFlow(EventLoopUpdateGui::class.java).map { System.currentTimeMillis() },
        rxBus.toFlow(EventRefreshOverview::class.java).map { System.currentTimeMillis() }
    )

    private val iobCobTicker = merge(
        flow {
            while (true) {
                emit(Unit)
                delay(150_000L) // 2.5 minutes
            }
        },
        rxBus.toFlow(EventLoopUpdateGui::class.java).map { Unit },
        rxBus.toFlow(EventRefreshOverview::class.java).map { Unit }
    )

    val iobUiState: StateFlow<IobUiState> = iobCobTicker.combine(cache.iobGraphFlow) { _, _ ->
        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
        val total = bolusIob.iob + basalIob.basaliob
        IobUiState(
            text = rh.gs(R.string.format_insulin_units, total),
            iobTotal = total
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = IobUiState()
    )

    val cobUiState: StateFlow<CobUiState> = iobCobTicker.combine(cache.cobGraphFlow) { _, _ ->
        val cobInfo = iobCobCalculator.getCobInfo("ChipsViewModel COB")
        var cobText = cobInfo.displayText(rh, decimalFormatter)
            ?: rh.gs(R.string.value_unavailable_short)
        var carbsReq = 0

        val constraintsProcessed = loop.lastRun?.constraintsProcessed
        val lastRun = loop.lastRun
        if (config.APS && constraintsProcessed != null && lastRun != null) {
            if (constraintsProcessed.carbsReq > 0) {
                val lastCarbsTime = persistenceLayer.getNewestCarbs()?.timestamp ?: 0L
                if (lastCarbsTime < lastRun.lastAPSRun) {
                    cobText += " ${constraintsProcessed.carbsReq}${rh.gs(R.string.required)}"
                }
                carbsReq = constraintsProcessed.carbsReq
            }
        }

        CobUiState(text = cobText, carbsReq = carbsReq, cobValue = cobInfo.displayCob ?: 0.0)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CobUiState()
    )

    val sensitivityUiState: StateFlow<SensitivityUiState> = refreshFlow.map {
        buildSensitivityUiState()
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SensitivityUiState()
        )

    val smbUiState: StateFlow<SmbUiState> = refreshFlow.map {
        val lastSmbBolus = persistenceLayer.getNewestBolusOfType(app.aaps.core.data.model.BS.Type.SMB)
        if (lastSmbBolus != null) {
            val minsAgo = (dateUtil.now() - lastSmbBolus.timestamp) / 60000
            SmbUiState(
                text = "SMB: ${decimalFormatter.to2Decimal(lastSmbBolus.amount)}U ${minsAgo}m",
                hasData = true
            )
        } else {
            SmbUiState(hasData = false)
        }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SmbUiState()
        )

    val tbrUiState: StateFlow<TbrUiState> = combine(refreshFlow, iobCobTicker) { time, _ ->
        val now = time
        val currentTbr = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
        val profileBasal = profileFunction.getProfile()?.getBasal(now) ?: 0.0

        if (currentTbr == null) {
            TbrUiState(
                rate = profileBasal,
                profileBasal = profileBasal,
                isAbsolute = true,
                arrow = TbrArrow.FLAT
            )
        } else {
            val currentRate = if (currentTbr.isAbsolute) {
                currentTbr.rate
            } else {
                profileBasal * (currentTbr.rate / 100.0)
            }

            val arrow = when {
                currentRate > profileBasal + 0.001 -> TbrArrow.UP
                currentRate < profileBasal - 0.001 -> TbrArrow.DOWN
                else -> TbrArrow.FLAT
            }

            TbrUiState(
                rate = currentRate,
                profileBasal = profileBasal,
                isAbsolute = currentTbr.isAbsolute,
                arrow = arrow
            )
        }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = TbrUiState()
        )

    private suspend fun buildSensitivityUiState(): SensitivityUiState {
        val lastAutosensData = iobCobCalculator.ads.getLastAutosensData("Overview", aapsLogger, dateUtil)
        val lastAutosensRatio = lastAutosensData?.autosensResult?.ratio
        val lastAutosensPercent = lastAutosensRatio?.let { it * 100 }

        val isEnabled = if (config.AAPSCLIENT) preferences.get(BooleanNonKey.AutosensUsedOnMainPhone)
        else constraintChecker.isAutosensModeEnabled().value()

        val profile = profileFunction.getProfile()
        val request = loop.lastRun?.request
        val isfMgdl = profile?.getProfileIsfMgdl()
        val variableSens =
            if (config.APS) request?.variableSens ?: 0.0
            else if (config.AAPSCLIENT) processedDeviceStatusData.getAPSResult()?.variableSens ?: 0.0
            else 0.0
        val ratioUsed = request?.autosensResult?.ratio ?: 1.0
        val units = profileFunction.getUnits()

        var asText = ""
        var isfFrom = ""
        var isfTo = ""
        val dialogText = ArrayList<String>()

        if (variableSens != isfMgdl && variableSens != 0.0 && isfMgdl != null) {
            // Variable ISF branch — hide "AS: 100%" from overview when ratio is exactly 100%
            lastAutosensPercent?.let {
                if (it != 100.0)
                    asText = rh.gs(R.string.autosens_short, it)
                dialogText.add(rh.gs(R.string.autosens_long, it))
            }
            val profileIsfDisplayed = profileUtil.fromMgdlToUnits(isfMgdl, units)
            val variableIsfDisplayed = profileUtil.fromMgdlToUnits(variableSens, units)
            isfFrom = String.format(Locale.getDefault(), "%1$.1f", profileIsfDisplayed)
            isfTo = String.format(Locale.getDefault(), "%1$.1f", variableIsfDisplayed)
            dialogText.add(rh.gs(R.string.isf_profile, profileIsfDisplayed))
            dialogText.add(rh.gs(R.string.isf_variable, variableIsfDisplayed))
            if (ratioUsed != 1.0 && ratioUsed != lastAutosensRatio)
                dialogText.add(rh.gs(R.string.algorithm_long, ratioUsed * 100))
            val isfForCarbs = profile.getIsfMgdlForCarbs(dateUtil.now(), "Overview", config, processedDeviceStatusData)
            dialogText.add(rh.gs(R.string.isf_for_carbs, profileUtil.fromMgdlToUnits(isfForCarbs, units)))
            if (config.APS) {
                activePlugin.activeAPS?.getSensitivityOverviewString()?.let { dialogText.add(it) }
            }
        } else {
            // Standard autosens-only branch — hide "AS: 100%" from chip but always show in dialog
            lastAutosensData?.let {
                val pct = it.autosensResult.ratio * 100
                if (pct != 100.0)
                    asText = rh.gs(R.string.autosens_short, pct)
                dialogText.add(rh.gs(R.string.autosens_long, pct))
            }
            if (isfMgdl != null) {
                val profileIsfDisplayed = profileUtil.fromMgdlToUnits(isfMgdl, units)
                dialogText.add(rh.gs(R.string.isf_profile, profileIsfDisplayed))
                lastAutosensRatio?.let { ratio ->
                    dialogText.add(rh.gs(R.string.isf_effective, profileUtil.fromMgdlToUnits(isfMgdl * ratio, units)))
                }
            }
        }

        return SensitivityUiState(
            asText = asText,
            isfFrom = isfFrom,
            isfTo = isfTo,
            dialogText = dialogText.joinToString("\n"),
            ratio = lastAutosensRatio ?: 1.0,
            isEnabled = isEnabled,
            hasData = lastAutosensData != null
        )
    }
}
