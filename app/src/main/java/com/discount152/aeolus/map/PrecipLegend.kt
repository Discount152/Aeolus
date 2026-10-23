package com.discount152.aeolus.map

import com.discount152.aeolus.domain.units.PrecipitationUnit
import com.discount152.aeolus.domain.units.UnitConverter
import kotlin.math.roundToInt

/**
 * Precipitation intensity scale for map overlays (mm/h).
 * Dry areas stay fully transparent; palette is blue/cyan rainfall-like (no aurora greens).
 *
 * Bands: Dry &lt;0.1 · Very light 0.1–0.5 · Light 0.5–1 · Moderate 1–2.5 ·
 * Heavy 2.5–5 · Very heavy 5–10 · Extreme &gt;10
 */
object PrecipLegend {
    /** Explicit dry mask — below this after interpolation → transparent. */
    const val DRY_THRESHOLD_MM_H = 0.1
    const val RATE_TRANSPARENT_MAX_MM_H = DRY_THRESHOLD_MM_H

    data class Threshold(val minMmPerHour: Double, val argb: Int, val labelKey: String)

    val thresholds: List<Threshold> = listOf(
        Threshold(0.0, PrecipArgb.TRANSPARENT, "dry"),
        Threshold(0.1, PrecipArgb.argb(150, 120, 190, 255), "very_light"),
        Threshold(0.5, PrecipArgb.argb(170, 70, 150, 245), "light"),
        Threshold(1.0, PrecipArgb.argb(190, 40, 110, 230), "moderate"),
        Threshold(2.5, PrecipArgb.argb(205, 25, 70, 210), "heavy"),
        Threshold(5.0, PrecipArgb.argb(220, 20, 40, 170), "very_heavy"),
        Threshold(10.0, PrecipArgb.argb(235, 15, 20, 120), "extreme"),
    )

    fun colorForRateMmPerHour(mmPerHour: Double): Int {
        if (mmPerHour < DRY_THRESHOLD_MM_H) return PrecipArgb.TRANSPARENT
        var chosen = thresholds.first { it.minMmPerHour > 0 }
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
            when {
                rate <= 0.0 -> "Dry"
                else -> formatRateLabel(rate, unit)
            }
        }

    fun blendColors(c1: Int, c2: Int, c3: Int, c4: Int, tx: Double, ty: Double): Int {
        val top = lerpArgb(c1, c2, tx)
        val bottom = lerpArgb(c3, c4, tx)
        val blended = lerpArgb(top, bottom, ty)
        // Re-apply dry mask: near-transparent blends stay fully transparent
        if (PrecipArgb.alpha(blended) < 20) return PrecipArgb.TRANSPARENT
        return blended
    }

    private fun lerpArgb(a: Int, b: Int, t: Double): Int {
        val ta = t.coerceIn(0.0, 1.0)
        val inv = 1.0 - ta
        return PrecipArgb.argb(
            (PrecipArgb.alpha(a) * inv + PrecipArgb.alpha(b) * ta).roundToInt(),
            (PrecipArgb.red(a) * inv + PrecipArgb.red(b) * ta).roundToInt(),
            (PrecipArgb.green(a) * inv + PrecipArgb.green(b) * ta).roundToInt(),
            (PrecipArgb.blue(a) * inv + PrecipArgb.blue(b) * ta).roundToInt(),
        )
    }
}

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
