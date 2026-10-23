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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.runtime.mutableLongStateOf
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
import com.discount152.aeolus.map.MapCoveredSkip
import com.discount152.aeolus.map.MapFrameSource
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
import com.discount152.aeolus.ui.state.WeatherCopy
import com.discount152.aeolus.ui.state.aeolusContainer
import com.discount152.aeolus.ui.state.rememberWeatherViewModel
import com.discount152.aeolus.ui.theme.PhosphorGreen
import com.discount152.aeolus.ui.theme.TerminalBackground
import com.discount152.aeolus.ui.theme.TerminalElevated
import com.discount152.aeolus.ui.theme.TerminalMuted
import com.discount152.aeolus.ui.theme.TerminalText
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import java.util.concurrent.atomic.AtomicLong

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
private const val MARKER_SOURCE = "aeolus-marker"
private const val MARKER_LAYER = "aeolus-marker-layer"
private const val RADAR_IMAGE_SOURCE = "aeolus-radar-image"
private const val RADAR_IMAGE_LAYER = "aeolus-radar-image-layer"
private const val MAP_LOAD_TAG = "MapLoad"
/** Readable weather-animation step (not real-time frame duration). */
private const val PLAYBACK_FRAME_MS = 650L
private const val PLAYBACK_NOW_PAUSE_MS = 1000L
private const val PLAYBACK_LOOP_PAUSE_MS = 750L

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
    var selectedInstant by remember { mutableStateOf<Long?>(null) }
    var publishedTimeline by remember { mutableStateOf<UnifiedMapTimeline?>(null) }
    var publishedGen by remember { mutableLongStateOf(-1L) }
    var idleJob by remember { mutableStateOf<Job?>(null) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var viewedZoneId by remember { mutableStateOf<String?>(state.location?.timezone) }
    var showTzDialog by remember { mutableStateOf(false) }
    var showControlsDialog by remember { mutableStateOf(false) }
    var overlayLoadStartedAt by remember { mutableLongStateOf(0L) }
    var firstVisibleLogged by remember { mutableStateOf(false) }
    val mapGeneration = remember { AtomicLong(0L) }

    fun noteFirstVisible(reason: String) {
        if (firstVisibleLogged) return
        firstVisibleLogged = true
        val ms = if (overlayLoadStartedAt > 0L) System.currentTimeMillis() - overlayLoadStartedAt else -1L
        Log.i(MAP_LOAD_TAG, "first_visible_frame reason=$reason ms=$ms")
    }

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

    val activeGen = mapGeneration.get()
    val radarPrep = radarSession?.preparing == true && (radarSession?.generation ?: 0L) == activeGen
    val forecastPrep = forecastSession?.preparing == true && (forecastSession?.generation ?: 0L) == activeGen
    val preparing = radarPrep || forecastPrep

    val timeline = publishedTimeline
    val ready = timeline?.readyFrames.orEmpty()
    val displayed: UnifiedMapFrame? = selectedInstant?.let { timeline?.nearestFrame(it) }
        ?: ready.lastOrNull { it.epochSeconds <= (timeline?.nowEpochSeconds ?: Long.MAX_VALUE) }
        ?: ready.lastOrNull()
    val scrub = timeline?.let { t ->
        selectedInstant?.let { t.scrubPositionFor(it) } ?: t.nowMarkerPosition()
    } ?: 0f

    val offlineMap = state.connectivity == ConnectivityPresentation.OFFLINE_WITH_CACHE ||
        state.connectivity == ConnectivityPresentation.OFFLINE_NO_CACHE

    LaunchedEffect(Unit) { container.settingsRepository.ensureHomeTimezoneInitialized() }
    LaunchedEffect(state.radarOpacity) { overlayOpacity = state.radarOpacity }

    // Publish when radar is ready (degraded OK); upgrade when forecast finishes. Never require both.
    LaunchedEffect(radarSession, forecastSession, mapReady) {
        if (!mapReady) return@LaunchedEffect
        val gen = mapGeneration.get()
        val r = radarPipeline.session.value?.takeIf { it.generation == gen }
        val f = forecastPipeline.session.value?.takeIf { it.generation == gen }
        val rDone = r == null || !r.preparing
        val fDone = f == null || !f.preparing
        val observed = if (rDone) radarPipeline.playbackFrames() else emptyList()
        val forecast = if (fDone) forecastPipeline.playbackFrames() else emptyList()
        val built = UnifiedMapTimeline.build(
            observed = observed,
            observedFootprint = r?.footprint,
            forecast = forecast,
            forecastFootprint = f?.footprint,
        )
        // Radar-first early publish; never settle an empty/unplayable unified timeline.
        val canPublish = MapCoveredSkip.canPublishSettle(
            radarDone = rDone,
            forecastDone = fDone,
            observedReadyCount = observed.size,
            forecastReadyCount = forecast.size,
            unifiedPlayable = built.playable,
            unifiedReadyCount = built.readyFrames.size,
        )
        if (!canPublish) return@LaunchedEffect
        val prev = publishedTimeline
        if (gen == publishedGen && prev != null &&
            prev.readyFrames.size == built.readyFrames.size &&
            prev.readyFrames.map { it.epochSeconds } == built.readyFrames.map { it.epochSeconds }
        ) {
            return@LaunchedEffect
        }
        val prevSelected = selectedInstant
        publishedTimeline = built
        publishedGen = gen
        Log.i(
            MAP_LOAD_TAG,
            "timeline_publish gen=$gen frames=${built.readyFrames.size} " +
                "radarDone=$rDone forecastDone=$fDone playable=${built.playable}",
        )
        val nextSelected = when {
            prevSelected != null && built.readyFrames.any { it.epochSeconds == prevSelected } -> prevSelected
            prevSelected != null -> built.nearestFrame(prevSelected)?.epochSeconds
            else -> built.readyFrames.lastOrNull { it.epochSeconds <= built.nowEpochSeconds }?.epochSeconds
                ?: built.readyFrames.lastOrNull()?.epochSeconds
        }
        selectedInstant = nextSelected
        nextSelected?.let { epoch ->
            built.nearestFrame(epoch)?.let { frame ->
                mapView?.getMapAsync { map ->
                    map.style?.let {
                        applyOverlayBitmap(it, frame, overlayOpacity)
                        noteFirstVisible("timeline_publish")
                    }
                }
            }
        }
    }

    // Early radar bitmap preview before first timeline publish.
    LaunchedEffect(radarSession?.frames, mapReady, publishedTimeline) {
        if (!mapReady || publishedTimeline != null) return@LaunchedEffect
        val newest = radarPipeline.playbackFrames().maxByOrNull { it.time } ?: return@LaunchedEffect
        val fp = radarSession?.footprint ?: return@LaunchedEffect
        if (newest.bitmap == null) return@LaunchedEffect
        mapView?.getMapAsync { map ->
            map.style?.let {
                applyOverlayBitmap(
                    it, newest.bitmap, fp.latNorth, fp.latSouth, fp.lonWest, fp.lonEast, overlayOpacity,
                )
                noteFirstVisible("radar_preview")
            }
        }
    }

    // ONE playback clock — must NOT key on selectedInstant (that relaunched every frame → 8+ fps).
    LaunchedEffect(playback) {
        if (playback != MapPlaybackState.PLAYING) return@LaunchedEffect
        while (playback == MapPlaybackState.PLAYING) {
            val timelineNow = publishedTimeline
            val list = timelineNow?.readyFrames.orEmpty()
            if (timelineNow == null || list.size < 2) {
                // Reload briefly clears the timeline — do not STOP or we lose AUTO resume.
                delay(PLAYBACK_FRAME_MS)
                continue
            }
            val cur = selectedInstant
            val idx = list.indexOfFirst { it.epochSeconds == cur }.takeIf { it >= 0 } ?: 0
            val nextIdx = (idx + 1) % list.size
            val next = list[nextIdx]
            val nowBoundary = timelineNow.nowEpochSeconds
            val crossingNow = list[idx].epochSeconds <= nowBoundary && next.epochSeconds > nowBoundary
            val looping = nextIdx == 0
            selectedInstant = next.epochSeconds
            mapView?.getMapAsync { map ->
                map.style?.let { applyOverlayBitmap(it, next, overlayOpacity) }
            }
            delay(
                when {
                    looping -> PLAYBACK_LOOP_PAUSE_MS
                    crossingNow -> PLAYBACK_NOW_PAUSE_MS
                    else -> PLAYBACK_FRAME_MS
                },
            )
        }
    }

    // Auto-resume after camera settle once the active generation is playable.
    LaunchedEffect(playback, publishedTimeline?.playable) {
        if (playback != MapPlaybackState.AUTO_PAUSED_FOR_CAMERA) return@LaunchedEffect
        if (publishedTimeline?.playable != true) return@LaunchedEffect
        delay(350)
        if (playback == MapPlaybackState.AUTO_PAUSED_FOR_CAMERA &&
            publishedTimeline?.playable == true
        ) {
            playback = MapPlaybackState.PLAYING
            Log.i(MAP_LOAD_TAG, "auto_resume_effect frames=${publishedTimeline?.readyFrames?.size}")
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
            latSouth = (latSouth - latPad).coerceIn(-85.05, 85.05),
            latNorth = (latNorth + latPad).coerceIn(-85.05, 85.05),
            lonWest = RadarViewport.normalizeLon(lonWest - lonPad),
            lonEast = RadarViewport.normalizeLon(lonEast + lonPad),
            rasterSizePx = ForecastPrecipPipeline.DEFAULT_RASTER_SIZE_PX,
        )
    }

    fun reloadOverlays(map: org.maplibre.android.maps.MapLibreMap, resumeIfAutoPaused: Boolean) {
        val b = map.projection.visibleRegion.latLngBounds
        val centerLat = (b.latitudeSouth + b.latitudeNorth) / 2.0
        val centerLon = RadarViewport.normalizeLon((b.longitudeWest + b.longitudeEast) / 2.0)
        val gen = mapGeneration.incrementAndGet()
        val t0 = System.currentTimeMillis()
        loadJob?.cancel()
        loadJob = scope.launch {
            Log.i(MAP_LOAD_TAG, "reload_begin gen=$gen resumeAuto=$resumeIfAutoPaused")
            if (overlayLoadStartedAt == 0L) overlayLoadStartedAt = t0
            if (!RadarViewport.visibleBoundsUsable(
                    b.latitudeSouth, b.latitudeNorth, b.longitudeWest, b.longitudeEast,
                )
            ) {
                Log.i(
                    MAP_LOAD_TAG,
                    "reload_skip_invalid_bounds gen=$gen " +
                        "lat=${b.latitudeSouth}..${b.latitudeNorth}",
                )
                return@launch
            }
            val online = state.connectivity != ConnectivityPresentation.OFFLINE_WITH_CACHE &&
                state.connectivity != ConnectivityPresentation.OFFLINE_NO_CACHE
            // Never block overlay load on timezone (429 retries were ~9s of the 15s path).
            if (viewedZoneId == null) {
                launch {
                    runCatching {
                        tzResolver.resolve(centerLat, centerLon, allowNetwork = online)?.let {
                            if (gen == mapGeneration.get()) viewedZoneId = it.id
                        }
                    }
                }
            }

            if (gen != mapGeneration.get()) return@launch

            val radarFp = RadarViewport.footprintForVisibleBounds(
                b.latitudeSouth, b.latitudeNorth, b.longitudeWest, b.longitudeEast,
            )
            val forecastFp = paddedForecastFootprint(
                b.latitudeSouth, b.latitudeNorth, b.longitudeWest, b.longitudeEast,
            )
            val radarFramesReady = radarPipeline.playbackFrames().isNotEmpty()
            val forecastFramesReady = forecastPipeline.playbackFrames().isNotEmpty()
            val radarGeoCovered = activeRadarFootprint?.coversVisibleAtZoom(
                b.latitudeSouth, b.latitudeNorth, b.longitudeWest, b.longitudeEast, radarFp.zoom,
            ) == true
            val forecastGeoCovered = activeForecastFootprint?.coversVisibleAdequately(
                b.latitudeSouth, b.latitudeNorth, b.longitudeWest, b.longitudeEast,
            ) == true
            val built = UnifiedMapTimeline.build(
                observed = radarPipeline.playbackFrames(),
                observedFootprint = radarPipeline.session.value?.footprint,
                forecast = forecastPipeline.playbackFrames(),
                forecastFootprint = forecastPipeline.session.value?.footprint,
            )
            val decision = MapCoveredSkip.reloadDecision(
                radarFramesReady = radarFramesReady,
                forecastFramesReady = forecastFramesReady,
                radarGeoCovered = radarGeoCovered,
                forecastGeoCovered = forecastGeoCovered,
                unifiedPlayable = built.playable,
                unifiedReadyCount = built.readyFrames.size,
            )
            if (decision.skipNetwork) {
                if (gen == mapGeneration.get()) {
                    publishedTimeline = built
                    publishedGen = gen
                    if (selectedInstant == null) {
                        selectedInstant = built.readyFrames
                            .lastOrNull { it.epochSeconds <= built.nowEpochSeconds }
                            ?.epochSeconds
                            ?: built.readyFrames.lastOrNull()?.epochSeconds
                    }
                    Log.i(
                        MAP_LOAD_TAG,
                        "reload_covered gen=$gen frames=${built.readyFrames.size} " +
                            "playable=${built.playable} ms=${System.currentTimeMillis() - t0}",
                    )
                }
            } else {
                publishedTimeline = null
                publishedGen = -1L
                val needRadar = decision.needRadar
                val needForecast = decision.needForecast
                // Parallel pipelines — sequential await was a major ~15s contributor.
                coroutineScope {
                    val radarDeferred = async {
                        if (needRadar) {
                            activeRadarFootprint = radarFp
                            val tr = System.currentTimeMillis()
                            radarPipeline.loadForViewport(radarFp, generation = gen)
                            Log.i(MAP_LOAD_TAG, "radar_done gen=$gen ms=${System.currentTimeMillis() - tr}")
                        }
                    }
                    val forecastDeferred = async {
                        val startForecast = needForecast && MapCoveredSkip.shouldStartMapForecast(
                            coreGetWeatherInFlight = container.weatherRepository.isGetWeatherInFlight(),
                            gateCoolingDown = container.openMeteoGate.isCoolingDown(),
                        )
                        if (needForecast && !startForecast) {
                            Log.i(MAP_LOAD_TAG, "forecast_yield gen=$gen core_or_cooldown")
                            launch {
                                repeat(24) {
                                    delay(250)
                                    if (gen != mapGeneration.get()) return@launch
                                    if (!MapCoveredSkip.shouldStartMapForecast(
                                            coreGetWeatherInFlight = container.weatherRepository.isGetWeatherInFlight(),
                                            gateCoolingDown = container.openMeteoGate.isCoolingDown(),
                                        )
                                    ) {
                                        return@repeat
                                    }
                                    activeForecastFootprint = forecastFp
                                    val tf = System.currentTimeMillis()
                                    forecastPipeline.loadForViewport(
                                        footprint = forecastFp,
                                        visibleLatSouth = b.latitudeSouth,
                                        visibleLatNorth = b.latitudeNorth,
                                        visibleLonWest = b.longitudeWest,
                                        visibleLonEast = b.longitudeEast,
                                        generation = gen,
                                    )
                                    Log.i(
                                        MAP_LOAD_TAG,
                                        "forecast_done gen=$gen batches=${forecastPipeline.networkBatchCount} " +
                                            "retry=true ms=${System.currentTimeMillis() - tf}",
                                    )
                                    return@launch
                                }
                            }
                        }
                        if (startForecast) {
                            activeForecastFootprint = forecastFp
                            val tf = System.currentTimeMillis()
                            forecastPipeline.loadForViewport(
                                footprint = forecastFp,
                                visibleLatSouth = b.latitudeSouth,
                                visibleLatNorth = b.latitudeNorth,
                                visibleLonWest = b.longitudeWest,
                                visibleLonEast = b.longitudeEast,
                                generation = gen,
                            )
                            Log.i(
                                MAP_LOAD_TAG,
                                "forecast_done gen=$gen batches=${forecastPipeline.networkBatchCount} " +
                                    "ms=${System.currentTimeMillis() - tf}",
                            )
                        }
                    }
                    radarDeferred.await()
                    forecastDeferred.await()
                }
                Log.i(MAP_LOAD_TAG, "reload_pipelines gen=$gen ms=${System.currentTimeMillis() - t0}")
            }
            val endBuilt = UnifiedMapTimeline.build(
                observed = radarPipeline.playbackFrames(),
                observedFootprint = radarPipeline.session.value?.footprint,
                forecast = forecastPipeline.playbackFrames(),
                forecastFootprint = forecastPipeline.session.value?.footprint,
            )
            if (resumeIfAutoPaused &&
                playback == MapPlaybackState.AUTO_PAUSED_FOR_CAMERA &&
                endBuilt.playable &&
                gen == mapGeneration.get()
            ) {
                if (publishedTimeline == null || publishedGen != gen) {
                    publishedTimeline = endBuilt
                    publishedGen = gen
                }
                playback = MapPlaybackState.PLAYING
                Log.i(MAP_LOAD_TAG, "auto_resume gen=$gen frames=${endBuilt.readyFrames.size}")
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
                if (publishedTimeline?.playable != true) return
                playback = MapPlaybackState.PLAYING
            }
        }
    }

    if (showTzDialog) {
        AlertDialog(
            onDismissRequest = { showTzDialog = false },
            title = { Text("Map time zone", fontFamily = FontFamily.Monospace, color = PhosphorGreen) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                                .padding(vertical = 6.dp),
                        )
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

    if (showControlsDialog) {
        AlertDialog(
            onDismissRequest = { showControlsDialog = false },
            title = { Text("Map controls", fontFamily = FontFamily.Monospace, color = PhosphorGreen) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Weather layer opacity", color = TerminalText, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Slider(
                        value = overlayOpacity,
                        onValueChange = {
                            overlayOpacity = it
                            vm.updateRadarOpacity(it)
                            displayed?.let { frame ->
                                mapView?.getMapAsync { map ->
                                    map.style?.let { style -> applyOverlayBitmap(style, frame, it) }
                                }
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
                }
            },
            confirmButton = {
                TextButton(onClick = { showControlsDialog = false }) {
                    Text("DONE", fontFamily = FontFamily.Monospace, color = PhosphorGreen)
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
                                // Stable coord identity; timezone=auto in repository — do not borrow viewport TZ.
                                vm.selectLocation(
                                    GeoLocation(
                                        id = "map:${"%.5f".format(latLng.latitude)},${"%.5f".format(latLng.longitude)}",
                                        name = "Map pin",
                                        latitude = latLng.latitude,
                                        longitude = latLng.longitude,
                                        timezone = "auto",
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

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "PRECIPITATION",
                color = PhosphorGreen,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                modifier = Modifier
                    .background(TerminalBackground.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Row {
                Text(
                    MapTimezone.shortLabel(displayZone),
                    color = PhosphorGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .background(TerminalBackground.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                        .clickable { showTzDialog = true }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                IconButton(onClick = { showControlsDialog = true }) {
                    Icon(Icons.Outlined.Tune, contentDescription = "Map controls", tint = PhosphorGreen)
                }
                IconButton(onClick = { locateOnMap() }) {
                    Icon(Icons.Outlined.MyLocation, contentDescription = "Locate me", tint = PhosphorGreen)
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .background(TerminalBackground.copy(alpha = 0.88f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            val playable = timeline?.playable == true
            val frame = displayed
            val timeLabel = frame?.let {
                val prev = ready.filter { f -> f.epochSeconds < it.epochSeconds }.maxByOrNull { f -> f.epochSeconds }
                MapTimezone.formatInstant(it.epochSeconds, displayZone, previousEpochSeconds = prev?.epochSeconds)
            } ?: if (preparing) "PREPARING…" else "—"
            val sourceShort = when (frame?.source) {
                MapFrameSource.OBSERVED_RADAR -> "OBSERVED"
                MapFrameSource.FORECAST_PRECIP -> "FORECAST"
                null -> if (preparing) "" else "UNAVAILABLE"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { togglePlay() },
                    enabled = playable || playback == MapPlaybackState.PLAYING,
                ) {
                    if (preparing && !playable) {
                        CircularProgressIndicator(
                            color = PhosphorGreen,
                            modifier = Modifier.padding(8.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            if (playback == MapPlaybackState.PLAYING) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = "Play/pause",
                            tint = if (playable) PhosphorGreen else TerminalMuted,
                        )
                    }
                }
                Column {
                    Text(timeLabel, color = TerminalText, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text(sourceShort, color = TerminalMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
            if (ready.isNotEmpty()) {
                Slider(
                    value = scrub,
                    onValueChange = { v ->
                        if (playback == MapPlaybackState.PLAYING) {
                            playback = MapPlaybackState.USER_PAUSED
                        }
                        val target = timeline?.frameAtScrub(v) ?: return@Slider
                        selectedInstant = target.epochSeconds
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
                    Text("OBSERVED", color = TerminalMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Text("FORECAST", color = TerminalMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
                val hasObserved = ready.any { it.source == MapFrameSource.OBSERVED_RADAR }
                val hasForecast = ready.any { it.source == MapFrameSource.FORECAST_PRECIP }
                if (hasObserved && !hasForecast && !forecastPrep) {
                    Text(
                        WeatherCopy.FORECAST_TEMPORARILY_UNAVAILABLE,
                        color = TerminalMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
            } else if (!preparing) {
                Text(
                    WeatherCopy.PRECIPITATION_UNAVAILABLE,
                    color = TerminalMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
            Text(
                "Map © OpenStreetMap/OpenFreeMap · Weather RainViewer/Open-Meteo",
                color = TerminalMuted,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
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
