package com.discount152.aeolus.ui.state

import com.discount152.aeolus.data.prefs.SettingsRepository
import com.discount152.aeolus.data.repository.WeatherRepository
import com.discount152.aeolus.domain.models.GeoLocation
import com.discount152.aeolus.domain.models.WeatherBundle
import com.discount152.aeolus.location.LocationIdentity
import com.discount152.aeolus.location.DeviceLocationSource
import kotlinx.coroutines.flow.first

/**
 * Deterministic cold-start orchestration shared by production ViewModel and tests.
 *
 * Order: prefs → active location → permission/device/fallback → stable cache key →
 * hydrate → optional live refresh (caller decides network).
 */
class StartupCoordinator(
    private val settings: SettingsRepository,
    private val locationService: DeviceLocationSource,
    private val weatherRepository: WeatherRepository,
) {
    data class StartupResult(
        val location: GeoLocation,
        val bundle: WeatherBundle?,
        val fromCache: Boolean,
        val cacheKey: String,
        val needsPermissionRequest: Boolean,
    )

    /**
     * @param permissionGranted current foreground location permission
     * @param permissionJustResolved if true, do not ask again this session
     * @param allowPermissionPrompt whether we may signal a system permission request
     */
    suspend fun resolveInitialLocation(
        permissionGranted: Boolean,
        permissionJustResolved: Boolean = false,
        allowPermissionPrompt: Boolean = true,
    ): Pair<GeoLocation, Boolean> {
        migrateIfNeeded()

        val persistedActive = settings.awaitActiveLocation()?.let { LocationIdentity.withStableId(it) }
        if (persistedActive != null && !LocationIdentity.isFallback(persistedActive)) {
            return persistedActive to false
        }

        // Prefer previously selected saved location when active JSON missing (pre-1.0.3)
        val selectedId = settings.selectedLocationId.first()
        val saved = LocationIdentity.migrateSavedLocations(settings.savedLocations.first())
        val fromSaved = selectedId?.let { id ->
            saved.firstOrNull { it.id == id || LocationIdentity.stableId(it) == id }
        } ?: saved.firstOrNull()
        if (fromSaved != null && !LocationIdentity.isFallback(fromSaved)) {
            val stable = LocationIdentity.withStableId(fromSaved)
            settings.setActiveLocation(stable)
            return stable to false
        }

        if (permissionGranted) {
            val device = locationService.currentLocation()?.let { LocationIdentity.withStableId(it) }
            if (device != null) {
                settings.setActiveLocation(device)
                return device to false
            }
        } else if (allowPermissionPrompt && !permissionJustResolved &&
            !settings.locationPermissionNagDismissed.first()
        ) {
            // Signal permission request — do NOT publish fallback UI yet
            return LocationIdentity.FALLBACK_LOCATION to true
        }

        // Fallback is ephemeral — do not insert into saved locations
        settings.setActiveLocation(LocationIdentity.FALLBACK_LOCATION)
        return LocationIdentity.FALLBACK_LOCATION to false
    }

    fun hydrateFor(location: GeoLocation): WeatherBundle? {
        val stable = LocationIdentity.withStableId(location)
        return weatherRepository.hydrateFromCache(stable, markStale = false)
    }

    /**
     * Full cold-start without network: migrate → resolve location → hydrate with production cache key.
     */
    suspend fun coldStartOffline(permissionGranted: Boolean = false): StartupResult {
        val (location, needsPermission) = resolveInitialLocation(
            permissionGranted = permissionGranted,
            allowPermissionPrompt = false,
        )
        val key = LocationIdentity.cacheKey(location)
        val bundle = hydrateFor(location)?.copy(fromCache = true, stale = true)
        if (bundle != null) {
            // Ensure repository state matches hydrated snapshot
            weatherRepository.hydrateFromCache(location, markStale = true)
        }
        return StartupResult(
            location = location,
            bundle = weatherRepository.weather.value ?: bundle,
            fromCache = (weatherRepository.weather.value ?: bundle) != null,
            cacheKey = key,
            needsPermissionRequest = needsPermission,
        )
    }

    suspend fun migrateIfNeeded() {
        val version = settings.locationMigrationVersion.first()
        val saved = settings.savedLocations.first()
        val migrated = LocationIdentity.migrateSavedLocations(saved)
        if (migrated != saved) {
            settings.setSavedLocations(migrated)
        }
        if (version < SettingsRepository.CURRENT_LOCATION_MIGRATION_VERSION) {
            if (settings.awaitActiveLocation() == null) {
                val selectedId = settings.selectedLocationId.first()
                val active = selectedId?.let { id ->
                    migrated.firstOrNull { it.id == id || LocationIdentity.stableId(it) == id }
                } ?: migrated.firstOrNull()
                if (active != null) {
                    settings.setActiveLocation(LocationIdentity.withStableId(active))
                }
            } else {
                settings.awaitActiveLocation()?.let {
                    settings.setActiveLocation(LocationIdentity.withStableId(it))
                }
            }
            settings.setLocationMigrationVersion(SettingsRepository.CURRENT_LOCATION_MIGRATION_VERSION)
        }
    }
}
