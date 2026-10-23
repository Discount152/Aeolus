package com.discount152.aeolus.map

import android.graphics.Bitmap
import com.discount152.aeolus.data.network.OpenMeteoForecastApi
import com.discount152.aeolus.data.network.OpenMeteoForecastResponse
import kotlinx.coroutines.CancellationException
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
    val generation: Long = 0L,
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
 * Data source: Open-Meteo forecast with coherent **ICON Seamless** (`icon_seamless`) across the viewport.
 * Adaptive sample grid; batched multi-location requests; 24 hourly frames generated locally.
 */
fun interface ForecastPrecipRasterEncoder {
    fun encode(widthPx: Int, heightPx: Int, pixels: IntArray): Bitmap
}

class ForecastPrecipPipeline(
    private val forecastApi: OpenMeteoForecastApi,
    private val modelId: String = MAP_FORECAST_MODEL,
    private val nowUtc: () -> Instant = { Instant.now().truncatedTo(ChronoUnit.HOURS) },
    private val rasterEncoder: ForecastPrecipRasterEncoder = ForecastPrecipRasterEncoder { w, h, pixels ->
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    },
) {
    /** Network multi-location calls for the last load (tests / diagnostics). */
    @Volatile
    var networkBatchCount: Int = 0
        private set
    private val mutex = Mutex()
    private val frameCache = LinkedHashMap<String, Bitmap>(48, 0.75f, true)
    private val maxCacheEntries = 64

    private val _session = MutableStateFlow<ForecastPrecipSession?>(null)
    val session: StateFlow<ForecastPrecipSession?> = _session.asStateFlow()

    @Volatile
    private var activeGeneration: Long = 0L

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
        generation: Long = 0L,
    ) {
        activeGeneration = generation
        mutex.withLock {
            val current = _session.value
            if (!forceRefresh &&
                current != null &&
                current.generation == generation &&
                current.footprint.coversVisible(
                    visibleLatSouth,
                    visibleLatNorth,
                    visibleLonWest,
                    visibleLonEast,
                ) &&
                current.frames.any { it.status == ForecastPrecipFrameStatus.READY } &&
                !current.preparing
            ) {
                return
            }
        }

        val (gridCols, gridRows) = ForecastPrecipSampling.adaptiveGridSize(
            footprint.latSouth,
            footprint.latNorth,
            footprint.lonWest,
            footprint.lonEast,
        )
        val samples = ForecastPrecipSampling.sampleGrid(
            footprint.latSouth,
            footprint.latNorth,
            footprint.lonWest,
            footprint.lonEast,
            gridCols,
            gridRows,
        )
        fun terminalEmpty() {
            if (generation == activeGeneration) {
                _session.value = ForecastPrecipSession(
                    footprint, emptyList(), preparing = false, generation = generation,
                )
            }
        }
        if (samples.isEmpty()) {
            terminalEmpty()
            return
        }

        networkBatchCount = 0
        val responses = try {
            withContext(Dispatchers.IO) {
                fetchBatched(samples)
            }
        } catch (e: CancellationException) {
            if (generation == activeGeneration) {
                _session.value = ForecastPrecipSession(
                    footprint, emptyList(), preparing = false, generation = generation,
                )
            }
            throw e
        } catch (_: Exception) {
            terminalEmpty()
            return
        }
        if (generation != activeGeneration) return

        if (responses.size != samples.size) {
            terminalEmpty()
            return
        }

        val referenceTimes = responses.first().hourly?.time.orEmpty()
        if (referenceTimes.isEmpty() || responses.any { it.hourly?.time != referenceTimes }) {
            terminalEmpty()
            return
        }

        val hourIndices = ForecastPrecipSampling.indicesForNext24Hours(referenceTimes, nowUtc())
        if (hourIndices.size < 2) {
            terminalEmpty()
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
        if (generation != activeGeneration) return
        _session.value = ForecastPrecipSession(
            footprint, pendingFrames, preparing = true, generation = generation,
        )

        val gridValues = buildSampleGridValues(responses)
        val size = footprint.rasterSizePx

        try {
            hourIndices.forEachIndexed { frameIndex, hourIdx ->
                if (generation != activeGeneration) return
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

                updateFrame(frameMeta.hourEpochUtcMs, generation) {
                    it.copy(status = ForecastPrecipFrameStatus.READY, bitmap = bitmap)
                }
            }

            val done = _session.value
            if (done != null &&
                done.generation == generation &&
                generation == activeGeneration &&
                done.footprint.cacheKeyPart() == footprint.cacheKeyPart()
            ) {
                _session.value = done.copy(preparing = false)
            }
        } catch (e: CancellationException) {
            if (generation == activeGeneration) {
                val cur = _session.value
                if (cur != null && cur.generation == generation) {
                    _session.value = cur.copy(preparing = false)
                }
            }
            throw e
        }
    }

    fun playbackFrames(): List<ForecastPrecipFrame> =
        _session.value?.readyFrames?.sortedBy { it.hourEpochUtcMs }.orEmpty()

    fun clearSession() {
        _session.value = null
    }

    private suspend fun fetchBatched(samples: List<PrecipSamplePoint>): List<OpenMeteoForecastResponse> {
        val out = ArrayList<OpenMeteoForecastResponse>(samples.size)
        var i = 0
        while (i < samples.size) {
            val end = (i + MAX_POINTS_PER_BATCH).coerceAtMost(samples.size)
            val chunk = samples.subList(i, end)
            val latParam = chunk.joinToString(",") { "%.5f".format(it.lat) }
            val lonParam = chunk.joinToString(",") { "%.5f".format(it.lon) }
            val batch = forecastApi.forecastMultiLocation(
                latitude = latParam,
                longitude = lonParam,
                models = modelId,
            )
            networkBatchCount++
            out.addAll(batch)
            i = end
        }
        return out
    }

    private fun buildSampleGridValues(responses: List<OpenMeteoForecastResponse>): List<DoubleArray> =
        responses.map { resp ->
            resp.hourly?.precipitation?.map { it ?: 0.0 }?.toDoubleArray() ?: doubleArrayOf()
        }

    private fun updateFrame(
        hourEpochUtcMs: Long,
        generation: Long,
        transform: (ForecastPrecipFrame) -> ForecastPrecipFrame,
    ) {
        if (generation != activeGeneration) return
        val sess = _session.value ?: return
        if (sess.generation != generation) return
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
        /** DWD ICON Seamless — one coherent model for the whole map viewport. */
        const val MAP_FORECAST_MODEL = "icon_seamless"
        const val FALLBACK_FORECAST_MODEL = "ecmwf_ifs025"
        const val DEFAULT_GRID_COLS = 18
        const val DEFAULT_GRID_ROWS = 12
        const val DEFAULT_RASTER_SIZE_PX = 512
        const val MAX_SAMPLE_POINTS = 384
        const val MAX_POINTS_PER_BATCH = 80
    }
}

object ForecastPrecipSampling {
    /**
     * Adaptive cols×rows from geographic span. Wider views → ~18×12; closer → denser up to ~24×16,
     * capped at [ForecastPrecipPipeline.MAX_SAMPLE_POINTS].
     */
    fun adaptiveGridSize(
        latSouth: Double,
        latNorth: Double,
        lonWest: Double,
        lonEast: Double,
    ): Pair<Int, Int> {
        var west = lonWest
        var east = lonEast
        if (east < west) east += 360.0
        val latSpan = (latNorth - latSouth).coerceAtLeast(0.01)
        val lonSpan = (east - west).coerceAtLeast(0.01)
        val maxSpan = maxOf(latSpan, lonSpan)
        val (cols, rows) = when {
            maxSpan > 25.0 -> 18 to 12
            maxSpan > 10.0 -> 20 to 14
            maxSpan > 4.0 -> 22 to 15
            else -> 24 to 16
        }
        var c = cols
        var r = rows
        while (c * r > ForecastPrecipPipeline.MAX_SAMPLE_POINTS && (c > 8 || r > 6)) {
            if (c >= r && c > 8) c-- else if (r > 6) r-- else break
        }
        return c to r
    }

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

                // Interpolate rates then dry-mask — avoids rain-colored halos into dry cells
                val r00 = rateAt(x0, y0)
                val r10 = rateAt(x1, y0)
                val r01 = rateAt(x0, y1)
                val r11 = rateAt(x1, y1)
                val top = r00 * (1 - tx) + r10 * tx
                val bottom = r01 * (1 - tx) + r11 * tx
                val rate = top * (1 - ty) + bottom * ty
                pixels[py * widthPx + px] = PrecipLegend.colorForRateMmPerHour(rate)
            }
        }

        return pixels
    }
}
