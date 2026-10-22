package com.discount152.aeolus.domain.weather

import com.discount152.aeolus.domain.confidence.ConfidenceEngine
import com.discount152.aeolus.domain.consensus.ConsensusEngine
import com.discount152.aeolus.domain.models.EnsembleMemberSummary
import kotlin.math.sqrt

object EnsembleAnalyzer {
    /**
     * @param memberDailyPrecipMm one value per ensemble member for a given day
     * @param memberTempMaxC optional temperatures per member
     */
    fun summarizeDay(
        memberDailyPrecipMm: List<Double>,
        memberTempMaxC: List<Double> = emptyList(),
        precipThresholdMm: Double = ConfidenceEngine.PRECIP_MEMBER_THRESHOLD_MM,
    ): EnsembleMemberSummary {
        val n = memberDailyPrecipMm.size
        val above = memberDailyPrecipMm.count { it > precipThresholdMm }
        val precipMean = if (n > 0) memberDailyPrecipMm.average() else null
        val precipSpread = if (n > 1) stdDev(memberDailyPrecipMm) else 0.0
        val sorted = memberDailyPrecipMm.sorted()
        val p10 = percentile(sorted, 0.10)
        val p90 = percentile(sorted, 0.90)
        val tMean = if (memberTempMaxC.isNotEmpty()) memberTempMaxC.average() else null
        val tSpread = if (memberTempMaxC.size > 1) stdDev(memberTempMaxC) else null
        return EnsembleMemberSummary(
            memberCount = n,
            membersPredictingPrecipAboveThreshold = above,
            precipThresholdMm = precipThresholdMm,
            temperatureMeanC = tMean,
            temperatureSpreadC = tSpread,
            precipMeanMm = precipMean,
            precipSpreadMm = precipSpread,
            precipP10Mm = p10,
            precipP90Mm = p90,
        )
    }

    fun stdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val varSum = values.sumOf { (it - mean) * (it - mean) }
        return sqrt(varSum / (values.size - 1))
    }

    fun percentile(sorted: List<Double>, p: Double): Double? {
        if (sorted.isEmpty()) return null
        if (sorted.size == 1) return sorted[0]
        val idx = p * (sorted.size - 1)
        val lo = idx.toInt()
        val hi = (lo + 1).coerceAtMost(sorted.lastIndex)
        val frac = idx - lo
        return sorted[lo] * (1 - frac) + sorted[hi] * frac
    }

    fun median(values: List<Double>): Double? = ConsensusEngine.median(values)
}
