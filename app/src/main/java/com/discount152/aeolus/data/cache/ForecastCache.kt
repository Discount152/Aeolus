package com.discount152.aeolus.data.cache

import android.content.Context
import android.util.Log
import com.discount152.aeolus.domain.models.WeatherBundle
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ForecastCache(
    private val cacheDir: File,
    private val json: Json,
) {
    constructor(context: Context, json: Json) : this(
        File(context.filesDir, "cache/forecasts"),
        json,
    ) {
        cacheDir.mkdirs()
    }

    init {
        cacheDir.mkdirs()
    }

    fun read(locationId: String): WeatherBundle? {
        val file = fileFor(locationId)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<WeatherBundle>(file.readText())
        }.onFailure { e ->
            Log.e(TAG, "Failed to decode forecast cache for $locationId", e)
        }.getOrNull()
    }

    /** Fallback when exact id miss (e.g. GPS jitter id change) — most recent bundle for nearby coords. */
    fun findNearest(location: com.discount152.aeolus.domain.models.GeoLocation): WeatherBundle? {
        val files = cacheDir.listFiles()?.filter { it.isFile && it.extension == "json" }.orEmpty()
        var best: WeatherBundle? = null
        var bestDist = Double.MAX_VALUE
        for (file in files) {
            val bundle = runCatching {
                json.decodeFromString<WeatherBundle>(file.readText())
            }.onFailure { e ->
                Log.e(TAG, "Failed to decode forecast cache file ${file.name}", e)
            }.getOrNull() ?: continue
            val d = com.discount152.aeolus.location.LocationIdentity.distanceMeters(
                location.latitude,
                location.longitude,
                bundle.location.latitude,
                bundle.location.longitude,
            )
            if (d < bestDist && d <= com.discount152.aeolus.location.LocationIdentity.GPS_MATCH_RADIUS_M) {
                bestDist = d
                best = bundle
            }
        }
        return best
    }

    fun write(locationId: String, bundle: WeatherBundle) {
        val target = fileFor(locationId)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            temp.writeText(json.encodeToString(bundle))
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (e: Exception) {
            if (temp.exists()) temp.delete()
            throw e
        }
    }

    fun clear(locationId: String? = null) {
        if (locationId != null) {
            fileFor(locationId).delete()
            return
        }
        cacheDir.listFiles()?.forEach { child ->
            if (child.isFile && child.extension == "json") child.delete()
        }
    }

    private fun fileFor(locationId: String): File {
        val safeId = locationId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(cacheDir, "$safeId.json")
    }

    private companion object {
        const val TAG = "ForecastCache"
    }
}
