package com.discount152.aeolus.domain.weather

import com.discount152.aeolus.domain.confidence.ConfidenceEngine
import com.discount152.aeolus.domain.models.StabilitySignal

object StabilityAnalyzer {
    const val MAX_SNAPSHOTS = 8

    fun signalsForPrecipHistory(mmSeries: List<Double>): List<StabilitySignal> {
        if (mmSeries.size < 2) return emptyList()
        val latest = mmSeries.last()
        val previous = mmSeries[mmSeries.lastIndex - 1]
        val delta = latest - previous
        val signals = mutableListOf<StabilitySignal>()
        when {
            delta > 3.0 -> signals.add(StabilitySignal("expected rainfall increasing", "up"))
            delta < -3.0 -> signals.add(StabilitySignal("expected rainfall decreasing", "down"))
        }
        val riskPrev = previous > ConfidenceEngine.PRECIP_MEMBER_THRESHOLD_MM
        val riskNow = latest > ConfidenceEngine.PRECIP_MEMBER_THRESHOLD_MM
        when {
            !riskPrev && riskNow -> signals.add(StabilitySignal("rain risk increasing", "up"))
            riskPrev && !riskNow -> signals.add(StabilitySignal("rain risk decreasing", "down"))
        }
        return signals
    }

    fun signalsForTempHistory(cSeries: List<Double>): List<StabilitySignal> {
        if (cSeries.size < 2) return emptyList()
        val delta = cSeries.last() - cSeries[cSeries.lastIndex - 1]
        return if (kotlin.math.abs(delta) >= 2.0) {
            listOf(StabilitySignal("temperature trend shifting", if (delta > 0) "up" else "down"))
        } else emptyList()
    }

    fun signalsForConfidenceHistory(scores: List<Int>): List<StabilitySignal> {
        if (scores.size < 2) return emptyList()
        val delta = scores.last() - scores[scores.lastIndex - 1]
        return when {
            delta >= 5 -> listOf(StabilitySignal("confidence improving", "up"))
            delta <= -5 -> listOf(StabilitySignal("confidence deteriorating", "down"))
            else -> emptyList()
        }
    }
}
