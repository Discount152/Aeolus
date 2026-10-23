package com.discount152.aeolus.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.discount152.aeolus.domain.models.GeoLocation
import com.discount152.aeolus.domain.units.UnitPreferences
import com.discount152.aeolus.map.MapTimezoneMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.ZoneId

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {
    val unitPreferences: Flow<UnitPreferences> =
        dataStore.data.map { prefs ->
            prefs[Keys.UNIT_PREFERENCES_JSON]?.let { raw ->
                runCatching { json.decodeFromString<UnitPreferences>(raw) }.getOrNull()
            } ?: UnitPreferences()
        }

    val radarOpacity: Flow<Float> =
        dataStore.data.map { it[Keys.RADAR_OPACITY] ?: DEFAULT_RADAR_OPACITY }

    val savedLocations: Flow<List<GeoLocation>> =
        dataStore.data.map { prefs ->
            prefs[Keys.SAVED_LOCATIONS_JSON]?.let { raw ->
                runCatching { json.decodeFromString<List<GeoLocation>>(raw) }.getOrDefault(emptyList())
            } ?: emptyList()
        }

    val selectedLocationId: Flow<String?> =
        dataStore.data.map { it[Keys.SELECTED_LOCATION_ID] }

    /** Full active location — survives process death independently of the saved list. */
    val activeLocation: Flow<GeoLocation?> =
        dataStore.data.map { prefs ->
            prefs[Keys.ACTIVE_LOCATION_JSON]?.let { raw ->
                runCatching { json.decodeFromString<GeoLocation>(raw) }.getOrNull()
            }
        }

    val locationPermissionNagDismissed: Flow<Boolean> =
        dataStore.data.map { it[Keys.LOCATION_NAG_DISMISSED] ?: false }

    val locationMigrationVersion: Flow<Int> =
        dataStore.data.map { it[Keys.LOCATION_MIGRATION_VERSION] ?: 0 }

    val mapTimezoneMode: Flow<MapTimezoneMode> =
        dataStore.data.map { prefs ->
            prefs[Keys.MAP_TIMEZONE_MODE]?.let { raw ->
                runCatching { MapTimezoneMode.valueOf(raw) }.getOrNull()
            } ?: MapTimezoneMode.VIEWED_LOCATION
        }

    val homeTimezoneId: Flow<String> =
        dataStore.data.map {
            it[Keys.HOME_TIMEZONE_ID] ?: ZoneId.systemDefault().id
        }

    val customTimezoneId: Flow<String> =
        dataStore.data.map {
            it[Keys.CUSTOM_TIMEZONE_ID] ?: ZoneId.systemDefault().id
        }

    suspend fun setUnitPreferences(units: UnitPreferences) {
        dataStore.edit { it[Keys.UNIT_PREFERENCES_JSON] = json.encodeToString(units) }
    }

    suspend fun setRadarOpacity(opacity: Float) {
        dataStore.edit { it[Keys.RADAR_OPACITY] = opacity.coerceIn(0f, 1f) }
    }

    suspend fun setSavedLocations(locations: List<GeoLocation>) {
        dataStore.edit { it[Keys.SAVED_LOCATIONS_JSON] = json.encodeToString(locations) }
    }

    suspend fun setSelectedLocationId(id: String?) {
        dataStore.edit {
            if (id == null) it.remove(Keys.SELECTED_LOCATION_ID)
            else it[Keys.SELECTED_LOCATION_ID] = id
        }
    }

    suspend fun setActiveLocation(location: GeoLocation?) {
        dataStore.edit {
            if (location == null) {
                it.remove(Keys.ACTIVE_LOCATION_JSON)
                it.remove(Keys.SELECTED_LOCATION_ID)
            } else {
                it[Keys.ACTIVE_LOCATION_JSON] = json.encodeToString(location)
                it[Keys.SELECTED_LOCATION_ID] = location.id
            }
        }
    }

    suspend fun setLocationPermissionNagDismissed(dismissed: Boolean) {
        dataStore.edit { it[Keys.LOCATION_NAG_DISMISSED] = dismissed }
    }

    suspend fun setLocationMigrationVersion(version: Int) {
        dataStore.edit { it[Keys.LOCATION_MIGRATION_VERSION] = version }
    }

    suspend fun setMapTimezoneMode(mode: MapTimezoneMode) {
        dataStore.edit { it[Keys.MAP_TIMEZONE_MODE] = mode.name }
    }

    suspend fun setHomeTimezoneId(zoneId: String) {
        dataStore.edit { it[Keys.HOME_TIMEZONE_ID] = zoneId }
    }

    suspend fun ensureHomeTimezoneInitialized() {
        dataStore.edit { prefs ->
            if (prefs[Keys.HOME_TIMEZONE_ID] == null) {
                prefs[Keys.HOME_TIMEZONE_ID] = ZoneId.systemDefault().id
            }
        }
    }

    suspend fun setCustomTimezoneId(zoneId: String) {
        dataStore.edit { it[Keys.CUSTOM_TIMEZONE_ID] = zoneId }
    }

    suspend fun awaitActiveLocation(): GeoLocation? = activeLocation.first()

    private object Keys {
        val UNIT_PREFERENCES_JSON = stringPreferencesKey("unit_preferences_json")
        val RADAR_OPACITY = floatPreferencesKey("radar_opacity")
        val SAVED_LOCATIONS_JSON = stringPreferencesKey("saved_locations_json")
        val SELECTED_LOCATION_ID = stringPreferencesKey("selected_location_id")
        val ACTIVE_LOCATION_JSON = stringPreferencesKey("active_location_json")
        val LOCATION_NAG_DISMISSED = booleanPreferencesKey("location_nag_dismissed")
        val LOCATION_MIGRATION_VERSION = intPreferencesKey("location_migration_version")
        val MAP_TIMEZONE_MODE = stringPreferencesKey("map_timezone_mode")
        val HOME_TIMEZONE_ID = stringPreferencesKey("home_timezone_id")
        val CUSTOM_TIMEZONE_ID = stringPreferencesKey("custom_timezone_id")
    }

    companion object {
        const val DEFAULT_RADAR_OPACITY = 0.7f
        const val CURRENT_LOCATION_MIGRATION_VERSION = 1
    }
}
