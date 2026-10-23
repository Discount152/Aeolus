package com.discount152.aeolus.map

import com.discount152.aeolus.domain.units.PrecipitationUnit
import com.discount152.aeolus.domain.units.UnitConverter
import kotlin.math.roundToInt

/**
 * Precipitation intensity scale for forecast map overlays (mm/h internally).
 */
object PrecipLegend {
    const val RATE_TRANSPARENT_MAX_MM_H = 0.05

    data class Threshold(val minMmPerHour: Double, val argb: Int, val labelKey: String)

    val thresholds: List<Threshold> = listOf(
        Threshold(0.0, PrecipArgb.TRANSPARENT, "none"),
        Threshold(0.1, PrecipArgb.argb(140, 180, 220, 255), "light"),
        Threshold(0.5, PrecipArgb.argb(165, 120, 200, 240), "light_moderate"),
        Threshold(1.0, PrecipArgb.argb(180, 80, 170, 210), "moderate"),
        Threshold(2.5, PrecipArgb.argb(195, 60, 180, 140), "moderate_heavy"),
        Threshold(5.0, PrecipArgb.argb(210, 40, 150, 100), "heavy"),
        Threshold(10.0, PrecipArgb.argb(225, 20, 110, 80), "very_heavy"),
    )

    /** Map precipitation rate (mm/h) to ARGB; transparent below [RATE_TRANSPARENT_MAX_MM_H]. */
    fun colorForRateMmPerHour(mmPerHour: Double): Int {
        if (mmPerHour < RATE_TRANSPARENT_MAX_MM_H) return PrecipArgb.TRANSPARENT
        var chosen = thresholds.first()
        for (t in thresholds) {
            if (mmPerHour >= t.minMmPerHour) chosen = t
        }
        return chosen.argb
    }

    fun formatRateLabel(mmPerHour: Double, unit: PrecipitationUnit): String {
        val value = UnitConverter.convertPrecipitation(mmPerHour, unit)
        val symbol = UnitConverter.precipitationSymbol(unit)
        val formatted = when (unit) {
            PrecipitationUnit.MM -> "%.1f".format(value)
            PrecipitationUnit.INCHES -> "%.2f".format(value)
        }
        return "$formatted $symbol/h"
    }

    fun legendTickRatesMmPerHour(): List<Double> =
        listOf(0.0, 0.1, 0.5, 1.0, 2.5, 5.0, 10.0)

    fun legendTickLabels(unit: PrecipitationUnit): List<String> =
        legendTickRatesMmPerHour().map { rate ->
            if (rate <= 0.0) "0" else formatRateLabel(rate, unit)
        }

    fun blendColors(c1: Int, c2: Int, c3: Int, c4: Int, tx: Double, ty: Double): Int {
        val top = lerpArgb(c1, c2, tx)
        val bottom = lerpArgb(c3, c4, tx)
        return lerpArgb(top, bottom, ty)
    }

    private fun lerpArgb(a: Int, b: Int, t: Double): Int {
        val ta = t.coerceIn(0.0, 1.0)
        val inv = 1.0 - ta
        val aA = PrecipArgb.alpha(a)
        val aR = PrecipArgb.red(a)
        val aG = PrecipArgb.green(a)
        val aB = PrecipArgb.blue(a)
        val bA = PrecipArgb.alpha(b)
        val bR = PrecipArgb.red(b)
        val bG = PrecipArgb.green(b)
        val bB = PrecipArgb.blue(b)
        return PrecipArgb.argb(
            (aA * inv + bA * ta).roundToInt(),
            (aR * inv + bR * ta).roundToInt(),
            (aG * inv + bG * ta).roundToInt(),
            (aB * inv + bB * ta).roundToInt(),
        )
    }
}

/** ARGB helpers usable on JVM unit tests without Robolectric. */
internal object PrecipArgb {
    const val TRANSPARENT: Int = 0

    fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or
            (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or
            blue.coerceIn(0, 255)

    fun alpha(color: Int): Int = (color ushr 24) and 0xFF
    fun red(color: Int): Int = (color ushr 16) and 0xFF
    fun green(color: Int): Int = (color ushr 8) and 0xFF
    fun blue(color: Int): Int = color and 0xFF
}
