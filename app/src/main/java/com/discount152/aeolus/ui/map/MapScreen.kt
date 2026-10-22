package com.discount152.aeolus.ui.map

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import com.discount152.aeolus.map.FieldRasterInterpolator
import com.discount152.aeolus.map.MapOverlayController
import com.discount152.aeolus.map.RadarState
import com.discount152.aeolus.map.RadarTiles
import com.discount152.aeolus.map.SamplePoint
import com.discount152.aeolus.map.WeatherLayer
import com.discount152.aeolus.ui.state.WeatherViewModel
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
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
private const val RADAR_SOURCE = "aeolus-radar"
private const val RADAR_LAYER = "aeolus-radar-layer"
private const val FIELD_SOURCE = "aeolus-field"
private const val FIELD_LAYER = "aeolus-field-layer"
private const val MARKER_SOURCE = "aeolus-marker"
private const val MARKER_LAYER = "aeolus-marker-layer"

@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = context.aeolusContainer()
    val vm: WeatherViewModel = rememberWeatherViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val overlay = remember {
        MapOverlayController(container.rainViewerApi, container.okHttpClient, container.json)
    }

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var layer by remember { mutableStateOf(WeatherLayer.RADAR) }
    var radar by remember { mutableStateOf(RadarState()) }
    var frameIndex by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var overlayOpacity by remember { mutableFloatStateOf(state.radarOpacity) }
    var samples by remember { mutableStateOf<List<SamplePoint>>(emptyList()) }
    var sampleBounds by remember {
        mutableStateOf<FieldRasterInterpolator.Bounds?>(null)
    }
    var sampleError by remember { mutableStateOf<String?>(null) }
    var idleJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        radar = overlay.loadRadar()
        frameIndex = radar.frames.lastIndex.coerceAtLeast(0)
    }
    LaunchedEffect(state.radarOpacity) { overlayOpacity = state.radarOpacity }

    LaunchedEffect(playing, radar.frames, layer) {
        if (!playing || layer != WeatherLayer.RADAR || radar.frames.isEmpty()) return@LaunchedEffect
        while (playing) {
            delay(500)
            frameIndex = (frameIndex + 1) % radar.frames.size
        }
    }

    LaunchedEffect(frameIndex, radar, layer, overlayOpacity, mapReady) {
        if (!mapReady || layer != WeatherLayer.RADAR) return@LaunchedEffect
        mapView?.getMapAsync { map ->
            map.style?.let { applyRadarFrame(it, radar, frameIndex, overlayOpacity) }
        }
    }

    LaunchedEffect(samples, sampleBounds, layer, overlayOpacity, mapReady) {
        if (!mapReady || layer == WeatherLayer.RADAR) return@LaunchedEffect
        val bounds = sampleBounds ?: return@LaunchedEffect
        mapView?.getMapAsync { map ->
            map.style?.let { applyFieldRaster(it, samples, bounds, layer, overlayOpacity) }
        }
    }

    LaunchedEffect(state.location, mapReady) {
        val loc = state.location ?: return@LaunchedEffect
        if (!mapReady) return@LaunchedEffect
        mapView?.getMapAsync { map ->
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 7.0))
            map.style?.let { setMarker(it, loc.latitude, loc.longitude) }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            playing = false
            idleJob?.cancel()
            mapView?.onPause()
            mapView?.onStop()
            mapView?.onDestroy()
        }
    }

    fun sampleVisible(map: org.maplibre.android.maps.MapLibreMap, active: WeatherLayer) {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(650)
            if (active == WeatherLayer.RADAR) return@launch
            runCatching {
                val b = map.projection.visibleRegion.latLngBounds
                val bounds = FieldRasterInterpolator.Bounds(
                    latSouth = b.latitudeSouth,
                    latNorth = b.latitudeNorth,
                    lonWest = b.longitudeWest,
                    lonEast = b.longitudeEast,
                )
                samples = overlay.sampleField(
                    active,
                    latSouth = bounds.latSouth,
                    latNorth = bounds.latNorth,
                    lonWest = bounds.lonWest,
                    lonEast = bounds.lonEast,
                )
                sampleBounds = bounds
                sampleError = null
            }.onFailure {
                sampleError = "${active.name} OVERLAY UNAVAILABLE"
                samples = emptyList()
                sampleBounds = null
            }
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
                                )
                                true
                            }
                            map.addOnCameraIdleListener { sampleVisible(map, layer) }
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WeatherLayer.entries.forEach { option ->
                    FilterChip(
                        selected = layer == option,
                        onClick = {
                            layer = option
                            playing = false
                            mapView?.getMapAsync { map ->
                                map.style?.let { style ->
                                    clearRadar(style)
                                    clearField(style)
                                }
                                if (option == WeatherLayer.RADAR) {
                                    scope.launch { radar = overlay.loadRadar() }
                                } else {
                                    sampleVisible(map, option)
                                }
                            }
                        },
                        label = { Text(option.name, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TerminalElevated,
                            selectedLabelColor = PhosphorGreen,
                            containerColor = TerminalBackground.copy(alpha = 0.85f),
                            labelColor = TerminalMuted,
                        ),
                    )
                }
                IconButton(onClick = { vm.useDeviceLocation() }) {
                    Icon(Icons.Outlined.MyLocation, contentDescription = "Locate me", tint = PhosphorGreen)
                }
            }

            when (layer) {
                WeatherLayer.RADAR -> {
                    if (radar.unavailable) {
                        Text(
                            "RADAR DATA UNAVAILABLE",
                            color = TerminalText,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.background(TerminalElevated, RoundedCornerShape(8.dp)).padding(8.dp),
                        )
                    } else if (radar.frames.isNotEmpty()) {
                        val frame = radar.frames[frameIndex.coerceIn(0, radar.frames.lastIndex)]
                        val time = DateTimeFormatter.ofPattern("HH:mm 'UTC'")
                            .withZone(ZoneOffset.UTC)
                            .format(Instant.ofEpochSecond(frame.time))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { playing = !playing }) {
                                Icon(
                                    if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                    contentDescription = if (playing) "Pause radar" else "Play radar",
                                    tint = PhosphorGreen,
                                )
                            }
                            Text(time, color = TerminalText, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                        Slider(
                            value = frameIndex.toFloat(),
                            onValueChange = {
                                playing = false
                                frameIndex = it.toInt().coerceIn(0, radar.frames.lastIndex)
                            },
                            valueRange = 0f..radar.frames.lastIndex.coerceAtLeast(0).toFloat(),
                            colors = SliderDefaults.colors(thumbColor = PhosphorGreen, activeTrackColor = PhosphorGreen),
                        )
                        Text(
                            "Radar opacity · source z≤${RadarTiles.SOURCE_MAX_ZOOM} (overscales beyond)",
                            color = TerminalMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Slider(
                            value = overlayOpacity,
                            onValueChange = {
                                overlayOpacity = it
                                vm.updateRadarOpacity(it)
                            },
                            colors = SliderDefaults.colors(thumbColor = PhosphorGreen, activeTrackColor = PhosphorGreen),
                        )
                        Text("Radar © RainViewer", color = TerminalMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                WeatherLayer.TEMPERATURE -> {
                    sampleError?.let {
                        Text(
                            it,
                            color = TerminalText,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.background(TerminalElevated, RoundedCornerShape(8.dp)).padding(8.dp),
                        )
                    }
                    Text(
                        "Temp field −15→30°C · Open-Meteo samples (interpolated)",
                        color = TerminalMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Slider(
                        value = overlayOpacity,
                        onValueChange = { overlayOpacity = it },
                        colors = SliderDefaults.colors(thumbColor = PhosphorGreen, activeTrackColor = PhosphorGreen),
                    )
                }
                WeatherLayer.CLOUDS -> {
                    sampleError?.let {
                        Text(
                            it,
                            color = TerminalText,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.background(TerminalElevated, RoundedCornerShape(8.dp)).padding(8.dp),
                        )
                    }
                    Text(
                        "Cloud cover field 0→100% · Open-Meteo samples (interpolated)",
                        color = TerminalMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Slider(
                        value = overlayOpacity,
                        onValueChange = { overlayOpacity = it },
                        colors = SliderDefaults.colors(thumbColor = PhosphorGreen, activeTrackColor = PhosphorGreen),
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

private fun clearRadar(style: Style) {
    style.getLayer(RADAR_LAYER)?.let { style.removeLayer(it) }
    style.getSource(RADAR_SOURCE)?.let { style.removeSource(it) }
}

private fun clearField(style: Style) {
    style.getLayer(FIELD_LAYER)?.let { style.removeLayer(it) }
    style.getSource(FIELD_SOURCE)?.let { style.removeSource(it) }
}

private fun applyRadarFrame(style: Style, radar: RadarState, index: Int, opacity: Float) {
    if (radar.host.isNullOrBlank() || radar.frames.isEmpty()) return
    val frame = radar.frames[index.coerceIn(0, radar.frames.lastIndex)]
    val template = RadarTiles.tileTemplate(radar.host!!, frame.path)
    clearRadar(style)
    clearField(style)
    val tiles = TileSet("2.1.0", template).apply {
        minZoom = RadarTiles.SOURCE_MIN_ZOOM.toFloat()
        // Cap source zoom at 7 so MapLibre overscales instead of requesting z8+
        maxZoom = RadarTiles.SOURCE_MAX_ZOOM.toFloat()
    }
    style.addSource(RasterSource(RADAR_SOURCE, tiles, 256))
    val radarLayer = RasterLayer(RADAR_LAYER, RADAR_SOURCE).withProperties(
        PropertyFactory.rasterOpacity(opacity),
    )
    if (style.getLayer(MARKER_LAYER) != null) {
        style.addLayerBelow(radarLayer, MARKER_LAYER)
    } else {
        style.addLayer(radarLayer)
    }
}

private fun applyFieldRaster(
    style: Style,
    samples: List<SamplePoint>,
    bounds: FieldRasterInterpolator.Bounds,
    layer: WeatherLayer,
    opacity: Float,
) {
    clearRadar(style)
    clearField(style)
    val bitmap = when (layer) {
        WeatherLayer.TEMPERATURE -> FieldRasterInterpolator.renderTemperature(samples, bounds, opacity = opacity)
        WeatherLayer.CLOUDS -> FieldRasterInterpolator.renderCloudCover(samples, bounds, opacity = opacity)
        WeatherLayer.RADAR -> null
    } ?: return

    val quad = LatLngQuad(
        LatLng(bounds.latNorth, bounds.lonWest),
        LatLng(bounds.latNorth, bounds.lonEast),
        LatLng(bounds.latSouth, bounds.lonEast),
        LatLng(bounds.latSouth, bounds.lonWest),
    )
    style.addSource(ImageSource(FIELD_SOURCE, quad, bitmap))
    val fieldLayer = RasterLayer(FIELD_LAYER, FIELD_SOURCE).withProperties(
        PropertyFactory.rasterOpacity(1f),
    )
    if (style.getLayer(MARKER_LAYER) != null) {
        style.addLayerBelow(fieldLayer, MARKER_LAYER)
    } else {
        style.addLayer(fieldLayer)
    }
}

private fun setMarker(style: Style, lat: Double, lon: Double) {
    ensureMarker(style)
    val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat))
    (style.getSource(MARKER_SOURCE) as? GeoJsonSource)
        ?.setGeoJson(FeatureCollection.fromFeatures(arrayOf(feature)))
}
