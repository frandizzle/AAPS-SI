package app.aaps.core.graph.vico

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer

/**
 * Custom Vico Interpolator that creates a smooth Monotone Cubic Spline line.
 *
 * Provides a "fluent" look with organic curves between data points, similar to
 * how high-end charting libraries (and the legacy Overview graph) render
 * physiological data like IOB or Glucose.
 */
val Smooth: LineCartesianLayer.Interpolator = object : LineCartesianLayer.Interpolator {
    override fun interpolate(
        context: CartesianDrawingContext,
        path: Path,
        points: List<Offset>,
        visibleIndexRange: IntRange
    ) {
        if (visibleIndexRange.isEmpty()) return
        
        val list = points.subList(visibleIndexRange.first, visibleIndexRange.last + 1)
        if (list.size < 2) {
            if (list.isNotEmpty()) path.moveTo(list[0].x, list[0].y)
            return
        }

        // Monotone Cubic Hermite Interpolation (Simplified for path drawing)
        path.moveTo(list[0].x, list[0].y)
        
        val n = list.size
        val h = FloatArray(n - 1)
        val m = FloatArray(n - 1)
        
        for (i in 0 until n - 1) {
            h[i] = list[i + 1].x - list[i].x
            m[i] = (list[i + 1].y - list[i].y) / h[i]
        }
        
        val tangents = FloatArray(n)
        for (i in 1 until n - 1) {
            if (m[i - 1] * m[i] <= 0) {
                tangents[i] = 0f
            } else {
                tangents[i] = 3f * (h[i - 1] + h[i]) / ((2f * h[i] + h[i - 1]) / m[i - 1] + (h[i] + 2f * h[i - 1]) / m[i])
            }
        }
        tangents[0] = m[0]
        tangents[n - 1] = m[n - 2]

        for (i in 0 until n - 1) {
            val p0 = list[i]
            val p1 = list[i + 1]
            val t0 = tangents[i]
            val t1 = tangents[i + 1]
            
            val controlPt1X = p0.x + h[i] / 3f
            val controlPt1Y = p0.y + h[i] * t0 / 3f
            
            val controlPt2X = p1.x - h[i] / 3f
            val controlPt2Y = p1.y - h[i] * t1 / 3f
            
            path.cubicTo(controlPt1X, controlPt1Y, controlPt2X, controlPt2Y, p1.x, p1.y)
        }
    }
}
