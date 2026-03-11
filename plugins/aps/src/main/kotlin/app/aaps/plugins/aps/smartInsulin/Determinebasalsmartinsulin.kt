package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.smartInsulin.MealMode
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * SmartInsulin APS — core basal/SMB determination.
 *
 * Uses stock OpenAPS SMB prediction and dosing math (eventualBG, minPredBG, threshold,
 * insulinReq/2 SMB sizing) so that ISF behaves exactly as users expect: lowering ISF
 * increases both the prediction of BG rise and the dose. Suspend/caution are driven by
 * stock threshold = min_bg - 0.5*(min_bg-40), not a custom lowGuard.
 *
 * SmartInsulin layers on top:
 *   - Meal mode ISF override (via oapsProfile.sens)
 *   - Circadian learning multipliers (basal, ISF, aggressiveness ceiling)
 *   - Rebound window taper (fasting only)
 *   - Activity target raise
 *   - Dawn SMB reduction
 *   - CGM warmup / artefact guard
 */
@Singleton
class DetermineBasalSmartInsulin @Inject constructor(
    private val apsResultProvider: Provider<APSResult>
) {

    private fun Double.toFixed2(): String = DecimalFormat("0.00#").format(round(this, 2))
    private fun Double.withoutZeros(): String = DecimalFormat("0.##").format(this)

    fun round(value: Double, digits: Int): Double {
        if (value.isNaN()) return Double.NaN
        val scale = 10.0.pow(digits.toDouble())
        return Math.round(value * scale) / scale
    }
    fun round(value: Double): Int = value.roundToInt()
    fun round_basal(value: Double): Double = value

    private fun getMaxSafeBasal(profile: OapsProfile): Double =
        min(profile.max_basal, min(profile.max_daily_safety_multiplier * profile.max_daily_basal,
                                   profile.current_basal_safety_multiplier * profile.current_basal))

    fun setTempBasal(_rate: Double, duration: Int, profile: OapsProfile, rT: RT, currentTemp: CurrentTemp): RT {
        val maxSafeBasal = getMaxSafeBasal(profile)
        var rate = _rate.coerceAtLeast(0.0).coerceAtMost(maxSafeBasal)
        val suggestedRate = round_basal(rate)
        if (currentTemp.duration > (duration - 10) && currentTemp.duration <= 120
            && suggestedRate <= currentTemp.rate * 1.2 && suggestedRate >= currentTemp.rate * 0.8
            && duration > 0) {
            rT.reason.append(" ${currentTemp.duration}m left and ${currentTemp.rate.withoutZeros()} ~ req ${suggestedRate.withoutZeros()}U/hr: no temp required")
            return rT
        }
        if (suggestedRate == profile.current_basal) {
            if (profile.skip_neutral_temps) {
                if (currentTemp.duration > 0) {
                    rT.reason.append("Canceling temp — suggested == profile rate")
                    rT.duration = 0; rT.rate = 0.0
                } else {
                    rT.reason.append("No temp — suggested == profile rate")
                }
            } else {
                rT.reason.append("Setting neutral temp ${profile.current_basal}U/hr")
                rT.duration = duration; rT.rate = suggestedRate
            }
        } else {
            rT.duration = duration; rT.rate = suggestedRate
        }
        return rT
    }

    fun determine_basal(
        glucoseStatus:           GlucoseStatus,
        currentTemp:             CurrentTemp,
        iobArray:                Array<IobTotal>,
        oapsProfile:             OapsProfile,
        mealData:                MealData,
        profile:                 Profile,
        mealMode:                MealMode,
        maxSmbU:                 Double,
        aggressiveness:          Double,
        basalMultiplier:         Double,
        microBolusAllowed:       Boolean,
        inReboundWindow:         Boolean,
        msSinceLastSuspend:      Long,
        currentTime:             Long,
        isTempTarget:            Boolean,
        profileTargetMgdl:       Double,
        dawnWindowStartHour:     Int,
        dawnWindowEndHour:       Int,
        dawnSmbReduction:        Double,
        bgWentLow:               Boolean,
        activityLevel:           ActivityMonitor.ActivityLevel,
        activityTargetOffsetMmol: Double,
        cgmSmbFraction:          Double,
        cgmDeltaPlausible:       Boolean,
        cgmWarmupReason:         String
    ): APSResult {

        val result = apsResultProvider.get()
        var rT = RT(
            algorithm = APSResult.Algorithm.SMB,
            runningDynamicIsf = false,
            timestamp = currentTime,
            consoleLog = mutableListOf(),
            consoleError = mutableListOf()
        )

        val bg        = glucoseStatus.glucose
        val iob_data  = iobArray[0]
        val sens      = oapsProfile.sens   // ISF mg/dL/U — meal mode override already applied by plugin
        val basal     = round_basal(oapsProfile.current_basal * basalMultiplier)

        // ── Target ────────────────────────────────────────────────────────────
        val activityOffsetMgdl  = activityTargetOffsetMmol * MMOL_TO_MGDL
        var target_bg = if (isTempTarget) oapsProfile.target_bg
        else oapsProfile.target_bg + activityOffsetMgdl
        var min_bg    = oapsProfile.min_bg + (if (!isTempTarget) activityOffsetMgdl else 0.0)
        var max_bg    = oapsProfile.max_bg
        val highTempTargetActive = isTempTarget && target_bg > profileTargetMgdl

        // ── Delta / deviation ─────────────────────────────────────────────────
        val minDelta    = min(glucoseStatus.delta, glucoseStatus.shortAvgDelta)
        val minAvgDelta = min(glucoseStatus.shortAvgDelta, glucoseStatus.longAvgDelta)
        val maxDelta    = max(glucoseStatus.delta, max(glucoseStatus.shortAvgDelta, glucoseStatus.longAvgDelta))

        // BGI: expected BG change from insulin activity alone over 5 mins
        val bgi = round(-iob_data.activity * sens * 5, 2)

        // Deviation: observed minus expected — positive = carbs/UAM pushing BG up
        var deviation = round(30.0 / 5 * (minDelta - bgi))
        if (deviation < 0) {
            deviation = round(30.0 / 5 * (minAvgDelta - bgi))
            if (deviation < 0)
                deviation = round(30.0 / 5 * (glucoseStatus.longAvgDelta - bgi))
        }

        // ── eventualBG: stock naive IOB math + deviation ──────────────────────
        val naive_eventualBG = if (iob_data.iob > 0) round(bg - iob_data.iob * sens, 0)
        else round(bg - iob_data.iob * min(sens, oapsProfile.sens), 0)
        var eventualBG = naive_eventualBG + deviation

        val expectedDelta = run {
            val fiveMinBlocks = (2 * 60) / 5
            round(bgi + (target_bg - eventualBG) / fiveMinBlocks, 1)
        }

        // ── Stock threshold (not a user-configurable low guard) ───────────────
        var threshold = min_bg - 0.5 * (min_bg - 40)
        oapsProfile.lgsThreshold?.let { lgs ->
            if (lgs > threshold) threshold = lgs.toDouble()
        }

        // ── IOB prediction curve (stock 48-tick / 4h) ─────────────────────────
        // ci = current carb/UAM impact on BG (minDelta - bgi)
        val ci  = round(minDelta - bgi, 1)
        val uci = ci

        val csf = sens / oapsProfile.carb_ratio
        val maxCarbAbsorptionRate = 30
        val maxCI = round(maxCarbAbsorptionRate * csf * 5.0 / 60, 1)
        val effectiveCi = if (ci > maxCI) maxCI else ci

        var remainingCATimeMin = 3.0 / 1.0   // sensitivityRatio=1.0 (no autosens)
        val assumedCarbAbsorptionRate = 20
        var remainingCATime = remainingCATimeMin
        if (mealData.carbs != 0.0) {
            remainingCATimeMin = Math.max(remainingCATimeMin, mealData.mealCOB / assumedCarbAbsorptionRate)
            val lastCarbAge = round((currentTime - mealData.lastCarbTime) / 60000.0)
            remainingCATime = round(remainingCATimeMin + 1.5 * lastCarbAge / 60, 1)
        }
        val totalCI   = Math.max(0.0, effectiveCi / 5 * 60 * remainingCATime / 2)
        val totalCA   = totalCI / csf
        val remainingCarbsCap = min(90, oapsProfile.remainingCarbsCap)
        var remainingCarbs = Math.min(remainingCarbsCap.toDouble(), Math.max(0.0, mealData.mealCOB - totalCA))
        val remainingCIpeak = if (remainingCATime > 0) remainingCarbs * csf * 5 / 60 / (remainingCATime / 2) else 0.0

        val slopeFromMaxDeviation = round(mealData.slopeFromMaxDeviation, 2)
        val slopeFromMinDeviation = round(mealData.slopeFromMinDeviation, 2)
        val slopeFromDeviations   = Math.min(slopeFromMaxDeviation, -slopeFromMinDeviation / 3)

        val cid = if (effectiveCi == 0.0) 0.0
        else min(remainingCATime * 60 / 5 / 2, Math.max(0.0, mealData.mealCOB * csf / effectiveCi))
        val acid = Math.max(0.0, mealData.mealCOB * csf / 10)

        var minIOBPredBG  = 999.0; var minCOBPredBG = 999.0; var minUAMPredBG = 999.0
        var minCOBGuardBG = 999.0; var minUAMGuardBG = 999.0
        var minIOBGuardBG = 999.0; var minZTGuardBG  = 999.0
        var maxIOBPredBG  = bg
        var UAMduration   = 0.0
        val insulinPeak5m = (90.0 / 60.0) * 12.0  // 90m peak time

        val IOBpredBGs  = mutableListOf(bg)
        val COBpredBGs  = mutableListOf(bg)
        val UAMpredBGs  = mutableListOf(bg)
        val ZTpredBGs   = mutableListOf(bg)
        val aCOBpredBGs = mutableListOf(bg)

        iobArray.forEach { iobTick ->
            val predBGI   = round(-iobTick.activity * sens * 5, 2)
            val predZTBGI = round(-(iobTick.iobWithZeroTemp?.activity ?: 0.0) * sens * 5, 2)

            val predDev   = ci * (1 - min(1.0, IOBpredBGs.size / (60.0 / 5.0)))
            val iobPredBG = IOBpredBGs.last() + predBGI + predDev
            val ztPredBG  = ZTpredBGs.last() + predZTBGI

            val predCI    = Math.max(0.0, Math.max(0.0, effectiveCi) * (1 - COBpredBGs.size / Math.max(cid * 2, 1.0)))
            val predACI   = Math.max(0.0, 10.0 * (1 - COBpredBGs.size / Math.max(acid * 2, 1.0)))
            val intervals = Math.min(COBpredBGs.size.toDouble(), (remainingCATime * 12) - COBpredBGs.size)
            val remainingCI = Math.max(0.0, if (remainingCATime > 0) intervals / (remainingCATime / 2 * 12) * remainingCIpeak else 0.0)
            val cobPredBG   = COBpredBGs.last() + predBGI + min(0.0, predDev) + predCI + remainingCI
            val acobPredBG  = aCOBpredBGs.last() + predBGI + min(0.0, predDev) + predACI

            val predUCIslope = Math.max(0.0, uci + UAMpredBGs.size * slopeFromDeviations)
            val predUCImax   = Math.max(0.0, uci * (1 - UAMpredBGs.size / Math.max(3.0 * 60 / 5, 1.0)))
            val predUCI      = min(predUCIslope, predUCImax)
            if (predUCI > 0) UAMduration = round((UAMpredBGs.size + 1) * 5.0 / 60, 1)
            val uamPredBG   = UAMpredBGs.last() + predBGI + min(0.0, predDev) + predUCI

            if (IOBpredBGs.size < 48)  IOBpredBGs.add(iobPredBG)
            if (COBpredBGs.size < 48)  COBpredBGs.add(cobPredBG)
            if (aCOBpredBGs.size < 48) aCOBpredBGs.add(acobPredBG)
            if (UAMpredBGs.size < 48)  UAMpredBGs.add(uamPredBG)
            if (ZTpredBGs.size < 48)   ZTpredBGs.add(ztPredBG)

            if (cobPredBG < minCOBGuardBG) minCOBGuardBG = round(cobPredBG).toDouble()
            if (uamPredBG < minUAMGuardBG) minUAMGuardBG = round(uamPredBG).toDouble()
            if (iobPredBG < minIOBGuardBG) minIOBGuardBG = iobPredBG
            if (ztPredBG  < minZTGuardBG)  minZTGuardBG  = round(ztPredBG, 0)

            if (IOBpredBGs.size > insulinPeak5m && iobPredBG < minIOBPredBG) minIOBPredBG = round(iobPredBG, 0)
            if (iobPredBG > maxIOBPredBG) maxIOBPredBG = iobPredBG
            if ((cid != 0.0 || remainingCIpeak > 0) && COBpredBGs.size > insulinPeak5m && cobPredBG < minCOBPredBG)
                minCOBPredBG = round(cobPredBG, 0)
            if (oapsProfile.enableUAM && UAMpredBGs.size > 12 && uamPredBG < minUAMPredBG)
                minUAMPredBG = round(uamPredBG, 0)
        }

        minIOBPredBG = Math.max(39.0, minIOBPredBG)
        minCOBPredBG = Math.max(39.0, minCOBPredBG)
        minUAMPredBG = Math.max(39.0, minUAMPredBG)

        // ── Build predBG lists for graph ──────────────────────────────────────
        val clamp = { list: MutableList<Double> ->
            list.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
        }
        val iobClamped = clamp(IOBpredBGs)
        val ztClamped  = clamp(ZTpredBGs)

        rT.predBGs = Predictions()
        // Trim trailing flat values (stock behaviour)
        val iobTrimmed = iobClamped.toMutableList()
        for (i in iobTrimmed.size - 1 downTo 13)
            if (iobTrimmed[i - 1] != iobTrimmed[i]) break else iobTrimmed.removeAt(iobTrimmed.lastIndex)
        rT.predBGs?.IOB = iobTrimmed.map { it.toInt() }

        val ztTrimmed = ztClamped.toMutableList()
        for (i in ztTrimmed.size - 1 downTo 7)
            if (ztTrimmed[i - 1] >= ztTrimmed[i] || ztTrimmed[i] <= target_bg) break else ztTrimmed.removeAt(ztTrimmed.lastIndex)
        rT.predBGs?.ZT = ztTrimmed.map { it.toInt() }

        var lastCOBpredBG: Double? = null
        var lastUAMpredBG: Double? = null
        if (mealData.mealCOB > 0 && (effectiveCi > 0 || remainingCIpeak > 0)) {
            val cobClamped = clamp(COBpredBGs).toMutableList()
            for (i in cobClamped.size - 1 downTo 13)
                if (cobClamped[i - 1] != cobClamped[i]) break else cobClamped.removeAt(cobClamped.lastIndex)
            rT.predBGs?.COB = cobClamped.map { it.toInt() }
            lastCOBpredBG = cobClamped.last()
            eventualBG = max(eventualBG, round(cobClamped.last(), 0))
        }
        if (effectiveCi > 0 || remainingCIpeak > 0) {
            if (oapsProfile.enableUAM) {
                val uamClamped = clamp(UAMpredBGs).toMutableList()
                for (i in uamClamped.size - 1 downTo 13)
                    if (uamClamped[i - 1] != uamClamped[i]) break else uamClamped.removeAt(uamClamped.lastIndex)
                rT.predBGs?.UAM = uamClamped.map { it.toInt() }
                lastUAMpredBG = uamClamped.last()
                eventualBG = max(eventualBG, round(uamClamped.last(), 0))
            }
            rT.eventualBG = eventualBG
        }

        // ── minPredBG / avgPredBG / minGuardBG (stock logic) ──────────────────
        val fractionCarbsLeft = if (mealData.carbs > 0) mealData.mealCOB / mealData.carbs else 0.0

        var minZTUAMPredBG = minUAMPredBG
        if (minZTGuardBG < threshold)
            minZTUAMPredBG = (minUAMPredBG + minZTGuardBG) / 2.0
        else if (minZTGuardBG < target_bg) {
            val blendPct = (minZTGuardBG - threshold) / (target_bg - threshold)
            minZTUAMPredBG = (minUAMPredBG + minUAMPredBG * blendPct + minZTGuardBG * (1 - blendPct)) / 2.0
        } else if (minZTGuardBG > minUAMPredBG)
            minZTUAMPredBG = (minUAMPredBG + minZTGuardBG) / 2.0
        minZTUAMPredBG = round(minZTUAMPredBG, 0)

        var minPredBG: Double = round(minIOBPredBG, 0)
        if (mealData.carbs != 0.0) {
            minPredBG = when {
                !oapsProfile.enableUAM && minCOBPredBG < 999 -> round(max(minIOBPredBG, minCOBPredBG), 0)
                minCOBPredBG < 999 -> {
                    val blended = fractionCarbsLeft * minCOBPredBG + (1 - fractionCarbsLeft) * minZTUAMPredBG
                    round(max(minIOBPredBG, max(minCOBPredBG, blended)), 0)
                }
                oapsProfile.enableUAM -> minZTUAMPredBG
                else -> minIOBGuardBG
            }
        } else if (oapsProfile.enableUAM) {
            minPredBG = round(max(minIOBPredBG, minZTUAMPredBG), 0)
        }

        val avgPredBG = when {
            minUAMPredBG < 999 && minCOBPredBG < 999 ->
                round((1 - fractionCarbsLeft) * (lastUAMpredBG ?: minUAMPredBG) + fractionCarbsLeft * (lastCOBpredBG ?: minCOBPredBG), 0)
            minCOBPredBG < 999 -> round(((lastCOBpredBG ?: minCOBPredBG) + IOBpredBGs.last()) / 2.0, 0)
            minUAMPredBG < 999 -> round(((lastUAMpredBG ?: minUAMPredBG) + IOBpredBGs.last()) / 2.0, 0)
            else               -> round(IOBpredBGs.last(), 0)
        }.let { if (minZTGuardBG > it) minZTGuardBG else it }

        minPredBG = min(minPredBG, avgPredBG)
        if (maxCOBPredBG > bg) minPredBG = min(minPredBG, maxIOBPredBG)

        val minGuardBG = round(when {
                                   (cid > 0.0 || remainingCIpeak > 0) && oapsProfile.enableUAM ->
                                       fractionCarbsLeft * minCOBGuardBG + (1 - fractionCarbsLeft) * minUAMGuardBG
                                   cid > 0.0 || remainingCIpeak > 0 -> minCOBGuardBG
                                   oapsProfile.enableUAM -> minUAMGuardBG
                                   else -> minIOBGuardBG
                               }, 0)

        // ── Dawn window SMB reduction ──────────────────────────────────────────
        val cal = java.util.Calendar.getInstance().also { it.timeInMillis = currentTime }
        val currentHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val inDawnWindow = mealMode == MealMode.FASTING && glucoseStatus.delta > 0 && run {
            if (dawnWindowStartHour <= dawnWindowEndHour)
                currentHour in dawnWindowStartHour until dawnWindowEndHour
            else currentHour >= dawnWindowStartHour || currentHour < dawnWindowEndHour
        }
        val dawnFraction = if (inDawnWindow) dawnSmbReduction else 1.0
        val cgmFraction  = if (!cgmDeltaPlausible) 0.0 else cgmSmbFraction

        // ── Rebound taper (fasting only, same logic as before) ────────────────
        val reboundActive = (bgWentLow || inReboundWindow) && mealMode == MealMode.FASTING
        val reboundMins   = if (reboundActive) (msSinceLastSuspend / 60_000.0) else 0.0
        val reboundTaperFraction = when {
            !reboundActive  -> 1.0
            inReboundWindow -> (reboundMins / REBOUND_TAPER_MINS).coerceIn(0.0, 1.0)
            bgWentLow       -> (reboundMins / REBOUND_TAPER_MINS).coerceIn(0.0, 1.0)
            else            -> 1.0
        }
        val reboundSmbAllowed = reboundTaperFraction >= REBOUND_SMB_GATE

        // ── SMB enable (stock logic + our gates) ──────────────────────────────
        var enableSMB = microBolusAllowed && !highTempTargetActive && (
            oapsProfile.enableSMB_always ||
                (oapsProfile.enableSMB_with_COB && mealData.mealCOB > 0) ||
                (oapsProfile.enableSMB_after_carbs && mealData.carbs > 0) ||
                (oapsProfile.enableSMB_with_temptarget && isTempTarget && target_bg < 100)
            )
        if (enableSMB && minGuardBG < threshold) enableSMB = false
        if (maxDelta > 0.20 * bg) enableSMB = false
        if (!reboundSmbAllowed) enableSMB = false
        if (cgmFraction == 0.0) enableSMB = false

        // ── Reason string header ──────────────────────────────────────────────
        val sb = StringBuilder()
        if (cgmWarmupReason.isNotEmpty()) sb.append("$cgmWarmupReason ")
        sb.append("ISF=${String.format(Locale.US, "%.1f", sens / MMOL_TO_MGDL)}mmol ")
        sb.append("basal=%.3f(x%.2f) ".format(Locale.US, oapsProfile.current_basal * basalMultiplier, basalMultiplier))
        sb.append("target=${String.format(Locale.US, "%.1f", target_bg / MMOL_TO_MGDL)}mmol ")
        sb.append("eventualBG=${String.format(Locale.US, "%.1f", eventualBG / MMOL_TO_MGDL)} ")
        sb.append("minPredBG=${String.format(Locale.US, "%.1f", minPredBG / MMOL_TO_MGDL)} ")
        sb.append("minGuardBG=${String.format(Locale.US, "%.1f", minGuardBG / MMOL_TO_MGDL)} ")
        sb.append("threshold=${String.format(Locale.US, "%.1f", threshold / MMOL_TO_MGDL)} ")
        when {
            !reboundActive && (bgWentLow || inReboundWindow) ->
                sb.append("rebound=skipped(mealMode=${mealMode.label}) ")
            inReboundWindow -> sb.append("rebound(elapsed=%.0fmin taper=%.2f) ".format(Locale.US, reboundMins, reboundTaperFraction))
            bgWentLow       -> sb.append("rebound=watching(elapsed=%.0fmin taper=%.2f) ".format(Locale.US, reboundMins, reboundTaperFraction))
        }
        if (activityLevel != ActivityMonitor.ActivityLevel.SEDENTARY)
            sb.append("activity=${activityLevel.label}(+${"%.1f".format(activityTargetOffsetMmol)}mmol) ")
        if (inDawnWindow)
            sb.append("dawn(reduction=${(dawnFraction * 100).toInt()}%) ")
        if (cgmSmbFraction < 1.0 && cgmDeltaPlausible)
            sb.append("cgm=warmup(smb=${(cgmFraction * 100).toInt()}%) ")

        rT.reason.append(sb)
        rT.COB = mealData.mealCOB
        rT.IOB = iob_data.iob
        rT.bg = bg
        rT.tick = if (glucoseStatus.delta > -0.5) "+${round(glucoseStatus.delta)}" else round(glucoseStatus.delta).toString()
        rT.eventualBG = eventualBG
        rT.targetBG = target_bg

        // ── LGS suspend ───────────────────────────────────────────────────────
        val lgsThreshold = oapsProfile.lgsThreshold?.toDouble() ?: 0.0
        if (lgsThreshold > 0 && bg < lgsThreshold) {
            rT.reason.append("LGS_SUSPEND bg=${String.format(Locale.US, "%.1f", bg / MMOL_TO_MGDL)} < lgs=${String.format(Locale.US, "%.1f", lgsThreshold / MMOL_TO_MGDL)}")
            result.with(setTempBasal(0.0, 30, oapsProfile, rT, currentTemp))
            return result
        }

        // ── Stock suspend: minGuardBG < threshold ─────────────────────────────
        if (bg < threshold || minGuardBG < threshold) {
            rT.reason.append("minGuardBG ${String.format(Locale.US, "%.1f", minGuardBG / MMOL_TO_MGDL)} < threshold ${String.format(Locale.US, "%.1f", threshold / MMOL_TO_MGDL)}")
            val bgUndershoot    = target_bg - minGuardBG
            val worstCaseInsReq = bgUndershoot / sens
            var durationReq     = round(60 * worstCaseInsReq / oapsProfile.current_basal).coerceIn(30, 120)
            durationReq         = (durationReq / 30) * 30
            result.with(setTempBasal(0.0, durationReq, oapsProfile, rT, currentTemp))
            return result
        }

        // ── eventualBG below min — reduce/zero temp ───────────────────────────
        if (eventualBG < min_bg) {
            rT.reason.append("eventualBG ${String.format(Locale.US, "%.1f", eventualBG / MMOL_TO_MGDL)} < min_bg")
            if (minDelta > expectedDelta && minDelta > 0) {
                rT.reason.append(", but rising faster than expected")
                result.with(setTempBasal(basal, 30, oapsProfile, rT, currentTemp))
                return result
            }
            var insulinReq = 2 * min(0.0, (eventualBG - target_bg) / sens)
            if (minDelta < 0 && minDelta > expectedDelta)
                insulinReq = round(insulinReq * (minDelta / expectedDelta), 2)
            val rate = round_basal(basal + 2 * insulinReq)
            result.with(setTempBasal(rate, 30, oapsProfile, rT, currentTemp))
            return result
        }

        // ── eventualBG above target — dose ────────────────────────────────────
        if (iob_data.iob > oapsProfile.max_iob) {
            rT.reason.append("IOB ${round(iob_data.iob, 2)} > max_iob ${oapsProfile.max_iob}")
            result.with(setTempBasal(basal, 30, oapsProfile, rT, currentTemp))
            return result
        }

        // insulinReq: stock (minPredBG vs eventualBG, lower of the two) / sens
        var insulinReq = round((min(minPredBG, eventualBG) - target_bg) / sens, 2)
        if (insulinReq > oapsProfile.max_iob - iob_data.iob)
            insulinReq = oapsProfile.max_iob - iob_data.iob
        insulinReq = round(insulinReq, 3)
        rT.insulinReq = insulinReq

        var smbOut = 0.0
        if (microBolusAllowed && enableSMB && bg > threshold) {
            // Stock SMB sizing: insulinReq/2 capped at maxSMBBasalMinutes of basal
            // We override the cap with our flat maxSmbU pref
            val mealInsulinReq = round(mealData.mealCOB / oapsProfile.carb_ratio, 3)
            val maxBasalBolus  = if (iob_data.iob > mealInsulinReq && iob_data.iob > 0)
                round(oapsProfile.current_basal * oapsProfile.maxUAMSMBBasalMinutes / 60, 1)
            else
                round(oapsProfile.current_basal * oapsProfile.maxSMBBasalMinutes / 60, 1)
            val bolusStep  = oapsProfile.bolus_increment.takeIf { it > 0.0 } ?: 0.05
            val roundSMBTo = 1.0 / bolusStep
            // Apply aggressiveness to insulinReq before halving (>1.0 = more, <1.0 = less)
            val scaledReq  = insulinReq * aggressiveness
            var microBolus = Math.floor(min(scaledReq / 2.0, maxBasalBolus) * roundSMBTo) / roundSMBTo
            // Cap at user's maxSmb, then apply dawn/cgm fractions
            microBolus = microBolus.coerceAtMost(maxSmbU) * dawnFraction * cgmFraction
            // Apply rebound taper to SMB
            if (reboundSmbAllowed) microBolus *= reboundTaperFraction
            microBolus = Math.floor(microBolus * roundSMBTo) / roundSMBTo  // re-round after taper
            if (microBolus >= bolusStep) smbOut = microBolus
        }

        // TBR: stock high-temp calculation, scaled by rebound taper
        val rate     = round_basal((basal + 2 * insulinReq) * reboundTaperFraction)
        val tbrRate  = rate.coerceAtMost(getMaxSafeBasal(oapsProfile))

        rT.reason.append("insulinReq=$insulinReq smb=${"%.3f".format(smbOut)} tbr=${"%.3f".format(tbrRate)}")

        rT.units = smbOut.takeIf { it > 0.0 }
        result.with(setTempBasal(tbrRate, 30, oapsProfile, rT, currentTemp))
        return result
    }

    companion object {
        private const val MMOL_TO_MGDL        = 18.0
        private const val REBOUND_TAPER_MINS  = 60.0
        private const val REBOUND_SMB_GATE    = 0.5
    }
}