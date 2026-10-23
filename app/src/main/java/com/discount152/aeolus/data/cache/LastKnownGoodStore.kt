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
import java.io.FileOutputStream

data class LastKnownGoodSnapshot(
    val schemaVersion: Int,
    val location: GeoLocation,
    val lastSuccessfulRefreshEpochMs: Long,
    val bundle: WeatherBundle,
)

/**
 * Single-file boot snapshot independent of per-location [ForecastCache] keys.
 * Atomic temp+rename with fsync so force-stop after a successful write cannot leave an empty target.
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

    fun absolutePath(): String = snapshotFile.absolutePath

    fun read(): LastKnownGoodSnapshot? {
        val exists = snapshotFile.isFile
        val size = if (exists) snapshotFile.length() else 0L
        Log.i(
            TAG,
            "startup_read: path=${snapshotFile.absolutePath} exists=$exists size=$size",
        )
        if (!exists) {
            return null
        }
        return runCatching {
            val text = snapshotFile.readText()
            val payload = json.decodeFromString<LastKnownGoodPayload>(text)
            if (payload.schemaVersion != SCHEMA_VERSION) {
                Log.w(
                    TAG,
                    "startup_read: unsupported schema=${payload.schemaVersion} expected=$SCHEMA_VERSION size=$size",
                )
                return null
            }
            val snap = LastKnownGoodSnapshot(
                schemaVersion = payload.schemaVersion,
                location = payload.location,
                lastSuccessfulRefreshEpochMs = payload.lastSuccessfulRefreshEpochMs,
                bundle = payload.bundle,
            )
            Log.i(
                TAG,
                "startup_read: decode_ok version=${payload.schemaVersion} " +
                    "locationId=${payload.location.id} name=${payload.location.name} " +
                    "refreshedAt=${payload.lastSuccessfulRefreshEpochMs} " +
                    "models=${payload.bundle.models.size} daily=${payload.bundle.consensusDaily.size} " +
                    "hourly=${payload.bundle.consensusHourly.size} " +
                    "ensemble=${payload.bundle.ensemble != null}",
            )
            snap
        }.onFailure { e ->
            Log.e(TAG, "startup_read: decode_failed exists=true size=$size", e)
        }.getOrNull()
    }

    fun write(bundle: WeatherBundle): Boolean {
        snapshotFile.parentFile?.mkdirs()
        Log.i(
            TAG,
            "write_attempt: path=${snapshotFile.absolutePath} schema=$SCHEMA_VERSION " +
                "locationId=${bundle.location.id} name=${bundle.location.name} " +
                "fetchedAt=${bundle.fetchedAtEpochMs} " +
                "daily=${bundle.consensusDaily.size} hourly=${bundle.consensusHourly.size}",
        )
        // Defense in depth: never destroy a prior good snapshot with an empty/partial bundle.
        val usable = bundle.consensusDaily.isNotEmpty() &&
            bundle.consensusHourly.isNotEmpty() &&
            bundle.models.any { it.available }
        if (!usable) {
            Log.e(
                TAG,
                "write_refused_incoherent: locationId=${bundle.location.id} " +
                    "daily=${bundle.consensusDaily.size} hourly=${bundle.consensusHourly.size} " +
                    "availableModels=${bundle.models.count { it.available}} " +
                    "priorExists=${snapshotFile.isFile} priorSize=${if (snapshotFile.isFile) snapshotFile.length() else 0}",
            )
            return false
        }
        val payload = LastKnownGoodPayload(
            schemaVersion = SCHEMA_VERSION,
            location = bundle.location,
            lastSuccessfulRefreshEpochMs = bundle.fetchedAtEpochMs,
            bundle = bundle,
        )
        val bytes = try {
            json.encodeToString(payload).encodeToByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "write: serialize_failed locationId=${bundle.location.id}", e)
            return false
        }
        val temp = File(snapshotFile.parentFile, "${snapshotFile.name}.tmp")
        return try {
            FileOutputStream(temp).use { fos ->
                fos.write(bytes)
                fos.fd.sync()
            }
            if (snapshotFile.exists()) snapshotFile.delete()
            if (!temp.renameTo(snapshotFile)) {
                temp.copyTo(snapshotFile, overwrite = true)
                temp.delete()
                FileOutputStream(snapshotFile, true).use { it.fd.sync() }
            }
            val finalSize = snapshotFile.length()
            Log.i(
                TAG,
                "write_success: bytes=${bytes.size} finalSize=$finalSize " +
                    "locationId=${bundle.location.id} version=$SCHEMA_VERSION",
            )
            true
        } catch (e: Exception) {
            if (temp.exists()) temp.delete()
            Log.e(TAG, "write_failed: locationId=${bundle.location.id}", e)
            false
        }
    }

    fun clear() {
        val existed = snapshotFile.isFile
        val deleted = if (existed) snapshotFile.delete() else true
        Log.i(TAG, "clear: existed=$existed deleted=$deleted existsNow=${snapshotFile.isFile}")
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
