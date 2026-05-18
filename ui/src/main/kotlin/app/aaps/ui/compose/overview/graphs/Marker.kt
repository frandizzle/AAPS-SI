package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.Insets
import com.patrykandpatrick.vico.compose.common.Position
import com.patrykandpatrick.vico.compose.common.component.LineComponent
import com.patrykandpatrick.vico.compose.common.component.TextComponent
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import kotlin.math.abs

/**
 * Data structure for the interactive graph tooltip.
 */
data class MarkerData(
    val time: String,
    val bgValue: Double,
    val bgColor: Color,
    val rangeEmoji: String,
    val deltaText: String,
    val iobText: String,
    val cobText: String = ""
)

/**
 * DefaultCartesianMarker subclass that paints the label at an EMA-smoothed
 * Y position so the tooltip rises and falls with the BG curve without
 * snapping between per-reading step changes.
 *
 * Only the label Y is smoothed. The guideline is drawn by the parent at
 * the real canvas X so horizontal tracking stays precise.
 */
private class SmoothedCartesianMarker(
    label: TextComponent,
    valueFormatter: ValueFormatter,
    guideline: LineComponent?
) : DefaultCartesianMarker(
    label = label,
    valueFormatter = valueFormatter,
    labelPosition = LabelPosition.AroundPoint, // no reserved insets; draw position is overridden anyway
    indicator = null,
    indicatorSize = 0.dp,
    guideline = guideline,
) {
    /** EMA-smoothed label Y in canvas pixels. NaN = reset. */
    private var smoothedY: Float = Float.NaN
    private var lastX: Float = Float.NaN

    // Movement less than this many pixels is ignored — the label stays put.
    // Kills the per-reading step bounce (typical CGM jump is ~3-8px of Y).
    private val deadZonePx: Float = 15f

    // Fraction of the remaining gap closed per frame when smoothing kicks in.
    // Low = very gentle drift. 0.08 ≈ slow follow.
    private val smoothing: Float = 0.08f

    override fun drawOverLayers(
        context: CartesianDrawingContext,
        targets: List<CartesianMarker.Target>
    ) {
        with(context) {
            // Let the parent draw the guideline at its real X.
            drawGuideline(targets)

            if (targets.isEmpty()) return

            // Prefer the main BG layer (0) for tracking the marker Y position.
            val lineTarget = targets.filterIsInstance<LineCartesianLayerMarkerTarget>()
                .firstOrNull()
                ?: return
            val point = lineTarget.points.firstOrNull() ?: return
            val rawY = point.canvasY
            val targetX = lineTarget.canvasX

            // Reset smoothing on first frame of a scrub or on a big horizontal jump.
            val reset = smoothedY.isNaN() ||
                (!lastX.isNaN() && abs(targetX - lastX) > 120f)

            smoothedY = when {
                reset -> rawY
                // Dead zone: small per-reading jumps don't move the label at all.
                abs(rawY - smoothedY) < deadZonePx -> smoothedY
                // Gentle EMA drift toward the new Y.
                else -> smoothedY + smoothing * (rawY - smoothedY)
            }
            lastX = targetX

            // Keep the label inside the plot area — but only if the plot is
            // tall enough to contain it. If not (label height > plot height),
            // center it vertically in the plot to avoid an invalid range.
            val text = valueFormatter.format(context, targets)
            val labelBounds = label.getBounds(context, text, layerBounds.width.toInt())
            val halfH = labelBounds.height / 2f

            // Offset the label upward so it doesn't sit under the finger.
            // Roughly: full label height + gap above the touch point.
            val fingerOffset = labelBounds.height + 80f
            val targetY = smoothedY - fingerOffset

            // Clamp only against the BOTTOM edge (so the label can't escape
            // downward off the plot). Allow it to rise above layerBounds.top
            // — Compose doesn't clip, so drawing outside the plot area is
            // fine and it means the finger doesn't cover the tooltip.
            val maxY = layerBounds.bottom - halfH
            val drawY = if (targetY > maxY) maxY else targetY

            // Apply the range color to the label if needed (e.g. for border).
            // (Ignoring strokeFill reassignment as it is val)

            label.draw(
                context = context,
                text = text,
                x = targetX,
                y = drawY,
                verticalPosition = Position.Vertical.Center,
                maxWidth = layerBounds.width.toInt(),
            )
        }
    }
}

@Composable
fun rememberMarker(
    minTimestamp: Long,
    isVisible: Boolean,
    getDetails: (Long) -> MarkerData?
): DefaultCartesianMarker {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    val labelBackground = remember(surfaceColor, outlineColor) {
        ShapeComponent(
            fill = Fill(surfaceColor),
            shape = RoundedCornerShape(4.dp),
            strokeFill = Fill(outlineColor),
            strokeThickness = 1.dp
        )
    }

    val label = rememberTextComponent(
        style = TextStyle(
            color = onSurfaceColor,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Start
        ),
        background = labelBackground,
        padding = Insets(horizontal = 8.dp, vertical = 4.dp),
        lineCount = 4
    )

    val guideline = rememberLineComponent(
        fill = Fill(if (isVisible) onSurfaceColor.copy(alpha = 0.2f) else Color.Transparent),
        thickness = 2.dp,
    )

    val valueFormatter = remember(minTimestamp, getDetails, isVisible) {
        DefaultCartesianMarker.ValueFormatter { _, targets ->
            if (!isVisible) return@ValueFormatter ""
            val bgTarget = targets.filterIsInstance<LineCartesianLayerMarkerTarget>().firstOrNull()
                ?: return@ValueFormatter ""
            val x = bgTarget.x
            val timestamp = minTimestamp + (x * 60000).toLong()
            val data = getDetails(timestamp) ?: return@ValueFormatter ""

            val bgFormatted = if (data.bgValue > 30) data.bgValue.toInt().toString() else "%.1f".format(data.bgValue)
            val cobLine = if (data.cobText.isNotEmpty()) "  🥪 ${data.cobText}" else ""

            "${data.time}\n${data.rangeEmoji} $bgFormatted ${data.deltaText}\n💉 ${data.iobText}$cobLine"
        }
    }

    return remember(label, valueFormatter, guideline) {
        SmoothedCartesianMarker(
            label = label,
            valueFormatter = valueFormatter,
            guideline = guideline,
        )
    }
}