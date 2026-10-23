package com.discount152.aeolus.data.repository

import com.discount152.aeolus.domain.models.GeoLocation
import com.discount152.aeolus.location.LocationIdentity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Spec 1.0.8: map pins must not require city search TZ; request uses auto. */
class MapPinTimezoneTest {
    @Test
    fun mapPin_isEphemeral_andStableCoordId() {
        val pin = GeoLocation(
            id = "map:53.25000,6.55000",
            name = "Map pin",
            latitude = 53.25,
            longitude = 6.55,
            timezone = "auto",
        )
        val stable = LocationIdentity.withStableId(pin)
        assertThat(LocationIdentity.isEphemeralActive(pin)).isTrue()
        assertThat(stable.id).startsWith("coord:")
        assertThat(LocationIdentity.cacheKey(stable)).isEqualTo(stable.id)
    }

    @Test
    fun citySearch_isNotEphemeral() {
        val city = GeoLocation(
            id = "geo:2755251",
            name = "Groningen",
            latitude = 53.2194,
            longitude = 6.5665,
            timezone = "Europe/Amsterdam",
        )
        assertThat(LocationIdentity.isEphemeralActive(city)).isFalse()
    }
}
