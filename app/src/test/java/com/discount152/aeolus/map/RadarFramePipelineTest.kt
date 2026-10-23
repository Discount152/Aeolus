package com.discount152.aeolus.map

import android.graphics.Bitmap
import com.discount152.aeolus.data.network.RainViewerApi
import com.discount152.aeolus.data.network.RainViewerFrame
import com.discount152.aeolus.data.network.RainViewerMapsResponse
import com.discount152.aeolus.data.network.RainViewerRadar
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class RadarFramePipelineTest {
    private lateinit var server: MockWebServer
    private lateinit var api: RainViewerApi
    private val fakeBitmap: Bitmap = mockk(relaxed = true) {
        every { isRecycled } returns false
    }
    private val decoder = RadarImageDecoder { fakeBitmap }
    private val stitcher: (RadarFootprint, List<Bitmap>) -> Bitmap = { _, tiles ->
        tiles.first()
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = mockk()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueuePng(count: Int = 1) {
        repeat(count) {
            server.enqueue(
                MockResponse()
                    .setBody(Buffer().write(byteArrayOf(1, 2, 3, 4)))
                    .setResponseCode(200),
            )
        }
    }

    private fun pipeline() = RadarFramePipeline(
        api, OkHttpClient(), maxConcurrency = 2, decoder = decoder, stitcher = stitcher,
    )

    @Test
    fun newestFrameFirst_thenHistoricalReadyOnly() = runBlocking {
        val host = server.url("/").toString().trimEnd('/')
        val frames = listOf(
            RainViewerFrame(100, "/v2/radar/old"),
            RainViewerFrame(200, "/v2/radar/mid"),
            RainViewerFrame(300, "/v2/radar/new"),
        )
        coEvery { api.weatherMaps() } returns RainViewerMapsResponse(
            host = host,
            radar = RainViewerRadar(past = frames),
        )
        val fp = RadarViewport.footprintForVisibleBounds(51.0, 52.0, 4.0, 5.0)
        enqueuePng(frames.size * fp.tiles.size)
        val p = pipeline()
        p.loadForViewport(fp)

        val session = p.session.value!!
        assertThat(session.readyFrames).hasSize(3)
        session.readyFrames.forEach { frame ->
            assertThat(frame.status).isEqualTo(RadarFrameStatus.READY)
            assertThat(frame.bitmap).isNotNull()
        }
        val firstReq = server.takeRequest(2, TimeUnit.SECONDS)
        assertThat(firstReq).isNotNull()
        assertThat(firstReq!!.path).contains("radar/new")
        assertThat(fp.tiles.size).isAtMost(RadarViewport.MAX_TILES_PER_FRAME)
    }

    @Test
    fun failedTile_preventsFrameReady() = runBlocking {
        val host = server.url("/").toString().trimEnd('/')
        val frames = listOf(
            RainViewerFrame(100, "/v2/radar/a"),
            RainViewerFrame(200, "/v2/radar/b"),
        )
        coEvery { api.weatherMaps() } returns RainViewerMapsResponse(
            host = host,
            radar = RainViewerRadar(past = frames),
        )
        val fp = RadarViewport.footprintForVisibleBounds(51.0, 52.0, 4.0, 5.0)
        // Newest (200) succeeds all tiles; oldest fails first tile after retries
        enqueuePng(fp.tiles.size)
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))

        val p = RadarFramePipeline(
            api, OkHttpClient(), maxConcurrency = 1, maxRetries = 1,
            decoder = decoder, stitcher = stitcher,
        )
        p.loadForViewport(fp)

        val sess = p.session.value!!
        val ready = p.playbackFrames()
        assertThat(ready).hasSize(1)
        assertThat(ready.single().time).isEqualTo(200L)
        val failed = sess.frames.find { it.time == 100L }
        assertThat(failed?.status).isEqualTo(RadarFrameStatus.FAILED)
        assertThat(failed?.bitmap).isNull()
    }

    @Test
    fun playbackLoop_usesCache_noExtraNetwork() = runBlocking {
        val host = server.url("/").toString().trimEnd('/')
        val frames = listOf(
            RainViewerFrame(100, "/v2/radar/a"),
            RainViewerFrame(200, "/v2/radar/b"),
        )
        coEvery { api.weatherMaps() } returns RainViewerMapsResponse(
            host = host,
            radar = RainViewerRadar(past = frames),
        )
        val fp = RadarViewport.footprintForVisibleBounds(51.0, 52.0, 4.0, 5.0)
        enqueuePng(frames.size * fp.tiles.size)

        val p = pipeline()
        p.loadForViewport(fp)
        val afterFirst = server.requestCount

        p.loadForViewport(fp)
        assertThat(server.requestCount).isEqualTo(afterFirst)
        assertThat(p.playbackFrames()).hasSize(2)
        assertThat(p.session.value?.playable).isTrue()
    }

    @Test
    fun timeline_matchesDisplayedReadyTimestamps() = runBlocking {
        val host = server.url("/").toString().trimEnd('/')
        val frames = listOf(
            RainViewerFrame(111, "/v2/radar/a"),
            RainViewerFrame(222, "/v2/radar/b"),
            RainViewerFrame(333, "/v2/radar/c"),
        )
        coEvery { api.weatherMaps() } returns RainViewerMapsResponse(
            host = host,
            radar = RainViewerRadar(past = frames),
        )
        val fp = RadarViewport.footprintForVisibleBounds(51.0, 52.0, 4.0, 5.0)
        enqueuePng(frames.size * fp.tiles.size)
        val p = pipeline()
        p.loadForViewport(fp)
        val playable = p.playbackFrames()
        assertThat(playable.map { it.time }).containsExactly(111L, 222L, 333L).inOrder()
    }
}
