package com.discount152.aeolus.ui.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.discount152.aeolus.AeolusApp
import com.discount152.aeolus.di.AppContainer
import com.discount152.aeolus.domain.models.GeoLocation
import com.discount152.aeolus.domain.models.WeatherBundle
import com.discount152.aeolus.domain.units.UnitPreferences
import com.discount152.aeolus.location.LocationIdentity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class WeatherUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val bundle: WeatherBundle? = null,
    val location: GeoLocation? = null,
    val units: UnitPreferences = UnitPreferences(),
    val radarOpacity: Float = 0.7f,
    val savedLocations: List<GeoLocation> = emptyList(),
    val searchResults: List<GeoLocation> = emptyList(),
    val searchQuery: String = "",
    val connectivity: ConnectivityPresentation = ConnectivityPresentation.ERROR_NO_CACHE,
    val locationIsSaved: Boolean = false,
    val awaitingLocationPermission: Boolean = false,
)

class WeatherViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {
    private val startup = StartupCoordinator(
        settings = container.settingsRepository,
        locationService = container.locationService,
        weatherRepository = container.weatherRepository,
        lastKnownGoodStore = container.lastKnownGoodStore,
    )

    private val _meta = MutableStateFlow(MetaState())
    private data class MetaState(
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val error: String? = null,
        val location: GeoLocation? = null,
        val units: UnitPreferences = UnitPreferences(),
        val radarOpacity: Float = 0.7f,
        val savedLocations: List<GeoLocation> = emptyList(),
        val searchResults: List<GeoLocation> = emptyList(),
        val searchQuery: String = "",
        val awaitingLocationPermission: Boolean = false,
    )

    private val _permissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val permissionRequests: SharedFlow<Unit> = _permissionRequests.asSharedFlow()

    val state: StateFlow<WeatherUiState> = combine(
        _meta,
        container.weatherRepository.weather,
    ) { meta, bundle ->
        val loc = meta.location ?: bundle?.location
        WeatherUiState(
            loading = meta.loading,
            refreshing = meta.refreshing,
            error = meta.error,
            bundle = bundle,
            location = loc,
            units = meta.units,
            radarOpacity = meta.radarOpacity,
            savedLocations = meta.savedLocations,
            searchResults = meta.searchResults,
            searchQuery = meta.searchQuery,
            connectivity = computeConnectivityPresentation(
                loading = meta.loading,
                refreshing = meta.refreshing,
                error = meta.error,
                bundle = bundle,
            ),
            locationIsSaved = loc != null && meta.savedLocations.any {
                LocationIdentity.stableId(it) == LocationIdentity.stableId(loc)
            },
            awaitingLocationPermission = meta.awaitingLocationPermission,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        WeatherUiState(),
    )

    private var refreshJob: Job? = null
    private var permissionResolved = false

    init {
        viewModelScope.launch {
            container.settingsRepository.unitPreferences.collect { units ->
                _meta.update { it.copy(units = units) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.radarOpacity.collect { opacity ->
                _meta.update { it.copy(radarOpacity = opacity) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.savedLocations.collect { saved ->
                val migrated = LocationIdentity.migrateSavedLocations(saved)
                if (migrated != saved) {
                    container.settingsRepository.setSavedLocations(migrated)
                }
                _meta.update { it.copy(savedLocations = migrated) }
            }
        }
        viewModelScope.launch { bootstrap() }
        startAutoRefresh()
    }

    private suspend fun bootstrap() {
        val bootFromLkg = startup.bootFromLastKnownGood()
        if (bootFromLkg != null) {
            _meta.update {
                it.copy(
                    location = bootFromLkg.location,
                    loading = false,
                    error = null,
                )
            }
        }

        val permissionGranted = container.locationService.hasPermission()
        val (location, needsPermission) = startup.resolveInitialLocation(
            permissionGranted = permissionGranted,
            allowPermissionPrompt = !permissionResolved,
        )

        if (needsPermission) {
            if (bootFromLkg != null) {
                _meta.update {
                    it.copy(
                        loading = false,
                        awaitingLocationPermission = true,
                        location = bootFromLkg.location,
                    )
                }
            } else {
                _meta.update {
                    it.copy(
                        loading = true,
                        awaitingLocationPermission = true,
                        // Do not publish Amsterdam to UI yet — wait for permission result
                        location = null,
                    )
                }
            }
            _permissionRequests.tryEmit(Unit)
            return
        }

        applyActiveLocation(location, persistActive = true, saveToList = false, forceNetwork = true)
    }

    /** Called from UI after the system permission dialog resolves. */
    fun onLocationPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            permissionResolved = true
            container.settingsRepository.setLocationPermissionNagDismissed(true)
            _meta.update { it.copy(awaitingLocationPermission = false) }

            if (granted) {
                val device = container.locationService.currentLocation()
                    ?.let { LocationIdentity.withStableId(it) }
                if (device != null) {
                    applyActiveLocation(device, persistActive = true, saveToList = false, forceNetwork = true)
                    return@launch
                }
            }
            applyActiveLocation(
                LocationIdentity.FALLBACK_LOCATION,
                persistActive = true,
                saveToList = false,
                forceNetwork = true,
            )
        }
    }

    fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (isActive) {
                delay(45 * 60_000L)
                refresh(silent = true)
            }
        }
    }

    /**
     * Set the active viewing location. Does NOT add to Saved Locations unless [saveToList].
     */
    fun selectLocation(
        location: GeoLocation,
        saveToList: Boolean = false,
        forceNetwork: Boolean = true,
    ) {
        viewModelScope.launch {
            applyActiveLocation(
                location = location,
                persistActive = true,
                saveToList = saveToList,
                forceNetwork = forceNetwork,
            )
        }
    }

    fun saveCurrentLocation() {
        viewModelScope.launch {
            val loc = _meta.value.location ?: return@launch
            // Explicit save: never use ephemeral fallback/device/map ids — stable deletable record
            val toSave = if (LocationIdentity.isFallback(loc)) {
                loc.copy(
                    id = LocationIdentity.coordKey(loc.latitude, loc.longitude),
                    name = "Amsterdam",
                )
            } else {
                LocationIdentity.withStableId(loc)
            }
            val saved = container.settingsRepository.savedLocations.first()
            val updated = LocationIdentity.upsert(saved, toSave, fromDevice = false)
            container.settingsRepository.setSavedLocations(updated)
        }
    }

    private suspend fun applyActiveLocation(
        location: GeoLocation,
        persistActive: Boolean,
        saveToList: Boolean,
        forceNetwork: Boolean,
    ) {
        val fromDevice = location.id.startsWith("device:", ignoreCase = true) ||
            location.id.startsWith("coord:", ignoreCase = true) ||
            LocationIdentity.normalizeName(location.name) == "current location"
        val canonical = LocationIdentity.withStableId(location)
        _meta.update { it.copy(location = canonical, error = null, awaitingLocationPermission = false) }

        if (persistActive) {
            container.settingsRepository.setActiveLocation(canonical)
        }
        if (saveToList) {
            val saved = container.settingsRepository.savedLocations.first()
            val updated = LocationIdentity.upsert(saved, canonical, fromDevice = fromDevice)
            container.settingsRepository.setSavedLocations(updated)
        }

        val cached = container.weatherRepository.hydrateFromCache(canonical)
        if (cached != null) {
            _meta.update { it.copy(loading = false) }
        }
        loadWeather(force = forceNetwork)
    }

    fun refresh(silent: Boolean = false) {
        viewModelScope.launch { loadWeather(force = true, silent = silent) }
    }

    fun search(query: String) {
        _meta.update { it.copy(searchQuery = query) }
        viewModelScope.launch {
            val results = if (query.length < 2) emptyList()
            else container.weatherRepository.searchLocations(query)
            _meta.update { it.copy(searchResults = results) }
        }
    }

    fun clearSearch() {
        _meta.update { it.copy(searchQuery = "", searchResults = emptyList()) }
    }

    fun updateUnits(units: UnitPreferences) {
        viewModelScope.launch { container.settingsRepository.setUnitPreferences(units) }
    }

    fun updateRadarOpacity(opacity: Float) {
        viewModelScope.launch { container.settingsRepository.setRadarOpacity(opacity) }
    }

    fun removeSavedLocation(id: String) {
        viewModelScope.launch {
            val saved = container.settingsRepository.savedLocations.first()
            val target = saved.firstOrNull {
                it.id == id || LocationIdentity.stableId(it) == id
            }
            val remaining = saved.filterNot {
                it.id == id ||
                    (target != null && LocationIdentity.stableId(it) == LocationIdentity.stableId(target))
            }
            container.settingsRepository.setSavedLocations(remaining)

            val active = _meta.value.location
            if (active != null && target != null &&
                LocationIdentity.stableId(active) == LocationIdentity.stableId(target)
            ) {
                val next = when {
                    container.locationService.hasPermission() ->
                        container.locationService.currentLocation()?.let { LocationIdentity.withStableId(it) }
                    else -> null
                } ?: remaining.firstOrNull()?.let { LocationIdentity.withStableId(it) }
                    ?: LocationIdentity.FALLBACK_LOCATION
                applyActiveLocation(next, persistActive = true, saveToList = false, forceNetwork = true)
            }
        }
    }

    fun useDeviceLocation() {
        viewModelScope.launch {
            if (!container.locationService.hasPermission()) {
                _meta.update { it.copy(awaitingLocationPermission = true) }
                _permissionRequests.tryEmit(Unit)
                return@launch
            }
            val loc = container.locationService.currentLocation()
            if (loc != null) {
                applyActiveLocation(loc, persistActive = true, saveToList = false, forceNetwork = true)
            } else {
                _meta.update { it.copy(error = "Location unavailable. Search or pick on the map.") }
            }
        }
    }

    private suspend fun loadWeather(force: Boolean, silent: Boolean = false) {
        val location = _meta.value.location ?: return
        _meta.update {
            it.copy(
                loading = container.weatherRepository.weather.value == null && !silent && it.loading,
                refreshing = silent || container.weatherRepository.weather.value != null,
                error = null,
            )
        }
        if (container.weatherRepository.weather.value != null) {
            _meta.update { it.copy(loading = false, refreshing = true) }
        }

        val priorBundle = container.weatherRepository.weather.value
        runCatching {
            container.weatherRepository.getWeather(location, forceRefresh = force)
        }.onSuccess {
            _meta.update { it.copy(loading = false, refreshing = false, error = null) }
        }.onFailure {
            val cached = container.weatherRepository.cachedFor(location)
                ?: container.weatherRepository.weather.value
                ?: priorBundle
            if (cached != null) {
                val fromDisk = container.weatherRepository.hydrateFromCache(location, markStale = true)
                if (fromDisk == null) {
                    container.weatherRepository.publishBootSnapshot(cached)
                }
            }
            _meta.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    error = if (cached == null) {
                        "Unable to load forecast. Check connection and retry."
                    } else {
                        "Showing cached forecast (refresh failed)."
                    },
                )
            }
        }
    }

    companion object {
        @Deprecated("Use LocationIdentity.FALLBACK_LOCATION", ReplaceWith("LocationIdentity.FALLBACK_LOCATION"))
        val DEFAULT_LOCATION = LocationIdentity.FALLBACK_LOCATION

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = container.applicationContext as Application
                    return WeatherViewModel(app, container) as T
                }
            }
    }
}

fun Application.aeolusContainer(): AppContainer =
    (this as AeolusApp).appContainer

fun android.content.Context.aeolusContainer(): AppContainer =
    (applicationContext as AeolusApp).appContainer
