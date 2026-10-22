package com.discount152.aeolus.data.cache

import android.content.Context
import com.discount152.aeolus.domain.models.WeatherBundle
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ForecastSnapshotStore(
    context: Context,
    private val json: Json,
) {
    private val storeDir: File =
        File(context.filesDir, "cache/snapshots").also { it.mkdirs() }

    private val maxSnapshots = 8

    fun record(locationId: String, bundle: WeatherBundle) {
        val precipByDate = mutableMapOf<String, Double>()
        val tempMaxByDate = mutableMapOf<String, Double>()
        val confidenceByDate = mutableMapOf<String, Int>()
        for (day in bundle.consensusDaily) {
            day.precipitationSumMm?.let { precipByDate[day.date] = it }
            day.tempMaxC?.let { tempMaxByDate[day.date] = it }
            bundle.confidenceByDate[day.date]?.score?.let { confidenceByDate[day.date] = it }
        }
        val entry = SnapshotEntry(
            recordedAtEpochMs = bundle.fetchedAtEpochMs,
            precipByDate = precipByDate,
            tempMaxByDate = tempMaxByDate,
            confidenceByDate = confidenceByDate,
        )
        val file = fileFor(locationId)
        val existing = readEntries(file)
        val updated = (existing + entry).takeLast(maxSnapshots)
        writeEntries(file, updated)
    }

    fun precipHistory(locationId: String): Map<String, List<Double>> =
        historyByDate(locationId) { it.precipByDate }

    fun tempHistory(locationId: String): Map<String, List<Double>> =
        historyByDate(locationId) { it.tempMaxByDate }

    fun confidenceHistory(locationId: String): Map<String, List<Int>> {
        val entries = readEntries(fileFor(locationId))
        if (entries.isEmpty()) return emptyMap()
        val dates = entries.flatMap { it.confidenceByDate.keys }.toSet()
        return dates.associateWith { date ->
            entries.mapNotNull { it.confidenceByDate[date] }
        }
    }

    private fun historyByDate(
        locationId: String,
        selector: (SnapshotEntry) -> Map<String, Double>,
    ): Map<String, List<Double>> {
        val entries = readEntries(fileFor(locationId))
        if (entries.isEmpty()) return emptyMap()
        val dates = entries.flatMap { selector(it).keys }.toSet()
        return dates.associateWith { date ->
            entries.mapNotNull { selector(it)[date] }
        }
    }

    private fun readEntries(file: File): List<SnapshotEntry> {
        if (!file.isFile) return emptyList()
        return runCatching {
            json.decodeFromString<SnapshotFile>(file.readText()).snapshots
        }.getOrDefault(emptyList())
    }

    private fun writeEntries(file: File, entries: List<SnapshotEntry>) {
        file.parentFile?.mkdirs()
        val target = file
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            temp.writeText(json.encodeToString(SnapshotFile(entries)))
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (e: Exception) {
            if (temp.exists()) temp.delete()
            throw e
        }
    }

    private fun fileFor(locationId: String): File {
        val safeId = locationId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(storeDir, "$safeId.json")
    }

    @Serializable
    private data class SnapshotFile(val snapshots: List<SnapshotEntry> = emptyList())

    @Serializable
    private data class SnapshotEntry(
        val recordedAtEpochMs: Long,
        val precipByDate: Map<String, Double> = emptyMap(),
        val tempMaxByDate: Map<String, Double> = emptyMap(),
        val confidenceByDate: Map<String, Int> = emptyMap(),
    )
}
