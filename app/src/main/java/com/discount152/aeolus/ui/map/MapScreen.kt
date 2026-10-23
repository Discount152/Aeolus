package com.discount152.aeolus.ui.map

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discount152.aeolus.domain.models.GeoLocation
import com.discount152.aeolus.map.RadarFootprint
import com.discount152.aeolus.map.RadarFrameAsset
import com.discount152.aeolus.map.RadarFramePipeline
import com.discount152.aeolus.map.RadarFrameStatus
import com.discount152.aeolus.map.RadarViewport
import com.discount152.aeolus.ui.state.aeolusContainer
import com.discount152.aeolus.ui.state.rememberWeatherViewModel
import com.discount152.aeolus.ui.theme.PhosphorGreen
import com.discount152.aeolus.ui.theme.TerminalBackground
import com.discount152.aeolus.ui.theme.TerminalElevated
import com.discount152.aeolus.ui.theme.TerminalMuted
import com.discount152.aeolus.ui.theme.TerminalText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
private const val MARKER_SOURCE = "aeolus-marker"
private const val MARKER_LAYER = "aeolus-marker-layer"
private const val RADAR_IMAGE_SOURCE = "aeolus-radar-image"
private const val RADAR_IMAGE_LAYER = "aeolus-radar-image-layer"

@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = context.aeolusContainer()
    val vm = rememberWeatherViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val pipeline = remember {
        RadarFramePipeline(container.rainViewerApi, container.okHttpClient)
    }
    val session by pipeline.session.collectAsStateWithLifecycle()

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var overlayOpacity by remember { mutableFloatStateOf(state.radarOpacity) }
    var lastCenteredLocationId by remember { mutableStateOf<String?>(null) }
    var activeFootprint by remember { mutableStateOf<RadarFootprint?>(null) }
    var playIndex by remember { mutableIntStateOf(0) }
    var displayedTime by remember { mutableStateOf<Long?>(null) }
    var idleJob by remember { mutableStateOf<Job?>(null) }
    var loadJob by remember { mutableStateOf<Job?>(null) }

    val readyFrames = remember(session) { pipeline.playbackFrames() }

    LaunchedEffect(state.radarOpacity) { overlayOpacity = state.radarOpacity }

    LaunchedEffect(session?.newestReady?.time, mapReady) {
        val newest = session?.newestReady ?: return@LaunchedEffect
        if (!mapReady) return@LaunchedEffect
        if (playing) return@LaunchedEffect
        displayedTime = newest.time
        mapView?.getMapAsync { map ->
            map.style?.let { style ->
                session?.footprint?.let { fp ->
                    applyRadarBitmap(style, newest.bitmap, fp, overlayOpacity)
                }
            }
        }
    }

    LaunchedEffect(playing, readyFrames) {
        if (!playing) return@LaunchedEffect
        val frames = readyFrames
        if (frames.size < 2) {
            playing = false
            return@LaunchedEffect
        }
        playIndex = playIndex.coerceIn(0, frames.lastIndex)
        while (playing) {
            val list = pipeline.playbackFrames()
            if (list.size < 2) {
                playing = false
                break
            }
            playIndex = (playIndex + 1) % list.size
            val frame = list[playIndex]
            displayedTime = frame.time
            mapView?.getMapAsync { map ->
                map.style?.let { style ->
                    session?.footprint?.let { fp ->
                        applyRadarBitmap(style, frame.bitmap, fp, overlayOpacity)
                    }
                }
            }
            delay(500)
        }
    }

    LaunchedEffect(state.location?.id, mapReady) {
        val loc = state.location ?: return@LaunchedEffect
        if (!mapReady) return@LaunchedEffect
        if (loc.id == lastCenteredLocationId) return@LaunchedEffect
        lastCenteredLocationId = loc.id
        mapView?.getMapAsync { map ->
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 7.0))
            map.style?.let { setMarker(it, loc.latitude, loc.longitude) }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            playing = false
            idleJob?.cancel()
            loadJob?.cancel()
            mapView?.onPause()
            mapView?.onStop()
            mapView?.onDestroy()
        }
    }

    fun reloadRadarForMap(map: org.maplibre.android.maps.MapLibreMap) {
        val b = map.projection.visibleRegion.latLngBounds
        val footprint = RadarViewport.footprintForVisibleBounds(
            latSouth = b.latitudeSouth,
            latNorth = b.latitudeNorth,
            lonWest = b.longitudeWest,
            lonEast = b.longitudeEast,
        )
        val current = activeFootprint
        if (current != null && current.coversVisible(
                b.latitudeSouth, b.latitudeNorth, b.longitudeWest, b.longitudeEast,
            )
        ) {
            return
        }
        playing = false
        activeFootprint = footprint
        loadJob?.cancel()
        loadJob = scope.launch {
            pipeline.loadForViewport(footprint)
        }
    }

    fun locateOnMap() {
        scope.launch {
            val loc = container.locationService.currentLocation()
            if (loc == null) {
                vm.useDeviceLocation()
                return@launch
            }
            lastCenteredLocationId = loc.id
            mapView?.getMapAsync { map ->
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 10.0),
                )
                map.style?.let { setMarker(it, loc.latitude, loc.longitude) }
            }
            vm.selectLocation(loc, saveToList = false)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(TerminalBackground)) {
        AndroidView(
            factory = { ctx ->
                MapView(ctx).also { mv ->
                    mapView = mv
                    mv.onCreate(null)
                    mv.onStart()
                    mv.onResume()
                    mv.getMapAsync { map ->
                        map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                            ensureMarker(style)
                            mapReady = true
                            state.location?.let { setMarker(style, it.latitude, it.longitude) }
                            map.addOnMapClickListener { latLng ->
                                vm.selectLocation(
                                    GeoLocation(
                                        id = "map:${latLng.latitude},${latLng.longitude}",
                                        name = "Map pin",
                                        latitude = latLng.latitude,
                                        longitude = latLng.longitude,
                                        timezone = java.util.TimeZone.getDefault().id,
                                    ),
                                    saveToList = false,
                                )
                                true
                            }
                            map.addOnCameraMoveStartedListener {
                                if (playing) playing = false
                            }
                            map.addOnCameraIdleListener {
                                idleJob?.cancel()
                                idleJob = scope.launch {
                                    delay(450)
                                    reloadRadarForMap(map)
                                }
                            }
                            // Initial load
                            reloadRadarForMap(map)
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { it.onResume() },
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "RADAR",
                    color = PhosphorGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .background(TerminalBackground.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                IconButton(onClick = { locateOnMap() }) {
                    Icon(Icons.Outlined.MyLocation, contentDescription = "Locate me", tint = PhosphorGreen)
                }
            }

            val frames = readyFrames
            val sess = session
            when {
                sess == null || (sess.frames.isEmpty() && !sess.preparing) -> {
                    Text(
                        "RADAR DATA UNAVAILABLE",
                        color = TerminalText,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.background(TerminalElevated, RoundedCornerShape(8.dp)).padding(8.dp),
                    )
                }
                else -> {
                    val displayFrame: RadarFrameAsset? =
                        frames.find { it.time == displayedTime } ?: sess.newestReady
                    val timeLabel = displayFrame?.let {
                        DateTimeFormatter.ofPattern("HH:mm 'UTC'")
                            .withZone(ZoneOffset.UTC)
                            .format(Instant.ofEpochSecond(it.time))
                    } ?: "—"

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val canPlay = sess.playable
                        IconButton(
                            onClick = {
                                if (!canPlay) return@IconButton
                                playing = !playing
                                if (playing) {
                                    val list = pipeline.playbackFrames()
                                    playIndex = list.indexOfFirst { it.time == displayedTime }
                                        .takeIf { it >= 0 } ?: list.lastIndex.coerceAtLeast(0)
                                }
                            },
                            enabled = canPlay || playing,
                        ) {
                            if (sess.preparing && !canPlay) {
                                CircularProgressIndicator(
                                    color = PhosphorGreen,
                                    modifier = Modifier.padding(8.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                    contentDescription = if (playing) "Pause radar" else "Play radar",
                                    tint = if (canPlay) PhosphorGreen else TerminalMuted,
                                )
                            }
                        }
                        Text(timeLabel, color = TerminalText, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        if (sess.preparing) {
                            Text(
                                "  PREPARING…",
                                color = TerminalMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            )
                        }
                    }

                    if (frames.isNotEmpty()) {
                        val idx = frames.indexOfFirst { it.time == displayedTime }
                            .takeIf { it >= 0 } ?: frames.lastIndex
                        Slider(
                            value = idx.toFloat(),
                            onValueChange = { v ->
                                playing = false
                                val i = v.toInt().coerceIn(0, frames.lastIndex)
                                val target = frames[i]
                                displayedTime = target.time
                                mapView?.getMapAsync { map ->
                                    map.style?.let { style ->
                                        sess.footprint.let { fp ->
                                            applyRadarBitmap(style, target.bitmap, fp, overlayOpacity)
                                        }
                                    }
                                }
                            },
                            valueRange = 0f..frames.lastIndex.coerceAtLeast(0).toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = PhosphorGreen,
                                activeTrackColor = PhosphorGreen,
                            ),
                        )
                    }

                    // Scrub into not-yet-ready historical: request that complete frame only
                    val allTimes = sess.frames.map { it.time }
                    if (allTimes.size > frames.size) {
                        Text(
                            "Ready ${frames.size}/${sess.frames.count { it.status != RadarFrameStatus.FAILED }} frames · ~1 image/timestamp",
                            color = TerminalMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    Text(
                        "Opacity · centered ${sess.footprint.sizePx}px z${sess.footprint.zoom}",
                        color = TerminalMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Slider(
                        value = overlayOpacity,
                        onValueChange = {
                            overlayOpacity = it
                            vm.updateRadarOpacity(it)
                            mapView?.getMapAsync { map ->
                                map.style?.getLayer(RADAR_IMAGE_LAYER)?.setProperties(
                                    PropertyFactory.rasterOpacity(it),
                                )
                            }
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = PhosphorGreen,
                            activeTrackColor = PhosphorGreen,
                        ),
                    )
                    Text(
                        "Light rain → Heavy  ·  Radar © RainViewer",
                        color = TerminalMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        Text(
            "© OpenFreeMap · © OpenStreetMap contributors · MapLibre",
            color = TerminalMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(TerminalBackground.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                .padding(4.dp),
        )
    }
}

private fun applyRadarBitmap(
    style: Style,
    bitmap: Bitmap?,
    footprint: RadarFootprint,
    opacity: Float,
) {
    if (bitmap == null || bitmap.isRecycled) return
    val quad = LatLngQuad(
        LatLng(footprint.latNorth, footprint.lonWest),
        LatLng(footprint.latNorth, footprint.lonEast),
        LatLng(footprint.latSouth, footprint.lonEast),
        LatLng(footprint.latSouth, footprint.lonWest),
    )
    val existing = style.getSource(RADAR_IMAGE_SOURCE) as? ImageSource
    if (existing != null) {
        existing.setCoordinates(quad)
        existing.setImage(bitmap)
        style.getLayer(RADAR_IMAGE_LAYER)?.setProperties(
            PropertyFactory.rasterOpacity(opacity),
            PropertyFactory.rasterFadeDuration(0f),
        )
    } else {
        style.addSource(ImageSource(RADAR_IMAGE_SOURCE, quad, bitmap))
        val layer = RasterLayer(RADAR_IMAGE_LAYER, RADAR_IMAGE_SOURCE).withProperties(
            PropertyFactory.rasterOpacity(opacity),
            PropertyFactory.rasterFadeDuration(0f),
        )
        if (style.getLayer(MARKER_LAYER) != null) {
            style.addLayerBelow(layer, MARKER_LAYER)
        } else {
            style.addLayer(layer)
        }
    }
}

private fun ensureMarker(style: Style) {
    if (style.getSource(MARKER_SOURCE) == null) {
        style.addSource(GeoJsonSource(MARKER_SOURCE, FeatureCollection.fromFeatures(emptyArray())))
        style.addLayer(
            CircleLayer(MARKER_LAYER, MARKER_SOURCE).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor(AndroidColor.parseColor("#39FF88")),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor(AndroidColor.WHITE),
            ),
        )
    }
}

private fun setMarker(style: Style, lat: Double, lon: Double) {
    ensureMarker(style)
    val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat))
    (style.getSource(MARKER_SOURCE) as? GeoJsonSource)
        ?.setGeoJson(FeatureCollection.fromFeatures(arrayOf(feature)))
}
