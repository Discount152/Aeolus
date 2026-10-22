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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

class WeatherViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {
    private val _meta = MutableStateFlow(
        MetaState(),
    )
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
    )

    val state: StateFlow<WeatherUiState> = combine(
        _meta,
        container.weatherRepository.weather,
    ) { meta, bundle ->
        WeatherUiState(
            loading = meta.loading,
            refreshing = meta.refreshing,
            error = meta.error,
            bundle = bundle,
            location = meta.location ?: bundle?.location,
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
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        WeatherUiState(),
    )

    private var refreshJob: Job? = null

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
                val deduped = LocationIdentity.dedupe(saved)
                if (deduped.size != saved.size) {
                    container.settingsRepository.setSavedLocations(deduped)
                }
                _meta.update { it.copy(savedLocations = deduped) }
            }
        }
        viewModelScope.launch { bootstrap() }
        startAutoRefresh()
    }

    private suspend fun bootstrap() {
        val settings = container.settingsRepository
        var saved = LocationIdentity.dedupe(settings.savedLocations.first())
        if (saved != settings.savedLocations.first()) {
            settings.setSavedLocations(saved)
        }
        val selectedId = settings.selectedLocationId.first()
        val selected = saved.firstOrNull { it.id == selectedId } ?: saved.firstOrNull()
        val location = selected
            ?: container.locationService.currentLocation()?.let { device ->
                LocationIdentity.findDuplicate(saved, device, fromDevice = true) ?: device
            }
            ?: DEFAULT_LOCATION

        _meta.update { it.copy(location = location) }

        // Show disk cache immediately so offline restart works
        val cached = container.weatherRepository.hydrateFromCache(location)
        if (cached != null) {
            _meta.update { it.copy(loading = false, error = null) }
        }

        selectLocation(location, persist = selected == null, forceNetwork = true)
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

    fun selectLocation(location: GeoLocation, persist: Boolean = true, forceNetwork: Boolean = true) {
        viewModelScope.launch {
            val fromDevice = location.id.startsWith("device:", ignoreCase = true) ||
                LocationIdentity.normalizeName(location.name) == "current location"
            val canonical = if (fromDevice) {
                location
            } else {
                location.copy(id = LocationIdentity.canonicalSearchId(location))
            }
            _meta.update { it.copy(location = canonical, error = null) }

            if (persist) {
                val saved = container.settingsRepository.savedLocations.first()
                val updated = LocationIdentity.upsert(saved, canonical, fromDevice = fromDevice)
                container.settingsRepository.setSavedLocations(updated)
                val selectedId = LocationIdentity.findDuplicate(updated, canonical, fromDevice)?.id
                    ?: canonical.id
                container.settingsRepository.setSelectedLocationId(selectedId)
            }

            // Prefer cache for this location before network
            container.weatherRepository.hydrateFromCache(canonical)
            loadWeather(force = forceNetwork)
        }
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
            val saved = container.settingsRepository.savedLocations.first().filterNot { it.id == id }
            container.settingsRepository.setSavedLocations(saved)
        }
    }

    fun useDeviceLocation() {
        viewModelScope.launch {
            val loc = container.locationService.currentLocation()
            if (loc != null) selectLocation(loc)
            else _meta.update { it.copy(error = "Location unavailable. Search or pick on the map.") }
        }
    }

    private suspend fun loadWeather(force: Boolean, silent: Boolean = false) {
        val location = _meta.value.location ?: return
        _meta.update {
            it.copy(
                loading = it.let { m -> container.weatherRepository.weather.value == null && !silent && m.loading },
                refreshing = silent || container.weatherRepository.weather.value != null,
                error = null,
            )
        }
        // If we already showed cache, don't flash blank loading
        if (container.weatherRepository.weather.value != null) {
            _meta.update { it.copy(loading = false, refreshing = true) }
        }

        runCatching {
            container.weatherRepository.getWeather(location, forceRefresh = force)
        }.onSuccess {
            _meta.update { it.copy(loading = false, refreshing = false, error = null) }
        }.onFailure {
            val cached = container.weatherRepository.cachedOrNull(location.id)
                ?: container.weatherRepository.weather.value
            if (cached != null) {
                container.weatherRepository.hydrateFromCache(location, markStale = true)
            }
            _meta.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    error = if (cached == null && container.weatherRepository.weather.value == null) {
                        "Unable to load forecast. Check connection and retry."
                    } else {
                        "Showing cached forecast (refresh failed)."
                    },
                )
            }
        }
    }

    companion object {
        val DEFAULT_LOCATION = GeoLocation(
            id = "default:amsterdam",
            name = "Amsterdam",
            latitude = 52.37,
            longitude = 4.89,
            timezone = "Europe/Amsterdam",
            country = "Netherlands",
            admin1 = "North Holland",
        )

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
