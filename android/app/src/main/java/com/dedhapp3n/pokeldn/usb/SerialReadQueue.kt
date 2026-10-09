package com.dedhapp3n.pokeldn.usb

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Bridges the serial library's blocking receive callback to bounded synchronous reads.
 * Each received chunk is removed from the queue exactly once.
 */
internal class SerialReadQueue {
    private sealed interface Event {
        data class Data(val bytes: ByteArray) : Event
        data class Failed(val error: IOException) : Event
    }

    private val events = LinkedBlockingQueue<Event>()
    private val closed = AtomicBoolean(false)
    private var pending: ByteArray? = null
    private var pendingOffset = 0

    fun offer(bytes: ByteArray) {
        if (bytes.isEmpty() || closed.get()) return
        events.offer(Event.Data(bytes.copyOf()))
    }

    fun fail(error: Exception) {
        close(IOException("Serial receive failed", error))
    }

    fun close(error: IOException = IOException("Serial port is not connected")) {
        if (!closed.compareAndSet(false, true)) return
        events.clear()
        events.offer(Event.Failed(error))
    }

    @Synchronized
    fun read(destination: ByteArray, timeoutMillis: Int): Int {
        require(destination.isNotEmpty()) { "Read buffer must not be empty" }
        require(timeoutMillis >= 0) { "Read timeout must not be negative" }

        while (pending == null) {
            val event = if (timeoutMillis == 0) {
                try {
                    events.take()
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Serial read interrupted", error)
                }
            } else {
                try {
                    events.poll(timeoutMillis.toLong(), TimeUnit.MILLISECONDS) ?: return 0
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Serial read interrupted", error)
                }
            }

            when (event) {
                is Event.Data -> {
                    pending = event.bytes
                    pendingOffset = 0
                }
                is Event.Failed -> {
                    events.offer(event)
                    throw event.error
                }
            }
        }

        val source = checkNotNull(pending)
        val count = min(destination.size, source.size - pendingOffset)
        source.copyInto(destination, endIndex = pendingOffset + count, startIndex = pendingOffset)
        pendingOffset += count
        if (pendingOffset == source.size) {
            pending = null
            pendingOffset = 0
        }
        return count
    }
}
