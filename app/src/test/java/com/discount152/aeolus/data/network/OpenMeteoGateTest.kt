package com.discount152.aeolus.data.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OpenMeteoGateTest {
    @Test
    fun maxConcurrent_isTwo() {
        assertThat(OpenMeteoGate.MAX_CONCURRENT).isEqualTo(2)
        assertThat(OpenMeteoGate().maxConcurrent).isEqualTo(2)
    }

    @Test
    fun semaphorePolicy_neverExceedsTwoInFlight() {
        val sem = Semaphore(OpenMeteoGate.MAX_CONCURRENT, true)
        val inFlight = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)
        val latch = CountDownLatch(6)
        val pool = Executors.newFixedThreadPool(6)
        repeat(6) {
            pool.execute {
                sem.acquire()
                try {
                    val n = inFlight.incrementAndGet()
                    maxSeen.updateAndGet { cur -> maxOf(cur, n) }
                    Thread.sleep(40)
                } finally {
                    inFlight.decrementAndGet()
                    sem.release()
                    latch.countDown()
                }
            }
        }
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
        pool.shutdown()
        assertThat(maxSeen.get()).isAtMost(2)
    }

    @Test
    fun noteRateLimited_acceptsRetryAfterAndNull() {
        val gate = OpenMeteoGate()
        gate.noteRateLimited(5)
        gate.noteRateLimited(null)
    }
}
