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
import com.discount152.aeolus.map.ForecastPrecipFootprint
import com.discount152.aeolus.map.ForecastPrecipPipeline
import com.discount152.aeolus.map.MapPlaybackState
import com.discount152.aeolus.map.MapPrecipMode
import com.discount152.aeolus.map.MapTimezone
import com.discount152.aeolus.map.MapTimezoneMode
import com.discount152.aeolus.map.PrecipLegend
import com.discount152.aeolus.map.RadarFootprint
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
import kotlinx.coroutines.flow.first
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
    val radarSession by radarPipeline.session.collectAsStateWithLifecycle()
    val forecastSession by forecastPipeline.session.collectAsStateWithLifecycle()

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var playback by remember { mutableStateOf(MapPlaybackState.STOPPED) }
    var precipMode by remember { mutableStateOf(MapPrecipMode.OBSERVED) }
    var overlayOpacity by remember { mutableFloatStateOf(state.radarOpacity) }
    var lastCenteredLocationId by remember { mutableStateOf<String?>(null) }
    var activeRadarFootprint by remember { mutableStateOf<RadarFootprint?>(null) }
    var activeForecastFootprint by remember { mutableStateOf<ForecastPrecipFootprint?>(null) }
    var playIndex by remember { mutableIntStateOf(0) }
    var displayedRadarTime by remember { mutableStateOf<Long?>(null) }
    var displayedForecastTime by remember { mutableStateOf<Long?>(null) }
    var idleJob by remember { mutableStateOf<Job?>(null) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var viewedZoneId by remember { mutableStateOf<String?>(null) }
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

    val radarReady = remember(radarSession) { radarPipeline.playbackFrames() }
    val forecastReady = remember(forecastSession) { forecastPipeline.playbackFrames() }

    LaunchedEffect(Unit) {
        container.settingsRepository.ensureHomeTimezoneInitialized()
    }
    LaunchedEffect(state.radarOpacity) { overlayOpacity = state.radarOpacity }

    LaunchedEffect(radarSession?.newestReady?.time, mapReady, precipMode) {
        if (precipMode != MapPrecipMode.OBSERVED || !mapReady) return@LaunchedEffect
        val newest = radarSession?.newestReady ?: return@LaunchedEffect
        if (playback == MapPlaybackState.PLAYING) return@LaunchedEffect
        displayedRadarTime = newest.time
        mapView?.getMapAsync { map ->
            map.style?.let { style ->
                radarSession?.footprint?.let { fp ->
                    applyOverlayBitmap(style, newest.bitmap, fp.latNorth, fp.latSouth, fp.lonWest, fp.lonEast, overlayOpacity)
                }
            }
        }
    }

    LaunchedEffect(forecastSession?.readyFrames?.size, mapReady, precipMode) {
        if (precipMode != MapPrecipMode.FORECAST || !mapReady) return@LaunchedEffect
        val frames = forecastPipeline.playbackFrames()
        val first = frames.firstOrNull() ?: return@LaunchedEffect
        if (playback == MapPlaybackState.PLAYING) return@LaunchedEffect
        displayedForecastTime = first.hourEpochUtcMs
        mapView?.getMapAsync { map ->
            map.style?.let { style ->
                forecastSession?.footprint?.let { fp ->
                    applyOverlayBitmap(style, first.bitmap, fp.latNorth, fp.latSouth, fp.lonWest, fp.lonEast, overlayOpacity)
                }
            }
        }
    }

    LaunchedEffect(playback, precipMode, radarReady, forecastReady) {
        if (playback != MapPlaybackState.PLAYING) return@LaunchedEffect
        while (playback == MapPlaybackState.PLAYING) {
            if (precipMode == MapPrecipMode.OBSERVED) {
                val list = radarPipeline.playbackFrames()
                if (list.size < 2) {
                    playback = MapPlaybackState.STOPPED
                    break
                }
                playIndex = (playIndex + 1) % list.size
                val frame = list[playIndex]
                displayedRadarTime = frame.time
                mapView?.getMapAsync { map ->
                    map.style?.let { style ->
                        radarSession?.footprint?.let { fp ->
                            applyOverlayBitmap(style, frame.bitmap, fp.latNorth, fp.latSouth, fp.lonWest, fp.lonEast, overlayOpacity)
                        }
                    }
                }
            } else {
                val list = forecastPipeline.playbackFrames()
                if (list.size < 2) {
                    playback = MapPlaybackState.STOPPED
                    break
                }
                playIndex = (playIndex + 1) % list.size
                val frame = list[playIndex]
                displayedForecastTime = frame.hourEpochUtcMs
                mapView?.getMapAsync { map ->
                    map.style?.let { style ->
                        forecastSession?.footprint?.let { fp ->
                            applyOverlayBitmap(style, frame.bitmap, fp.latNorth, fp.latSouth, fp.lonWest, fp.lonEast, overlayOpacity)
                        }
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
        )
    }

    fun reloadOverlays(map: org.maplibre.android.maps.MapLibreMap, resumeIfAutoPaused: Boolean) {
        val b = map.projection.visibleRegion.latLngBounds
        val centerLat = (b.latitudeSouth + b.latitudeNorth) / 2.0
        val centerLon = RadarViewport.normalizeLon((b.longitudeWest + b.longitudeEast) / 2.0)
        viewedZoneId = MapTimezone.approximateZoneFromLongitude(centerLon).id
        // Prefer location timezone when pin is near center
        state.location?.timezone?.let { viewedZoneId = it }

        loadJob?.cancel()
        loadJob = scope.launch {
            if (precipMode == MapPrecipMode.OBSERVED) {
                val footprint = RadarViewport.footprintForVisibleBounds(
                    latSouth = b.latitudeSouth,
                    latNorth = b.latitudeNorth,
                    lonWest = b.longitudeWest,
                    lonEast = b.longitudeEast,
                )
                val current = activeRadarFootprint
                val covered = current != null && current.coversVisible(
                    b.latitudeSouth, b.latitudeNorth, b.longitudeWest, b.longitudeEast,
                )
                if (!covered) {
                    activeRadarFootprint = footprint
                    radarPipeline.loadForViewport(footprint)
                }
            } else {
                val footprint = paddedForecastFootprint(
                    b.latitudeSouth, b.latitudeNorth, b.longitudeWest, b.longitudeEast,
                )
                val current = activeForecastFootprint
                val covered = current != null && current.coversVisible(
                    b.latitudeSouth, b.latitudeNorth, b.longitudeWest, b.longitudeEast,
                )
                if (!covered) {
                    activeForecastFootprint = footprint
                    forecastPipeline.loadForViewport(
                        footprint = footprint,
                        visibleLatSouth = b.latitudeSouth,
                        visibleLatNorth = b.latitudeNorth,
                        visibleLonWest = b.longitudeWest,
                        visibleLonEast = b.longitudeEast,
                    )
                }
            }

            if (resumeIfAutoPaused && playback == MapPlaybackState.AUTO_PAUSED_FOR_CAMERA) {
                val ready = if (precipMode == MapPrecipMode.OBSERVED) {
                    radarPipeline.session.value?.playable == true
                } else {
                    forecastPipeline.session.value?.playable == true
                }
                if (ready) {
                    playback = MapPlaybackState.PLAYING
                } else {
                    // Wait briefly for frames then resume
                    delay(800)
                    val ready2 = if (precipMode == MapPrecipMode.OBSERVED) {
                        radarPipeline.session.value?.playable == true
                    } else {
                        forecastPipeline.session.value?.playable == true
                    }
                    if (playback == MapPlaybackState.AUTO_PAUSED_FOR_CAMERA && ready2) {
                        playback = MapPlaybackState.PLAYING
                    }
                }
            }
        }
    }

    LaunchedEffect(precipMode, mapReady) {
        if (!mapReady) return@LaunchedEffect
        activeRadarFootprint = null
        activeForecastFootprint = null
        mapView?.getMapAsync { map ->
            reloadOverlays(map, resumeIfAutoPaused = false)
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
        val canPlay = if (precipMode == MapPrecipMode.OBSERVED) {
            radarSession?.playable == true
        } else {
            forecastSession?.playable == true
        }
        when (playback) {
            MapPlaybackState.PLAYING -> playback = MapPlaybackState.USER_PAUSED
            MapPlaybackState.USER_PAUSED, MapPlaybackState.STOPPED, MapPlaybackState.AUTO_PAUSED_FOR_CAMERA -> {
                if (!canPlay) return
                if (precipMode == MapPrecipMode.OBSERVED) {
                    val list = radarPipeline.playbackFrames()
                    playIndex = list.indexOfFirst { it.time == displayedRadarTime }
                        .takeIf { it >= 0 } ?: list.lastIndex.coerceAtLeast(0)
                } else {
                    val list = forecastPipeline.playbackFrames()
                    playIndex = list.indexOfFirst { it.hourEpochUtcMs == displayedForecastTime }
                        .takeIf { it >= 0 } ?: 0
                }
                playback = MapPlaybackState.PLAYING
            }
        }
    }

    if (showTzDialog) {
        AlertDialog(
            onDismissRequest = { showTzDialog = false },
            title = { Text("MAP TIME ZONE", fontFamily = FontFamily.Monospace, color = PhosphorGreen) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MapTimezoneMode.entries.forEach { mode ->
                        Text(
                            text = when (mode) {
                                MapTimezoneMode.VIEWED_LOCATION -> "VIEWED LOCATION"
                                MapTimezoneMode.DEVICE -> "DEVICE TIME"
                                MapTimezoneMode.HOME -> "HOME TIME ($homeTz)"
                                MapTimezoneMode.UTC -> "UTC"
                                MapTimezoneMode.CUSTOM -> "CUSTOM ($customTz)"
                            },
                            color = if (mode == tzMode) PhosphorGreen else TerminalText,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        container.settingsRepository.setMapTimezoneMode(mode)
                                        if (mode == MapTimezoneMode.HOME) {
                                            container.settingsRepository.ensureHomeTimezoneInitialized()
                                        }
                                        if (mode == MapTimezoneMode.CUSTOM) {
                                            // Default custom to common zones if unset
                                            val current = container.settingsRepository.customTimezoneId.first()
                                            if (current.isBlank()) {
                                                container.settingsRepository.setCustomTimezoneId("Europe/Amsterdam")
                                            }
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
                ModeChip(precipMode) { precipMode = it; playback = MapPlaybackState.STOPPED }
                IconButton(onClick = { locateOnMap() }) {
                    Icon(Icons.Outlined.MyLocation, contentDescription = "Locate me", tint = PhosphorGreen)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "TIME · ${MapTimezone.modeLabel(tzMode)} · ${MapTimezone.shortLabel(displayZone)}",
                    color = PhosphorGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .background(TerminalBackground.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                        .clickable { showTzDialog = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            when (precipMode) {
                MapPrecipMode.OBSERVED -> ObservedControls(
                    session = radarSession,
                    readyFrames = radarReady,
                    displayedTime = displayedRadarTime,
                    displayZone = displayZone,
                    playback = playback,
                    overlayOpacity = overlayOpacity,
                    onTogglePlay = { togglePlay() },
                    onScrub = { time, bitmap ->
                        if (playback == MapPlaybackState.PLAYING) {
                            playback = MapPlaybackState.USER_PAUSED
                        }
                        displayedRadarTime = time
                        mapView?.getMapAsync { map ->
                            map.style?.let { style ->
                                radarSession?.footprint?.let { fp ->
                                    applyOverlayBitmap(style, bitmap, fp.latNorth, fp.latSouth, fp.lonWest, fp.lonEast, overlayOpacity)
                                }
                            }
                        }
                    },
                    onOpacity = {
                        overlayOpacity = it
                        vm.updateRadarOpacity(it)
                        mapView?.getMapAsync { map ->
                            map.style?.getLayer(RADAR_IMAGE_LAYER)?.setProperties(
                                PropertyFactory.rasterOpacity(it),
                            )
                        }
                    },
                )
                MapPrecipMode.FORECAST -> ForecastControls(
                    session = forecastSession,
                    readyFrames = forecastReady,
                    displayedTime = displayedForecastTime,
                    displayZone = displayZone,
                    playback = playback,
                    overlayOpacity = overlayOpacity,
                    precipUnit = state.units.precipitation,
                    onTogglePlay = { togglePlay() },
                    onScrub = { time, bitmap ->
                        if (playback == MapPlaybackState.PLAYING) {
                            playback = MapPlaybackState.USER_PAUSED
                        }
                        displayedForecastTime = time
                        mapView?.getMapAsync { map ->
                            map.style?.let { style ->
                                forecastSession?.footprint?.let { fp ->
                                    applyOverlayBitmap(style, bitmap, fp.latNorth, fp.latSouth, fp.lonWest, fp.lonEast, overlayOpacity)
                                }
                            }
                        }
                    },
                    onOpacity = {
                        overlayOpacity = it
                        vm.updateRadarOpacity(it)
                        mapView?.getMapAsync { map ->
                            map.style?.getLayer(RADAR_IMAGE_LAYER)?.setProperties(
                                PropertyFactory.rasterOpacity(it),
                            )
                        }
                    },
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

@Composable
private fun ModeChip(mode: MapPrecipMode, onChange: (MapPrecipMode) -> Unit) {
    Row(
        modifier = Modifier
            .background(TerminalBackground.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(MapPrecipMode.OBSERVED to "OBSERVED", MapPrecipMode.FORECAST to "FORECAST").forEach { (m, label) ->
            Text(
                label,
                color = if (m == mode) PhosphorGreen else TerminalMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier
                    .background(
                        if (m == mode) TerminalElevated else TerminalBackground,
                        RoundedCornerShape(6.dp),
                    )
                    .clickable { onChange(m) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ObservedControls(
    session: com.discount152.aeolus.map.RadarSession?,
    readyFrames: List<com.discount152.aeolus.map.RadarFrameAsset>,
    displayedTime: Long?,
    displayZone: ZoneId,
    playback: MapPlaybackState,
    overlayOpacity: Float,
    onTogglePlay: () -> Unit,
    onScrub: (Long, Bitmap?) -> Unit,
    onOpacity: (Float) -> Unit,
) {
    val sess = session
    when {
        sess == null || (sess.frames.isEmpty() && !sess.preparing) -> {
            Text(
                "OBSERVED RADAR UNAVAILABLE",
                color = TerminalText,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.background(TerminalElevated, RoundedCornerShape(8.dp)).padding(8.dp),
            )
        }
        else -> {
            val displayFrame = readyFrames.find { it.time == displayedTime } ?: sess.newestReady
            val timeLabel = displayFrame?.let {
                MapTimezone.formatInstant(it.time, displayZone)
            } ?: "—"
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onTogglePlay, enabled = sess.playable || playback == MapPlaybackState.PLAYING) {
                    if (sess.preparing && !sess.playable) {
                        CircularProgressIndicator(color = PhosphorGreen, modifier = Modifier.padding(8.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (playback == MapPlaybackState.PLAYING) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = "Play/pause",
                            tint = if (sess.playable) PhosphorGreen else TerminalMuted,
                        )
                    }
                }
                Column {
                    Text("OBSERVED RADAR", color = PhosphorGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text(timeLabel, color = TerminalText, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                if (sess.preparing) {
                    Text("  PREPARING…", color = TerminalMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
            if (readyFrames.isNotEmpty()) {
                val idx = readyFrames.indexOfFirst { it.time == displayedTime }
                    .takeIf { it >= 0 } ?: readyFrames.lastIndex
                Slider(
                    value = idx.toFloat(),
                    onValueChange = { v ->
                        val i = v.toInt().coerceIn(0, readyFrames.lastIndex)
                        val target = readyFrames[i]
                        onScrub(target.time, target.bitmap)
                    },
                    valueRange = 0f..readyFrames.lastIndex.coerceAtLeast(0).toFloat(),
                    colors = SliderDefaults.colors(thumbColor = PhosphorGreen, activeTrackColor = PhosphorGreen),
                )
            }
            Text(
                "Ready ${readyFrames.size}/${sess.frames.count { it.status != RadarFrameStatus.FAILED }} · " +
                    "mosaic ${sess.footprint.tilesX}×${sess.footprint.tilesY} z${sess.footprint.zoom} " +
                    "(≤${RadarViewport.MAX_TILES_PER_FRAME} tiles/frame)",
                color = TerminalMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Slider(
                value = overlayOpacity,
                onValueChange = onOpacity,
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
}

@Composable
private fun ForecastControls(
    session: com.discount152.aeolus.map.ForecastPrecipSession?,
    readyFrames: List<com.discount152.aeolus.map.ForecastPrecipFrame>,
    displayedTime: Long?,
    displayZone: ZoneId,
    playback: MapPlaybackState,
    overlayOpacity: Float,
    precipUnit: com.discount152.aeolus.domain.units.PrecipitationUnit,
    onTogglePlay: () -> Unit,
    onScrub: (Long, Bitmap?) -> Unit,
    onOpacity: (Float) -> Unit,
) {
    val sess = session
    when {
        sess == null || (sess.frames.isEmpty() && !sess.preparing) -> {
            Text(
                "FORECAST PRECIP UNAVAILABLE",
                color = TerminalText,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.background(TerminalElevated, RoundedCornerShape(8.dp)).padding(8.dp),
            )
        }
        else -> {
            val displayFrame = readyFrames.find { it.hourEpochUtcMs == displayedTime } ?: readyFrames.firstOrNull()
            val timeLabel = displayFrame?.let {
                MapTimezone.formatInstant(it.hourEpochUtcMs / 1000L, displayZone)
            } ?: "—"
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onTogglePlay, enabled = sess.playable || playback == MapPlaybackState.PLAYING) {
                    if (sess.preparing && !sess.playable) {
                        CircularProgressIndicator(color = PhosphorGreen, modifier = Modifier.padding(8.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (playback == MapPlaybackState.PLAYING) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = "Play/pause",
                            tint = if (sess.playable) PhosphorGreen else TerminalMuted,
                        )
                    }
                }
                Column {
                    Text("FORECAST PRECIP · MODEL", color = PhosphorGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text(timeLabel, color = TerminalText, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
            if (readyFrames.isNotEmpty()) {
                val idx = readyFrames.indexOfFirst { it.hourEpochUtcMs == displayedTime }
                    .takeIf { it >= 0 } ?: 0
                Slider(
                    value = idx.toFloat(),
                    onValueChange = { v ->
                        val i = v.toInt().coerceIn(0, readyFrames.lastIndex)
                        val target = readyFrames[i]
                        onScrub(target.hourEpochUtcMs, target.bitmap)
                    },
                    valueRange = 0f..readyFrames.lastIndex.coerceAtLeast(0).toFloat(),
                    colors = SliderDefaults.colors(thumbColor = PhosphorGreen, activeTrackColor = PhosphorGreen),
                )
            }
            Text(
                "Next 24h hourly · Open-Meteo best_match · not observed radar",
                color = TerminalMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "Legend: ${PrecipLegend.legendTickLabels(precipUnit).joinToString(" · ")}",
                color = TerminalMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Slider(
                value = overlayOpacity,
                onValueChange = onOpacity,
                colors = SliderDefaults.colors(thumbColor = PhosphorGreen, activeTrackColor = PhosphorGreen),
            )
        }
    }
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
