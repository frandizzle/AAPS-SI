package app.aaps.plugins.aps.smartInsulin

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * CgmSmoother — Unscented Kalman Filter for CGM noise reduction.
 *
 * Pure math class: no Android dependencies, no SharedPreferences, no DI.
 * Instantiate fresh each call (stateless) — the filter re-converges over
 * the history window on every invocation, so persistence adds no benefit.
 *
 * Ported from piecycle/tsunami UnscentedKalmanFilterPlugin with the
 * following deliberate simplifications for SmartInsulin's use case:
 *   - Fixed R (no adaptive learning, no persistence) — batch reprocessing
 *     converges R implicitly across the window every cycle
 *   - No RxBus / sensor-change event subscription — caller controls gating
 *   - No PluginBase inheritance — this is a utility class, not an AAPS plugin
 *
 * State vector: x = [G, Ġ]ᵀ
 *   G  = glucose (mg/dL)
 *   Ġ  = rate of change (mg/dL/min)
 *
 * Process model: x_{t+1} = [G + Ġ·Δt,  Ġ·RATE_DAMPING]ᵀ  + w,  w ~ N(0,Q)
 * Measurement:   z_t     = G + v,  v ~ N(0,R)
 *
 * Usage:
 *   val smoother = CgmSmoother()
 *   val result   = smoother.smooth(readings)   // newest-first list of (timestampMs, mg/dL)
 *   // result.smoothedMgdl[0]     = smoothed current BG
 *   // result.deltaMgdl           = smoothed 5-min delta
 *   // result.shortAvgDeltaMgdl   = smoothed short average delta (avg of last 3 deltas)
 */
class CgmSmoother {

    // ── UKF parameters (Merwe's scaled formulation) ───────────────────────────
    private val n      = 2          // state dimension [G, Ġ]
    private val alpha  = 1.00
    private val beta   = 0.0
    private val kappa  = 3.0
    private val lambda = alpha * alpha * (n + kappa) - n
    private val gamma  = sqrt(n + lambda)

    // Sigma point weights
    private val Wm = DoubleArray(2 * n + 1)
    private val Wc = DoubleArray(2 * n + 1)

    // ── Noise parameters ─────────────────────────────────────────────────────
    // Q: fixed process noise — tuned for realistic glucose physiology.
    // Matches tsunami's tested set: Q_glucose=1.0, Q_rate=0.40
    // ~1.0 mg/dL std dev per 5 min on glucose, ~0.63 mg/dL/min std dev on rate.
    private val Q = doubleArrayOf(
        1.0, 0.0,
        0.0, 0.40
    )

    // R: fixed measurement noise — moderate starting trust in sensor.
    // ~5 mg/dL std dev. Conservative for a noisy first-day G6.
    // For G7 always-on use: R=16–25 is appropriate (no onboard smoothing).
    private val R = 25.0

    // ── Physiological limits ──────────────────────────────────────────────────
    private val MAX_RATE_MGDL_PER_MIN = 4.0   // ~72 mg/dL per 18 min — hard physiological cap
    private val MAX_GLUCOSE_VARIANCE  = 400.0  // 20 mg/dL std dev max
    private val MAX_RATE_VARIANCE     = 4.0    // 2 mg/dL/min std dev max

    // ── Gap handling ──────────────────────────────────────────────────────────
    private val MAJOR_GAP_MINUTES     = 20.0   // gap > 20 min → segment break
    private val MIN_INTERVAL_MINUTES  = 2.0    // gap < 2 min → duplicate, skip

    // ── Result container ──────────────────────────────────────────────────────
    data class SmoothedResult(
        /** Smoothed BG values, newest-first, parallel to input list. mg/dL. */
        val smoothedMgdl:       List<Double>,
        /** Smoothed 5-min delta (current minus one reading back). mg/dL. */
        val deltaMgdl:          Double,
        /** Smoothed short average delta (mean of up to 3 consecutive deltas). mg/dL. */
        val shortAvgDeltaMgdl:  Double,
        /** True if the filter ran (≥2 valid readings); false = raw passthrough. */
        val filtered:           Boolean
    )

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * @param readings  Glucose readings, newest-first.
     *                  Each element is Pair(timestampMs: Long, valueMgdl: Double).
     *                  Must contain at least 2 readings for the filter to run.
     * @return SmoothedResult with smoothed values and recomputed deltas.
     */
    fun smooth(readings: List<Pair<Long, Double>>): SmoothedResult {
        initWeights()

        if (readings.size < 2) {
            val raw = readings.firstOrNull()?.second ?: 100.0
            return SmoothedResult(
                smoothedMgdl      = readings.map { it.second },
                deltaMgdl         = 0.0,
                shortAvgDeltaMgdl = 0.0,
                filtered          = false
            )
        }

        val smoothed = DoubleArray(readings.size) { readings[it].second }

        // Find contiguous segments (split at major gaps or error values)
        val segments = findSegments(readings)

        for (seg in segments) {
            if (seg.last - seg.first < 1) continue   // single point — nothing to filter
            processSegment(readings, smoothed, seg.first, seg.last)
        }

        // Recompute deltas from smoothed values (newest = index 0)
        val delta         = computeDelta(readings, smoothed, lookback = 1)
        val shortAvgDelta = computeShortAvgDelta(readings, smoothed, maxDeltas = 3)

        return SmoothedResult(
            smoothedMgdl      = smoothed.toList(),
            deltaMgdl         = delta,
            shortAvgDeltaMgdl = shortAvgDelta,
            filtered          = true
        )
    }

    // ── Weight initialisation ─────────────────────────────────────────────────

    private fun initWeights() {
        Wm[0] = lambda / (n + lambda)
        Wc[0] = lambda / (n + lambda) + (1.0 - alpha * alpha + beta)
        val w = 1.0 / (2.0 * (n + lambda))
        for (i in 1 until 2 * n + 1) {
            Wm[i] = w
            Wc[i] = w
        }
    }

    // ── Segment detection ─────────────────────────────────────────────────────
    // readings are newest-first; segments contain index ranges into that array.
    // Each IntRange is [newestIdx .. oldestIdx] inclusive within the segment.

    private fun findSegments(readings: List<Pair<Long, Double>>): List<IntRange> {
        val segments = mutableListOf<IntRange>()
        var segStart = 0   // newest index of current segment

        for (i in 0 until readings.size - 1) {
            val dtMin = (readings[i].first - readings[i + 1].first) / 60_000.0
            val isError = readings[i].second <= 38.0
            if (isError || dtMin > MAJOR_GAP_MINUTES || dtMin < MIN_INTERVAL_MINUTES) {
                // Close segment if it has at least 2 points
                if (i - segStart >= 1) segments.add(segStart..i)
                segStart = i + 1
            }
        }
        // Close final segment
        val last = readings.size - 1
        if (last - segStart >= 1) segments.add(segStart..last)

        return segments
    }

    // ── Forward pass + RTS backward smoother for one segment ─────────────────
    // Segment indices are into the newest-first readings array.
    // We process oldest→newest (reverse order) for the forward filter,
    // then apply RTS smoother newest→oldest.

    private fun processSegment(
        readings: List<Pair<Long, Double>>,
        smoothed: DoubleArray,
        newestIdx: Int,
        oldestIdx:  Int
    ) {
        val segLen = oldestIdx - newestIdx + 1   // number of points in segment

        // Arrays for RTS smoother (indexed 0=oldest within segment)
        val xFwd = Array(segLen) { DoubleArray(n) }   // forward filtered states
        val PFwd = Array(segLen) { DoubleArray(n * n) } // forward filtered covariances

        // ── Forward pass: oldest → newest ─────────────────────────────────────
        // initialise from oldest point in segment
        val oldestReading = readings[oldestIdx]
        val x = doubleArrayOf(oldestReading.second, 0.0)
        val P = doubleArrayOf(16.0, 0.0, 0.0, 1.0)

        // Seed rate from oldest two points if possible
        if (oldestIdx < readings.size - 1) {
            val dtMin = (readings[oldestIdx].first - readings[oldestIdx + 1].first) / 60_000.0
            if (dtMin in MIN_INTERVAL_MINUTES..MAJOR_GAP_MINUTES) {
                val rawRate = (readings[oldestIdx].second - readings[oldestIdx + 1].second) / dtMin
                x[1] = rawRate.coerceIn(-MAX_RATE_MGDL_PER_MIN, MAX_RATE_MGDL_PER_MIN)
            }
        }

        // Store initial state (oldest point)
        xFwd[0][0] = x[0]; xFwd[0][1] = x[1]
        PFwd[0]    = P.copyOf()

        // Iterate from second-oldest to newest
        for (k in 1 until segLen) {
            val readingIdx = oldestIdx - k   // newest-first array index
            val prevIdx    = oldestIdx - k + 1
            val dtMin = (readings[readingIdx].first - readings[prevIdx].first) / 60_000.0
                .coerceIn(MIN_INTERVAL_MINUTES, MAJOR_GAP_MINUTES)

            // Predict
            val (xPred, PPred) = predict(x, P, dtMin)

            // Update
            update(xPred, PPred, readings[readingIdx].second, x, P)

            // Store
            xFwd[k][0] = x[0]; xFwd[k][1] = x[1]
            PFwd[k]    = P.copyOf()
        }

        // ── RTS Backward smoother: newest → oldest ────────────────────────────
        // Write forward-filtered newest value as initial smoothed estimate
        smoothed[newestIdx] = xFwd[segLen - 1][0]

        val xS = xFwd[segLen - 1].copyOf()  // smoothed state, starts at newest
        val PS = PFwd[segLen - 1].copyOf()  // smoothed covariance

        for (k in segLen - 2 downTo 0) {
            val readingIdx = oldestIdx - k
            val prevIdx    = oldestIdx - k - 1
            val dtMin = (readings[readingIdx].first - readings[prevIdx].first) / 60_000.0
                .coerceIn(MIN_INTERVAL_MINUTES, MAJOR_GAP_MINUTES)

            // Predicted state and covariance from forward state at k
            val xK = xFwd[k]
            val PK = PFwd[k]
            val (xPred, PPred) = predict(xK, PK, dtMin)

            // RTS gain: G = PK * F^T * PPred^{-1}  (scalar simplification for 2×2)
            // F = state transition Jacobian = [[1, dt],[0, damping]]
            // We compute G numerically: G = PK * F^T * inv(PPred)
            val G = rtsGain(PK, PPred, dtMin)

            // Smoothed state: xS_k = xK + G * (xS_{k+1} - xPred)
            val innov0 = xS[0] - xPred[0]
            val innov1 = xS[1] - xPred[1]
            val newXS0 = xK[0] + G[0] * innov0 + G[1] * innov1
            val newXS1 = xK[1] + G[2] * innov0 + G[3] * innov1
            xS[0] = newXS0.coerceIn(39.0, 400.0)
            xS[1] = newXS1.coerceIn(-MAX_RATE_MGDL_PER_MIN, MAX_RATE_MGDL_PER_MIN)

            // Smoothed covariance: PS_k = PK + G * (PS_{k+1} - PPred) * G^T
            // (We only need the glucose variance diagonal for output quality info,
            //  but we maintain the full matrix for correct RTS propagation.)
            val dP00 = PS[0] - PPred[0]; val dP01 = PS[1] - PPred[1]
            val dP10 = PS[2] - PPred[2]; val dP11 = PS[3] - PPred[3]
            PS[0] = PK[0] + G[0] * (dP00 * G[0] + dP01 * G[2]) + G[1] * (dP10 * G[0] + dP11 * G[2])
            PS[1] = PK[1] + G[0] * (dP00 * G[1] + dP01 * G[3]) + G[1] * (dP10 * G[1] + dP11 * G[3])
            PS[2] = PK[2] + G[2] * (dP00 * G[0] + dP01 * G[2]) + G[3] * (dP10 * G[0] + dP11 * G[2])
            PS[3] = PK[3] + G[2] * (dP00 * G[1] + dP01 * G[3]) + G[3] * (dP10 * G[1] + dP11 * G[3])
            PS[0] = max(PS[0], 0.1);  PS[3] = max(PS[3], 0.001)

            // Write smoothed glucose back to output array
            val outIdx = oldestIdx - k   // newest-first output index for this point
            smoothed[outIdx] = xS[0]
        }
        // Write oldest point
        smoothed[oldestIdx] = xFwd[0][0]
    }

    // ── UKF Predict step ─────────────────────────────────────────────────────

    private fun predict(x: DoubleArray, P: DoubleArray, dtMin: Double): Pair<DoubleArray, DoubleArray> {
        val sigma = generateSigmaPoints(x, P)

        // Propagate each sigma point through f(x) = [G + Ġ·dt, Ġ·damping]
        val RATE_DAMPING = 0.98  // slight decay per step keeps long predictions stable
        val xPropagated = Array(2 * n + 1) { i ->
            doubleArrayOf(
                sigma[i][0] + sigma[i][1] * dtMin,
                sigma[i][1] * RATE_DAMPING
            )
        }

        // Predicted mean
        val xPred = DoubleArray(n)
        for (i in 0 until 2 * n + 1) {
            xPred[0] += Wm[i] * xPropagated[i][0]
            xPred[1] += Wm[i] * xPropagated[i][1]
        }
        xPred[0] = xPred[0].coerceIn(39.0, 400.0)
        xPred[1] = xPred[1].coerceIn(-MAX_RATE_MGDL_PER_MIN, MAX_RATE_MGDL_PER_MIN)

        // Predicted covariance + Q
        val PPred = DoubleArray(n * n)
        for (i in 0 until 2 * n + 1) {
            val d0 = xPropagated[i][0] - xPred[0]
            val d1 = xPropagated[i][1] - xPred[1]
            PPred[0] += Wc[i] * d0 * d0
            PPred[1] += Wc[i] * d0 * d1
            PPred[2] += Wc[i] * d1 * d0
            PPred[3] += Wc[i] * d1 * d1
        }
        PPred[0] = (PPred[0] + Q[0]).coerceAtMost(MAX_GLUCOSE_VARIANCE)
        PPred[1] += Q[1]
        PPred[2] += Q[2]
        PPred[3] = (PPred[3] + Q[3]).coerceAtMost(MAX_RATE_VARIANCE)

        return Pair(xPred, PPred)
    }

    // ── UKF Update step ──────────────────────────────────────────────────────

    private fun update(xPred: DoubleArray, PPred: DoubleArray, z: Double, xOut: DoubleArray, POut: DoubleArray) {
        val sigma = generateSigmaPoints(xPred, PPred)

        // Measurement prediction: h(x) = G (first state element)
        var zPred = 0.0
        for (i in 0 until 2 * n + 1) zPred += Wm[i] * sigma[i][0]

        // Innovation covariance Pzz and cross-covariance Pxz
        var Pzz = R
        val Pxz = DoubleArray(n)
        for (i in 0 until 2 * n + 1) {
            val dz = sigma[i][0] - zPred
            val d0 = sigma[i][0] - xPred[0]
            val d1 = sigma[i][1] - xPred[1]
            Pzz   += Wc[i] * dz * dz
            Pxz[0] += Wc[i] * d0 * dz
            Pxz[1] += Wc[i] * d1 * dz
        }

        if (Pzz < 1e-6) {
            xOut[0] = xPred[0]; xOut[1] = xPred[1]
            POut[0] = PPred[0]; POut[1] = PPred[1]; POut[2] = PPred[2]; POut[3] = PPred[3]
            return
        }

        // Kalman gain K = Pxz / Pzz
        val K0 = Pxz[0] / Pzz
        val K1 = Pxz[1] / Pzz

        // State update
        val innov  = z - zPred
        xOut[0] = (xPred[0] + K0 * innov).coerceIn(39.0, 400.0)
        xOut[1] = (xPred[1] + K1 * innov).coerceIn(-MAX_RATE_MGDL_PER_MIN, MAX_RATE_MGDL_PER_MIN)

        // Covariance update P = P̄ - K·Pzz·Kᵀ
        POut[0] = max(PPred[0] - K0 * Pzz * K0, 0.1)
        POut[1] = PPred[1] - K0 * Pzz * K1
        POut[2] = PPred[2] - K1 * Pzz * K0
        POut[3] = max(PPred[3] - K1 * Pzz * K1, 0.001)
    }

    // ── RTS smoother gain ────────────────────────────────────────────────────
    // G = PK · Fᵀ · PPred⁻¹  (all 2×2)
    // F = [[1, dt],[0, damping]]  →  Fᵀ = [[1,0],[dt, damping]]

    private fun rtsGain(PK: DoubleArray, PPred: DoubleArray, dtMin: Double): DoubleArray {
        val damping = 0.98

        // PK · Fᵀ
        // PK = [[p00,p01],[p10,p11]]
        // Fᵀ = [[1,  0  ],[dt, 0.98]]
        val PF00 = PK[0] * 1.0 + PK[1] * dtMin
        val PF01 = PK[0] * 0.0 + PK[1] * damping
        val PF10 = PK[2] * 1.0 + PK[3] * dtMin
        val PF11 = PK[2] * 0.0 + PK[3] * damping

        // PPred⁻¹ (2×2 analytical inverse)
        val det = PPred[0] * PPred[3] - PPred[1] * PPred[2]
        val safedet = if (det.isNaN() || det < 1e-9) 1e-9 else det
        val inv00 =  PPred[3] / safedet
        val inv01 = -PPred[1] / safedet
        val inv10 = -PPred[2] / safedet
        val inv11 =  PPred[0] / safedet

        // G = (PK·Fᵀ) · PPred⁻¹
        return doubleArrayOf(
            PF00 * inv00 + PF01 * inv10,
            PF00 * inv01 + PF01 * inv11,
            PF10 * inv00 + PF11 * inv10,
            PF10 * inv01 + PF11 * inv11
        )
    }

    // ── Sigma point generation (Merwe's scaled formulation) ──────────────────

    private fun generateSigmaPoints(x: DoubleArray, P: DoubleArray): Array<DoubleArray> {
        val sqrtP = choleskyL(P)
        return Array(2 * n + 1) { i ->
            when {
                i == 0     -> x.copyOf()
                i <= n     -> doubleArrayOf(x[0] + gamma * sqrtP[(i-1)*2],     x[1] + gamma * sqrtP[(i-1)*2+1])
                else       -> doubleArrayOf(x[0] - gamma * sqrtP[(i-n-1)*2],   x[1] - gamma * sqrtP[(i-n-1)*2+1])
            }
        }
    }

    // ── Cholesky decomposition (2×2 analytical) ───────────────────────────────
    // Returns L in column-major layout matching tsunami's implementation.

    private fun choleskyL(P: DoubleArray): DoubleArray {
        val a = P[0]; val b = (P[1] + P[2]) / 2.0; val d = P[3]
        val l11 = sqrt(max(a, 1e-9))
        val l21 = b / l11
        val disc = d - l21 * l21
        val l22 = if (disc < -1e-9) sqrt(max(d, 0.01)) else sqrt(max(disc, 1e-9))
        return doubleArrayOf(l11, l21, 0.0, l22)
    }

    // ── Delta computation ────────────────────────────────────────────────────
    // Compute 5-min equivalent delta from smoothed values.
    // Finds the reading closest to 5 minutes back and scales the BG difference.

    private fun computeDelta(
        readings: List<Pair<Long, Double>>,
        smoothed: DoubleArray,
        lookback: Int
    ): Double {
        if (readings.size <= lookback) return 0.0
        val dtMin = (readings[0].first - readings[lookback].first) / 60_000.0
        if (dtMin < MIN_INTERVAL_MINUTES) return 0.0
        val rawDelta = smoothed[0] - smoothed[lookback]
        // Normalise to 5-min equivalent
        return rawDelta * (5.0 / dtMin)
    }

    // Compute short average delta: mean of up to maxDeltas consecutive 5-min deltas.

    private fun computeShortAvgDelta(
        readings: List<Pair<Long, Double>>,
        smoothed: DoubleArray,
        maxDeltas: Int
    ): Double {
        val deltas = mutableListOf<Double>()
        val limit = min(maxDeltas, readings.size - 1)
        for (i in 0 until limit) {
            val dtMin = (readings[i].first - readings[i + 1].first) / 60_000.0
            if (dtMin < MIN_INTERVAL_MINUTES || dtMin > MAJOR_GAP_MINUTES) break
            deltas.add((smoothed[i] - smoothed[i + 1]) * (5.0 / dtMin))
        }
        return if (deltas.isEmpty()) 0.0 else deltas.average()
    }
}