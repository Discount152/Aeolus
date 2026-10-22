package com.discount152.aeolus.data.cache

import android.content.Context
import com.discount152.aeolus.domain.models.WeatherBundle
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ForecastCache(
    context: Context,
    private val json: Json,
) {
    private val cacheDir: File =
        File(context.filesDir, "cache/forecasts").also { it.mkdirs() }

    fun read(locationId: String): WeatherBundle? {
        val file = fileFor(locationId)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<WeatherBundle>(file.readText())
        }.getOrNull()
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
}
