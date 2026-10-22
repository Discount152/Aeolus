package com.discount152.aeolus.location

import com.discount152.aeolus.domain.models.GeoLocation
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocationIdentityTest {
    private fun loc(
        id: String,
        name: String,
        lat: Double,
        lon: Double,
        country: String? = "Netherlands",
        admin: String? = null,
    ) = GeoLocation(id, name, lat, lon, "Europe/Amsterdam", country, admin)

    @Test
    fun sameSearchResult_savedTwice_singleEntry() {
        val a = loc("123", "Amsterdam", 52.37, 4.89)
        val b = loc("123", "Amsterdam", 52.37, 4.89)
        val once = LocationIdentity.upsert(emptyList(), a)
        val twice = LocationIdentity.upsert(once, b)
        assertThat(twice).hasSize(1)
        assertThat(twice.first().id).isEqualTo(LocationIdentity.stableId(a))
    }

    @Test
    fun sameSearchResult_caseAndSpacing_stillDeduped() {
        val a = loc("55", "Amsterdam", 52.37, 4.89)
        val b = loc("55", "  amsterdam ", 52.3701, 4.8901)
        val out = LocationIdentity.upsert(listOf(LocationIdentity.withStableId(a)), b)
        assertThat(out).hasSize(1)
    }

    @Test
    fun currentLocation_smallGpsJitter_sameCacheKey() {
        val a = loc("device:1", "Current location", 52.3700, 4.8900)
        val b = loc("device:2", "Current location", 52.3704, 4.8904)
        assertThat(LocationIdentity.cacheKey(a)).isEqualTo(LocationIdentity.cacheKey(b))
        val out = LocationIdentity.upsert(listOf(LocationIdentity.withStableId(a)), b, fromDevice = true)
        assertThat(out).hasSize(1)
    }

    @Test
    fun genuinelyDifferentNearbyCities_notMerged() {
        val amsterdam = loc("1", "Amsterdam", 52.37, 4.89)
        val haarlem = loc("2", "Haarlem", 52.38, 4.64, admin = "North Holland")
        val out = LocationIdentity.upsert(
            listOf(LocationIdentity.withStableId(amsterdam)),
            haarlem,
        )
        assertThat(out).hasSize(2)
    }

    @Test
    fun dedupe_migratesObviousDuplicates() {
        val a = loc("123", "Amsterdam", 52.37, 4.89)
        val b = loc("123", "Amsterdam", 52.37, 4.89)
        val c = loc("device:52.3705,4.8905", "Current location", 52.3705, 4.8905)
        val out = LocationIdentity.dedupe(listOf(a, b, c))
        assertThat(out.size).isAtMost(2)
    }

    @Test
    fun fallback_hasStableId() {
        assertThat(LocationIdentity.FALLBACK_LOCATION.id).isEqualTo(LocationIdentity.FALLBACK_ID)
        assertThat(LocationIdentity.isFallback(LocationIdentity.FALLBACK_LOCATION)).isTrue()
    }
}
