package com.discount152.aeolus.domain.confidence

import com.discount152.aeolus.domain.consensus.ConsensusEngine
import com.discount152.aeolus.domain.models.ConfidenceBreakdown
import com.discount152.aeolus.domain.models.ConfidenceCategory
import com.discount152.aeolus.domain.models.ConsensusDaily
import com.discount152.aeolus.domain.models.EnsembleMemberSummary
import com.discount152.aeolus.domain.models.ForecastModelId
import com.discount152.aeolus.domain.models.ModelForecast
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

object ConfidenceEngine {
    const val W_DETERMINISTIC = 0.40
    const val W_ENSEMBLE = 0.35
    const val W_LEAD = 0.15
    const val W_STABILITY = 0.10

    /** Precipitation threshold (mm) for ensemble member counting. */
    const val PRECIP_MEMBER_THRESHOLD_MM = 5.0

    fun categoryFor(score: Int): ConfidenceCategory =
        when {
            score >= 85 -> ConfidenceCategory.HIGH
            score >= 70 -> ConfidenceCategory.MODERATE
            score >= 50 -> ConfidenceCategory.LOW
            else -> ConfidenceCategory.VERY_LOW
        }

    fun scoreDay(
        date: String,
        today: LocalDate,
        models: List<ModelForecast>,
        consensus: ConsensusDaily,
        ensemble: EnsembleMemberSummary?,
        stabilityScore01: Double?, // null = no history
    ): ConfidenceBreakdown {
        val notes = mutableListOf<String>()
        val availableCount = consensus.availableModels.size.coerceAtMost(3)
        if (availableCount < 3) {
            notes.add("Only $availableCount deterministic model(s) available")
        }
        if (ensemble == null) {
            notes.add("ENSEMBLE DATA UNAVAILABLE")
        }

        val det = deterministicAgreement01(models, date)
        val ens = ensembleAgreement01(ensemble)
        val lead = leadTime01(date, today)
        val hasStability = stabilityScore01 != null
        if (!hasStability) {
            notes.add("BUILDING FORECAST HISTORY")
        }

        var wDet = W_DETERMINISTIC
        var wEns = W_ENSEMBLE
        var wLead = W_LEAD
        var wStab = if (hasStability) W_STABILITY else 0.0

        if (ensemble == null) {
            wEns = 0.0
        }
        val weightSum = wDet + wEns + wLead + wStab
        wDet /= weightSum
        wEns /= weightSum
        wLead /= weightSum
        wStab /= weightSum

        var combined =
            det * wDet +
                (ens ?: 0.0) * wEns +
                lead * wLead +
                (stabilityScore01 ?: 0.0) * wStab

        // Cap max confidence when models missing
        val maxCap = when (availableCount) {
            3 -> 1.0
            2 -> 0.88
            1 -> 0.70
            else -> 0.40
        }
        combined = combined.coerceAtMost(maxCap)

        val score = (combined * 100.0).roundToInt().coerceIn(0, 100)
        return ConfidenceBreakdown(
            score = score,
            category = categoryFor(score),
            deterministicWeightUsed = wDet,
            ensembleWeightUsed = wEns,
            leadTimeWeightUsed = wLead,
            stabilityWeightUsed = wStab,
            buildingHistory = !hasStability,
            notes = notes,
        )
    }

    fun deterministicAgreement01(models: List<ModelForecast>, date: String): Double {
        val available = models.filter { it.available }
        val temps = available.mapNotNull { m -> m.daily.firstOrNull { it.date == date }?.tempMaxC }
        val precips = available.mapNotNull { m -> m.daily.firstOrNull { it.date == date }?.precipitationSumMm }
        val winds = available.mapNotNull { m -> m.daily.firstOrNull { it.date == date }?.gustMaxKmh
            ?: m.daily.firstOrNull { it.date == date }?.windSpeedMaxKmh }

        if (temps.isEmpty() && precips.isEmpty()) return 0.5

        val tDisp = ConsensusEngine.normalizedDispersion(temps, scale = 8.0)
        val pDisp = ConsensusEngine.normalizedDispersion(precips, scale = 15.0)
        val wDisp = ConsensusEngine.normalizedDispersion(winds, scale = 25.0)
        val agreement = 1.0 - (tDisp * 0.4 + pDisp * 0.4 + wDisp * 0.2)
        return agreement.coerceIn(0.0, 1.0)
    }

    fun ensembleAgreement01(ensemble: EnsembleMemberSummary?): Double? {
        if (ensemble == null || ensemble.memberCount <= 0) return null
        val frac = ensemble.membersPredictingPrecipAboveThreshold.toDouble() / ensemble.memberCount
        // Agreement high when members cluster (frac near 0 or 1) and spread is small
        val cluster = 1.0 - (4.0 * frac * (1.0 - frac)) // 1 at 0/1, 0 at 0.5
        val tempSpreadPen = ((ensemble.temperatureSpreadC ?: 0.0) / 10.0).coerceIn(0.0, 1.0)
        val precipSpreadPen = ((ensemble.precipSpreadMm ?: 0.0) / 20.0).coerceIn(0.0, 1.0)
        return (cluster * 0.5 + (1.0 - tempSpreadPen) * 0.25 + (1.0 - precipSpreadPen) * 0.25)
            .coerceIn(0.0, 1.0)
    }

    fun leadTime01(date: String, today: LocalDate): Double {
        val target = runCatching { LocalDate.parse(date) }.getOrNull() ?: return 0.5
        val days = ChronoUnit.DAYS.between(today, target).toInt().coerceAtLeast(0)
        // Gradual decline; day 0 ~1.0, day 14 ~0.45
        return (1.0 - days * 0.04).coerceIn(0.35, 1.0)
    }

    /**
     * Stability from successive precip snapshots for the same date.
     * Returns 0..1 where 1 is stable.
     */
    fun stabilityFromPrecipHistory(mmSeries: List<Double>): Double {
        if (mmSeries.size < 2) return 1.0
        val diffs = mmSeries.zipWithNext { a, b -> kotlin.math.abs(a - b) }
        val meanDiff = diffs.average()
        return (1.0 - meanDiff / 20.0).coerceIn(0.0, 1.0)
    }
}
