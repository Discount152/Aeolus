package com.discount152.aeolus.map

import android.graphics.Bitmap
import android.graphics.Canvas

/**
 * Stitch decoded RainViewer XYZ tiles into one complete mosaic bitmap.
 * Tile order must match [RadarFootprint.tiles] (row-major, y then x).
 */
object RadarMosaic {
    fun stitch(
        footprint: RadarFootprint,
        tileBitmaps: List<Bitmap>,
    ): Bitmap {
        require(tileBitmaps.size == footprint.tiles.size) {
            "Expected ${footprint.tiles.size} tiles, got ${tileBitmaps.size}"
        }
        require(footprint.tilesX > 0 && footprint.tilesY > 0)
        val w = footprint.mosaicWidthPx
        val h = footprint.mosaicHeightPx
        val mosaic = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(mosaic)
        val size = footprint.tileSizePx
        var i = 0
        for (row in 0 until footprint.tilesY) {
            for (col in 0 until footprint.tilesX) {
                val bmp = tileBitmaps[i++]
                canvas.drawBitmap(bmp, (col * size).toFloat(), (row * size).toFloat(), null)
            }
        }
        return mosaic
    }
}
