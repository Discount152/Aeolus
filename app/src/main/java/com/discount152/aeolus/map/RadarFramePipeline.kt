package com.discount152.aeolus.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.discount152.aeolus.data.network.RainViewerApi
import com.discount152.aeolus.data.network.RainViewerFrame
import kotlinx.coroutines.CancellationException
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
    val generation: Long = 0L,
) {
    val readyFrames: List<RadarFrameAsset>
        get() = frames.filter { it.status == RadarFrameStatus.READY && it.bitmap != null }

    val playable: Boolean
        get() = readyFrames.size >= 2

    val newestReady: RadarFrameAsset?
        get() = readyFrames.maxByOrNull { it.time }
}

/**
 * Downloads RainViewer XYZ tiles (≤~4/frame), stitches complete mosaics, then exposes
 * READY frames to MapLibre via ImageSource. MapLibre never fetches animated radar tiles.
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
    private val stitcher: (RadarFootprint, List<Bitmap>) -> Bitmap = { fp, tiles ->
        RadarMosaic.stitch(fp, tiles)
    },
) {
    private val mutex = Mutex()
    /** Completed mosaic frames. */
    private val frameCache = LinkedHashMap<String, Bitmap>(64, 0.75f, true)
    private val maxFrameCacheEntries = 48
    /** Individual XYZ tiles shared across footprints. */
    private val tileCache = LinkedHashMap<String, Bitmap>(256, 0.75f, true)
    private val maxTileCacheEntries = 200

    private val _session = MutableStateFlow<RadarSession?>(null)
    val session: StateFlow<RadarSession?> = _session.asStateFlow()

    /** Test/diagnostic: network tile fetches since last clear. */
    @Volatile
    var networkTileFetches: Int = 0
        private set

    private var metadataHost: String? = null
    private var metadataFrames: List<RainViewerFrame> = emptyList()
    private var metadataAt = 0L

    @Volatile
    private var activeGeneration: Long = 0L

    fun resetNetworkCounters() {
        networkTileFetches = 0
    }

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
     * Load mosaic set for [footprint]. Reuses tile/frame caches.
     * Emits session updates as frames become READY. [generation] must match MapGeneration.
     */
    suspend fun loadForViewport(
        footprint: RadarFootprint,
        forceMetadata: Boolean = false,
        generation: Long = 0L,
    ) {
        activeGeneration = generation
        val (host, past) = try {
            ensureMetadata(forceMetadata)
        } catch (e: Exception) {
            if (e is CancellationException) {
                discardIfGeneration(generation)
                throw e
            }
            if (generation == activeGeneration) {
                _session.value = RadarSession(
                    host = "",
                    footprint = footprint,
                    frames = emptyList(),
                    preparing = false,
                    generation = generation,
                )
            }
            return
        }
        if (generation != activeGeneration) return
        if (past.isEmpty()) {
            _session.value = RadarSession(host, footprint, emptyList(), preparing = false, generation)
            return
        }

        val initial = past.map { f ->
            val key = RadarImages.frameCacheKey(f.path, footprint)
            val cached = mutex.withLock { frameCache[key] }
            if (cached != null && !cached.isRecycled) {
                RadarFrameAsset(
                    time = f.time,
                    path = f.path,
                    status = RadarFrameStatus.READY,
                    bitmap = cached,
                    url = mosaicLabel(host, f.path, footprint),
                )
            } else {
                RadarFrameAsset(f.time, f.path, RadarFrameStatus.PENDING)
            }
        }
        _session.value = RadarSession(
            host, footprint, initial,
            preparing = initial.any { it.status != RadarFrameStatus.READY },
            generation = generation,
        )

        try {
            val ordered = past.sortedByDescending { it.time }
            val newest = ordered.first()
            downloadMosaic(host, newest, footprint, generation)
            val rest = ordered.drop(1)
            coroutineScope {
                val semaphore = Semaphore(maxConcurrency)
                rest.map { frame ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            downloadMosaic(host, frame, footprint, generation)
                        }
                    }
                }.awaitAll()
            }
            val current = _session.value
            if (current != null &&
                current.generation == generation &&
                generation == activeGeneration &&
                current.footprint.cacheKeyPart() == footprint.cacheKeyPart()
            ) {
                _session.value = current.copy(preparing = false)
            }
        } catch (e: CancellationException) {
            discardIfGeneration(generation)
            throw e
        }
    }

    private fun discardIfGeneration(generation: Long) {
        val cur = _session.value
        if (cur != null && cur.generation == generation && generation == activeGeneration) {
            _session.value = cur.copy(preparing = false)
        } else if (cur != null && cur.generation == generation && generation != activeGeneration) {
            // Old generation cancelled — do not touch newer session.
        }
    }

    suspend fun ensureFrame(time: Long) {
        val sess = _session.value ?: return
        val asset = sess.frames.find { it.time == time } ?: return
        if (asset.status == RadarFrameStatus.READY && asset.bitmap != null) return
        val meta = metadataFrames.find { it.time == time } ?: return
        downloadMosaic(sess.host, meta, sess.footprint, sess.generation)
    }

    fun playbackFrames(): List<RadarFrameAsset> =
        _session.value?.readyFrames?.sortedBy { it.time }.orEmpty()

    fun clearSession() {
        _session.value = null
    }

    private suspend fun downloadMosaic(
        host: String,
        frame: RainViewerFrame,
        footprint: RadarFootprint,
        generation: Long,
    ) {
        if (generation != activeGeneration) return
        val frameKey = RadarImages.frameCacheKey(frame.path, footprint)
        mutex.withLock {
            frameCache[frameKey]?.takeIf { !it.isRecycled }?.let { bmp ->
                updateFrame(frame.time, generation) {
                    it.copy(
                        status = RadarFrameStatus.READY,
                        bitmap = bmp,
                        url = mosaicLabel(host, frame.path, footprint),
                    )
                }
                return
            }
        }
        updateFrame(frame.time, generation) {
            it.copy(
                status = RadarFrameStatus.LOADING,
                url = mosaicLabel(host, frame.path, footprint),
            )
        }

        val tiles = footprint.tiles
        if (tiles.isEmpty()) {
            updateFrame(frame.time, generation) { it.copy(status = RadarFrameStatus.FAILED, bitmap = null) }
            return
        }

        val decoded = ArrayList<Bitmap>(tiles.size)
        for (tile in tiles) {
            if (generation != activeGeneration) return
            val bmp = fetchTile(host, frame.path, tile, footprint.tileSizePx)
            if (bmp == null) {
                updateFrame(frame.time, generation) { it.copy(status = RadarFrameStatus.FAILED, bitmap = null) }
                return
            }
            decoded.add(bmp)
        }

        val mosaic = try {
            withContext(Dispatchers.Default) {
                stitcher(footprint, decoded)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            updateFrame(frame.time, generation) { it.copy(status = RadarFrameStatus.FAILED, bitmap = null) }
            return
        }

        mutex.withLock { putFrameCache(frameKey, mosaic) }
        updateFrame(frame.time, generation) {
            it.copy(
                status = RadarFrameStatus.READY,
                bitmap = mosaic,
                url = mosaicLabel(host, frame.path, footprint),
            )
        }
    }

    private suspend fun fetchTile(
        host: String,
        path: String,
        tile: RadarTileCoord,
        sizePx: Int,
    ): Bitmap? {
        val key = RadarImages.tileCacheKey(path, tile.z, tile.x, tile.y, sizePx)
        mutex.withLock {
            tileCache[key]?.takeIf { !it.isRecycled }?.let { return it }
        }

        var lastError: Exception? = null
        repeat(maxRetries + 1) { attempt ->
            try {
                val url = RadarImages.tileUrl(host, path, tile.z, tile.x, tile.y, sizePx)
                val bitmap = withContext(Dispatchers.IO) {
                    val req = Request.Builder().url(url).get().build()
                    okHttp.newCall(req).execute().use { resp ->
                        if (resp.code == 429) error("HTTP 429")
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        val bytes = resp.body?.bytes() ?: error("empty body")
                        decoder.decode(bytes) ?: error("decode failed")
                    }
                }
                networkTileFetches++
                mutex.withLock { putTileCache(key, bitmap) }
                return bitmap
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(200L * (attempt + 1))
                }
            }
        }
        @Suppress("UNUSED_EXPRESSION")
        lastError
        return null
    }

    private fun mosaicLabel(host: String, path: String, footprint: RadarFootprint): String =
        "mosaic:$host$path z${footprint.zoom} ${footprint.tilesX}x${footprint.tilesY}"

    private fun updateFrame(
        time: Long,
        generation: Long,
        transform: (RadarFrameAsset) -> RadarFrameAsset,
    ) {
        if (generation != activeGeneration) return
        val sess = _session.value ?: return
        if (sess.generation != generation) return
        val updated = sess.frames.map { if (it.time == time) transform(it) else it }
        val preparing = updated.any {
            it.status == RadarFrameStatus.PENDING || it.status == RadarFrameStatus.LOADING
        }
        _session.value = sess.copy(frames = updated, preparing = preparing)
    }

    private fun putFrameCache(key: String, bitmap: Bitmap) {
        frameCache[key] = bitmap
        while (frameCache.size > maxFrameCacheEntries) {
            val eldest = frameCache.entries.iterator().next()
            frameCache.remove(eldest.key)
        }
    }

    private fun putTileCache(key: String, bitmap: Bitmap) {
        tileCache[key] = bitmap
        while (tileCache.size > maxTileCacheEntries) {
            val eldest = tileCache.entries.iterator().next()
            tileCache.remove(eldest.key)
        }
    }
}
