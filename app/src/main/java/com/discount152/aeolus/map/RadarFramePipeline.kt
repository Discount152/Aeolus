package com.discount152.aeolus.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.discount152.aeolus.data.network.RainViewerApi
import com.discount152.aeolus.data.network.RainViewerFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

enum class RadarFrameStatus {
    PENDING,
    LOADING,
    READY,
    FAILED,
}

data class RadarFrameAsset(
    val time: Long,
    val path: String,
    val status: RadarFrameStatus,
    val bitmap: Bitmap? = null,
    val url: String? = null,
)

data class RadarSession(
    val host: String,
    val footprint: RadarFootprint,
    val frames: List<RadarFrameAsset>,
    val preparing: Boolean,
) {
    val readyFrames: List<RadarFrameAsset>
        get() = frames.filter { it.status == RadarFrameStatus.READY && it.bitmap != null }

    val playable: Boolean
        get() = readyFrames.size >= 2

    val newestReady: RadarFrameAsset?
        get() = readyFrames.maxByOrNull { it.time }
}

/**
 * Downloads complete RainViewer centered radar images (≈1 request per timestamp per viewport).
 * Newest frame first; historical frames with bounded concurrency; READY only after full decode.
 */
fun interface RadarImageDecoder {
    fun decode(bytes: ByteArray): Bitmap?
}

class RadarFramePipeline(
    private val rainViewerApi: RainViewerApi,
    private val okHttp: OkHttpClient,
    private val maxConcurrency: Int = 3,
    private val maxRetries: Int = 1,
    private val decoder: RadarImageDecoder = RadarImageDecoder { bytes ->
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    },
) {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, Bitmap>(64, 0.75f, true)
    private val maxCacheEntries = 48

    private val _session = MutableStateFlow<RadarSession?>(null)
    val session: StateFlow<RadarSession?> = _session.asStateFlow()

    private var metadataHost: String? = null
    private var metadataFrames: List<RainViewerFrame> = emptyList()
    private var metadataAt = 0L

    suspend fun ensureMetadata(force: Boolean = false): Pair<String, List<RainViewerFrame>> =
        mutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && metadataHost != null && metadataFrames.isNotEmpty() &&
                now - metadataAt < 5 * 60_000L
            ) {
                return metadataHost!! to metadataFrames
            }
            val maps = rainViewerApi.weatherMaps()
            val host = maps.host?.trimEnd('/') ?: error("RainViewer host missing")
            val past = maps.radar?.past.orEmpty().takeLast(24)
            metadataHost = host
            metadataFrames = past
            metadataAt = now
            host to past
        }

    /**
     * Load radar set for [footprint]. Reuses cache for identical frame/viewport keys.
     * Emits session updates as frames become READY.
     */
    suspend fun loadForViewport(footprint: RadarFootprint, forceMetadata: Boolean = false) {
        val (host, past) = try {
            ensureMetadata(forceMetadata)
        } catch (_: Exception) {
            _session.value = RadarSession(
                host = "",
                footprint = footprint,
                frames = emptyList(),
                preparing = false,
            )
            return
        }
        if (past.isEmpty()) {
            _session.value = RadarSession(host, footprint, emptyList(), preparing = false)
            return
        }

        val initial = past.map { f ->
            val key = RadarImages.frameCacheKey(f.path, footprint)
            val cached = mutex.withLock { cache[key] }
            if (cached != null && !cached.isRecycled) {
                RadarFrameAsset(
                    time = f.time,
                    path = f.path,
                    status = RadarFrameStatus.READY,
                    bitmap = cached,
                    url = RadarImages.centeredImageUrl(
                        host, f.path, footprint.zoom, footprint.centerLat, footprint.centerLon, footprint.sizePx,
                    ),
                )
            } else {
                RadarFrameAsset(f.time, f.path, RadarFrameStatus.PENDING)
            }
        }
        _session.value = RadarSession(host, footprint, initial, preparing = initial.any { it.status != RadarFrameStatus.READY })

        // Newest first
        val ordered = past.sortedByDescending { it.time }
        val newest = ordered.first()
        downloadOne(host, newest, footprint)
        // Remaining with bounded concurrency
        val rest = ordered.drop(1)
        coroutineScope {
            val semaphore = Semaphore(maxConcurrency)
            rest.map { frame ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        downloadOne(host, frame, footprint)
                    }
                }
            }.awaitAll()
        }
        val current = _session.value
        if (current != null && current.footprint.cacheKeyPart() == footprint.cacheKeyPart()) {
            _session.value = current.copy(preparing = false)
        }
    }

    /** Fetch a single frame on demand (e.g. scrub to not-yet-ready). */
    suspend fun ensureFrame(time: Long) {
        val sess = _session.value ?: return
        val asset = sess.frames.find { it.time == time } ?: return
        if (asset.status == RadarFrameStatus.READY && asset.bitmap != null) return
        val meta = metadataFrames.find { it.time == time } ?: return
        downloadOne(sess.host, meta, sess.footprint)
    }

    fun playbackFrames(): List<RadarFrameAsset> =
        _session.value?.readyFrames?.sortedBy { it.time }.orEmpty()

    fun clearSession() {
        _session.value = null
    }

    private suspend fun downloadOne(
        host: String,
        frame: RainViewerFrame,
        footprint: RadarFootprint,
    ) {
        val key = RadarImages.frameCacheKey(frame.path, footprint)
        mutex.withLock {
            cache[key]?.takeIf { !it.isRecycled }?.let { bmp ->
                updateFrame(frame.time) {
                    it.copy(status = RadarFrameStatus.READY, bitmap = bmp, url = centeredUrl(host, frame, footprint))
                }
                return
            }
        }
        updateFrame(frame.time) {
            it.copy(status = RadarFrameStatus.LOADING, url = centeredUrl(host, frame, footprint))
        }

        var lastError: Exception? = null
        repeat(maxRetries + 1) { attempt ->
            try {
                val url = centeredUrl(host, frame, footprint)
                val bitmap = withContext(Dispatchers.IO) {
                    val req = Request.Builder().url(url).get().build()
                    okHttp.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        val bytes = resp.body?.bytes() ?: error("empty body")
                        decoder.decode(bytes) ?: error("decode failed")
                    }
                }
                mutex.withLock {
                    putCache(key, bitmap)
                }
                updateFrame(frame.time) {
                    it.copy(status = RadarFrameStatus.READY, bitmap = bitmap, url = url)
                }
                return
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(200L * (attempt + 1))
                }
            }
        }
        updateFrame(frame.time) {
            it.copy(status = RadarFrameStatus.FAILED, bitmap = null)
        }
        @Suppress("UNUSED_EXPRESSION")
        lastError
    }

    private fun centeredUrl(host: String, frame: RainViewerFrame, footprint: RadarFootprint): String =
        RadarImages.centeredImageUrl(
            host = host,
            path = frame.path,
            zoom = footprint.zoom,
            latitude = footprint.centerLat,
            longitude = footprint.centerLon,
            sizePx = footprint.sizePx,
        )

    private fun updateFrame(time: Long, transform: (RadarFrameAsset) -> RadarFrameAsset) {
        val sess = _session.value ?: return
        val updated = sess.frames.map { if (it.time == time) transform(it) else it }
        val preparing = updated.any {
            it.status == RadarFrameStatus.PENDING || it.status == RadarFrameStatus.LOADING
        }
        _session.value = sess.copy(frames = updated, preparing = preparing)
    }

    private fun putCache(key: String, bitmap: Bitmap) {
        cache[key] = bitmap
        while (cache.size > maxCacheEntries) {
            val eldest = cache.entries.iterator().next()
            cache.remove(eldest.key)
            // Do not recycle — may still be displayed
        }
    }
}
