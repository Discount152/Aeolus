package com.discount152.aeolus.data.cache

import android.content.Context
import android.util.Log
import com.discount152.aeolus.domain.models.GeoLocation
import com.discount152.aeolus.domain.models.WeatherBundle
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

data class LastKnownGoodSnapshot(
    val schemaVersion: Int,
    val location: GeoLocation,
    val lastSuccessfulRefreshEpochMs: Long,
    val bundle: WeatherBundle,
)

/**
 * Single-file boot snapshot independent of per-location [ForecastCache] keys.
 */
class LastKnownGoodStore(
    private val snapshotFile: File,
    private val json: Json,
) {
    constructor(context: Context, json: Json) : this(
        File(context.filesDir, "cache/last_known_good.json"),
        json,
    ) {
        snapshotFile.parentFile?.mkdirs()
    }

    init {
        snapshotFile.parentFile?.mkdirs()
    }

    fun read(): LastKnownGoodSnapshot? {
        val exists = snapshotFile.isFile
        val size = if (exists) snapshotFile.length() else 0L
        if (!exists) {
            Log.d(TAG, "read: missing exists=false size=0")
            return null
        }
        return runCatching {
            val text = snapshotFile.readText()
            val payload = json.decodeFromString<LastKnownGoodPayload>(text)
            if (payload.schemaVersion != SCHEMA_VERSION) {
                Log.w(
                    TAG,
                    "read: unsupported schema=${payload.schemaVersion} expected=$SCHEMA_VERSION size=$size",
                )
                return null
            }
            val snap = LastKnownGoodSnapshot(
                schemaVersion = payload.schemaVersion,
                location = payload.location,
                lastSuccessfulRefreshEpochMs = payload.lastSuccessfulRefreshEpochMs,
                bundle = payload.bundle,
            )
            Log.d(
                TAG,
                "read: ok exists=true size=$size version=${payload.schemaVersion} " +
                    "locationId=${payload.location.id} refreshedAt=${payload.lastSuccessfulRefreshEpochMs}",
            )
            snap
        }.onFailure { e ->
            Log.e(TAG, "read: failed exists=true size=$size", e)
        }.getOrNull()
    }

    fun write(bundle: WeatherBundle): Boolean {
        snapshotFile.parentFile?.mkdirs()
        val payload = LastKnownGoodPayload(
            schemaVersion = SCHEMA_VERSION,
            location = bundle.location,
            lastSuccessfulRefreshEpochMs = bundle.fetchedAtEpochMs,
            bundle = bundle,
        )
        val text = json.encodeToString(payload)
        val temp = File(snapshotFile.parentFile, "${snapshotFile.name}.tmp")
        return try {
            temp.writeText(text)
            if (!temp.renameTo(snapshotFile)) {
                temp.copyTo(snapshotFile, overwrite = true)
                temp.delete()
            }
            Log.d(
                TAG,
                "write: success locationId=${bundle.location.id} version=$SCHEMA_VERSION bytes=${text.length}",
            )
            true
        } catch (e: Exception) {
            if (temp.exists()) temp.delete()
            Log.e(TAG, "write: failed locationId=${bundle.location.id}", e)
            false
        }
    }

    fun clear() {
        val existed = snapshotFile.isFile
        val deleted = if (existed) snapshotFile.delete() else true
        Log.d(TAG, "clear: existed=$existed deleted=$deleted existsNow=${snapshotFile.isFile}")
    }

    @Serializable
    private data class LastKnownGoodPayload(
        val schemaVersion: Int,
        val location: GeoLocation,
        val lastSuccessfulRefreshEpochMs: Long,
        val bundle: WeatherBundle,
    )

    companion object {
        const val TAG = "LastKnownGood"
        const val SCHEMA_VERSION = 1
    }
}
