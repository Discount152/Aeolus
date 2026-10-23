package com.discount152.aeolus.location

import com.discount152.aeolus.domain.models.GeoLocation
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SavedLocationMigrationTest {
    @Test
    fun migrate_removesAutoFallbackAmsterdam_keepsUserCities() {
        val lockedFallback = GeoLocation(
            id = "default:amsterdam",
            name = "Amsterdam",
            latitude = 52.37,
            longitude = 4.89,
            timezone = "Europe/Amsterdam",
            country = "Netherlands",
            admin1 = "North Holland",
        )
        val mapPin = GeoLocation(
            id = "map:41.01,28.97",
            name = "Map pin",
            latitude = 41.01,
            longitude = 28.97,
            timezone = "Europe/Istanbul",
        )
        val userCity = GeoLocation(
            id = "2759794",
            name = "Rotterdam",
            latitude = 51.92,
            longitude = 4.48,
            timezone = "Europe/Amsterdam",
            country = "Netherlands",
        )
        val migrated = LocationIdentity.migrateSavedLocations(listOf(lockedFallback, mapPin, userCity))
        assertThat(migrated.any { LocationIdentity.isFallback(it) }).isFalse()
        assertThat(migrated).hasSize(2)
        assertThat(migrated.map { it.id }).contains(LocationIdentity.stableId(mapPin))
        assertThat(migrated.map { it.id }).contains(LocationIdentity.stableId(userCity))
        // Every migrated id is a normal stable id — deletable by id match
        migrated.forEach { loc ->
            val afterDelete = migrated.filterNot { it.id == loc.id }
            assertThat(afterDelete).hasSize(migrated.size - 1)
        }
    }

    @Test
    fun migrate_normalizesMapPinToCoordKey() {
        val pin = GeoLocation("map:52.37012,4.89045", "Map pin", 52.37012, 4.89045, "UTC")
        val migrated = LocationIdentity.migrateSavedLocations(listOf(pin))
        assertThat(migrated).hasSize(1)
        assertThat(migrated.first().id).isEqualTo(LocationIdentity.coordKey(52.37012, 4.89045))
    }

    @Test
    fun explicitAmsterdamCoordSave_survivesMigration() {
        val explicit = GeoLocation(
            id = LocationIdentity.coordKey(52.37, 4.89),
            name = "Amsterdam",
            latitude = 52.37,
            longitude = 4.89,
            timezone = "Europe/Amsterdam",
            country = "Netherlands",
        )
        val migrated = LocationIdentity.migrateSavedLocations(listOf(explicit))
        assertThat(migrated).hasSize(1)
        assertThat(migrated.first().id).isEqualTo(explicit.id)
    }
}
