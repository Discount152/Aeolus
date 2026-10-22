package com.discount152.aeolus.domain.confidence

import com.discount152.aeolus.domain.models.ConfidenceCategory
import com.discount152.aeolus.domain.models.ConsensusDaily
import com.discount152.aeolus.domain.models.DailyPoint
import com.discount152.aeolus.domain.models.EnsembleMemberSummary
import com.discount152.aeolus.domain.models.ForecastModelId
import com.discount152.aeolus.domain.models.ModelForecast
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class ConfidenceEngineTest {
    private val today = LocalDate.parse("2026-10-22")

    @Test
    fun categoryBands() {
        assertThat(ConfidenceEngine.categoryFor(90)).isEqualTo(ConfidenceCategory.HIGH)
        assertThat(ConfidenceEngine.categoryFor(75)).isEqualTo(ConfidenceCategory.MODERATE)
        assertThat(ConfidenceEngine.categoryFor(55)).isEqualTo(ConfidenceCategory.LOW)
        assertThat(ConfidenceEngine.categoryFor(20)).isEqualTo(ConfidenceCategory.VERY_LOW)
    }

    @Test
    fun firstRun_omitsStabilityAndNotesBuildingHistory() {
        val models = threeCloseModels()
        val consensus = consensusFrom(models)
        val result = ConfidenceEngine.scoreDay(
            date = "2026-10-22",
            today = today,
            models = models,
            consensus = consensus,
            ensemble = EnsembleMemberSummary(
                memberCount = 51,
                membersPredictingPrecipAboveThreshold = 43,
                precipThresholdMm = 5.0,
                temperatureMeanC = 17.0,
                temperatureSpreadC = 1.5,
                precipMeanMm = 12.0,
                precipSpreadMm = 3.0,
                precipP10Mm = 8.0,
                precipP90Mm = 16.0,
            ),
            stabilityScore01 = null,
        )
        assertThat(result.buildingHistory).isTrue()
        assertThat(result.notes).contains("BUILDING FORECAST HISTORY")
        assertThat(result.stabilityWeightUsed).isEqualTo(0.0)
        assertThat(result.score).isAtLeast(50)
    }

    @Test
    fun missingModel_reducesMaxConfidence() {
        val models = listOf(
            closeModel(ForecastModelId.ECMWF, 10.0),
            closeModel(ForecastModelId.ICON, 11.0),
            ModelForecast(ForecastModelId.GFS, available = false),
        )
        val consensus = consensusFrom(models)
        val withTwo = ConfidenceEngine.scoreDay(
            date = "2026-10-22",
            today = today,
            models = models,
            consensus = consensus,
            ensemble = null,
            stabilityScore01 = 1.0,
        )
        assertThat(withTwo.score).isAtMost(88)
        assertThat(withTwo.notes.any { it.contains("ENSEMBLE") || it.contains("deterministic") }).isTrue()
    }

    @Test
    fun leadTime_declinesWithDistance() {
        assertThat(ConfidenceEngine.leadTime01("2026-10-22", today))
            .isGreaterThan(ConfidenceEngine.leadTime01("2026-11-05", today))
    }

    @Test
    fun stabilityFromPrecipHistory() {
        val stable = ConfidenceEngine.stabilityFromPrecipHistory(listOf(12.0, 14.0, 13.0, 13.0))
        val unstable = ConfidenceEngine.stabilityFromPrecipHistory(listOf(2.0, 29.0, 8.0, 35.0))
        assertThat(stable).isGreaterThan(unstable)
    }

    @Test
    fun ensembleMemberPercentage_usesDynamicDenominator() {
        val summary = EnsembleMemberSummary(
            memberCount = 51,
            membersPredictingPrecipAboveThreshold = 43,
            precipThresholdMm = ConfidenceEngine.PRECIP_MEMBER_THRESHOLD_MM,
            temperatureMeanC = null,
            temperatureSpreadC = null,
            precipMeanMm = null,
            precipSpreadMm = null,
            precipP10Mm = null,
            precipP90Mm = null,
        )
        assertThat(summary.membersPredictingPrecipAboveThreshold).isEqualTo(43)
        assertThat(summary.memberCount).isEqualTo(51)
        val agreement = ConfidenceEngine.ensembleAgreement01(summary)
        assertThat(agreement).isNotNull()
        assertThat(agreement!!).isGreaterThan(0.5)
    }

    private fun threeCloseModels() = listOf(
        closeModel(ForecastModelId.ECMWF, 12.0),
        closeModel(ForecastModelId.ICON, 13.0),
        closeModel(ForecastModelId.GFS, 11.5),
    )

    private fun closeModel(id: ForecastModelId, precip: Double) =
        ModelForecast(
            model = id,
            available = true,
            daily = listOf(
                DailyPoint(
                    date = "2026-10-22",
                    tempMaxC = 18.0,
                    tempMinC = 10.0,
                    precipitationSumMm = precip,
                    gustMaxKmh = 30.0,
                    windSpeedMaxKmh = 20.0,
                ),
            ),
        )

    private fun consensusFrom(models: List<ModelForecast>): ConsensusDaily {
        val available = models.filter { it.available }.map { it.model }
        return ConsensusDaily(
            date = "2026-10-22",
            weatherCode = 61,
            tempMaxC = 18.0,
            tempMinC = 10.0,
            precipitationSumMm = 12.0,
            precipitationProbabilityMax = 60,
            windSpeedMaxKmh = 20.0,
            gustMaxKmh = 30.0,
            sunriseIso = null,
            sunsetIso = null,
            availableModels = available,
            missingModels = ForecastModelId.entries.filter { it !in available },
        )
    }
}
