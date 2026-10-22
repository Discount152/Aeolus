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
import com.discount152.aeolus.map.MapOverlayController
import com.discount152.aeolus.map.RadarDoubleBuffer
import com.discount152.aeolus.map.RadarState
import com.discount152.aeolus.map.RadarTiles
import com.discount152.aeolus.ui.state.rememberWeatherViewModel
import com.discount152.aeolus.ui.state.aeolusContainer
import com.discount152.aeolus.ui.theme.PhosphorGreen
import com.discount152.aeolus.ui.theme.TerminalBackground
import com.discount152.aeolus.ui.theme.TerminalElevated
import com.discount152.aeolus.ui.theme.TerminalMuted
import com.discount152.aeolus.ui.theme.TerminalText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
private const val MARKER_SOURCE = "aeolus-marker"
private const val MARKER_LAYER = "aeolus-marker-layer"

@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = context.aeolusContainer()
    val vm = rememberWeatherViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val overlay = remember {
        MapOverlayController(container.rainViewerApi, container.okHttpClient, container.json)
    }
    val radarBuffer = remember { RadarDoubleBuffer(belowLayerId = MARKER_LAYER) }

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var radar by remember { mutableStateOf(RadarState()) }
    var frameIndex by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var overlayOpacity by remember { mutableFloatStateOf(state.radarOpacity) }
    var lastCenteredLocationId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        radar = overlay.loadRadar()
        frameIndex = radar.frames.lastIndex.coerceAtLeast(0)
    }
    LaunchedEffect(state.radarOpacity) { overlayOpacity = state.radarOpacity }

    LaunchedEffect(playing, radar.frames) {
        if (!playing || radar.frames.isEmpty()) return@LaunchedEffect
        while (playing) {
            delay(500)
            frameIndex = (frameIndex + 1) % radar.frames.size
        }
    }

    LaunchedEffect(frameIndex, radar, overlayOpacity, mapReady) {
        if (!mapReady) return@LaunchedEffect
        if (radar.host.isNullOrBlank() || radar.frames.isEmpty()) return@LaunchedEffect
        val frame = radar.frames[frameIndex.coerceIn(0, radar.frames.lastIndex)]
        val template = RadarTiles.tileTemplate(radar.host!!, frame.path)
        mapView?.getMapAsync { map ->
            map.style?.let { style ->
                radarBuffer.showFrame(style, template, overlayOpacity)
            }
        }
        delay(320)
        mapView?.getMapAsync { map ->
            map.style?.let { radarBuffer.commitSwap(it) }
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
            mapView?.onPause()
            mapView?.onStop()
            mapView?.onDestroy()
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
                    "Opacity · RainViewer z≤${RadarTiles.SOURCE_MAX_ZOOM}",
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
                Text(
                    "Light rain → Heavy  ·  Radar © RainViewer",
                    color = TerminalMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
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

private fun setMarker(style: Style, lat: Double, lon: Double) {
    ensureMarker(style)
    val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat))
    (style.getSource(MARKER_SOURCE) as? GeoJsonSource)
        ?.setGeoJson(FeatureCollection.fromFeatures(arrayOf(feature)))
}
