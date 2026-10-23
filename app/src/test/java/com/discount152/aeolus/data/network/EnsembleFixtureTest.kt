package com.discount152.aeolus.data.network

import com.discount152.aeolus.data.repository.WeatherRepository
import com.discount152.aeolus.domain.confidence.ConfidenceEngine
import com.discount152.aeolus.domain.consensus.ConsensusEngine
import com.discount152.aeolus.domain.models.DailyPoint
import com.discount152.aeolus.domain.models.ForecastModelId
import com.discount152.aeolus.domain.models.GeoLocation
import com.discount152.aeolus.domain.models.ModelForecast
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.time.LocalDate

class EnsembleFixtureTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun load(name: String): String =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream(name)).bufferedReader().readText()

    private fun firstDate(raw: String): String {
        val times = json.parseToJsonElement(raw).jsonObject["daily"]!!.jsonObject["time"] as JsonArray
        return times.first().jsonPrimitive.content
    }

    @Test
    fun amsterdamFixture_parsesMembersDynamicallyAndAggregates() {
        val raw = load("open_meteo_ecmwf_ensemble_daily.json")
        val daily = json.parseToJsonElement(raw).jsonObject["daily"]!!.jsonObject
        val keys = ForecastMapper.discoverMemberKeys(daily, "precipitation_sum")
        assertThat(keys.size).isAtLeast(40)
        assertThat(keys.first()).isEqualTo("precipitation_sum_member01")

        val date = firstDate(raw)
        val summary = ForecastMapper.parseEnsembleDay(raw, date)
        assertThat(summary).isNotNull()
        // control + members discovered dynamically (not hardcoded 51)
        assertThat(summary!!.memberCount).isEqualTo(keys.size + 1)
        assertThat(summary.membersPredictingPrecipAboveThreshold).isAtLeast(1)
        assertThat(summary.precipMeanMm).isNotNull()
        assertThat(summary.precipSpreadMm).isNotNull()
        assertThat(summary.precipP10Mm).isNotNull()
        assertThat(summary.precipP90Mm).isNotNull()
        assertThat(summary.temperatureSpreadC).isNotNull()
    }

    @Test
    fun turkeyFixture_parsesAndFeedsConfidence() {
        val raw = load("open_meteo_ecmwf_ensemble_side_tr.json")
        val date = firstDate(raw)
        val summary = ForecastMapper.parseEnsembleDay(raw, date)!!
        assertThat(summary.memberCount).isGreaterThan(10)

        val models = listOf(
            closeModel(ForecastModelId.ECMWF, date, 2.0),
            closeModel(ForecastModelId.ICON, date, 3.0),
            closeModel(ForecastModelId.GFS, date, 1.5),
        )
        val consensus = ConsensusEngine.buildConsensusDaily(models).first()
        val confidence = ConfidenceEngine.scoreDay(
            date = date,
            today = LocalDate.parse(date),
            models = models,
            consensus = consensus,
            ensemble = summary,
            stabilityScore01 = null,
        )
        assertThat(confidence.ensembleWeightUsed).isGreaterThan(0.0)
        assertThat(confidence.notes.any { it.contains("ENSEMBLE DATA UNAVAILABLE") }).isFalse()
        assertThat(confidence.score).isGreaterThan(0)
    }

    @Test
    fun ensembleUrl_encodesTimezoneSlash() {
        val loc = GeoLocation(
            id = "1",
            name = "Amsterdam",
            latitude = 52.37,
            longitude = 4.89,
            timezone = "Europe/Amsterdam",
        )
        val url = WeatherRepository.buildEnsembleUrl(loc)
        assertThat(url).contains("timezone=Europe%2FAmsterdam")
        assertThat(url).doesNotContain("timezone=Europe/Amsterdam")
        assertThat(url).contains("models=ecmwf_ifs025")
        assertThat(url).contains("forecast_days=15")
    }

    private fun closeModel(id: ForecastModelId, date: String, precip: Double) =
        ModelForecast(
            model = id,
            available = true,
            daily = listOf(
                DailyPoint(
                    date = date,
                    tempMaxC = 18.0,
                    precipitationSumMm = precip,
                    gustMaxKmh = 20.0,
                    windSpeedMaxKmh = 15.0,
                ),
            ),
        )
}
