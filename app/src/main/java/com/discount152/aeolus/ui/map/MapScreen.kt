package com.discount152.aeolus.ui.map

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discount152.aeolus.domain.models.GeoLocation
import com.discount152.aeolus.domain.units.UnitConverter
import com.discount152.aeolus.domain.units.UnitPreferences
import com.discount152.aeolus.map.FieldRasterInterpolator
import com.discount152.aeolus.map.FieldSampleResult
import com.discount152.aeolus.map.MapOverlayController
import com.discount152.aeolus.map.RadarDoubleBuffer
import com.discount152.aeolus.map.RadarState
import com.discount152.aeolus.map.RadarTiles
import com.discount152.aeolus.map.WeatherLayer
import com.discount152.aeolus.ui.state.WeatherViewModel
import com.discount152.aeolus.ui.state.aeolusContainer
import com.discount152.aeolus.ui.state.rememberWeatherViewModel
import com.discount152.aeolus.ui.theme.PhosphorGreen
import com.discount152.aeolus.ui.theme.TerminalBackground
import com.discount152.aeolus.ui.theme.TerminalElevated
import com.discount152.aeolus.ui.theme.TerminalMuted
import com.discount152.aeolus.ui.theme.TerminalText
import kotlin.math.roundToInt
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
    val radarBuffer = remember { RadarDoubleBuffer(belowLayerId = MARKER_LAYER) }

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var layer by remember { mutableStateOf(WeatherLayer.RADAR) }
    var radar by remember { mutableStateOf(RadarState()) }
    var frameIndex by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var overlayOpacity by remember { mutableFloatStateOf(state.radarOpacity) }
    var fieldSample by remember { mutableStateOf<FieldSampleResult?>(null) }
    var sampleError by remember { mutableStateOf<String?>(null) }
    var idleJob by remember { mutableStateOf<Job?>(null) }
    var lastCenteredLocationId by remember { mutableStateOf<String?>(null) }

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

    // Double-buffer radar: keep active frame visible until staging is ready
    LaunchedEffect(frameIndex, radar, layer, overlayOpacity, mapReady) {
        if (!mapReady || layer != WeatherLayer.RADAR) return@LaunchedEffect
        if (radar.host.isNullOrBlank() || radar.frames.isEmpty()) return@LaunchedEffect
        val frame = radar.frames[frameIndex.coerceIn(0, radar.frames.lastIndex)]
        val template = RadarTiles.tileTemplate(radar.host!!, frame.path)
        mapView?.getMapAsync { map ->
            map.style?.let { style ->
                clearField(style)
                radarBuffer.showFrame(style, template, overlayOpacity)
            }
        }
        // Keep active frame visible while staging tiles populate, then swap atomically
        delay(320)
        mapView?.getMapAsync { map ->
            map.style?.let { radarBuffer.commitSwap(it) }
        }
    }

    LaunchedEffect(fieldSample, layer, overlayOpacity, mapReady) {
        if (!mapReady || layer == WeatherLayer.RADAR) return@LaunchedEffect
        val sample = fieldSample ?: return@LaunchedEffect
        mapView?.getMapAsync { map ->
            map.style?.let { applyFieldRaster(it, sample, layer, overlayOpacity, radarBuffer) }
        }
    }

    // Initial / weather-driven recenter only when location id actually changes
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
                val result = overlay.sampleField(
                    active,
                    latSouth = b.latitudeSouth,
                    latNorth = b.latitudeNorth,
                    lonWest = b.longitudeWest,
                    lonEast = b.longitudeEast,
                )
                fieldSample = result
                sampleError = null
            }.onFailure {
                sampleError = "${active.name} OVERLAY UNAVAILABLE"
                fieldSample = null
            }
        }
    }

    /** Map-owned locate: recenter camera for any overlay, then refresh field if needed. */
    fun locateOnMap() {
        scope.launch {
            val loc = container.locationService.currentLocation()
            if (loc == null) {
                vm.useDeviceLocation()
                return@launch
            }
            lastCenteredLocationId = loc.id
            val activeLayer = layer
            mapView?.getMapAsync { map ->
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 10.0),
                )
                map.style?.let { setMarker(it, loc.latitude, loc.longitude) }
            }
            // Keep weather tabs in sync without coupling overlay selection to radar paths
            vm.selectLocation(loc)
            // Camera idle will resample; also nudge if overlay is temp/cloud
            delay(700)
            if (activeLayer != WeatherLayer.RADAR) {
                mapView?.getMapAsync { map -> sampleVisible(map, activeLayer) }
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
                                    if (option == WeatherLayer.RADAR) {
                                        clearField(style)
                                    } else {
                                        radarBuffer.clear(style)
                                    }
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
                IconButton(onClick = { locateOnMap() }) {
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
                                mapView?.getMapAsync { map ->
                                    map.style?.let { radarBuffer.setOpacity(it, overlayOpacity) }
                                }
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
                    TemperatureLegend(units = state.units)
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
                    CloudLegend()
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

@Composable
private fun TemperatureLegend(units: UnitPreferences) {
    val stops = FieldRasterInterpolator.temperatureLegendStopsCelsius()
    val colors = stops.map { Color(it.second) }
    val labels = stops.map { (c, _) ->
        val v = UnitConverter.convertTemperature(c, units.temperature).roundToInt()
        "$v${UnitConverter.temperatureSymbol(units.temperature)}"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalBackground.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        Text(
            "TEMPERATURE",
            color = TerminalMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .background(Brush.horizontalGradient(colors), RoundedCornerShape(4.dp)),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            labels.forEach { label ->
                Text(label, color = TerminalText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun CloudLegend() {
    val stops = FieldRasterInterpolator.cloudLegendStops()
    val colors = stops.map { Color(it.second).copy(alpha = 1f) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalBackground.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        Text(
            "CLOUD COVER",
            color = TerminalMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .background(Brush.horizontalGradient(colors), RoundedCornerShape(4.dp)),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            stops.forEach { (pct, _) ->
                Text("$pct%", color = TerminalText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
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

private fun clearField(style: Style) {
    style.getLayer(FIELD_LAYER)?.let { style.removeLayer(it) }
    style.getSource(FIELD_SOURCE)?.let { style.removeSource(it) }
}

private fun applyFieldRaster(
    style: Style,
    sample: FieldSampleResult,
    layer: WeatherLayer,
    opacity: Float,
    radarBuffer: RadarDoubleBuffer,
) {
    radarBuffer.clear(style)
    val bounds = sample.bounds
    val bitmap = when (layer) {
        WeatherLayer.TEMPERATURE -> FieldRasterInterpolator.renderTemperature(
            sample.values, bounds, sample.rows, sample.cols, opacity = opacity,
        )
        WeatherLayer.CLOUDS -> FieldRasterInterpolator.renderCloudCover(
            sample.values, bounds, sample.rows, sample.cols, opacity = opacity,
        )
        WeatherLayer.RADAR -> null
    } ?: return

    val quad = LatLngQuad(
        LatLng(bounds.latNorth, bounds.lonWest),
        LatLng(bounds.latNorth, bounds.lonEast),
        LatLng(bounds.latSouth, bounds.lonEast),
        LatLng(bounds.latSouth, bounds.lonWest),
    )
    val existing = style.getSource(FIELD_SOURCE) as? ImageSource
    if (existing != null) {
        existing.setCoordinates(quad)
        existing.setImage(bitmap)
    } else {
        style.addSource(ImageSource(FIELD_SOURCE, quad, bitmap))
        val fieldLayer = RasterLayer(FIELD_LAYER, FIELD_SOURCE).withProperties(
            PropertyFactory.rasterOpacity(1f),
            PropertyFactory.rasterFadeDuration(0f),
        )
        if (style.getLayer(MARKER_LAYER) != null) {
            style.addLayerBelow(fieldLayer, MARKER_LAYER)
        } else {
            style.addLayer(fieldLayer)
        }
    }
}

private fun setMarker(style: Style, lat: Double, lon: Double) {
    ensureMarker(style)
    val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat))
    (style.getSource(MARKER_SOURCE) as? GeoJsonSource)
        ?.setGeoJson(FeatureCollection.fromFeatures(arrayOf(feature)))
}
