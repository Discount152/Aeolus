package com.discount152.aeolus.ui.state

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.discount152.aeolus.data.network.NetworkStatus
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.discount152.aeolus.AeolusApp
import com.discount152.aeolus.data.network.MAP_PIN_DEBOUNCE_MS
import com.discount152.aeolus.di.AppContainer
import com.discount152.aeolus.domain.models.GeoLocation
import com.discount152.aeolus.domain.models.WeatherBundle
import com.discount152.aeolus.domain.units.UnitPreferences
import com.discount152.aeolus.location.LocationIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

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
    val connectivity: ConnectivityPresentation = ConnectivityPresentation.OFFLINE_NO_CACHE,
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

    private val _forecastExpandDate = MutableStateFlow<String?>(null)
    val forecastExpandDate: StateFlow<String?> = _forecastExpandDate.asStateFlow()

    private val connectivityManager =
        getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkValidated = MutableStateFlow(NetworkStatus.isValidated(getApplication()))

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _networkValidated.value = NetworkStatus.isValidated(getApplication())
        }

        override fun onLost(network: Network) {
            _networkValidated.value = NetworkStatus.isValidated(getApplication())
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            _networkValidated.value =
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    val state: StateFlow<WeatherUiState> = combine(
        _meta,
        container.weatherRepository.weather,
        _networkValidated,
    ) { meta, bundle, networkValidated ->
        val loc = meta.location
        // Never present weather for a different location under the active pin/search name.
        val matching = bundle?.takeIf { b ->
            loc != null &&
                LocationIdentity.stableId(b.location) == LocationIdentity.stableId(loc)
        }
        val displayLoc = loc ?: matching?.location
        WeatherUiState(
            loading = meta.loading,
            refreshing = meta.refreshing,
            error = meta.error,
            bundle = matching,
            location = displayLoc,
            units = meta.units,
            radarOpacity = meta.radarOpacity,
            savedLocations = meta.savedLocations,
            searchResults = meta.searchResults,
            searchQuery = meta.searchQuery,
            connectivity = computeConnectivityPresentation(
                loading = meta.loading,
                refreshing = meta.refreshing,
                error = meta.error,
                bundle = matching,
                networkValidated = networkValidated,
            ),
            locationIsSaved = displayLoc != null && meta.savedLocations.any {
                LocationIdentity.stableId(it) == LocationIdentity.stableId(displayLoc)
            },
            awaitingLocationPermission = meta.awaitingLocationPermission,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        WeatherUiState(),
    )

    private var refreshJob: Job? = null
    private var weatherLoadJob: Job? = null
    private var pinDebounceJob: Job? = null
    private var permissionResolved = false
    private val locationGeneration = AtomicInteger(0)
    @Volatile
    private var activeLocationGeneration: Int = 0

    init {
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
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
        android.util.Log.i(
            com.discount152.aeolus.data.cache.LastKnownGoodStore.TAG,
            "bootstrap: begin path=${container.lastKnownGoodStore.absolutePath()}",
        )
        val bootFromLkg = startup.bootFromLastKnownGood()
        if (bootFromLkg != null) {
            android.util.Log.i(
                com.discount152.aeolus.data.cache.LastKnownGoodStore.TAG,
                "bootstrap: lkg_hydrated locationId=${bootFromLkg.location.id} " +
                    "bundleDaily=${bootFromLkg.bundle?.consensusDaily?.size} " +
                    "weatherFlow=${container.weatherRepository.weather.value != null}",
            )
            _meta.update {
                it.copy(
                    location = bootFromLkg.location,
                    loading = false,
                    error = null,
                )
            }
        } else {
            android.util.Log.w(
                com.discount152.aeolus.data.cache.LastKnownGoodStore.TAG,
                "bootstrap: lkg_missing_or_decode_failed",
            )
        }

        val permissionGranted = container.locationService.hasPermission()
        val (location, needsPermission) = startup.resolveInitialLocation(
            permissionGranted = permissionGranted,
            allowPermissionPrompt = !permissionResolved,
        )
        android.util.Log.i(
            com.discount152.aeolus.data.cache.LastKnownGoodStore.TAG,
            "bootstrap: resolved locationId=${location.id} needsPermission=$needsPermission " +
                "hadLkg=${bootFromLkg != null}",
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

        // Prefer LKG-embedded location when process restarts offline — avoid replacing with
        // a GPS fix that has no per-location cache and would appear blank after failed refresh.
        val active = if (bootFromLkg != null) bootFromLkg.location else location
        applyActiveLocation(active, persistActive = true, saveToList = false, forceNetwork = true)
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
     * Map pins are debounced (~500 ms) to avoid Open-Meteo stampedes.
     */
    fun selectLocation(
        location: GeoLocation,
        saveToList: Boolean = false,
        forceNetwork: Boolean = true,
    ) {
        val isMapPin = LocationIdentity.normalizeName(location.name) == "map pin" ||
            location.id.startsWith("map:", ignoreCase = true)
        if (isMapPin) {
            pinDebounceJob?.cancel()
            pinDebounceJob = viewModelScope.launch {
                delay(MAP_PIN_DEBOUNCE_MS)
                applyActiveLocation(
                    location = location,
                    persistActive = true,
                    saveToList = saveToList,
                    forceNetwork = forceNetwork,
                )
            }
        } else {
            pinDebounceJob?.cancel()
            viewModelScope.launch {
                applyActiveLocation(
                    location = location,
                    persistActive = true,
                    saveToList = saveToList,
                    forceNetwork = forceNetwork,
                )
            }
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
        val gen = locationGeneration.incrementAndGet()
        activeLocationGeneration = gen
        weatherLoadJob?.cancel()

        val fromDevice = location.id.startsWith("device:", ignoreCase = true) ||
            location.id.startsWith("coord:", ignoreCase = true) ||
            LocationIdentity.normalizeName(location.name) == "current location"
        val canonical = LocationIdentity.withStableId(location)
        _meta.update {
            it.copy(
                location = canonical,
                error = null,
                awaitingLocationPermission = false,
                loading = true,
                refreshing = false,
            )
        }

        if (persistActive) {
            container.settingsRepository.setActiveLocation(canonical)
        }
        if (saveToList) {
            val saved = container.settingsRepository.savedLocations.first()
            val updated = LocationIdentity.upsert(saved, canonical, fromDevice = fromDevice)
            container.settingsRepository.setSavedLocations(updated)
        }

        if (gen != activeLocationGeneration) return

        val cached = container.weatherRepository.hydrateFromCache(canonical)
        if (gen != activeLocationGeneration) return
        if (cached != null) {
            _meta.update { it.copy(loading = false) }
        } else {
            container.weatherRepository.clearIfNotMatching(canonical)
            _meta.update { it.copy(loading = true) }
        }
        weatherLoadJob = viewModelScope.launch {
            loadWeather(force = forceNetwork, locationGen = gen)
        }
        weatherLoadJob?.join()
    }

    fun refresh(silent: Boolean = false) {
        val gen = activeLocationGeneration
        weatherLoadJob?.cancel()
        weatherLoadJob = viewModelScope.launch {
            loadWeather(force = true, silent = silent, locationGen = gen)
        }
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

    /** Navigate to Forecast and expand the daily card for [date] (yyyy-MM-dd). */
    fun requestForecastDayExpand(date: String) {
        _forecastExpandDate.value = date
    }

    fun consumeForecastExpandDate() {
        _forecastExpandDate.value = null
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

    private suspend fun loadWeather(
        force: Boolean,
        silent: Boolean = false,
        locationGen: Int = activeLocationGeneration,
    ) {
        val location = _meta.value.location ?: return
        if (locationGen != activeLocationGeneration) return

        val sameLocBundle = container.weatherRepository.weather.value?.takeIf {
            LocationIdentity.stableId(it.location) == LocationIdentity.stableId(location)
        }
        _meta.update {
            it.copy(
                loading = sameLocBundle == null && !silent,
                refreshing = silent || sameLocBundle != null,
                error = null,
            )
        }

        try {
            val result = container.weatherRepository.getWeather(location, forceRefresh = force)
            if (locationGen != activeLocationGeneration) return
            if (LocationIdentity.stableId(result.location) != LocationIdentity.stableId(location)) {
                return
            }
            _meta.update { it.copy(loading = false, refreshing = false, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (locationGen != activeLocationGeneration) return
            val cached = container.weatherRepository.cachedFor(location)?.takeIf {
                LocationIdentity.stableId(it.location) == LocationIdentity.stableId(location)
            }
            if (cached != null) {
                container.weatherRepository.hydrateFromCache(location, markStale = true)
            } else {
                container.weatherRepository.clearIfNotMatching(location)
            }
            _meta.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    error = if (cached == null) {
                        "WEATHER DATA TEMPORARILY UNAVAILABLE"
                    } else {
                        "Showing cached forecast (refresh failed)."
                    },
                )
            }
        }
    }

    override fun onCleared() {
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        super.onCleared()
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
