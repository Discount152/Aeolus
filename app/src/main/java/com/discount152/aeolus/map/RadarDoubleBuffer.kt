package com.discount152.aeolus.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/**
 * Two-slot radar raster buffer: keep the active frame visible while the next
 * frame's tiles load in the staging slot, then atomically swap opacity.
 */
class RadarDoubleBuffer(
    private val belowLayerId: String = "aeolus-marker-layer",
) {
    private var activeSlot = 0
    private var lastTemplate: String? = null
    private var lastOpacity = 0.7f

    fun sourceId(slot: Int) = "aeolus-radar-src-$slot"
    fun layerId(slot: Int) = "aeolus-radar-lyr-$slot"

    fun ensureSlots(style: Style, opacity: Float) {
        lastOpacity = opacity
        for (slot in 0..1) {
            if (style.getSource(sourceId(slot)) == null) {
                // Placeholder empty tile set until first real frame
                val tiles = TileSet("2.1.0", "https://tilecache.rainviewer.com/v2/radar/0/256/{z}/{x}/{y}/2/1_1.png").apply {
                    minZoom = RadarTiles.SOURCE_MIN_ZOOM.toFloat()
                    maxZoom = RadarTiles.SOURCE_MAX_ZOOM.toFloat()
                }
                style.addSource(RasterSource(sourceId(slot), tiles, 256))
            }
            if (style.getLayer(layerId(slot)) == null) {
                val layer = RasterLayer(layerId(slot), sourceId(slot)).withProperties(
                    PropertyFactory.rasterOpacity(0f),
                    PropertyFactory.rasterFadeDuration(0f),
                )
                if (style.getLayer(belowLayerId) != null) {
                    style.addLayerBelow(layer, belowLayerId)
                } else {
                    style.addLayer(layer)
                }
            } else {
                style.getLayer(layerId(slot))?.setProperties(
                    PropertyFactory.rasterFadeDuration(0f),
                )
            }
        }
    }

    /**
     * Show [template] without removing the currently visible frame.
     * Returns true if a swap was scheduled/applied.
     */
    fun showFrame(style: Style, template: String, opacity: Float) {
        ensureSlots(style, opacity)
        lastOpacity = opacity
        if (template == lastTemplate) {
            // Same frame — just refresh active opacity
            style.getLayer(layerId(activeSlot))?.setProperties(
                PropertyFactory.rasterOpacity(opacity),
                PropertyFactory.rasterFadeDuration(0f),
            )
            style.getLayer(layerId(1 - activeSlot))?.setProperties(
                PropertyFactory.rasterOpacity(0f),
                PropertyFactory.rasterFadeDuration(0f),
            )
            return
        }

        val staging = 1 - activeSlot
        // Replace staging source URL by removing only the staging slot (active stays visible)
        style.getLayer(layerId(staging))?.let { style.removeLayer(it) }
        style.getSource(sourceId(staging))?.let { style.removeSource(it) }

        val tiles = TileSet("2.1.0", template).apply {
            minZoom = RadarTiles.SOURCE_MIN_ZOOM.toFloat()
            maxZoom = RadarTiles.SOURCE_MAX_ZOOM.toFloat()
        }
        style.addSource(RasterSource(sourceId(staging), tiles, 256))
        val stagingLayer = RasterLayer(layerId(staging), sourceId(staging)).withProperties(
            // Start transparent; become opaque after brief settle so tiles can populate
            PropertyFactory.rasterOpacity(0f),
            PropertyFactory.rasterFadeDuration(0f),
        )
        if (style.getLayer(belowLayerId) != null) {
            style.addLayerBelow(stagingLayer, belowLayerId)
        } else {
            style.addLayer(stagingLayer)
        }

        // Keep active fully visible during staging load
        style.getLayer(layerId(activeSlot))?.setProperties(
            PropertyFactory.rasterOpacity(opacity),
            PropertyFactory.rasterFadeDuration(0f),
        )

        lastTemplate = template
        // Caller should invoke commitSwap after a short delay so tiles can arrive
        pendingStaging = staging
    }

    private var pendingStaging: Int? = null

    fun commitSwap(style: Style) {
        val staging = pendingStaging ?: return
        val opacity = lastOpacity
        // Staging becomes active; previous active goes transparent (kept for next cycle)
        style.getLayer(layerId(staging))?.setProperties(
            PropertyFactory.rasterOpacity(opacity),
            PropertyFactory.rasterFadeDuration(0f),
        )
        style.getLayer(layerId(1 - staging))?.setProperties(
            PropertyFactory.rasterOpacity(0f),
            PropertyFactory.rasterFadeDuration(0f),
        )
        activeSlot = staging
        pendingStaging = null
    }

    fun setOpacity(style: Style, opacity: Float) {
        lastOpacity = opacity
        style.getLayer(layerId(activeSlot))?.setProperties(PropertyFactory.rasterOpacity(opacity))
    }

    fun clear(style: Style) {
        for (slot in 0..1) {
            style.getLayer(layerId(slot))?.let { style.removeLayer(it) }
            style.getSource(sourceId(slot))?.let { style.removeSource(it) }
        }
        lastTemplate = null
        pendingStaging = null
        activeSlot = 0
    }
}
