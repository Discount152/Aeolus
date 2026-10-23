package com.discount152.aeolus.map

import android.graphics.Bitmap
import com.discount152.aeolus.data.network.OpenMeteoForecastApi
import com.discount152.aeolus.data.network.OpenMeteoForecastResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

enum class ForecastPrecipFrameStatus {
    PENDING,
    LOADING,
    READY,
    FAILED,
}

data class ForecastPrecipFootprint(
    val latSouth: Double,
    val latNorth: Double,
    val lonWest: Double,
    val lonEast: Double,
    val rasterSizePx: Int = ForecastPrecipPipeline.DEFAULT_RASTER_SIZE_PX,
) {
    fun cacheKeyPart(): String =
        "${"%.4f".format(latSouth)},${"%.4f".format(latNorth)}," +
            "${"%.4f".format(lonWest)},${"%.4f".format(lonEast)}@$rasterSizePx"

    fun coversVisible(
        visLatSouth: Double,
        visLatNorth: Double,
        visLonWest: Double,
        visLonEast: Double,
    ): Boolean =
        visLatSouth >= latSouth &&
            visLatNorth <= latNorth &&
            visLonWest >= lonWest &&
            visLonEast <= lonEast
}

data class ForecastPrecipFrame(
    /** UTC hour bucket; frame identity excludes display timezone. */
    val hourEpochUtcMs: Long,
    val isoTimeUtc: String,
    val status: ForecastPrecipFrameStatus,
    val bitmap: Bitmap? = null,
)

data class ForecastPrecipSession(
    val footprint: ForecastPrecipFootprint,
    val frames: List<ForecastPrecipFrame>,
    val preparing: Boolean,
) {
    val readyFrames: List<ForecastPrecipFrame>
        get() = frames.filter { it.status == ForecastPrecipFrameStatus.READY && it.bitmap != null }

    val playable: Boolean
        get() = readyFrames.size >= 2
}

data class PrecipSamplePoint(val lat: Double, val lon: Double)

/**
 * Builds georeferenced hourly forecast precipitation rasters for a padded map viewport.
 *
 * Data source: Open-Meteo forecast API with **Open-Meteo best_match** ([OpenMeteoForecastApi.forecastMultiLocation]).
 * One batched request per viewport refresh; 24 hourly frames interpolated locally from the sample grid.
 */
fun interface ForecastPrecipRasterEncoder {
    fun encode(widthPx: Int, heightPx: Int, pixels: IntArray): Bitmap
}

class ForecastPrecipPipeline(
    private val forecastApi: OpenMeteoForecastApi,
    private val gridCols: Int = DEFAULT_GRID_COLS,
    private val gridRows: Int = DEFAULT_GRID_ROWS,
    private val nowUtc: () -> Instant = { Instant.now().truncatedTo(ChronoUnit.HOURS) },
    private val rasterEncoder: ForecastPrecipRasterEncoder = ForecastPrecipRasterEncoder { w, h, pixels ->
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    },
) {
    private val mutex = Mutex()
    private val frameCache = LinkedHashMap<String, Bitmap>(48, 0.75f, true)
    private val maxCacheEntries = 64

    private val _session = MutableStateFlow<ForecastPrecipSession?>(null)
    val session: StateFlow<ForecastPrecipSession?> = _session.asStateFlow()

    /**
     * Load or refresh forecast precipitation for [footprint]. Skips network when the active session
     * [ForecastPrecipFootprint.coversVisible] the visible bounds and cached frames are still valid.
     */
    suspend fun loadForViewport(
        footprint: ForecastPrecipFootprint,
        visibleLatSouth: Double,
        visibleLatNorth: Double,
        visibleLonWest: Double,
        visibleLonEast: Double,
        forceRefresh: Boolean = false,
    ) {
        mutex.withLock {
            val current = _session.value
            if (!forceRefresh &&
                current != null &&
                current.footprint.coversVisible(
                    visibleLatSouth,
                    visibleLatNorth,
                    visibleLonWest,
                    visibleLonEast,
                ) &&
                current.frames.any { it.status == ForecastPrecipFrameStatus.READY }
            ) {
                return
            }
        }

        val samples = ForecastPrecipSampling.sampleGrid(
            footprint.latSouth,
            footprint.latNorth,
            footprint.lonWest,
            footprint.lonEast,
            gridCols,
            gridRows,
        )
        if (samples.isEmpty()) {
            _session.value = ForecastPrecipSession(footprint, emptyList(), preparing = false)
            return
        }

        val latParam = samples.joinToString(",") { "%.5f".format(it.lat) }
        val lonParam = samples.joinToString(",") { "%.5f".format(it.lon) }

        val responses = try {
            withContext(Dispatchers.IO) {
                forecastApi.forecastMultiLocation(
                    latitude = latParam,
                    longitude = lonParam,
                )
            }
        } catch (_: Exception) {
            _session.value = ForecastPrecipSession(footprint, emptyList(), preparing = false)
            return
        }

        if (responses.size != samples.size) {
            _session.value = ForecastPrecipSession(footprint, emptyList(), preparing = false)
            return
        }

        val referenceTimes = responses.first().hourly?.time.orEmpty()
        if (referenceTimes.isEmpty() || responses.any { it.hourly?.time != referenceTimes }) {
            _session.value = ForecastPrecipSession(footprint, emptyList(), preparing = false)
            return
        }

        val hourIndices = ForecastPrecipSampling.indicesForNext24Hours(referenceTimes, nowUtc())
        if (hourIndices.size < 2) {
            _session.value = ForecastPrecipSession(footprint, emptyList(), preparing = false)
            return
        }

        val pendingFrames = hourIndices.map { idx ->
            val iso = referenceTimes[idx]
            ForecastPrecipFrame(
                hourEpochUtcMs = ForecastPrecipSampling.hourEpochUtcMs(iso),
                isoTimeUtc = iso,
                status = ForecastPrecipFrameStatus.LOADING,
            )
        }
        _session.value = ForecastPrecipSession(footprint, pendingFrames, preparing = true)

        val gridValues = buildSampleGridValues(responses)
        val size = footprint.rasterSizePx

        hourIndices.forEachIndexed { frameIndex, hourIdx ->
            val hourRates = DoubleArray(gridCols * gridRows) { cell ->
                gridValues[cell][hourIdx].coerceAtLeast(0.0)
            }
            val frameMeta = pendingFrames[frameIndex]
            val cacheKey = frameCacheKey(footprint, frameMeta.hourEpochUtcMs)

            val cached = mutex.withLock { frameCache[cacheKey]?.takeIf { !it.isRecycled } }
            val bitmap = cached ?: withContext(Dispatchers.Default) {
                val pixels = ForecastPrecipRaster.renderBilinearPixels(
                    ratesMmPerHour = hourRates,
                    cols = gridCols,
                    rows = gridRows,
                    widthPx = size,
                    heightPx = size,
                    latSouth = footprint.latSouth,
                    latNorth = footprint.latNorth,
                    lonWest = footprint.lonWest,
                    lonEast = footprint.lonEast,
                )
                rasterEncoder.encode(size, size, pixels)
            }

            if (cached == null) {
                mutex.withLock { putCache(cacheKey, bitmap) }
            }

            updateFrame(frameMeta.hourEpochUtcMs) {
                it.copy(status = ForecastPrecipFrameStatus.READY, bitmap = bitmap)
            }
        }

        val done = _session.value
        if (done != null && done.footprint.cacheKeyPart() == footprint.cacheKeyPart()) {
            _session.value = done.copy(preparing = false)
        }
    }

    fun playbackFrames(): List<ForecastPrecipFrame> =
        _session.value?.readyFrames?.sortedBy { it.hourEpochUtcMs }.orEmpty()

    fun clearSession() {
        _session.value = null
    }

    private fun buildSampleGridValues(responses: List<OpenMeteoForecastResponse>): List<DoubleArray> =
        responses.map { resp ->
            resp.hourly?.precipitation?.map { it ?: 0.0 }?.toDoubleArray() ?: doubleArrayOf()
        }

    private fun updateFrame(hourEpochUtcMs: Long, transform: (ForecastPrecipFrame) -> ForecastPrecipFrame) {
        val sess = _session.value ?: return
        val updated = sess.frames.map { if (it.hourEpochUtcMs == hourEpochUtcMs) transform(it) else it }
        val preparing = updated.any {
            it.status == ForecastPrecipFrameStatus.PENDING || it.status == ForecastPrecipFrameStatus.LOADING
        }
        _session.value = sess.copy(frames = updated, preparing = preparing)
    }

    private fun frameCacheKey(footprint: ForecastPrecipFootprint, hourEpochUtcMs: Long): String =
        "${footprint.cacheKeyPart()}@$hourEpochUtcMs"

    private fun putCache(key: String, bitmap: Bitmap) {
        frameCache[key] = bitmap
        while (frameCache.size > maxCacheEntries) {
            val eldest = frameCache.entries.iterator().next()
            frameCache.remove(eldest.key)
        }
    }

    companion object {
        const val DEFAULT_GRID_COLS = 12
        const val DEFAULT_GRID_ROWS = 8
        const val DEFAULT_RASTER_SIZE_PX = 256
    }
}

object ForecastPrecipSampling {
    fun sampleGrid(
        latSouth: Double,
        latNorth: Double,
        lonWest: Double,
        lonEast: Double,
        cols: Int = ForecastPrecipPipeline.DEFAULT_GRID_COLS,
        rows: Int = ForecastPrecipPipeline.DEFAULT_GRID_ROWS,
    ): List<PrecipSamplePoint> {
        if (cols < 2 || rows < 2) return emptyList()
        var west = lonWest
        var east = lonEast
        if (east < west) east += 360.0
        val latSpan = latNorth - latSouth
        val lonSpan = east - west
        if (latSpan <= 0.0 || lonSpan <= 0.0) return emptyList()

        val points = ArrayList<PrecipSamplePoint>(cols * rows)
        for (row in 0 until rows) {
            val v = row.toDouble() / (rows - 1)
            val lat = latNorth - v * latSpan
            for (col in 0 until cols) {
                val u = col.toDouble() / (cols - 1)
                val lon = west + u * lonSpan
                points.add(PrecipSamplePoint(lat, RadarViewport.normalizeLon(lon)))
            }
        }
        return points
    }

    fun indicesForNext24Hours(hourlyTimesUtc: List<String>, now: Instant): List<Int> {
        if (hourlyTimesUtc.isEmpty()) return emptyList()
        val startMs = now.toEpochMilli()
        val picked = mutableListOf<Int>()
        for (i in hourlyTimesUtc.indices) {
            val epoch = hourEpochUtcMs(hourlyTimesUtc[i])
            if (epoch >= startMs) {
                picked.add(i)
                if (picked.size >= 24) break
            }
        }
        return picked
    }

    fun hourEpochUtcMs(isoTime: String): Long {
        val instant = parseHourlyInstantUtc(isoTime)
        return instant.truncatedTo(ChronoUnit.HOURS).toEpochMilli()
    }

    fun parseHourlyInstantUtc(isoTime: String): Instant {
        return if (isoTime.endsWith('Z')) {
            Instant.parse(isoTime)
        } else {
            LocalDateTime.parse(isoTime).toInstant(ZoneOffset.UTC)
        }
    }
}

object ForecastPrecipRaster {
    fun renderBilinearPixels(
        ratesMmPerHour: DoubleArray,
        cols: Int,
        rows: Int,
        widthPx: Int,
        heightPx: Int,
        latSouth: Double,
        latNorth: Double,
        lonWest: Double,
        lonEast: Double,
    ): IntArray {
        val pixels = IntArray((widthPx * heightPx).coerceAtLeast(0))
        if (widthPx <= 0 || heightPx <= 0) {
            return pixels
        }
        var west = lonWest
        var east = lonEast
        if (east < west) east += 360.0
        val latSpan = latNorth - latSouth
        val lonSpan = east - west

        for (py in 0 until heightPx) {
            val v = py.toDouble() / (heightPx - 1).coerceAtLeast(1)
            val lat = latNorth - v * latSpan
            val gy = (latNorth - lat) / latSpan * (rows - 1)
            val y0 = gy.toInt().coerceIn(0, rows - 2)
            val y1 = y0 + 1
            val ty = gy - y0

            for (px in 0 until widthPx) {
                val u = px.toDouble() / (widthPx - 1).coerceAtLeast(1)
                val lon = west + u * lonSpan
                val gx = (RadarViewport.normalizeLon(lon) - lonWest) / lonSpan * (cols - 1)
                val x0 = gx.toInt().coerceIn(0, cols - 2)
                val x1 = x0 + 1
                val tx = gx - x0

                fun rateAt(c: Int, r: Int): Double = ratesMmPerHour[r * cols + c]

                val c00 = PrecipLegend.colorForRateMmPerHour(rateAt(x0, y0))
                val c10 = PrecipLegend.colorForRateMmPerHour(rateAt(x1, y0))
                val c01 = PrecipLegend.colorForRateMmPerHour(rateAt(x0, y1))
                val c11 = PrecipLegend.colorForRateMmPerHour(rateAt(x1, y1))
                val blended = if (PrecipArgb.alpha(c00) == 0 && PrecipArgb.alpha(c10) == 0 &&
                    PrecipArgb.alpha(c01) == 0 && PrecipArgb.alpha(c11) == 0
                ) {
                    PrecipArgb.TRANSPARENT
                } else {
                    PrecipLegend.blendColors(c00, c10, c01, c11, tx, ty)
                }
                pixels[py * widthPx + px] = blended
            }
        }

        return pixels
    }
}
