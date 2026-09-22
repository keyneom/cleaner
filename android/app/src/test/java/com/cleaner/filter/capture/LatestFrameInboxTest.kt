package com.cleaner.filter.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LatestFrameInboxTest {
    @Test
    fun newerWaitingFrameReplacesOlderOne() {
        val discarded = mutableListOf<Int>()
        val inbox = LatestFrameInbox<Int> { discarded.add(it) }

        inbox.offer(1)
        inbox.offer(2)
        inbox.offer(3)

        assertEquals(listOf(1, 2), discarded)
        assertEquals(2L, inbox.droppedCount())
        assertEquals(3, inbox.take())
        inbox.close()
    }

    @Test
    fun frameBeingProcessedIsNotDropped() {
        val discarded = mutableListOf<Int>()
        val inbox = LatestFrameInbox<Int> { discarded.add(it) }
        inbox.offer(1)
        val inFlight = inbox.take()
        inbox.offer(2)
        inbox.offer(3)

        assertEquals(1, inFlight)
        assertEquals(listOf(2), discarded)
        assertEquals(3, inbox.take())
        inbox.close()
    }

    @Test
    fun takeWaitsForNextOffer() {
        val inbox = LatestFrameInbox<String> { }
        val result = AtomicReference<String?>()
        val started = CountDownLatch(1)
        val worker = Thread {
            started.countDown()
            result.set(inbox.take())
        }
        worker.start()
        started.await(1, TimeUnit.SECONDS)
        Thread.sleep(50)
        inbox.offer("latest")
        worker.join(1_000)
        assertEquals("latest", result.get())
        inbox.close()
    }

    @Test
    fun closeUnblocksTake() {
        val inbox = LatestFrameInbox<String> { }
        val result = AtomicReference("pending")
        val worker = Thread { result.set(inbox.take()) }
        worker.start()
        Thread.sleep(50)
        inbox.close()
        worker.join(1_000)
        assertNull(result.get())
    }
}
