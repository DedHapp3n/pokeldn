package com.dedhapp3n.pokeldn.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SerialReadQueueTest {
    @Test
    fun receivedChunkIsConsumedExactlyOnce() {
        val queue = SerialReadQueue()
        queue.offer(byteArrayOf(0x02, 0x8B.toByte(), 0x00))

        val first = ByteArray(8)
        assertEquals(3, queue.read(first, 10))
        assertArrayEquals(byteArrayOf(0x02, 0x8B.toByte(), 0x00), first.copyOf(3))

        val started = System.nanoTime()
        assertEquals(0, queue.read(ByteArray(8), 30))
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("empty reads must wait for their timeout", elapsedMillis >= 20)
    }

    @Test
    fun chunkLargerThanDestinationIsReturnedWithoutReplay() {
        val queue = SerialReadQueue()
        queue.offer(byteArrayOf(1, 2, 3, 4, 5))

        val first = ByteArray(3)
        val second = ByteArray(3)
        assertEquals(3, queue.read(first, 10))
        assertEquals(2, queue.read(second, 10))
        assertArrayEquals(byteArrayOf(1, 2, 3), first)
        assertArrayEquals(byteArrayOf(4, 5), second.copyOf(2))
        assertEquals(0, queue.read(ByteArray(3), 1))
    }

    @Test
    fun separatelyReceivedIdenticalChunksRemainSeparateReads() {
        val queue = SerialReadQueue()
        val credit = byteArrayOf(0x02, 0x8B.toByte(), 0x01, 0x00)
        queue.offer(credit)
        queue.offer(credit)

        val first = ByteArray(credit.size)
        val second = ByteArray(credit.size)
        assertEquals(credit.size, queue.read(first, 10))
        assertEquals(credit.size, queue.read(second, 10))
        assertArrayEquals(credit, first)
        assertArrayEquals(credit, second)
        assertEquals(0, queue.read(ByteArray(credit.size), 1))
    }

    @Test
    fun offeredArrayIsCopiedBeforeItCanBeReused() {
        val queue = SerialReadQueue()
        val source = byteArrayOf(1, 2, 3)
        queue.offer(source)
        source.fill(9)

        val destination = ByteArray(3)
        assertEquals(3, queue.read(destination, 10))
        assertArrayEquals(byteArrayOf(1, 2, 3), destination)
    }

    @Test(expected = IOException::class)
    fun receiveFailureWakesReaderAndPropagates() {
        val queue = SerialReadQueue()
        queue.fail(IllegalStateException("USB read failed"))

        queue.read(ByteArray(8), 10)
    }
}
