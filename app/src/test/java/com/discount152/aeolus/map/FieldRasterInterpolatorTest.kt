package com.discount152.aeolus.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FieldRasterInterpolatorTest {
    @Test
    fun temperatureColor_variesAcrossRange() {
        val cold = FieldRasterInterpolator.temperatureRgba(-10.0, 1f)
        val hot = FieldRasterInterpolator.temperatureRgba(30.0, 1f)
        assertThat(cold).isNotEqualTo(hot)
        assertThat(alpha(cold)).isGreaterThan(0)
        assertThat(alpha(hot)).isGreaterThan(0)
    }

    @Test
    fun cloudColor_nonlinearTransparency() {
        assertThat(alpha(FieldRasterInterpolator.cloudRgba(0.0, 1f))).isEqualTo(0)
        assertThat(alpha(FieldRasterInterpolator.cloudRgba(5.0, 1f))).isEqualTo(0)
        val at15 = alpha(FieldRasterInterpolator.cloudRgba(15.0, 1f))
        assertThat(at15).isGreaterThan(0)
        assertThat(at15).isLessThan(40)
        assertThat(alpha(FieldRasterInterpolator.cloudRgba(90.0, 1f))).isGreaterThan(180)
        assertThat(alpha(FieldRasterInterpolator.cloudRgba(95.0, 1f))).isGreaterThan(100)
    }

    @Test
    fun renderRequiresEnoughSamples() {
        val bounds = FieldRasterInterpolator.Bounds(50.0, 52.0, 4.0, 6.0)
        assertThat(
            FieldRasterInterpolator.renderTemperature(
                listOf(SamplePoint(50.0, 4.0, 10.0)),
                bounds,
            ),
        ).isNull()
    }

    @Test
    fun gridRender_allNull_isTransparent() {
        val grid = arrayOf(
            arrayOf(null, null),
            arrayOf(null, null),
        )
        val bounds = FieldRasterInterpolator.Bounds(50.0, 52.0, 4.0, 6.0)
        val bitmap = FieldRasterInterpolator.renderTemperature(
            grid,
            bounds,
            rows = 2,
            cols = 2,
            width = 8,
            height = 8,
        )!!
        assertThat(alpha(bitmap.getPixel(4, 4))).isEqualTo(0)
    }

    @Test
    fun legendStops_nonEmpty() {
        assertThat(FieldRasterInterpolator.temperatureLegendStopsCelsius()).hasSize(4)
        assertThat(FieldRasterInterpolator.cloudLegendStops()).isNotEmpty()
    }

    private fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF
}
