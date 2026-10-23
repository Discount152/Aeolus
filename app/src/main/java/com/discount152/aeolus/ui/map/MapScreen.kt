package com.discount152.aeolus.ui.map

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.discount152.aeolus.map.ForecastPrecipFootprint
import com.discount152.aeolus.map.ForecastPrecipPipeline
import com.discount152.aeolus.map.MapPlaybackState
import com.discount152.aeolus.map.MapTimezone
import com.discount152.aeolus.map.MapTimezoneMode
import com.discount152.aeolus.map.PrecipLegend
import com.discount152.aeolus.map.RadarFootprint
import com.discount152.aeolus.map.RadarFramePipeline
import com.discount152.aeolus.map.RadarViewport
import com.discount152.aeolus.map.UnifiedMapFrame
import com.discount152.aeolus.map.UnifiedMapTimeline
import com.discount152.aeolus.map.ViewedTimezoneResolver
import com.discount152.aeolus.ui.state.ConnectivityPresentation
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
import java.time.ZoneId

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
    val radarPipeline = remember {
        RadarFramePipeline(container.rainViewerApi, container.okHttpClient)
    }
    val forecastPipeline = remember {
        ForecastPrecipPipeline(container.forecastApi)
    }
    val tzResolver = remember { ViewedTimezoneResolver(container.forecastApi) }
    val radarSession by radarPipeline.session.collectAsStateWithLifecycle()
    val forecastSession by forecastPipeline.session.collectAsStateWithLifecycle()

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var playback by remember { mutableStateOf(MapPlaybackState.STOPPED) }
    var overlayOpacity by remember { mutableFloatStateOf(state.radarOpacity) }
    var lastCenteredLocationId by remember { mutableStateOf<String?>(null) }
    var activeRadarFootprint by remember { mutableStateOf<RadarFootprint?>(null) }
    var activeForecastFootprint by remember { mutableStateOf<ForecastPrecipFootprint?>(null) }
    var scrub by remember { mutableFloatStateOf(1f) }
    var displayed by remember { mutableStateOf<UnifiedMapFrame?>(null) }
    var idleJob by remember { mutableStateOf<Job?>(null) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var viewedZoneId by remember { mutableStateOf<String?>(state.location?.timezone) }
    var showTzDialog by remember { mutableStateOf(false) }

    val tzMode by container.settingsRepository.mapTimezoneMode.collectAsStateWithLifecycle(
        initialValue = MapTimezoneMode.VIEWED_LOCATION,
    )
    val homeTz by container.settingsRepository.homeTimezoneId.collectAsStateWithLifecycle(
        initialValue = ZoneId.systemDefault().id,
    )
    val customTz by container.settingsRepository.customTimezoneId.collectAsStateWithLifecycle(
        initialValue = ZoneId.systemDefault().id,
    )
    val displayZone = remember(tzMode, viewedZoneId, homeTz, customTz) {
        MapTimezone.resolveZoneId(tzMode, viewedZoneId, homeTz, customTz)
    }

    val timeline = remember(radarSession, forecastSession) {
        UnifiedMapTimeline.build(
            observed = radarPipeline.playbackFrames(),
            observedFootprint = radarSession?.footprint,
            forecast = forecastPipeline.playbackFrames(),
            forecastFootprint = forecastSession?.footprint,
        )
    }
    val ready = timeline.readyFrames
    val offlineMap = state.connectivity == ConnectivityPresentation.OFFLINE_WITH_CACHE ||
        state.connectivity == ConnectivityPresentation.OFFLINE_NO_CACHE

    LaunchedEffect(Unit) { container.settingsRepository.ensureHomeTimezoneInitialized() }
    LaunchedEffect(state.radarOpacity) { overlayOpacity = state.radarOpacity }

    LaunchedEffect(timeline.readyFrames.size, mapReady) {
        if (!mapReady || ready.isEmpty()) return@LaunchedEffect
        if (playback == MapPlaybackState.PLAYING) return@LaunchedEffect
        val newestObs = ready.lastOrNull { it.epochSeconds <= timeline.nowEpochSeconds }
            ?: ready.last()
        scrub = timeline.scrubPositionFor(newestObs.epochSeconds)
        displayed = newestObs
        mapView?.getMapAsync { map ->
            map.style?.let { applyOverlayBitmap(it, newestObs, overlayOpacity) }
        }
    }

    LaunchedEffect(playback, ready) {
        if (playback != MapPlaybackState.PLAYING) return@LaunchedEffect
        while (playback == MapPlaybackState.PLAYING) {
            val list = timeline.readyFrames
            if (list.size < 2) {
                playback = MapPlaybackState.STOPPED
                break
            }
            val current = displayed
            val idx = list.indexOfFirst { it.epochSeconds == current?.epochSeconds }
                .takeIf { it >= 0 } ?: 0
            val next = list[(idx + 1) % list.size]
            displayed = next
            scrub = timeline.scrubPositionFor(next.epochSeconds)
            mapView?.getMapAsync { map ->
                map.style?.let { applyOverlayBitmap(it, next, overlayOpacity) }
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
            playback = MapPlaybackState.STOPPED
            idleJob?.cancel()
            loadJob?.cancel()
            mapView?.onPause()
            mapView?.onStop()
            mapView?.onDestroy()
        }
    }

    fun paddedForecastFootprint(
        latSouth: Double,
        latNorth: Double,
        lonWest: Double,
        lonEast: Double,
    ): ForecastPrecipFootprint {
        val latPad = (latNorth - latSouth) * RadarViewport.PAD_FRACTION
        val lonPad = (lonEast - lonWest).let { if (it < 0) it + 360 else it } * RadarViewport.PAD_FRACTION
        return ForecastPrecipFootprint(
            latSouth = latSouth - latPad,
            latNorth = latNorth + latPad,
            lonWest = RadarViewport.normalizeLon(lonWest - lonPad),
            lonEast = RadarViewport.normalizeLon(lonEast + lonPad),
            rasterSizePx = ForecastPrecipPipeline.DEFAULT_RASTER_SIZE_PX,
        )
    }

    fun reloadOverlays(map: org.maplibre.android.maps.MapLibreMap, resumeIfAutoPaused: Boolean) {
        val b = map.projection.visibleRegion.latLngBounds
        val centerLat = (b.latitudeSouth + b.latitudeNorth) / 2.0
        val centerLon = RadarViewport.normalizeLon((b.longitudeWest + b.longitudeEast) / 2.0)
        loadJob?.cancel()
        loadJob = scope.launch {
            val online = state.connectivity != ConnectivityPresentation.OFFLINE_WITH_CACHE &&
                state.connectivity != ConnectivityPresentation.OFFLINE_NO_CACHE
            tzResolver.resolve(centerLat, centerLon, allowNetwork = online)?.let {
                viewedZoneId = it.id
            } ?: state.location?.timezone?.let { viewedZoneId = it }

            val radarFp = RadarViewport.footprintForVisibleBounds(
                b.latitudeSouth, b.latitudeNorth, b.longitudeWest, b.longitudeEast,
            )
            val forecastFp = paddedForecastFootprint(
                b.latitudeSouth, b.latitudeNorth, b.longitudeWest, b.longitudeEast,
            )
            val radarCovered = activeRadarFootprint?.coversVisible(
                b.latitudeSouth, b.latitudeNorth, b.longitudeWest, b.longitudeEast,
            ) == true
            val forecastCovered = activeForecastFootprint?.coversVisible(
                b.latitudeSouth, b.latitudeNorth, b.longitudeWest, b.longitudeEast,
            ) == true
            if (!radarCovered) {
                activeRadarFootprint = radarFp
                radarPipeline.loadForViewport(radarFp)
            }
            if (!forecastCovered) {
                activeForecastFootprint = forecastFp
                forecastPipeline.loadForViewport(
                    footprint = forecastFp,
                    visibleLatSouth = b.latitudeSouth,
                    visibleLatNorth = b.latitudeNorth,
                    visibleLonWest = b.longitudeWest,
                    visibleLonEast = b.longitudeEast,
                )
            }
            if (resumeIfAutoPaused && playback == MapPlaybackState.AUTO_PAUSED_FOR_CAMERA) {
                delay(600)
                if (playback == MapPlaybackState.AUTO_PAUSED_FOR_CAMERA &&
                    UnifiedMapTimeline.build(
                        radarPipeline.playbackFrames(),
                        radarPipeline.session.value?.footprint,
                        forecastPipeline.playbackFrames(),
                        forecastPipeline.session.value?.footprint,
                    ).playable
                ) {
                    playback = MapPlaybackState.PLAYING
                }
            }
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

    fun togglePlay() {
        when (playback) {
            MapPlaybackState.PLAYING -> playback = MapPlaybackState.USER_PAUSED
            else -> {
                if (!timeline.playable) return
                playback = MapPlaybackState.PLAYING
            }
        }
    }

    if (showTzDialog) {
        AlertDialog(
            onDismissRequest = { showTzDialog = false },
            title = { Text("Map time zone", fontFamily = FontFamily.Monospace, color = PhosphorGreen) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MapTimezoneMode.entries.forEach { mode ->
                        Text(
                            text = MapTimezone.modeMenuLabel(mode, viewedZoneId, homeTz, customTz),
                            color = if (mode == tzMode) PhosphorGreen else TerminalText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        container.settingsRepository.setMapTimezoneMode(mode)
                                        if (mode == MapTimezoneMode.HOME) {
                                            container.settingsRepository.ensureHomeTimezoneInitialized()
                                        }
                                    }
                                    showTzDialog = false
                                }
                                .padding(vertical = 8.dp),
                        )
                    }
                    if (tzMode == MapTimezoneMode.CUSTOM || tzMode == MapTimezoneMode.HOME) {
                        listOf(
                            "Europe/Amsterdam",
                            "Europe/Istanbul",
                            "UTC",
                            "America/New_York",
                            "Pacific/Honolulu",
                        ).forEach { zid ->
                            Text(
                                zid,
                                color = TerminalMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            if (tzMode == MapTimezoneMode.HOME) {
                                                container.settingsRepository.setHomeTimezoneId(zid)
                                            } else {
                                                container.settingsRepository.setCustomTimezoneId(zid)
                                            }
                                        }
                                        showTzDialog = false
                                    }
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTzDialog = false }) {
                    Text("CLOSE", fontFamily = FontFamily.Monospace, color = PhosphorGreen)
                }
            },
            containerColor = TerminalElevated,
        )
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
                                        timezone = viewedZoneId ?: ZoneId.systemDefault().id,
                                    ),
                                    saveToList = false,
                                )
                                true
                            }
                            map.addOnCameraMoveStartedListener {
                                if (playback == MapPlaybackState.PLAYING) {
                                    playback = MapPlaybackState.AUTO_PAUSED_FOR_CAMERA
                                }
                            }
                            map.addOnCameraIdleListener {
                                idleJob?.cancel()
                                idleJob = scope.launch {
                                    delay(450)
                                    reloadOverlays(
                                        map,
                                        resumeIfAutoPaused = playback == MapPlaybackState.AUTO_PAUSED_FOR_CAMERA,
                                    )
                                }
                            }
                            reloadOverlays(map, resumeIfAutoPaused = false)
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { it.onResume() },
        )

        if (offlineMap && !mapReady) {
            Text(
                "OFFLINE · MAP TILES NOT CACHED",
                color = TerminalText,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(TerminalElevated, RoundedCornerShape(8.dp))
                    .padding(16.dp),
            )
        }

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
                    "PRECIPITATION",
                    color = PhosphorGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .background(TerminalBackground.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                IconButton(onClick = { locateOnMap() }) {
                    Icon(Icons.Outlined.MyLocation, contentDescription = "Locate me", tint = PhosphorGreen)
                }
            }

            Text(
                "TIME · ${MapTimezone.modeChipLabel(tzMode)} · ${MapTimezone.shortLabel(displayZone)}",
                color = PhosphorGreen,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier
                    .background(TerminalBackground.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                    .clickable { showTzDialog = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )

            val frame = displayed
            val preparing = (radarSession?.preparing == true) || (forecastSession?.preparing == true)
            if (ready.isEmpty() && !preparing) {
                Text(
                    "PRECIPITATION DATA UNAVAILABLE",
                    color = TerminalText,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.background(TerminalElevated, RoundedCornerShape(8.dp)).padding(8.dp),
                )
            } else {
                val prev = ready.filter { it.epochSeconds < (frame?.epochSeconds ?: 0) }.maxByOrNull { it.epochSeconds }
                val timeLabel = frame?.let {
                    MapTimezone.formatInstant(
                        it.epochSeconds,
                        displayZone,
                        previousEpochSeconds = prev?.epochSeconds,
                    )
                } ?: "—"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { togglePlay() },
                        enabled = timeline.playable || playback == MapPlaybackState.PLAYING,
                    ) {
                        if (preparing && !timeline.playable) {
                            CircularProgressIndicator(
                                color = PhosphorGreen,
                                modifier = Modifier.padding(8.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                if (playback == MapPlaybackState.PLAYING) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                contentDescription = "Play/pause",
                                tint = if (timeline.playable) PhosphorGreen else TerminalMuted,
                            )
                        }
                    }
                    Column {
                        Text(timeLabel, color = TerminalText, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        Text(
                            frame?.sourceLabel ?: if (preparing) "PREPARING…" else "",
                            color = TerminalMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                }

                if (ready.isNotEmpty()) {
                    val nowPos = timeline.nowMarkerPosition()
                    Slider(
                        value = scrub,
                        onValueChange = { v ->
                            if (playback == MapPlaybackState.PLAYING) {
                                playback = MapPlaybackState.USER_PAUSED
                            }
                            scrub = v
                            val target = timeline.frameAtScrub(v) ?: return@Slider
                            displayed = target
                            mapView?.getMapAsync { map ->
                                map.style?.let { applyOverlayBitmap(it, target, overlayOpacity) }
                            }
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = PhosphorGreen,
                            activeTrackColor = PhosphorGreen,
                        ),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("PAST", color = TerminalMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            "NOW",
                            color = PhosphorGreen,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(start = ((nowPos * 100).toInt().coerceIn(0, 90)).dp),
                        )
                        Text("FORECAST", color = TerminalMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }

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
                    colors = SliderDefaults.colors(thumbColor = PhosphorGreen, activeTrackColor = PhosphorGreen),
                )
                Text(
                    "Dry → Heavy  ·  ${PrecipLegend.legendTickLabels(state.units.precipitation).drop(1).take(4).joinToString(" · ")}",
                    color = TerminalMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "RainViewer · Open-Meteo / DWD",
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

private fun applyOverlayBitmap(style: Style, frame: UnifiedMapFrame, opacity: Float) {
    applyOverlayBitmap(
        style,
        frame.bitmap,
        frame.latNorth,
        frame.latSouth,
        frame.lonWest,
        frame.lonEast,
        opacity,
    )
}

private fun applyOverlayBitmap(
    style: Style,
    bitmap: Bitmap?,
    latNorth: Double,
    latSouth: Double,
    lonWest: Double,
    lonEast: Double,
    opacity: Float,
) {
    if (bitmap == null || bitmap.isRecycled) return
    val quad = LatLngQuad(
        LatLng(latNorth, lonWest),
        LatLng(latNorth, lonEast),
        LatLng(latSouth, lonEast),
        LatLng(latSouth, lonWest),
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
