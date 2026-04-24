package app.aaps.workflow

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.graph.AbsIobGraphData
import app.aaps.core.interfaces.overview.graph.ActivityGraphData
import app.aaps.core.interfaces.overview.graph.BgiGraphData
import app.aaps.core.interfaces.overview.graph.CobFailOverPoint
import app.aaps.core.interfaces.overview.graph.CobGraphData
import app.aaps.core.interfaces.overview.graph.DevSlopeGraphData
import app.aaps.core.interfaces.overview.graph.DeviationDataPoint
import app.aaps.core.interfaces.overview.graph.DeviationType
import app.aaps.core.interfaces.overview.graph.DeviationsGraphData
import app.aaps.core.interfaces.overview.graph.FuelTrimGraphData
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.IobGraphData
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.overview.graph.RatioGraphData
import app.aaps.core.interfaces.overview.graph.VarSensGraphData
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.workflow.CalculationSignalsEmitter
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.objects.extensions.combine
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.utils.receivers.DataWorkerStorage
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

class PrepareIobAutosensGraphDataWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.Default) {

    // MIGRATION: KEEP - Core dependencies needed for calculation
    @Inject lateinit var dataWorkerStorage: DataWorkerStorage
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var decimalFormatter: DecimalFormatter

    class PrepareIobAutosensData(
        val iobCobCalculator: IobCobCalculator, // cannot be injected : HistoryBrowser uses different instance
        val overviewData: OverviewData,
        val cache: OverviewDataCache,
        val signals: CalculationSignalsEmitter
    )

    override suspend fun doWorkAndLog(): Result {
        val data = dataWorkerStorage.pickupObject(inputData.getLong(DataWorkerStorage.STORE_KEY, -1)) as PrepareIobAutosensData?
            ?: return Result.failure(workDataOf("Error" to "missing input data"))

        // MIGRATION: KEEP - Get 24h time range from cache for Compose
        val cacheTimeRange = data.cache.timeRangeFlow.value
        val fromTime = cacheTimeRange?.fromTime ?: data.overviewData.fromTime
        val endTime = cacheTimeRange?.endTime ?: data.overviewData.endTime

        data.signals.emitProgress(CalculationWorkflow.ProgressData.PREPARE_IOB_AUTOSENS_DATA, 0)

        val now = dateUtil.now().toDouble()
        var time = fromTime
        var maxActivity = 0.0

        // ========== MIGRATION: KEEP - Compose arrays ==========
        val iobListCompose: MutableList<GraphDataPoint> = ArrayList()
        val absIobListCompose: MutableList<GraphDataPoint> = ArrayList()
        val cobListCompose: MutableList<GraphDataPoint> = ArrayList()
        val cobFailOverListCompose: MutableList<CobFailOverPoint> = ArrayList()
        val activityListCompose: MutableList<GraphDataPoint> = ArrayList()
        val activityPredictionListCompose: MutableList<GraphDataPoint> = ArrayList()
        val bgiListCompose: MutableList<GraphDataPoint> = ArrayList()
        val bgiPredictionListCompose: MutableList<GraphDataPoint> = ArrayList()
        val deviationsListCompose: MutableList<DeviationDataPoint> = ArrayList()
        val ratioListCompose: MutableList<GraphDataPoint> = ArrayList()
        val dsMaxListCompose: MutableList<GraphDataPoint> = ArrayList()
        val dsMinListCompose: MutableList<GraphDataPoint> = ArrayList()
        val fuelTrimListCompose: MutableList<GraphDataPoint> = ArrayList()

        val adsData = data.iobCobCalculator.ads.clone()

        // Pre-fetch APS results once; we sample fuelTrim onto the 5-min grid below
        // so the TRIM line has the same density as IOB/BGI/etc. and renders smoothly
        // instead of as sparse flat plateaus between loop cycles.
        val apsResults = persistenceLayer.getApsResults(fromTime, endTime).sortedBy { it.date }
        var apsIdx = 0

        // Seed currentTrim from the most recent APS result BEFORE the visible window so the
        // line starts at the chart's left edge instead of only appearing once the first
        // non-null fuelTrim lands inside the window. Look back a conservative 6h.
        val seedResults = persistenceLayer.getApsResults(fromTime - 6 * 60 * 60 * 1000L, fromTime)
        var currentTrim: Double? = seedResults
            .asReversed()
            .firstNotNullOfOrNull { it.fuelTrim }

        aapsLogger.debug(
            LTag.AUTOSENS,
            "FuelTrim debug: fromTime=${dateUtil.dateAndTimeAndSecondsString(fromTime)} " +
                "endTime=${dateUtil.dateAndTimeAndSecondsString(endTime)} " +
                "apsResultsInWindow=${apsResults.size} " +
                "nonNullTrimInWindow=${apsResults.count { it.fuelTrim != null }} " +
                "seedResultsBefore=${seedResults.size} " +
                "nonNullSeedTrim=${seedResults.count { it.fuelTrim != null }} " +
                "seededTrim=$currentTrim"
        )

        while (time <= endTime) {
            if (isStopped) return Result.failure(workDataOf("Error" to "stopped"))
            val progress = (time - fromTime).toDouble() / (endTime - fromTime) * 100.0
            data.signals.emitProgress(CalculationWorkflow.ProgressData.PREPARE_IOB_AUTOSENS_DATA, progress.toInt())
            val profile = profileFunction.getProfile(time)
            if (profile == null) {
                time += 5 * 60 * 1000L
                continue
            }
            // IOB
            val iob = data.iobCobCalculator.calculateFromTreatmentsAndTemps(time, profile)
            val baseBasalIob = data.iobCobCalculator.calculateAbsoluteIobFromBaseBasals(time)
            val absIob = IobTotal.combine(iob, baseBasalIob)
            val autosensData = adsData.getAutosensDataAtTime(time)

            // ========== MIGRATION: KEEP - Compose data collection ==========
            // IOB for Compose
            iobListCompose.add(GraphDataPoint(time, iob.iob))
            absIobListCompose.add(GraphDataPoint(time, absIob.iob))

            // COB, BGI, Deviations, Ratio, DevSlope for Compose
            if (autosensData != null) {
                cobListCompose.add(GraphDataPoint(time, autosensData.cob))
                if (autosensData.failOverToMinAbsorptionRate) {
                    cobFailOverListCompose.add(CobFailOverPoint(time, autosensData.cob))
                }

                // BGI for Compose
                val bgiCompose: Double = iob.activity * autosensData.sens * 5.0
                if (time <= now) bgiListCompose.add(GraphDataPoint(time, bgiCompose))
                else bgiPredictionListCompose.add(GraphDataPoint(time, bgiCompose))

                // Deviations for Compose (with type instead of color)
                val deviationType = when {
                    autosensData.type == "uam"          -> DeviationType.UAM
                    autosensData.type == "csf"          -> DeviationType.CSF
                    autosensData.pastSensitivity == "C" -> DeviationType.CSF
                    autosensData.pastSensitivity == "+" -> DeviationType.POSITIVE
                    autosensData.pastSensitivity == "-" -> DeviationType.NEGATIVE
                    else                                -> DeviationType.EQUAL
                }
                deviationsListCompose.add(DeviationDataPoint(time, autosensData.deviation, deviationType))

                // Ratio for Compose
                ratioListCompose.add(GraphDataPoint(time, 100.0 * (autosensData.autosensResult.ratio - 1)))

                // Dev slope for Compose
                dsMaxListCompose.add(GraphDataPoint(time, autosensData.slopeFromMaxDeviation))
                dsMinListCompose.add(GraphDataPoint(time, autosensData.slopeFromMinDeviation))
            }

            // Activity for Compose
            if (time <= now) activityListCompose.add(GraphDataPoint(time, iob.activity))
            else activityPredictionListCompose.add(GraphDataPoint(time, iob.activity))
            if (iob.activity > maxActivity) maxActivity = iob.activity
            else if (-iob.activity > maxActivity) maxActivity = -iob.activity

            // Fuel Trim: advance through APS results up to current tick, hold most recent value.
            // Emitting one point per 5-min tick gives the line the same density as BGI/IOB so
            // Smooth interpolation produces a flowing curve instead of sparse plateaus.
            // Only emit up to 'now' — trim is a historical/current measurement, not a forecast,
            // so the line must stop at the now-marker like BGI/activity do.
            while (apsIdx < apsResults.size && apsResults[apsIdx].date <= time) {
                apsResults[apsIdx].fuelTrim?.let { currentTrim = it }
                apsIdx++
            }
            if (time <= now) currentTrim?.let { fuelTrimListCompose.add(GraphDataPoint(time, it)) }

            time += 5 * 60 * 1000L
        }

        // ========== MIGRATION: KEEP - IOB predictions for Compose ==========
        val iobPredictionsListCompose: MutableList<GraphDataPoint> = ArrayList()
        val lastAutosensData = adsData.getLastAutosensData("GraphData", aapsLogger, dateUtil)
        val lastAutosensResult = lastAutosensData?.autosensResult ?: AutosensResult()
        val isTempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now()) != null
        val iobPredictionArray = data.iobCobCalculator.calculateIobArrayForSMB(lastAutosensResult, SMBDefaults.exercise_mode, SMBDefaults.half_basal_exercise_target, isTempTarget)
        for (i in iobPredictionArray) {
            iobPredictionsListCompose.add(GraphDataPoint(i.time, i.iob))
        }
        aapsLogger.debug(LTag.AUTOSENS, "IOB prediction for AS=" + decimalFormatter.to2Decimal(lastAutosensResult.ratio) + ": " + data.iobCobCalculator.iobArrayToString(iobPredictionArray))

        // ========== MIGRATION: KEEP - VarSens for Compose ==========
        // Note: apsResults was fetched above the main loop for fuelTrim grid sampling; reuse it here.
        val varSensListCompose: MutableList<GraphDataPoint> = ArrayList()
        apsResults.forEach {
            it.variableSens?.let { variableSens ->
                val varSens = profileUtil.fromMgdlToUnits(variableSens)
                varSensListCompose.add(GraphDataPoint(it.date, varSens))
            }
        }

        // ========== MIGRATION: KEEP - Compose cache updates ==========
        data.cache.updateIobGraph(
            IobGraphData(
                iob = iobListCompose,
                predictions = iobPredictionsListCompose
            )
        )
        data.cache.updateAbsIobGraph(
            AbsIobGraphData(
                absIob = absIobListCompose
            )
        )
        data.cache.updateCobGraph(
            CobGraphData(
                cob = cobListCompose,
                failOverPoints = cobFailOverListCompose
            )
        )
        data.cache.updateActivityGraph(
            ActivityGraphData(
                activity = activityListCompose,
                activityPrediction = activityPredictionListCompose,
                maxActivity = maxActivity
            )
        )
        data.cache.updateBgiGraph(
            BgiGraphData(
                bgi = bgiListCompose,
                bgiPrediction = bgiPredictionListCompose
            )
        )
        data.cache.updateDeviationsGraph(
            DeviationsGraphData(
                deviations = deviationsListCompose
            )
        )
        data.cache.updateRatioGraph(
            RatioGraphData(
                ratio = ratioListCompose
            )
        )
        data.cache.updateDevSlopeGraph(
            DevSlopeGraphData(
                dsMax = dsMaxListCompose,
                dsMin = dsMinListCompose
            )
        )
        data.cache.updateVarSensGraph(
            VarSensGraphData(
                varSens = varSensListCompose
            )
        )
        data.cache.updateFuelTrimGraph(
            FuelTrimGraphData(
                fuelTrim = fuelTrimListCompose
            )
        )

        data.signals.emitProgress(CalculationWorkflow.ProgressData.PREPARE_IOB_AUTOSENS_DATA, 100)
        return Result.success()
    }
}