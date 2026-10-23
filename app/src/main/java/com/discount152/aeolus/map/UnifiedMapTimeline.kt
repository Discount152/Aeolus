package com.discount152.aeolus.map

import android.graphics.Bitmap

enum class MapFrameSource {
    OBSERVED_RADAR,
    FORECAST_PRECIP,
}

/**
 * One continuous map timeline: past observed radar → NOW → future forecast precip.
 * Position is by absolute timestamp (epoch seconds), not equal array spacing.
 */
data class UnifiedMapFrame(
    /** Absolute UTC epoch seconds — identity; display timezone never alters this. */
    val epochSeconds: Long,
    val source: MapFrameSource,
    val bitmap: Bitmap?,
    val latNorth: Double,
    val latSouth: Double,
    val lonWest: Double,
    val lonEast: Double,
    val sourceLabel: String,
) {
    val ready: Boolean get() = bitmap != null && !bitmap.isRecycled
}

data class UnifiedMapTimeline(
    val frames: List<UnifiedMapFrame>,
    val nowEpochSeconds: Long,
) {
    val readyFrames: List<UnifiedMapFrame>
        get() = frames.filter { it.ready }.sortedBy { it.epochSeconds }

    val playable: Boolean get() = readyFrames.size >= 2

    /** Fractional scrub position in [0,1] by timestamp span (not index). */
    fun scrubPositionFor(epochSeconds: Long): Float {
        val list = readyFrames
        if (list.isEmpty()) return 0f
        if (list.size == 1) return 0f
        val minT = list.first().epochSeconds.toDouble()
        val maxT = list.last().epochSeconds.toDouble()
        if (maxT <= minT) return 0f
        return ((epochSeconds.toDouble() - minT) / (maxT - minT)).toFloat().coerceIn(0f, 1f)
    }

    fun frameAtScrub(position: Float): UnifiedMapFrame? {
        val list = readyFrames
        if (list.isEmpty()) return null
        if (list.size == 1) return list.first()
        val minT = list.first().epochSeconds.toDouble()
        val maxT = list.last().epochSeconds.toDouble()
        val target = minT + position.coerceIn(0f, 1f) * (maxT - minT)
        return list.minByOrNull { kotlin.math.abs(it.epochSeconds - target) }
    }

    fun nearestFrame(epochSeconds: Long): UnifiedMapFrame? =
        readyFrames.minByOrNull { kotlin.math.abs(it.epochSeconds - epochSeconds) }

    fun nowMarkerPosition(): Float = scrubPositionFor(nowEpochSeconds)

    companion object {
        const val OBSERVED_LABEL = "OBSERVED · RainViewer"
        const val FORECAST_LABEL = "FORECAST · ICON"

        fun build(
            observed: List<RadarFrameAsset>,
            observedFootprint: RadarFootprint?,
            forecast: List<ForecastPrecipFrame>,
            forecastFootprint: ForecastPrecipFootprint?,
            nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
        ): UnifiedMapTimeline {
            val frames = mutableListOf<UnifiedMapFrame>()
            if (observedFootprint != null) {
                for (f in observed) {
                    if (f.status != RadarFrameStatus.READY || f.bitmap == null) continue
                    if (f.time > nowEpochSeconds + 120) continue // safety
                    frames.add(
                        UnifiedMapFrame(
                            epochSeconds = f.time,
                            source = MapFrameSource.OBSERVED_RADAR,
                            bitmap = f.bitmap,
                            latNorth = observedFootprint.latNorth,
                            latSouth = observedFootprint.latSouth,
                            lonWest = observedFootprint.lonWest,
                            lonEast = observedFootprint.lonEast,
                            sourceLabel = OBSERVED_LABEL,
                        ),
                    )
                }
            }
            if (forecastFootprint != null) {
                for (f in forecast) {
                    if (f.status != ForecastPrecipFrameStatus.READY || f.bitmap == null) continue
                    val sec = f.hourEpochUtcMs / 1000L
                    if (sec < nowEpochSeconds - 1800) continue // keep near-now and future
                    frames.add(
                        UnifiedMapFrame(
                            epochSeconds = sec,
                            source = MapFrameSource.FORECAST_PRECIP,
                            bitmap = f.bitmap,
                            latNorth = forecastFootprint.latNorth,
                            latSouth = forecastFootprint.latSouth,
                            lonWest = forecastFootprint.lonWest,
                            lonEast = forecastFootprint.lonEast,
                            sourceLabel = FORECAST_LABEL,
                        ),
                    )
                }
            }
            return UnifiedMapTimeline(
                frames = frames.sortedBy { it.epochSeconds },
                nowEpochSeconds = nowEpochSeconds,
            )
        }
    }
}
