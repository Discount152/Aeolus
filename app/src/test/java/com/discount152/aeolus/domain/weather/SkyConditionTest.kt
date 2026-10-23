package com.discount152.aeolus.domain.weather

import com.discount152.aeolus.data.network.ForecastMapper
import com.discount152.aeolus.domain.models.HourlyPoint
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SkyConditionTest {
    private val date = "2026-10-23"
    private val sunrise = "${date}T06:00"
    private val sunset = "${date}T18:00"

    @Test
    fun categoryForMean_respectsThresholds() {
        assertThat(SkyCondition.categoryForMean(5.0)).isEqualTo(SkyCondition.Category.CLEAR)
        assertThat(SkyCondition.categoryForMean(10.0)).isEqualTo(SkyCondition.Category.CLEAR)
        assertThat(SkyCondition.categoryForMean(11.0)).isEqualTo(SkyCondition.Category.MOSTLY_CLEAR)
        assertThat(SkyCondition.categoryForMean(30.0)).isEqualTo(SkyCondition.Category.MOSTLY_CLEAR)
        assertThat(SkyCondition.categoryForMean(31.0)).isEqualTo(SkyCondition.Category.PARTLY_CLOUDY)
        assertThat(SkyCondition.categoryForMean(60.0)).isEqualTo(SkyCondition.Category.PARTLY_CLOUDY)
        assertThat(SkyCondition.categoryForMean(61.0)).isEqualTo(SkyCondition.Category.MOSTLY_CLOUDY)
        assertThat(SkyCondition.categoryForMean(85.0)).isEqualTo(SkyCondition.Category.MOSTLY_CLOUDY)
        assertThat(SkyCondition.categoryForMean(86.0)).isEqualTo(SkyCondition.Category.OVERCAST)
        assertThat(SkyCondition.categoryForMean(100.0)).isEqualTo(SkyCondition.Category.OVERCAST)
    }

    @Test
    fun deriveDailySummary_usesDaylightHoursOnly() {
        val hourly = listOf(
            hour("${date}T05:00", 100),
            hour("${date}T10:00", 20),
            hour("${date}T14:00", 40),
            hour("${date}T19:00", 100),
        )
        val summary = SkyCondition.deriveDailySummary(date, hourly, sunrise, sunset)
        assertThat(summary).isEqualTo("Mostly clear")
    }

    @Test
    fun deriveDailySummary_variableWhenSpreadExtreme() {
        val hourly = listOf(
            hour("${date}T09:00", 5),
            hour("${date}T12:00", 90),
        )
        val summary = SkyCondition.deriveDailySummary(date, hourly, sunrise, sunset)
        assertThat(summary).isEqualTo("Variable clouds")
    }

    @Test
    fun deriveDailySummary_nullWithoutSamples() {
        val hourly = listOf(hour("${date}T22:00", 50))
        assertThat(SkyCondition.deriveDailySummary(date, hourly, sunrise, sunset)).isNull()
    }

    private fun hour(iso: String, cloud: Int): HourlyPoint =
        HourlyPoint(
            timeEpochMs = ForecastMapper.parseTimeMs(iso),
            timeIso = iso,
            cloudCoverPercent = cloud,
        )
}
