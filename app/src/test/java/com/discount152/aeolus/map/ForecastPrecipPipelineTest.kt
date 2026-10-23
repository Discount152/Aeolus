package com.discount152.aeolus.map

import android.graphics.Bitmap
import com.discount152.aeolus.data.network.HourlyBlock
import com.discount152.aeolus.data.network.OpenMeteoForecastApi
import com.discount152.aeolus.data.network.OpenMeteoForecastResponse
import com.discount152.aeolus.domain.units.PrecipitationUnit
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant

class ForecastPrecipPipelineTest {

    @Test
    fun sampleGrid_defaultViewport_isEighteenByTwelve() {
        val points = ForecastPrecipSampling.sampleGrid(
            latSouth = 50.0,
            latNorth = 52.0,
            lonWest = 4.0,
            lonEast = 6.0,
        )
        assertThat(points).hasSize(18 * 12)
        assertThat(points.first().lat).isWithin(1e-6).of(52.0)
        assertThat(points.first().lon).isWithin(1e-6).of(4.0)
        assertThat(points.last().lat).isWithin(1e-6).of(50.0)
        assertThat(points.last().lon).isWithin(1e-6).of(6.0)
    }

    @Test
    fun adaptiveGrid_capsSampleCount() {
        val (c, r) = ForecastPrecipSampling.adaptiveGridSize(40.0, 60.0, -10.0, 30.0)
        assertThat(c * r).isAtMost(ForecastPrecipPipeline.MAX_SAMPLE_POINTS)
        assertThat(c).isAtLeast(8)
    }

    @Test
    fun dryMask_belowThreshold_isTransparent() {
        assertThat(PrecipLegend.colorForRateMmPerHour(0.05)).isEqualTo(0)
        assertThat(PrecipLegend.colorForRateMmPerHour(0.1)).isNotEqualTo(0)
    }

    @Test
    fun indicesForNext24Hours_selectsFromNowUtc_inclusive() {
        val times = (0 until 48).map { h ->
            val dayOffset = h / 24
            val hour = h % 24
            "2024-06-%02dT%02d:00".format(1 + dayOffset, hour)
        }
        val now = Instant.parse("2024-06-01T05:00:00Z")
        val indices = ForecastPrecipSampling.indicesForNext24Hours(times, now)
        assertThat(indices).hasSize(24)
        assertThat(indices.first()).isEqualTo(5)
        assertThat(indices.last()).isEqualTo(28)
    }

    @Test
    fun hourEpochUtcMs_ignoresDisplayTimezone_usesUtcBucket() {
        val ms = ForecastPrecipSampling.hourEpochUtcMs("2024-06-01T12:00")
        assertThat(ms).isEqualTo(Instant.parse("2024-06-01T12:00:00Z").toEpochMilli())
    }

    @Test
    fun footprint_coversVisible_matchesGeoreferencedQuad() {
        val fp = ForecastPrecipFootprint(
            latSouth = 50.0,
            latNorth = 52.0,
            lonWest = 4.0,
            lonEast = 6.0,
        )
        assertThat(fp.coversVisible(50.5, 51.5, 4.5, 5.5)).isTrue()
        assertThat(fp.coversVisible(49.0, 51.0, 4.5, 5.5)).isFalse()
        // MapLibre LatLngQuad order: NW, NE, SE, SW — corners align with footprint bounds.
        assertThat(fp.latNorth).isEqualTo(52.0)
        assertThat(fp.latSouth).isEqualTo(50.0)
        assertThat(fp.lonWest).isEqualTo(4.0)
        assertThat(fp.lonEast).isEqualTo(6.0)
    }

    @Test
    fun precipLegend_formatsUserUnits() {
        assertThat(PrecipLegend.formatRateLabel(2.5, PrecipitationUnit.MM)).isEqualTo("2.5 mm/h")
        assertThat(PrecipLegend.formatRateLabel(2.54, PrecipitationUnit.INCHES)).contains("in/h")
    }

    @Test
    fun rasterInterpolation_marksWetPixel_whenSampleHasPrecip() {
        val rates = DoubleArray(4) { if (it == 0) 3.0 else 0.0 }
        val pixels = ForecastPrecipRaster.renderBilinearPixels(
            ratesMmPerHour = rates,
            cols = 2,
            rows = 2,
            widthPx = 8,
            heightPx = 8,
            latSouth = 50.0,
            latNorth = 52.0,
            lonWest = 4.0,
            lonEast = 6.0,
        )
        assertThat(pixels[0]).isNotEqualTo(0)
        assertThat(pixels.last()).isEqualTo(0)
    }

    @Test
    fun pipeline_marksReadyOnlyWithCompleteBitmap() = runBlocking {
        val api = mockk<OpenMeteoForecastApi>()
        val times = (0 until 48).map { h ->
            val dayOffset = h / 24
            val hour = h % 24
            "2024-06-%02dT%02d:00".format(1 + dayOffset, hour)
        }
        val precip = List(48) { idx -> if (idx == 0) 1.0 else 0.0 }
        val block = HourlyBlock(time = times, precipitation = precip)
        val single = OpenMeteoForecastResponse(hourly = block)
        coEvery {
            api.forecastMultiLocation(any(), any(), any(), any(), any(), any(), any())
        } answers {
            val lat = firstArg<String>()
            val count = lat.split(',').size
            List(count) { single }
        }

        val fakeBitmap = mockk<Bitmap>(relaxed = true) {
            every { width } returns 16
            every { isRecycled } returns false
        }
        val pipeline = ForecastPrecipPipeline(
            forecastApi = api,
            nowUtc = { Instant.parse("2024-06-01T00:00:00Z") },
            rasterEncoder = ForecastPrecipRasterEncoder { _, _, _ -> fakeBitmap },
        )
        val footprint = ForecastPrecipFootprint(50.0, 52.0, 4.0, 6.0, rasterSizePx = 16)
        pipeline.loadForViewport(footprint, 50.5, 51.5, 4.5, 5.5)

        val session = pipeline.session.value!!
        assertThat(session.frames).hasSize(24)
        session.frames.forEach { frame ->
            if (frame.status == ForecastPrecipFrameStatus.READY) {
                assertThat(frame.bitmap).isNotNull()
                assertThat(frame.bitmap!!.width).isEqualTo(16)
            } else {
                assertThat(frame.bitmap).isNull()
            }
        }
        assertThat(session.readyFrames).hasSize(24)
        assertThat(session.playable).isTrue()
        assertThat(pipeline.networkBatchCount).isAtLeast(1)
    }

    @Test
    fun pipeline_reusesCacheWhenFootprintStillCoversVisible() = runBlocking {
        val api = mockk<OpenMeteoForecastApi>()
        val times = (0 until 48).map { h ->
            val dayOffset = h / 24
            val hour = h % 24
            "2024-06-%02dT%02d:00".format(1 + dayOffset, hour)
        }
        val block = HourlyBlock(time = times, precipitation = List(48) { 0.1 })
        val single = OpenMeteoForecastResponse(hourly = block)
        coEvery {
            api.forecastMultiLocation(any(), any(), any(), any(), any(), any(), any())
        } answers {
            List(firstArg<String>().split(',').size) { single }
        }

        val fakeBitmap = mockk<Bitmap>(relaxed = true) {
            every { isRecycled } returns false
        }
        val pipeline = ForecastPrecipPipeline(
            forecastApi = api,
            nowUtc = { Instant.parse("2024-06-01T00:00:00Z") },
            rasterEncoder = ForecastPrecipRasterEncoder { _, _, _ -> fakeBitmap },
        )
        val footprint = ForecastPrecipFootprint(50.0, 52.0, 4.0, 6.0, rasterSizePx = 8)
        pipeline.loadForViewport(footprint, 50.5, 51.5, 4.5, 5.5)
        val batchesAfterFirst = pipeline.networkBatchCount
        pipeline.loadForViewport(footprint, 50.6, 51.4, 4.6, 5.4)

        assertThat(pipeline.networkBatchCount).isEqualTo(batchesAfterFirst)
        assertThat(pipeline.playbackFrames()).hasSize(24)
    }
}
