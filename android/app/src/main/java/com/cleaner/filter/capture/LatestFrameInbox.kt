package com.cleaner.filter.capture

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Holds at most one waiting frame. A newer offer discards the previous waiting frame.
 * The frame currently being classified is not in the inbox, so latency stays at one
 * inference instead of growing with a backlog.
 */
class LatestFrameInbox<T>(private val discard: (T) -> Unit) : AutoCloseable {
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private var latest: T? = null
    private var closed = false
    private val dropped = AtomicLong(0)

    fun offer(frame: T) {
        lock.withLock {
            if (closed) {
                discard(frame)
                return
            }
            latest?.let { previous ->
                discard(previous)
                dropped.incrementAndGet()
            }
            latest = frame
            arrived.signal()
        }
    }

    /** Blocks until a frame is available. Returns null when closed and empty. */
    fun take(): T? {
        lock.withLock {
            while (latest == null && !closed) {
                arrived.await()
            }
            val frame = latest
            latest = null
            return frame
        }
    }

    fun droppedCount(): Long = dropped.get()

    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            latest?.let { discard(it) }
            latest = null
            arrived.signalAll()
        }
    }
}
