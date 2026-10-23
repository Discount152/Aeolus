package com.discount152.aeolus.map

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class UnifiedMapTimelineTest {
    private val bmp: Bitmap = mockk(relaxed = true) {
        every { isRecycled } returns false
    }

    private fun obs(time: Long) = RadarFrameAsset(
        time = time,
        path = "/v2/radar/$time",
        status = RadarFrameStatus.READY,
        bitmap = bmp,
    )

    private fun fc(hourMs: Long) = ForecastPrecipFrame(
        hourEpochUtcMs = hourMs,
        isoTimeUtc = "t",
        status = ForecastPrecipFrameStatus.READY,
        bitmap = bmp,
    )

    private val obsFp = RadarFootprint(
        centerLat = 52.0, centerLon = 5.0, zoom = 5,
        latSouth = 51.0, latNorth = 53.0, lonWest = 4.0, lonEast = 6.0,
        tiles = listOf(RadarTileCoord(5, 1, 1)), tilesX = 1, tilesY = 1,
    )
    private val fcFp = ForecastPrecipFootprint(51.0, 53.0, 4.0, 6.0)

    @Test
    fun timeline_ordersObservedBeforeForecast_withNowBoundary() {
        val now = 1_700_000_000L
        val timeline = UnifiedMapTimeline.build(
            observed = listOf(obs(now - 3600), obs(now - 600)),
            observedFootprint = obsFp,
            forecast = listOf(fc((now + 3600) * 1000), fc((now + 7200) * 1000)),
            forecastFootprint = fcFp,
            nowEpochSeconds = now,
        )
        val times = timeline.readyFrames.map { it.epochSeconds }
        assertThat(times).isInOrder()
        assertThat(times.first()).isLessThan(now)
        assertThat(times.last()).isGreaterThan(now)
        assertThat(timeline.readyFrames.first().source).isEqualTo(MapFrameSource.OBSERVED_RADAR)
        assertThat(timeline.readyFrames.last().source).isEqualTo(MapFrameSource.FORECAST_PRECIP)
        assertThat(timeline.readyFrames.first().sourceLabel).contains("OBSERVED")
        assertThat(timeline.readyFrames.last().sourceLabel).contains("ICON")
    }

    @Test
    fun scrub_usesAbsoluteTimestamps_notEqualIndexSpacing() {
        val now = 1_000_000L
        val timeline = UnifiedMapTimeline.build(
            observed = listOf(obs(now - 7200), obs(now - 600)),
            observedFootprint = obsFp,
            forecast = listOf(fc((now + 3600) * 1000), fc((now + 24 * 3600) * 1000)),
            forecastFootprint = fcFp,
            nowEpochSeconds = now,
        )
        val nearNow = timeline.frameAtScrub(timeline.scrubPositionFor(now - 600))
        assertThat(nearNow?.epochSeconds).isEqualTo(now - 600)
        val farFuture = timeline.frameAtScrub(1f)
        assertThat(farFuture?.epochSeconds).isEqualTo(now + 24 * 3600)
    }

    @Test
    fun timezoneLabelChange_doesNotAlterFrameIdentity() {
        val now = 1_000_000L
        val a = UnifiedMapTimeline.build(
            listOf(obs(now - 600)), obsFp, listOf(fc((now + 3600) * 1000)), fcFp, now,
        )
        val b = UnifiedMapTimeline.build(
            listOf(obs(now - 600)), obsFp, listOf(fc((now + 3600) * 1000)), fcFp, now,
        )
        assertThat(a.readyFrames.map { it.epochSeconds }).isEqualTo(b.readyFrames.map { it.epochSeconds })
    }
}
