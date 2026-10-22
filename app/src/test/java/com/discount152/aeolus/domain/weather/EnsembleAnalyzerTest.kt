package com.discount152.aeolus.domain.weather

import com.discount152.aeolus.domain.confidence.ConfidenceEngine
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EnsembleAnalyzerTest {
    @Test
    fun summarizeDay_dynamicMemberCount() {
        val precip = List(51) { i -> if (i < 43) 8.0 else 1.0 }
        val summary = EnsembleAnalyzer.summarizeDay(precip)
        assertThat(summary.memberCount).isEqualTo(51)
        assertThat(summary.membersPredictingPrecipAboveThreshold).isEqualTo(43)
        assertThat(summary.precipThresholdMm).isEqualTo(ConfidenceEngine.PRECIP_MEMBER_THRESHOLD_MM)
        assertThat(summary.precipP10Mm).isNotNull()
        assertThat(summary.precipP90Mm).isNotNull()
    }

    @Test
    fun percentile_edges() {
        assertThat(EnsembleAnalyzer.percentile(emptyList(), 0.5)).isNull()
        assertThat(EnsembleAnalyzer.percentile(listOf(5.0), 0.5)).isEqualTo(5.0)
        assertThat(EnsembleAnalyzer.percentile(listOf(0.0, 10.0), 0.5)).isWithin(1e-9).of(5.0)
    }
}
