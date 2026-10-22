package com.discount152.aeolus.map

import android.graphics.Bitmap
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Bilinear interpolation of grid samples into a continuous RGBA bitmap.
 * Network/sampling stays outside this class.
 */
object FieldRasterInterpolator {
    data class Bounds(
        val latSouth: Double,
        val latNorth: Double,
        val lonWest: Double,
        val lonEast: Double,
    )

    fun renderTemperature(
        grid: Array<Array<Double?>>,
        bounds: Bounds,
        rows: Int = grid.size,
        cols: Int = grid.firstOrNull()?.size ?: 0,
        width: Int = 256,
        height: Int = 256,
        opacity: Float = 0.75f,
    ): Bitmap? = renderGrid(grid, rows, cols, bounds, width, height, opacity, ::temperatureRgba)

    fun renderCloudCover(
        grid: Array<Array<Double?>>,
        bounds: Bounds,
        rows: Int = grid.size,
        cols: Int = grid.firstOrNull()?.size ?: 0,
        width: Int = 256,
        height: Int = 256,
        opacity: Float = 0.75f,
    ): Bitmap? = renderGrid(grid, rows, cols, bounds, width, height, opacity, ::cloudRgba)

    fun renderTemperature(
        samples: List<SamplePoint>,
        bounds: Bounds,
        width: Int = 256,
        height: Int = 256,
        opacity: Float = 0.75f,
    ): Bitmap? = samplesToGrid(samples)?.let { (grid, rows, cols) ->
        renderTemperature(grid, bounds, rows, cols, width, height, opacity)
    }

    fun renderCloudCover(
        samples: List<SamplePoint>,
        bounds: Bounds,
        width: Int = 256,
        height: Int = 256,
        opacity: Float = 0.75f,
    ): Bitmap? = samplesToGrid(samples)?.let { (grid, rows, cols) ->
        renderCloudCover(grid, bounds, rows, cols, width, height, opacity)
    }

    private fun samplesToGrid(samples: List<SamplePoint>): Triple<Array<Array<Double?>>, Int, Int>? {
        if (samples.size < 4) return null
        val cols = inferCols(samples)
        val rows = (samples.size + cols - 1) / cols
        if (cols < 2 || rows < 2) return null
        val grid = Array(rows) { r ->
            Array(cols) { c ->
                val idx = r * cols + c
                samples.getOrNull(idx)?.value
            }
        }
        return Triple(grid, rows, cols)
    }

    private fun renderGrid(
        grid: Array<Array<Double?>>,
        rows: Int,
        cols: Int,
        bounds: Bounds,
        width: Int,
        height: Int,
        opacity: Float,
        colorizer: (Double, Float) -> Int,
    ): Bitmap? {
        if (rows < 2 || cols < 2 || grid.size < rows) return null
        val latSpan = (bounds.latNorth - bounds.latSouth).takeIf { it != 0.0 } ?: return null
        val lonSpan = (bounds.lonEast - bounds.lonWest).takeIf { it != 0.0 } ?: return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            val v = 1.0 - (y + 0.5) / height // north at top of image
            val gy = v * (rows - 1)
            for (x in 0 until width) {
                val u = (x + 0.5) / width
                val gx = u * (cols - 1)
                val value = bilinearNullable(grid, rows, cols, gx, gy)
                val pixel = if (value == null) 0 else colorizer(value, opacity)
                bitmap.setPixel(x, y, pixel)
            }
        }
        @Suppress("UNUSED_EXPRESSION")
        latSpan + lonSpan
        return bitmap
    }

    private fun inferCols(samples: List<SamplePoint>): Int {
        if (samples.isEmpty()) return 0
        val firstLat = samples.first().lat
        var cols = 1
        for (i in 1 until samples.size) {
            if (kotlin.math.abs(samples[i].lat - firstLat) < 1e-9) cols++ else break
        }
        return cols.coerceAtLeast(2)
    }

    /**
     * Bilinear blend; null cells are omitted from the weighted sum.
     * All-null neighborhood → null; if weights vanish, nearest non-null sample.
     */
    internal fun bilinearNullable(
        grid: Array<Array<Double?>>,
        rows: Int,
        cols: Int,
        gx: Double,
        gy: Double,
    ): Double? {
        if (rows == 0 || cols == 0) return null
        val x0 = floor(gx).toInt().coerceIn(0, cols - 1)
        val y0 = floor(gy).toInt().coerceIn(0, rows - 1)
        val x1 = (x0 + 1).coerceAtMost(cols - 1)
        val y1 = (y0 + 1).coerceAtMost(rows - 1)
        val tx = (gx - x0).coerceIn(0.0, 1.0)
        val ty = (gy - y0).coerceIn(0.0, 1.0)

        val w00 = (1 - tx) * (1 - ty)
        val w10 = tx * (1 - ty)
        val w01 = (1 - tx) * ty
        val w11 = tx * ty

        var sum = 0.0
        var wSum = 0.0
        fun contribute(v: Double?, w: Double) {
            if (v == null || w <= 0.0) return
            sum += v * w
            wSum += w
        }
        contribute(cell(grid, y0, x0), w00)
        contribute(cell(grid, y0, x1), w10)
        contribute(cell(grid, y1, x0), w01)
        contribute(cell(grid, y1, x1), w11)

        if (wSum > 0.0) return sum / wSum
        return nearestNonNull(grid, rows, cols, gx, gy)
    }

    private fun cell(grid: Array<Array<Double?>>, row: Int, col: Int): Double? =
        grid.getOrNull(row)?.getOrNull(col)

    private fun nearestNonNull(
        grid: Array<Array<Double?>>,
        rows: Int,
        cols: Int,
        gx: Double,
        gy: Double,
    ): Double? {
        var best: Double? = null
        var bestD = Double.MAX_VALUE
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val v = cell(grid, r, c) ?: continue
                val d = sqrt((r - gy) * (r - gy) + (c - gx) * (c - gx))
                if (d < bestD) {
                    bestD = d
                    best = v
                }
            }
        }
        return best
    }

    /** Cool → warm continuous gradient. */
    fun temperatureRgba(celsius: Double, opacity: Float): Int {
        val t = ((celsius + 15.0) / 45.0).coerceIn(0.0, 1.0)
        val r: Int
        val g: Int
        val b: Int
        when {
            t < 0.25 -> {
                val u = t / 0.25
                r = (20 + 40 * u).toInt()
                g = (40 + 120 * u).toInt()
                b = (180 + 40 * u).toInt()
            }
            t < 0.5 -> {
                val u = (t - 0.25) / 0.25
                r = (60 + 100 * u).toInt()
                g = (160 + 60 * u).toInt()
                b = (220 - 120 * u).toInt()
            }
            t < 0.75 -> {
                val u = (t - 0.5) / 0.25
                r = (160 + 70 * u).toInt()
                g = (220 - 40 * u).toInt()
                b = (100 - 60 * u).toInt()
            }
            else -> {
                val u = (t - 0.75) / 0.25
                r = (230 + 25 * u).toInt().coerceAtMost(255)
                g = (180 - 120 * u).toInt().coerceAtLeast(20)
                b = (40 - 20 * u).toInt().coerceAtLeast(0)
            }
        }
        val a = (opacity * 220).toInt().coerceIn(0, 255)
        return argb(a, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    /** Nonlinear alpha vs cloud cover; neutral gray/white. Missing callers use transparent (alpha 0). */
    fun cloudRgba(percent: Double, opacity: Float): Int {
        val coverAlpha = cloudCoverAlphaFraction(percent)
        if (coverAlpha <= 0.0) return 0
        val p = (percent / 100.0).coerceIn(0.0, 1.0)
        val gray = (200 + 55 * p).toInt().coerceIn(180, 255)
        val a = (opacity * coverAlpha * 255.0).toInt().coerceIn(0, 255)
        return argb(a, gray, gray, gray)
    }

    /** Opacity multiplier in 0..1 for cloud cover percent (before layer opacity). */
    fun cloudCoverAlphaFraction(percent: Double): Double {
        val p = percent.coerceIn(0.0, 100.0)
        return when {
            p <= 10.0 -> 0.0
            p <= 20.0 -> lerp(0.0, 0.10, (p - 10.0) / 10.0)
            p <= 40.0 -> lerp(0.10, 0.32, (p - 20.0) / 20.0)
            p <= 60.0 -> lerp(0.32, 0.52, (p - 40.0) / 20.0)
            p <= 80.0 -> lerp(0.52, 0.76, (p - 60.0) / 20.0)
            else -> lerp(0.76, 1.0, (p - 80.0) / 20.0)
        }
    }

    fun temperatureLegendStopsCelsius(): List<Pair<Double, Int>> =
        listOf(-15.0, 0.0, 15.0, 30.0).map { c -> c to temperatureRgba(c, 1f) }

    fun cloudLegendStops(): List<Pair<Int, Int>> =
        listOf(0, 25, 50, 75, 100).map { pct -> pct to cloudRgba(pct.toDouble(), 1f) }

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t.coerceIn(0.0, 1.0)
}
