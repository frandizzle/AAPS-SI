package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.configuration.Constants
import app.aaps.core.graph.vico.Square
import app.aaps.core.interfaces.overview.graph.ActivityGraphData
import app.aaps.core.interfaces.overview.graph.BasalGraphData
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.EpsGraphPoint
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.interfaces.overview.graph.TargetLineData
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.icons.IcProfile
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.LineComponent
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import com.patrykandpatrick.vico.compose.common.component.TextComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent

/** Series identifiers */
/** Basal on BG graph — deprecated, now shown as flipped overlay on IOB graph. Set to true to restore. */
@Deprecated("Basal moved to IOB graph as flipped overlay")
private const val showBasalOnBgGraph = false

private const val SERIES_REGULAR = "regular"
private const val SERIES_BUCKETED = "bucketed"
private const val SERIES_PRED_IOB = "pred_iob"
private const val SERIES_PRED_COB = "pred_cob"
private const val SERIES_PRED_ACOB = "pred_acob"
private const val SERIES_PRED_UAM = "pred_uam"
private const val SERIES_PRED_ZT = "pred_zt"

/** All prediction series identifiers */
private val PREDICTION_SERIES = listOf(SERIES_PRED_IOB, SERIES_PRED_COB, SERIES_PRED_ACOB, SERIES_PRED_UAM, SERIES_PRED_ZT)

/**
 * BG Graph using Vico — dual-layer chart.
 *
 * Layer 0 (start axis): BG readings — regular (outlined circles) + bucketed (filled, range-colored)
 * Layer 1 (end axis, hidden): Basal — profile (dashed step) + actual delivered (solid step + area fill)
 *
 * Basal Y-axis is scaled so maxBasal = 25% of chart height (maxY = maxBasal * 4).
 *
 * Scroll/Zoom:
 * - Accepts external scroll/zoom states for synchronization with secondary graphs
 * - This is the primary interactive graph - user controls scroll/zoom here
 */
@Composable
fun BgGraphCompose(
    viewModel: GraphViewModel,
    bgOverlays: List<SeriesType>,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    derivedTimeRange: Pair<Long, Long>?,
    nowTimestamp: Long,
    modifier: Modifier = Modifier
) {
    // 1. Collect flows
    val bgReadings by viewModel.bgReadingsFlow.collectAsStateWithLifecycle()
    val bucketedData by viewModel.bucketedDataFlow.collectAsStateWithLifecycle()
    val showPredictions = SeriesType.PREDICTIONS in bgOverlays
    val rawPredictions by viewModel.predictionsFlow.collectAsStateWithLifecycle()
    val predictions = if (showPredictions) rawPredictions else emptyList()
    val rawBasalData by viewModel.basalGraphFlow.collectAsStateWithLifecycle()
    val targetData by viewModel.targetLineFlow.collectAsStateWithLifecycle()
    val iobData by viewModel.iobGraphFlow.collectAsStateWithLifecycle()
    val epsPoints by viewModel.epsGraphFlow.collectAsStateWithLifecycle()
    val showActivity = SeriesType.ACTIVITY in bgOverlays
    val activityData by viewModel.activityGraphFlow.collectAsStateWithLifecycle()
    val chartConfig by viewModel.chartConfigFlow.collectAsStateWithLifecycle()

    // 2. Derived time range
    val (minTimestamp, maxTimestamp) = derivedTimeRange ?: run {
        val now = System.currentTimeMillis()
        val dayAgo = now - Constants.GRAPH_TIME_RANGE_HOURS * 60 * 60 * 1000L
        dayAgo to now
    }

    // 3. Colors
    val regularColor = AapsTheme.generalColors.originalBgValue
    val lowColor = AapsTheme.generalColors.bgLow
    val inRangeColor = AapsTheme.generalColors.bgInRange
    val highColor = AapsTheme.generalColors.bgHigh
    val basalColor = AapsTheme.elementColors.tempBasal
    val targetLineColor = AapsTheme.elementColors.tempTarget
    val activityColor = AapsTheme.elementColors.activity
    val iobPredColor = AapsTheme.generalColors.iobPrediction
    val cobPredColor = AapsTheme.generalColors.cobPrediction
    val aCobPredColor = AapsTheme.generalColors.aCobPrediction
    val uamPredColor = AapsTheme.generalColors.uamPrediction
    val ztPredColor = AapsTheme.generalColors.ztPrediction

    // 4. Data Lookups (for Tooltip)
    val getBgDetails = remember(bgReadings, bucketedData, iobData, viewModel.profileUtil, lowColor, inRangeColor, highColor) {
        { ts: Long ->
            val allBg = bgReadings + bucketedData
            val closest = allBg.minByOrNull { kotlin.math.abs(it.timestamp - ts) }
            if (closest != null && kotlin.math.abs(closest.timestamp - ts) < 5 * 60000) {
                val isMmol = viewModel.profileUtil.units == app.aaps.core.data.model.GlucoseUnit.MMOL

                // Find previous reading to compute delta
                val prev = allBg.filter { it.timestamp < closest.timestamp }.maxByOrNull { it.timestamp }
                val deltaText = if (prev != null) {
                    val delta = closest.value - prev.value
                    if (isMmol) "(%+.1f)".format(delta) else "(%+0.0f)".format(delta)
                } else ""

                // Find closest IOB
                val closestIob = iobData.iob.minByOrNull { kotlin.math.abs(it.timestamp - ts) }
                val iobText = if (closestIob != null && kotlin.math.abs(closestIob.timestamp - ts) < 5 * 60000) {
                    "%.2f U".format(closestIob.value)
                } else "—"

                val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(closest.timestamp))

                val bgColor = when {
                    isMmol -> when {
                        closest.value <= 3.9 -> Color(0xFFE53935)
                        closest.value >= 10.0 -> Color(0xFFFB8C00)
                        else -> Color(0xFF43A047)
                    }
                    else -> when {
                        closest.value <= 70.0 -> Color(0xFFE53935)
                        closest.value >= 180.0 -> Color(0xFFFB8C00)
                        else -> Color(0xFF43A047)
                    }
                }

                MarkerData(
                    time = timeStr,
                    bgValue = closest.value,
                    bgColor = bgColor,
                    deltaText = deltaText,
                    iobText = iobText
                )
            } else null
        }
    }

    // 5. Chart Range & Scaling
    val maxX = remember(minTimestamp, maxTimestamp) {
        timestampToX(maxTimestamp, minTimestamp)
    }
    val stableTimeRange = remember(minTimestamp / 60000, maxTimestamp / 60000) {
        minTimestamp to maxTimestamp
    }
    val basalMaxY = remember(rawBasalData.maxBasal) {
        if (rawBasalData.maxBasal > 0.0) rawBasalData.maxBasal * 4.0 else 1.0
    }

    // 6. Model Producer & Registry
    val modelProducer = remember { CartesianChartModelProducer() }
    val seriesRegistry = remember { mutableStateMapOf<String, List<BgDataPoint>>() }
    val activeSeriesState = remember { mutableStateOf(listOf<String>()) }

    // Rebuild function
    suspend fun rebuildChart(
        currentBasalData: BasalGraphData,
        currentTargetData: TargetLineData,
        currentEpsPoints: List<EpsGraphPoint>,
        currentActivityData: ActivityGraphData,
        currentMaxBgY: Double
    ) {
        val regularPoints = seriesRegistry[SERIES_REGULAR] ?: emptyList()
        val bucketedPoints = seriesRegistry[SERIES_BUCKETED] ?: emptyList()
        if (regularPoints.isEmpty() && bucketedPoints.isEmpty()) return

        modelProducer.runTransaction {
            // Block 1: BG & Predictions
            lineSeries {
                val activeSeries = mutableListOf<String>()
                if (regularPoints.isNotEmpty()) {
                    val dataPoints = regularPoints
                        .map { timestampToX(it.timestamp, minTimestamp) to it.value }
                        .sortedBy { it.first }
                    series(x = dataPoints.map { it.first }, y = dataPoints.map { it.second })
                    activeSeries.add(SERIES_REGULAR)
                }
                if (bucketedPoints.isNotEmpty()) {
                    val dataPoints = bucketedPoints
                        .map { timestampToX(it.timestamp, minTimestamp) to it.value }
                        .sortedBy { it.first }
                    series(x = dataPoints.map { it.first }, y = dataPoints.map { it.second })
                    activeSeries.add(SERIES_BUCKETED)
                }
                for (predSeries in PREDICTION_SERIES) {
                    val predPoints = seriesRegistry[predSeries]
                    if (predPoints != null && predPoints.isNotEmpty()) {
                        val dataPoints = predPoints
                            .map { timestampToX(it.timestamp, minTimestamp) to it.value }
                            .sortedBy { it.first }
                        series(x = dataPoints.map { it.first }, y = dataPoints.map { it.second })
                        activeSeries.add(predSeries)
                    }
                }
                series(x = normalizerX(maxX), y = NORMALIZER_Y)
                activeSeriesState.value = activeSeries.toList()
            }
            // Block 2: Target Line
            lineSeries {
                if (currentTargetData.targets.size >= 2) {
                    val pts = currentTargetData.targets
                        .map { timestampToX(it.timestamp, minTimestamp) to it.value }
                        .sortedBy { it.first }
                    series(x = pts.map { it.first }, y = pts.map { it.second })
                } else {
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                }
            }
            // Block 3: Activity
            lineSeries {
                val maxAct = currentActivityData.maxActivity
                if (!showActivity || maxAct <= 0.0 || currentActivityData.activity.size < 2) {
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                    return@lineSeries
                }
                val scaleFactor = currentMaxBgY * 0.8 / maxAct
                val pts = currentActivityData.activity
                    .map { timestampToX(it.timestamp, minTimestamp) to (it.value * scaleFactor) }
                    .sortedBy { it.first }
                series(x = pts.map { it.first }, y = pts.map { it.second })
                if (currentActivityData.activityPrediction.size >= 2) {
                    val predPts = currentActivityData.activityPrediction
                        .map { timestampToX(it.timestamp, minTimestamp) to (it.value * scaleFactor) }
                        .sortedBy { it.first }
                    series(x = predPts.map { it.first }, y = predPts.map { it.second })
                } else {
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                }
            }
        }
    }

    val predictionsByType = remember(predictions) {
        mapOf(
            SERIES_PRED_IOB to predictions.filter { it.type == BgType.IOB_PREDICTION },
            SERIES_PRED_COB to predictions.filter { it.type == BgType.COB_PREDICTION },
            SERIES_PRED_ACOB to predictions.filter { it.type == BgType.A_COB_PREDICTION },
            SERIES_PRED_UAM to predictions.filter { it.type == BgType.UAM_PREDICTION },
            SERIES_PRED_ZT to predictions.filter { it.type == BgType.ZT_PREDICTION }
        )
    }

    LaunchedEffect(bgReadings, bucketedData, predictionsByType, rawBasalData, targetData, epsPoints, activityData, showActivity, chartConfig, stableTimeRange) {
        seriesRegistry[SERIES_REGULAR] = bgReadings
        seriesRegistry[SERIES_BUCKETED] = bucketedData
        for ((key, points) in predictionsByType) {
            seriesRegistry[key] = points
        }
        val allBgValues = (bgReadings + bucketedData).map { it.value }
        val maxBgY = if (allBgValues.isNotEmpty()) maxOf(allBgValues.max(), chartConfig.highMark) else chartConfig.highMark
        rebuildChart(rawBasalData, targetData, epsPoints, activityData, maxBgY)
    }

    // 7. Graph Lines Configuration
    val bucketedLookup = remember(bucketedData, minTimestamp) {
        bucketedData.associateBy { timestampToX(it.timestamp, minTimestamp) }
    }
    val bucketedPointProvider = remember(bucketedLookup, lowColor, inRangeColor, highColor) {
        BucketedPointProvider(bucketedLookup, lowColor, inRangeColor, highColor)
    }
    val timeFormatter = rememberTimeFormatter(minTimestamp)
    val bottomAxisItemPlacer = rememberBottomAxisItemPlacer(minTimestamp)

    val regularLine = remember(regularColor) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
            areaFill = null,
            pointProvider = LineCartesianLayer.PointProvider.single(
                LineCartesianLayer.Point(
                    component = ShapeComponent(fill = Fill(Color.Transparent), shape = CircleShape, strokeFill = Fill(regularColor.copy(alpha = 0.3f)), strokeThickness = 1.dp),
                    size = 6.dp
                )
            )
        )
    }
    val bucketedLine = remember(bucketedPointProvider) {
        LineCartesianLayer.Line(fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)), areaFill = null, pointProvider = bucketedPointProvider)
    }
    val normalizerLine = remember { createNormalizerLine() }
    val iobPredLine = remember(iobPredColor) { createPredictionLine(iobPredColor) }
    val cobPredLine = remember(cobPredColor) { createPredictionLine(cobPredColor) }
    val aCobPredLine = remember(aCobPredColor) { createPredictionLine(aCobPredColor) }
    val uamPredLine = remember(uamPredColor) { createPredictionLine(uamPredColor) }
    val ztPredLine = remember(ztPredColor) { createPredictionLine(ztPredColor) }

    val activeSeries by activeSeriesState
    val bgLines = remember(activeSeries, regularLine, bucketedLine, iobPredLine, cobPredLine, aCobPredLine, uamPredLine, ztPredLine, normalizerLine) {
        buildList {
            if (SERIES_REGULAR in activeSeries) add(regularLine)
            if (SERIES_BUCKETED in activeSeries) add(bucketedLine)
            if (SERIES_PRED_IOB in activeSeries) add(iobPredLine)
            if (SERIES_PRED_COB in activeSeries) add(cobPredLine)
            if (SERIES_PRED_ACOB in activeSeries) add(aCobPredLine)
            if (SERIES_PRED_UAM in activeSeries) add(uamPredLine)
            if (SERIES_PRED_ZT in activeSeries) add(ztPredLine)
            add(normalizerLine)
        }
    }

    val profileBasalLine = remember(basalColor) {
        LineCartesianLayer.Line(fill = LineCartesianLayer.LineFill.single(Fill(basalColor)), stroke = LineCartesianLayer.LineStroke.Dashed(thickness = 1.dp, cap = StrokeCap.Round, dashLength = 1.dp, gapLength = 2.dp), areaFill = null, interpolator = Square)
    }
    val actualBasalLine = remember(basalColor) {
        LineCartesianLayer.Line(fill = LineCartesianLayer.LineFill.single(Fill(basalColor)), stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.dp), areaFill = LineCartesianLayer.AreaFill.single(Fill(basalColor.copy(alpha = 0.3f))), interpolator = Square)
    }
    val basalLines = remember(profileBasalLine, actualBasalLine) { listOf(profileBasalLine, actualBasalLine) }

    val targetLine = remember(targetLineColor) {
        LineCartesianLayer.Line(fill = LineCartesianLayer.LineFill.single(Fill(targetLineColor)), stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.dp), areaFill = null, interpolator = Square)
    }
    val targetLines = remember(targetLine) { listOf(targetLine) }

    val profileSwitchColor = AapsTheme.elementColors.profileSwitch
    val profilePainter = rememberVectorPainter(IcProfile)
    val epsLine = remember(profileSwitchColor, profilePainter) {
        LineCartesianLayer.Line(fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)), areaFill = null, pointProvider = LineCartesianLayer.PointProvider.single(LineCartesianLayer.Point(component = PainterComponent(profilePainter, tint = profileSwitchColor), size = 16.dp)))
    }
    val epsLines = remember(epsLine) { listOf(epsLine) }

    val activityHistLine = remember(activityColor) {
        LineCartesianLayer.Line(fill = LineCartesianLayer.LineFill.single(Fill(activityColor)), stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.5.dp), areaFill = null)
    }
    val activityPredLine = remember(activityColor) {
        LineCartesianLayer.Line(fill = LineCartesianLayer.LineFill.single(Fill(activityColor)), stroke = LineCartesianLayer.LineStroke.Dashed(thickness = 1.5.dp, cap = StrokeCap.Round, dashLength = 4.dp, gapLength = 4.dp), areaFill = null)
    }
    val activityLines = remember(activityHistLine, activityPredLine) { listOf(activityHistLine, activityPredLine) }

    // 8. Marker & Decorations
    val nowLineColor = MaterialTheme.colorScheme.onSurface
    val nowLine = rememberNowLine(minTimestamp, nowTimestamp, nowLineColor)
    val decorations = remember(nowLine) { listOf(nowLine) }
    val marker = rememberMarker(minTimestamp, getBgDetails)

    val startAxisRangeProvider = remember(maxX) { CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = maxX) }
    val endAxisRangeProvider = remember(maxX, basalMaxY) { CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = maxX, minY = 0.0, maxY = basalMaxY) }

    // 9. Chart Host
    val scrubbing by viewModel.isScrubbing.collectAsStateWithLifecycle()

    // (I DELETED the scrubbingScrollConnection block from here!)

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 👇 DELETED .nestedScroll(...) from here!
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { viewModel.setScrubbing(true) },
                    onDragEnd = { viewModel.setScrubbing(false) },
                    onDragCancel = { viewModel.setScrubbing(false) },
                    onDrag = { _, _ -> } // Leave empty! Do not consume here!
                )
            }
    ) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                // Layer 1: BG & Predictions
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(bgLines),
                    rangeProvider = startAxisRangeProvider,
                    verticalAxisPosition = Axis.Position.Vertical.Start
                ),
                // Layer 2: Target Line
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(targetLines),
                    rangeProvider = startAxisRangeProvider,
                    verticalAxisPosition = Axis.Position.Vertical.Start
                ),
// Layer 3: Activity
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(activityLines),
                    rangeProvider = startAxisRangeProvider,
                    verticalAxisPosition = Axis.Position.Vertical.Start
                ),
                // 👇 REVERT THIS LINE: Always pass the marker!
                marker = marker,
                decorations = decorations,
                startAxis = VerticalAxis.rememberStart(
                    itemPlacer = VerticalAxis.ItemPlacer.step({ 1.0 }),
                    label = rememberTextComponent(style = TextStyle(color = MaterialTheme.colorScheme.onSurface), minWidth = TextComponent.MinWidth.fixed(30.dp)),
                    guideline = LineComponent(fill = Fill(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
                ),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = timeFormatter,
                    itemPlacer = bottomAxisItemPlacer,
                    label = rememberTextComponent(style = TextStyle(color = MaterialTheme.colorScheme.onSurface)),
                    guideline = LineComponent(fill = Fill(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
                ),
                getXStep = { 1.0 }
            ),
            modelProducer = modelProducer,
            modifier = Modifier.fillMaxWidth(),
            scrollState = scrollState,
            zoomState = zoomState
        )
    }
}