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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
)

class WeatherViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(WeatherUiState())
    val state: StateFlow<WeatherUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            container.settingsRepository.unitPreferences.collect { units ->
                _state.update { it.copy(units = units) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.radarOpacity.collect { opacity ->
                _state.update { it.copy(radarOpacity = opacity) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.savedLocations.collect { saved ->
                _state.update { it.copy(savedLocations = saved) }
            }
        }
        viewModelScope.launch { bootstrap() }
        startAutoRefresh()
    }

    private suspend fun bootstrap() {
        val settings = container.settingsRepository
        val saved = settings.savedLocations.first()
        val selectedId = settings.selectedLocationId.first()
        val selected = saved.firstOrNull { it.id == selectedId } ?: saved.firstOrNull()
        val location = selected
            ?: container.locationService.currentLocation()
            ?: DEFAULT_LOCATION
        selectLocation(location, persist = selected == null)
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

    fun selectLocation(location: GeoLocation, persist: Boolean = true) {
        viewModelScope.launch {
            _state.update { it.copy(location = location, error = null) }
            if (persist) {
                container.settingsRepository.setSelectedLocationId(location.id)
                val saved = container.settingsRepository.savedLocations.first()
                if (saved.none { it.id == location.id }) {
                    container.settingsRepository.setSavedLocations(saved + location)
                }
            }
            loadWeather(force = true)
        }
    }

    fun refresh(silent: Boolean = false) {
        viewModelScope.launch { loadWeather(force = true, silent = silent) }
    }

    fun search(query: String) {
        _state.update { it.copy(searchQuery = query) }
        viewModelScope.launch {
            val results = if (query.length < 2) emptyList()
            else container.weatherRepository.searchLocations(query)
            _state.update { it.copy(searchResults = results) }
        }
    }

    fun clearSearch() {
        _state.update { it.copy(searchQuery = "", searchResults = emptyList()) }
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
            else _state.update { it.copy(error = "Location unavailable. Search or pick on the map.") }
        }
    }

    private suspend fun loadWeather(force: Boolean, silent: Boolean = false) {
        val location = _state.value.location ?: return
        _state.update {
            it.copy(
                loading = it.bundle == null && !silent,
                refreshing = silent || it.bundle != null,
                error = null,
            )
        }
        runCatching {
            container.weatherRepository.getWeather(location, forceRefresh = force)
        }.onSuccess { bundle ->
            _state.update {
                it.copy(loading = false, refreshing = false, bundle = bundle, error = null)
            }
        }.onFailure { e ->
            val cached = container.weatherRepository.cachedOrNull(location.id)
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    bundle = cached?.copy(fromCache = true, stale = true) ?: it.bundle,
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
