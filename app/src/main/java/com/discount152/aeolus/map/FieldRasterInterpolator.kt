package com.discount152.aeolus.map

import android.graphics.Bitmap
import kotlin.math.floor

/**
 * Bilinear interpolation of scattered/grid samples into a continuous RGBA bitmap.
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
        samples: List<SamplePoint>,
        bounds: Bounds,
        width: Int = 128,
        height: Int = 96,
        opacity: Float = 0.75f,
    ): Bitmap? = render(samples, bounds, width, height, opacity, ::temperatureRgba)

    fun renderCloudCover(
        samples: List<SamplePoint>,
        bounds: Bounds,
        width: Int = 128,
        height: Int = 96,
        opacity: Float = 0.75f,
    ): Bitmap? = render(samples, bounds, width, height, opacity, ::cloudRgba)

    private fun render(
        samples: List<SamplePoint>,
        bounds: Bounds,
        width: Int,
        height: Int,
        opacity: Float,
        colorizer: (Double, Float) -> Int,
    ): Bitmap? {
        if (samples.size < 4) return null
        val cols = inferCols(samples)
        val rows = (samples.size + cols - 1) / cols
        if (cols < 2 || rows < 2) return null

        // Assume samples were generated row-major south→north, west→east
        val grid = Array(rows) { r ->
            DoubleArray(cols) { c ->
                val idx = r * cols + c
                samples.getOrNull(idx)?.value ?: samples.last().value
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val latSpan = (bounds.latNorth - bounds.latSouth).takeIf { it != 0.0 } ?: return null
        val lonSpan = (bounds.lonEast - bounds.lonWest).takeIf { it != 0.0 } ?: return null

        for (y in 0 until height) {
            val v = 1.0 - (y + 0.5) / height // north at top of image
            val gy = v * (rows - 1)
            for (x in 0 until width) {
                val u = (x + 0.5) / width
                val gx = u * (cols - 1)
                val value = bilinear(grid, gx, gy)
                bitmap.setPixel(x, y, colorizer(value, opacity))
            }
        }
        // bounds unused beyond validation — georeferencing applied by ImageSource
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

    private fun bilinear(grid: Array<DoubleArray>, gx: Double, gy: Double): Double {
        val rows = grid.size
        val cols = grid[0].size
        val x0 = floor(gx).toInt().coerceIn(0, cols - 1)
        val y0 = floor(gy).toInt().coerceIn(0, rows - 1)
        val x1 = (x0 + 1).coerceAtMost(cols - 1)
        val y1 = (y0 + 1).coerceAtMost(rows - 1)
        val tx = (gx - x0).coerceIn(0.0, 1.0)
        val ty = (gy - y0).coerceIn(0.0, 1.0)
        val v00 = grid[y0][x0]
        val v10 = grid[y0][x1]
        val v01 = grid[y1][x0]
        val v11 = grid[y1][x1]
        val a = v00 * (1 - tx) + v10 * tx
        val b = v01 * (1 - tx) + v11 * tx
        return a * (1 - ty) + b * ty
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

    /** Transparent at low cover → opaque light gray/white at high cover. */
    fun cloudRgba(percent: Double, opacity: Float): Int {
        val p = (percent / 100.0).coerceIn(0.0, 1.0)
        if (p < 0.05) return 0
        val gray = (180 + 60 * p).toInt().coerceIn(0, 255)
        val a = (opacity * (40 + 180 * p)).toInt().coerceIn(0, 255)
        return argb(a, gray, gray, gray)
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b
}
