package com.discount152.aeolus.domain.weather

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StabilityAnalyzerTest {
    @Test
    fun precipSignals_increasingRainfallAndRisk() {
        val signals = StabilityAnalyzer.signalsForPrecipHistory(listOf(1.0, 12.0))
        assertThat(signals.map { it.label }).contains("expected rainfall increasing")
        assertThat(signals.map { it.label }).contains("rain risk increasing")
    }

    @Test
    fun tempAndConfidenceSignals() {
        assertThat(StabilityAnalyzer.signalsForTempHistory(listOf(10.0, 13.0)).first().label)
            .isEqualTo("temperature trend shifting")
        assertThat(StabilityAnalyzer.signalsForConfidenceHistory(listOf(60, 70)).first().label)
            .isEqualTo("confidence improving")
        assertThat(StabilityAnalyzer.signalsForConfidenceHistory(listOf(70, 60)).first().label)
            .isEqualTo("confidence deteriorating")
    }
}
