package com.discount152.aeolus.data.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Shared Open-Meteo coordination: bounded concurrency + 429 backoff.
 * Shared across forecast / ensemble / geocoding OkHttp clients.
 */
class OpenMeteoGate(
    maxConcurrent: Int = MAX_CONCURRENT,
) {
    private val semaphore = Semaphore(maxConcurrent, true)
    private val cooldownUntilMs = AtomicLong(0L)

    val maxConcurrent: Int = maxConcurrent

    fun isCoolingDown(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs < cooldownUntilMs.get()

    fun noteRateLimited(retryAfterSeconds: Long?) {
        val waitMs = when {
            retryAfterSeconds != null && retryAfterSeconds in 1..120 -> retryAfterSeconds * 1000L
            else -> 1_500L + ThreadLocalRandom.current().nextLong(0, 500)
        }
        val until = System.currentTimeMillis() + waitMs
        cooldownUntilMs.updateAndGet { cur -> maxOf(cur, until) }
    }

    fun interceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        if (!request.url.host.contains("open-meteo.com")) {
            return@Interceptor chain.proceed(request)
        }
        val call = chain.call()
        var attempt = 0
        while (attempt <= MAX_RETRIES) {
            if (call.isCanceled()) throw IOException("Canceled")
            val coolUntil = cooldownUntilMs.get()
            val now = System.currentTimeMillis()
            if (now < coolUntil) {
                // Sleep in short slices so cancelled/superseded work releases promptly.
                var left = coolUntil - now
                while (left > 0) {
                    if (call.isCanceled()) throw IOException("Canceled")
                    val slice = min(left, 100L)
                    Thread.sleep(slice)
                    left -= slice
                }
            }
            if (call.isCanceled()) throw IOException("Canceled")
            if (!semaphore.tryAcquire(15, TimeUnit.SECONDS)) {
                if (call.isCanceled()) throw IOException("Canceled")
                throw IOException("Open-Meteo gate acquire timeout")
            }
            val response: Response = try {
                if (call.isCanceled()) throw IOException("Canceled")
                chain.proceed(request)
            } finally {
                semaphore.release()
            }
            if (response.code != 429) {
                return@Interceptor response
            }
            val retryAfter = response.header("Retry-After")?.toLongOrNull()
            response.close()
            noteRateLimited(retryAfter)
            val backoff = min(4_000L, (1L shl attempt) * 1_000L) +
                ThreadLocalRandom.current().nextLong(0, 400)
            var left = backoff
            while (left > 0) {
                if (call.isCanceled()) throw IOException("Canceled")
                val slice = min(left, 100L)
                Thread.sleep(slice)
                left -= slice
            }
            attempt++
        }
        throw IOException("Open-Meteo HTTP 429 after $MAX_RETRIES retries")
    }

    companion object {
        const val MAX_CONCURRENT = 2
        const val MAX_RETRIES = 2
    }
}

/** Map-pin selection debounce window. */
const val MAP_PIN_DEBOUNCE_MS = 500L
